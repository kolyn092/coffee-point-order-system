# 커피 포인트 주문 시스템

다수 서버 환경에서 포인트로 커피를 주문하고, 주문 완료 이벤트를 기반으로 인기 메뉴를 조회하는
Spring Boot 애플리케이션이다. 제품 요구사항과 단계별 범위는 [PRD](docs/PRD.md), HTTP·Kafka
계약은 [API 명세](docs/API.md), 데이터 모델은 [ERD](docs/ERD.md)에 기록한다.

## 실행 환경

- Java 17
- Spring Boot 4.1.0
- Docker Desktop과 Docker Compose
- MySQL 8.0.16 이상
- Redis 7.2 이상
- Kafka 3.9 이상

## 로컬 실행

저장소 루트에서 다음 명령으로 MySQL, Redis와 Kafka를 실행한다.

```shell
docker compose up -d
docker compose ps
```

서비스 포트는 MySQL `3306`, Redis `6379`, Kafka `29092`이다. 애플리케이션은 기본값으로 호스트의
세 서비스에 연결하며, MySQL이 정상화되면 Flyway migration을 적용하고 JPA 스키마를 검증한다.

```shell
.\gradlew.bat bootRun
```

기본 연결 설정은 다음 환경 변수로 바꿀 수 있다.

| 환경 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `DB_HOST` | `localhost` | MySQL 호스트 |
| `DB_PORT` | `3306` | MySQL 포트 |
| `DB_NAME` | `coffee_point_order` | MySQL 데이터베이스 |
| `DB_USERNAME` | `coffee` | MySQL 애플리케이션 사용자 |
| `DB_PASSWORD` | `coffee` | MySQL 애플리케이션 비밀번호 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `KAFKA_PORT` | `29092` | Compose가 호스트에 공개하는 Kafka 포트 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka bootstrap 서버 |

`docker compose down -v`는 로컬 데이터 볼륨까지 삭제하므로 migration을 처음부터 다시 적용할 때만
사용한다. MySQL, Redis 또는 Kafka 연결이 준비되지 않으면 기동 명령의 실패 원인을 확인하고 인프라를
먼저 복구한다. P0에서는 연결 실패를 Redis fallback이나 임의 재시도로 숨기지 않는다.

## 데이터 모델과 API

MySQL을 메뉴, 포인트 계정과 주문의 단일 원본으로 사용한다. M0 migration은 다음 테이블과 API 예시의
초기 메뉴 데이터를 생성한다.

| ID | 메뉴 | 가격 |
| --- | --- | ---: |
| 1 | 아메리카노 | 4500 |
| 2 | 카페라떼 | 5000 |

상세한 PK·FK·`CHECK` 제약·인덱스와 UTC 시각 정책은 [ERD](docs/ERD.md)를 따른다. HTTP API는
[API 명세](docs/API.md)를 기준으로 하며, `GET /api/v1/menus`, 포인트 충전, 주문·결제와 인기 메뉴
조회 계약을 포함한다.

## 설계 의도와 문제 해결 전략

### 데이터 일관성

- MySQL을 최종 원본으로 두고 메뉴, 포인트 계정과 주문에 DB 제약을 적용한다.
- 사용자 식별값을 `point_accounts`의 기본 키로 사용해 사용자별 계정 하나를 보장하고, 해당 행을
  포인트 충전과 주문의 잠금 대상으로 사용한다.
- 주문과 포인트 차감은 같은 트랜잭션에서 처리한다. 주문 실패 시 차감도 함께 rollback한다.
- `orders`는 결제 당시 가격을 저장해 메뉴 가격이 바뀌어도 주문 이력이 변하지 않게 한다.
- 애플리케이션·JDBC·MySQL session timezone을 UTC로 고정하고 `DATETIME(6)` 정밀도를 보존한다.

### 이벤트와 조회 모델

Kafka는 주문 트랜잭션 commit 후 `order.completed` 이벤트를 전달하는 인프라이고, Redis는 일자별
Sorted Set으로 인기 메뉴를 조회하는 파생 모델이다. M0에서는 연결 기반만 준비하고 주문 이벤트
발행·소비 로직은 후속 마일스톤에서 구현한다. P0의 Redis 장애는 실패로 처리하며, MySQL fallback과
Redis 재구성은 P2에서 도입한다.

### 다중 서버와 확장성

애플리케이션은 JVM 로컬 락이나 로컬 캐시 없이 stateless로 동작한다. 모든 인스턴스가 같은 MySQL의
트랜잭션·제약·행 잠금을 사용하므로 포인트와 주문 정합성을 공유한다. Redis와 Kafka는 MySQL 원본을
대체하지 않는 비동기·조회 확장 지점으로 분리한다. 이벤트 유실과 중복 소비를 줄이는 Transactional
Outbox와 consumer 중복 방지는 P1의 책임으로 남긴다.

## 개발 단계와 현재 범위

| 단계 | 범위 |
| --- | --- |
| P0 | API, MySQL, Kafka, Redis와 Compose를 포함한 기본 주문·인기 메뉴 흐름 |
| P1 | Transactional Outbox, Kafka 재시도와 consumer 중복 방지 |
| P2 | 성능 측정, Redis fallback·재구성과 운영 관측 |

현재 이슈의 M0 범위는 기준 문서, Flyway migration, Docker Compose와 애플리케이션 연결 설정이다.
메뉴·포인트·주문·인기 메뉴 API는 `M1 → M4` 순서로 구현한다.

## 검증

```shell
.\gradlew.bat test
docker compose up -d
docker compose ps
.\gradlew.bat bootRun
```

통합 테스트는 Testcontainers로 MySQL 8.0.36을 실행해 migration, 초기 데이터, 제약, 인덱스와 UTC
마이크로초 저장을 검증한다. Docker가 실행되지 않은 환경에서는 해당 통합 테스트를 실행할 수 없으므로
성공으로 간주하지 않고 환경을 먼저 확인한다.
