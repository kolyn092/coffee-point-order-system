# 전체 테스트 실행 시간 초과 조사

## 요약

- 상태: 해결
- 영향: #20 구현 후 전체 회귀 테스트가 정상 통과했다.
- 최초 확인 시각과 시간대: 2026-07-17 12:00 KST
- 관련 요구사항: `docs/code-convention.md` §12 테스트, P1 M6 Kafka 게시 재시도
- revision과 환경: `feature/#20-outbox-retry-publisher`, Windows, Gradle 9.5.1, Testcontainers

## 기대 결과와 실제 결과

- 기대 결과: `./gradlew.bat test`가 모든 단위·통합·경합 테스트를 완료하고 종료 코드 0을 반환한다.
- 실제 결과: #20 단위 테스트와 `OutboxEventRetryIntegrationTest`는 각각 통과했다. 전체 `./gradlew.bat test`는
  출력 없이 244초가 지나 실행 제한으로 종료 코드 124를 반환했으나, 캐시를 무시한 전체 실행은 5분 58초에 성공했다.

## 재현 절차

1. `feature/#20-outbox-retry-publisher`에서 `./gradlew.bat test`를 실행한다.
2. Gradle 테스트 작업이 244초 안에 끝나는지 확인한다.

## 수집한 증거

- `./gradlew.bat test --tests "com.coffeepointordersystem.domain.outbox.service.OutboxEventRetryIntegrationTest"`
  실행은 43초 안에 성공했다.
- 전체 실행의 시간 초과 출력에는 특정 실패 테스트나 예외가 포함되지 않았다.

## 조사 타임라인

| 시각 | 구분 | 가설 또는 작업 | 기대·검증 | 결과 | 판정 |
| --- | --- | --- | --- | --- | --- |
| 2026-07-17 12:00 KST | 재현 | `./gradlew.bat test` 실행 | 전체 테스트 완료 | 244초 후 종료 코드 124 | 원인 조사 필요 |
| 2026-07-17 12:04 KST | 가설 | 테스트 리포트와 개별 테스트 실행 시간을 확인 | 지연 테스트 식별 | 대기 | 진행 중 |
| 2026-07-17 12:18 KST | 검증 | `./gradlew.bat test --rerun-tasks` 실행 | 캐시 없는 전체 회귀 완료 | 5분 58초, 종료 코드 0 | 해결 |
| 2026-07-17 12:29 KST | 회귀 | #20 최종 변경 후 전체 실행 | 캐시 없는 전체 회귀 완료 | 6분 32초, 종료 코드 0 | 유지 |

## 가설과 검증

- 가설 1: 기존 Testcontainers 통합 테스트들의 컨테이너 기동·종료 누적으로 전체 실행 시간이 제한을 초과한다.
  - 검증: 캐시 없는 전체 실행은 10개 Hikari 데이터소스를 순차 종료하며 5분 58초에 성공했다.
  - 결론: 확인. 244초 제한이 실제 전체 스위트 실행 시간보다 짧았다.
- 가설 2: 새 Outbox 스케줄러가 기존 테스트에서 Kafka 장애 이벤트를 반복 재시도해 테스트를 지연시킨다.
  - 검증: 1시간 fixed delay 테스트 속성에서 #20 통합 테스트가 43초에 통과했고, 전체 스위트도 실패 없이 완료됐다.
  - 결론: 기각. 스케줄러는 이번 시간 초과의 원인으로 확인되지 않았다.

## 근본 원인

전체 테스트는 여러 독립 Testcontainers 기반 통합·경합 테스트를 순차 실행하며, 실제 수행 시간이 5분 58초였다.
처음 사용한 4분 명령 제한이 더 짧아 테스트 워커가 끝나기 전에 외부 실행이 종료됐다. 이는 테스트 실패나 #20 구현의
교착 상태가 아니다.

## 해결 또는 완화

전체 회귀 검증에는 최소 6분 이상의 실행 제한을 사용한다. 코드 변경은 필요하지 않았다.

## Before/After 검증

- Before: 244초 제한의 `./gradlew.bat test`가 종료 코드 124로 중단됐다.
- After: 충분한 제한의 `./gradlew.bat test --rerun-tasks`가 5분 58초에 종료 코드 0으로 통과했다.

## 추가 테스트

- `./gradlew.bat test --tests "com.coffeepointordersystem.domain.outbox.service.OutboxEventRetryIntegrationTest"`: 43초, 통과
- `./gradlew.bat test --rerun-tasks`: 5분 58초, 통과
- #20 최종 변경 후 `./gradlew.bat test --rerun-tasks`: 6분 32초, 통과

## 재발 방지와 문서 반영

- Testcontainers 기반 전체 회귀 명령은 충분한 실행 제한으로 실행한다.
- 테스트 정책이나 제품 계약 변경은 없으므로 PRD, API, ERD 수정은 필요하지 않다.

## 잔여 위험과 후속 작업

- 컨테이너 이미지가 로컬에 없거나 Docker 자원이 부족한 환경에서는 전체 실행 시간이 더 길어질 수 있다.
