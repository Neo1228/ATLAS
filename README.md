# ATLAS

전체 이커머스 플랫폼의 구매·판매 흐름과 거래 정합성·장애 복구를 검증하는 로컬 학습 프로젝트. 현재 일반 구매 흐름과 한정판매 경로를 함께 개발.

- 일반 구매: Seller-SKU → Buyer-wide Cart·Seller별 PurchaseOrder → Fake PSP 결제 → Reorder·수동 fulfillment·배송 전 부분 환불·Delivered return.
- 한정판매: Seller-SKU 재고, FIFO/K 대기열, location-aware reservation·주문 routing.
- Seller는 fulfillment location을 관리하며 각 Seller 주문은 모든 line을 처리할 단일 location을 사용합니다. 위치 선택은 priority·UUID 순이며 DEFAULT는 기존 재고의 보존 매핑입니다. 실제 인증·tenant 격리, live payment/refund, carrier·주소 기반 routing, cloud 배포, production SLO/capacity는 미구현.
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
| [EXP-016](docs/03-experiments/EXP-016-buyer-checkout-comparison.md) | one-seller/two-seller의 p50/p95/p99, HTTP RPS와 checkout TPS 비교. Fresh DB, profile별 3 × 120초, 1,002 sessions; 오류/dropped `0/0`. 5초 별도 smoke에서 order cardinality, inventory conservation, payment capture, settlement balance/allocation 검증. | `2.78 sessions/s`는 합성 입력. 운영 SLO·capacity 추정 아님. 상세 방법과 JSON 증거는 [EXP-016](docs/03-experiments/EXP-016-buyer-checkout-comparison.md). |
| [Issue #95 측정 메모](https://github.com/Neo1228/ATLAS/issues/95#issuecomment-6605721385) | 만료 대상 10/100/300건에서 Order 중앙값 247.1/1,540.2/5,225.4ms, Admission 10.2/10.4/20.1ms. | 로컬 데이터 규모·현재 구현의 측정치. 운영 traffic/SLO를 뜻하지 않음. |

추가 부하·용량 검증은 별도 승인과 범위 결정 전까지 수행하지 않습니다. [실험 보고서](docs/03-experiments/)를 참조하세요.

## 모듈 구조

한 번에 실행되는 Spring Boot modular monolith. 물리 디렉터리는 저장소 root의 `ATLAS-*`; `settings.gradle`은 Gradle project path를 유지.

| 디렉터리 | Gradle project | 책임 |
|---|---|---|
| [ATLAS-application](ATLAS-application) | `:application-module` | HTTP, 설정, 공통 오류 처리, Flyway migration |
| [ATLAS-catalog](ATLAS-catalog) | `:catalog-module` | Seller, Product, Seller-SKU, 현재 가격 |
| [ATLAS-cart](ATLAS-cart) | `:cart-module` | Buyer 단위 Cart, Seller-SKU별 CartItem |
| [ATLAS-campaign](ATLAS-campaign) | `:campaign-module` | Campaign과 판매 자격 |
| [ATLAS-admission](ATLAS-admission) | `:admission-module` | Seller-SKU FIFO/K, ticket, expiry |
| [ATLAS-inventory](ATLAS-inventory) | `:inventory-module` | Seller location·공유 재고 pool, reservation·transfer |
| [ATLAS-order](ATLAS-order) | `:order-module` | BuyerCheckout, Seller별 PurchaseOrder/OrderItem·location snapshot, fulfillment·refund·return 조정 |
| [ATLAS-payment](ATLAS-payment) | `:payment-module` | Payment attempt와 Fake PSP 상태 |
| [ATLAS-settlement](ATLAS-settlement) | `:settlement-module` | Gross capture 원장과 Seller Order allocation; local refund reversal |

- 모듈 간 호출은 공개 `.service`/DTO 계약 사용. Entity·repository 직접 참조 금지.
- CRUD는 JPA, PostgreSQL 특화 동시성 SQL은 모듈 내부 `.repository`에서 처리.
- Location routing은 모든 Seller 주문 line을 만족하는 active location 하나를 priority ASC, UUID ASC로 정합니다. Reservation/PurchaseOrder가 선택 위치를 보존하고 return-restock도 이를 사용합니다. 기존 재고는 V16에서 DEFAULT로 매핑 ([D-072](docs/00-governance/decision-log.md), [ADR-0017](docs/02-decisions/adr/ADR-0017-seller-fulfillment-locations.md)).
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

일반 구매는 Buyer-wide Cart와 Seller별 주문. 한 Cart checkout은 BuyerCheckout 1건, Seller당 PurchaseOrder 1건, Fake PSP attempt/capture 1회를 사용. 한정판매 Campaign은 별도 구매 경로.
- Location 관리: Seller 생성 `POST /api/sellers/{sellerId}/fulfillment-locations`, 목록 `GET /api/sellers/{sellerId}/fulfillment-locations`, 수정 `PUT /api/sellers/{sellerId}/fulfillment-locations/{locationId}`.
- 위치별 재고 조회 `GET /api/sellers/{sellerId}/inventory/locations/{locationId}`·조정 `POST /api/sellers/{sellerId}/inventory/locations/{locationId}/skus/{skuId}/capacity-adjustments`. 기존 `/api/sellers/{sellerId}/inventory/{skuId}/capacity-adjustments`는 `DEFAULT` location 대상.
- 재고 이전 `POST /api/sellers/{sellerId}/inventory/transfers`와 `Idempotency-Key`. Checkout 라인은 Seller 주문별 한 location에서 처리. 우선순위 ASC, 동률 UUID ASC ([D-072](docs/00-governance/decision-log.md), [ADR-0017](docs/02-decisions/adr/ADR-0017-seller-fulfillment-locations.md)).

1. Seller 생성: `POST /api/sellers`.
2. Product 등록: `POST /api/sellers/{sellerId}/products`.
3. Product에 SKU·가격 등록: `POST /api/sellers/{sellerId}/products/{productId}/skus`.
4. 상품 조회: `GET /api/products` 또는 Seller별 `GET /api/sellers/{sellerId}/products`.
   - Pagination: `limit` defaults to 10, maximum 100. Pass `nextCursor` as `cursor` for the next page.
   - Cursor 적용 범위: `sellerId` filter와 정규화된 `query`. 둘 중 하나가 다르면 HTTP 400 ProblemDetail (`code: INVALID_REQUEST`, `detail: Invalid product cursor`). `limit` 변경은 허용.
5. Cart line 설정: `PUT /api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}`. Line identity는 `(sellerId, skuId)`.
   - Line 삭제: `DELETE /api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}`. 전체 Cart 비우기: `DELETE /api/buyers/{buyerRef}/cart/items`. Cart는 유지되며 재고는 바뀌지 않음.
6. Buyer checkout: `POST /api/buyers/{buyerRef}/cart/checkout`과 `Idempotency-Key`. 재고 예약은 전체 Seller에 대해 all-or-nothing.
7. Parent Checkout과 Seller별 Order 조회: `GET /api/checkouts/{checkoutId}`, `GET /api/orders/{orderId}`.
8. 결제 조정: `POST /api/checkouts/{checkoutId}/payment`; 미확정 결과는 `/reconcile`, 미결제 취소는 `/cancel`. 모두 같은 checkout attempt를 사용.
9. Fulfillment는 Seller별 PurchaseOrder당 1건. Seller는 `PUT /api/sellers/{sellerId}/orders/{orderId}/fulfillment`로 순차 상태 전이; Buyer는 `GET /api/orders/{orderId}/fulfillment`로 조회. `DELIVERED`는 local simulation.
10. Pre-shipment refund: `POST /api/sellers/{sellerId}/orders/{orderId}/refund-records` with `Idempotency-Key` and OrderItem quantities; summary `GET /api/sellers/{sellerId}/orders/{orderId}/refund-record`. `UNFULFILLED`/`IN_PROGRESS` only; local ledger, no PSP refund or money movement ([ADR-0013](docs/02-decisions/adr/ADR-0013-pre-shipment-refund-recording.md)).
11. Delivered return: Buyer requests at `POST /api/buyers/{buyerRef}/orders/{orderId}/returns`; Seller records receipt and per-OrderItem `PASSED`/`FAILED` at `PUT /api/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/receipt` and `/inspection`. Only passed quantities receive local refund/restock; failed quantities stay claimed. Status: `GET /api/buyers/{buyerRef}/orders/{orderId}/returns/{returnId}`. No PSP refund or money movement ([ADR-0016](docs/02-decisions/adr/ADR-0016-buyer-wide-cart-multi-seller-checkout.md)).
Order-level 환불 summary는 모든 OrderItem의 누적 환불 수량이 주문 수량에 도달해야 `FULLY_RECORDED`가 됩니다. 한 item만 전량 환불한 경우 summary는 `PARTIALLY_RECORDED`이며 남은 item의 fulfillment는 계속 가능합니다.

Cart는 Buyer 소유이며 여러 Seller의 SKU·수량을 포함할 수 있음. Checkout은 현재 Seller-SKU·가격을 재검증하고 OrderItem snapshot 저장. 하나라도 재고 부족이면 전체 checkout rollback. 취소·만료 후 Cart 자동 복원 없음; terminal Seller Order를 명시적으로 Reorder. `RESERVED` checkout은 `POST /api/checkouts/{checkoutId}/cancel`로 취소하며 모든 예약을 해제 ([D-063/D-071](docs/00-governance/decision-log.md), [Q-011](docs/01-requirements/requirements-index.md#assumptions-and-open-questions)).

한정판매는 별도 Campaign 경로. Seller-SKU 공유 재고와 FIFO/K 대기열을 유지. `sellerId`와 `buyerRef`는 합성 식별자이며 인증·인가 수단이 아님.

## Database 시작 정책

V6/V7은 clean-start 전용. V8 Reorder, V9 fulfillment, V10 capture ledger, V11 pre-shipment refund, V12 inventory audit, V13 durable Fake PSP, V14 BuyerCheckout·Buyer-wide Cart, V15 delivered return/inspection schema. V14는 기존 commerce row가 있으면 중단하며 mapping/backfill 없음. 근거: [D-055/D-071](docs/00-governance/decision-log.md), [ADR-0006](docs/02-decisions/adr/ADR-0006-v6-clean-start.md), [ADR-0016](docs/02-decisions/adr/ADR-0016-buyer-wide-cart-multi-seller-checkout.md).
