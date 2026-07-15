# API 명세서

## 1. 개요

<!-- 개요 -->

## 2. 공통 규칙

### Base URL

```text
/api
```

### 공통 에러 응답 형식

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청 값이 올바르지 않습니다."
}
```

## 조회 API

<!-- 설명 -->

### Request

```http
GET /api/menus
```

#### Request Example

```http

```

#### Request Headers

| Name | Required | Description |
| --- | --- | --- |
| Content-Type | Y | `application/json` |

#### Path Variable

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| userId | Long | Y | 사용자 ID |

#### Query Parameter

없음.

### Response

#### Status

```http
200 OK
```

#### Response Body

```json
[
  {
    "menuId": 1,
    "name": "Americano",
    "price": 4500
  },
  {
    "menuId": 2,
    "name": "Cafe Latte",
    "price": 5000
  }
]
```

#### Response Field

| Name | Type | Description |
| --- | --- | --- |
| `[].menuId` | Long | 메뉴 ID |
| `[].name` | String | 메뉴 이름 |
| `[].price` | Long | 메뉴 가격 |

### Error Code

| HTTP Status | Code | Description |
| --- | --- | --- |
| 500 | INTERNAL_SERVER_ERROR | 서버 내부 오류 |
