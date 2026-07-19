#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly PROJECT_ROOT
readonly COMPOSE_FILE="$PROJECT_ROOT/docker-compose.load-test.yml"
readonly COMPOSE_PROJECT_NAME='coffee-point-order-load-test'
readonly RESULTS_ROOT="$PROJECT_ROOT/docs/load-test/results"
readonly BASE_URL="http://localhost:${LOAD_TEST_HTTP_PORT:-18080}"
readonly SEED_BALANCE=10000000
readonly OBSERVATION_INTERVAL_SECONDS=5
readonly RECOVERY_TIMEOUT_SECONDS=300

SCENARIO='all'
KEEP_ENVIRONMENT=false
ENVIRONMENT_STARTED=false
K6_PID=''
ANY_FAILURE=false

declare -a RUN_IDS=()
declare -a RUN_PASSES=()
declare -a RUN_RPS=()
declare -a RUN_P95=()
declare -a RUN_P99=()

usage() {
    cat <<'EOF'
사용법: ./scripts/load-test/invoke-load-test.sh [--scenario <name>] [--keep-environment]

시나리오: all, mixed, contention, redis-connection, redis-data-loss, kafka-recovery
EOF
}

fail() {
    echo "오류: $*" >&2
    exit 1
}

compose() {
    docker compose --project-name "$COMPOSE_PROJECT_NAME" --file "$COMPOSE_FILE" "$@"
}

mysql_query() {
    local query="$1"

    compose exec -T mysql mysql --batch --skip-column-names -ucoffee -pcoffee \
        coffee_point_order -e "$query"
}

mysql_scalar() {
    local query="$1"

    mysql_query "$query" | head -n 1 | tr -d '\r'
}

utc_now() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
}

utc_run_id() {
    date -u '+%Y%m%dT%H%M%SZ'
}

require_command() {
    local command_name="$1"

    command -v "$command_name" >/dev/null 2>&1 || fail "필수 명령을 찾을 수 없습니다: $command_name"
}

assert_dedicated_compose_file() {
    grep -Eq '^name:[[:space:]]*coffee-point-order-load-test[[:space:]]*$' "$COMPOSE_FILE" \
        || fail '전용 부하 테스트 Compose 프로젝트 이름을 확인할 수 없어 초기화를 중단했습니다.'
}

start_load_test_environment() {
    assert_dedicated_compose_file
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
    compose up --build --detach
    ENVIRONMENT_STARTED=true

    local deadline=$(( $(date +%s) + 240 ))
    while (( $(date +%s) < deadline )); do
        if mysql_query 'SELECT 1' >/dev/null 2>&1 \
            && curl --fail --silent --show-error --max-time 5 "$BASE_URL/api/v1/menus" >/dev/null; then
            return
        fi
        sleep 5
    done

    fail '전용 부하 테스트 환경이 4분 안에 준비되지 않았습니다.'
}

stop_load_test_environment() {
    assert_dedicated_compose_file
    compose down --volumes --remove-orphans >/dev/null
}

cleanup() {
    if [[ -n "$K6_PID" ]] && kill -0 "$K6_PID" >/dev/null 2>&1; then
        kill "$K6_PID" >/dev/null 2>&1 || true
        wait "$K6_PID" >/dev/null 2>&1 || true
    fi

    if [[ "$KEEP_ENVIRONMENT" == false && "$ENVIRONMENT_STARTED" == true ]]; then
        stop_load_test_environment || true
    fi
}

reset_load_test_data() {
    mysql_query "
DELETE FROM outbox_events;
DELETE FROM orders;
DELETE FROM point_accounts WHERE user_id LIKE 'load-%';
" >/dev/null

    local values=()
    local number
    for number in $(seq 1 60); do
        values+=("('load-mixed-$number', $SEED_BALANCE)")
    done
    for number in $(seq 1 30); do
        values+=("('load-redis-$number', $SEED_BALANCE)")
    done
    for number in $(seq 1 10); do
        values+=("('load-kafka-$number', $SEED_BALANCE)")
    done
    values+=("('load-contention', $SEED_BALANCE)")

    local joined_values
    joined_values=$(IFS=,; echo "${values[*]}")
    mysql_query "INSERT INTO point_accounts (user_id, balance) VALUES $joined_values;" >/dev/null
    compose exec -T redis redis-cli FLUSHALL >/dev/null
}

declare -A BASELINE_BALANCES=()

load_baseline_balances() {
    BASELINE_BALANCES=()
    local user_id balance
    while IFS=$'\t' read -r user_id balance; do
        [[ -n "$user_id" ]] || continue
        BASELINE_BALANCES["$user_id"]="$balance"
    done < <(mysql_query "
SELECT user_id, balance
FROM point_accounts
WHERE user_id LIKE 'load-%'
ORDER BY user_id;
")
}

pending_snapshot_json() {
    local row count oldest_created_at
    row=$(mysql_scalar "
SELECT CONCAT(
    COUNT(*), '|', COALESCE(DATE_FORMAT(MIN(created_at), '%Y-%m-%dT%H:%i:%s.%fZ'), '')
)
FROM outbox_events
WHERE status = 'PENDING';
")
    count=${row%%|*}
    oldest_created_at=${row#*|}
    jq -cn --argjson count "$count" --arg oldestCreatedAt "$oldest_created_at" \
        '{count: $count, oldestCreatedAt: $oldestCreatedAt}'
}

kafka_lag_snapshot_json() {
    local output line topic partition lag total_lag=0
    local partitions=()

    if ! output=$(compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server kafka:9092 --describe --group popular-menu 2>&1); then
        jq -cn --arg error "$output" '{partitionLag: [], totalLag: null, error: $error}'
        return
    fi

    while IFS= read -r line; do
        read -r -a columns <<<"$line"
        if [[ ${#columns[@]} -lt 6 ]]; then
            continue
        fi
        topic=${columns[1]}
        partition=${columns[2]}
        lag=${columns[5]}
        [[ "$topic" == 'order.completed' ]] || continue
        partitions+=("$(jq -cn --argjson partition "$partition" --argjson lag "$lag" \
            '{partition: $partition, lag: $lag}')")
        total_lag=$(( total_lag + lag ))
    done <<<"$output"

    local partitions_json
    partitions_json=$(printf '%s\n' "${partitions[@]:-}" | jq -cs '.')
    jq -cn --argjson partitionLag "$partitions_json" --argjson totalLag "$total_lag" \
        '{partitionLag: $partitionLag, totalLag: $totalLag, error: ""}'
}

container_stats_json() {
    local raw_containers container_lines container_id service stat cpu memory_percent memory_usage
    local items=()

    if ! raw_containers=$(compose ps --format json 2>/dev/null); then
        echo '[]'
        return
    fi

    if [[ "$raw_containers" == \[* ]]; then
        container_lines=$(jq -r '.[] | [.ID, .Service] | @tsv' <<<"$raw_containers")
    else
        container_lines=$(jq -r '[.ID, .Service] | @tsv' <<<"$raw_containers")
    fi

    while IFS=$'\t' read -r container_id service; do
        [[ -n "$container_id" ]] || continue
        stat=$(docker stats --no-stream --format '{{json .}}' "$container_id" 2>/dev/null || true)
        [[ -n "$stat" ]] || continue
        cpu=$(jq -r '.CPUPerc | sub("%$"; "")' <<<"$stat")
        memory_percent=$(jq -r '.MemPerc | sub("%$"; "")' <<<"$stat")
        memory_usage=$(jq -r '.MemUsage' <<<"$stat")
        items+=("$(jq -cn --arg container "$service" --argjson cpu "$cpu" \
            --argjson memory "$memory_percent" --arg memoryUsage "$memory_usage" \
            '{container: $container, cpuPercent: $cpu, memoryPercent: $memory, memoryUsage: $memoryUsage}')")
    done <<<"$container_lines"

    printf '%s\n' "${items[@]:-}" | jq -cs '.'
}

application_log_counts_json() {
    local since="$1"
    local logs initial_failures retry_failures

    logs=$(compose logs --no-color --since "$since" app-1 app-2 2>&1 || true)
    initial_failures=$(grep -cF '주문 완료 이벤트 발행에 실패했습니다.' <<<"$logs" || true)
    retry_failures=$(grep -cF 'PENDING Outbox 이벤트 재시도에 실패했습니다.' <<<"$logs" || true)
    jq -cn --argjson initial "$initial_failures" --argjson retry "$retry_failures" \
        '{initialPublishFailures: $initial, retryPublishFailures: $retry}'
}

add_observation() {
    local observation_path="$1"
    local log_since="$2"
    local pending kafka_lag containers log_counts

    pending=$(pending_snapshot_json)
    kafka_lag=$(kafka_lag_snapshot_json)
    containers=$(container_stats_json)
    log_counts=$(application_log_counts_json "$log_since")
    jq -cn --arg timestampUtc "$(utc_now)" --argjson pending "$pending" --argjson kafkaLag "$kafka_lag" \
        --argjson containers "$containers" --argjson publishFailureLogs "$log_counts" \
        '{timestampUtc: $timestampUtc, pending: $pending, kafkaLag: $kafkaLag, containers: $containers,
          publishFailureLogs: $publishFailureLogs}' >>"$observation_path"
}

declare -A FAULT_STATE=([stopped]=false [restored]=false [injected]=false)
declare -a FAULT_EVENTS=()

add_fault_event() {
    local action="$1"

    FAULT_EVENTS+=("$(utc_now)|$action")
}

inject_fault() {
    local scenario_name="$1"
    local started_epoch="$2"
    local elapsed=$(( $(date +%s) - started_epoch ))

    if [[ "$scenario_name" == 'redis-connection' ]]; then
        if [[ ${FAULT_STATE[stopped]} == false && $elapsed -ge 60 ]]; then
            compose stop redis >/dev/null
            FAULT_STATE[stopped]=true
            add_fault_event 'Redis 중단'
        fi
        if [[ ${FAULT_STATE[stopped]} == true && ${FAULT_STATE[restored]} == false && $elapsed -ge 120 ]]; then
            compose start redis >/dev/null
            FAULT_STATE[restored]=true
            add_fault_event 'Redis 복구'
        fi
    fi

    if [[ "$scenario_name" == 'redis-data-loss' && ${FAULT_STATE[injected]} == false && $elapsed -ge 60 ]]; then
        compose exec -T redis redis-cli FLUSHALL >/dev/null
        FAULT_STATE[injected]=true
        add_fault_event 'Redis FLUSHALL'
    fi

    if [[ "$scenario_name" == 'kafka-recovery' ]]; then
        if [[ ${FAULT_STATE[stopped]} == false && $elapsed -ge 60 ]]; then
            compose stop kafka >/dev/null
            FAULT_STATE[stopped]=true
            add_fault_event 'Kafka broker 중단'
        fi
        if [[ ${FAULT_STATE[stopped]} == true && ${FAULT_STATE[restored]} == false && $elapsed -ge 120 ]]; then
            compose start kafka >/dev/null
            FAULT_STATE[restored]=true
            add_fault_event 'Kafka broker 복구'
        fi
    fi
}

restore_faulted_service() {
    local scenario_name="$1"

    if [[ ${FAULT_STATE[stopped]} != true || ${FAULT_STATE[restored]} == true ]]; then
        return
    fi

    if [[ "$scenario_name" == redis-* ]]; then
        compose start redis >/dev/null
    else
        compose start kafka >/dev/null
    fi
    FAULT_STATE[restored]=true
}

start_k6_run() {
    local script_name="$1"
    local run_id="$2"
    local output_path="$3"
    shift 3
    local environment_arguments=("$@")
    local k6_log_path="$output_path/k6.log"

    compose run --rm -T "${environment_arguments[@]}" k6 run \
        "--summary-export=/results/$run_id/summary.json" \
        "--out=json=/results/$run_id/metrics.json" \
        "/scripts/$script_name" >"$k6_log_path" 2>&1 &
    K6_PID=$!
}

declare -A LEDGER_ORDERS=()
declare -A LEDGER_ORDER_AMOUNTS=()
declare -A LEDGER_CHARGE_AMOUNTS=()
declare -A STAGE_REQUESTS=([ten_vus]=0 [thirty_vus]=0 [sixty_vus]=0)
declare -A STAGE_P95=()
declare -A STAGE_RPS=()

add_ledger_value() {
    local map_name="$1"
    local user_id="$2"
    local value="$3"
    declare -n map="$map_name"

    map["$user_id"]=$(awk -v left="${map[$user_id]:-0}" -v right="$value" 'BEGIN { print left + right }')
}

percentile_from_file() {
    local value_path="$1"
    local percentile="$2"

    [[ -s "$value_path" ]] || {
        echo '0'
        return
    }
    sort -n "$value_path" | awk -v percentile="$percentile" '
        { values[NR] = $1 }
        END {
            index = int((NR * percentile) + 0.999999)
            if (index < 1) {
                index = 1
            }
            print values[index]
        }
    '
}

analyse_k6_metrics() {
    local metrics_path="$1"
    local output_path="$2"
    local metric value user_id scenario
    local stage

    LEDGER_ORDERS=()
    LEDGER_ORDER_AMOUNTS=()
    LEDGER_CHARGE_AMOUNTS=()
    STAGE_REQUESTS=([ten_vus]=0 [thirty_vus]=0 [sixty_vus]=0)
    STAGE_P95=()
    STAGE_RPS=()
    for stage in ten_vus thirty_vus sixty_vus; do
        : >"$output_path/$stage-duration-ms.txt"
    done

    while IFS=$'\t' read -r metric value user_id scenario; do
        case "$metric" in
            successful_orders)
                if [[ -n "$user_id" ]]; then
                    add_ledger_value LEDGER_ORDERS "$user_id" "$value"
                fi
                ;;
            successful_order_paid_amounts)
                if [[ -n "$user_id" ]]; then
                    add_ledger_value LEDGER_ORDER_AMOUNTS "$user_id" "$value"
                fi
                ;;
            successful_charge_amounts)
                if [[ -n "$user_id" ]]; then
                    add_ledger_value LEDGER_CHARGE_AMOUNTS "$user_id" "$value"
                fi
                ;;
            http_reqs)
                if [[ -n "${STAGE_REQUESTS[$scenario]+present}" ]]; then
                    STAGE_REQUESTS["$scenario"]=$(( STAGE_REQUESTS[$scenario] + 1 ))
                fi
                ;;
            http_req_duration)
                if [[ -n "${STAGE_REQUESTS[$scenario]+present}" ]]; then
                    echo "$value" >>"$output_path/$scenario-duration-ms.txt"
                fi
                ;;
        esac
    done < <(jq -r '
        select(.type == "Point")
        | [.metric, .data.value, (.data.tags.user_id // ""), (.data.tags.scenario // "")]
        | @tsv
    ' "$metrics_path")

    for stage in ten_vus thirty_vus sixty_vus; do
        STAGE_RPS["$stage"]=$(awk -v count="${STAGE_REQUESTS[$stage]}" 'BEGIN { printf "%.2f", count / 180 }')
        STAGE_P95["$stage"]=$(percentile_from_file "$output_path/$stage-duration-ms.txt" 0.95)
        rm -f "$output_path/$stage-duration-ms.txt"
    done
}

summary_value() {
    local summary_path="$1"
    local jq_path="$2"

    jq -r "$jq_path // 0" "$summary_path"
}

declare -A POST_RUN_ORDER_COUNTS=()
declare -A POST_RUN_ORDER_AMOUNTS=()

load_post_run_orders() {
    local last_order_id="$1"
    local user_id count amount

    POST_RUN_ORDER_COUNTS=()
    POST_RUN_ORDER_AMOUNTS=()
    while IFS=$'\t' read -r user_id count amount; do
        [[ -n "$user_id" ]] || continue
        POST_RUN_ORDER_COUNTS["$user_id"]="$count"
        POST_RUN_ORDER_AMOUNTS["$user_id"]="$amount"
    done < <(mysql_query "
SELECT user_id, COUNT(*), COALESCE(SUM(paid_amount), 0)
FROM orders
WHERE id > $last_order_id
GROUP BY user_id
ORDER BY user_id;
")
}

declare -a VALIDATION_NAMES=()
declare -a VALIDATION_PASSES=()
declare -a VALIDATION_DETAILS=()

add_validation() {
    local name="$1"
    local passed="$2"
    local details="$3"

    VALIDATION_NAMES+=("$name")
    VALIDATION_PASSES+=("$passed")
    VALIDATION_DETAILS+=("$details")
}

popular_menus_match_mysql() {
    local output_path="$1"
    local api_path="$output_path/popular-menu-api.json"
    local status expected_json

    status=$(curl --silent --show-error --max-time 10 --output "$api_path" --write-out '%{http_code}' \
        "$BASE_URL/api/v1/menus/popular" || true)
    [[ "$status" == '200' ]] || return 1
    expected_json=$(mysql_scalar "
SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
    'menuId', ranked.menu_id,
    'name', ranked.name,
    'price', ranked.price,
    'orderCount', ranked.order_count
)), JSON_ARRAY())
FROM (
    SELECT m.id AS menu_id, m.name, m.price, COUNT(o.id) AS order_count
    FROM orders o
    JOIN menus m ON m.id = o.menu_id
    WHERE o.ordered_at >= UTC_DATE() - INTERVAL 6 DAY
      AND o.ordered_at < UTC_DATE() + INTERVAL 1 DAY
    GROUP BY m.id, m.name, m.price
    ORDER BY order_count DESC, menu_id ASC
    LIMIT 3
) ranked;
")
    jq -e --argjson expected "$expected_json" '.code == "SUCCESS" and .data == $expected' "$api_path" >/dev/null
}

declare -a BOTTLENECK_CANDIDATES=()

add_bottleneck_candidates() {
    local observation_path="$1"
    local previous_stage current_stage previous_rps current_rps previous_p95 current_p95
    local throughput_increase p95_increase container resource candidate

    BOTTLENECK_CANDIDATES=()
    for previous_stage in ten_vus thirty_vus; do
        current_stage=thirty_vus
        if [[ "$previous_stage" == thirty_vus ]]; then
            current_stage=sixty_vus
        fi
        previous_rps=${STAGE_RPS[$previous_stage]}
        current_rps=${STAGE_RPS[$current_stage]}
        previous_p95=${STAGE_P95[$previous_stage]}
        current_p95=${STAGE_P95[$current_stage]}
        if awk -v value="$previous_rps" 'BEGIN { exit value == 0 }'; then
            throughput_increase=$(awk -v current="$current_rps" -v previous="$previous_rps" \
                'BEGIN { print (current - previous) * 100 / previous }')
            p95_increase=$(awk -v current="$current_p95" -v previous="$previous_p95" \
                'BEGIN { print previous == 0 ? 0 : (current - previous) * 100 / previous }')
            if awk -v throughput="$throughput_increase" -v p95="$p95_increase" \
                'BEGIN { exit !(throughput < 10 && p95 >= 50) }'; then
                candidate="$previous_stage에서 $current_stage: 처리량 $throughput_increase%"
                candidate+=", p95 $p95_increase%"
                BOTTLENECK_CANDIDATES+=("$candidate")
            fi
        fi
    done

    while IFS=$'\t' read -r container resource; do
        BOTTLENECK_CANDIDATES+=("$container 컨테이너의 $resource 사용량이 1분 이상 80% 이상")
    done < <(jq -r '
        .containers[]?
        | [.container, .cpuPercent, .memoryPercent]
        | @tsv
    ' "$observation_path" | awk -F '\t' '
        {
            if ($2 >= 80) {
                cpu[$1]++
            } else {
                cpu[$1] = 0
            }
            if ($3 >= 80) {
                memory[$1]++
            } else {
                memory[$1] = 0
            }
            if (cpu[$1] == 12 && !cpu_reported[$1]++) {
                print $1 "\tCPU"
            }
            if (memory[$1] == 12 && !memory_reported[$1]++) {
                print $1 "\t메모리"
            }
        }
    ')
}

wait_for_recovery() {
    local observation_path="$1"
    local log_since="$2"
    local deadline=$(( $(date +%s) + RECOVERY_TIMEOUT_SECONDS ))
    local observation

    while (( $(date +%s) < deadline )); do
        add_observation "$observation_path" "$log_since"
        observation=$(tail -n 1 "$observation_path")
        if jq -e '.pending.count == 0 and .kafkaLag.totalLag == 0' <<<"$observation" >/dev/null; then
            return 0
        fi
        sleep "$OBSERVATION_INTERVAL_SECONDS"
    done
    return 1
}

write_run_report() {
    local output_path="$1"
    local run_id="$2"
    local scenario_name="$3"
    local commit="$4"
    local k6_version="$5"
    local requests="$6"
    local requests_per_second="$7"
    local p50="$8"
    local p95="$9"
    local p99="${10}"
    local failed_rate="${11}"
    local summary_hash="${12}"
    local metrics_hash="${13}"
    local report_path="$output_path/report.md"
    local index status

    cat >"$report_path" <<EOF
# 부하 테스트 실행 보고서

## 실행 정보

| 항목 | 값 |
| --- | --- |
| 실행 식별자 | $run_id |
| 시나리오 | $scenario_name |
| Git commit | $commit |
| k6 버전 | $k6_version |
| 요청 주소 | $BASE_URL |
| 데이터 준비 | 전용 Compose의 \`load-*\` 포인트 계정을 $SEED_BALANCE point로 생성하고, 주문·Outbox를 비운 뒤 Redis를 초기화함 |
| 실행 명령 | \`./scripts/load-test/invoke-load-test.sh --scenario $scenario_name\` |

## 장애 주입 시각

EOF
    if [[ ${#FAULT_EVENTS[@]} -eq 0 ]]; then
        echo '- 없음' >>"$report_path"
    else
        for index in "${!FAULT_EVENTS[@]}"; do
            echo "- ${FAULT_EVENTS[$index]%%|*}: ${FAULT_EVENTS[$index]#*|}" >>"$report_path"
        done
    fi

    cat >>"$report_path" <<EOF

## k6 원본 요약

| 요청 수 | 초당 요청 수 | p50(ms) | p95(ms) | p99(ms) | http_req_failed |
| ---: | ---: | ---: | ---: | ---: | ---: |
| $requests | $requests_per_second | $p50 | $p95 | $p99 | $failed_rate |

## 혼합 흐름 VU 단계 비교

| 단계 | 초당 요청 수 | p95(ms) |
| --- | ---: | ---: |
| ten_vus | ${STAGE_RPS[ten_vus]} | ${STAGE_P95[ten_vus]} |
| thirty_vus | ${STAGE_RPS[thirty_vus]} | ${STAGE_P95[thirty_vus]} |
| sixty_vus | ${STAGE_RPS[sixty_vus]} | ${STAGE_P95[sixty_vus]} |

## 정합성·복구 판정

| 항목 | 판정 | 근거 |
| --- | --- | --- |
EOF
    for index in "${!VALIDATION_NAMES[@]}"; do
        status='실패'
        if [[ ${VALIDATION_PASSES[$index]} == true ]]; then
            status='통과'
        fi
        echo "| ${VALIDATION_NAMES[$index]} | $status | ${VALIDATION_DETAILS[$index]} |" >>"$report_path"
    done

    cat >>"$report_path" <<EOF

## 병목 후보

EOF
    if [[ ${#BOTTLENECK_CANDIDATES[@]} -eq 0 ]]; then
        echo '- 없음' >>"$report_path"
    else
        printf -- '- %s\n' "${BOTTLENECK_CANDIDATES[@]}" >>"$report_path"
    fi

    cat >>"$report_path" <<EOF

## 원본 파일 무결성

| 파일 | SHA-256 |
| --- | --- |
| \`summary.json\` | \`$summary_hash\` |
| \`metrics.json\` | \`$metrics_hash\` |

\`observations.ndjson\`에는 컨테이너 CPU·메모리, \`PENDING\`, consumer group partition별·합계 lag와 게시 실패 로그 수를 5초마다 기록한다.
EOF
}

median() {
    printf '%s\n' "$@" | sort -n | sed -n '2p'
}

maximum() {
    printf '%s\n' "$@" | sort -n | tail -n 1
}

write_aggregate_report() {
    local batch_id="$1"
    local scenario_name="$2"
    local report_path="$RESULTS_ROOT/$batch_id-$scenario_name-aggregate.md"
    local index status

    cat >"$report_path" <<EOF
# $scenario_name 3회 실행 집계

| 실행 | 초당 요청 수 | p95(ms) | p99(ms) | 판정 |
| --- | ---: | ---: | ---: | --- |
EOF
    for index in "${!RUN_IDS[@]}"; do
        status='실패'
        if [[ ${RUN_PASSES[$index]} == true ]]; then
            status='통과'
        fi
        echo "| ${RUN_IDS[$index]} | ${RUN_RPS[$index]} | ${RUN_P95[$index]} | ${RUN_P99[$index]} | $status |" \
            >>"$report_path"
    done

    cat >>"$report_path" <<EOF

| 지표 | 중앙값 | 최댓값 |
| --- | ---: | ---: |
| 초당 요청 수 | $(median "${RUN_RPS[@]}") | $(maximum "${RUN_RPS[@]}") |
| p95(ms) | $(median "${RUN_P95[@]}") | $(maximum "${RUN_P95[@]}") |
| p99(ms) | $(median "${RUN_P99[@]}") | $(maximum "${RUN_P99[@]}") |
EOF
}

run_scenario() {
    local scenario_name="$1"
    local run_number="$2"
    local commit="$3"
    local k6_version="$4"
    local script_name user_environment=()
    local last_order_id last_outbox_event_id run_id output_path observation_path started_at started_epoch
    local summary_path metrics_path k6_exit_code recovered requests requests_per_second p50 p95 p99 failed_rate
    local successful_orders actual_order_count actual_outbox_count published_outbox_count
    local initial_failures retry_failures
    local balance_matches=true order_amount_matches=true popular_matches=false passed=true user_id expected_balance
    local charge_amount order_amount expected_count expected_amount actual_balance summary_hash metrics_hash

    reset_load_test_data
    load_baseline_balances
    last_order_id=$(mysql_scalar 'SELECT COALESCE(MAX(id), 0) FROM orders;')
    last_outbox_event_id=$(mysql_scalar 'SELECT COALESCE(MAX(id), 0) FROM outbox_events;')
    run_id="$(utc_run_id)-$scenario_name-$run_number"
    output_path="$RESULTS_ROOT/$run_id"
    observation_path="$output_path/observations.ndjson"
    mkdir -p "$output_path"

    case "$scenario_name" in
        mixed)
            script_name='mixed-flow.js'
            user_environment=(-e USER_PREFIX=load-mixed -e USER_COUNT=60)
            ;;
        contention)
            script_name='same-user-contention.js'
            user_environment=(-e CONTENTION_USER_ID=load-contention)
            ;;
        redis-connection|redis-data-loss)
            script_name='redis-recovery.js'
            ;;
        kafka-recovery)
            script_name='kafka-recovery.js'
            user_environment=(-e USER_PREFIX=load-kafka -e USER_COUNT=10)
            ;;
        *)
            fail "알 수 없는 시나리오입니다: $scenario_name"
            ;;
    esac

    FAULT_STATE=([stopped]=false [restored]=false [injected]=false)
    FAULT_EVENTS=()
    VALIDATION_NAMES=()
    VALIDATION_PASSES=()
    VALIDATION_DETAILS=()
    started_at=$(utc_now)
    started_epoch=$(date +%s)
    add_observation "$observation_path" "$started_at"
    start_k6_run "$script_name" "$run_id" "$output_path" \
        -e K6_BASE_URL=http://load-balancer:8080 \
        -e ORDER_MENU_ID=1 \
        -e POINT_CHARGE_AMOUNT=5000 \
        "${user_environment[@]}"

    while kill -0 "$K6_PID" >/dev/null 2>&1; do
        inject_fault "$scenario_name" "$started_epoch"
        add_observation "$observation_path" "$started_at"
        sleep "$OBSERVATION_INTERVAL_SECONDS"
    done
    if wait "$K6_PID"; then
        k6_exit_code=0
    else
        k6_exit_code=$?
    fi
    K6_PID=''
    restore_faulted_service "$scenario_name"

    [[ $k6_exit_code -eq 0 ]] || fail "k6 실행이 실패했습니다. 로그: $output_path/k6.log"

    if wait_for_recovery "$observation_path" "$started_at"; then
        recovered=true
    else
        recovered=false
    fi

    summary_path="$output_path/summary.json"
    metrics_path="$output_path/metrics.json"
    analyse_k6_metrics "$metrics_path" "$output_path"
    requests=$(summary_value "$summary_path" '.metrics.http_reqs.values.count')
    requests_per_second=$(summary_value "$summary_path" '.metrics.http_reqs.values.rate')
    p50=$(summary_value "$summary_path" '.metrics.http_req_duration.values["p(50)"]')
    p95=$(summary_value "$summary_path" '.metrics.http_req_duration.values["p(95)"]')
    p99=$(summary_value "$summary_path" '.metrics.http_req_duration.values["p(99)"]')
    failed_rate=$(summary_value "$summary_path" '.metrics.http_req_failed.values.rate')
    successful_orders=$(summary_value "$summary_path" '.metrics.successful_orders.values.count')
    actual_order_count=$(mysql_scalar "SELECT COUNT(*) FROM orders WHERE id > $last_order_id;")
    actual_outbox_count=$(mysql_scalar "SELECT COUNT(*) FROM outbox_events WHERE id > $last_outbox_event_id;")
    published_outbox_count=$(mysql_scalar "
SELECT COUNT(*)
FROM outbox_events
WHERE id > $last_outbox_event_id
  AND status = 'PUBLISHED';
")
    load_post_run_orders "$last_order_id"
    initial_failures=$(application_log_counts_json "$started_at" | jq -r '.initialPublishFailures')
    retry_failures=$(application_log_counts_json "$started_at" | jq -r '.retryPublishFailures')

    if awk -v value="$failed_rate" 'BEGIN { exit value != 0 }'; then
        add_validation '유효 요청의 비-2xx 응답 없음' true "http_req_failed=$failed_rate"
    else
        add_validation '유효 요청의 비-2xx 응답 없음' false "http_req_failed=$failed_rate"
    fi
    if [[ "$successful_orders" == "$actual_order_count" ]]; then
        add_validation '성공 주문 수와 새 orders 행 수 일치' true \
            "k6=$successful_orders, orders=$actual_order_count"
    else
        add_validation '성공 주문 수와 새 orders 행 수 일치' false \
            "k6=$successful_orders, orders=$actual_order_count"
    fi
    if [[ "$actual_order_count" == "$actual_outbox_count" ]]; then
        add_validation '새 orders와 Outbox 행 수 일치' true \
            "orders=$actual_order_count, outbox=$actual_outbox_count"
    else
        add_validation '새 orders와 Outbox 행 수 일치' false \
            "orders=$actual_order_count, outbox=$actual_outbox_count"
    fi
    if [[ "$published_outbox_count" == "$actual_outbox_count" ]]; then
        add_validation '모든 새 Outbox가 PUBLISHED' true \
            "published=$published_outbox_count, outbox=$actual_outbox_count"
    else
        add_validation '모든 새 Outbox가 PUBLISHED' false \
            "published=$published_outbox_count, outbox=$actual_outbox_count"
    fi
    add_validation 'PENDING과 consumer lag 5분 내 해소' "$recovered" \
        "최대 대기=$RECOVERY_TIMEOUT_SECONDS 초"

    while IFS=$'\t' read -r user_id actual_balance; do
        [[ -n "$user_id" ]] || continue
        charge_amount=${LEDGER_CHARGE_AMOUNTS[$user_id]:-0}
        order_amount=${LEDGER_ORDER_AMOUNTS[$user_id]:-0}
        expected_balance=$(awk -v baseline="${BASELINE_BALANCES[$user_id]}" \
            -v charge="$charge_amount" -v order="$order_amount" 'BEGIN { print baseline + charge - order }')
        if [[ "$actual_balance" != "$expected_balance" ]]; then
            balance_matches=false
            break
        fi
    done < <(mysql_query "
SELECT user_id, balance
FROM point_accounts
WHERE user_id LIKE 'load-%'
ORDER BY user_id;
")
    add_validation '사용자별 포인트 잔액 정합성' "$balance_matches" \
        '성공 충전액 합계 - 성공 주문 결제액을 각 시작 잔액과 대조'

    for user_id in "${!POST_RUN_ORDER_COUNTS[@]}"; do
        expected_count=${LEDGER_ORDERS[$user_id]:-0}
        expected_amount=${LEDGER_ORDER_AMOUNTS[$user_id]:-0}
        if [[ ${POST_RUN_ORDER_COUNTS[$user_id]} != "$expected_count" \
            || ${POST_RUN_ORDER_AMOUNTS[$user_id]} != "$expected_amount" ]]; then
            order_amount_matches=false
            break
        fi
    done
    add_validation '사용자별 성공 주문 금액 정합성' "$order_amount_matches" \
        'k6 성공 주문 계수와 MySQL paid_amount 합계를 대조'

    if popular_menus_match_mysql "$output_path"; then
        popular_matches=true
    fi
    add_validation '인기 메뉴 API와 MySQL Top 3 일치' "$popular_matches" \
        'lag 0 이후 최근 7개 UTC 날짜 집계와 대조'

    if [[ "$scenario_name" == 'kafka-recovery' ]]; then
        if [[ $initial_failures -gt 0 ]]; then
            add_validation 'Kafka 장애 중 최초 발행 실패 로그 확인' true "count=$initial_failures"
        else
            add_validation 'Kafka 장애 중 최초 발행 실패 로그 확인' false "count=$initial_failures"
        fi
        if [[ $retry_failures -gt 0 ]]; then
            add_validation 'Kafka 장애 중 재시도 실패 로그 확인' true "count=$retry_failures"
        else
            add_validation 'Kafka 장애 중 재시도 실패 로그 확인' false "count=$retry_failures"
        fi
    else
        if [[ $initial_failures -eq 0 && $retry_failures -eq 0 ]]; then
            add_validation '의도하지 않은 Kafka 게시 실패 로그 없음' true \
                "initial=$initial_failures, retry=$retry_failures"
        else
            add_validation '의도하지 않은 Kafka 게시 실패 로그 없음' false \
                "initial=$initial_failures, retry=$retry_failures"
        fi
    fi

    add_bottleneck_candidates "$observation_path"
    summary_hash=$(sha256sum "$summary_path" | awk '{print $1}')
    metrics_hash=$(sha256sum "$metrics_path" | awk '{print $1}')
    write_run_report "$output_path" "$run_id" "$scenario_name" "$commit" "$k6_version" \
        "$requests" "$requests_per_second" "$p50" "$p95" "$p99" "$failed_rate" \
        "$summary_hash" "$metrics_hash"

    for expected_count in "${VALIDATION_PASSES[@]}"; do
        if [[ "$expected_count" != true ]]; then
            passed=false
            ANY_FAILURE=true
            break
        fi
    done

    RUN_IDS+=("$run_id")
    RUN_PASSES+=("$passed")
    RUN_RPS+=("$requests_per_second")
    RUN_P95+=("$p95")
    RUN_P99+=("$p99")
}

parse_arguments() {
    while (( $# > 0 )); do
        case "$1" in
            --scenario)
                (( $# >= 2 )) || fail '--scenario 값이 필요합니다.'
                SCENARIO="$2"
                shift 2
                ;;
            --keep-environment)
                KEEP_ENVIRONMENT=true
                shift
                ;;
            --help|-h)
                usage
                exit 0
                ;;
            *)
                fail "알 수 없는 옵션입니다: $1"
                ;;
        esac
    done

    case "$SCENARIO" in
        all|mixed|contention|redis-connection|redis-data-loss|kafka-recovery)
            ;;
        *)
            fail "알 수 없는 시나리오입니다: $SCENARIO"
            ;;
    esac
}

main() {
    parse_arguments "$@"
    require_command docker
    require_command curl
    require_command git
    require_command jq
    require_command sha256sum
    assert_dedicated_compose_file
    mkdir -p "$RESULTS_ROOT"

    local commit k6_version batch_id scenario_name run_number
    commit=$(git -C "$PROJECT_ROOT" rev-parse HEAD)
    start_load_test_environment
    k6_version=$(compose run --rm -T k6 version | tr '\n' ' ')
    batch_id=$(utc_run_id)

    local scenarios=()
    if [[ "$SCENARIO" == all ]]; then
        scenarios=(mixed contention redis-connection redis-data-loss kafka-recovery)
    else
        scenarios=("$SCENARIO")
    fi

    for scenario_name in "${scenarios[@]}"; do
        RUN_IDS=()
        RUN_PASSES=()
        RUN_RPS=()
        RUN_P95=()
        RUN_P99=()
        for run_number in 1 2 3; do
            run_scenario "$scenario_name" "$run_number" "$commit" "$k6_version"
        done
        write_aggregate_report "$batch_id" "$scenario_name"
    done

    [[ "$ANY_FAILURE" == false ]] || fail "부하 테스트 판정 실패가 있습니다. 결과 경로: $RESULTS_ROOT"
}

trap cleanup EXIT
main "$@"
