---
id: DOC-010
title: Change Control
status: Draft
owner: Project Owner
updated: 2026-11-03
links:
  requirements: []
  adr: []
  experiments: []
---

# 변경 통제
- 상태: Draft
- 담당: Project Owner
- 갱신일: 2026-11-03

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

## 변경 추적
- 변경은 관련 요구사항·결정·실패 시나리오와 검증 기준의 SSOT에 연결. 구현·검증·상태 기록은 실제 결과와 일치.
- 변경 절차와 Git 안전 수칙은 [AGENTS.md의 GitHub 작업 및 변경 관리](../../AGENTS.md#github-작업-및-변경-관리)를 단일 기준으로 사용. Issue/PR 양식은 [Issue forms](../../.github/ISSUE_TEMPLATE/)와 [PR template](../../.github/PULL_REQUEST_TEMPLATE.md), 검증은 [docs-lint workflow](../../.github/workflows/docs-lint.yml)를 따른다.
