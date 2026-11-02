---
id: DOC-011
title: 완료 기준
status: Draft
owner: Project Owner
updated: 2026-11-02
links:
  requirements: []
  adr: []
  experiments: []
---

# 완료 기준

- 상태: ○ Draft
- 담당: 프로젝트 책임자
- 갱신일: 2026-11-02

## 완료 조건
- 요구사항·정책·실패 시나리오를 문서·구현에 연결.
- 주요 결정은 Accepted ADR 또는 명시적 보류로 추적.
- Test는 동작과 핵심 invariant를 검증.
- Error/security/recovery path와 관측 방법 기록.
- 성능 주장은 반복 측정으로 뒷받침.
- 로컬 실행·검증 절차 실제 수행.
- 포트폴리오 주장은 코드·측정 근거 보유. Synthetic 결과는 합성임을 표시.
- 복잡한 Issue/PR은 배경·사실/가정·이유·대안·영향·검증을 본문에 작성. Trace ID와 기준 문서는 직접 연결; 링크만으로 설명 대체 금지.
- 복잡한 flow/state/architecture는 Mermaid 또는 짧은 설명과 기준 도식 link.
- 모든 PR checkbox 완료; required PR checklist 통과. N/A에는 구체적 사유.

현재 Buyer journey 기준은 Gate 2 승인. 세부 구현·운영 기준은 Gate 3 설계에서 확정; 실행 검증은 별도 구현 승인 뒤 진행. Issue/PR 설명 기준과 checklist는 즉시 적용.

## 관련 문서
- [Requirements Index](../02-requirements/requirements-index.md)
- [Experiment Registry](../08-experiments/experiment-registry.md)
- [Change Control](change-control.md), [Roadmap](../09-delivery/roadmap.md)
