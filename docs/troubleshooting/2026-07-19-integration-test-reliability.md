# 통합 테스트 인프라 검증 신뢰성

## 요약

- 상태: 해결
- 영향: Docker가 없거나 Kafka 기본 포트가 사용 중인 환경에서 M0 인프라 검증이 건너뛰거나 제품과 무관하게 실패할 수 있었다.
- 최초 확인 시각과 시간대: 2026-07-15 14:56 KST
- 관련 요구사항: PRD P0 M0, 코드 컨벤션 §12 테스트 독립성·MySQL·Redis·Kafka 통합 검증
- revision과 환경: PR #2, Testcontainers 기반 Spring 통합 테스트

## 기대 결과와 실제 결과

- 기대 결과: Docker·MySQL·Redis·Kafka를 사용할 수 없으면 통합 테스트가 명시적으로 실패하고,
  사용 가능하면 세 인프라의 실제 연결과 DB 제약을 검증한다.
- 실제 결과: Docker가 없을 때 MySQL 통합 테스트 전체가 건너뛰어도 Gradle이 성공할 수 있었고,
  Redis·Kafka 실연결과 FK `ON UPDATE RESTRICT`는 검증하지 않았다. Kafka는 호스트 `9092` 고정을 사용했다.

## 재현 절차

1. Docker를 사용할 수 없는 환경에서 기존 `InfrastructureIntegrationTest`를 실행한다.
2. 호스트 `9092`를 다른 Kafka 또는 프로세스가 사용한 상태에서 Kafka Testcontainer를 기동한다.

## 수집한 증거

- PR #2의 2026-07-15 14:56 KST 리뷰는 `disabledWithoutDocker = true`로 검증이 성공처럼 끝나는 경로와
  Redis·Kafka 실연결 검증 부재를 지적했다.
- 같은 날 16:21 KST 리뷰는 고정 호스트 포트가 병렬 실행과 포트 충돌에서 테스트 독립성을 깨는 것을 지적했다.
- 커밋 `4575f923c37f3baa4bb20592df1f9b303b4b20dc`는 Kafka 통합 테스트 갱신을 포함한다.
- 최종 리뷰는 MySQL migration, Redis `PING`, Kafka Admin 연결, `InfrastructureIntegrationTest` 15건 통과를 보고했다.

## 조사 타임라인

| 시각 | 구분 | 가설 또는 작업 | 기대·검증 | 결과 | 판정 |
| --- | --- | --- | --- | --- | --- |
| 2026-07-15 14:56 KST | 관찰 | Docker 부재 시 테스트 결과 확인 | 검증 미수행을 실패로 표시 | 성공으로 종료 가능 | 원인 후보 |
| 2026-07-15 16:21 KST | 관찰 | Kafka 포트 고정 확인 | 병렬 실행과 무관해야 함 | 9092 충돌 가능 | 원인 후보 |
| 2026-07-15 17:07 KST | 수정 | Testcontainers 연결 검증 갱신 | 세 인프라 실연결 확인 | 커밋 반영 | 해결 적용 |
| 2026-07-15 17:38 KST | 회귀 | PR 재리뷰 | 15건 통과·검증 범위 확인 | 발견한 문제 없음 | 해결 |

## 가설과 검증

- 가설: Docker 비활성화와 고정 포트 가정이 테스트 결과를 신뢰할 수 없게 만든다.
  - 검증: 리뷰가 해당 annotation과 포트 사용을 직접 확인했고, 수정 후 Testcontainers 기반 15건 통과가 보고됐다.
  - 결론: 확인.
- 가설: 설정 파일만으로 Redis·Kafka 연결을 보장할 수 있다.
  - 검증: 수정 후 Redis `PING`과 Kafka Admin 연결을 실제 통합 테스트 범위에 넣었다.
  - 결론: 기각.

## 근본 원인

필수 인프라의 가용성과 연결을 테스트 전제에 숨기고, Kafka endpoint를 실행 환경의 고정 포트에 의존했다.
그 결과 통합 테스트가 제품 계약 대신 로컬 환경 상태에 좌우될 수 있었다.

## 해결 또는 완화

- Docker가 없으면 인프라 통합 검증을 건너뛰지 않도록 하고, Redis·Kafka 실연결과 DB FK 갱신 제약을 추가했다.
- Testcontainers가 할당한 Kafka endpoint를 사용해 호스트 포트 충돌을 제거했다.

## Before/After 검증

- Before: Docker 부재에서 migration·제약 검증이 실행되지 않아도 성공으로 끝날 수 있었다.
- After: 최종 PR 리뷰에서 `InfrastructureIntegrationTest` 15건, Redis `PING`, Kafka Admin 연결과
  `./gradlew.bat test --rerun-tasks` 성공이 확인됐다.

## 추가 테스트

- MySQL migration·제약·UTC 저장 검증
- Redis 명령 실행과 Kafka Admin 연결 검증
- 참조 중인 메뉴와 포인트 계정의 `ON UPDATE RESTRICT` 검증

## 재발 방지와 문서 반영

- Testcontainers 통합 테스트는 Docker 부재를 성공으로 숨기지 않는다.
- 테스트 endpoint는 고정 호스트 포트가 아니라 컨테이너가 제공한 endpoint를 사용한다.

## 잔여 위험과 후속 작업

- 실제 Docker Compose 기동 후 `bootRun` 연결은 당시 실행하지 않았다.
- 전체 테스트 실행 시간 제한은 별도 기록인
  [전체 테스트 실행 시간 초과 조사](2026-07-17-full-test-timeout.md)를 따른다.
