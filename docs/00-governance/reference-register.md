---
id: DOC-006
title: Reference Register
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 외부 참고자료 레지스트리

REF-001~008은 검토 후보로 등록했지만 아직 확인하지 않아 근거로 사용하지 않는다. REF-009~013은 2026-10-29 확인한 GitHub 제품 문서, REF-014~017은 같은 날 확인한 공개 엔지니어링 실무/커뮤니티 자료다. 적용 범위와 한계는 각 행에 적었다. 외부 자료가 ATLAS 요구나 정책을 대신 결정하지는 않는다.

| ID | 제목 / 유형 | URL | 확인일 | ATLAS 적용 대상 | 제한점 / 상태 |
|---|---|---|---|---|---|
| REF-001 | Fowler, Architecture Decision Record / 실무 글 | https://martinfowler.com/bliki/ArchitectureDecisionRecord.html | 미확인 | ADR 기록 방식 | 이 세션에서 확인하지 않은 후보 |
| REF-002 | UK GDS, Documenting architecture decisions / 정부 가이드 | https://gds-way.digital.cabinet-office.gov.uk/standards/architecture-decisions.html | 미확인 | ADR 기록 방식 | 이 세션에서 확인하지 않은 후보 |
| REF-003 | C4 Model diagrams / 아키텍처 도식 안내 | https://c4model.com/diagrams | 미확인 | 시스템 구성도 | 이 세션에서 확인하지 않은 후보 |
| REF-004 | EventStorming resources / 도메인 탐색 자료 | https://www.eventstorming.com/resources/ | 미확인 | 도메인 탐색 | 이 세션에서 확인하지 않은 후보 |
| REF-005 | Diátaxis / 문서 방법론 | https://diataxis.fr/ | 미확인 | 문서 유형 구분 | 이 세션에서 확인하지 않은 후보 |
| REF-006 | Google SRE Workbook / 신뢰성 자료 | https://sre.google/workbook/table-of-contents/ | 미확인 | 신뢰성·실험 | 이 세션에서 확인하지 않은 후보 |
| REF-007 | OWASP ASVS / 보안 표준 | https://asvs.owasp.org/ | 미확인 | 보안 요구사항 | 버전과 적용 항목 미확인 |
| REF-008 | OpenAPI Specification / API 표준 | https://spec.openapis.org/oas/latest.html | 미확인 | API 계약 | 구현 시점에 버전 확인 필요 |
| REF-009 | GitHub, PR template 안내 / 제품 문서 | https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/creating-a-pull-request-template-for-your-repository | 2026-10-29 | PR template | 본문을 미리 채우지만 체크박스 완료를 강제하지 않음 |
| REF-010 | GitHub, Issue form 형식 / 제품 문서 | https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms | 2026-10-29 | Issue 입력 양식 | 입력값이 Issue 본문 Markdown이 됨; PR 양식에는 적용되지 않음 |
| REF-011 | GitHub, diagram 작성 안내 / 제품 문서 | https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams | 2026-10-29 | Issue/PR/Markdown 도식 | Mermaid 지원 버전에 맞는 문법 사용 |
| REF-012 | GitHub, task list 안내 / 제품 문서 | https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/about-tasklists | 2026-10-29 | Issue/PR 체크박스 | 체크박스는 클릭 가능하지만 병합 조건은 아님 |
| REF-013 | GitHub, protected branch 안내 / 제품 문서 | https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches | 2026-10-29 | main required checks | 성공한 status check를 병합 조건으로 지정 가능; check 이름은 workflow 간 고유해야 함 |
| REF-014 | Google Engineering Practices, 변경 설명 작성 / 엔지니어링 실무 | https://google.github.io/eng-practices/review/developer/cl-descriptions.html | 2026-10-29 | PR의 What/Why·맥락·한계 | 변경 내용뿐 아니라 이유와 미래 독자에게 필요한 맥락을 본문에 기록 |
| REF-015 | Google Engineering Practices, 작은 변경 / 엔지니어링 실무 | https://google.github.io/eng-practices/review/developer/small-cls.html | 2026-10-29 | 커밋/PR 크기 | 한 가지 자체 완결 변경을 우선; 줄 수 기준은 없고, 의미가 어려워질 정도의 과도한 분리도 피함 |
| REF-016 | ADR GitHub Community, ADR templates / 커뮤니티 실무 | https://adr.github.io/adr-templates/ | 2026-10-29 | ADR의 근거·대안 기록 | MADR은 대안/장단점을 강조하고 Nygard 형식은 맥락·결정·상태·결과를 간결히 기록; 상황에 맞게 선택 |
| REF-017 | arc42, Building Block/Runtime View / 아키텍처 문서 실무 | https://docs.arc42.org/section-5/ ; https://docs.arc42.org/section-6/ | 2026-10-29 | 구성요소와 핵심 실행 흐름의 도식화 | 중요·복잡·위험한 부분만 선택; 승인 전 전체 아키텍처 그림 목록을 만들지 않음 |

PayPal API/지역/계정/자금흐름 관련 사실은 구현 시점 공식 문서로 별도 확인한다. 이 목록만으로 PayPal 기능이나 법률 사항이 검증된 것은 아니다.

## 관련 문서
- [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md)는 REF-001~003을 후보 근거로 참조한다.
- [Project Charter](project-charter.md)에서 확인되지 않은 정책/성능 주장을 제한한다.
