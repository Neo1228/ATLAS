---
id: DOC-006
title: Reference Register
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 외부 참고자료 레지스트리

REF-001~008: 미확인 후보, 근거로 사용 전 검토 필요. REF-009~013: 확인한 GitHub 제품 문서. REF-014~022: 2026-10-29 확인한 공개 실무·기술 자료. REF-023~029: 2026-10-30 확인한 practitioner/product 자료. REF-030~042: 2026-10-30 결제, checkout, load-model 사례 확인. 범위와 한계는 각 행 참조; 외부 자료가 ATLAS 정책을 대신 결정하지 않음.

| ID | 제목 / 유형 | URL | 확인일 | ATLAS 적용 대상 | 제한점 / 상태 |
|---|---|---|---|---|---|
| REF-001 | Fowler, Architecture Decision Record / 실무 글 | https://martinfowler.com/bliki/ArchitectureDecisionRecord.html | 미확인 | ADR 기록 방식 | ? 후보; 확인 전 근거 아님 |
| REF-002 | UK GDS, Documenting architecture decisions / 정부 가이드 | https://gds-way.digital.cabinet-office.gov.uk/standards/architecture-decisions.html | 미확인 | ADR 기록 방식 | ? 후보; 확인 전 근거 아님 |
| REF-003 | C4 Model diagrams / 아키텍처 도식 안내 | https://c4model.com/diagrams | 미확인 | 시스템 구성도 | ? 후보; 확인 전 근거 아님 |
| REF-004 | EventStorming resources / 도메인 탐색 자료 | https://www.eventstorming.com/resources/ | 미확인 | 도메인 탐색 | ? 후보; 확인 전 근거 아님 |
| REF-005 | Diátaxis / 문서 방법론 | https://diataxis.fr/ | 미확인 | 문서 유형 구분 | ? 후보; 확인 전 근거 아님 |
| REF-006 | Google SRE Workbook / 신뢰성 자료 | https://sre.google/workbook/table-of-contents/ | 미확인 | 신뢰성·실험 | ? 후보; 확인 전 근거 아님 |
| REF-007 | OWASP ASVS / 보안 표준 | https://asvs.owasp.org/ | 미확인 | 보안 요구사항 | ? 버전/적용 항목 미확인 |
| REF-008 | OpenAPI Specification / API 표준 | https://spec.openapis.org/oas/latest.html | 미확인 | API 계약 | ? 구현 시점 버전 확인 |
| REF-009 | GitHub, PR template 안내 / 제품 문서 | https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/creating-a-pull-request-template-for-your-repository | 2026-10-29 | PR template | ✓ 확인; 본문을 채우지만 checkbox 완료를 강제하지 않음 |
| REF-010 | GitHub, Issue form 형식 / 제품 문서 | https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms | 2026-10-29 | Issue 입력 양식 | ✓ 확인; 응답은 Issue 본문 Markdown, PR에는 적용 안 됨 |
| REF-011 | GitHub, diagram 작성 안내 / 제품 문서 | https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams | 2026-10-29 | Issue/PR/Markdown 도식 | ✓ 확인; Mermaid 문법은 지원 버전에 따름 |
| REF-012 | GitHub, task list 안내 / 제품 문서 | https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/about-tasklists | 2026-10-29 | Issue/PR 체크박스 | ✓ 확인; checkbox 자체는 merge condition 아님 |
| REF-013 | GitHub, protected branch 안내 / 제품 문서 | https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches | 2026-10-29 | main 필수 검사 | ✓ 확인; status check 이름은 workflow 간 고유해야 함 |
| REF-014 | Google Engineering Practices, 변경 설명 작성 / 실무 자료 | https://google.github.io/eng-practices/review/developer/cl-descriptions.html | 2026-10-29 | PR what/why·맥락·한계 | ✓ 확인; 변경 이유와 미래 독자용 맥락 기록 |
| REF-015 | Google Engineering Practices, 작은 변경 / 실무 자료 | https://google.github.io/eng-practices/review/developer/small-cls.html | 2026-10-29 | commit/PR 크기 | ✓ 확인; 자체 완결 변경 우선, 줄 수 기준 없음 |
| REF-016 | ADR GitHub Community, ADR templates / 커뮤니티 자료 | https://adr.github.io/adr-templates/ | 2026-10-29 | ADR rationale/대안 | ✓ 확인; 양식마다 trade-off와 context/decision/consequence 강조 차이 |
| REF-017 | arc42, Building Block/Runtime View / 설계 문서 자료 | https://docs.arc42.org/section-5/ ; https://docs.arc42.org/section-6/ | 2026-10-29 | 구성요소·핵심 실행 흐름 | ✓ 확인; 중요/복잡/위험한 부분만 시각화 |
| REF-018 | AWS Prescriptive Guidance, Load test types / 실무 가이드 | https://docs.aws.amazon.com/prescriptive-guidance/latest/load-testing/test-types.html | 2026-10-29 | RPS·latency·concurrency와 multi-step transaction 분리 | ✓ 확인; 단위와 test 대상 경계를 명시. AWS 권고를 ATLAS 목표로 간주하지 않음 |
| REF-019 | Grafana k6, Open and closed models / 부하 모델 설명 | https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/ | 2026-10-29 | Arrival rate와 response time 관계 | ✓ 확인; k6 executor 구현 설명이며 k6 채택 결정 아님 |
| REF-020 | Google SRE Book, Monitoring Distributed Systems / SRE reference | https://sre.google/sre-book/monitoring-distributed-systems/ | 2026-10-29 | Latency·traffic·errors·saturation, RPS/TPS 예시, tail latency | ✓ 확인; Google production practice, ATLAS SLO/도구 결정 아님 |
| REF-021 | Grafana Labs, The RED Method / observability practice | https://grafana.com/blog/the-red-method-how-to-instrument-your-services/ | 2026-10-29 | Rate·Errors·Duration 및 resource USE metrics | ✓ 확인; monitoring taxonomy, tool/architecture 선택 아님 |
| REF-022 | Wolfram MathWorld, Little's Law / queueing reference | https://mathworld.wolfram.com/LittlesLaw.html | 2026-10-29 | Arrival rate·시간·in-flight 수 관계 | ✓ 확인; steady state 관계식, capacity target 아님 |
| REF-023 | Shopify Engineering, Inventory Reservations / practitioner engineering post | https://shopify.engineering/scaling-inventory-reservations | 2026-10-30 | 상품/location별 재고 식별, 임시 reservation과 claim 분리 | ✓ 검토; Shopify production 규모와 MySQL 설계는 맥락 의존. ATLAS architecture 권고 아님 |
| REF-024 | SeatGeek, Virtual Waiting Room / InfoQ QCon practitioner presentation | https://www.infoq.com/presentations/ticketing-system-virtual-waiting-room/ | 2026-10-30 | Sale 전 waiting room과 on-sale queue의 FIFO/drain rate | ✓ 검토; SeatGeek 운영 맥락, 일반적 fairness 증명 아님 |
| REF-025 | commercetools, Inventory Management / product modeling guide | https://docs.commercetools.com/learning-model-your-product-catalog/inventory-modeling/inventory-management | 2026-10-30 | Product variant/location/channel별 재고 범위와 available/reservation 구분 | ✓ 검토; vendor model의 사례, 보편적 best practice 아님 |
| REF-026 | Queue-it, Online Fairness / vendor practitioner blog | https://queue-it.com/blog/online-fairness | 2026-10-30 | 예정된 sale 전 무작위 배치 후 FIFO 제안 | ✓ 검토; vendor 관점/이해관계 존재, 일반적 fairness 근거 아님 |
| REF-027 | Brandur Leach, Implementing Stripe-like Idempotency Keys in Postgres / practitioner engineering article | https://brandur.org/idempotency-keys | 2026-10-30 | Same-attempt retry, recovery point, ambiguous external charge | ✓ 검토; Stripe-like/Postgres example, PayPal contract 아님 |
| REF-028 | Stripe Engineering, Designing robust and predictable APIs with idempotency / engineering blog | https://stripe.com/blog/idempotency | 2026-10-30 | Idempotency key로 charge retry의 중복 효과 방지 | ✓ 검토; Stripe API/SDK 사례, 타 PSP로 일반화하지 않음 |
| REF-029 | Stripe, Advanced error handling / API guidance | https://docs.stripe.com/error-low-level | 2026-10-30 | Network/5xx outcome uncertainty와 reconcile-before-new-operation | ✓ 검토; key retention/retry window는 Stripe-specific, ATLAS/PayPal 기준값 아님 |
| REF-030 | PayPal, Authorize a payment and capture funds later / API guide | https://developer.paypal.com/platforms/checkout/standard/customize/auth-capture/ | 2026-10-30 | Authorization/capture 순서 및 funds hold와 later capture 의미 | ✓ 검토; PayPal flow/eligibility·validity 조건은 provider-specific |
| REF-031 | PayPal Orders v2, Checkout payment intent / API definition | https://developer.paypal.com/api/orders/v2/definitions/checkout_payment_intent/ | 2026-10-30 | `CAPTURE` immediate capture와 `AUTHORIZE` separate capture 차이 | ✓ 검토; 3-day honor/29-day validity 및 purchase-unit 제한은 PayPal-specific, ATLAS TTL 아님 |
| REF-032 | PayPal REST API, Idempotency / API guidance | https://developer.paypal.com/api/rest/reference/idempotency/ | 2026-10-30 | `PayPal-Request-Id` retry identity | ✓ 검토; endpoint마다 support/retention이 다름; ATLAS key/retry policy 아님 |
| REF-033 | Shopify & Coinbase Engineering, Commerce Payments Protocol / practitioner article | https://shopify.engineering/commerce-payments-protocol | 2026-10-30 | Authorize/capture 사례, cancellation/partial capture trade-offs | ✓ 검토; on-chain protocol, PayPal/card flow의 direct implementation 근거 아님 |
| REF-034 | PayPal Payments v2, Capture authorized payment / API reference | https://developer.paypal.com/api/payments/v2/authorizations-capture | 2026-10-30 | Capture response, `PayPal-Request-Id`, capture `PENDING`/`DECLINED`/`COMPLETED` callbacks | ✓ 검토; endpoint/API version/eligibility는 구현 시 확인 |
| REF-035 | PayPal Payments v2, Void authorized payment / API reference | https://developer.paypal.com/api/payments/v2/authorizations-void | 2026-10-30 | Active authorization void; fully captured payment는 void 불가 | ✓ 검토; timeout/retry guarantees 아님 |
| REF-036 | PayPal, Webhook event names / API reference | https://developer.paypal.com/api/rest/webhooks/event-names/ | 2026-10-30 | Authorization voided, capture pending/declined/completed/reversed events | ✓ 검토; event delivery/order 보장이나 ATLAS state policy 아님 |
| REF-037 | PayPal, Authorization and honor period / API guidance | https://developer.paypal.com/payment-methods/auth-honor | 2026-10-30 | PayPal authorization/honor-period semantics | ✓ 검토; 29-day guide와 webhook 문서의 30-day 문구 차이 존재; ATLAS TTL 아님 |
| REF-038 | Stripe, Payment capture strategies / practitioner/product guide | https://stripe.com/resources/more/payment-capture-strategies-timing-risks-and-what-businesses-need-to-know | 2026-10-30 | Delayed capture, cancellation, failed capture trade-offs | ✓ 참고 사례; card-focused, PayPal/ATLAS contract 근거 아님 |
| REF-039 | Ticketmaster Help, Checkout time limit / customer policy | https://help.ticketmaster.com/hc/en-us/articles/9642204793617-Why-is-there-a-time-limit-for-checking-out | 2026-10-30 | Checkout hold duration varies with ticket demand | ✓ 검토; Ticketmaster policy, no universal numeric recommendation |
| REF-040 | Broadleaf, Checkout inventory reservations / product docs | https://developer.broadleafcommerce.com/services/inventory-services/checkout-inventory-reservations | 2026-10-30 | Soft reservation expiration after unsuccessful checkout | ✓ 검토; configurable 5-minute product example, ATLAS default 아님 |
| REF-041 | PayPal, Integrate webhooks / API guide | https://developer.paypal.com/api/rest/webhooks/rest | 2026-10-30 | Failed webhook delivery retried up to 25 times over 3 days | ✓ 검토; delivery retry contract, checkout TTL/API deadline 아님 |
| REF-042 | Artillery, Understanding workload models / engineering blog | https://www.artillery.io/blog/load-testing-workload-models | 2026-10-30 | Open arrivals with sequential per-user work (hybrid workload) | ✓ 검토; load-generator vendor explanation, universal workload choice 아님 |

PayPal API/지역/계정/자금 흐름은 구현 시점 공식 문서로 확인. Authorization validity/honor window는 provider-specific이며 ATLAS inventory TTL로 사용하지 않음. Webhook delivery retry 역시 buyer checkout TTL/API deadline과 별개. REF-030~042는 기능/사례를 설명하며 production behavior·endpoint support·법률 의무를 검증하지 않음.

## 관련 문서
- [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md): REF-001~003은 미확인 후보.
- [Project Charter](project-charter.md): 확인되지 않은 정책/성능 주장을 제한.
