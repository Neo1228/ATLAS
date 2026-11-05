# ATLAS

전체 이커머스 플랫폼의 구매·판매 흐름과 거래 정합성·장애 복구를 검증하는 로컬 학습 프로젝트. 현재 일반 구매 흐름과 한정판매 경로를 함께 개발.

- 일반 구매: Seller-SKU → Cart → Order → Fake PSP 결제 → 명시적 Reorder·수동 fulfillment·배송 전 부분 환불 기록.
- 한정판매: Seller-SKU shared inventory, FIFO/K 대기열, 주문·결제 정합성.
- 미구현: 실제 사용자 인증·tenant 격리, live payment/refund, carrier 연동, cloud 배포, production SLO/capacity.
- 요구사항: [Requirements Index](docs/01-requirements/requirements-index.md)
- 결정: [Decision Log](docs/00-governance/decision-log.md)
- 로컬 실행 및 검증: 아래 [환경 설정](#로컬-실행-환경)
- 작업 규칙: [AGENTS.md](AGENTS.md)
- GitHub 양식: [PR template](.github/PULL_REQUEST_TEMPLATE.md), [Issue templates](.github/ISSUE_TEMPLATE)
- CI: [Backend checks](.github/workflows/backend-ci.yml), [Markdown lint·PR checklist](.github/workflows/docs-lint.yml)

## 현재 검증 결과

| 실측 자료 | 결과 | 범위와 한계 |
|---|---|---|
| [EXP-013](docs/03-experiments/EXP-013-gate4-20-minute-calibration.md) | Local Buyer flow에서 inventory 보존 확인 | K=1 profile에서 WAITING/409 미관찰. Queue contention·capacity 입증 아님 |
| [EXP-014](docs/03-experiments/EXP-014-id-strategy-comparison.md) | UUIDv7 parent PK index 3,178,496 bytes; UUIDv4 4,407,296 bytes. Insert p50 211.49/212.64ms로 유사 | PostgreSQL 18.6, 합성 100k parent/200k child. 단일 local 비교; production 성능 근거 아님 |
| [EXP-015](docs/03-experiments/EXP-015-catalog-listing-performance.md) | 합성 상품 10k/100k baseline. 100k에서 Global p50 18.172ms, Seller 2.950ms, broad/needle 검색 17.751/16.162ms. | 1 VU, cache-warm local 비교. 운영 RPS·용량 예측 아님. |
| [Issue #95 측정 메모](https://github.com/Neo1228/ATLAS/issues/95#issuecomment-6605721385) | 만료 대상 10/100/300건에서 Order 중앙값 247.1/1,540.2/5,225.4ms, Admission 10.2/10.4/20.1ms. | 로컬 데이터 규모·현재 구현의 측정치. 운영 traffic/SLO를 뜻하지 않음. |

추가 부하·용량 검증은 별도 승인과 범위 결정 전까지 수행하지 않습니다. [실험 보고서](docs/03-experiments/)를 참조하세요.

## 모듈 구조

한 번에 실행되는 Spring Boot modular monolith. 물리 디렉터리는 저장소 root의 `ATLAS-*`; `settings.gradle`은 Gradle project path를 유지.

| 디렉터리 | Gradle project | 책임 |
|---|---|---|
| [ATLAS-application](ATLAS-application) | `:application-module` | HTTP, 설정, 공통 오류 처리, Flyway migration |
| [ATLAS-catalog](ATLAS-catalog) | `:catalog-module` | Seller, Product, Seller-SKU, 현재 가격 |
| [ATLAS-cart](ATLAS-cart) | `:cart-module` | Seller별 Buyer Cart와 CartItem |
| [ATLAS-campaign](ATLAS-campaign) | `:campaign-module` | Campaign과 판매 자격 |
| [ATLAS-admission](ATLAS-admission) | `:admission-module` | Seller-SKU FIFO/K, ticket, expiry |
| [ATLAS-inventory](ATLAS-inventory) | `:inventory-module` | 공유 재고, 수량 reservation |
| [ATLAS-order](ATLAS-order) | `:order-module` | Cart/Campaign checkout, Order·OrderItem·fulfillment 및 refund-record lifecycle, 결제 조정 |
| [ATLAS-payment](ATLAS-payment) | `:payment-module` | Payment attempt와 Fake PSP 상태 |
| [ATLAS-settlement](ATLAS-settlement) | `:settlement-module` | capture·refund reversal의 내부 이중기입 원장; 실제 PSP 정산·환불·송금 아님 |

- 모듈 간 호출은 공개 `.service`/DTO 계약 사용. Entity·repository 직접 참조 금지.
- CRUD는 JPA, PostgreSQL 특화 동시성 SQL은 모듈 내부 `.repository`에서 처리.
- Order capture는 Settlement에 gross KRW 원장을 기록하고, 배송 전 환불 기록은 불변 reversal journal로 남깁니다. 양쪽 모두 OrderItem price/quantity snapshot, 멱등 처리, PostgreSQL transaction 적용 ([D-066/D-068](docs/00-governance/decision-log.md), [ADR-0011](docs/02-decisions/adr/ADR-0011-gross-capture-settlement-ledger.md), [ADR-0013](docs/02-decisions/adr/ADR-0013-pre-shipment-refund-recording.md)).
- 배송 전 부분 환불은 OrderItem 수량과 KRW price snapshot으로 local refund record 및 append-only reversal을 남깁니다. `executionMode: LOCAL_LEDGER_ONLY`는 실제 PSP 요청이나 고객 자금 이동이 없음을 뜻합니다. 상태 전이는 기존 `OrderState.PAID`와 분리하며, 환불 수량만 재고로 복원 ([D-068](docs/00-governance/decision-log.md), [ADR-0013](docs/02-decisions/adr/ADR-0013-pre-shipment-refund-recording.md)).
- 생성 primary ID는 UUIDv7. 근거·한계: [D-056](docs/00-governance/decision-log.md), [ADR-0007](docs/02-decisions/adr/ADR-0007-uuidv7-identifiers.md).
- Schema는 Flyway 소유, Hibernate는 `ddl-auto: validate`.
## 로컬 실행 환경

필요: JDK 25, Docker Compose, PostgreSQL container.

```sh
export JAVA_HOME="<JDK 25 home>"
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait postgres
./gradlew :application-module:bootRun
```

- API bind address: `127.0.0.1:8080`.
- 전체 compile·Checkstyle·unit/integration tests: `./gradlew --no-daemon check :application-module:bootJar`.
- Colima에서 Testcontainers 연결이 필요하면 현재 Docker context의 `DOCKER_HOST`와 `/var/run/docker.sock` override를 사용. 자세한 규칙은 [AGENTS.md](AGENTS.md#개발-명령) 참조.

## 기본 구매 흐름

현재 첫 일반 구매 여정은 한 Seller의 상품을 Cart에 담아 하나의 Order로 checkout하는 경로. 실제 사용자 인증·다중 Seller 통합 주문은 제외.

1. Seller 생성: `POST /api/sellers`.
2. Product 등록: `POST /api/sellers/{sellerId}/products`.
3. Product에 SKU·가격 등록: `POST /api/sellers/{sellerId}/products/{productId}/skus`.
4. 상품 조회: `GET /api/products` 또는 Seller별 `GET /api/sellers/{sellerId}/products`.
   - 잘못된 Product 목록 cursor는 HTTP 400 ProblemDetail `code: INVALID_REQUEST`, `detail: Invalid product cursor`를 반환합니다.
5. Cart 수량 설정: `PUT /api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}`.
   - Cart 항목 삭제: `DELETE /api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}`는 한 항목, 같은 경로에서 `/items`는 전체 항목을 제거합니다. Cart는 유지되며 재고는 변경되지 않습니다.
6. Cart checkout: 같은 경로의 `POST /checkout`, `Idempotency-Key` 헤더 포함.
7. 결과 확인: `GET /api/orders/{orderId}`.
8. Reorder: terminal `CART` Order에서 `POST /api/orders/{orderId}/reorder` 호출. `Idempotency-Key`와 `{"confirmExistingCartMerge":true}` 포함. 현재 Seller-SKU와 재고를 확인하고, 기존 Cart가 비어 있지 않으면 확인 후 병합. Reorder는 재고를 예약하지 않으며 Checkout에서 다시 확인 ([FR-006/BR-005/INV-005](docs/01-requirements/requirements-index.md#business-rules-and-domain-invariants)).
9. Fulfillment 상태: 결제된 Order에 주문당 1건 생성. Seller는 `PUT /api/sellers/{sellerId}/orders/{orderId}/fulfillment`로 `IN_PROGRESS` → `SHIPPED` → `DELIVERED` 전이. Buyer는 `GET /api/orders/{orderId}/fulfillment`로 조회. `DELIVERED`는 carrier 확인이 아닌 local simulation ([FR-007/BR-006](docs/01-requirements/requirements-index.md), [ADR-0010](docs/02-decisions/adr/ADR-0010-order-fulfillment-lifecycle.md)).
10. 배송 전 부분 환불 기록·조회: `POST /api/sellers/{sellerId}/orders/{orderId}/refund-records`에 `Idempotency-Key`와 OrderItem ID·수량 전송; 조회는 `GET /api/sellers/{sellerId}/orders/{orderId}/refund-record`. `UNFULFILLED`/`IN_PROGRESS`만 허용. `executionMode: LOCAL_LEDGER_ONLY`; 실제 PSP 호출·현금 반환 아님 ([FR-008/BR-008](docs/01-requirements/requirements-index.md#business-rules-and-domain-invariants), [ADR-0013](docs/02-decisions/adr/ADR-0013-pre-shipment-refund-recording.md)).
Order-level 환불 summary는 모든 OrderItem의 누적 환불 수량이 주문 수량에 도달해야 `FULLY_RECORDED`가 됩니다. 한 item만 전량 환불한 경우 summary는 `PARTIALLY_RECORDED`이며 남은 item의 fulfillment는 계속 가능합니다.

Cart는 같은 Seller의 여러 SKU·수량을 지원. Checkout은 현재 Seller-SKU와 가격을 재검증하고 상품명·SKU·KRW 단가·수량을 OrderItem에 snapshot. 재고 예약은 모든 SKU에 대해 원자적으로 처리하며 하나라도 부족하면 전체 rollback. 가격 변경은 `PUT /api/sellers/{sellerId}/products/{productId}/skus/{skuId}/price`. `RESERVED` 주문은 `POST /api/orders/{orderId}/cancel`로 취소 가능하고, 취소·기한 만료 시 모든 재고 예약 해제. Cart 자동 복원은 하지 않으며, 이전 주문의 재주문은 명시적인 `POST /api/orders/{orderId}/reorder`로 처리 ([Q-011](docs/01-requirements/requirements-index.md#assumptions-and-open-questions)).

한정판매는 별도 Campaign 경로. 기존 Seller-SKU shared inventory와 FIFO/K 대기열을 유지하고 Catalog의 SKU 및 Admission의 K policy를 참조. `sellerId`와 `buyerRef`는 합성 식별자이며 인증·인가 수단이 아님.

## Database 시작 정책

V6는 기존 schema에서 Seller-SKU 구조로 전환하며, 기존 데이터가 있으면 DDL 전에 중단. V7은 Product·Cart·OrderItem을 추가하며 V6 application table에 행이 있으면 중단. V8은 Reorder 멱등 기록, V9는 `order_fulfillment`와 기존 `PAID` Order의 `UNFULFILLED` 초기 row, V10은 gross capture 내부 원장 테이블, V11은 배송 전 refund record와 inventory restore·append-only reversal을 추가. V10/V11은 과거 결제의 원장·refund를 소급 backfill하지 않음. V6/V7은 legacy data를 자동 mapping·초기화하지 않음. 새 로컬 database에서 시작하거나 필요한 데이터를 앱 밖에서 명시적으로 보관한 뒤 비운 database에서 진행. 근거: [D-055](docs/00-governance/decision-log.md), [D-065](docs/00-governance/decision-log.md), [D-066](docs/00-governance/decision-log.md), [D-068](docs/00-governance/decision-log.md), [ADR-0006](docs/02-decisions/adr/ADR-0006-v6-clean-start.md), [ADR-0008](docs/02-decisions/adr/ADR-0008-catalog-cart-order-boundaries.md), [ADR-0010](docs/02-decisions/adr/ADR-0010-order-fulfillment-lifecycle.md), [ADR-0011](docs/02-decisions/adr/ADR-0011-gross-capture-settlement-ledger.md), [ADR-0013](docs/02-decisions/adr/ADR-0013-pre-shipment-refund-recording.md).
