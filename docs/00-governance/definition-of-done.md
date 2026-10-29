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
- 복잡한 변경의 Issue/PR 본문에 문제 배경, 사실/가정, 결정 이유, 대안/트레이드오프, 영향·검증 근거가 충분히 기록됨. 문서 링크만으로 설명을 대체하지 않음.
- 흐름/상태/아키텍처가 복잡해 검토하기 어려우면 Mermaid 등으로 시각화하거나 기준 도식을 직접 연결하고 본문에 요약한다. OPEN 경계와 근거를 구분한다.
- `PR checklist` required status check가 성공하고 PR template의 모든 체크박스가 완료됨. 해당 없음은 사유를 기록한다.

이 체크리스트의 상세한 제품/구현 인수 기준은 Gate 2에서 구체화한다. Issue/PR 근거 작성과 필수 PR checklist는 Gate 상태와 무관하게 즉시 적용한다.

## 관련 문서
- [Requirements Index](../02-requirements/requirements-index.md): 범위와 검증 가능한 조건.
- [Experiment Registry](../08-experiments/experiment-registry.md): 성능/복구 주장의 실험 근거.
- [Change Control](change-control.md) 및 [Roadmap](../09-delivery/roadmap.md): 승인·추적 단계.
