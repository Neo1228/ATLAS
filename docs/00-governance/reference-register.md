---
id: DOC-006
title: Reference Register
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 외부 참고자료 레지스트리

REF-001~008: 미확인 후보, 근거로 사용 전 검토 필요. REF-009~013: 확인한 GitHub 제품 문서. REF-014~022: 2026-10-29 확인한 공개 실무·기술 자료. REF-023~026: 2026-10-30 확인한 practitioner/product 자료. 적용 범위와 한계는 각 행 참조. 외부 자료가 ATLAS 요구/정책을 대신 결정하지 않음.

| ID | 제목 / 유형 | URL | 확인일 | ATLAS 적용 대상 | 제한점 / 상태 |
|---|---|---|---|---|---|
| REF-001 | Fowler, Architecture Decision Record / 실무 글 | https://martinfowler.com/bliki/ArchitectureDecisionRecord.html | 미확인 | ADR 기록 방식 | ? 후보; 확인 전 근거 아님 |
| REF-002 | UK GDS, Documenting architecture decisions / 정부 가이드 | https://gds-way.digital.cabinet-office.gov.uk/standards/architecture-decisions.html | 미확인 | ADR 기록 방식 | ? 후보; 확인 전 근거 아님 |
| REF-003 | C4 Model diagrams / 아키텍처 도식 안내 | https://c4model.com/diagrams | 미확인 | 시스템 구성도 | ? 후보; 확인 전 근거 아님 |
| REF-004 | EventStorming resources / 도메인 탐색 자료 | https://www.eventstorming.com/resources/ | 미확인 | 도메인 탐색 | ? 후보; 확인 전 근거 아님 |
| REF-005 | Diátaxis / 문서 방법론 | https://diataxis.fr/ | 미확인 | 문서 유형 구분 | ? 후보; 확인 전 근거 아님 |
| REF-006 | Google SRE Workbook / 신뢰성 자료 | https://sre.google/workbook/table-of-contents/ | 미확인 | 신뢰성·실험 | ? 후보; 확인 전 근거 아님 |
| REF-007 | OWASP ASVS / 보안 표준 | https://asvs.owasp.org/ | 미확인 | 보안 요구사항 | ? 버전/적용 항목 미확인 |
| REF-008 | OpenAPI Specification / API 표준 | https://spec.openapis.org/oas/latest.html | 미확인 | API 계약 | ? 구현 시점 버전 확인 |
| REF-009 | GitHub, PR template 안내 / 제품 문서 | https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/creating-a-pull-request-template-for-your-repository | 2026-10-29 | PR template | ✓ 확인; 본문을 채우지만 checkbox 완료를 강제하지 않음 |
| REF-010 | GitHub, Issue form 형식 / 제품 문서 | https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms | 2026-10-29 | Issue 입력 양식 | ✓ 확인; 응답은 Issue 본문 Markdown, PR에는 적용 안 됨 |
| REF-011 | GitHub, diagram 작성 안내 / 제품 문서 | https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams | 2026-10-29 | Issue/PR/Markdown 도식 | ✓ 확인; Mermaid 문법은 지원 버전에 따름 |
| REF-012 | GitHub, task list 안내 / 제품 문서 | https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/about-tasklists | 2026-10-29 | Issue/PR 체크박스 | ✓ 확인; checkbox 자체는 merge condition 아님 |
| REF-013 | GitHub, protected branch 안내 / 제품 문서 | https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches | 2026-10-29 | main 필수 검사 | ✓ 확인; status check 이름은 workflow 간 고유해야 함 |
| REF-014 | Google Engineering Practices, 변경 설명 작성 / 실무 자료 | https://google.github.io/eng-practices/review/developer/cl-descriptions.html | 2026-10-29 | PR what/why·맥락·한계 | ✓ 확인; 변경 이유와 미래 독자용 맥락 기록 |
| REF-015 | Google Engineering Practices, 작은 변경 / 실무 자료 | https://google.github.io/eng-practices/review/developer/small-cls.html | 2026-10-29 | commit/PR 크기 | ✓ 확인; 자체 완결 변경 우선, 줄 수 기준 없음 |
| REF-016 | ADR GitHub Community, ADR templates / 커뮤니티 자료 | https://adr.github.io/adr-templates/ | 2026-10-29 | ADR rationale/대안 | ✓ 확인; 양식마다 trade-off와 context/decision/consequence 강조 차이 |
| REF-017 | arc42, Building Block/Runtime View / 설계 문서 자료 | https://docs.arc42.org/section-5/ ; https://docs.arc42.org/section-6/ | 2026-10-29 | 구성요소·핵심 실행 흐름 | ✓ 확인; 중요/복잡/위험한 부분만 시각화 |
| REF-018 | AWS Prescriptive Guidance, Load test types / 실무 가이드 | https://docs.aws.amazon.com/prescriptive-guidance/latest/load-testing/test-types.html | 2026-10-29 | RPS·latency·concurrency와 multi-step transaction 분리 | ✓ 확인; 단위와 test 대상 경계를 명시. AWS 권고를 ATLAS 목표로 간주하지 않음 |
| REF-019 | Grafana k6, Open and closed models / 부하 모델 설명 | https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/ | 2026-10-29 | Arrival rate와 response time 관계 | ✓ 확인; k6 executor 구현 설명이며 k6 채택 결정 아님 |
| REF-020 | Google SRE Book, Monitoring Distributed Systems / SRE reference | https://sre.google/sre-book/monitoring-distributed-systems/ | 2026-10-29 | Latency·traffic·errors·saturation, RPS/TPS 예시, tail latency | ✓ 확인; Google production practice, ATLAS SLO/도구 결정 아님 |
| REF-021 | Grafana Labs, The RED Method / observability practice | https://grafana.com/blog/the-red-method-how-to-instrument-your-services/ | 2026-10-29 | Rate·Errors·Duration 및 resource USE metrics | ✓ 확인; monitoring taxonomy, tool/architecture 선택 아님 |
| REF-022 | Wolfram MathWorld, Little's Law / queueing reference | https://mathworld.wolfram.com/LittlesLaw.html | 2026-10-29 | Arrival rate·시간·in-flight 수 관계 | ✓ 확인; steady state 관계식, capacity target 아님 |
| REF-023 | Shopify Engineering, Inventory Reservations / practitioner engineering post | https://shopify.engineering/scaling-inventory-reservations | 2026-10-30 | 상품/location별 재고 식별, 임시 reservation과 claim 분리 | ✓ 검토; Shopify production 규모와 MySQL 설계는 맥락 의존. ATLAS architecture 권고 아님 |
| REF-024 | SeatGeek, Virtual Waiting Room / InfoQ QCon practitioner presentation | https://www.infoq.com/presentations/ticketing-system-virtual-waiting-room/ | 2026-10-30 | Sale 전 waiting room과 on-sale queue의 FIFO/drain rate | ✓ 검토; SeatGeek 운영 맥락, 일반적 fairness 증명 아님 |
| REF-025 | commercetools, Inventory Management / product modeling guide | https://docs.commercetools.com/learning-model-your-product-catalog/inventory-modeling/inventory-management | 2026-10-30 | Product variant/location/channel별 재고 범위와 available/reservation 구분 | ✓ 검토; vendor model의 사례, 보편적 best practice 아님 |
| REF-026 | Queue-it, Online Fairness / vendor practitioner blog | https://queue-it.com/blog/online-fairness | 2026-10-30 | 예정된 sale 전 무작위 배치 후 FIFO 제안 | ✓ 검토; vendor 관점/이해관계 존재, 일반적 fairness 근거 아님 |

PayPal API/지역/계정/자금 흐름은 구현 시점의 공식 문서로 확인. 현재 목록은 PayPal 기능이나 법률을 검증하지 않음.

## 관련 문서
- [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md): REF-001~003은 미확인 후보.
- [Project Charter](project-charter.md): 확인되지 않은 정책/성능 주장을 제한.
