# 포인트 충전 요청 계약 검증 누락

## 요약

- 상태: 해결
- 영향: 잘못된 Content-Type·JSON scalar 타입이 500 또는 실제 충전으로 이어지고, 비BMP 문자 64개의 `userId`가 거절될 수 있었다.
- 최초 확인 시각과 시간대: 2026-07-16 00:25 KST
- 관련 요구사항: API 포인트 충전 요청 계약, PRD POINT-01, 코드 컨벤션 §7.1 Controller·DTO
- revision과 환경: PR #6, Spring MVC·Jackson 3.1.4

## 기대 결과와 실제 결과

- 기대 결과: 지원하지 않는 Content-Type과 잘못된 JSON 타입은 `400 INVALID_REQUEST`로 응답하고,
  `userId`의 1~64자 규칙은 코드 포인트 기준으로 판단한다.
- 실제 결과: 지원하지 않는 Content-Type은 일반 예외 처리로 `500`이 될 수 있었고,
  Jackson의 기본 coercion은 숫자↔문자열을 변환해 잔액을 변경할 수 있었다. `@Size(max = 64)`는 UTF-16 길이를 사용했다.

## 재현 절차

1. `Content-Type: text/plain`으로 `POST /api/v1/points/charges`를 요청한다.
2. `userId` 또는 `amount`를 API 명세와 다른 JSON scalar 타입으로 전송한다.
3. `"😀".repeat(64)`를 `userId`로 전송한다.

## 수집한 증거

- PR #6 리뷰는 Content-Type 예외 미매핑, 비BMP 64자 길이 계산, 문자열·정수 coercion을 각각 지적했다.
- `00979fa89dcb44a80acab80fdb4724d5d731e01a`은 Content-Type 오류 처리를,
  `8aba28fca927fc1a4b5dc6cb71414a17121507c6`은 유니코드 길이 검증을,
  `4e892d005e8ded0869ca566f45609483a52e8e8c`은 JSON scalar 타입 강제를 반영했다.
- PR #6에는 요청 오류 계약 문서와 테스트 보강이 함께 포함됐다.

## 조사 타임라인

| 시각 | 구분 | 가설 또는 작업 | 기대·검증 | 결과 | 판정 |
| --- | --- | --- | --- | --- | --- |
| 2026-07-16 00:25 KST | 관찰 | 지원하지 않는 media type 요청 | 입력 오류 응답 | 500 가능 | 원인 확인 |
| 2026-07-16 00:52 KST | 수정 | media type 예외 매핑 | 400 응답 | 커밋 반영 | 해결 적용 |
| 2026-07-16 00:52 KST | 관찰 | 비BMP 64자 길이 확인 | 정상 입력 허용 | 400 가능 | 원인 확인 |
| 2026-07-16 10:28 KST | 수정 | 코드 포인트 길이 검증 | 64자 허용 | 커밋 반영 | 해결 적용 |
| 2026-07-16 10:48 KST | 관찰 | JSON coercion 확인 | 잘못된 타입 거부 | 실제 충전 가능 | 원인 확인 |
| 2026-07-16 11:22 KST | 수정 | scalar coercion 차단 | 400 응답 | 커밋 반영 | 해결 적용 |

## 가설과 검증

- 가설: Bean Validation만으로 요청 JSON의 원시 타입 계약을 지킬 수 있다.
  - 검증: Jackson이 숫자 `userId`와 문자열 `amount`를 각각 변환하는 것이 리뷰에서 확인됐다.
  - 결론: 기각.
- 가설: `@Size`는 API가 정의한 문자 수를 계산한다.
  - 검증: 비BMP 문자는 UTF-16 code unit 두 개를 사용해 64자가 128로 계산됐다.
  - 결론: 기각.

## 근본 원인

HTTP media type, JSON 역직렬화, 문자열 길이 단위라는 서로 다른 입력 경계를 하나의 DTO 검증으로만 처리했다.
프레임워크 기본값의 coercion과 UTF-16 길이 의미가 공개 API 계약과 달랐다.

## 해결 또는 완화

- 지원하지 않는 media type을 `INVALID_REQUEST`로 변환했다.
- `userId` 길이를 코드 포인트 기준으로 검증했다.
- 문자열과 정수 필드의 scalar coercion을 차단해 명세와 다른 JSON 타입을 거절했다.

## Before/After 검증

- Before: 비정상 요청이 `500`이 되거나 유효성 검증을 통과해 충전을 수행할 수 있었다.
- After: 오류 계약과 JSON 타입 검증 테스트가 추가됐고, PR #6은 전체 Gradle 테스트 성공으로 병합됐다.

## 추가 테스트

- `text/plain` 요청의 `400 INVALID_REQUEST`
- 비BMP 문자 64개 `userId` 허용
- 숫자형 `userId`, 문자열형 `amount` 거부

## 재발 방지와 문서 반영

- 공개 API의 타입 계약은 DTO 선언만으로 가정하지 않고 역직렬화와 MockMvc 계약 테스트로 함께 검증한다.
- 외부 식별값의 길이 단위는 API와 DB 문자셋 의미를 명시한다.

## 잔여 위험과 후속 작업

- 이 기록의 범위는 포인트 충전 요청이다. 이후 공개 API도 같은 JSON coercion 정책을 적용하는지 변경 시 확인한다.
