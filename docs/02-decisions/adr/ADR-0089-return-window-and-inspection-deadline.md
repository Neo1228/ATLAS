# ADR-0089: 반품 요청 기간과 검수 마감을 설정으로 강제하고, 초과는 표시와 집계로만 알린다

- 상태: 채택(제안 PR, 병합은 통합 담당). 7일·5영업일은 **가정**이며 아래 "바꿀 조건"이 생기면 다시 정한다.
- 날짜: 2026-11-25
- 관련: [ADR-0085](ADR-0085-v1-scope-channel-operator-and-payout-decisions.md), [ADR-0083](ADR-0083-admission-operational-values-and-lock-failure.md)(설정 record 패턴), [ADR-0069](ADR-0069-layered-naming-and-runtime-configuration.md), [EXP-068](../../03-experiments/EXP-068-stage-evidence-map.md), [EXP-093](../../03-experiments/EXP-093-return-window-and-inspection-deadline.md)
- 추적: [Q-010](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [D-155](../../00-governance/decision-log.md), [D-159](../../00-governance/decision-log.md), [Issue #649](https://github.com/Neo1228/ATLAS/issues/649)

## 문제

ADR-0085는 반품 기간 7일과 검수 기한 5영업일을 정했지만 코드는 둘 다 강제하지 않았다. `origin/main`(82345acd)에서 같은 시험으로 배송 후 1일·6일 23:59:59·7일·7일+1초·8일·30일·400일에 반품을 요청하면 **7건 모두 수락**됐다(EXP-093 기준선). 검수 기한은 구현이 없어 수령 후 검수를 미루는 반품이 보이지 않는다. 환불 책임이 시간으로 닫히지 않고, 이를 막는 규칙을 시험으로 지킬 수도 없었다.

## 사실 (라벨: 측정 / 코드 읽기 / 가정)

- **측정(로컬·결정적):** 기준선 수락 7/7, 변경 후 수락 3/7(1일·6일 23:59:59·7일 정확히)과 거절 4/4(7일+1초 이후), 거절 뒤 `order_return` 행 0([EXP-093](../../03-experiments/EXP-093-return-window-and-inspection-deadline.md)).
- **코드 읽기:** 반품 줄마다 `resolveShipment`가 정확히 하나의 배송 완료 출고를 고르고, 출고 행 `delivered_at`은 `status='DELIVERED'`이면 NOT NULL이다(`order_shipment_timestamps_check`). 수령 시각 `order_return.received_at`은 이미 저장된다. 그래서 **새 컬럼과 migration은 필요 없다.**
- **코드 읽기:** 주문 전체 `fulfillment`가 DELIVERED여야 반품을 받는다(기존 규칙). 분할 출고 주문은 마지막 출고가 도착해야 반품이 열린다.
- **가정:** 7일과 5영업일은 외부 정책·법정 청약 철회 기간 근거를 확인하지 못했다(ADR-0085와 같다). 공휴일 달력은 없다. 기준 시간대 Asia/Seoul은 통화가 원화이고 환율 공급자가 이미 한국 달력을 쓰는 점에 기댄 가정이다.

## 대안

| 안 | 비용 | 위험 | 근거와 판단 |
| --- | --- | --- | --- |
| A. 아무것도 안 함 | 0 | 환불 책임이 무한하고 규칙이 시험되지 않는다 | 기준선 7/7 수락. 기각 |
| B. 요청 기간은 거절하고, 검수 초과는 표시와 gauge만 한다(**선택**) | 설정 record 1개, 서비스 검사 1곳, 응답 필드 2개, gauge 1개 | 검수가 늦어도 사람이 gauge를 보지 않으면 방치된다 | 거절은 되돌릴 수 있고(설정) 금전을 만들지 않는다. 표시는 상태를 바꾸지 않는다 |
| C. 검수 초과 시 자동 승인(PASSED로 간주) | 스케줄러·상태 전이·환불 호출 | Seller가 보지 못한 물건에 대해 환불이 나간다. 환불은 provider 호출이라 되돌리기 어렵다 | **기각.** Steel-man: 구매자 대기와 Seller 방치를 확실히 끊는다. 그러나 그 이득은 Seller에게 검수 주도권을 주는 정책 결정이고, 오분류된 환불은 돈이 먼저 나간 뒤에야 발견된다. 사람의 결정 기록 없이 자동 환불을 만들지 않는다 |
| D. 검수 초과 시 자동 환불 | C와 같음 | C보다 더 크다(검수 결과 없이 금액 확정) | 기각. 같은 이유 |
| E. 기준 시각을 주문 단위(마지막 출고·`fulfillment.delivered_at`)로 한다 | 코드 더 적음 | 먼저 도착한 출고의 7일이 마지막 출고 기준으로 늘어난다. 같은 주문의 두 줄이 같은 결과여야 해서 분할 출고 시험이 두 안을 구분하지 못한다 | 출고 단위가 정직하다(반품 줄은 이미 출고에 묶여 있다). EXP-093 H4가 두 안을 구분한다. 기각 |
| F. 기간을 DB 제약·trigger로 강제 | migration, 시간 의존 trigger | 시험하기 어렵고 Clock 주입을 우회한다 | 앱 서비스가 이미 주문 행 잠금 안에서 시계를 읽는다. 기각 |
| G. 검수 기한을 ADR-0085 그대로 `inspection-deadline: P7D`(달력일 근사)로 둔다 | 영업일 계산 0 | 5영업일은 평일 수령이면 달력 7일과 같지만(계산으로 확인) 토요일 수령은 6일, 일요일 수령은 5일이고, 값을 3영업일로 조정하면 달력 3일과 월·화·일 수령에서만 같고 수~금 수령에서는 5일로 어긋난다. 기한이 "조정 가능한 값"이라는 ADR-0085의 전제와 달력 근사가 맞지 않는다 | **속성 이름보다 의미를 택했다.** Steel-man: 이름을 ADR-0085와 맞추면 SSOT가 하나이고 5일에서는 거의 같다. 그러나 영업일 규칙은 20줄이고 시험이 표로 닫힌다. 속성은 `inspection-business-days`(정수)로 바꾸고 이 ADR이 ADR-0085의 해당 속성 이름을 대체한다 |
| H. 기간을 달력일 자정(예: 7일째 23:59:59 KST)으로 한다 | 사용자에게 설명이 쉽다 | 시간대에 따라 마감이 달라지고 배송 시각과 무관한 최대 24시간 오차 | 기각. 168시간 정확히(시간대 무관)로 한다. Steel-man: 구매자는 "7일째까지"로 이해한다. 필요하면 `window`를 P8D로 조정한다 |

## 결정

1. **반품 요청 기간.** 반품 줄이 속한 출고의 `delivered_at`부터 `atlas.order.return.window`(기본 `P7D`, 정확히 168시간)이다. **경계를 포함**한다(`요청 시각 <= delivered_at + window`). 마이크로초로 절사한 같은 `now`로 비교한다. 한 줄이라도 기간 밖이면 요청 전체를 거절한다(부분 저장 없음). 거절은 HTTP **409**, 오류 코드 **`RETURN_WINDOW_EXPIRED`**다(기존 CONFLICT와 같은 상태 코드, 코드는 별도). 같은 idempotency key의 재전송은 기간 검사보다 **먼저** 기존 반품을 돌려준다(응답 유실 뒤 재시도가 깨지지 않게). 시계는 주문 행 잠금을 얻은 **뒤** 트랜잭션 안에서 읽는다.
2. **검수 마감.** 수령 시각(`received_at`)의 `atlas.order.return.business-zone`(기본 `Asia/Seoul`) 달력 날짜에서 `atlas.order.return.inspection-business-days`(기본 5)영업일 뒤가 마감일이고, 마감 시각은 마감일 다음 날 00:00(**배타적 끝**)이다. 영업일은 월~금이고 **공휴일은 지원하지 않는다**(공휴일이 끼면 마감이 실제보다 빨리 와서 알림이 일찍 뜬다). 수령일이 주말이면 다음 월요일이 1영업일이다. 마감 시각 정각부터 초과다. 기준은 수령 시각이라 환불 송금 실패 뒤 재검수를 해도 마감은 리셋되지 않는다.
3. **초과의 처리는 표시와 집계뿐이다.** 판매자 반품 목록 응답에 `inspectionDueAt`과 `inspectionOverdue`(RECEIVED이고 마감 이후일 때만 true)를 더하고, 운영자용 gauge `order.return.inspection_overdue_count`(label 없음, 1분 주기)로 건수를 낸다. **상태를 바꾸지 않고, 자동 승인·자동 환불을 만들지 않으며, 늦은 검수도 막지 않고, 늦게 검수해도 환불 금액은 같다.** 자동 승인이나 자동 환불은 이 ADR 밖이며, 하려면 별도 ADR에 사람의 결정 기록(누가 어떤 근거로)을 먼저 남겨야 한다.
4. **설정은 범위를 검증하는 record다**(`ReturnPolicy`, `AdmissionLimits`와 같은 패턴). 기간 PT1H~P365D, 영업일 1~30, 시간대 필수. 범위를 벗어나면 시작이 실패한다. 기간 검사를 끄는 롤백 스위치 `atlas.order.return.window-enforced`(기본 true)가 있고, 끈 상태에서도 값 범위는 검증한다(다시 켤 때 시작이 실패하지 않게). 속성은 시작 시 한 번 읽으므로 바꾸려면 재배포(재시작)가 필요하다. 기본 yml 값과 코드 상수가 같은지는 `ReturnPolicyDefaultsTest`가 확인한다.
5. **migration은 만들지 않는다.** 필요한 컬럼이 이미 있다(위 사실).

## 알려진 한계 (결정하지 않은 것)

- **분할 출고와 주문 단위 게이트.** 반품은 주문 전체가 배송 완료일 때만 열린다(기존). 출고 A가 도착하고 마지막 출고 B가 7일 넘게 뒤에 도착하면, 열리는 시점에 A의 기간은 이미 끝나 A 줄은 반품할 수 없다. 출고 단위 게이트로 바꾸는 것은 이 PR 범위 밖이며 별도 이슈 후보다.
- **REQUESTED 상태의 반품은 기간이 없다.** 기간은 요청에만 적용한다. 요청 뒤 구매자가 물건을 보내지 않거나 Seller가 수령 확인을 하지 않으면 그 수량은 계속 점유된다. 이 수령 기한은 정하지 않았다.
- **공휴일.** 지원하지 않는다. 공휴일이 끼면 초과 알림이 실제보다 빠르다(보수적 방향).
- **gauge를 보는 사람.** 저장소에는 Prometheus 알림 규칙이 없다(`observability/`에 scrape 설정만 있다). gauge는 값을 내지만 누가 임계값에 호출되는지는 정해져 있지 않다.
- **배송 완료 시각 신뢰.** `delivered_at`은 Seller 호출이 기록한다. Seller가 배송 완료 처리를 늦추면 구매자 기간 시작이 늦어진다(구매자에게 불리하지 않은 방향). 반대로 앞당길 수는 없다(시계가 서버 시계).

## 검증

[EXP-093](../../03-experiments/EXP-093-return-window-and-inspection-deadline.md): 기준선·변경 후 같은 시험, 규칙 12개 변이로 시험이 깨짐을 확인, 경계 동시 경합 n=30, 롤백 드릴. 영구 시험은 `ReturnPolicyTest`(경계·영업일·범위), `OrderReturnWindowIntegrationTest`(PostgreSQL, 경계 -1초·정확히·+1초, HTTP 409 코드, 분할 출고, 같은 key 재전송, 잠금 대기 중 시계 경계, 초과 표시·늦은 검수, 롤백).

## 위험과 감지

| 실패 양상 | 감지 |
| --- | --- |
| 정상 반품이 막힌다(시계 오차, 기준 시각 오해) | `RETURN_WINDOW_EXPIRED` 응답 비율. 평소 0에 가깝고 갑자기 늘면 설정·시계를 본다. 즉시 완화는 `window-enforced=false` |
| 배포 환경 시간대가 다르다 | 영업일 계산은 `business-zone`만 쓰고 JVM 기본 시간대에 기대지 않는다(시험이 UTC·Seoul 차이를 확인) |
| gauge 갱신 실패 | `order.return.inspection_metrics.refresh_failures` 카운터 |
| 초과가 누구에게도 보이지 않는다 | gauge와 판매자 목록 필드. 알림 규칙은 아직 없다(위 한계) |

## 바꿀 조건과 되돌리기

- 법정 청약 철회 기간이나 실제 Seller 정책을 확인하면 `window`와 `inspection-business-days`만 바꾼다(코드 변경 없음).
- 공휴일이 실제 문제가 되면 휴일 달력을 `ReturnPolicy`에 주입한다(별도 변경).
- **되돌리기:** `ATLAS_ORDER_RETURN_WINDOW_ENFORCED=false`로 재배포하면 기간 검사가 꺼져 이전 동작(기간 없음)이 된다. 검수 표시와 gauge는 읽기 전용이라 되돌릴 상태가 없다. migration이 없어 DB 되돌리기는 없다. 응답에 필드가 더해졌지만 추가뿐이라 기존 클라이언트는 영향이 없다.
