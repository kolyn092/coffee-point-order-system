# ADR-0011: 개발 환경의 Kafka 진단에 로컬 Kafka UI를 사용한다

- 상태: 채택됨
- 기록 유형: 신규
- 기록일: 2026-07-20
- 결정일: 2026-07-20
- 관련 요구사항: [`docs/PRD.md` P2 M9 부하 테스트와 운영 관측 정책](../PRD.md#p2-m9-부하-테스트와-운영-관측-정책)
- 관련 마일스톤: M9
- 관련 이슈: [#40](https://github.com/kolyn092/coffee-point-order-system/issues/40)
- 대체 대상: [ADR-0010](0010-use-k6-for-load-test-observability.md)의 대시보드 미도입 범위 중 개발용 Kafka 진단

## 맥락

M9은 k6, 두 애플리케이션 인스턴스와 JSON·Markdown 결과로 부하와 장애 복구를 재현한다. 이 방식은 정식 부하
측정과 판정을 위한 기준으로 유지한다. 다만 로컬 개발 중에는 `order.completed` 메시지, 3개 partition과
`popular-menu` Consumer Group의 offset·lag를 빠르게 확인할 수 있는 수동 진단 수단이 필요하다.

Kafka UI를 추가하면 개발자가 broker 중단 또는 Redis 처리 실패 뒤의 lag 변화를 화면에서 확인할 수 있다. 반면
Kafka UI는 토픽 관리 기능도 제공하므로, 외부 공개나 운영 관측 도구로 확장하면 보안 경계와 운영 범위가 달라진다.

## 결정 동인과 불변 조건

- `order.completed` 이벤트 schema, Producer·Consumer, Outbox, Redis 정책을 바꾸지 않는다.
- Kafka UI는 개발용 `docker-compose.yml`에만 두고 `127.0.0.1`에만 바인딩한다.
- `docker-compose.load-test.yml`, 운영 배포, 인증·인가, 대시보드 설정과 알림은 이 결정의 범위에서 제외한다.
- k6의 5초 관측 수집과 JSON·Markdown 결과는 M9의 재현 가능한 판정 기준으로 유지한다.

## 검토한 선택지

| 순서 | 선택지 | 장점 | 단점·실패 위험 | 되돌리기 비용 |
| --- | --- | --- | --- | --- |
| 1 | 개발 Compose에 로컬 Kafka UI를 추가한다. | 수동 진단이 빠르다. | 외부 노출 시 운영·보안 범위가 커진다. | 서비스·문서를 제거한다. |
| 2 | Kafka CLI와 로그만 사용한다. | 컨테이너와 UI 노출이 없다. | #40의 수동 관찰 요구를 충족하지 못한다. | 별도 비용이 없다. |
| 3 | 부하 테스트 Compose에 대시보드·알림을 추가한다. | 시각화를 자동화한다. | M9의 원시 결과 범위를 넘어선다. | 수집·보안·운영 문서를 되돌린다. |

## 결정

개발용 `docker-compose.yml`에 Kafka UI를 추가한다. UI는 Compose 내부 broker 주소 `kafka:9092`에 연결한다.
호스트 접속 포트는 `127.0.0.1:${KAFKA_UI_PORT:-8080}`으로 제한한다.

Kafka UI는 `order.completed`의 메시지·key·partition과 `popular-menu` Consumer Group의 Consumer 구성,
partition별 current offset·log end offset·lag를 수동으로 확인하는 용도다. `KAFKA_UI_PORT`를 변경한 경우에는
설정한 포트로 접속한다.

이 결정은 ADR-0010의 k6 기반 M9 관측 방식이나 대시보드·알림 미도입 원칙 전체를 대체하지 않는다. 개발용 Kafka
진단 UI에 한해서만 제한된 예외를 둔다.

## 결과와 트레이드오프

### 기대 효과

- Redis 처리 실패와 Kafka broker 복구 뒤의 Consumer lag 변화를 로컬에서 빠르게 확인할 수 있다.
- UI가 Compose 내부 Kafka listener에 연결되므로 호스트 주소를 컨테이너 내부에서 잘못 사용하는 구성을 피한다.
- UI 포트를 loopback에만 노출해 개발 환경 밖의 접근 범위를 넓히지 않는다.

### 허용한 단점과 위험

- 개발 Compose에 컨테이너 하나와 이미지 의존성이 추가된다.
- Kafka UI는 진단 보조 수단일 뿐 M9의 측정·판정 결과나 운영 대시보드를 대체하지 않는다.
- loopback 바인딩을 제거하거나 운영 Compose에 같은 서비스를 추가하려면 보안·운영 요구사항을 다시 결정해야 한다.

## 검증 방법

- `docker compose config --quiet`로 Kafka UI의 Compose 문법, loopback 포트와 `kafka:9092` 연결 설정을 확인한다.
- 개발 Compose에서 UI가 `local` cluster에 연결되는지 수동 확인한다.
- `order.completed`의 메시지·3개 partition과 `popular-menu`의 partition별 offset·lag 표시를 수동 확인한다.
- Redis 중단·복구와 Kafka broker 중단·복구 뒤 Consumer lag가 증가했다가 0으로 돌아오는지 README 절차로 확인한다.

## 대체 조건

- 운영 환경에서도 Kafka UI 또는 다른 관측 도구를 외부에 공개해야 하는 경우
- Kafka UI에 인증·인가, 역할 기반 권한, 영속 설정 또는 알림을 추가해야 하는 경우
- M9의 k6·JSON·Markdown 관측 기준을 다른 운영 관측 체계로 대체해야 하는 경우
