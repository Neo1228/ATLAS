---
id: DOC-010
title: Change Control
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: []
  adr: []
  experiments: []
---

# 변경 통제
- 상태: Draft
- 담당: Project Owner
- 갱신일: 2026-10-30

## 사용자 승인
사전 승인 대상:
- 장기 범위 A/B/C, PSP, 개인정보·금전 정책.
- 핵심 도메인 정책, 아키텍처, Gate 진입/종료.

## 결정 원칙
- 미승인 결정: `△ Proposed`.
- 기존 승인 내용과 충돌 시 영향·대안부터 제시.
- 기존 ADR 소급 수정 금지. 변경 시 새 ADR에서 대체 관계 기록.
- 요구사항·테스트/실험·SSOT 연결.

## Issue/PR 설명
- 복잡한 변경은 문제/배경, 사실/가정, 결정 이유, 대안·trade-off, 영향 상태/invariant, 검증 근거를 본문에 기록.
- 링크만으로 설명 대체 금지.
- Issue/PR의 trace ID와 변경 대상은 관련 기준 문서로 직접 가는 Markdown link로 작성. PR은 관련 Issue와 실제 변경 문서를 연결.
- 복잡한 flow/state/component boundary는 Mermaid 사용. 중복 도식 금지, OPEN 경계 표시.
- 짧고 자연스러운 한국어. 일반 technical term은 English 사용 가능. 프로젝트 용어는 첫 등장에 설명.
- PR 크기는 검토 독립성으로 결정; 줄 수 기준 금지 ([REF-014/015](reference-register.md)).

## Issue·commit·PR 추적
- 계획 작업은 착수 전 Issue 생성: 문제, trace ID, 범위, acceptance/verification.
- 하나의 Issue는 하나의 응집된 작업 묶음. 관련 commit/Issue는 공통 목적·완료 기준의 PR로 통합 가능.
- Branch/commit/PR에 Issue 연결. 완료 시 `Closes #N`, 일부만 반영 시 `Refs #N`. 번호는 GitHub record로 직접 링크.
- 작은 논리 변경은 별도 commit. 독립 목적·위험·배포 경계는 별도 PR.
- 실제 Issue/PR 상태, SHA, checks를 [Milestone Log](../09-delivery/milestone-log.md)와 [Session Handoff](../09-delivery/session-handoff.md)에 기록.

## Git 안전
- 공개 이력 재작성은 기본 금지.
- 승인된 예외: 별도 Issue, 대상 SHA/영향, backup ref, `--force-with-lease`, branch protection 즉시 복원.
- `main` direct push, 무승인 force push, 작업 손실 가능성이 있는 reset/clean 금지.
- Stage 전후 `status`/`diff` 확인. 관련 경로만 stage.

## PR 통합
- [PR Template](../../.github/PULL_REQUEST_TEMPLATE.md) 사용.
- commit/Issue마다 PR 생성 금지. 관련 변경을 하나의 review/acceptance boundary로 묶음.
- 독립 목표·위험·release/recovery boundary는 분리.
- `Markdown lint`와 `PR checklist` 통과 후 병합. 보호 설정은 [CONTRIBUTING](../../CONTRIBUTING.md) 참조.
