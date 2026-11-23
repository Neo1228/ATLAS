# ADR-0071: 작은 campaign·cart 모듈을 admission·order로 합친다

- 상태: Accepted(구현·검증 완료)
- 날짜: 2026-11-24
- 관련 결정: [ADR-0069](ADR-0069-layered-naming-and-runtime-configuration.md), [ADR-0059](ADR-0059-independent-inventory-location-service.md)
- 추적: [Issue #526](https://github.com/Neo1228/ATLAS/issues/526)

## 문제

Gradle 모듈 11개 중 `campaign`(클래스 8개)과 `cart`(클래스 10개)는 모듈마다 `build.gradle`, 경계 테스트 허용 목록, 패키지 어휘를 따로 유지한다. 모듈 분리는 의존 방향을 강제하고 독립 배포를 가능하게 할 때 값을 한다. 이 두 모듈은 그 근거가 약하다.

## 측정

2026-10-01 이후 171개 커밋(병합 제외)과 현재 `main`에서 잰 값이다.

| 항목 | campaign | cart |
|---|---|---|
| main 클래스 | 8 | 10 |
| 소유 테이블 | 1 | 2 |
| 리소스·마이그레이션 | 없음 | 없음 |
| Gradle 의존 | catalog | catalog |
| 가져다 쓰는 모듈 | admission, application | order, application |
| 바꾼 커밋 | 9 | 7 |

모듈 쌍별 함께 변경 비율(Jaccard)은 가장 큰 `application`+`order`가 0.30이고 나머지는 0.1~0.2이다. 함께 변경 비율만으로는 병합 근거가 없다. 병합 근거는 크기가 작고, 의존이 한쪽으로만 있으며, 독립 배포나 별도 리소스가 없다는 점이다.

## 대안과 절충

- 모든 모듈을 합친다: `payment`와 `settlement`는 서로 의존하지 않고 `order`가 둘 다 쓴다. 합치면 결제와 정산의 경계를 잃는다. 채택하지 않는다.
- `inventory-location`을 `inventory`로 합친다: ADR-0059가 별도 프로세스와 별도 데이터베이스로 정했다. 채택하지 않는다.
- 그대로 둔다: 모듈 11개의 유지 비용이 남는다. 이번에는 근거가 모인 두 쌍만 합친다.

## 결정

`campaign`을 `admission`에, `cart`를 `order`에 합친다. 패키지는 각각 `com.atlas.admission.*`, `com.atlas.order.*`로 바꾸고 표준 패키지 어휘(`dto.request`, `service` 등)를 그대로 쓴다. 클래스 이름 충돌이 없어 이름은 바꾸지 않는다. 합친 뒤 모듈은 9개다.

## 검증과 한계

`./gradlew clean check`(JDK 25, Testcontainers PostgreSQL)로 검증한다. 이 결정은 성능이나 배포 구조를 바꾸지 않는다. 경계 테스트의 허용 목록은 합친 모듈의 규칙 하나로 정리했다. 이전 ADR과 실험 기록은 당시 모듈 이름을 그대로 둔다.
