#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEST_RESULTS_ROOT="$(mktemp -d "$SCRIPT_DIRECTORY/.test-results.XXXXXX")"

test_cleanup() {
    rm -rf "$TEST_RESULTS_ROOT"
}

trap test_cleanup EXIT

export LOAD_TEST_RESULTS_ROOT="$TEST_RESULTS_ROOT"
# shellcheck source=invoke-load-test.sh
source "$SCRIPT_DIRECTORY/invoke-load-test.sh"

jq() {
    case "$*" in
        *'.activeConsumerCount'*)
            echo '1'
            ;;
        *'.assignedPartitionCount'*)
            echo '3'
            ;;
        *'.members[]'*)
            printf 'consumer-1\t0, 1, 2\n'
            ;;
        *)
            echo '{}'
            ;;
    esac
}

assert_contains() {
    local expected="$1"
    local target_file="$2"

    grep --fixed-strings --quiet -- "$expected" "$target_file" \
        || { echo "기대 문자열을 찾지 못했습니다: $expected" >&2; exit 1; }
}

WORK_RESULTS_DIRECTORY="$RESULTS_ROOT/.work-test"
mkdir -p "$WORK_RESULTS_DIRECTORY"

ENVIRONMENT_SERVICES=(app-1)
ENVIRONMENT_IMAGE_REFERENCES=([app-1]='coffee-point-order:latest')
ENVIRONMENT_IMAGE_IDS=([app-1]='sha256:test')
DOCKER_ENGINE_VERSION='test-engine'
DOCKER_COMPOSE_VERSION='test-compose'
FAULT_EVENTS=()
VALIDATION_NAMES=('주문과 Outbox 정합성')
VALIDATION_PASSES=(true)
VALIDATION_DETAILS=('orders=600, outbox=600')
BOTTLENECK_CANDIDATES=()
STAGE_RPS=([ten_vus]='0.00' [thirty_vus]='0.00' [sixty_vus]='0.00')
STAGE_P95=([ten_vus]='0.00' [thirty_vus]='0.00' [sixty_vus]='0.00')
SCALING_CONSUMER_COUNTS=(1 2 3)
SCALING_MEDIAN_POST_LOAD_LAG_ZERO_SECONDS=(2 2 2)
SCALING_MEDIAN_END_TO_END_COMPLETION_SECONDS=(20 20 20)
SCALING_MEDIAN_END_TO_END_THROUGHPUT=(30.00 30.00 30.00)
SCALING_MAX_END_TO_END_THROUGHPUT=(30.00 30.00 30.00)
SCALING_PASSES=(true true true)

for consumer_count in 1 2 3; do
    run_id="test-consumer-scaling-$consumer_count-consumers-1"
    run_directory="$WORK_RESULTS_DIRECTORY/$run_id"
    consumer_assignment='{"activeConsumerCount":1,"assignedPartitionCount":3,"members":[]}'
    mkdir -p "$run_directory"
    write_run_report "$run_directory" "$run_id" 'consumer-scaling' 'test-commit' 'v0-test' \
        '600' '30.00' '5.00' '10.00' '15.00' '0' '100.00' "$consumer_count" 'test-group' \
        "$consumer_assignment" '3' '0' '2' '20' '30.00'
done

write_consumer_scaling_aggregate_report 'test-batch'
cleanup

final_report="$RESULTS_ROOT/test-batch-consumer-scaling/report.md"
[[ -f "$final_report" ]] || { echo '최종 Markdown 보고서가 없습니다.' >&2; exit 1; }
assert_contains '## 실행 결과: test-consumer-scaling-1-consumers-1' "$final_report"
assert_contains '## 실행 결과: test-consumer-scaling-2-consumers-1' "$final_report"
assert_contains '## 실행 결과: test-consumer-scaling-3-consumers-1' "$final_report"
assert_contains '| 600 | 100.00 | 30.00 | 5.00 | 10.00 | 15.00 | 0 |' "$final_report"
assert_contains '| 3 | 0 | 2 |' "$final_report"
assert_contains '| consumer-1 | 0, 1, 2 |' "$final_report"
assert_contains '| 주문과 Outbox 정합성 | 통과 | orders=600, outbox=600 |' "$final_report"

unexpected_file=$(find "$RESULTS_ROOT" -type f ! -path "$final_report" -print -quit)
[[ -z "$unexpected_file" ]] || { echo "중간 산출물이 남았습니다: $unexpected_file" >&2; exit 1; }

echo '부하 테스트 Markdown 보고서 생성 검증을 통과했습니다.'
