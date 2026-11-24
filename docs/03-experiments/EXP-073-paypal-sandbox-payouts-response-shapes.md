# EXP-073: 실제 PayPal Sandbox Payouts 응답은 지급 클라이언트가 가정한 모양과 같은가

- 질문: 지급 클라이언트와 워커([ADR-0052](../02-decisions/adr/ADR-0052-settlement-fee-reserve-payout.md), [ADR-0057](../02-decisions/adr/ADR-0057-financial-operation-outbox-and-payout-reconciliation.md))가 가정한 PayPal Payouts 응답 모양과 동작이 실제 Sandbox와 같은가? 응답을 잃은 뒤 같은 `sender_batch_id`로 재시도하면 무슨 일이 생기는가?
- 상태: 탐색 실험. **애플리케이션을 거치지 않고 PayPal Sandbox API를 직접 호출했다.** 결과는 provider의 응답 모양과 동작에 대한 것이며 앱 전체 흐름(정산 원장, 운영자 승인, 워커, webhook)의 end-to-end 확인이 아니다.
- 날짜: 2026-11-24
- 관련: Issue #486, [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md)(같은 Sandbox에서 결제 쪽을 확인한 실험)

## 왜 했는가

지급 흐름은 병합됐지만 Sandbox 실서버로 확인하지 않았다(Issue #486). 테스트는 `MockWebServer`로 만든 응답을 쓰는데, 그 응답 모양이 실제와 같은지는 비교한 적이 없다.

## 환경과 방법

- 대상: `api-m.sandbox.paypal.com`만 허용한다. 호스트가 다르면 스크립트가 중단되도록 했고 Live endpoint는 호출하지 않았다.
- 자격 증명: git 제외 `.env.local`에서 프로세스 안에서만 읽고 출력하지 않았다. 출력에는 상태 코드와 비밀이 아닌 필드만 남겼고, 이 문서의 응답 예시는 식별자를 치환했다.
- 호출: 앱의 `PayPalPayoutsRestClient`와 같은 요청 모양(`recipient_type=EMAIL`, USD, `PayPal-Request-Id` 헤더)으로 Python에서 직접 보냈다. 지급은 1.00 USD짜리 4건을 만들었고 중복 `sender_batch_id` 재전송은 5회 거부됐다.
- 수취인: Sandbox 개인 계정 1개, PayPal 계정이 없는 임의 이메일 1개.
- 하네스는 저장소에 올리지 않았다(Sandbox 자격 증명과 로컬 `.env.local`에 의존한다).

## 결과

| 확인한 것 | 결과 |
| --- | --- |
| 지급 권한 | OAuth 토큰 발급 200, 토큰 범위에 Payouts 포함. 별도 활성화 없이 생성 `201` |
| 생성 응답 | `201`, `batch_header`에 `payout_batch_id`, `batch_status=PENDING`, `sender_batch_header`. `items`는 없다 |
| 항목 상태 전이(Sandbox 계정 수취) | 배치 `PENDING` → `PROCESSING`, 항목 `PENDING` → `SUCCESS`. 항목이 `SUCCESS`가 된 시점에 배치는 아직 `PROCESSING`이었다 |
| 항목 상태(PayPal 계정이 없는 이메일 수취) | 항목 `UNCLAIMED`(`errors.name=RECEIVER_UNREGISTERED`). **배치는 `SUCCESS`가 됐는데 항목은 `UNCLAIMED`로 남았다** |
| 성공 항목이 담은 필드 | `payout_item_id`, `payout_item.amount`(USD 1.00), `payout_item.sender_item_id`, `payout_item_fee`(USD 0.02), 배치 `fees`(USD 0.02). `currency_conversion`은 없다 |
| 같은 `sender_batch_id` 재전송 | 새 `PayPal-Request-Id`로도, **같은 `PayPal-Request-Id`와 같은 본문으로도** 거부된다. 원래 배치를 돌려주는 멱등 재생은 없었다 |
| 중복 거부 본문 | HTTP `400`, `name=USER_BUSINESS_ERROR`, `message=User business error.`. 식별 정보는 `details[0]`에 있다: `field=SENDER_BATCH_ID`, `issue=Batch with given sender_batch_id already exists`, `link`는 원래 배치로 가는 GET 링크 1개. 최상위 `links`는 빈 배열이다 |
| 조회 응답의 `sender_batch_id` 위치 | `batch_header.sender_batch_header.sender_batch_id`. `batch_header` 바로 아래에는 없다 |

## 코드와 어긋난 곳 두 가지

1. **중복 거부를 중복으로 알아보지 못했다.** `isDuplicateBatch`는 `text(root, "details", "0", "issue")`로 `details[0].issue`를 읽었는데, `JsonNode.path("0")`은 배열 인덱스로 동작하지 않아 항상 비어 있었다. 기존 테스트는 `message`에 "Duplicate sender_batch_id"를 넣어 이 결함을 가렸다. 실제 응답은 `message`가 `User business error.`이고 식별 문구는 `details[0].issue`에만 있다. 그 결과 실제 중복 거부는 `DUPLICATE_BATCH`가 아니라 `DEFINITIVE_NO_SEND`로 분류된다(수정 전 테스트에서 `expected: DUPLICATE_BATCH but was: DEFINITIVE_NO_SEND`로 재현). 워커는 `DEFINITIVE_NO_SEND`를 "보내지 않음이 확정"으로 읽어 지급을 `FAILED`(`PROVIDER_REJECTED`)로 만든다(코드를 읽은 것이며 end-to-end로 실행하지는 않았다). 응답을 잃은 지급을 재시도하는 바로 그 경우에, PayPal에는 지급이 있는데 장부는 실패로 기록하는 방향이다.
2. **원래 배치 ID를 찾지 못했다.** `extractOriginalBatchId`는 최상위 `links`만 읽는데 실제 응답은 이를 비워 두고 `details[].link`에 원래 배치를 담는다. 1번이 고쳐져도 원래 배치 ID가 없어 워커가 `DUPLICATE_WITHOUT_BATCH_ID`로 사람 검토에 올렸을 것이다.

추가로 `parse`가 `sender_batch_id`를 잘못된 위치에서 읽어 조회 응답의 `senderBatchId`가 항상 `null`이었다. 워커의 `senderMismatch` 검사가 실제 응답에서는 동작하지 않았다는 뜻이다.

## 고친 것

- `isDuplicateBatch`: `details` 배열을 순회해 `field`와 `issue`를 함께 본다.
- `extractOriginalBatchId`: 최상위 `links`와 `details[].link`(배열 또는 단일 객체)에서 찾는다. 제공된 URL은 따라가지 않고 경로에서 ID만 꺼내는 기존 방침은 그대로다.
- `parse`: `batch_header.sender_batch_header.sender_batch_id`를 먼저 읽고 없으면 기존 위치를 본다.
- 테스트: 실제 응답 모양(식별자만 치환)의 클라이언트 테스트 2건을 추가했다. 수정 전 소스에서는 2건 모두 실패하고(1번은 위 분류 오류, 2번은 `null`) 수정 후 9건 모두 통과한다. 워커의 중복 분기를 고정하는 테스트 2건도 추가했다(원래 배치 ID가 있으면 `UNKNOWN`으로 두고 재조회, 없으면 `FAILED`가 아니라 검토). 워커 코드는 바꾸지 않았으므로 이 2건은 기존 동작을 고정하는 특성 테스트다.

## 성공 기준별로 확인한 정도

| Issue #486의 기준 | 확인한 정도 |
| --- | --- |
| 1. 응답을 잃은 뒤 재시도가 같은 `sender_batch_id`만 쓰고 provider 항목이 한 번만 만들어진다 | provider 쪽만 확인했다. 같은 `sender_batch_id`는 거부되고 배치는 새로 만들어지지 않는다. 앱이 응답을 잃고 재시도하는 시나리오와 30일 보호 기간 이후의 `NEEDS_REVIEW` 격상은 실행하지 않았다 |
| 2. 배치 `SUCCESS`만으로 journal을 확정하지 않고 항목 상태를 구분한다 | 실제 Sandbox에서 `배치 SUCCESS / 항목 UNCLAIMED`와 `항목 SUCCESS / 배치 PROCESSING`을 모두 관측했다. 워커가 항목 상태로만 판단하는 것은 단위 테스트(`batchSuccessDoesNotOverrideUnclaimedItemStatus`)와 코드로 확인했다. `BLOCKED`, `RETURNED`, `FAILED`, 같은 항목의 webhook·poll 재전달은 만들지 못했다 |
| 3. 기능 플래그가 꺼졌을 때 provider 호출 0건, 자격 증명이 git과 CI 로그에 남지 않음 | 호출은 `@ConditionalOnProperty`(`atlas.payment.paypal.payouts.enabled`, 기본 꺼짐)로 막혀 있다는 것을 코드로 읽었다. 꺼진 상태에서 실제로 호출이 0건인지는 실행하지 않았다. 이 실험의 하네스는 자격 증명을 출력하지 않았다 |
| 4. 실행 환경과 결과를 문서에 기록 | 이 문서 |

## 한계

- 앱을 거치지 않은 직접 호출이다. 정산 원장에서 지급을 만들고 운영자가 승인해 워커가 보내는 전체 경로는 실행하지 않았다.
- 지급 4건, 수취인 2종, 통화 USD 하나다. 다른 통화, 다른 수취 국가(대한민국은 통화 변환 제외 대상), 수수료율의 일반성은 확인하지 못했다. 수수료 0.02는 1.00에 대한 한 번의 값이다.
- 같은 `sender_batch_id`가 거부되는 기간(30일 보호 기간)은 확인하지 못했다.
- Sandbox와 운영 PayPal의 응답이 같다는 보장은 없다.
- `DEFINITIVE_NO_SEND`가 지급 `FAILED`로 이어진다는 서술은 워커 코드를 읽은 것이다.

## 후속 제안(적용하지 않음)

- 정산 원장부터 워커까지 앱을 켜고 Sandbox로 보내는 end-to-end 실행. 응답을 잃는 조건은 Toxiproxy(EXP-050과 같은 방식)로 주입한다.
- 수취인이 `BLOCKED`나 `RETURNED`가 되는 Sandbox 시나리오를 찾아 워커의 분류를 확인한다.
