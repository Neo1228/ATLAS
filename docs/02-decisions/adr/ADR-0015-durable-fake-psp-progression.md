# ADR-0015: Fake PSP 진행 상태의 PostgreSQL 저장

- 상태: 승인
- 날짜: 2026-11-05
- 결정: D-070
- 요구사항: FR-010

## 배경

프로세스 메모리에 둔 Fake PSP 진행 상태는 재시작 시 사라지고, 주문·settlement 후속 transaction 실패가 결제 결과까지 되돌릴 수 있음. 재시도에서 provider 결과와 내부 업무 효과를 구분할 필요.

## 결정

- Fake PSP의 예정 결과, 현재 진행 상태, idempotent operation 결과를 PostgreSQL에 저장.
- 각 provider operation은 `(phase, requestId, idempotencyKey)`로 구분.
- 같은 key는 기존 결과 replay. 다음 상태 진행은 새 key로 요청.
- provider 결과 기록은 독립 transaction으로 먼저 commit. Order·inventory·settlement·fulfillment 효과는 기존 업무 transaction에서 처리.
- 수동 reconcile/cancel 요청에 `Idempotency-Key` 필수. 내부 suffix를 포함한 저장 key 길이를 `VARCHAR(255)` 이내로 제한.
- Fake PSP는 로컬 시뮬레이터만 담당. 외부 PSP 호출, 실제 자금 이동, 자동 polling/worker는 추가하지 않음.

## 선택 이유와 대안

- application 메모리만 사용하는 방식은 재시작 후 진행 상태 복구를 검증할 수 없어 제외.
- 실제 PSP 연동·message broker·자동 retry worker는 현재 local recovery 시나리오에 필요하지 않아 제외.
- 기존 PostgreSQL과 transaction 경계를 재사용해 결과 영속성과 업무 transaction rollback을 각각 검증.

## 결과와 한계

- 안정적인 key 재사용은 동일 결과를 반환하고 진행을 소비하지 않음. 다음 단계는 새 key로만 진행.
- 내부 operation key의 최대 조합은 `reconcile-cancel:` 17자와 `:cancel-reconcile` 17자를 포함. 외부 key 최대 221자로 제한해 `VARCHAR(255)`를 보장.
- 테스트는 같은 key replay, 새 key progression, 중복 동시 요청, downstream rollback 뒤 복구를 검증.
- Fake PSP의 동작은 실제 PSP의 상태·재시도·결제 의미를 재현하지 않음.

## 관련 문서

- [결정 로그 D-070](../../00-governance/decision-log.md)
- [요구사항 FR-010](../../01-requirements/requirements-index.md)
- [결제 복구 Issue #127](https://github.com/Neo1228/ATLAS/issues/127)
