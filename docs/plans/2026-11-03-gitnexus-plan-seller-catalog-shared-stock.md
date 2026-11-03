# GitNexus Engineering Plan

> Task: One Issue/PR for Seller=Tenant, Seller-owned SKU, shared Seller-SKU stock and global FIFO.
> Baseline/source investigation pinned to commit 72c572e2e799cf26a9e315903324cb9646f0af54; graph evidence applies only to that pre-change baseline. Final ATLAS-* layout and linked SSOT are in commits 13b9f33, 071a616, 4b070d8, and 9649a23 and 8012b9f; the earlier modules/ intermediate is superseded. PR #90 is open. Post-change review is source-based; no post-change graph claim.
> Evidence provenance schema 2; global dirty digest 0a9c85780067d9afcd0764f307b60891e3cee927ee11eaeb5ec7826d10fd82cd; cited-path manifest 29 sorted entries; exact generated plan path excluded.

현재 적용 금지: 이 계획의 legacy mapping/aggregate migration 제안은 [D-055](../00-governance/decision-log.md)·[ADR-0006](../06-decisions/adr/ADR-0006-v6-clean-start.md)로 supersede되었다. 기록은 D-049 당시 계획의 이력으로 남기며, V6는 empty-start만 허용하고 nonempty V1–V5 application table에서 DDL 전에 실패한다. 기존 local data는 앱 밖에서 명시적으로 보관·reset한다. Seller-SKU shared stock/global FIFO/K 결정은 유지.

## 1. Objective

Implement the owner-confirmed local Seller/Tenant, Seller-owned SKU, shared stock and Seller-SKU global FIFO design as one workstream. No cloud deployment, authentication, external library or load-test scope.

## 2. Current Behaviour

- [verified] Campaign API accepts `skuId` and `inventoryCapacity`; `CampaignServiceImpl#create` saves Campaign and creates pool `(campaignId, skuId)` (`CampaignController.java:30-34`; `CampaignServiceImpl.java:41-54`).
- [verified] Inventory reserves one unit per order and handles `RESERVED → CLAIMED/RELEASED`; payment capture claims, eligible failure/cancel/expiry releases (`InventoryServiceImpl.java:58-109`; `OrderServiceImpl.java:74-105,232-282`).
- [verified] Admission join locks a Campaign counter, increments Campaign sequence, checks Campaign-held slots against Campaign `maxActiveCheckouts`, then creates a ticket (`AdmissionServiceImpl.java:50-85`).
- [verified] Release/expiry promote within Campaign; JDBC FIFO query filters `campaign_id` and orders by sequence (`AdmissionServiceImpl.java:128-163,196-205`; `JdbcQueueRepository.java:21-61`).
- [verified] V1 uniqueness is `(campaign_id, sequence)` and `(campaign_id, idempotency_key)`. Campaign currently stores queue TTL and active cap (`V1__campaign_admission.sql:14-50`; `Campaign.java`).
- [verified] No Seller identity exists in current Campaign, ticket, order or inventory keys. Existing tests cover Campaign FIFO, stock oversell/conservation and payment lifecycle.

## 3. Relevant Architecture

- [verified] Modules: application, campaign, admission, inventory, order, payment. Admission already depends on campaign-module; order coordinates admission/inventory/payment.
- [owner-confirmed] Seller, SKU and Campaign remain in campaign-module. Add no Gradle module. Inventory owns stock/reservation state; Admission owns queue state; application owns HTTP/Flyway.
- [verified] `ModuleBoundaryTest` enforces explicit package dependencies. Keep package responsibilities clear within the existing module graph.

## 4. GitNexus Findings

- [verified] `node .gitnexus/run.cjs analyze --index-only --pdg` refreshed index at HEAD `72c572e`; output `changed=4, added=0, deleted=0`.
- [verified limitation] No GitNexus impact/query MCP tool is exposed. No graph blast-radius claim is used.
- [verified source flow] Campaign creation→Inventory pool; Admission join→Campaign eligibility→counter/sequence/ticket; release/expiry→promotion; Order checkout→Admission ticket→Inventory reserve; payment result→claim/release.

## 5. Statement-Level PDG Findings

No PDG query output is available. Source flow requires counter lock, sequence increment, active count, expiry and promotion to share one Seller-SKU identity; otherwise Campaigns can still admit independently or promote inconsistently. Revalidate transaction boundaries; do not infer one transaction across modules.

## 6. Proposed Changes

- **Seller/SKU in existing campaign-module:** minimal Seller registration API returns a server-generated UUID; no authentication. Seller-SKU registration sets `initialStock` and one global `maxActiveCheckouts`.
- **Campaign:** reference `(sellerId, skuId)`; keep sale status and per-Campaign `queueTicketTtl`. Remove Campaign-owned `inventoryCapacity` and `maxActiveCheckouts`.
- **Inventory:** pool key `(sellerId, skuId)`; Reservation/Order keep Campaign attribution. Preserve per-order reserve/claim/release state machine.
- **Admission:** global counter/sequence/lock/active count/expiry/FIFO promotion by Seller-SKU. New global K is set at Seller-SKU registration. Keep Campaign-scoped idempotency and ticket TTL as compatibility defaults; no legacy Campaign K aggregation.
- **Order:** propagate sellerId to shared inventory reserve; preserve payment recovery, claim and release behavior.
- **Migration (superseded):** The earlier mapping/aggregation/backfill proposal is historical only. D-055/ADR-0006 require empty-start; nonempty V1–V5 application tables fail before DDL without data/schema changes. Empty schema may be replaced with fresh Seller-SKU schema.
- **Documentation:** after implementation and tests stabilized, updated Decision Log (D-049), FR-004, Q-002/Q-003/Q-007/Q-010, business rules, invariants, README, module READMEs, ADR-0005, roadmap and source register in one linked SSOT batch.

## 7. Implementation Sequence

1. Confirm V1–V5 application tables are empty. If local data must be retained, archive/reset explicitly outside the app before fresh-start; never silently delete it.
2. Add Seller/Seller-SKU model and minimal Seller registration inside campaign-module; create SKU with initialStock/global K.
3. Update Campaign to reference existing Seller-SKU; remove per-Campaign stock creation and active cap.
4. Re-key Inventory pool and reservation SQL to Seller-SKU; retain Campaign attribution.
5. Re-key Admission lock, sequence, K, expiry and FIFO promotion to Seller-SKU; retain Campaign TTL/idempotency.
6. Propagate sellerId through ticket/Order persistence and shared inventory calls.
7. Implement V6 clean-start only per D-055/ADR-0006; no mapping, aggregation, backfill or preservation.
8. [done] Added cross-Campaign PostgreSQL concurrency/lifecycle/migration tests. `./gradlew --no-daemon check :application-module:bootJar` passed: 29 tests, 0 failures/errors/skips.
9. [done] Updated linked SSOT/docs as one batch. Keep one Issue #89 and one eventual PR; use multiple logical commits where helpful.

## 8. Test Strategy

- `BuyerFlowIntegrationTest`: two Campaigns sharing Seller-SKU get unique global sequence; at global K, later tickets wait; release/expiry/payment release promotes oldest ticket across Campaign IDs.
- Concurrent joins serialize on one Seller-SKU counter; active ticket count never exceeds global K.
- Same SKU under different Sellers has independent queue, K and stock.
- Same Campaign/idempotency key replay returns original ticket; same key in a different Campaign stays distinct.
- Campaign-specific TTL expiry updates global promotion; payment capture claims shared stock; eligible failure/cancel/expiry releases.
- `InventoryConcurrencyIntegrationTest`: cross-Campaign concurrent reserve of capacity one → exactly one succeeds; conservation holds through claim/release.
- V6 integration: nonempty V1–V5 application tables fail before DDL with data/schema unchanged; empty schema is replaced with fresh Seller-SKU schema.
- `ModuleBoundaryTest`: no Gradle module addition; inventory persistence remains private.
- Verification: `./gradlew --no-daemon check :application-module:bootJar`; Markdown lint and PR checklist in CI. No load test.

## 9. Risk and Impact Analysis

- **Identity:** sellerId request without auth is a local data identifier, not authorization/isolation.
- **Legacy data:** no mapping, aggregation or backfill. V6 does not silently erase data; explicit archival/reset is outside the app. See D-055/ADR-0006.
- **Historical queue order:** previous sequences were Campaign-local; exact cross-Campaign arrival order is unavailable. Timestamp plus stable tie-break is deterministic, not historical truth.
- **Global capacity:** K is set on new Seller-SKU registration; legacy Campaign K is not aggregated under D-055/ADR-0006.
- **Concurrency:** one counter/lock per Seller-SKU serializes joins; correctness tests only, no throughput or capacity conclusion.
- **Module scope:** campaign-module gains Seller/SKU responsibilities by owner choice; avoid unrelated Catalog expansion.
- **References/limits:** [REF-023](../00-governance/reference-register.md) covers Shopify reservation/claim consistency without importing its MySQL/location/scale details. [REF-101](../00-governance/reference-register.md) documents Alibaba Cloud flash-sale concerns; Redis, RocketMQ, cloud scale and TPS figures are not adopted. [REF-102/103](../00-governance/reference-register.md) discuss queue modeling and ordering/parallelism trade-offs in Meituan cases; they do not prescribe ATLAS global FIFO/K. External material informs trade-off analysis, while global FIFO/K remains owner-decided. Global FIFO/K are Owner choices.

## 10. Files Expected to Change

| File | Responsibility | Reason |
|---|---|---|
| `ATLAS-campaign/src/main/java/com/atlas/campaign/**` | Seller/SKU ownership, Campaign, service/response | Seller registration; initialStock/global K; Campaign SKU reference |
| `ATLAS-application/src/main/java/com/atlas/application/controller/**` and `controller/dto/**` | Seller/SKU/Campaign HTTP | Return Seller UUID; accept sellerId without auth claim |
| `ATLAS-inventory/src/main/java/com/atlas/inventory/**` | Pool key, InventoryService/Impl, JDBC stock mutation | Shared Seller-SKU pool |
| `ATLAS-admission/src/main/java/com/atlas/admission/**` | service, JDBC queue, ticket entity/repository/contracts | Global FIFO/counter/K/promotion |
| `ATLAS-order/src/main/java/com/atlas/order/**` | PurchaseOrder, OrderServiceImpl | Carry sellerId through reservation/payment |
| `ATLAS-application/src/main/resources/db/migration/V6__seller_sku_global_fifo.sql` (proposed) | seller/SKU/Campaign/Inventory/Order/Admission schema | Empty-start schema replacement; fail before DDL for existing application rows (D-055/ADR-0006) |
| `ATLAS-application/src/test/java/com/atlas/BuyerFlowIntegrationTest.java`; `ATLAS-application/src/test/java/com/atlas/inventory/InventoryConcurrencyIntegrationTest.java`; `ATLAS-application/src/test/java/com/atlas/ModuleBoundaryTest.java` | integration/concurrency/boundary scenarios | Cross-Campaign correctness |
| Linked governance/domain/API docs | Decision Log, open questions, invariants, diagrams, roadmap | SSOT update after implementation |

## 11. Reusable Implementation Context

```yaml
implementation_context:
  {"task_summary":"Seller-owned SKU with shared Seller-SKU stock and global FIFO; V6 clean-start only per D-055/ADR-0006.","acceptance_criteria":["Campaigns sharing Seller-SKU share stock and one global FIFO/K.","New Seller-SKU registration sets stock and K.","Campaign TTL and idempotency remain Campaign-scoped.","Nonempty V1–V5 application tables fail before DDL without data/schema changes; empty-start only, no mapping/backfill."]}
```

## 12. Assumptions and Open Questions

**Owner-confirmed:** Seller=Tenant; requests carry sellerId without authentication; Seller-owned SKU; initialStock and global K at SKU registration; shared stock; global Seller-SKU FIFO; K set for new Seller-SKU (no legacy K aggregation); Seller/SKU remain in campaign-module; minimal Seller registration API; one Issue #89/one PR.

**Compatibility defaults:** preserve Campaign ticket TTL and `(campaignId, idempotencyKey)` scope. Campaign remains ticket/order attribution; global sequence and active cap use Seller-SKU.

**Execution constraint:** no Campaign→Seller mapping file is required or used. Verify the database is empty; preserve any needed local data outside the app via explicit archival/reset. V6 aborts before DDL if any V1–V5 application row exists.

**Deferred:** authentication, cloud deployment, load testing, multi-location stock, canonical Product/SPU matching, refunds/settlement, DDD/CQRS, new modules and additional libraries.

## 13. Definition of Done

- Minimal Seller registration returns server UUID; request sellerId is documented as data identity only.
- Seller-SKU registration establishes initial stock and global K once; Campaign creation neither adds stock nor resets K.
- One Seller-SKU global counter/sequence/K/FIFO is enforced across Campaigns; Campaign TTL and idempotency remain explicit.
- Cross-Campaign inventory cannot oversell; payment claim/release and conservation hold.
- V6 accepts empty-start only; nonempty V1–V5 application tables fail before DDL and leave data/schema unchanged. No legacy mapping, aggregate, backfill or preservation.
- PostgreSQL tests and module-boundary checks pass; full checks and docs checks pass.
- Decision Log, Q-002/Q-003/Q-007/Q-010, invariants, API docs, diagrams and roadmap agree; one tracked Issue/PR, multiple logical commits permitted.
