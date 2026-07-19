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
주문, Outbox와 Redis만 초기화한다. Redis 시나리오에는 부분 결과 검증을 위해 초기화 뒤 MySQL 원본 집계용 고정 주문을
별도로 준비한다. 기본 메뉴 migration과 일반 개발 Compose의 데이터·컨테이너에는 접근하지 않는다.

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
| `consumer-scaling` | 같은 600건 주문을 30 VU로 1·2·3 Consumer에서 각각 세 번 실행 | 없음 |

예를 들어 Kafka 복구 시나리오만 세 번 실행하려면 다음과 같이 실행한다.

```bash
bash ./scripts/load-test/invoke-load-test.sh --scenario kafka-recovery
```

### Consumer Group 확장 비교

`consumer-scaling`은 `order.completed`의 3개 파티션을 대상으로 같은 600건 주문을 Consumer 수 1·2·3에서 각각
세 번 처리한다. 각 실행은 `popular-menu-scaling-<실행 식별자>` Group을 새로 사용하고, `app-1`의 listener 병렬도를
1·2·3으로 설정한다. `app-2`는 HTTP 요청을 계속 처리하지만 listener는 비활성화해 Group 전체 활성 Consumer 수가
실행별 설정값과 같도록 한다.

```bash
bash ./scripts/load-test/invoke-load-test.sh --scenario consumer-scaling
```

기본 주문 수는 `CONSUMER_SCALING_ITERATIONS=600`이다. 값을 바꿀 때는 한 번의 명령에서 모든 Consumer 수에 같은 값을
적용해야 하며, 결과 보고서의 데이터 준비와 실제 `CONSUMER_SCALING_ITERATIONS` 값을 포함한 실행 명령을 함께 보관한다.

애플리케이션은 `order.completed`를 새 환경에서 파티션 3개, 복제 계수 1로 생성한다. `popular-menu.consumer.concurrency`
기본값은 1이고 상한은 3이다. 이미 생성된 Kafka 토픽의 파티션 수는 줄일 수 없으므로, 3개보다 많은 기존 토픽을 이
실험의 대상으로 재사용하지 않는다. Kafka는 같은 Consumer Group에서 파티션 하나를 동시에 둘 이상의 Consumer에
할당하지 않는다. 따라서 4개 이상의 Consumer를 실행하면 최대 3개만 파티션을 받고 나머지는 유휴 상태가 된다.

진단을 위해 종료 후 환경을 유지해야 할 때만 `--keep-environment`를 추가한다. 기본 동작은 종료 시 전용 컨테이너와
전용 volume을 정리한다.

## 수집·판정

실행 중 5초마다 다음 데이터를 `observations.ndjson`에 남긴다.

- 각 컨테이너의 CPU·메모리 사용량
- Outbox `PENDING` 개수와 가장 오래된 생성 시각
- `popular-menu` consumer group의 partition별·합계 lag
- Consumer Group 확장 실행의 설정 Consumer 수, 활성 Consumer 수와 Consumer별 파티션 할당. 할당 조회가 실패하면
  같은 관측의 오류 필드에 실패 원인을 남긴다.
- 최초 게시와 재시도 게시 실패 로그 수

k6 종료 뒤에는 요청 수, 초당 요청 수, `http_req_duration` p50·p95·p99, `http_req_failed`를 기록한다. 또한 다음을
MySQL 원본과 대조한다.

- 성공 주문 수와 새 `orders`·`outbox_events` 행 수
- 사용자별 시작 잔액 + 성공 충전액 - 성공 주문 결제액
- 모든 새 Outbox의 최종 `PUBLISHED` 상태
- lag가 0이 된 뒤 인기 메뉴 API의 Top 3·동점 정렬·주문 수

Redis 연결 장애와 Kafka broker 중단은 서비스 복구를 기록한 시각부터 5분 안에 `PENDING = 0` 및 consumer lag `= 0`이
되어야 한다. k6 종료 시각부터 대기 시간을 새로 계산하지 않는다. Kafka 중단 직전의 `PENDING`·lag를 기준값으로 남기고,
장애 주입 뒤 관측한 최댓값이 각각 기준값보다 증가해야 한다. 최초·재시도 게시 실패 로그, 증가한 `PENDING`과 lag,
복구 후 모든 새 Outbox의 `PUBLISHED` 상태를 함께 확인한다.

Redis 연결 장애·데이터 유실 시나리오는 장애 주입부터 k6 종료까지 인기 메뉴 응답을 별도 계수한다. 이 시나리오에는
주문 요청이 없으므로, 시작 시 MySQL에서 계산한 최근 7개 UTC 날짜의 Top 3·동점 정렬·주문 수를 각 `200 SUCCESS` 응답과
대조한다. 부분 결과·정렬 또는 주문 수 불일치·`503`은 모두 실패다. 유효 요청의 의도하지 않은 비-2xx와 주문 정합성
불일치도 실패다.

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
원본이다. `report.md`에는 Git commit, k6·Docker Engine·Docker Compose 버전, 각 컨테이너의 이미지 참조와 이미지 ID,
데이터 준비, 장애 주입·복구 시각, 검증 판정, 병목 후보와 두 JSON 파일의 SHA-256을 남긴다. Consumer Group 확장
보고서는 설정·활성 Consumer 수, Consumer별 파티션 할당, lag 0 도달 시간과 주문당 처리량도 남긴다. 세 번의 실행이
끝나면 같은 결과 루트에 시나리오별 중앙값·최댓값 집계 보고서가 생성되고, Consumer Group 확장은 1·2·3 Consumer
결과를 한 표에서 비교한 중앙 보고서도 생성된다.

결과를 재현할 때는 `report.md`의 commit, 실행 명령, Docker·Compose·k6 버전, 이미지 ID와 SHA-256이 같은지 먼저
확인한다. 장비가 고정되지 않으므로 절대 p95 SLA는 이 문서에서 채택하지 않는다.
