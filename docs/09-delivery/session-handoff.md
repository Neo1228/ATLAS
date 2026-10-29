---
id: DOC-009
title: Session Handoff
status: Current
owner: Project Owner / Agent
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 세션 인계

## 현재 상태
ATLAS 비공개 저장소 `Neo1228/ATLAS`에 WP-001~WP-005가 모두 게시됐다. 사용자가 2026-10-29 Gate 0을 승인해 Gate 1 discovery가 시작됐다. 현재 작업 단위는 Issue #19 / Q-010이며 코드·런타임 의존성·배포는 시작하지 않았다.

## 확정된 범위
장기 A/B/C 도메인 전체 포함. PayPal Sandbox 우선 및 별도 Fake PSP. 초기 실험에서 실결제·실개인정보·실제 지급 제외.

## 제안/미결정
- ADR-0001 Modular Monolith First: Proposed, 아직 승인되지 않음.
- Q-001은 해결: 실제 문제 검증이 아닌 가상 학습 시나리오로 시작한다 (D-005).
- 여러 판매 유형을 모두 탐색하고 싶다는 선호를 기록했다 (D-006). 이는 전체 구현 범위를 확정하지 않는다.
Q-010은 공통 핵심 buyer/seller persona+유형별 차이로 모델링 (D-015). Buyer goal은 공정한 기회/정확한 재고·예약 상태 (D-016), Seller goal은 초과판매 없는 공정한 한정판매 (D-017). Q-003: eligibility 후 queue admission에서 순번 발급, same-request retry reuse, restart preserves position (D-019~D-022); tie/expiry/type details remain OPEN under Issue #24.
Issue #20은 traffic/capacity 작업 순서를 추적한다: Gate 2에서 workload profile/목표 가정을 합의, Gate 3에서 capacity model, Gate 4에서 합성 부하/복구 실험. 수치 목표와 배포 조건은 아직 TBD이며 실측이 아니다.

## 후속 작업 추적
작은 작업 단위와 커밋으로 기록하고, 관련 작업은 PR 단위로 묶는다. 각 PR/커밋의 범위·연결 결정/요구사항·검증 결과를 마일스톤 로그와 인계 문서에 남긴다. 실제 생성된 기록만 보고한다.

## 현재 상태와 다음 단계
GitHub Issue/PR templates, Issue-first 규칙, PR batching 규칙, Markdown lint와 main 보호 설정이 배포되어 있다.
- WP-001 GitHub 기초: seed commit `7ed5fac`, CI 보정 `eb371ca`, `5151a15`.
- WP-002 거버넌스: PR #9 / Issue #5, commit `6049755`.
- WP-003 발견·요구사항: PR #10 / Issue #6, commit `f73a7e9`.
- WP-004 ADR·실험: PR #11 / Issue #8, commit `6b2b379`; ADR-0001 Proposed, EXP-001~009 Planned.
- WP-005 로드맵·인계: PR #12 / Issue #7, commit `8c71869`.
- Git conventions: PR #4 / Issue #2, commit `a76016e`; PR batching rule: PR #16 / Issue #15, commit `80dd964`.
PR #23 (Issue #22) merged queue-order baseline and domain rules; commit `eb95982`.
Issue #24 `대기열 순번 발급 시점과 재시도 경계 정의` is the active Gate 1 packet: sequence after eligibility/admission, same-request retry reuse, restart preservation. Tie, expiry, durability/key details, bot and type differences stay OPEN (Q-003).
Gate 0 is approved; Gate 1 continues. Do not enter Gate 2 or implementation without the next Gate's explicit approval.
