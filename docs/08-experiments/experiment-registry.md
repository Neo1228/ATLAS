---
id: DOC-007
title: Experiment Registry
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: [NFR-002]
  experiments: [EXP-001, EXP-002, EXP-003, EXP-004, EXP-005, EXP-006, EXP-007, EXP-008, EXP-009]
---
# 실험 레지스트리

아래 실험은 모두 `Planned`(아직 실행하지 않음)이며 결과가 없다. 합성 요청은 실제 사용자 트래픽이 아니고, 실험 후보의 숫자는 승인된 목표가 아니다. 전제, 환경, 명령, 반복 횟수, 지표와 한계를 결과 기록에 남긴다. 현재 트래픽/용량 숫자도 미정이다.
제안 순서는 Gate 2에서 합성 부하 프로필 합의 → Gate 3에서 용량 추정 → Gate 4에서 합성 실험 검증이다. 순서와 한계는 [Issue #20](https://github.com/Neo1228/ATLAS/issues/20)과 [Roadmap](../09-delivery/roadmap.md)에 있다. 부하/신뢰성 용어는 [Glossary](../01-discovery/glossary.md)를 참조한다.

| ID | 상태 | 가설/검증 내용 | 주요 위험 |
|---|---|---|---|
| EXP-001 | Planned | 후보 재고량 100/1,000개에서 동시 예약으로 초과 판매가 생기는지 확인한다. | 숫자는 시험 입력 후보일 뿐, 목표나 합의 값이 아니다 |
| EXP-002 | Planned | DB 조건부 갱신, 비관적 락, 낙관적 락을 같은 부하에서 처리량·p95/p99·대기시간으로 비교한다. | 동일 DB/환경/부하 조건 필요 |
| EXP-003 | Planned | 같은 주문 요청을 여러 번 전달해 주문·결제 의도가 중복 반영되는지 확인한다. | 중복 방지 키의 범위/보존 기간 미정 |
| EXP-004 | Planned | PSP 응답 유실, 늦은 webhook, 재시작 뒤 내부/외부 결제 상태를 맞추고 복구하는 과정을 관찰한다. | Fake PSP 시험이며 실제 PayPal 장애 재현이 아니다 |
| EXP-005 | Planned | 예약 만료와 결제 확정이 동시에 발생할 때 상태 불변조건을 검증한다. | 정책·합격 기준 미정 |
| EXP-006 | Planned | 환불·정정·수수료 변경 뒤 내부 정산 원장의 합계와 판매사별 기록을 검증한다. | 정산 정책 미정 |
| EXP-007 | Planned | 다른 테넌트의 데이터 ID를 바꿔 접근하는 권한 우회(IDOR)를 막는지 확인한다. | 권한 모델/위협 경계 미정 |
| EXP-008 | Planned | 캐시 갱신 순서 역전, 이벤트 중복/순서 변경, 소비자 재시작의 영향을 확인한다. | 캐시/메시징 기술 미정 |
| EXP-009 | Planned | 장애를 주입해 복구 시간과 허용 가능한 데이터 손실 가정을 측정한다. | RTO/RPO/SLO와 환경 미정 |

세부 실험은 [Experiment Template](TEMPLATE.md) 형식을 사용한다. 실험 전 연결된 요구사항·불변조건과 명시적 합격 기준을 정의한다.
