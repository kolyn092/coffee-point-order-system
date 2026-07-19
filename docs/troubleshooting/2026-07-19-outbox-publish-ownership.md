# Outbox 최초 발행·재시도 게시 소유권 경합

## 요약

- 상태: 해결
- 영향: 최초 Kafka 발행과 재시도가 같은 이벤트를 중복 발행하거나, 최초 발행 뒤 `PUBLISHED` 전이가 commit되지 않을 수 있었다.
- 최초 확인 시각과 시간대: 2026-07-17 12:59 KST
- 관련 요구사항: PRD P1 M6, ERD Outbox 게시 정책, ADR-0006
- revision과 환경: PR #25, Spring `AFTER_COMMIT`, MySQL `FOR UPDATE SKIP LOCKED`, Kafka

## 기대 결과와 실제 결과

- 기대 결과: 최초 발행과 재시도가 하나의 행 잠금·상태 확인 경로를 공유하고, Kafka 성공과 `PUBLISHED` 전이를 같은 새 트랜잭션에 저장한다.
- 실제 결과: 최초 발행은 공통 잠금 경로 밖에서 비동기로 전송할 수 있었고,
  `AFTER_COMMIT`에서 기본 `REQUIRED` 전파를 사용하면 완료된 주문 트랜잭션에 참여할 수 있었다.

## 재현 절차

1. Kafka 최초 발행 Future가 재시도 주기보다 오래 걸리도록 지연시킨다.
2. 주문 commit 뒤 재시도 스케줄러를 실행한다.
3. 최초 발행 직후 Outbox 상태와 Kafka 레코드 수를 확인한다.

## 수집한 증거

- PR #25 리뷰는 최초 발행 Future가 완료되기 전 재시도가 같은 `PENDING` 행을 발행하는 중복 경로를 지적했다.
- 후속 리뷰는 `AFTER_COMMIT`의 `REQUIRED` 전파가 `markPublished()`를 commit하지 못하게 하는 경로를 지적했다.
- `9fa26851cbd89f2a92d3fb5760c3ec1d640f5d85`는 최초 발행 경합을,
  `0c9b027c4fc473be7ca9934c90e172df08e5745c`는 `AFTER_COMMIT` 전이 commit을 보완했다.
- 최종 리뷰는 `REQUIRES_NEW`, 공통 잠금·상태 확인, `FOR UPDATE SKIP LOCKED`와 문서 동기화를 확인했다.

## 조사 타임라인

| 시각 | 구분 | 가설 또는 작업 | 기대·검증 | 결과 | 판정 |
| --- | --- | --- | --- | --- | --- |
| 2026-07-17 12:59 KST | 관찰 | 최초 발행 지연 후 재시도 | 이벤트 1회 발행 | 중복 가능 | 원인 확인 |
| 2026-07-17 16:48 KST | 수정 | 공통 잠금 경로 적용 | 단일 게시 소유권 | 커밋 반영 | 해결 적용 |
| 2026-07-17 17:10 KST | 관찰 | AFTER_COMMIT 전파 확인 | PUBLISHED commit | 미commit 가능 | 원인 확인 |
| 2026-07-17 18:04 KST | 수정 | 새 트랜잭션 전이 보장 | 상태 commit | 커밋 반영 | 해결 적용 |
| 2026-07-17 18:29 KST | 회귀 | PR 재리뷰 | 공통 경로·상태 전이 | 발견한 문제 없음 | 해결 |

## 가설과 검증

- 가설: 재시도만 행 잠금을 사용해도 최초 발행과의 중복은 막힌다.
  - 검증: 최초 발행이 잠금 밖에서 진행되면 재시도는 아직 `PENDING`인 행을 선택할 수 있다.
  - 결론: 기각.
- 가설: `AFTER_COMMIT` 호출의 기본 `REQUIRED`는 항상 새 트랜잭션을 연다.
  - 검증: 완료된 주문 트랜잭션 리소스에 참여해 상태 전이가 commit되지 않는 경로가 확인됐다.
  - 결론: 기각.

## 근본 원인

최초 발행과 재시도를 서로 다른 트랜잭션·동시성 경계로 구현했고, `AFTER_COMMIT` 호출의 Spring 전파 의미를
새 트랜잭션으로 잘못 가정했다.

## 해결 또는 완화

- 최초 발행과 재시도가 동일한 `FOR UPDATE SKIP LOCKED` 소유권 경로를 사용하도록 통합했다.
- 최초 발행은 `REQUIRES_NEW`에서 Kafka 발행 성공과 `PUBLISHED` 전이를 함께 commit하도록 했다.
- PRD·ERD·ADR에 잠긴 행 건너뜀, fixed delay, 최대 5초 확인 정책을 동기화했다.

## Before/After 검증

- Before: Kafka 지연 시 중복 전달, 정상 최초 발행 뒤 `PENDING` 잔류가 가능했다.
- After: 단위 테스트와 publisher·scheduler 테스트가 통과했고, 최종 리뷰가 공통 잠금과 새 트랜잭션 전이를 확인했다.

## 추가 테스트

- 최초 발행 지연 중 재시도 실행 시 Kafka 레코드 정확히 1건
- 주문 commit 뒤 최초 발행의 `PUBLISHED` 저장
- 잠긴 행을 재시도가 건너뛰는 MySQL 통합 경합

## 재발 방지와 문서 반영

- 이벤트 발행 경쟁 주체는 하나의 소유권 경계를 공유한다.
- `@TransactionalEventListener(AFTER_COMMIT)`에서 상태 변경이 필요하면 새 트랜잭션 여부를 명시적으로 검증한다.

## 잔여 위험과 후속 작업

- 당시 리뷰 환경은 Docker 데몬 부재로 MySQL `SKIP LOCKED` 통합 테스트를 재실행하지 못했다.
- Kafka 성공 뒤 DB 전이 실패의 중복 전달 부수 효과는 M7 consumer 멱등성 책임으로 남는다.
