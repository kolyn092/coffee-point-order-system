# ADR-0008: Redis 원자 script로 Kafka consumer 중복 이벤트를 처리한다

- 상태: 채택됨
- 기록 유형: 신규
- 기록일: 2026-07-17
- 결정일: 2026-07-17
- 관련 요구사항: [`docs/PRD.md` P1 M7 Kafka consumer 중복 처리 정책](../PRD.md#p1-m7-kafka-consumer-중복-처리-정책),
  [`docs/ERD.md` §6 Redis 인기 메뉴 조회 모델](../ERD.md#6-redis-인기-메뉴-조회-모델)
- 관련 마일스톤: M7
- 관련 이슈: [#26](https://github.com/kolyn092/coffee-point-order-system/issues/26)
- 대체 대상: 없음

## 맥락

ADR-0004, ADR-0006과 ADR-0007의 Outbox 게시 정책은 다중 인스턴스가 같은 `PENDING` 행을 동시에 발행하는
경로를 막는다. 그러나 Kafka 발행 성공 뒤 `PUBLISHED` 상태 전이가 실패하면 다음 재시도에서 같은
`order.completed` 레코드가 다시 발행될 수 있다.

현재 consumer는 이벤트마다 Redis 일자별 Sorted Set에 `ZINCRBY`를 실행한다. Kafka의 at-least-once 전달과
consumer 재시도에서 같은 이벤트가 여러 번 전달되면 인기 메뉴 점수가 실제 주문 수보다 커진다. 중복 판단과
점수 증가가 별도 Redis 호출이면 여러 consumer 인스턴스가 동시에 중복이 없다고 판단하거나, 한 호출만 실패해
부분 상태가 남을 수 있다.

## 결정 동인과 불변 조건

- 같은 `orderId`의 이벤트를 여러 번 수신해도 이벤트 UTC 날짜의 메뉴 점수는 한 번만 증가해야 한다.
- 중복 판단과 인기 메뉴 점수 갱신은 여러 Redis 호출이나 JVM 로컬 상태에 의존하지 않는다.
- 다중 consumer 인스턴스의 동시 수신, consumer 재시작과 Kafka 재전달에서도 같은 결과를 보장한다.
- 중복 기록의 보존 기간은 최근 7개 UTC 날짜 인기 메뉴 조회 범위와 일치해야 한다.
- Redis 장애는 성공으로 숨기지 않으며, offset commit 전에 전체 처리가 성공해야 한다.
- M7은 Outbox 상태 모델, Kafka 이벤트 계약, MySQL schema와 인기 메뉴 조회 정렬을 바꾸지 않는다.

## 검토한 선택지

| 순서 | 선택지 | 장점 | 단점·실패 위험 | 되돌리기 비용 |
| --- | --- | --- | --- | --- |
| 1 | Redis 중복 기록과 `ZINCRBY`를 하나의 Lua script로 실행 | `SET NX`, 점수 증가와 만료 설정을 원자 처리해 다중 인스턴스 경합과 부분 반영을 막는다. 기존 Redis 파생 모델과 UTC 만료 정책을 유지한다. | Redis script 운영과 script 변경 검증이 필요하다. Redis 유실 뒤에는 M8 재구성이 필요하다. | consumer와 Redis adapter를 되돌리면 되며 MySQL migration이 필요 없다. |
| 2 | consumer가 `GET`으로 중복을 확인한 뒤 `SET`과 `ZINCRBY`를 별도 실행 | 구현이 직관적이다. | 확인과 기록 사이의 경합으로 같은 이벤트가 여러 번 증가하며, 중간 실패가 중복 기록 또는 점수만 남긴다. | 이후 원자 script 또는 transaction으로 바꿔야 한다. |
| 3 | MySQL consumer 중복 기록 테이블에 `order_id` UNIQUE 제약을 둠 | 강한 영속 보존과 DB 제약을 활용한다. | Kafka consumer마다 MySQL 쓰기와 schema migration이 추가되고 Redis 점수 갱신과 DB 기록을 원자화할 수 없다. | 테이블, migration과 정리 정책을 함께 제거해야 한다. |
| 4 | Kafka exactly-once 처리만 사용 | Kafka 내부 read-process-write 흐름의 중복을 줄일 수 있다. | 외부 Redis 부수 효과의 원자성을 보장하지 않으며 producer·consumer 설정과 운영 복잡도가 M7 범위를 넘는다. | Kafka 설정과 배포 정책을 되돌려야 한다. |

## 결정

consumer는 이벤트의 `orderId`와 `occurredAt` UTC 날짜 `D`로 다음 Redis key를 만든다.

- 집계 key: `popular:menu:<yyyy-MM-dd>`
- 중복 기록 key: `popular:menu:processed:<orderId>`

하나의 Lua script는 중복 기록 key를 `SET NX`로 생성한다. 이미 존재하면 중복으로 판단하고 집계 key를 바꾸지
않고 성공으로 반환한다. 새 기록을 만들었을 때만 같은 script에서 집계 key에 `ZINCRBY 1 <menuId>`를 실행하고,
두 key를 `D+8 00:00:00Z`에 만료시킨다.

consumer는 script가 성공한 뒤에만 Kafka offset commit을 허용한다. script 또는 Redis 연결이 실패하면 예외를
전파해 offset을 commit하지 않는다. 이 경우 Kafka는 같은 레코드를 다시 전달하며, 성공한 script 재실행은 중복
결과로 끝나므로 점수를 다시 증가시키지 않는다.

## 결과와 트레이드오프

### 기대 효과

- Outbox의 발행 성공 뒤 상태 전이 실패로 같은 Kafka 레코드가 재전달되어도 Redis 점수가 한 번만 증가한다.
- 동시에 같은 이벤트를 받은 여러 consumer 인스턴스 중 한 script만 집계를 변경한다.
- 중복 기록 생성, 점수 증가와 만료 설정이 원자적이어서 부분 성공 상태를 남기지 않는다.
- 집계와 중복 기록이 같은 UTC 만료 시각을 사용해 7일 조회 창 안에서 중복 판단을 보장한다.

### 수용한 단점과 위험

- Redis 데이터는 파생 모델이므로 Redis 전체 유실 시 중복 기록도 함께 사라진다.
- 이벤트 UTC 날짜 `D+8 00:00:00Z` 이후에 같은 이벤트가 비정상적으로 재전달되면 중복 기록이 없어 다시 증가할 수
  있다. 이는 최근 7개 UTC 날짜 밖의 데이터를 M8 재구성 범위에서 제외하는 현재 보존 정책의 한계다.
- Redis script 배포 또는 변경이 잘못되면 인기 메뉴 반영이 실패하고 consumer lag가 증가할 수 있다.
- M7은 dead-letter, 장기 중복 보존, Kafka exactly-once 또는 Redis 장애 자동 복구를 제공하지 않는다.

## 실패 및 복구 흐름

1. script 실행 전 Redis 연결 또는 실행이 실패하면 점수와 중복 기록은 변경되지 않는다. consumer는 예외를 전파하고
   Kafka offset을 commit하지 않는다.
2. script가 성공하면 중복 기록, 점수와 두 만료 설정이 함께 반영된다. 이후 offset commit 전에 consumer가 중단되어
   레코드가 재전달되어도 script는 중복으로 반환한다.
3. Redis가 유실되면 인기 메뉴 조회를 실패 처리한다. M8은 MySQL `orders`에서 최근 7개 UTC 날짜를 재집계하고,
   각 주문의 `order_id` 중복 기록과 해당 메뉴 score를 같은 만료 시각으로 복원한다.
4. 재구성 중에는 consumer를 중지하거나 기존 Lua script 경로로만 처리해 재구성 결과와 실시간 반영이 경쟁하지 않게
   한다. 재구성과 중복 기록 복원이 끝난 뒤 consumer를 다시 시작하고 인기 메뉴 조회를 재개한다.

## 검증 방법

- 같은 `orderId` 이벤트를 순차로 두 번 소비해 해당 UTC 날짜 메뉴 score가 1인지 검증한다.
- 여러 consumer 실행이 같은 이벤트를 동시에 소비해 score가 1이고 중복 기록 key가 하나인지 검증한다.
- Redis script 실패 시 Kafka offset이 commit되지 않고, Redis 복구 뒤 같은 레코드가 재처리되는지 검증한다.
- script 성공 뒤 offset commit 전에 consumer를 중단한 뒤 재전달해 score가 증가하지 않는지 검증한다.
- 집계 key와 중복 기록 key가 모두 `D+8 00:00:00Z`에 만료되는지 UTC 경계 테스트로 검증한다.
- Redis 유실 뒤 M8 재구성으로 최근 7개 UTC 날짜의 집계와 중복 기록이 MySQL `orders`와 일치하는지 검증한다.

## 대체 조건

- 7일 창 밖의 지연 재전달도 중복 없이 처리해야 하여 중복 기록 보존 기간을 늘리거나 영속 저장소가 필요할 때
- Redis script 실행 시간이 운영 제한을 넘거나 Redis Cluster key-slot 제약으로 두 key의 원자 처리 정책을 바꿔야 할 때
- consumer의 외부 부수 효과가 Redis 이외로 늘어나 Kafka exactly-once 또는 별도 멱등성 저장소를 재검토할 때
- M8 재구성의 중단 시간, fallback 또는 운영 관측 요구사항이 확정될 때
