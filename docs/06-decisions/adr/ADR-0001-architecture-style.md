# ADR-0001: 초기 아키텍처 스타일
- Status: Proposed
- Date: 2026-10-29
- Decision owner: Project Owner
- Related: FR-001, FR-002, FR-003, NFR-001, NFR-002

## Context & Problem
ATLAS는 재고·주문·결제·정산 간 정합성을 학습하고 실험해야 한다. 팀 규모, 트래픽, 배포 제약은 아직 정해지지 않았다. 따라서 초기에 독립 배포 서비스와 운영 인프라를 늘리는 선택은 근거가 부족하다. 이 ADR은 확정 구조가 아니라 초기 검증 가설이다.

## Decision Drivers / Constraints
- 모듈 간 데이터 소유권과 트랜잭션 경계를 검증할 수 있어야 한다.
- 로컬에서 재현 가능한 통합/장애 테스트를 낮은 운영 복잡도로 실행해야 한다.
- 측정 없이 MSA, Kafka, Kubernetes를 선제 도입하지 않는다.
- 장기 A/B/C 도메인 범위를 줄이지 않는다.

## Options Considered
### Option A — Modular Monolith First (제안)
하나의 배포 단위 안에서 도메인 모듈의 API/데이터 소유권을 분리한다. 필요 시 특정 경계를 분리할 수 있게 관측·실험한다.
### Option B — 초기 Microservices
도메인별 독립 서비스/배포/저장소 경계를 초기에 만든다.
### Option C — 비모듈형 모놀리스
하나의 애플리케이션에서 모듈 경계나 데이터 소유권을 명시하지 않고 구현한다.

## Comparison / Trade-offs
- A: 배포/트랜잭션 실험이 단순하고 경계를 강제할 여지가 있다. 독립 스케일링/배포는 제한되며 모듈 경계가 느슨해질 위험이 있다.
- B: 독립 배포/스케일링은 용이하지만 네트워크 실패, 분산 트랜잭션, 운영 비용을 초기부터 부담한다. 현재 근거가 부족하다.
- C: 시작은 가장 단순하지만 결제·재고·정산 책임이 뒤섞여 장기 변경과 검증이 어려워질 수 있다.

## Decision (Proposed)
A를 초기 가설로 검증한다. 사용자가 승인하기 전 Accepted가 아니다. 기술 스택/프레임워크/버전은 이 결정에 의해 확정되지 않는다.

## Positive / Negative Consequences
긍정: 단일 로컬 환경에서 핵심 상태 전이와 동시성 조건을 먼저 검증 가능.
부정: 모듈 경계 규칙을 설계·검증해야 하고, 독립 확장/릴리스는 초기 제공하지 않는다.

## Validation Plan & Acceptance Criteria
Gate 3에서 모듈별 데이터 소유권·호출 규칙·주요 트랜잭션 경계를 제안하고, 테스트로 경계 위반을 검출할 방법을 정의한다. 도메인 정책/운영 요구가 드러난 뒤 분리 대안을 동일한 요구와 측정 조건으로 비교한다. 기준선은 TBD이며 실측 전 성능 주장을 하지 않는다.

## Revisit Triggers
팀/배포 독립성 요구, 모듈 간 변경·장애 결합 관측, 단일 배포/저장소의 입증된 병목, 데이터 소유권 변경 또는 요구량 실측 결과가 현재 가설을 반증할 때.

## References
- [Reference Register](../../00-governance/reference-register.md)의 REF-001~003은 참고 후보이며 미검증.
