---
id: DOC-007
title: Experiment Registry
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: [EXP-001, EXP-002, EXP-003, EXP-004, EXP-005, EXP-006, EXP-007, EXP-008, EXP-009]
---
# 실험 레지스트리

모두 `Planned` 후보이며 수행 결과가 없다. 합성 데이터/부하는 운영 실적이 아니다. 전제, 환경, 명령, 반복, 지표 및 한계를 결과 보고에 기록한다.

| ID | 상태 | 가설/검증 내용 | 주요 위험 |
|---|---|---|---|
| EXP-001 | Planned | 재고 100/1,000개, 동시 예약 시 초과 예약 0건 여부 확인 (합성 부하). | 정책과 부하 모델은 아직 미정 |
| EXP-002 | Planned | DB 조건부 UPDATE vs 비관적 락 vs 낙관적 락의 처리량·p95/p99·대기 비교. | 동일 환경/워크로드 필요 |
| EXP-003 | Planned | 동일 주문 요청 N회 전달 시 주문 및 결제 의도의 중복 적용 방지. | 멱등성 범위/보존기간 TBD |
| EXP-004 | Planned | PSP 응답 유실+늦은 웹훅+재시작에서 상태 대사/복구를 관찰. | Fake PSP 실험이지 PayPal 장애 재현 아님 |
| EXP-005 | Planned | 예약 만료와 결제 확정 경합에서 상태 불변조건 검증. | 정책 결정 전 합격 기준 미정 |
| EXP-006 | Planned | 환불/정정/수수료 변경 후 원장 합계·판매사별 정합성 검증. | 정산 정책 TBD |
| EXP-007 | Planned | 테넌트 교차 접근 및 IDOR 시나리오 차단 검증. | 권한 모델/위협 경계 TBD |
| EXP-008 | Planned | 캐시 갱신 역전, 이벤트 중복/역순, 소비자 재시작 영향 검증. | 캐시/메시징 기술 선택 미정 |
| EXP-009 | Planned | 장애 주입으로 복구 시간/데이터 손실 가설을 측정. | RTO/RPO/SLO 및 환경 TBD |

세부 실험은 `TEMPLATE.md` 형식을 사용한다. 실험 전 연결된 요구사항·불변조건과 명시적 합격 기준을 정의한다.
