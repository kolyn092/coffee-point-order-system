# PR·리뷰 코멘트 기반 트러블슈팅 이력

## 요약

- 상태: 조사 중
- 조사 범위: 2026-07-15부터 2026-07-19까지 생성된 PR #2~#42의 PR 본문, 리뷰 코멘트, 병합 커밋
- 기준: `docs/PRD.md`, `docs/API.md`, `docs/ERD.md`, `docs/code-convention.md`
- 조사 방법: GitHub PR 타임라인과 로컬 Git 커밋을 대조했다. 이 기록 작성 시점에는 과거 결함을 다시 실행하지 않았다.

## 조사 결과

| 분류 | PR | 상태 | 기록 |
| --- | --- | --- | --- |
| 통합 테스트 신뢰성 | #2 | 해결 | [통합 테스트 인프라 검증 신뢰성](2026-07-19-integration-test-reliability.md) |
| 충전 요청 계약 | #6 | 해결 | [요청 계약 검증 누락](2026-07-19-request-contract-validation.md) |
| 인기 메뉴 응답 계약 | #11 | 해결 | [응답 DTO 계약 검증 누락](2026-07-19-popular-menu-response-contract.md) |
| Outbox 상태 불변식 | #15 | 해결 | [Outbox 상태·발행 시각 불변식](2026-07-19-outbox-state-invariant.md) |
| Outbox 게시 소유권 | #25 | 해결 | [최초 발행·재시도 경합](2026-07-19-outbox-publish-ownership.md) |
| Redis 만료 경계 | #30 | 해결 | [Redis 서버 시각 기준 만료](2026-07-19-redis-expiry-clock-source.md) |
| Redis 유실 감지 | #34 | 해결 | [Redis 상태 key 유실 감지](2026-07-19-redis-cache-loss-detection.md) |
| 부하 테스트 판정 | #38 | 해결 | [장애 복구 부하 테스트 판정](2026-07-19-load-test-acceptance-criteria.md) |
| Consumer 확장 실측 | #42 | 원인 확인 | [Consumer 확장 측정 증거](2026-07-19-consumer-scaling-measurement-evidence.md) |

## PR 코멘트 분류

- 결함을 지적하고 후속 커밋과 재리뷰에서 해결을 확인한 PR: #2, #6, #11, #15, #25, #30, #34, #38
- 결함이 없다고 확인된 PR: #4, #9, #19
- 문서 선행 작업이라는 이유로 지적을 철회한 PR: #18. 성공 응답 전환은 후속 PR #19에서 구현·검증됐다.
- 정책·문서 또는 자동화 보조 변경으로 별도 결함 코멘트가 없던 PR: #7, #14, #22, #24, #28, #32, #36
- 아직 병합되지 않았고, 완료 기준을 만족하는 실측 결과가 없는 PR: #42

## 공통 관찰

1. 결함은 주로 경계 조건에서 발견됐다. Docker 부재, JSON 타입 강제, `AFTER_COMMIT` 전파,
   Redis 시간원·key 유실, 장애 복구 기준 시각이 해당한다.
2. 해결 커밋은 대체로 문서 정책, 구현, 회귀 테스트를 함께 보강했다. 다만 Docker/Testcontainers와
   실제 k6 실행이 필요한 항목은 실행 환경에 따라 전체 검증 결과가 남지 않은 경우가 있다.
3. 전체 Testcontainers 회귀의 시간 제한 문제는 이미
   [전체 테스트 실행 시간 초과 조사](2026-07-17-full-test-timeout.md)에 해결 기록이 있다.

## 남은 작업

- PR #42에서 Consumer 수 1·2·3별 3회 실행 결과와 MySQL 대조를 남긴 뒤 완료 여부를 판정한다.
- Docker Desktop 전용 부하 환경에서 PR #38의 자동화가 실제 결과 파일을 생성하는지 확인한다.
