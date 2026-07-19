#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly PROJECT_ROOT
readonly COMPOSE_FILE="$PROJECT_ROOT/docker-compose.load-test.yml"
readonly COMPOSE_PROJECT_NAME='coffee-point-order-load-test'
readonly RESULTS_ROOT="${LOAD_TEST_RESULTS_ROOT:-$PROJECT_ROOT/docs/load-test/results}"
readonly BASE_URL="http://localhost:${LOAD_TEST_HTTP_PORT:-18080}"
readonly SEED_BALANCE=10000000
readonly OBSERVATION_INTERVAL_SECONDS=5
readonly RECOVERY_TIMEOUT_SECONDS=300
readonly CONSUMER_SCALING_ITERATIONS="${CONSUMER_SCALING_ITERATIONS:-600}"

SCENARIO='all'
KEEP_ENVIRONMENT=false
ENVIRONMENT_STARTED=false
K6_PID=''
ANY_FAILURE=false
WORK_RESULTS_DIRECTORY=''

declare -a RUN_IDS=()
declare -a RUN_PASSES=()
declare -a RUN_RPS=()
declare -a RUN_P95=()
declare -a RUN_P99=()
declare -a RUN_END_TO_END_COMPLETION_SECONDS=()
declare -a RUN_POST_LOAD_LAG_ZERO_SECONDS=()
declare -a RUN_END_TO_END_THROUGHPUT=()
declare -a SCALING_CONSUMER_COUNTS=()
declare -a SCALING_MEDIAN_END_TO_END_COMPLETION_SECONDS=()
declare -a SCALING_MEDIAN_POST_LOAD_LAG_ZERO_SECONDS=()
declare -a SCALING_MEDIAN_END_TO_END_THROUGHPUT=()
declare -a SCALING_MAX_END_TO_END_THROUGHPUT=()
declare -a SCALING_PASSES=()
declare -a ENVIRONMENT_SERVICES=(mysql redis kafka app-1 app-2 load-balancer k6)
declare -A ENVIRONMENT_IMAGE_REFERENCES=()
declare -A ENVIRONMENT_IMAGE_IDS=()

DOCKER_ENGINE_VERSION=''
DOCKER_COMPOSE_VERSION=''

usage() {
    cat <<'EOF'
사용법: ./scripts/load-test/invoke-load-test.sh [--scenario <name>] [--keep-environment]

시나리오: all, mixed, contention, redis-connection, redis-data-loss, kafka-recovery, consumer-scaling
EOF
}

fail() {
    echo "오류: $*" >&2
    exit 1
}

compose() {
    if [[ "$OSTYPE" == msys* ]]; then
        MSYS_NO_PATHCONV=1 docker compose --project-name "$COMPOSE_PROJECT_NAME" \
            --file "$(cygpath -w "$COMPOSE_FILE")" "$@"
        return
    fi

    docker compose --project-name "$COMPOSE_PROJECT_NAME" --file "$COMPOSE_FILE" "$@"
}

capture_service_image_metadata() {
    local service_name="$1"
    local container_id

    container_id=$(compose ps --all --quiet "$service_name")
    [[ -n "$container_id" ]] || fail "이미지 정보를 찾을 수 없는 서비스입니다: $service_name"
    ENVIRONMENT_IMAGE_REFERENCES["$service_name"]=$(docker inspect --format '{{.Config.Image}}' "$container_id")
    ENVIRONMENT_IMAGE_IDS["$service_name"]=$(docker inspect --format '{{.Image}}' "$container_id")
}

capture_environment_metadata() {
    local service_name

    DOCKER_ENGINE_VERSION=$(docker version --format '{{.Server.Version}}')
    DOCKER_COMPOSE_VERSION=$(docker compose version --short)
    compose create k6 >/dev/null
    for service_name in "${ENVIRONMENT_SERVICES[@]}"; do
        capture_service_image_metadata "$service_name"
    done
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

    if [[ -n "$WORK_RESULTS_DIRECTORY" && -d "$WORK_RESULTS_DIRECTORY" ]]; then
        rm -rf "$WORK_RESULTS_DIRECTORY"
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
    for number in $(seq 1 30); do
        values+=("('load-scaling-$number', $SEED_BALANCE)")
    done
    values+=("('load-contention', $SEED_BALANCE)")

    local joined_values
    joined_values=$(IFS=,; echo "${values[*]}")
    mysql_query "INSERT INTO point_accounts (user_id, balance) VALUES $joined_values;" >/dev/null
    compose exec -T redis redis-cli FLUSHALL >/dev/null
}

seed_redis_popular_menu_data() {
    mysql_query "
INSERT INTO orders (user_id, menu_id, paid_amount, ordered_at)
VALUES
    ('load-redis-1', 1, 4500, UTC_TIMESTAMP(6)),
    ('load-redis-2', 1, 4500, UTC_TIMESTAMP(6)),
    ('load-redis-3', 1, 4500, UTC_TIMESTAMP(6)),
    ('load-redis-4', 2, 5000, UTC_TIMESTAMP(6)),
    ('load-redis-5', 2, 5000, UTC_TIMESTAMP(6));
" >/dev/null
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
    local consumer_group_id="${1:-popular-menu}"
    local output line topic partition lag total_lag=0
    local partitions=()

    if ! output=$(compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server kafka:9092 --describe --group "$consumer_group_id" 2>&1); then
        jq -cn --arg groupId "$consumer_group_id" --arg error "$output" \
            '{groupId: $groupId, partitionLag: [], totalLag: null, error: $error}'
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
    jq -cn --arg groupId "$consumer_group_id" --argjson partitionLag "$partitions_json" \
        --argjson totalLag "$total_lag" \
        '{groupId: $groupId, partitionLag: $partitionLag, totalLag: $totalLag, error: ""}'
}

order_completed_partition_count() {
    local output partition_count

    if ! output=$(compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server kafka:9092 --describe --topic order.completed 2>&1); then
        echo '0'
        return
    fi
    partition_count=$(sed -n 's/.*PartitionCount:\([0-9][0-9]*\).*/\1/p' <<<"$output" | head -n 1)
    echo "${partition_count:-0}"
}

consumer_assignment_json() {
    local consumer_group_id="$1"
    local output line consumer_id assignment partition_text
    local members=()
    local active_consumer_count=0
    local assigned_partition_count=0

    if ! output=$(compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server kafka:9092 --describe --group "$consumer_group_id" --members --verbose 2>&1); then
        jq -cn --arg error "$output" \
            '{members: [], activeConsumerCount: 0, assignedPartitionCount: 0, error: $error}'
        return
    fi

    while IFS= read -r line; do
        local columns=()
        local partitions=()

        read -r -a columns <<<"$line"
        if [[ ${#columns[@]} -lt 6 || ${columns[0]} == 'GROUP' ]]; then
            continue
        fi
        assignment=${columns[5]}
        consumer_id=${columns[1]}
        if [[ "$assignment" == *'order.completed('* ]]; then
            partition_text=${assignment#*order.completed(}
            partition_text=${partition_text%%)*}
        else
            partition_text=${assignment#(}
            partition_text=${partition_text%)}
        fi
        if [[ -n "$partition_text" ]]; then
            IFS=',' read -r -a partitions <<<"$partition_text"
        fi

        members+=("$(printf '%s\n' "${partitions[@]}" | jq -Rsc 'split("\n") | map(select(length > 0) | tonumber)' \
            | jq -c --arg consumerId "$consumer_id" '{consumerId: $consumerId, partitions: .}')")
        active_consumer_count=$(( active_consumer_count + 1 ))
        assigned_partition_count=$(( assigned_partition_count + ${#partitions[@]} ))
    done <<<"$output"

    local members_json
    members_json=$(printf '%s\n' "${members[@]:-}" | jq -cs '.')
    jq -cn --argjson members "$members_json" --argjson activeConsumerCount "$active_consumer_count" \
        --argjson assignedPartitionCount "$assigned_partition_count" \
        '{members: $members, activeConsumerCount: $activeConsumerCount,
          assignedPartitionCount: $assignedPartitionCount, error: ""}'
}

wait_for_consumer_assignment() {
    local consumer_group_id="$1"
    local expected_consumer_count="$2"
    local deadline_epoch=$(( $(date +%s) + 120 ))
    local assignment

    while (( $(date +%s) < deadline_epoch )); do
        assignment=$(consumer_assignment_json "$consumer_group_id")
        if jq -e --argjson expected "$expected_consumer_count" '
            .error == ""
            and .activeConsumerCount == $expected
            and .assignedPartitionCount == 3
        ' <<<"$assignment" >/dev/null; then
            echo "$assignment"
            return
        fi
        sleep 1
    done

    fail "Consumer Group 파티션 할당이 2분 안에 완료되지 않았습니다: $consumer_group_id" \
        "마지막 관측=$assignment"
}

restart_consumer_group() {
    local consumer_group_id="$1"
    local consumer_count="$2"

    LOAD_TEST_APP_1_CONSUMER_CONCURRENCY="$consumer_count" \
    LOAD_TEST_APP_1_CONSUMER_ENABLED=true \
    LOAD_TEST_APP_2_CONSUMER_CONCURRENCY=1 \
    LOAD_TEST_APP_2_CONSUMER_ENABLED=false \
    LOAD_TEST_CONSUMER_GROUP_ID="$consumer_group_id" \
    compose up --force-recreate --no-deps --detach app-1 app-2
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
        cpu=$(jq -r 'try (.CPUPerc | sub("%$"; "") | tonumber) catch null' <<<"$stat")
        memory_percent=$(jq -r 'try (.MemPerc | sub("%$"; "") | tonumber) catch null' <<<"$stat")
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
    initial_failures=$(printf '%s\n' "$logs" | grep -cF '주문 완료 이벤트 발행에 실패했습니다.' || true)
    retry_failures=$(printf '%s\n' "$logs" | grep -cF 'PENDING Outbox 이벤트 재시도에 실패했습니다.' || true)
    jq -cn --argjson initial "$initial_failures" --argjson retry "$retry_failures" \
        '{initialPublishFailures: $initial, retryPublishFailures: $retry}'
}

add_observation() {
    local observation_path="$1"
    local log_since="$2"
    local consumer_group_id="${3:-popular-menu}"
    local configured_consumer_count="${4:-null}"
    local pending
    local kafka_lag
    local consumer_assignment
    local containers
    local log_counts

    pending=$(pending_snapshot_json)
    kafka_lag=$(kafka_lag_snapshot_json "$consumer_group_id")
    consumer_assignment=$(consumer_assignment_json "$consumer_group_id")
    containers=$(container_stats_json)
    log_counts=$(application_log_counts_json "$log_since")
    jq -cn --arg timestampUtc "$(utc_now)" --argjson pending "$pending" --argjson kafkaLag "$kafka_lag" \
        --argjson consumerAssignment "$consumer_assignment" \
        --argjson configuredConsumerCount "$configured_consumer_count" \
        --argjson containers "$containers" --argjson publishFailureLogs "$log_counts" \
        '{timestampUtc: $timestampUtc, pending: $pending, kafkaLag: $kafkaLag,
          consumerAssignment: $consumerAssignment, configuredConsumerCount: $configuredConsumerCount,
          containers: $containers,
          publishFailureLogs: $publishFailureLogs}' >>"$observation_path"
}

declare -A FAULT_STATE=([stopped]=false [restored]=false [injected]=false)
declare -a FAULT_EVENTS=()

FAULT_RECOVERY_AT=''
RECOVERY_DEADLINE_EPOCH=0
RECOVERY_OBSERVED_AT=''
KAFKA_FAULT_STARTED_AT=''
KAFKA_BASELINE_CAPTURED=false
KAFKA_BASELINE_PENDING=0
KAFKA_BASELINE_LAG=0
KAFKA_MAX_PENDING=0
KAFKA_MAX_LAG=0

add_fault_event() {
    local action="$1"
    local occurred_at="${2:-$(utc_now)}"

    FAULT_EVENTS+=("$occurred_at|$action")
}

record_fault_recovery() {
    FAULT_RECOVERY_AT=$(utc_now)
    RECOVERY_DEADLINE_EPOCH=$(( $(date +%s) + RECOVERY_TIMEOUT_SECONDS ))
}

capture_kafka_fault_baseline() {
    local pending kafka_lag

    KAFKA_FAULT_STARTED_AT=$(utc_now)
    pending=$(pending_snapshot_json)
    kafka_lag=$(kafka_lag_snapshot_json)
    KAFKA_BASELINE_PENDING=$(jq -r '.count' <<<"$pending")
    if jq -e '.totalLag | type == "number"' <<<"$kafka_lag" >/dev/null; then
        KAFKA_BASELINE_LAG=$(jq -r '.totalLag' <<<"$kafka_lag")
        KAFKA_BASELINE_CAPTURED=true
        return
    fi

    KAFKA_BASELINE_LAG=0
    KAFKA_BASELINE_CAPTURED=false
}

load_kafka_fault_maxima() {
    local observation_path="$1"

    KAFKA_MAX_PENDING=0
    KAFKA_MAX_LAG=0
    [[ -n "$KAFKA_FAULT_STARTED_AT" ]] || return
    KAFKA_MAX_PENDING=$(jq -s --arg from "$KAFKA_FAULT_STARTED_AT" '
        [.[] | select(.timestampUtc >= $from) | .pending.count] | max // 0
    ' "$observation_path")
    KAFKA_MAX_LAG=$(jq -s --arg from "$KAFKA_FAULT_STARTED_AT" '
        [.[] | select(.timestampUtc >= $from) | .kafkaLag.totalLag | select(type == "number")] | max // 0
    ' "$observation_path")
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
            record_fault_recovery
            FAULT_STATE[restored]=true
            add_fault_event 'Redis 복구' "$FAULT_RECOVERY_AT"
        fi
    fi

    if [[ "$scenario_name" == 'redis-data-loss' && ${FAULT_STATE[injected]} == false && $elapsed -ge 60 ]]; then
        compose exec -T redis redis-cli FLUSHALL >/dev/null
        FAULT_STATE[injected]=true
        add_fault_event 'Redis FLUSHALL'
    fi

    if [[ "$scenario_name" == 'kafka-recovery' ]]; then
        if [[ ${FAULT_STATE[stopped]} == false && $elapsed -ge 60 ]]; then
            capture_kafka_fault_baseline
            compose stop kafka >/dev/null
            FAULT_STATE[stopped]=true
            add_fault_event 'Kafka broker 중단'
        fi
        if [[ ${FAULT_STATE[stopped]} == true && ${FAULT_STATE[restored]} == false && $elapsed -ge 120 ]]; then
            compose start kafka >/dev/null
            record_fault_recovery
            FAULT_STATE[restored]=true
            add_fault_event 'Kafka broker 복구' "$FAULT_RECOVERY_AT"
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
        record_fault_recovery
        add_fault_event 'Redis 복구(k6 종료 후)' "$FAULT_RECOVERY_AT"
    else
        compose start kafka >/dev/null
        record_fault_recovery
        add_fault_event 'Kafka broker 복구(k6 종료 후)' "$FAULT_RECOVERY_AT"
    fi
    FAULT_STATE[restored]=true
}

start_k6_run() {
    local script_name="$1"
    local output_path="$2"
    local results_relative_path
    shift 2
    local environment_arguments=("$@")
    local k6_log_path="$output_path/k6.log"

    results_relative_path=${output_path#"$RESULTS_ROOT"/}
    [[ "$results_relative_path" != "$output_path" ]] \
        || fail "중간 결과 경로가 결과 루트 밖에 있습니다: $output_path"

    compose run --rm -T "${environment_arguments[@]}" k6 run \
        "--summary-export=/results/$results_relative_path/summary.json" \
        "--out=json=/results/$results_relative_path/metrics.json" \
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
    local value fallback_jq_path

    value=$(jq -r "$jq_path // empty" "$summary_path")
    if [[ -n "$value" ]]; then
        echo "$value"
        return
    fi

    fallback_jq_path=${jq_path/.values/}
    jq -r "$fallback_jq_path // 0" "$summary_path"
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

popular_menu_expected_json() {
    mysql_scalar "
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
"
}

popular_menus_match_mysql() {
    local output_path="$1"
    local api_path="$output_path/popular-menu-api.json"
    local status expected_json

    status=$(curl --silent --show-error --max-time 10 --output "$api_path" --write-out '%{http_code}' \
        "$BASE_URL/api/v1/menus/popular" || true)
    [[ "$status" == '200' ]] || return 1
    expected_json=$(popular_menu_expected_json)
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
    local deadline_epoch="$3"
    local consumer_group_id="${4:-popular-menu}"
    local configured_consumer_count="${5:-null}"
    local observation now remaining_seconds sleep_seconds

    while true; do
        add_observation "$observation_path" "$log_since" "$consumer_group_id" "$configured_consumer_count"
        observation=$(tail -n 1 "$observation_path")
        if jq -e '.pending.count == 0 and .kafkaLag.totalLag == 0' <<<"$observation" >/dev/null; then
            RECOVERY_OBSERVED_AT=$(utc_now)
            return 0
        fi

        now=$(date +%s)
        if (( now >= deadline_epoch )); then
            return 1
        fi
        remaining_seconds=$(( deadline_epoch - now ))
        sleep_seconds=$OBSERVATION_INTERVAL_SECONDS
        if (( remaining_seconds < sleep_seconds )); then
            sleep_seconds=$remaining_seconds
        fi
        sleep "$sleep_seconds"
    done
}

kafka_lag_measurements() {
    local observation_path="$1"

    jq -sr '
        [.[] | .kafkaLag.totalLag | select(type == "number")] as $lags
        | if ($lags | length) == 0 then
              "0\\t0"
          else
              "\($lags | max)\\t\($lags | last)"
          end
    ' "$observation_path"
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
    local completion_rate="${12}"
    local consumer_count="${13}"
    local consumer_group_id="${14}"
    local consumer_assignment="${15}"
    local maximum_kafka_lag="${16}"
    local final_kafka_lag="${17}"
    local post_load_lag_zero_seconds="${18}"
    local end_to_end_completion_seconds="${19}"
    local end_to_end_throughput="${20}"
    local report_path="$output_path/report.md"
    local index status service_name data_preparation active_consumer_count assigned_partition_count
    local execution_command

    if [[ "$scenario_name" == redis-* ]]; then
        data_preparation="전용 Compose의 load-* 포인트 계정을 $SEED_BALANCE point로 생성하고, 주문·Outbox를 비운 뒤"
        data_preparation+=' MySQL 원본 집계용 고정 주문과 Redis를 초기화함'
    else
        data_preparation="전용 Compose의 load-* 포인트 계정을 $SEED_BALANCE point로 생성하고, 주문·Outbox와 Redis를 초기화함"
    fi
    execution_command="bash ./scripts/load-test/invoke-load-test.sh --scenario $scenario_name"
    if [[ "$scenario_name" == 'consumer-scaling' ]]; then
        execution_command="CONSUMER_SCALING_ITERATIONS=$CONSUMER_SCALING_ITERATIONS $execution_command"
    fi

    cat >"$report_path" <<EOF
## 실행 결과: $run_id

## 실행 정보

| 항목 | 값 |
| --- | --- |
| 실행 식별자 | $run_id |
| 시나리오 | $scenario_name |
| Git commit | $commit |
| k6 버전 | $k6_version |
| Docker Engine 버전 | $DOCKER_ENGINE_VERSION |
| Docker Compose 버전 | $DOCKER_COMPOSE_VERSION |
| 요청 주소 | $BASE_URL |
| 데이터 준비 | $data_preparation |
| 실행 명령 | \`$execution_command\` |

## 컨테이너 이미지

| 서비스 | 이미지 참조 | 이미지 ID |
| --- | --- | --- |
EOF
    for service_name in "${ENVIRONMENT_SERVICES[@]}"; do
        printf '| %s | %s | %s |\n' "$service_name" "${ENVIRONMENT_IMAGE_REFERENCES[$service_name]}" \
            "${ENVIRONMENT_IMAGE_IDS[$service_name]}" >>"$report_path"
    done

    cat >>"$report_path" <<EOF

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

### k6 원본 측정값

| 요청 수 | 완료율(%) | 초당 요청 수 | p50(ms) | p95(ms) | p99(ms) | http_req_failed |
| ---: | ---: | ---: | ---: | ---: | ---: |
| $requests | $completion_rate | $requests_per_second | $p50 | $p95 | $p99 | $failed_rate |

### Kafka lag 원본 측정값

| 관측 최댓값 | 최종 관측값 | k6 종료 후 lag 0 도달 시간(초) |
| ---: | ---: | ---: |
| $maximum_kafka_lag | $final_kafka_lag | $post_load_lag_zero_seconds |

## 혼합 흐름 VU 단계 비교

| 단계 | 초당 요청 수 | p95(ms) |
| --- | ---: | ---: |
| ten_vus | ${STAGE_RPS[ten_vus]} | ${STAGE_P95[ten_vus]} |
| thirty_vus | ${STAGE_RPS[thirty_vus]} | ${STAGE_P95[thirty_vus]} |
| sixty_vus | ${STAGE_RPS[sixty_vus]} | ${STAGE_P95[sixty_vus]} |
EOF
    if (( consumer_count > 0 )); then
        active_consumer_count=$(jq -r '.activeConsumerCount' <<<"$consumer_assignment")
        assigned_partition_count=$(jq -r '.assignedPartitionCount' <<<"$consumer_assignment")
        cat >>"$report_path" <<EOF

## Consumer Group 확장 결과

| 항목 | 값 |
| --- | ---: |
| 설정 Consumer 수 | $consumer_count |
| 활성 Consumer 수 | $active_consumer_count |
| 할당 파티션 수 | $assigned_partition_count |
| 관측 최대 Kafka lag | $maximum_kafka_lag |
| 최종 Kafka lag | $final_kafka_lag |
| 종료 후 lag 0 도달 시간(초) | $post_load_lag_zero_seconds |
| 종단간 완료 시간(초) | $end_to_end_completion_seconds |
| 종단간 처리량(건/초) | $end_to_end_throughput |

| Consumer | 할당 파티션 |
| --- | --- |
EOF
        while IFS=$'\t' read -r consumer_id partitions; do
            echo "| $consumer_id | $partitions |" >>"$report_path"
        done < <(jq -r '.members[] | [.consumerId, (.partitions | map(tostring) | join(", "))] | @tsv' \
            <<<"$consumer_assignment")
        cat >>"$report_path" <<EOF

Group ID는 \`$consumer_group_id\`이다. \`order.completed\`는 3개 파티션이다.
같은 Group에서 동시에 파티션을 할당받는 Consumer도 최대 3개다. 4개 이상으로 확장하면 추가 Consumer는 유휴 상태가 되며,
이 구현은 병렬도 상한을 3으로 제한한다. 종단간 지표에는 HTTP 요청과 Outbox 발행 시간이 포함되고,
k6 종료 후 lag 0 도달 시간은 Consumer가 부하 종료 뒤 처리한 잔여 시간을 나타낸다.
EOF
    fi

    cat >>"$report_path" <<EOF

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

\`observations.ndjson\`에는 컨테이너 CPU·메모리, \`PENDING\`, consumer group partition별·합계 lag와 게시 실패 로그 수를
5초마다 기록했다. 이 중간 파일은 최종 보고서를 만든 뒤 정리한다.
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
    local result_directory="$RESULTS_ROOT/$batch_id-$scenario_name"
    local report_path="$result_directory/report.md"
    local index status run_id

    mkdir -p "$result_directory"

    cat >"$report_path" <<EOF
# $scenario_name 부하 테스트 보고서

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

    cat >>"$report_path" <<EOF

## 반복 실행별 원본 측정값 및 판정 근거

EOF
    for run_id in "${RUN_IDS[@]}"; do
        cat "$WORK_RESULTS_DIRECTORY/$run_id/report.md" >>"$report_path"
        printf '\n' >>"$report_path"
    done
}

write_consumer_scaling_aggregate_report() {
    local batch_id="$1"
    local result_directory="$RESULTS_ROOT/$batch_id-consumer-scaling"
    local report_path="$result_directory/report.md"
    local index status report_fragment

    mkdir -p "$result_directory"

    cat >"$report_path" <<EOF
# Consumer Group 확장 비교 보고서

같은 $CONSUMER_SCALING_ITERATIONS건 주문 부하를 Consumer 수 1·2·3에서 각각 세 번 실행한 결과다. 모든 실행은
\`order.completed\`의 3개 파티션을 사용하며, \`app-2\` listener를 비활성화해 표의 Consumer 수를 Group 전체 활성
Consumer 수로 맞췄다.

| 설정 수 | 종료 후 lag 0 중앙(초) | 종단간 중앙(초) | 처리량 중앙(건/초) | 처리량 최대(건/초) | 판정 |
| ---: | ---: | ---: | ---: | ---: | --- |
EOF
    for index in "${!SCALING_CONSUMER_COUNTS[@]}"; do
        status='실패'
        if [[ ${SCALING_PASSES[$index]} == true ]]; then
            status='통과'
        fi
        echo "| ${SCALING_CONSUMER_COUNTS[$index]} | ${SCALING_MEDIAN_POST_LOAD_LAG_ZERO_SECONDS[$index]} |" \
            "${SCALING_MEDIAN_END_TO_END_COMPLETION_SECONDS[$index]} |" \
            "${SCALING_MEDIAN_END_TO_END_THROUGHPUT[$index]} |" \
            "${SCALING_MAX_END_TO_END_THROUGHPUT[$index]} | $status |" >>"$report_path"
    done

    cat >>"$report_path" <<EOF

각 실행 결과에는 Consumer별 실제 파티션 할당과 최대·최종 Kafka lag, 완료율이 있다.
정합성·복구 판정 근거도 함께 기록한다. Kafka는 같은 Group의
파티션 하나를 한 Consumer에만 할당하므로 4개 이상으로 확장하면 최대 3개만 활성이고 나머지는 유휴 상태가
된다. 이 구현은 3개 파티션을 상한으로 병렬도를 제한한다.
EOF

    cat >>"$report_path" <<EOF

## 반복 실행별 원본 측정값 및 판정 근거

EOF
    for report_fragment in "$WORK_RESULTS_DIRECTORY"/*-consumer-scaling-*/report.md; do
        [[ -f "$report_fragment" ]] || continue
        cat "$report_fragment" >>"$report_path"
        printf '\n' >>"$report_path"
    done
}

run_scenario() {
    local scenario_name="$1"
    local run_number="$2"
    local commit="$3"
    local k6_version="$4"
    local consumer_count="${5:-0}"
    local script_name user_environment=()
    local last_order_id last_outbox_event_id run_id output_path observation_path started_at started_epoch
    local summary_path metrics_path k6_exit_code k6_completed_epoch recovery_completed_epoch recovered
    local requests requests_per_second p50 p95 p99 failed_rate completion_rate
    local successful_orders actual_order_count actual_outbox_count published_outbox_count
    local initial_failures retry_failures recovery_deadline_epoch recovery_detail
    local expected_popular_menus fault_window_popular_responses fault_window_popular_violations
    local balance_matches=true order_amount_matches=true popular_matches=false passed=true user_id expected_balance
    local charge_amount order_amount expected_count expected_amount actual_balance
    local consumer_group_id='popular-menu'
    local consumer_assignment='{}'
    local observation_consumer_count='null'
    local order_completed_partitions
    local end_to_end_completion_seconds=0
    local post_load_lag_zero_seconds=0
    local end_to_end_throughput=0
    local maximum_kafka_lag=0
    local final_kafka_lag=0

    reset_load_test_data
    if [[ "$scenario_name" == redis-* ]]; then
        seed_redis_popular_menu_data
    fi
    load_baseline_balances
    last_order_id=$(mysql_scalar 'SELECT COALESCE(MAX(id), 0) FROM orders;')
    last_outbox_event_id=$(mysql_scalar 'SELECT COALESCE(MAX(id), 0) FROM outbox_events;')
    if (( consumer_count > 0 )); then
        run_id="$(utc_run_id)-$scenario_name-$consumer_count-consumers-$run_number"
        consumer_group_id="popular-menu-scaling-$run_id"
        observation_consumer_count="$consumer_count"
    else
        run_id="$(utc_run_id)-$scenario_name-$run_number"
    fi
    output_path="$WORK_RESULTS_DIRECTORY/$run_id"
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
        consumer-scaling)
            (( consumer_count >= 1 && consumer_count <= 3 )) \
                || fail 'Consumer Group 확장 실행의 Consumer 수는 1부터 3까지여야 합니다.'
            script_name='consumer-scaling.js'
            user_environment=(
                -e USER_PREFIX=load-scaling
                -e USER_COUNT=30
                -e "CONSUMER_SCALING_ITERATIONS=$CONSUMER_SCALING_ITERATIONS"
            )
            restart_consumer_group "$consumer_group_id" "$consumer_count"
            consumer_assignment=$(wait_for_consumer_assignment "$consumer_group_id" "$consumer_count")
            ;;
        *)
            fail "알 수 없는 시나리오입니다: $scenario_name"
            ;;
    esac

    FAULT_STATE=([stopped]=false [restored]=false [injected]=false)
    FAULT_EVENTS=()
    FAULT_RECOVERY_AT=''
    RECOVERY_DEADLINE_EPOCH=0
    RECOVERY_OBSERVED_AT=''
    KAFKA_FAULT_STARTED_AT=''
    KAFKA_BASELINE_CAPTURED=false
    KAFKA_BASELINE_PENDING=0
    KAFKA_BASELINE_LAG=0
    KAFKA_MAX_PENDING=0
    KAFKA_MAX_LAG=0
    VALIDATION_NAMES=()
    VALIDATION_PASSES=()
    VALIDATION_DETAILS=()
    order_completed_partitions=$(order_completed_partition_count)
    started_at=$(utc_now)
    started_epoch=$(date +%s)
    if [[ "$scenario_name" == redis-* ]]; then
        expected_popular_menus=$(popular_menu_expected_json)
        user_environment+=(
            -e "EXPECTED_POPULAR_MENUS_JSON=$expected_popular_menus"
            -e "POPULAR_MENU_FAULT_START_EPOCH_SECONDS=$(( started_epoch + 60 ))"
        )
    fi
    add_observation "$observation_path" "$started_at" "$consumer_group_id" "$observation_consumer_count"
    start_k6_run "$script_name" "$output_path" \
        -e K6_BASE_URL=http://load-balancer:8080 \
        -e ORDER_MENU_ID=1 \
        -e POINT_CHARGE_AMOUNT=5000 \
        "${user_environment[@]}"

    while kill -0 "$K6_PID" >/dev/null 2>&1; do
        inject_fault "$scenario_name" "$started_epoch"
        add_observation "$observation_path" "$started_at" "$consumer_group_id" "$observation_consumer_count"
        sleep "$OBSERVATION_INTERVAL_SECONDS"
    done
    if wait "$K6_PID"; then
        k6_exit_code=0
    else
        k6_exit_code=$?
    fi
    K6_PID=''
    k6_completed_epoch=$(date +%s)
    restore_faulted_service "$scenario_name"

    [[ $k6_exit_code -eq 0 ]] || fail "k6 실행이 실패했습니다. 로그: $output_path/k6.log"

    recovery_deadline_epoch=$RECOVERY_DEADLINE_EPOCH
    if (( recovery_deadline_epoch == 0 )); then
        recovery_deadline_epoch=$(( $(date +%s) + RECOVERY_TIMEOUT_SECONDS ))
    fi
    if wait_for_recovery "$observation_path" "$started_at" "$recovery_deadline_epoch" "$consumer_group_id" \
        "$observation_consumer_count"; then
        recovered=true
    else
        recovered=false
    fi
    recovery_completed_epoch=$(date +%s)
    if (( consumer_count > 0 )); then
        end_to_end_completion_seconds=$(( recovery_completed_epoch - started_epoch ))
        post_load_lag_zero_seconds=$(( recovery_completed_epoch - k6_completed_epoch ))
    fi
    if [[ "$scenario_name" == 'kafka-recovery' ]]; then
        load_kafka_fault_maxima "$observation_path"
    fi
    IFS=$'\t' read -r maximum_kafka_lag final_kafka_lag < <(kafka_lag_measurements "$observation_path")

    summary_path="$output_path/summary.json"
    metrics_path="$output_path/metrics.json"
    analyse_k6_metrics "$metrics_path" "$output_path"
    requests=$(summary_value "$summary_path" '.metrics.http_reqs.values.count')
    requests_per_second=$(summary_value "$summary_path" '.metrics.http_reqs.values.rate')
    p50=$(summary_value "$summary_path" '.metrics.http_req_duration.values["p(50)"]')
    p95=$(summary_value "$summary_path" '.metrics.http_req_duration.values["p(95)"]')
    p99=$(summary_value "$summary_path" '.metrics.http_req_duration.values["p(99)"]')
    failed_rate=$(summary_value "$summary_path" '.metrics.http_req_failed.values.rate')
    completion_rate=$(awk -v failed="$failed_rate" 'BEGIN { printf "%.2f", (1 - failed) * 100 }')
    successful_orders=$(summary_value "$summary_path" '.metrics.successful_orders.values.count')
    if (( consumer_count > 0 && end_to_end_completion_seconds > 0 )); then
        end_to_end_throughput=$(awk -v orders="$successful_orders" -v seconds="$end_to_end_completion_seconds" \
            'BEGIN { printf "%.2f", orders / seconds }')
        consumer_assignment=$(consumer_assignment_json "$consumer_group_id")
    fi
    fault_window_popular_responses=$(summary_value "$summary_path" \
        '.metrics.fault_window_popular_responses.values.count')
    fault_window_popular_violations=$(summary_value "$summary_path" \
        '.metrics.fault_window_popular_response_violations.values.count')
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
    if [[ -n "$FAULT_RECOVERY_AT" ]]; then
        recovery_detail="복구=$FAULT_RECOVERY_AT, 기한 epoch=$recovery_deadline_epoch, 관측=$RECOVERY_OBSERVED_AT"
    else
        recovery_detail="기한 epoch=$recovery_deadline_epoch, 관측=$RECOVERY_OBSERVED_AT"
    fi
    add_validation 'PENDING과 consumer lag 기한 내 해소' "$recovered" "$recovery_detail"

    if [[ "$scenario_name" == 'consumer-scaling' ]]; then
        if [[ "$order_completed_partitions" == 3 ]]; then
            add_validation 'order.completed 토픽 파티션 수' true "partitions=$order_completed_partitions"
        else
            add_validation 'order.completed 토픽 파티션 수' false "partitions=$order_completed_partitions"
        fi
        if jq -e --argjson expected "$consumer_count" '
            .error == ""
            and .activeConsumerCount == $expected
            and .assignedPartitionCount == 3
        ' <<<"$consumer_assignment" >/dev/null; then
            add_validation 'Consumer 수와 파티션 할당 상한' true \
                "configured=$consumer_count, active=$(jq -r '.activeConsumerCount' \
                    <<<"$consumer_assignment"), assigned=3"
        else
            add_validation 'Consumer 수와 파티션 할당 상한' false \
                "configured=$consumer_count, assignment=$(jq -c . <<<"$consumer_assignment")"
        fi
    fi

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

    if [[ "$scenario_name" == redis-* ]]; then
        if [[ $fault_window_popular_responses -gt 0 ]]; then
            add_validation 'Redis 장애·복구 구간 인기 메뉴 응답 수집' true \
                "count=$fault_window_popular_responses"
        else
            add_validation 'Redis 장애·복구 구간 인기 메뉴 응답 수집' false \
                "count=$fault_window_popular_responses"
        fi
        if [[ $fault_window_popular_violations -eq 0 ]]; then
            add_validation 'Redis 장애·복구 구간 인기 메뉴 응답과 MySQL 원본 일치' true \
                "violations=$fault_window_popular_violations"
        else
            add_validation 'Redis 장애·복구 구간 인기 메뉴 응답과 MySQL 원본 일치' false \
                "violations=$fault_window_popular_violations"
        fi
    fi

    if [[ "$scenario_name" == 'kafka-recovery' ]]; then
        if [[ "$KAFKA_BASELINE_CAPTURED" == true ]]; then
            add_validation 'Kafka 장애 전 PENDING과 consumer lag 기준 관측' true \
                "pending=$KAFKA_BASELINE_PENDING, lag=$KAFKA_BASELINE_LAG"
        else
            add_validation 'Kafka 장애 전 PENDING과 consumer lag 기준 관측' false \
                'Kafka broker 중단 전 consumer lag를 읽지 못함'
        fi
        if [[ "$KAFKA_BASELINE_CAPTURED" == true && $KAFKA_MAX_PENDING -gt $KAFKA_BASELINE_PENDING ]]; then
            add_validation 'Kafka 장애 중 PENDING 증가 확인' true \
                "baseline=$KAFKA_BASELINE_PENDING, maximum=$KAFKA_MAX_PENDING"
        else
            add_validation 'Kafka 장애 중 PENDING 증가 확인' false \
                "baseline=$KAFKA_BASELINE_PENDING, maximum=$KAFKA_MAX_PENDING"
        fi
        if [[ "$KAFKA_BASELINE_CAPTURED" == true && $KAFKA_MAX_LAG -gt $KAFKA_BASELINE_LAG ]]; then
            add_validation 'Kafka 장애 중 consumer lag 증가 확인' true \
                "baseline=$KAFKA_BASELINE_LAG, maximum=$KAFKA_MAX_LAG"
        else
            add_validation 'Kafka 장애 중 consumer lag 증가 확인' false \
                "baseline=$KAFKA_BASELINE_LAG, maximum=$KAFKA_MAX_LAG"
        fi
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
    write_run_report "$output_path" "$run_id" "$scenario_name" "$commit" "$k6_version" \
        "$requests" "$requests_per_second" "$p50" "$p95" "$p99" "$failed_rate" "$completion_rate" \
        "$consumer_count" "$consumer_group_id" "$consumer_assignment" "$maximum_kafka_lag" "$final_kafka_lag" \
        "$post_load_lag_zero_seconds" "$end_to_end_completion_seconds" "$end_to_end_throughput"

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
    RUN_END_TO_END_COMPLETION_SECONDS+=("$end_to_end_completion_seconds")
    RUN_POST_LOAD_LAG_ZERO_SECONDS+=("$post_load_lag_zero_seconds")
    RUN_END_TO_END_THROUGHPUT+=("$end_to_end_throughput")
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
        all|mixed|contention|redis-connection|redis-data-loss|kafka-recovery|consumer-scaling)
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
    assert_dedicated_compose_file
    [[ "$CONSUMER_SCALING_ITERATIONS" =~ ^[1-9][0-9]*$ ]] \
        || fail 'CONSUMER_SCALING_ITERATIONS는 1 이상의 정수여야 합니다.'
    mkdir -p "$RESULTS_ROOT"

    local commit k6_version batch_id scenario_name run_number
    commit=$(git -C "$PROJECT_ROOT" rev-parse HEAD)
    start_load_test_environment
    capture_environment_metadata
    k6_version=$(compose run --rm -T k6 version | tr '\n' ' ')
    batch_id=$(utc_run_id)
    WORK_RESULTS_DIRECTORY="$RESULTS_ROOT/.work-$batch_id"
    mkdir -p "$WORK_RESULTS_DIRECTORY"

    local scenarios=()
    if [[ "$SCENARIO" == all ]]; then
        scenarios=(mixed contention redis-connection redis-data-loss kafka-recovery consumer-scaling)
    else
        scenarios=("$SCENARIO")
    fi

    for scenario_name in "${scenarios[@]}"; do
        if [[ "$scenario_name" == 'consumer-scaling' ]]; then
            SCALING_CONSUMER_COUNTS=()
            SCALING_MEDIAN_END_TO_END_COMPLETION_SECONDS=()
            SCALING_MEDIAN_POST_LOAD_LAG_ZERO_SECONDS=()
            SCALING_MEDIAN_END_TO_END_THROUGHPUT=()
            SCALING_MAX_END_TO_END_THROUGHPUT=()
            SCALING_PASSES=()

            local consumer_count scaling_pass
            for consumer_count in 1 2 3; do
                RUN_IDS=()
                RUN_PASSES=()
                RUN_RPS=()
                RUN_P95=()
                RUN_P99=()
                RUN_END_TO_END_COMPLETION_SECONDS=()
                RUN_POST_LOAD_LAG_ZERO_SECONDS=()
                RUN_END_TO_END_THROUGHPUT=()
                for run_number in 1 2 3; do
                    run_scenario "$scenario_name" "$run_number" "$commit" "$k6_version" "$consumer_count"
                done
                scaling_pass=true
                for status in "${RUN_PASSES[@]}"; do
                    if [[ "$status" != true ]]; then
                        scaling_pass=false
                        break
                    fi
                done
                SCALING_CONSUMER_COUNTS+=("$consumer_count")
                SCALING_MEDIAN_END_TO_END_COMPLETION_SECONDS+=("$(median "${RUN_END_TO_END_COMPLETION_SECONDS[@]}")")
                SCALING_MEDIAN_POST_LOAD_LAG_ZERO_SECONDS+=("$(median "${RUN_POST_LOAD_LAG_ZERO_SECONDS[@]}")")
                SCALING_MEDIAN_END_TO_END_THROUGHPUT+=("$(median "${RUN_END_TO_END_THROUGHPUT[@]}")")
                SCALING_MAX_END_TO_END_THROUGHPUT+=("$(maximum "${RUN_END_TO_END_THROUGHPUT[@]}")")
                SCALING_PASSES+=("$scaling_pass")
            done
            write_consumer_scaling_aggregate_report "$batch_id"
            continue
        fi

        RUN_IDS=()
        RUN_PASSES=()
        RUN_RPS=()
        RUN_P95=()
        RUN_P99=()
        RUN_END_TO_END_COMPLETION_SECONDS=()
        RUN_POST_LOAD_LAG_ZERO_SECONDS=()
        RUN_END_TO_END_THROUGHPUT=()
        for run_number in 1 2 3; do
            run_scenario "$scenario_name" "$run_number" "$commit" "$k6_version"
        done
        write_aggregate_report "$batch_id" "$scenario_name"
    done

    [[ "$ANY_FAILURE" == false ]] || fail "부하 테스트 판정 실패가 있습니다. 결과 경로: $RESULTS_ROOT"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    trap cleanup EXIT
    main "$@"
fi
