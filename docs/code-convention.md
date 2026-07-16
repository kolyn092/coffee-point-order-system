# Code Convention

## 1. 패키지 구조

기본 패키지는 `com.coffeepointordersystem`이다. 기능 중심으로 먼저 나누고, 기능 안에서 계층을 나눈다.

```text
com.coffeepointordersystem
├── domain
│   └── <feature>
│       ├── controller
│       ├── dto
│       ├── entity
│       ├── facade
│       ├── port
│       ├── repository
│       └── service
├── global
│   ├── config
│   ├── database
│   ├── error
│   └── security
└── infra
    └── <externalsystem>
```

## 2. 기본 코드 스타일

### 2.1 파일 형식

- 모든 소스와 텍스트 파일은 BOM 없는 UTF-8로 저장한다.
- 줄 끝은 LF를 사용한다. Windows 실행 파일인 `*.bat`만 CRLF를 허용한다.
- 모든 파일은 마지막 LF로 끝낸다.
- 빈 줄을 포함해 줄 끝에 탭이나 공백을 남기지 않는다.
- 한 줄은 최대 120자로 작성한다. `package`와 `import` 선언, 표의 행은 120자를 넘어도 나누지 않는다.
- 한 파일의 줄 끝 문자를 섞지 않는다.

파일 종류별 들여쓰기는 다음과 같다.

| 파일 | 들여쓰기 |
| --- | --- |
| `*.java` | 하드 탭 1개, 표시 너비 4칸 |
| `*.gradle` | 스페이스 4칸 |
| `*.yaml`, `*.yml` | 스페이스 2칸, 탭 금지 |
| `*.sql` | 스페이스 4칸 |
| `*.md` | 문법에 필요한 스페이스 사용 |

### 2.2 이름

- 식별자는 영문, 숫자와 규칙상 허용된 언더스코어만 사용한다.
- 한국어 발음을 로마자로 옮긴 이름 대신 도메인 의미가 드러나는 영어 이름을 사용한다.
- 한국 고유명사가 꼭 필요하면 공식 영문 표기나 팀이 합의한 하나의 표기를 일관되게 사용한다.
- 패키지는 소문자만 사용하고 대문자나 언더스코어를 넣지 않는다.
- 클래스와 인터페이스는 UpperCamelCase를 사용한다.
- 클래스는 명사 또는 명사구, 인터페이스는 명사·명사구 또는 형용사·형용사구로 이름 짓는다.
- 메서드, 필드, 지역 변수와 매개변수는 lowerCamelCase를 사용한다.
- 메서드는 원칙적으로 동사로 시작한다. 변환 메서드의 `to`, 빌더 메서드의 `with`는 허용한다.
- 상수는 UPPER_SNAKE_CASE를 사용한다.
- 테스트 클래스 이름은 단수형 `Test`로 끝낸다.
- 테스트 메서드에는 동작과 기대 결과를 구분하기 위한 언더스코어를 허용한다.
- 한 글자 변수는 짧은 반복문 인덱스나 짧은 람다 매개변수처럼 생명주기가 매우 짧을 때만 허용한다.
- 약어를 모두 대문자로 유지하지 않는다.

### 2.3 역할별 타입 이름

역할이 있는 Spring 타입은 이름만 보고 책임을 알 수 있게 한다.

| 역할 | 형식 | 예시 |
| --- | --- | --- |
| HTTP 진입점 | `<Domain>Controller` | `OrderController` |
| 유스케이스 | `<Domain>Service` | `PointService` |
| 복잡한 유스케이스 조정 | `<UseCase>ApplicationService` | `CreateOrderApplicationService` |
| 하위 기능의 단순화된 진입점 | `<Domain>Facade` | `OrderFacade` |
| 영속성 접근 | `<Domain>Repository` | `MenuRepository` |
| 별도 조회 모델 | `<Purpose>QueryRepository` | `PopularMenuQueryRepository` |
| 요청 DTO | `<Action><Domain>Request` | `CreateOrderRequest` |
| 응답 DTO | `<Domain><Purpose>Response` | `OrderResponse` |
| 설정 바인딩 | `<Purpose>Properties` | `PopularCacheProperties` |
| 설정 클래스 | `<Purpose>Config` | `DbRoutingConfig` |
| 예외 | `<Cause>Exception` | `InsufficientPointException` |
| 단위 테스트 | `<Target>Test` | `OrderServiceTest` |
| 통합 테스트 | `<Target>IntegrationTest` | `OrderIntegrationTest` |
| 동시성 테스트 | `<Target>ConcurrencyTest` | `PointConcurrencyTest` |

`Manager`, `Helper`, `Processor`, `Util`, `Common`처럼 책임을 넓게 숨기는 이름은 피한다. 해당 이름이 필요하다면
한 문장으로 설명할 수 있는 단일 책임과 소유 도메인을 먼저 정한다.

## 3. 선언과 import

- Java 소스 파일 하나에는 탑레벨 타입을 하나만 선언한다.
- 한 줄에는 문장 하나, 선언문 하나에는 변수 하나만 둔다.
- 배열 대괄호는 변수명이 아니라 타입에 붙인다. 예: `String[] names`.
- `long` 리터럴에는 대문자 `L`을 붙인다.
- 줄바꿈·탭 등은 숫자 escape 대신 `\n`, `\t` 같은 전용 escape를 사용한다.

클래스 멤버는 기본적으로 상수, static 필드, 인스턴스 필드, 생성자, 정적 팩터리, public 메서드,
protected/package-private 메서드, private 메서드, 중첩 타입 순으로 배치한다. 밀접하게 협력하는 메서드는
찾기 쉽도록 가까이 둘 수 있다.

## 4. 들여쓰기, 중괄호와 공백

- Java 들여쓰기는 하드 탭을 사용하고 탭 표시 너비는 4칸으로 맞춘다.
- 블록이 한 단계 깊어질 때마다 탭 한 단계만 추가한다.
- 여는 중괄호는 선언과 같은 줄에 두는 K&R 스타일을 사용한다.
- `else`, `catch`, `finally`, `do-while`의 `while`은 앞 블록의 닫는 중괄호와 같은 줄에 둔다.
- 조건문과 반복문은 본문이 한 줄이어도 중괄호를 생략하지 않는다.
- 내용이 없는 블록은 의도가 명확할 때 `{}`로 한 줄에 쓸 수 있다.
- 제어문 키워드와 `(` 사이에는 공백을 넣고, 메서드·생성자 이름과 `(` 사이에는 넣지 않는다.
- 콤마와 `for` 구분용 세미콜론은 뒤에만 공백을 둔다.
- 이항·삼항 연산자 양쪽에는 공백을 두고 단항 연산자와 피연산자 사이에는 두지 않는다.
- 타입 캐스팅 괄호 안에는 공백을 넣지 않는다. 예: `(long) value`.
- 제네릭의 `<` 뒤와 `>` 앞에는 공백을 두지 않는다.
- 향상된 `for`와 삼항 연산자의 콜론 양쪽에는 공백을 둔다.
- 인라인 `//` 앞과 주석 기호 뒤에는 공백을 둔다.
- `package` 선언 뒤, import 그룹 사이와 메서드 사이에는 빈 줄을 둔다.

```java
@Service
public class OrderService {
	private static final long MAX_POINT_BALANCE = 100_000_000L;

	private final OrderRepository orderRepository;

	public OrderService(OrderRepository orderRepository) {
		this.orderRepository = orderRepository;
	}

	public OrderResponse createOrder(CreateOrderCommand command) {
		if (command == null) {
			throw new IllegalArgumentException("command must not be null");
		}

		Order order = Order.create(command.userId(), command.menuId());
		Order savedOrder = orderRepository.save(order);
		return OrderResponse.from(savedOrder);
	}
}
```

## 5. 줄바꿈

- 120자를 넘기기 전에 의미 단위로 줄을 나눈다.
- 이어지는 줄은 시작 줄보다 최소 한 단계 더 들여쓴다.
- `extends`, `implements`, `throws`와 여는 소괄호 뒤 또는 콤마 뒤에서 줄을 나눌 수 있다.
- 메서드 체인은 점(`.`) 앞에서 줄을 나눈다.
- 수식과 조건식은 연산자 앞에서 줄을 나눈다.
- 여러 인수를 세로로 나열하면 닫는 괄호도 별도 줄에 두어 구조를 드러낸다.
- 복잡한 조건을 줄바꿈만으로 버티지 말고 의미 있는 메서드나 변수로 추출한다.

```java
PopularMenuResponse response = popularMenuService
		.findPopularMenus(
			windowFrom,
			windowTo
		);

boolean reusable = idempotency.requestHash().equals(requestHash)
		&& idempotency.status() == IdempotencyStatus.COMPLETED;
```

## 6. Java 작성 원칙

- 클래스와 메서드는 SRP(Single Responsibility Principle)를 지키고 하나의 응집된 책임과 주된 변경 이유를 가진다.
- 코드 줄 수나 의존 객체 수만으로 책임을 나누지 않는다. 하나의 유스케이스를 위한 조정은 하나의 책임이 될 수 있다.
- 서로 다른 정책, 변경 주기 또는 행위자를 위한 로직이 함께 바뀐다면 책임을 별도 타입이나 메서드로 분리한다.
- 변경 가능한 상태와 공개 범위를 최소화한다. 필드는 기본적으로 `private final`을 우선한다.
- 생성자 주입을 사용한다. Spring 필드 주입은 사용하지 않는다.
- DTO와 값 객체는 프레임워크 제약이 없다면 불변 객체 또는 `record`를 우선한다.
- `record`, enum과 애너테이션 타입은 UpperCamelCase, record component는 lowerCamelCase를 사용한다.
- enum 상수는 UPPER_SNAKE_CASE를 사용하고, switch expression의 `->` 양쪽에는 공백을 둔다.
- `Optional`은 값이 없을 수 있는 반환 타입에만 사용한다. 필드, 매개변수와 컬렉션 원소로 사용하지 않는다.
- 컬렉션 반환값은 `null` 대신 빈 컬렉션을 사용한다.
- 매직 넘버와 반복 문자열은 이름 있는 상수 또는 설정으로 이동한다.
- 포인트와 결제 금액에 `float` 또는 `double`을 사용하지 않는다. 현재 정수 포인트 모델은 `long`과 checked
  arithmetic를 사용하고 상·하한을 함께 검증한다.
- `equals`로 비교해야 하는 값을 `==`로 비교하지 않는다. enum 비교에는 `==`를 사용할 수 있다.
- catch 블록을 비워 두거나 예외를 원인 없이 새 예외로 바꾸지 않는다.
- 현재 Java toolchain보다 높은 버전의 문법이나 preview 기능을 사용하지 않는다.
- 새 라이브러리는 표준 JDK와 기존 의존성으로 해결할 수 없는지 먼저 확인한다.

## 7. 계층별 책임

### 7.1 Controller와 DTO

- Controller는 요청 역직렬화, 형식 검증, 인증 정보 전달과 응답 매핑만 담당한다.
- Controller에서 영속성 API를 직접 호출하거나 트랜잭션·도메인 규칙을 구현하지 않는다.
- 공개 API에는 영속 엔티티를 직접 노출하지 않고 요청·응답 DTO를 사용한다.
- 입력 형식은 Bean Validation 등 경계 계층에서 검증하고, 상태에 따른 도메인 검증은 service에서 수행한다.
- HTTP status, 에러 코드, 필드명과 validation은 `docs/API.md`를 그대로 따른다.

### 7.2 Service

- Service의 public 메서드는 하나의 유스케이스와 트랜잭션 경계를 표현한다.
- Service 클래스는 같은 도메인과 변경 이유를 가진 유스케이스를 응집해 묶는다.
- 검증 순서와 실패 우선순위는 PRD와 API 계약을 유지한다.
- 다른 service와의 호출이 길게 연결되면 책임과 트랜잭션 경계를 다시 검토한다.
- 내부 구현 세부사항을 Controller가 조립하도록 넘기지 않는다.

### 7.3 Application Service와 Facade

- 단순한 유스케이스는 기존 Service에서 처리하고, 복잡도가 실제로 증가했을 때만 별도 패턴을 도입한다.
- 여러 도메인 객체, service, repository 또는 외부 port를 하나의 유스케이스로 조정해야 하면 Application Service를
  사용할 수 있다. Application Service는 실행 순서와 트랜잭션 경계를 책임지고 도메인 정책은 도메인 객체나
  해당 책임의 service에 위임한다.
- 여러 하위 service나 기능을 호출자에게 단순한 하나의 진입점으로 제공해야 하면 Facade를 사용할 수 있다.
- Facade는 호출 인터페이스를 단순화하는 역할에 집중한다. 새로운 도메인 규칙을 숨기거나 트랜잭션 경계를
  암묵적으로 바꾸지 않는다.
- 같은 유스케이스만을 위해 여러 협력자를 조정하는 것은 그 자체로 SRP 위반이 아니다. 서로 다른 변경 이유가
  섞이기 시작할 때 책임을 분리한다.
- 전달만 하는 `Facade → ApplicationService → Service` 계층을 관성적으로 만들지 않는다. 실질적인 조정,
  경계 단순화 또는 의존성 격리 중 하나가 있을 때만 계층을 추가한다.

### 7.4 Repository와 외부 연동

- Repository는 영속성 조회·저장에 집중하고 HTTP 응답이나 유스케이스 흐름을 알지 않는다.
- 복잡한 DB 전용 조회는 목적이 드러나는 QueryRepository로 분리한다.
- 쿼리 결과의 정렬과 경계 조건은 호출자 추측에 맡기지 않고 계약과 쿼리에 명시한다.
- 외부 시스템 연동은 인터페이스 뒤에 두고 timeout과 실패 의미를 명시한다.
- 테스트 편의를 위해 운영 코드에 우회 분기나 전역 mutable 상태를 추가하지 않는다.

### 7.5 공통 타입

- 공통 에러 코드는 `global.error.ErrorCode` 한 곳에서 관리하고 `docs/API.md`의 코드와 일치시킨다.
- 도메인 예외는 에러 코드와 원인을 보존하며, `GlobalExceptionHandler`가 HTTP 응답으로 변환한다.
- 공통 API 응답은 `global.response.ApiResponse`으로 관리한다. 성공 응답은 `code: "SUCCESS"`와 `data`를, 오류
  응답은 `code`, `message`를 반환하며 값이 없는 필드는 직렬화하지 않는다.
- 상속만을 위한 `BaseController`, `BaseService`, `BaseRepository`는 만들지 않는다.
- 두 곳에서 우연히 같은 코드가 보인다는 이유만으로 공통화하지 않는다. 책임과 변경 이유가 같을 때만 공유한다.

## 8. 트랜잭션과 데이터 접근 코드

- 트랜잭션 경계는 원칙적으로 public service 메서드에 둔다.
- 같은 클래스 내부 호출로 `@Transactional`이 우회되지 않게 한다. private 메서드의 애너테이션에 의존하지 않는다.
- 트랜잭션의 `readOnly`, isolation, propagation과 master 강제 여부는 호출부에서 추측하지 않도록 유스케이스 경계에
  명시한다. 구체적인 라우팅과 정합성 정책은 PRD와 성능 계획을 따른다.
- 라우팅을 HTTP method로 결정하지 않는다. 트랜잭션 속성과 명시적인 master 강제 정책을 사용한다.
- rollback이 필요한 예외를 잡아 정상 반환하지 않는다. checked exception을 사용한다면 rollback 정책을 명시한다.
- `REQUIRES_NEW`와 수동 트랜잭션은 원자성이나 라우팅 의미를 바꿀 수 있으므로 근거와 테스트 없이 추가하지 않는다.
- 외부 네트워크 호출을 DB 트랜잭션 안에 둘 때는 PRD가 요구하는 원자성과 timeout 영향을 먼저 확인한다.
- 공유 데이터의 다중 인스턴스 동기화를 `synchronized`, JVM 로컬 락이나 로컬 캐시에 의존하지 않는다.
- 영속 시각을 서버 로컬 기본 시간대로 만들지 않는다. DB와 JDBC 시간대 정책은 PRD와 ERD를 따른다.

트랜잭션 구조를 바꾸는 변경은 정상 경로뿐 아니라 rollback, timeout, deadlock과 경합 경로를 함께 검증한다.

## 9. DB와 Flyway

- 스키마 변경은 엔티티·쿼리, Flyway migration, `docs/ERD.md`와 관련 테스트를 같은 변경에서 맞춘다.
- ERD의 숫자 surrogate PK는 Flyway에서 `BIGINT NOT NULL AUTO_INCREMENT`로 정의하고 JPA 엔티티에서 `@Id`와
  `@GeneratedValue(strategy = GenerationType.IDENTITY)`를 함께 사용한다. 문자열 자연키에는 생성 전략을 적용하지
  않는다.
- 적용된 migration은 수정하거나 재사용하지 않고 새 버전을 추가한다.
- migration 파일은 `V<version>__<description>.sql` 형식으로 작성한다.
- 테이블·컬럼·인덱스·제약 이름은 ERD의 snake_case 규칙을 따른다.
- 애플리케이션 검증만 믿지 않고 금액 범위, 상태 조합, 고유성과 참조 무결성을 DB 제약으로 방어한다.
- Flyway 실행 대상과 영속 시간대는 이 문서에서 다시 정의하지 않고 PRD, 성능 계획과 실제 설정을 따른다.
- MySQL 고유 문법과 동작을 H2 결과만으로 검증 완료 처리하지 않는다.
- native SQL은 바인딩 매개변수를 사용한다. 사용자 입력을 문자열 연결로 조립하지 않는다.

## 10. 예외, 로깅과 보안

- 예상 가능한 도메인 실패는 의미 있는 예외와 중앙 오류 매핑을 통해 `docs/API.md`의 에러 코드로 변환한다.
- Controller마다 같은 예외 변환 로직을 반복하지 않는다.
- 내부 클래스명, SQL, stack trace와 민감한 값을 API 응답에 노출하지 않는다.
- 빈 catch, `printStackTrace`, `System.out`과 `System.err`를 사용하지 않는다.
- 로그는 SLF4J placeholder를 사용하고 문자열 연결을 피한다.
- 로그 수준은 운영자가 취할 행동을 기준으로 고른다. 재시도 가능한 예상 실패를 모두 `ERROR`로 남기지 않는다.
- 비밀번호, 토큰, 실제 접속 정보, 원문 `Idempotency-Key`, 민감한 요청·응답 body를 로그에 남기지 않는다.
- 같은 예외를 여러 계층에서 중복 기록하지 않는다. 처리하거나 의미를 바꾸는 경계에서 한 번 기록한다.
- 비밀값은 환경 변수나 승인된 secret 저장소로 주입하고 코드·문서·테스트 fixture에 실제 값을 넣지 않는다.

## 11. 주석과 문서화

- 주석은 코드가 무엇을 하는지 반복하지 않고 선택 이유, 불변식, 동시성 전제와 실패 시 의미를 설명한다.
- 복잡한 lock 순서, 트랜잭션 원자성, outbox 소유권과 Redis owner-token claim·단조 증가 latest 게시에는 전제가
  드러나는 주석을 남긴다.
- 공개 타입이라도 이름과 계약이 자명하면 형식적인 Javadoc을 강제하지 않는다.
- 외부에서 호출하는 공용 API나 오해하기 쉬운 반환·예외 계약에는 Javadoc을 작성한다.
- `TODO`에는 가능한 경우 이슈 번호나 제거 조건을 함께 남긴다. 담당자 이름만 적은 영구 TODO는 만들지 않는다.
- 공개 API 변경은 `docs/API.md`, 도메인 정책 변경은 `docs/PRD.md`, 데이터 모델 변경은 `docs/ERD.md`를
  같은 변경에서 갱신한다.
- 설정 키나 기본값을 바꾸면 모든 profile 설정과 관련 문서를 함께 확인한다.

## 12. 테스트

### 12.1 공통 원칙

- JUnit Platform과 JUnit Jupiter를 사용하며 버전은 `build.gradle`의 Spring Boot BOM을 따른다.
- 테스트는 서로 독립적이어야 하며 실행 순서와 이전 테스트의 상태에 의존하지 않는다.
- 하나의 테스트는 하나의 동작이나 하나의 실패 원인을 검증한다.
- 준비, 실행, 검증 단계가 보이도록 구성한다. 구분이 필요하면 `given`, `when`, `then` 주석을 사용할 수 있다.
- 테스트 이름은 대상 동작과 기대 결과를 드러낸다.
- 테스트 식별자는 영어로 작성하고 `@DisplayName`에는 한국어를 사용할 수 있다.
- 시간, 난수와 외부 응답은 제어 가능하게 주입한다. 동기화를 위해 임의의 긴 `sleep`에 의존하지 않는다.
- 실패한 검증을 통과한 것으로 보고하지 않는다.

```java
@Test
void chargePoint_rejectsAmountAboveSingleChargeLimit() {
	// given
	long amount = 1_000_001L;

	// when
	Throwable thrown = catchThrowable(() -> pointService.charge(USER_ID, amount));

	// then
	assertThat(thrown).isInstanceOf(PointChargeLimitExceededException.class);
}
```

### 12.2 테스트 범위

- 순수 계산, validation, 상태 전이와 오류 우선순위는 빠른 단위 테스트로 검증한다.
- Spring context가 필요 없는 단위 테스트에 `@SpringBootTest`를 사용하지 않는다.
- 웹 계약은 요청·응답 field, validation, HTTP status와 에러 코드를 검증한다.
- 영속성과 트랜잭션 테스트는 커밋과 rollback 후 DB 상태와 모든 부수효과를 함께 검증한다.
- MySQL lock, `CHECK`, `SKIP LOCKED`, 격리 수준과 Redis TTL·경합은 Testcontainers 기반 MySQL 8.0.16+
  및 Redis로 검증한다.
- 필요한 Testcontainers 모듈이 없으면 관련 통합 테스트를 구현하는 변경에서 `build.gradle` 의존성을 함께 추가한다.
- H2 또는 mock 결과만으로 MySQL·Redis 고유 동작의 검증을 완료하지 않는다.
- 외부 데이터 플랫폼만 결과와 장애를 제어할 수 있는 mock client로 대체한다.
- 동시성 테스트는 여러 thread만 만드는 데 그치지 않고 실제 경합 시작점, 최종 상태와 중복 부수효과를 검증한다.
- 버그 수정에는 가능하면 수정 전 실패하고 수정 후 통과하는 회귀 테스트를 먼저 추가한다.
