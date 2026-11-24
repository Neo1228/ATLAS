# ADR-0065: 무가입 Frankfurter Sandbox 환율 provider

- 상태: 채택(구현 완료, 단위 테스트 1건 통과, 실 endpoint 호출은 [D-128](../../00-governance/decision-log.md#d-128)의 검증 전 항목)
- 날짜: 2026-11-22
- 관련 결정: [ADR-0026](ADR-0026-paypal-sandbox-payments.md), [ADR-0030](ADR-0030-paypal-currency-boundary.md), [FR-002](../../01-requirements/requirements-index.md)

## 문제

- 주문·결제·환불·정산 원장은 KRW를 유지하고 PayPal 요청은 USD로 변환한다. 환율 source와 기준일은 payment attempt에 고정해 재시도·환불에서 같은 근거를 사용한다([ADR-0030](ADR-0030-paypal-currency-boundary.md)).
- 기본 BOK ECOS provider는 API key가 필요하다. 사용자는 PayPal Sandbox 실행에 회원가입이나 API key 없이 쓸 수 있는 무료 환율 API를 요청했다.
- [`FrankfurterExchangeRateProvider`](../../../atlas-payment/src/main/java/com/atlas/payment/provider/exchange/FrankfurterExchangeRateProvider.java)는 keyless ECB 일별 기준환율 endpoint를 이미 제공한다. 이 값은 실시간 PayPal quote나 은행 체결률이 아니다.

## 결정

- `ATLAS_EXCHANGE_RATE_PROVIDER` 기본값은 `bok-ecos`로 유지한다. local/Sandbox에서만 `ATLAS_EXCHANGE_RATE_PROVIDER=frankfurter`를 명시해 Frankfurter의 USD/KRW 일별 기준환율을 선택할 수 있다.
- Provider 선택은 명시적이어야 한다. BOK key 누락이나 provider 장애를 Frankfurter로 자동 전환하지 않는다. 유효한 quote를 얻지 못하면 결제를 fail-closed 처리한다.
- Frankfurter quote의 source·기준일을 기존 payment attempt 환율 snapshot에 저장한다. 누락·0 이하·허용 범위를 벗어난 미래 날짜(내일 초과)·기준일이 7일을 넘은 quote는 거부한다. 고정 환율이나 1:1 fallback은 사용하지 않는다.
- FX provider에 API key가 필요 없더라도 PayPal Sandbox client credentials와 기존 amount/currency 검증은 계속 필수다.

## 대안과 trade-off

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| BOK ECOS만 사용 | API key 발급이 필요해 key 없는 환경에서는 Sandbox 결제 흐름을 돌릴 수 없다. 기본값은 그대로 유지한다. |
| 고정 환율 상수 | 오래된 값이나 누락을 감지하지 못해 환율 스냅샷 검증(stale/missing fail-closed)을 우회한다. |
| Frankfurter(ECB 일별 기준환율, key 없음) | 선택. 명시적으로 선택할 때만 쓰고, 유효하지 않거나 7일 넘은 quote는 거부한다. |

## 결과와 검증

- Sandbox FX 검증은 회원가입이나 추가 API key 없이 실행할 수 있다. ECB 일별 기준환율은 영업일 갱신이며 실거래 환율과 다를 수 있으므로 운영 환율로 해석하지 않는다.
- Provider timeout·오류·stale response는 기존 결제 금액을 임의로 만들지 않고 실패로 노출한다. 코드는 연결 timeout 2초, 읽기 timeout 3초다. 기존 BOK ECOS 기본 설정과 secret 취급은 바뀌지 않는다.
- 검증: `FrankfurterExchangeRateProviderTest`(1건)가 MockWebServer로 전일 기준일의 quote(1328.45 KRW/USD) 파싱과 30일 지난 quote 거부를 확인한다. 누락 필드, 0 이하 값, 미래 날짜, HTTP 오류 응답, timeout 경로는 단위 테스트가 없다.
- 한계: 공개 endpoint의 가용성이나 SLA는 측정하지 않았다. 실 endpoint 호출 확인은 [D-128](../../00-governance/decision-log.md#d-128)을 따른다.

## 참고

- [FR-002](../../01-requirements/requirements-index.md)
- [ADR-0026](ADR-0026-paypal-sandbox-payments.md)
- [ADR-0030](ADR-0030-paypal-currency-boundary.md)
- [Decision log D-128](../../00-governance/decision-log.md#d-128)
