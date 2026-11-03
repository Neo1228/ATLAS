---
id: DOC-011
title: 완료 기준
status: Draft
owner: Project Owner
updated: 2026-11-03
links:
  requirements: []
  adr: []
  experiments: []
---

# 완료 기준

- 상태: ○ Draft
- 담당: 프로젝트 책임자
- 갱신일: 2026-11-03

## 완료 조건
- 요구사항·정책·실패 시나리오를 문서·구현에 연결.
- 주요 결정은 Accepted ADR 또는 명시적 보류로 추적.
- Test는 동작과 핵심 invariant를 검증.
- Error/security/recovery path와 관측 방법 기록.
- 성능 주장은 반복 측정으로 뒷받침.
- 로컬 실행·검증 절차 실제 수행.
- 포트폴리오 주장은 코드·측정 근거 보유. Synthetic 결과는 합성임을 표시.
- Issue/PR 설명·추적 및 복잡한 흐름 표현 기준은 [Change Control](change-control.md)을 따른다.
- PR checklist는 [repository PR template](../../.github/PULL_REQUEST_TEMPLATE.md)과 [AGENTS.md의 GitHub 작업 절차](../../AGENTS.md#github-작업-및-변경-관리)에 따라 완료. N/A에는 구체적 사유.

Gate 2 Buyer 기준은 승인됨. Local current-flow runtime은 [Issue #72](https://github.com/Neo1228/ATLAS/issues/72)에서 승인; `./gradlew check`와 PostgreSQL 통합 테스트, 실제 local Buyer flow smoke 필요. Live provider·deployment·production load verification은 별도 승인·환경 대상.

## 관련 문서
- [Requirements Index](../02-requirements/requirements-index.md)
- [Experiment Registry](../08-experiments/experiment-registry.md)
- [Change Control](change-control.md), [Roadmap](../09-delivery/roadmap.md)
