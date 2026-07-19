# M9 부하 테스트 실행 가이드

## 목적과 범위

이 가이드는 M9의 전용 환경에서 주문·결제, Kafka 게시·소비, 인기 메뉴 조회의 병목과 장애 복구를 재현하는 방법을
정의한다. 제품 API, Outbox 상태, Kafka 이벤트 schema와 Redis fallback·재구성 정책은 변경하지 않는다.

`docker-compose.load-test.yml`은 일반 개발용 `docker-compose.yml`과 별개의 `coffee-point-order-load-test`
프로젝트다. 데이터 초기화, Redis `FLUSHALL`, Redis 중단, Kafka broker 중단과 volume 정리는 이 Compose 프로젝트에만
적용된다.

## 준비

- Docker Desktop과 Docker Compose v2
- Bash 4.3 이상(Git Bash, WSL 또는 Linux/macOS)
- `curl`, `jq`, `sha256sum`
- 약 1시간 이상의 실행 시간과 Docker 이미지·Gradle 의존성을 내려받을 네트워크

실행 스크립트는 처음에 전용 Compose를 `down --volumes`한 뒤 다시 올리고, 각 실행 전에는 `load-*` 포인트 계정,
주문, Outbox와 Redis만 초기화한다. 기본 메뉴 migration과 일반 개발 Compose의 데이터·컨테이너에는 접근하지 않는다.

## 실행

저장소 루트에서 다음 명령을 실행한다.

```bash
bash ./scripts/load-test/invoke-load-test.sh --scenario all
```

`all`은 각 시나리오를 동일 Git commit과 Compose 환경에서 세 번 실행한다. 특정 시나리오만 다시 실행하려면 다음 중 하나를
`--scenario`에 지정한다.

| 값 | k6 부하 | 장애 주입 |
| --- | --- | --- |
| `mixed` | 1분 warming-up 뒤 10·30·60 VU를 각각 3분, 주문 50%·인기 메뉴 40%·충전 10% | 없음 |
| `contention` | 동일 사용자에 30 VU로 충전과 주문을 3분 | 없음 |
| `redis-connection` | 인기 메뉴 조회 30 VU를 3분 | 실행 1분 뒤 Redis를 1분 중단·복구 |
| `redis-data-loss` | 인기 메뉴 조회 30 VU를 3분 | 실행 1분 뒤 전용 Redis에 `FLUSHALL` |
| `kafka-recovery` | 주문 10 VU를 3분 | 실행 1분 뒤 Kafka broker를 1분 중단·복구 |

예를 들어 Kafka 복구 시나리오만 세 번 실행하려면 다음과 같이 실행한다.

```bash
bash ./scripts/load-test/invoke-load-test.sh --scenario kafka-recovery
```

진단을 위해 종료 후 환경을 유지해야 할 때만 `--keep-environment`를 추가한다. 기본 동작은 종료 시 전용 컨테이너와
전용 volume을 정리한다.

## 수집·판정

실행 중 5초마다 다음 데이터를 `observations.ndjson`에 남긴다.

- 각 컨테이너의 CPU·메모리 사용량
- Outbox `PENDING` 개수와 가장 오래된 생성 시각
- `popular-menu` consumer group의 partition별·합계 lag
- 최초 게시와 재시도 게시 실패 로그 수

k6 종료 뒤에는 요청 수, 초당 요청 수, `http_req_duration` p50·p95·p99, `http_req_failed`를 기록한다. 또한 다음을
MySQL 원본과 대조한다.

- 성공 주문 수와 새 `orders`·`outbox_events` 행 수
- 사용자별 시작 잔액 + 성공 충전액 - 성공 주문 결제액
- 모든 새 Outbox의 최종 `PUBLISHED` 상태
- lag가 0이 된 뒤 인기 메뉴 API의 Top 3·동점 정렬·주문 수

Redis·Kafka 장애 후에는 최대 5분 동안 `PENDING = 0` 및 consumer lag `= 0`을 기다린다. Redis 장애·유실 중의
부분 결과·`503`, 유효 요청의 의도하지 않은 비-2xx와 정합성 불일치는 실패다. Kafka 중단 시에는 최초·재시도 게시
실패 로그와 `PENDING` 증가가 있어야 하며, 복구 후에는 모두 `PUBLISHED`여야 한다.

혼합 흐름은 10·30·60 VU 단계별 초당 요청 수와 p95를 비교한다. 처리량 증가가 10% 미만이면서 p95가 50% 이상
증가하거나, 한 컨테이너의 CPU 또는 메모리가 1분 이상 80% 이상이면 실행을 실패시키지 않고 병목 후보로 보고한다.

## 결과 확인

각 실행은 다음 위치에 생성된다. 결과는 머신별로 달라서 Git에서 제외한다.

```text
docs/load-test/results/<UTC-실행식별자>/summary.json
docs/load-test/results/<UTC-실행식별자>/metrics.json
docs/load-test/results/<UTC-실행식별자>/observations.ndjson
docs/load-test/results/<UTC-실행식별자>/report.md
```

`summary.json`은 k6 원본 요약이며, `metrics.json`은 사용자별 성공 충전·주문 금액과 혼합 흐름 단계 지표를 재계산하는
원본이다. `report.md`에는 commit, k6 버전, 데이터 준비, 장애 주입 시각, 검증 판정, 병목 후보와 두 JSON 파일의
SHA-256을 남긴다. 세 번의 실행이 끝나면 같은 결과 루트에 시나리오별 중앙값·최댓값 집계 보고서도 생성된다.

결과를 재현할 때는 `report.md`의 commit, 실행 명령, k6 버전과 SHA-256이 같은지 먼저 확인한다. 장비가 고정되지
않으므로 절대 p95 SLA는 이 문서에서 채택하지 않는다.
