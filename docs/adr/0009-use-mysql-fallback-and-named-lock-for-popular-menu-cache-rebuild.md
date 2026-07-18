# ADR-0009: MySQL fallback과 명명 잠금으로 인기 메뉴 Redis를 재구성한다

- 상태: 채택됨
- 기록 유형: 신규
- 기록일: 2026-07-18
- 결정일: 2026-07-18
- 관련 요구사항: [`docs/PRD.md` POPULAR-01 인기 메뉴 조회](../PRD.md),
  [`docs/PRD.md` P2 M8 Redis fallback·재구성 정책](../PRD.md)
- 관련 마일스톤: M8
- 관련 이슈: [#31](https://github.com/kolyn092/coffee-point-order-system/issues/31)
- 대체 대상: 없음

## 맥락

인기 메뉴 Redis는 MySQL `orders`에서 재구성 가능한 파생 조회 모델이다. M7은 같은 `orderId`의 중복 Redis 점수
증가를 막지만, Redis 전체 유실·개별 key eviction·연결 장애를 감지하거나 복구하지 않는다. Redis가 정상 응답하더라도
점수 key가 없으면 주문이 없는 날짜와 유실된 날짜를 구분할 수 없고, 여러 인스턴스가 동시에 재구성하면 부분 결과와
consumer 갱신이 경합할 수 있다.

## 결정 동인과 불변 조건

- MySQL 주문 데이터는 인기 메뉴의 최종 원본으로 유지한다.
- Redis 장애와 데이터 유실 뒤에도 최근 7개 UTC 날짜, Top 3, 주문 수 내림차순·메뉴 ID 오름차순 계약은 유지한다.
- fallback 성공 응답의 schema와 HTTP 상태는 정상 Redis 조회와 구분하지 않는다.
- Redis 부분 결과, 재구성 중 결과와 이전 cache 결과를 성공 응답으로 반환하지 않는다.
- 다중 인스턴스에서 재구성은 한 번만 실행하고, 재구성 중 Kafka consumer가 점수를 중복 또는 누락 갱신하면 안 된다.
- 처리 표식과 점수 key의 `D+8 00:00:00Z` 만료 정책을 바꾸지 않는다.

## 검토한 선택지

| 순서 | 선택지 | 장점 | 단점·실패 위험 | 되돌림 비용 |
| --- | --- | --- | --- | --- |
| 1 | MySQL fallback·명명 잠금 재구성 | 원본 응답·단일 소유권 | MySQL 부하와 consumer 협조 | Redis key·consumer·조회 변경 |
| 2 | Redis 분산 잠금으로 재구성 | Redis 경로만으로 조정 가능 | Redis 연결 장애 자체에서는 잠금과 소유권을 얻을 수 없음 | 장애 시 MySQL 잠금으로 다시 설계 필요 |
| 3 | fallback만, 재구성은 운영 작업 | 변경을 줄임 | 자동 복구·eviction 대응 불가 | 기능·운영 절차 추가 |

## 결정

사용자 확인에 따라 1번 MySQL fallback과 MySQL 명명 잠금 재구성을 채택한다.

각 UTC 날짜에는 `popular:menu:state:<yyyy-MM-dd>` 상태 key를 둔다. `EMPTY`는 주문이 없어 점수 key가 없어야
함을, `READY`는 점수 key가 있어야 함을 뜻한다. 상태 key가 없거나 상태와 점수 key의 조합이 맞지 않으면 Redis
데이터 유실 또는 개별 key eviction으로 인한 불완전 상태다.

Redis timeout, 연결·명령 실행 실패, 불완전 상태 또는 재구성 표식이 있으면 API는 MySQL에서 최근 7개 UTC 날짜의
결제 완료 주문을 집계해 기존 성공 응답을 반환한다. MySQL 집계도 실패한 경우에만
`503 POPULAR_MENU_UNAVAILABLE`을 반환한다.

Redis가 다시 쓸 수 있으면 한 인스턴스만 MySQL 명명 잠금 `popular-menu-rebuild`를 획득한다. 소유자는 유한 TTL의
`popular:menu:rebuilding` 표식을 만든 뒤 최근 7개 UTC 날짜의 점수·상태·처리 표식을 MySQL 원본으로 재구성한다.
점수와 상태를 모두 완성한 날짜만 `READY`로 공개하고, 주문이 없는 날짜는 `EMPTY` 상태만 만든다. 모든 key는 기존과
같이 해당 날짜 `D`의 `D+8 00:00:00Z`에 만료한다.

재구성 표식이 있는 동안 조회는 MySQL fallback을 사용한다. consumer의 Redis 원자 스크립트는 표식을 확인해 처리하지
않고 실패로 반환하며 Kafka offset을 확정하지 않는다. 재구성 원본 집계에 포함된 주문은 복원한 처리 표식으로 중복
증가를 막고, 그 뒤 보류된 이벤트는 표식 해제 후 재전달되어 한 번만 반영한다.

## 결과와 트레이드오프

### 기대 효과

- Redis 연결 장애·전체 유실·개별 score 또는 상태 key eviction을 같은 MySQL 원본 경로로 안전하게 처리한다.
- 캐시가 불완전하거나 재구성 중인 경우에도 호출자는 기존 계약의 완전한 인기 메뉴 결과를 받는다.
- MySQL 명명 잠금으로 재구성 소유권을 하나로 제한하고, Redis 재구성 표식으로 API와 consumer의 부분 결과 경합을
  막는다.

### 수용한 단점과 위험

- Redis 장애나 유실 시 조회 요청이 MySQL 집계 부하를 유발한다. M9에서 fallback 비율, MySQL 집계 시간과 lock
  대기 영향을 관측해야 한다.
- 명명 잠금은 DB 연결에 귀속되고 재구성 표식은 Redis TTL에 의존하므로, 구현은 소유자 token 검증과 예외 경로의
  잠금·표식 해제를 보장해야 한다.
- 재구성 중 consumer는 offset을 확정하지 않아 일시적인 Kafka lag가 발생할 수 있다. 이 지연은 부분 cache 결과나
  중복 집계보다 우선한다.

## 검증 방법

이 이슈는 문서·ADR 변경만 포함한다. M8 구현 이슈에서는 다음을 통합 테스트로 검증한다.

- Redis timeout, 연결 실패와 명령 실패 시 MySQL 집계가 기존 `200 OK` body와 같은 기간·정렬·Top 3 결과를 반환한다.
- `EMPTY` 상태와 점수 key 없음은 정상 빈 날짜로, 상태 key 없음 또는 `READY`와 점수 key 없음은 fallback trigger로
  구분한다.
- Redis와 MySQL 모두 실패하면 `503 POPULAR_MENU_UNAVAILABLE`만 반환하고 부분 또는 이전 cache 결과를 반환하지
  않는다.
- 여러 인스턴스가 동시에 재구성을 요청해도 하나만 명명 잠금을 얻어 Redis를 쓰고, 나머지는 MySQL fallback으로
  응답한다.
- 재구성 표식 동안 API는 MySQL fallback을 사용하고 consumer는 offset을 확정하지 않는다. 완료 뒤 점수·상태·처리
  표식이 MySQL 원본과 일치하며 지연 이벤트의 점수는 한 번만 증가한다.
- 점수, 상태와 처리 표식이 모두 각 날짜 `D+8 00:00:00Z`에 만료하며, Redis 쓰기 실패 뒤 불완전한 날짜가 `READY`로
  남지 않는다.

## 대체 조건

- M9 측정에서 MySQL fallback 또는 명명 잠금이 허용 가능한 조회 지연·DB 부하를 넘을 때
- Redis 이중화 또는 별도 cache 재구성 worker가 도입되어 MySQL 명명 잠금보다 높은 가용성과 처리량이 필요할 때
- 인기 메뉴 집계 기간, 원본 데이터 또는 처리 표식 보존 기간이 바뀌어 일자별 상태 key 모델이 맞지 않을 때
