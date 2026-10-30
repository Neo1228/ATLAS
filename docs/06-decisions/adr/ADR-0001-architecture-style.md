# ADR-0001: 초기 아키텍처 방식
- 상태: △ Proposed; 사용자 승인 전
- 날짜: 2026-10-29
- 담당: 프로젝트 책임자
- 관련 요구사항: [FR-001~003](../../02-requirements/requirements-index.md), [NFR-001~002](../../02-requirements/requirements-index.md)

## 배경과 문제
- 주문·재고·결제·정산의 정합성과 장애 복구를 실험할 구조가 필요.
- 팀 규모, 트래픽, 배포 제약은 미정. 초기 단계에서 독립 service를 운영할 근거 없음.

## 판단 기준
- 모듈별 data ownership과 transaction 경계 검증.
- 낮은 운영 복잡도에서 통합·failure test 재현.
- 측정 전 MSA/Kafka/Kubernetes 도입 금지.
- 장기 A/B/C 범위 유지.

## 검토한 대안과 절충

| 대안 | 설명 | 이점 | 비용·위험 |
|---|---|---|---|
| A. Modular Monolith First (제안) | 하나의 배포 단위 안에서 module별 data ownership 분리 | transaction·test 단순화, 경계 강제 가능 | 독립 확장·배포 제한, 경계 약화 위험 |
| B. Microservices | 도메인별 service·배포·storage 분리 | 독립 배포·확장 | network failure, distributed transaction, 운영 비용 증가 |
| C. 단순 monolith | 단일 application, module/data 경계 미구분 | 시작이 단순 | 결합도 증가, 변경·검증 어려움 |

## 제안
초기 가설로 A 제안. 사용자 승인 전 미확정. Stack/framework/version 선택 없음.

## 예상 영향
- 단일 local environment에서 상태 전이와 동시성 실험 가능.
- module boundary 규칙과 검증 필요. 독립 scaling/release는 초기 범위 밖.

## 검증 계획
- Gate 3에서 module별 data ownership, 호출 규칙, 주요 transaction 경계 제안.
- 경계 위반 탐지 방법 정의.
- 같은 요구·환경으로 대안을 비교. 측정 전 성능 주장 금지.

## 재검토 조건
- 독립 team/deployment 요구 발생.
- module 간 변경·장애 결합 증가.
- 단일 배포가 병목이라는 측정 근거 확보.
- data ownership 변경 또는 측정 load가 가설 반증.
- 참고: REF-001~003은 [Reference Register](../../00-governance/reference-register.md)의 미확인 후보. 본 제안의 근거가 아님.
