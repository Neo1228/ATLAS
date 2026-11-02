---
id: DOC-010
title: Change Control
status: Draft
owner: Project Owner
updated: 2026-11-02
links:
  requirements: []
  adr: []
  experiments: []
---

# 변경 통제
- 상태: Draft
- 담당: Project Owner
- 갱신일: 2026-11-02

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
- 복잡한 변경은 문제·사실/가정·선택 이유·대안·영향 상태/invariant·검증을 본문에 기록.
- 프로젝트 맥락이 없는 독자를 기준으로 작성. 내부 shorthand·ID만 남기지 말고 핵심 개념·이유를 본문에 설명.
- 링크는 근거를 보완할 뿐, 설명을 대체하지 않음.
- trace ID와 변경 대상은 기준 문서로 직접 연결. PR은 관련 Issue와 변경 문서를 모두 연결.
- 복잡한 flow/state는 Mermaid로 설명. 중복 도식 금지; OPEN 경계 표시.
- 자연스러운 한국어 우선. API·transaction 등 정확한 기술 용어는 English 가능; 불필요한 전문 용어·병기 금지.
- PR 크기는 줄 수가 아닌 독립 검토 가능성으로 결정 ([REF-014/015](reference-register.md)).

## Issue·commit·PR 추적
- 착수 전 Issue에 문제·trace ID·범위·완료/검증 기준 기록.
- 같은 목적의 후속 질문·조사는 기존 Issue 댓글로 누적. 관련 PR 병합까지 Issue 유지.
- 목적·위험·책임자·완료 기준·배포/복구 경계가 독립될 때만 분리. 질문·metric·파일·commit마다 Issue/PR 생성 금지.
- 관련 Issue와 commit을 하나의 검토 가능한 PR로 묶고, Issue·PR·변경 문서 간 직접 링크 제공.
- 완료 PR은 plain-text Closes #N, 부분 반영은 Refs #N. 같은 Issues 줄에 Issue URL 연결. Markdown link로 close keyword 감싸지 않음; workflow에서 검사.
- 작은 논리 변경은 별도 commit. 독립 목적·위험·배포 경계는 별도 PR.
- 실제 Issue/PR 상태·SHA·checks를 [Milestone Log](../09-delivery/milestone-log.md), [Session Handoff](../09-delivery/session-handoff.md)에 기록.

## Git 안전
- 공개 이력 재작성은 기본 금지.
- 승인 예외: 별도 Issue, 대상 SHA·영향, backup ref, --force-with-lease, branch protection 복원 기록.
- main direct push, 무승인 force push, 작업을 잃을 수 있는 reset/clean 금지.
- Stage 전후 status/diff 확인. 관련 경로만 stage.

## PR 통합
- [PR Template](../../.github/PULL_REQUEST_TEMPLATE.md) 필수.
- 같은 목적의 변경은 PR 하나로 묶음. 질문·metric·commit마다 PR 생성 금지.
- 목적·위험·배포/복구 경계가 독립될 때만 분리.
- Markdown lint와 PR checklist 통과 후 병합. Checklist는 checkbox와 Issue link/close 형식을 검사. 보호 설정은 [CONTRIBUTING](../../CONTRIBUTING.md) 참조.
