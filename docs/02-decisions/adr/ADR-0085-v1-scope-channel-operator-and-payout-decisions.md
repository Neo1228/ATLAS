# ADR-0085: V1 채널 할당, 운영자 역할, 잔여 범위 cut-line, 지급 일정을 가정과 함께 정한다

- 상태: 채택(제안 PR, 병합은 통합 담당). 문서 결정이며 코드는 바꾸지 않는다.
- 날짜: 2026-11-25
- 관련: [ADR-0027](ADR-0027-account-jwt-authentication.md), [ADR-0052](ADR-0052-settlement-fee-reserve-payout.md), [ADR-0057](ADR-0057-financial-operation-outbox-and-payout-reconciliation.md), [ADR-0078](ADR-0078-payout-operator-controls-production-gate.md), [ADR-0083](ADR-0083-admission-operational-values-and-lock-failure.md)
- 추적: [Q-002, Q-007, Q-010, Q-013](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [D-153](../../00-governance/decision-log.md), [D-154](../../00-governance/decision-log.md), [D-155](../../00-governance/decision-log.md), [D-156](../../00-governance/decision-log.md), [Issue #641](https://github.com/Neo1228/ATLAS/issues/641)

## 문제

네 질문이 "미정"으로 남아 있어 구현 방향을 묻는 쪽이 매번 같은 질문을 다시 한다: 채널별 재고 할당을 할 것인가(Q-002), 운영자 역할은 무엇인가(Q-007), 이커머스 잔여 범위는 어디까지가 V1인가(Q-010), 지급 일정과 maker-checker는 언제인가(Q-013). 모두 외부 사실(실제 판매 채널 수, 운영 인력, 회계)이 없는 질문이라 **가정을 적고 가장 단순한 값**을 정한다.

## 결정

### Q-002 채널 할당: V1은 **공유 재고 pool, 채널별 할당 없음**

- 사실(코드 읽기, Q-002 행): inventory pool key는 `(Seller, SKU, location)`이고 판매 채널과 무관하게 재고를 공유한다. 채널별 주문 가능성(D-076/FR-013)은 구현돼 있으나 수량 할당은 없다.
- 대안: (가) 채널별 수량 쿼터 — 채널이 둘 이상 실제로 쓰이고 한 채널이 재고를 모두 가져가 다른 채널이 막히는 사례가 있어야 필요하다. 지금 근거가 없다. (나) 채널별 우선순위·예약 — 같은 이유. 선택: 현재 구조 유지.
- **가정:** 판매 채널은 `ONLINE_STORE`·`CAMPAIGN` 둘이고 한 Seller-SKU의 수요가 채널 사이에서 경쟁해 한쪽이 굶는 일이 V1 규모에서는 드물다.
- **바꿀 조건:** Campaign 중 같은 SKU의 일반 판매가 재고 소진으로 막히는 Seller 신고가 있거나, Seller가 채널별 안전 재고를 요구한다.
- **되돌리기:** 해당 없음(코드 변경 없음). 이후 도입은 `inventory_pool` 위에 채널 쿼터를 더하는 새 변경이다.

### Q-007 Staff/admin role: V1은 **단일 설정 운영자 계정 유지**, 사람별 역할 모델은 Live gate

- 사실: 운영자 인증은 `SettlementPayoutOperatorSecurityConfiguration`의 HTTP Basic 단일 설정 자격 증명이고 사람별 신원·MFA·운영자 간 maker-checker는 없다(ADR-0078 사실 1). `ROLE_SETTLEMENT_PAYOUT_OPERATOR` 하나만 있다.
- 결정: V1(Sandbox, feature flag 꺼짐)에서는 단일 계정 유지. 사람별 role 모델은 ADR-0078 gate 7(사람별 신원·MFA, 승인자 ≠ 검증자)에 **이미 속하므로 새 gate를 만들지 않는다**. 사람별 신원은 앱이 아니라 IdP·gateway가 제공하고 앱은 principal 이름이 사람 하나를 가리킨다는 전제만 쓴다(ADR-0078 결정 3).
- **constant-time 진술(구분해서 적는다):**
  - **보장하는 것:** Signup·verification·reset 요청은 계정 존재 여부와 무관하게 같은 HTTP status와 body를 돌려준다(ADR-0027). 로그인에서 없는 이메일에도 프로세스 생성 더미 hash로 PBKDF2를 돌려 응답 시간 차이를 줄인다(`AccountServiceImpl`의 `dummyLoginPasswordHash`, 코드 읽기). 서비스 토큰 비교는 constant-time(ADR-0059).
  - **보장하지 않는 것:** Signup·verification·reset의 SMTP 전달 여부에 따른 응답 시간 평준화(ADR-0027 명시). 로그인 전체 경로가 모든 입력에서 같은 시간이라는 주장. 존재하는 계정은 이후 row lock·token 발급을 더 하므로 시간 차이가 0이라고 측정하지 않았다. **"constant-time 비노출"을 주장하지 않는다.**
- **바꿀 조건:** Live 지급 승인, 두 번째 운영자 투입. **되돌리기:** 해당 없음.

### Q-010 잔여 범위 cut-line과 순서

| 항목 | V1 | 결정 · 가정 값(feature-config, 코드 기본값 아님) |
|---|---|---|
| Global taxonomy | **제외** | Seller-owned 카테고리 트리(D-077)를 유지한다. 전역 분류는 Seller 둘 이상이 같은 상품을 팔아야 의미가 있다. 가정: V1 Seller 수가 적고 상품이 Seller 소유다 |
| Ranking·추천 | **제외** | 목록은 현재 정렬(검색 일치·생성 순)만. 판매 이력이 쌓이기 전에는 순위 입력이 없다 |
| 반품 기간 | **포함, 값 정의** | 수령 후 **7일** 안에 return 요청(가정). 속성 이름(후속): `atlas.order.return.window`(`P7D`). 현재 코드는 반품 요청 기한을 검사하지 않는다(`git grep`으로 기한 설정 0건) |
| 검수 기한 | **포함, 값 정의** | Seller 수령 확인 후 **5영업일** 안에 검수 완료(가정). 속성: `atlas.order.return.inspection-deadline`(`P7D`로 달력일 근사). 초과 시 운영자 알림만(자동 환불 없음) |
| write-off | **제외** | 검수 불합격 수량은 이미 "재고 복원 안 함, 환불 안 함"(D-068)으로 남는다. 별도 손실 처리·회계는 Live 정산 gate와 함께 |
| 재검수 | **제외** | 이의 제기 절차는 운영 인력이 정해진 뒤. V1은 검수 결과 1회 |
| 재입고 통제·판매 중 capacity 조정 | 포함(구현됨, Q-002) | 변경 없음 |

- **순서(구현은 이 ADR이 하지 않는다):** 1) 반품 기간·검수 기한 속성과 기한 검사(상태 전이 테스트), 2) Live 지급 gate(ADR-0078) 진행, 3) 판매량이 생긴 뒤 ranking, 4) Seller 둘 이상이 같은 상품을 팔기 시작하면 global taxonomy. 기준: 돈·재고에 닿는 것이 먼저, 노출 개선이 나중.
- 7일·5영업일은 근거 있는 정책이 아니라 가정이다. 실제 Seller·소비자 정책(법정 청약 철회 기간 등)은 **확인하지 못했다**. 값은 feature-config이므로 코드를 바꾸지 않고 조정한다.
- **바꿀 조건:** 법무·정책 확정, 실제 반품 사례에서 기한 초과가 잦음. **되돌리기:** 속성 제거 시 기한 검사 없음(현재 동작).

### Q-013 지급 일정과 maker-checker

- **V1 일정:** 지금과 같이 **Seller 요청으로만 지급**(Q-013 현재). 지급을 만드는 `@Scheduled`는 없다(ADR-0078 사실 1). 자동 주기 지급은 V1에 넣지 않는다. 이유: 승인자·funding·보내는 국가 적격성(gate 1, 2, 4)이 닫히기 전에는 자동화가 사고 범위를 키운다.
- **Live 가정(Live가 열릴 때의 초안):** 지급 요청 최소 금액 **USD 10**, 요청 주기는 Seller당 **주 1회 이하**, 건당 앱 상한 **USD 5,000**(PayPal 건당 한도 등록 수취인 USD 60,000의 약 1/12, ADR-0078 gate 6의 "앱 상한을 둘지, 값"에 대한 초안). 셋 다 근거 있는 정책이 아니라 **가정**이며 PayPal 수수료(일반적으로 변동 2%, 상한 있음)와의 비율을 보지 않았다. 속성 이름(후속): `atlas.settlement.payout.minimum-amount`, `...max-amount-per-payout`, `...min-interval`.
- **maker-checker 구현 선행 조건:** (1) 운영자 사람별 신원(IdP 또는 gateway가 principal을 사람 하나로 보장), (2) 그 신원이 `verified_by`·승인자 actor에 그대로 기록, (3) 그 뒤에만 `PayoutDispatchApprovalService.approve`가 프로필 `verified_by`와 승인자를 비교(같으면 거절). 지금은 신원이 하나라 비교하면 모든 승인이 거절되므로(ADR-0078 결정 2) 구현하지 않는다.
- **구현 계획(순서):** 1) IdP/gateway 결정(앱 밖), 2) 운영자 chain을 JWT/OIDC principal로 전환하고 `ROLE_SETTLEMENT_PAYOUT_OPERATOR`를 claim에서 읽음, 3) 승인 시 `verified_by ≠ approver` 검사 + PostgreSQL 통합 테스트(변경 전 실패, 변경 후 통과), 4) 위 Live 값 속성 도입.
- **바꿀 조건:** Live gate 1·2가 닫힘. **되돌리기:** 해당 없음(코드 변경 없음).

## 한계

- 네 결정 모두 코드와 측정이 없고 가정에 기댄다. 반품 7일·검수 5영업일·USD 10/5,000·주 1회는 외부 정책·회계 근거를 확인하지 못했다.
- 보내는 국가 적격성은 여전히 not confirmed(ADR-0078 gate 2).
