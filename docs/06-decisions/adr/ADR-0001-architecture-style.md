# ADR-0001: 초기 아키텍처 스타일
- Status: △ Proposed; 사용자 승인 전 아키텍처 아님
- Date: 2026-10-29
- Owner: Project Owner
- Related: FR-001~003, NFR-001~002

## 맥락
- 문제: 주문·재고·결제·정산의 정합성과 recovery 실험 필요.
- 미정: 팀 규모, traffic, deployment constraints. 초기 독립 service 운영 근거 없음.

## 판단 기준
- 모듈별 data ownership/transaction boundary 검증.
- 낮은 운영 복잡도에서 재현 가능한 통합/failure 실험.
- 측정 전 MSA/Kafka/Kubernetes 도입 금지.
- 장기 A/B/C scope 유지.

## 선택지·trade-off

| 선택지 | 설명 | 이점 | 비용/위험 |
|---|---|---|---|
| A. Modular Monolith First (제안) | 한 배포 단위, 모듈별 data ownership 분리 | transaction/실험 단순; 경계 강제 가능 | 독립 확장/배포 제한; 모듈 경계 약화 위험 |
| B. Microservices | 도메인별 독립 service/deployment/storage | 독립 배포·확장 | network failure, distributed transaction, 운영 비용 |
| C. 비모듈형 monolith | 단일 app, 모듈/data 경계 미구분 | 초기 구현 단순 | 결합도 증가, 변경·검증 어려움 |

## 제안 결정
선택지 A를 초기 가설로 제안. 사용자 승인 전 미확정. Stack/framework/version 결정 없음.

## 예상 결과
- 이점: 단일 로컬 환경에서 state transition/concurrency 검증.
- 비용: module boundary 규칙·검증 필요. 독립 scaling/release는 초기 범위 밖.

## 검증 계획
- Gate 3: module별 data ownership, 호출 규칙, 주요 transaction boundary 제안.
- 경계 위반 탐지 방법 정의.
- 동일 요구/조건으로 대안 비교. 측정 전 성능 주장 금지.

## 재검토 조건
- 재검토: 독립 팀/deployment 요구, 모듈 간 변경·장애 결합, 단일 배포 병목 근거, data ownership 변경, 측정 load가 가설 반증.
- 참고: REF-001~003은 [Reference Register](../../00-governance/reference-register.md)의 미확인 후보. 이 제안의 근거 아님.
