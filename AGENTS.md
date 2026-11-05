# Repository Guidelines

## Project Overview

- ATLAS is a local learning project for a full e-commerce platform: catalog, cart, orders, limited-sale queues, inventory, payment-state recovery, fulfillment, and internal settlement.
- Current flows use synthetic Seller/Buyer identifiers, PostgreSQL, and Fake PSP. They are not a production service or live-payment integration.
- No real customers, personal data, cash movement, seller payout, carrier integration, or cloud deployment.
- Distinguish verified code, owner decisions, synthetic inputs, and measured results. Never present a test input as market demand or production capacity.
- Current scope and status: [README](README.md). Business requirements and unresolved policies: [Requirements Index](docs/01-requirements/requirements-index.md). Durable decisions: [Decision Log](docs/00-governance/decision-log.md).

## Architecture & Data Flow

- One deployable Spring Boot modular monolith. Gradle projects map to root `ATLAS-*` directories; modules are not independent services.
- `application-module` owns HTTP adapters, application configuration, error mapping, and Flyway migrations.
- Domain ownership:

| Module | Owns |
|---|---|
| `catalog-module` | Seller, Product, Seller-SKU, current price |
| `cart-module` | Seller-scoped Cart and CartItem |
| `campaign-module` | Campaign and sale eligibility |
| `admission-module` | FIFO queue, ticket, checkout slot and queue policy |
| `inventory-module` | Shared stock pool, reservations and quantity conservation |
| `order-module` | Cart/Campaign checkout, Order/OrderItem, fulfillment and workflow coordination |
| `payment-module` | PaymentAttempt and Fake PSP state |
| `settlement-module` | Internal gross-capture and local refund-reversal ledger |

- Module calls use public `.service` and DTO contracts. Never depend on another module’s entity or repository.
- Standard Cart flow: Cart → validate current Seller-SKU/price → snapshot OrderItems → atomically reserve all lines → clear Cart → separately submit payment.
- Campaign flow: open Campaign → validate queue eligibility → assign a durable FIFO sequence/checkout slot → create Campaign Order → reserve inventory → use the same Order payment path.
- Confirmed capture claims stock and records internal settlement. `PENDING`/`UNKNOWN` keeps the same attempt and stock hold until reconciliation. Fulfillment and refund-record states are separate from `OrderState`.
- Invariant: `available + reserved + claimed = initial_capacity`; all counters nonnegative. Reserve before payment; claim only after confirmed capture; release only after confirmed non-payment/required void.
- PSP calls and DB commits are separate effects. Do not hold a DB transaction around a real network call. Fake PSP is local simulation and its current provider state is in memory unless a later approved change says otherwise.
- Seller IDs and `buyerRef` are synthetic identifiers, not authentication, authorization, or tenant isolation.

## Key Directories

| Path | Purpose |
|---|---|
| `ATLAS-application/` | Spring Boot entry point, HTTP, config and Flyway migrations |
| `ATLAS-catalog/`, `ATLAS-cart/`, `ATLAS-campaign/`, `ATLAS-admission/` | Catalog, buyer cart, campaign and queue modules |
| `ATLAS-inventory/`, `ATLAS-order/`, `ATLAS-payment/`, `ATLAS-settlement/` | Stock, commerce workflow, Fake PSP and internal ledger modules |
| `docs/00-governance/` | Decision log and reference register |
| `docs/01-requirements/` | Functional requirements, invariants and open questions |
| `docs/02-decisions/adr/` | Accepted Architecture Decision Records |
| `docs/03-experiments/` | Reproducible performance/ID experiments and limits |
| `load-tests/` | k6 scripts; use only with an approved workload and matching flow |
| `.github/workflows/`, `.github/ISSUE_TEMPLATE/` | CI, lint, Issue/PR automation and templates |
| `config/checkstyle/`, `gradle/` | Checkstyle and Gradle Wrapper configuration |

## Development Commands

- Prerequisites: JDK 25 and Docker Compose. PostgreSQL image/version: 18.6.
- Set the JDK and start the local database:

```sh
export JAVA_HOME="<JDK 25 home>"
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait postgres
```

- Run the application: `./gradlew :application-module:bootRun`
- Full compile, Checkstyle, unit/integration checks and executable archive: `./gradlew --no-daemon check :application-module:bootJar`
- Markdown lint: `npx --yes markdownlint-cli2@0.24.0 "**/*.md" "*.md"`
- API binds to `127.0.0.1:8080` by default. Colima users may need `DOCKER_HOST` from the active Docker context; do not hard-code a machine-specific socket.
- Do not use `view` commands. Use static file reads and focused searches.

## Code Conventions & Common Patterns

- Java implementation uses imports, never FQCN. Checkstyle runs in Gradle `check`.
- Keep module packages cohesive: public `.service`, `.dto.request`, `.dto.response`, `.exception`; internal JPA `.persistence.entity`, lifecycle `.persistence.state`, module-owned `.repository`, workflow `.service.impl`.
- Prefer constructor injection. Lombok 1.18.48 is limited to approved mechanical `@Getter` and simple `@RequiredArgsConstructor`; no `@Data`, broad `@Setter`, builder, or generated entity equality.
- JPA for ordinary CRUD. PostgreSQL-specific atomic/locking SQL stays in the owning module’s repository and writes only tables owned by that module.
- Flyway owns schema; Hibernate uses `ddl-auto: validate`. Assign migration versions centrally when parallel work is selected.
- Use one typed exception/Code enum per module; `GlobalExceptionHandler` returns RFC 9457 `ProblemDetail` with a stable `code`. Unexpected 500 responses must not expose internal details.
- Keep GET/query methods read-only. Expiry, retries and queue promotion belong to a state-changing operation or scheduler.
- Preserve service transaction boundaries, idempotency and domain invariants. Test concurrency and PostgreSQL-specific behavior against PostgreSQL.
- Avoid CQRS, CommandBus, handlers/mediators, generic base frameworks, cross-cutting `common` modules, and unnecessary one-class packages.
- Compare JDK/Spring/PostgreSQL features, maintained libraries, and direct implementation. Prefer a suitable maintained library over custom code when it reduces complexity; record compatibility, runtime/test/build scope, security/licensing, alternatives and evidence.
- Primary identifiers follow the approved UUIDv7 policy. Check D-056 and existing migrations before introducing or changing identifier generation.
- `@Transactional` services proxied by CGLIB must not be `final`; add a Spring context/integration test for proxy-sensitive behavior.

## Important Files

- `settings.gradle` — Gradle project names and `ATLAS-*` directory mappings.
- `build.gradle`, `gradle/wrapper/gradle-wrapper.properties` — Java toolchain, dependency management, Checkstyle and Gradle Wrapper.
- `ATLAS-application/src/main/java/com/atlas/AtlasApplication.java` — Spring Boot entry point and scheduling.
- `ATLAS-application/src/main/resources/application.yml`, `compose.yaml` — local runtime and PostgreSQL defaults; use local-only credentials.
- `ATLAS-application/src/main/resources/db/migration/` — versioned schema changes. Never edit an applied migration; add a new version.
- `ATLAS-application/src/main/java/com/atlas/application/controller/` — HTTP adapters; transport DTOs belong here only when fields/validation differ from the module request.
- `.github/workflows/backend-ci.yml`, `.github/workflows/docs-lint.yml` — authoritative CI commands/check names.
- `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/` — mandatory tracking templates and PR checklist.
- `docs/03-experiments/EXP-013-*` is historical: it tested an older V1–V5/Campaign setup and does not validate current catalog/cart/order flow. `EXP-014` is a bounded local UUID comparison, not a general performance guarantee.

## Runtime/Tooling Preferences

- Required toolchain in repository: Java 25; Gradle Wrapper 9.8.2; Spring Boot 4.1.2; PostgreSQL 18.6.
- Use the checked-in Gradle Wrapper rather than a global Gradle installation. Application runtime dependencies use `implementation`; PostgreSQL/Flyway database support is runtime-scoped; Lombok is `compileOnly` + `annotationProcessor`; test tools stay test-scoped.
- Test stack: JUnit Platform, Spring Boot tests, Testcontainers PostgreSQL 18.6, ArchUnit 1.5.1. Backend CI runs Java 25 and verifies Docker before integration tests.
- Node 22 is used in docs CI for markdownlint-cli2 0.24.0; no package.json or alternative JS package-manager workflow is configured.
- Keep services local. PayPal Sandbox is only a future candidate; Fake PSP does not reproduce PayPal or remote provider guarantees.
- Add no cache, broker, search engine, worker, external service or library by default. First prove the requirement/limitation with PostgreSQL and the current stack; then compare alternatives and costs.

## Testing & QA

- Unit tests live in each `ATLAS-*/src/test`. Spring/PostgreSQL integration and architecture tests live in `ATLAS-application/src/test`.
- Key suites: `ModuleBoundaryTest`, `BuyerFlowIntegrationTest`, `InventoryConcurrencyIntegrationTest`, `PaymentLifecycleIntegrationTest`, `SettlementLedgerIntegrationTest`, `OrderRefundLifecycleIntegrationTest`, and `SellerSkuGlobalFifoMigrationIntegrationTest`.
- PostgreSQL SQL, row locks, concurrent stock changes, migrations and transaction rollback require real PostgreSQL/Testcontainers. Docker must be available; CI checks `docker info`.
- Test the exact failure boundary/invariant at risk. Avoid trivial forwarding/mapping tests, redundant assertions, and repeating the same risk at every layer. Mocks are not a substitute for PostgreSQL semantics.
- Use disposable test databases/schemas for manual SQL or migration experiments. Never use the existing `atlas` database as a destructive test target. Respect clean-start migration guards.
- Run full checks only after the final worktree is ready. If a command fails, record exact command, environment, failing scenario and remaining limitation; never describe unrun/failed tests as passed.
- Performance/capacity claims require a named workload, data shape, environment, measurement method, repeats and source/assumption limits. Existing Q-008 and EXP numbers are synthetic/report-only unless their specific test setup matches. k6 is for an approved HTTP workload; Toxiproxy only for a concrete network-failure hypothesis.
- Do not introduce a new numeric target from intuition. Use a source or local measurement; label synthetic test inputs and never present them as real demand/SLO.

## Git & Parallel Work

- Create one Issue/PR per coherent goal; do not split by endpoint/class or line count. Bundle related code, tests, migrations and authoritative docs. Do not create Issues/PRs while implementation is unfinished.
- Branch: `<type>/<issue>-<short-topic>`. Commit title: `<type>(<scope>): <한국어 요약>`; concise imperative summary in Korean.
- No direct push to `main`. PR requires all template checkboxes and required CI checks; squash merge only.
- Before edits/staging inspect `git status --short --branch`; stage only intended paths; review `git diff` and `git diff --cached`. Never use destructive reset/clean or rewrite shared history without explicit authorization and backup/lease steps.
- Parallel sessions use one plan/worktree per task with explicit source/test allowlists. No two sessions edit shared requirements/decision/README/reference/migration/build files concurrently; appoint one integration owner for those. Rebase only after reviewing the shared diff and rerun required checks.
- If the owner says a plan is reference-only, keep it uncommitted in its assigned local worktree; do not publish it or create tracking items for the plan alone.
- PR/Issue body must explain context, chosen behavior, alternatives, state/monetary/inventory effects, limitations and verification; link to exact requirements/decision records. Do not write unexplained IDs or claim unverified outcomes.
