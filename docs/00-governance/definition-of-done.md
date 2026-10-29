---
id: DOC-011
title: Definition of Done
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: []
  experiments: []
---

# Definition of Done

- 상태: ○ Draft
- 담당: 프로젝트 책임자
- 갱신일: 2026-10-29

## 완료 기준
- 요구사항·정책·실패 시나리오를 문서/구현에 연결.
- 주요 결정은 `✓ Accepted ADR` 또는 명시적 보류 기록 보유.
- 테스트는 동작과 핵심 invariant를 검증.
- error/security/recovery path와 관측 방법 기록.
- 성능 주장은 반복 가능한 측정으로 뒷받침.
- 로컬 실행·검증 절차 실제 수행.
- 포트폴리오 주장은 코드/측정 근거 보유. synthetic result 명시.
- 복잡한 Issue/PR은 배경, 사실/가정, 이유, 대안, 영향, 근거를 본문에 기록. 링크만으로 대체 금지.
- 복잡한 flow/state/architecture는 Mermaid 또는 짧은 설명+기준 도식 링크.
- 모든 PR checkbox 완료; required `PR checklist` 통과. N/A에는 사유.

상세 product/implementation 기준은 Gate 2에서 구체화. Issue/PR 설명 기준과 PR checklist는 즉시 적용.

## 관련 문서
- [Requirements Index](../02-requirements/requirements-index.md)
- [Experiment Registry](../08-experiments/experiment-registry.md)
- [Change Control](change-control.md), [Roadmap](../09-delivery/roadmap.md)
