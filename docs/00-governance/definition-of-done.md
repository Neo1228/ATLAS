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
- 상태: Draft
- 담당: Project Owner
- 갱신일: 2026-10-29

마일스톤 완료 시 아래 항목을 해당 범위에 맞게 확인한다.
- 정책·요구사항·실패 시나리오가 문서 및 구현과 연결됨.
- 중요한 결정은 Accepted ADR 또는 명시적 유예 기록으로 설명됨.
- 자동화 테스트가 동작과 위험 기반 불변조건을 검증함.
- 오류/보안/복구 경로와 관측 방법이 문서화됨.
- 성능 주장은 재현 가능한 실험 데이터와 연결됨.
- 로컬 실행·테스트 절차는 실제 실행으로 확인됨.
- 포트폴리오 주장은 측정/코드/문서 근거와 연결되고 합성 결과는 명확히 표시됨.

이 체크리스트는 제안 초안이며 프로젝트 전반의 절차/품질 정책은 Gate 0/2에서 검토한다.

## 관련 문서
- [Requirements Index](../02-requirements/requirements-index.md): 범위와 검증 가능한 조건.
- [Experiment Registry](../08-experiments/experiment-registry.md): 성능/복구 주장의 실험 근거.
- [Change Control](change-control.md) 및 [Roadmap](../09-delivery/roadmap.md): 승인·추적 단계.
