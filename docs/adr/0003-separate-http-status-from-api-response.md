# ADR-0003: HTTP 상태와 공통 응답 본문 책임을 분리한다

- 상태: 채택됨
- 기록 유형: 신규
- 기록일: 2026-07-17
- 결정일: 2026-07-17
- 관련 요구사항: [`docs/API.md` §2 공통 규칙](../API.md#2-공통-규칙),
  [`docs/code-convention.md` §7.5 공통 타입](../code-convention.md#75-공통-타입)
- 관련 마일스톤: 해당 없음
- 관련 이슈: [#16 공통 API 응답 구조 도입](https://github.com/kolyn092/coffee-point-order-system/issues/16),
  [#17 공개 API 공통 응답 구조 적용](https://github.com/kolyn092/coffee-point-order-system/issues/17)
- 대체 대상: 없음

## 맥락

공개 HTTP API는 성공 시 `code: "SUCCESS"`와 `data`를, 오류 시 `code`와 `message`를 반환하는 공통 본문
계약으로 전환한다. 이때 실제 HTTP 상태와 본문의 업무 코드가 같은 책임을 갖지 않도록 구분해야 한다.

`@ResponseStatus(HttpStatus.CREATED)`와 `ResponseEntity.status(HttpStatus.CREATED)`를 함께 사용하면 HTTP 상태를
두 곳에서 선언하게 된다. 둘 중 하나만 변경돼도 문서, 실제 상태와 Controller 의도가 어긋날 수 있다. 반대로
본문에 HTTP 상태를 추가하면 HTTP 헤더와 JSON 사이에 중복된 상태가 생긴다.

## 결정 동인과 불변 조건

- HTTP 상태는 실제 응답 상태로 클라이언트와 프록시가 해석할 수 있어야 한다.
- `ApiResponse`의 `code`는 업무·응답 코드이며 HTTP 상태를 복제하지 않는다.
- 성공 응답의 `message`와 오류 응답의 `data`는 직렬화하지 않는다.
- 기존 API가 정한 `200 OK`, `201 Created`, 4xx와 5xx 상태 및 오류 코드는 유지한다.
- 하나의 Controller 응답에서 HTTP 상태를 둘 이상의 방식으로 선언하지 않는다.

## 검토한 선택지

| 순서 | 선택지 | 장점 | 단점·실패 위험 | 되돌림 비용 |
| --- | --- | --- | --- | --- |
| 1 | `ResponseEntity`가 HTTP 상태를 단독으로 관리하고 `ApiResponse`는 본문만 관리 | 상태 선언 위치가 하나여서 `200 OK`, `201 Created`와 오류 상태를 실제 HTTP 응답으로 명확히 제어한다. | Controller의 반환 타입과 성공 반환문이 다소 길어진다. | 모든 Controller의 반환형과 API별 MockMvc 계약을 함께 바꿔야 한다. |
| 2 | `@ResponseStatus`와 직접 반환하는 `ApiResponse`를 사용 | 단순한 성공 응답 Controller가 짧아진다. | 상태가 예외 처리 또는 조건에 따라 달라질 때 규칙이 분산되고, `ResponseEntity`를 함께 쓰면 상태가 중복된다. | Controller마다 애너테이션과 반환 방식의 조합을 다시 정리해야 한다. |
| 3 | `ApiResponse` 본문에 HTTP 상태 필드를 추가 | JSON만 보는 소비자가 상태 값을 확인할 수 있다. | 실제 HTTP 상태와 본문 상태가 중복되며 불일치 시 어느 값을 신뢰할지 모호하다. | 공개 응답 schema와 모든 클라이언트의 파싱 규칙을 다시 변경해야 한다. |

## 결정

HTTP 상태는 `ResponseEntity`만으로 선언한다. 성공 Controller는 `ResponseEntity.ok(ApiResponse.ok(data))` 또는
`ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data))`처럼 반환한다. 같은 메서드에
`@ResponseStatus`를 함께 사용하지 않는다.

`ApiResponse`는 `code`, `message`, `data` 본문만 가진다. 성공의 `SUCCESS`는 업무·응답 코드일 뿐 HTTP 상태를
의미하지 않으며, 본문에 `httpStatus` 필드를 추가하지 않는다. 오류는 `GlobalExceptionHandler`가
`ResponseEntity.status(errorCode.getHttpStatus())`와 `ApiResponse.error(errorCode)`로 변환한다.

#16은 공통 타입, 전역 오류 변환과 공통 문서 계약만 도입한다. 각 공개 API Controller의 성공 응답 전환과 API별
예시·통합 테스트 변경은 #17에서 이 결정을 적용한다.

## 결과와 트레이드오프

### 기대 효과

- HTTP 상태의 단일 선언 지점이 생겨 `201 Created`와 `200 OK`의 의도가 Controller에서 명확해진다.
- 클라이언트는 HTTP 상태로 전송·프로토콜 결과를, 본문의 `code`로 업무 오류 분기를 수행할 수 있다.
- 전역 예외 처리와 정상 Controller 응답이 같은 `ApiResponse` 직렬화 규칙을 공유한다.

### 수용한 단점과 위험

- 단순한 성공 Controller도 `ResponseEntity<ApiResponse<T>>` 반환형을 작성해야 한다.
- 2xx 상태와 `SUCCESS` 코드가 함께 존재하므로 두 값의 책임을 혼동하지 않도록 API 문서와 테스트가 필요하다.
- 기존 성공 응답을 직접 파싱하는 클라이언트에는 호환되지 않는 변경이다. 클라이언트 이행 또는 API 버전 정책은
  현재 문서에 정해져 있지 않으므로 #17 범위에서 임의로 추가하지 않는다.

## 검증 방법

- `ApiResponse` 단위 테스트로 성공 응답의 `code`, `data`와 오류 응답의 `code`, `message`, null 필드 제외를
  검증한다.
- #17에서 각 API의 MockMvc 테스트가 실제 HTTP 상태와 본문의 `code`, `data` 또는 `message`를 함께 검증한다.
- 코드 리뷰에서 `ResponseEntity` 반환 메서드에 `@ResponseStatus`를 중복하지 않는지 확인한다.
- 전체 테스트는 `./gradlew test`로 실행한다.

## 대체 조건

- API Gateway 또는 별도 응답 변환 계층이 HTTP 상태와 공통 본문을 단일하게 소유하게 되는 경우
- 공개 API가 `ApiResponse`와 다른 오류 표준 또는 성공 schema를 채택해야 하는 경우
- 클라이언트 호환성 요구로 기존 응답과 새 응답을 병행하거나 새 API 버전을 운영해야 하는 경우
