# AGENTS.md

## PR 작성 및 본문 갱신
- PR 생성 또는 본문 수정 전에 대상 base 브랜치의 `.github/PULL_REQUEST_TEMPLATE.md` 실제 내용을 확인한다.
- 저장소에 작업 유형별 전용 PR 템플릿이 있으면 현재 작업과 맞는 형식을 우선한다.
- `/핵심정리` 형식은 한국어로 `목적`, `변경`, `검증`, `Finding`, `Source Merge`, `Production Gate`, `참조`를 구분해 작성한다.
- 실제 실행한 검증과 근거만 기록하고, 실행하지 않은 항목은 `PASS`가 아니라 `NOT_RUN`으로 표시한다.
- 작성자(Writer)의 검증 결과와 독립 Reviewer의 판정을 구분한다. 독립 `PASS`는 검수한 코드 HEAD가 명시되고 근거가 일치할 때만 기록한다.
- 코드 HEAD, base 브랜치, 테스트 명령·결과·링크가 실제 ref와 실행 근거에 맞는지 확인한다.
- Source Merge 승인과 Production 배포 승인을 별도 Gate로 기록한다. PR 본문 작성만으로 Merge 승인을 간주하지 않는다.
- Secret, PII, 민감한 SQL 파라미터를 본문에 기록하지 않는다. 필요한 설명에는 비식별·마스킹한 예시만 사용한다.
