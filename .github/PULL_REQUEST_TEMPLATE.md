<!-- 체크박스 전체 완료. N/A에는 해당 항목의 구체적 사유 작성. -->
# Pull Request
<!-- 제목 형식: <type>(<scope>): <한국어 요약> -->

## 목적
- 문제:
- 필요한 이유:

## 범위
- 포함:
- 제외:

## 근거와 영향
<!-- 복잡한 변경은 배경·사실/가정·선택 이유·대안·영향·검증을 본문에 작성. 링크만으로 대체하지 않음. 표준 backend 용어는 English 가능; 불필요한 전문 용어·병기 금지. -->
- 결정·근거:
- 대안·trade-off:
- 영향 상태/불변조건:
- 추가·제거 dependency/plugin 및 scope (runtime/test/build); 기존 library 대비 직접 구현 trade-off:
- 검증 근거:

## 시각화
<!-- 복잡한 흐름·상태에만 Mermaid 사용. 기존 도식은 핵심 설명과 링크. -->
- 도식 또는 N/A 사유:

## 추적
- Issues:
  <!-- 완료: Closes #N — [Issue #N](https://github.com/OWNER/REPO/issues/N) -->
  <!-- 일부 반영: Refs #N — [Issue #N](https://github.com/OWNER/REPO/issues/N) -->
  <!-- Closes/Refs와 #N은 plain text. Markdown link로 감싸지 않음. -->
- 요구사항·결정·규칙 ID: <!-- 관련 기준 문서로 직접 연결. -->
- 기준 문서·근거 자료: <!-- 문서·외부 자료 링크. 본문에 근거·영향 설명 유지. -->
- Commit links:

## 변경 요약
- [ ] 범위:

## 검증
- [ ] 명령·결과:
- [ ] 관련 문서·기록 갱신:

## 위험·후속 작업
- 위험·한계:
- 후속 작업:

## 검토 체크리스트
- [ ] 범위가 단일 목적에 맞음.
- [ ] 실행하지 않은 검증·성과를 주장하지 않음.
- [ ] 데이터·보안·금전 상태·복구 영향 검토; 영향 없음이면 구체적인 N/A 사유 작성.
- [ ] 관련 Issue 연결, `Closes`/`Refs` plain-text 형식과 link 일치.
- [ ] 복잡한 변경의 근거·영향 설명; 링크만으로 대체하지 않음.
- [ ] PR이 독립 검토 가능한 경계.
- [ ] HTTP/API 변경 시 error/status contract와 GET/read side effect 확인; 영향 없으면 구체적 사유 작성.
- [ ] 모든 항목 완료; N/A 사유 명시.
