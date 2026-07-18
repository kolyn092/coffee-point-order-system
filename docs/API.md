# API 명세서

## 1. 개요

이 문서는 `docs/PRD.md`의 `원 과제 최소·제출 요구사항` 절과 P0 범위를 HTTP API와 Kafka 이벤트 계약으로
구체화한다.
`reference/API_TEMPLATE.md`의 문서 구조를 따른다. P1과 P2 기능은 현재 요청·응답 schema에 포함하지 않으며,
P2 M8은 schema를 바꾸지 않는 Redis 장애·fallback 정책으로만 기록한다.

### 요구사항 추적

| 요구사항 | HTTP 또는 이벤트 계약 | 주요 데이터 |
| --- | --- | --- |
| MENU-01 메뉴 목록 조회 | `GET /api/v1/menus` | `menus` |
| POINT-01 포인트 충전 | `POST /api/v1/points/charges` | `point_accounts` |
| ORDER-01 주문·결제 | `POST /api/v1/orders` | `menus`, `point_accounts`, `orders` |
| EVENT-01 주문 데이터 전송 | Kafka `order.completed` | 주문 완료 이벤트 |
| POPULAR-01 인기 메뉴 조회 | `GET /api/v1/menus/popular` | Redis 일자별 Sorted Set |

## 2. 공통 규칙

### Base URL

```text
/api/v1
```

### 요청과 응답 형식

| 항목 | 규칙 |
| --- | --- |
| 요청·응답 형식 | `application/json; charset=UTF-8` |
| 금액과 포인트 | 소수부 없는 JSON 정수, `1원 = 1P` |
| 정수 범위 | signed 64-bit |
| 시각 | ISO 8601 UTC, 예: `2026-07-15T03:04:05.123456Z` |
| 인증·인가 | P0에서는 지원하지 않음 |
| 성공 응답 | `code: "SUCCESS"`와 각 API의 DTO 또는 배열을 담은 `data`를 반환하며, `message`는 포함하지 않음 |

`Long` 입력의 최댓값은 `9,223,372,036,854,775,807`이다. P0에는 멱등성 키가 없으므로 같은 충전 또는
주문 요청을 반복하면 각각 별도 실행으로 처리한다.

### 공통 입력 규칙

다음 필드는 해당 필드를 사용하는 API에서 필수다.

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `userId` | String | Y | 1~64자, 공백만으로 구성하거나 양 끝에 공백을 둘 수 없음 |
| `menuId` | Long | Y | 양의 정수 |
| `amount` | Long | Y | 양의 정수 |

`userId`는 외부에서 발급된 불투명 식별값이다. 서버는 대소문자를 구분하고 값을 잘라내거나 변환하지 않는다.

요청 JSON 문법, 필수값, 타입 또는 범위가 잘못되면 `INVALID_REQUEST`를 반환한다.

### 공통 성공 응답 형식

성공 응답은 다음 구조를 사용한다. `data`에는 각 API가 정의한 DTO, 배열 또는 값이 들어간다.

```json
{
  "code": "SUCCESS",
  "data": {}
}
```

### 공통 오류 응답 형식

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청 값이 올바르지 않습니다."
}
```

성공 응답에는 `message`를, 오류 응답에는 `data`를 포함하지 않는다. 클라이언트는 `message`가 아니라 `code`를
분기 기준으로 사용한다. 내부 예외명, SQL, stack trace와 비밀값은 응답에 포함하지 않는다.

## 3. 메뉴 목록 조회 API

메뉴 ID, 이름과 가격을 전체 조회한다. 메뉴는 `menuId` 오름차순으로 정렬하며 페이지네이션을 지원하지 않는다.

### Request

```http
GET /api/v1/menus
```

#### Request Example

```http
GET /api/v1/menus HTTP/1.1
Accept: application/json
```

#### Request Headers

| Name | Required | Description |
| --- | --- | --- |
| `Accept` | N | `application/json` |

#### Path Variable

없음.

#### Query Parameter

없음.

#### Request Body

없음.

### Response

#### Status

```http
200 OK
```

#### Response Body

```json
{
  "code": "SUCCESS",
  "data": [
    {
      "menuId": 1,
      "name": "아메리카노",
      "price": 4500
    },
    {
      "menuId": 2,
      "name": "카페라떼",
      "price": 5000
    }
  ]
}
```

#### Response Field

| Name | Type | Description |
| --- | --- | --- |
| `code` | String | 항상 `SUCCESS` |
| `data[].menuId` | Long | 메뉴 ID |
| `data[].name` | String | 메뉴 이름 |
| `data[].price` | Long | 원 단위 가격이며 같은 수의 포인트가 필요함 |

메뉴가 없으면 `200 OK`와 `data: []`을 반환한다.

### Error Code

| HTTP Status | Code | Description |
| --- | --- | --- |
| 500 | `INTERNAL_SERVER_ERROR` | 공개 가능한 도메인 오류로 분류되지 않은 서버 오류 |

#### Error Response Body

```json
{
  "code": "INTERNAL_SERVER_ERROR",
  "message": "서버 오류가 발생했습니다."
}
```

## 4. 포인트 충전 API

사용자 식별값과 충전 금액을 받아 `1원 = 1P`로 포인트 잔액에 반영한다.

### Request

```http
POST /api/v1/points/charges
```

#### Request Example

```http
POST /api/v1/points/charges HTTP/1.1
Content-Type: application/json
Accept: application/json

{
  "userId": "user-123",
  "amount": 10000
}
```

#### Request Headers

| Name | Required | Description |
| --- | --- | --- |
| `Content-Type` | Y | `application/json` |
| `Accept` | N | `application/json` |

#### Path Variable

없음.

#### Query Parameter

없음.

#### Request Body

```json
{
  "userId": "user-123",
  "amount": 10000
}
```

#### Request Field

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `userId` | String | Y | 공통 `userId` 규칙을 따름 |
| `amount` | Long | Y | 충전할 원 단위 금액, 양의 정수 |

### 처리 규칙

- 외부 회원 시스템의 사용자 등록 과정에서 포인트 계정은 잔액 0으로 생성되어 있어야 한다. 이 API는 계정을
  생성하지 않는다.
- 같은 사용자의 충전과 주문은 포인트 계정 행에 대한 DB 비관적 잠금으로 직렬화한다.
- 충전 전 잔액과 `amount`의 합은 checked arithmetic으로 계산한다.
- 동시 충전이 성공하면 어느 요청도 유실되지 않으며 최종 잔액은 성공한 충전 금액의 합과 같다.
- 같은 요청을 다시 보내면 새로운 충전으로 처리한다.
- 포인트 계정이 없으면 회원 시스템과의 데이터 정합성이 깨진 것이므로 `INTERNAL_SERVER_ERROR`를 반환한다.

### Response

#### Status

```http
200 OK
```

#### Response Body

```json
{
  "code": "SUCCESS",
  "data": {
    "userId": "user-123",
    "chargedAmount": 10000,
    "balance": 10000
  }
}
```

#### Response Field

| Name | Type | Description |
| --- | --- | --- |
| `code` | String | 항상 `SUCCESS` |
| `data.userId` | String | 충전 대상 사용자 식별값 |
| `data.chargedAmount` | Long | 이번 요청에서 충전한 포인트 |
| `data.balance` | Long | 이번 충전 트랜잭션이 반영된 직후 잔액 |

### Error Code

| HTTP Status | Code | Description |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `Content-Type`, 요청 JSON, `userId` 또는 `amount`가 공통 입력 규칙을 위반함 |
| 409 | `POINT_BALANCE_LIMIT_EXCEEDED` | 충전 후 잔액이 signed 64-bit 범위를 초과함 |
| 500 | `INTERNAL_SERVER_ERROR` | 공개 가능한 도메인 오류로 분류되지 않은 서버 오류 |

#### Error Response Body

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청 값이 올바르지 않습니다."
}
```

## 5. 주문·결제 API

사용자 식별값과 메뉴 ID를 받아 메뉴 한 잔을 주문하고 포인트로 결제한다.

### Request

```http
POST /api/v1/orders
```

#### Request Example

```http
POST /api/v1/orders HTTP/1.1
Content-Type: application/json
Accept: application/json

{
  "userId": "user-123",
  "menuId": 1
}
```

#### Request Headers

| Name | Required | Description |
| --- | --- | --- |
| `Content-Type` | Y | `application/json` |
| `Accept` | N | `application/json` |

#### Path Variable

없음.

#### Query Parameter

없음.

#### Request Body

```json
{
  "userId": "user-123",
  "menuId": 1
}
```

#### Request Field

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `userId` | String | Y | 공통 `userId` 규칙을 따름 |
| `menuId` | Long | Y | 주문할 메뉴 한 잔의 ID, 양의 정수 |

### 처리 규칙

1. 입력 형식을 검증한다.
2. 메뉴 존재 여부와 현재 가격을 확인한다.
3. 포인트 계정 행을 비관적 잠금으로 조회한다.
4. 잔액이 메뉴 가격 이상인지 확인한다.
5. 한 트랜잭션에서 포인트를 차감하고 결제 당시 가격이 포함된 주문을 저장한다.
6. 트랜잭션 commit 후 Kafka `order.completed` 이벤트 발행을 한 번 시도한다.

검증 실패, 메뉴 부재, 계정 부재, 잔액 부족 순으로 오류를 결정한다. 주문 저장 또는 차감이 실패하면 둘 다
rollback한다. Kafka 발행 실패는 이미 완료된 주문과 결제를 rollback하거나 HTTP 성공을 실패로 바꾸지 않는다.

### Response

#### Status

```http
201 Created
```

#### Response Body

```json
{
  "code": "SUCCESS",
  "data": {
    "orderId": 101,
    "userId": "user-123",
    "menuId": 1,
    "paidAmount": 4500,
    "remainingPointBalance": 5500,
    "orderedAt": "2026-07-15T03:04:05.123456Z"
  }
}
```

#### Response Field

| Name | Type | Description |
| --- | --- | --- |
| `code` | String | 항상 `SUCCESS` |
| `data.orderId` | Long | 생성된 주문 ID |
| `data.userId` | String | 주문 사용자 식별값 |
| `data.menuId` | Long | 주문한 메뉴 ID |
| `data.paidAmount` | Long | 결제 당시 메뉴 가격과 실제 차감 포인트 |
| `data.remainingPointBalance` | Long | 주문 트랜잭션이 반영된 직후 잔액 |
| `data.orderedAt` | String | 주문 완료 시각, ISO 8601 UTC |

### Error Code

| HTTP Status | Code | Description |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `userId` 또는 `menuId`가 공통 입력 규칙을 위반함 |
| 404 | `MENU_NOT_FOUND` | 메뉴가 없으며 차감과 주문 저장이 발생하지 않음 |
| 404 | `POINT_ACCOUNT_NOT_FOUND` | 포인트 계정이 없으며 차감과 주문 저장이 발생하지 않음 |
| 409 | `INSUFFICIENT_POINT_BALANCE` | 잔액이 결제 금액보다 적으며 차감과 주문 저장이 발생하지 않음 |
| 500 | `INTERNAL_SERVER_ERROR` | 공개 가능한 도메인 오류로 분류되지 않은 서버 오류 |

#### Error Response Body

```json
{
  "code": "MENU_NOT_FOUND",
  "message": "메뉴를 찾을 수 없습니다."
}
```

## 6. 인기 메뉴 목록 조회 API

조회일을 포함한 최근 7개 UTC 날짜의 결제 완료 주문 수를 기준으로 인기 메뉴를 최대 3개 조회한다.

### Request

```http
GET /api/v1/menus/popular
```

#### Request Example

```http
GET /api/v1/menus/popular HTTP/1.1
Accept: application/json
```

#### Request Headers

| Name | Required | Description |
| --- | --- | --- |
| `Accept` | N | `application/json` |

#### Path Variable

없음.

#### Query Parameter

없음.

#### Request Body

없음.

### 처리 규칙

- 조회 UTC 날짜를 `D`라고 할 때 `D-6`부터 `D`까지 일곱 개 날짜의 주문 수를 합산한다.
- `orderCount` 내림차순으로 정렬한다.
- 주문 수가 같으면 `menuId` 오름차순으로 정렬한다.
- 최대 3개를 반환한다.
- Kafka 소비 후 Redis에 반영되므로 방금 완료된 주문이 즉시 보이지 않을 수 있다.

### Response

#### Status

```http
200 OK
```

#### Response Body

```json
{
  "code": "SUCCESS",
  "data": [
    {
      "menuId": 1,
      "name": "아메리카노",
      "price": 4500,
      "orderCount": 17
    },
    {
      "menuId": 2,
      "name": "카페라떼",
      "price": 5000,
      "orderCount": 12
    }
  ]
}
```

#### Response Field

| Name | Type | Description |
| --- | --- | --- |
| `code` | String | 항상 `SUCCESS` |
| `data[].menuId` | Long | 메뉴 ID |
| `data[].name` | String | 현재 메뉴 이름 |
| `data[].price` | Long | 현재 메뉴 가격 |
| `data[].orderCount` | Long | 최근 7개 UTC 날짜의 결제 완료 주문 수 |

집계 대상 주문이 없으면 `200 OK`와 `data: []`을 반환한다.

### Redis 장애와 M8 cache 상태 계약

P0에서는 Redis timeout, 연결 실패 또는 조회 불가 시 MySQL로 fallback하지 않는다.
`503 Service Unavailable`과 `POPULAR_MENU_UNAVAILABLE`을 반환한다.

Redis가 정상 응답했지만 key가 없으면 해당 날짜의 주문이 없는 것으로 처리한다. P0는 Redis 데이터 유실을
자동 탐지하거나 복구하지 않는다.

P2 M8에서는 각 집계 날짜의 상태 key와 점수 key 조합이 완전한 Redis 조회 모델인지 확인한다. 상태 key가 없거나
상태와 점수 key가 맞지 않으면 데이터 유실 또는 개별 key eviction으로 인한 cache 불완전 상태로 판단한다.
Redis timeout, 연결·명령 실행 실패와 cache 불완전 상태에서는 MySQL로 최근 7개 UTC 날짜의 결제 완료 주문을
집계해 `200 OK`와 기존 성공 응답 body를 반환한다. fallback 여부를 나타내는 header·field는 추가하지 않는다.

다른 인스턴스가 Redis를 재구성 중임을 나타내는 표식이 있으면 Redis 부분 결과를 반환하지 않고 같은 MySQL
fallback 규칙을 적용한다. fallback이 도입되어도 정상 응답 schema, 집계 기간과 정렬 규칙은 변경하지 않는다.
MySQL 집계도 실패한 경우에만 `503 Service Unavailable`과 `POPULAR_MENU_UNAVAILABLE`을 반환하며, 부분 결과나
이전 Redis 결과를 성공 응답으로 반환하지 않는다.

### Error Code

| HTTP Status | Code | Description |
| --- | --- | --- |
| 503 | `POPULAR_MENU_UNAVAILABLE` | P0 Redis 장애 또는 M8 fallback MySQL 집계 실패로 인기 메뉴를 조회할 수 없음 |
| 500 | `INTERNAL_SERVER_ERROR` | 공개 가능한 도메인 오류로 분류되지 않은 서버 오류 |

#### Error Response Body

```json
{
  "code": "POPULAR_MENU_UNAVAILABLE",
  "message": "인기 메뉴를 조회할 수 없습니다."
}
```

## 7. Kafka 주문 완료 이벤트

### Event

```text
Topic: order.completed
```

#### Event Example

```json
{
  "orderId": 101,
  "userId": "user-123",
  "menuId": 1,
  "paidAmount": 4500,
  "occurredAt": "2026-07-15T03:04:05.123456Z"
}
```

#### Event Field

| Name | Type | Description |
| --- | --- | --- |
| `orderId` | Long | 완료된 주문 ID |
| `userId` | String | 주문 사용자 식별값 |
| `menuId` | Long | 주문한 메뉴 ID |
| `paidAmount` | Long | 결제 당시 가격과 차감 포인트 |
| `occurredAt` | String | 주문 완료 시각, ISO 8601 UTC |

`occurredAt`은 `orders.ordered_at`과 같은 시각이다. 인기 메뉴 consumer는 이 값의 UTC 날짜를 Redis key의
날짜로 사용한다.

### 발행 규칙

| 항목 | 계약 |
| --- | --- |
| 발행 시점 | 주문·포인트 차감 트랜잭션 commit 후 |
| P0 발행 방식 | 한 번 발행 시도, Outbox와 재시도 없음 |
| 소비 대상 | 인기 메뉴 집계 consumer와 데이터 수집 플랫폼 Mock consumer |

### P0 전달 한계

- DB commit과 Kafka 발행은 원자적이지 않으므로 commit 후 프로세스 장애 시 이벤트가 유실될 수 있다.
- Kafka 재전달로 같은 이벤트가 중복 소비되면 P0의 인기 점수가 중복 증가할 수 있다.
- 발행 실패는 주문 API의 성공 결과를 취소하지 않는다.
- P1에서 Transactional Outbox, 게시 재시도와 consumer 중복 방지를 도입한다.

## 8. 확장 지점

다음 항목은 현재 API 계약이나 P0 완료 조건에 포함하지 않는다.

- 회원가입, 로그인, 권한 확인과 인증 사용자 기반 `userId` 결정
- 주문 조회, 여러 메뉴·수량, 취소와 환불
- 포인트 원장, 포인트 만료와 충전 이력 조회
- 멱등성 키
- P1 Transactional Outbox, Kafka 재시도와 consumer 중복 방지
- P2 인기 메뉴 MySQL fallback과 Redis 재구성
