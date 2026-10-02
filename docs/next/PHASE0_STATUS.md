# Phase 0 계약 결과 (2026-10-02)

- 슬롯 기반 상태 표시, 40 dp 기준 여백과 기존 기본값 이전, LINE/PARAGRAPH 계약, 앱 설정과 백업 매핑을 반영했다.
- schema v3와 노트·단어장·인용문 스타일·돌아갈 위치의 공용 모델을 추가했다.
- Anchors, ReaderJump, QuoteStyles, StatusFit, ProgressMath와 목록의 즉시 넘김 수학을 구현했다.
- W1 소유 파일에는 명세의 안전한 기본값 또는 호출되지 않는 TODO를 배치했다. 아직 새 기능 완성 빌드가 아니다.
- `R3_INTERFACES.md`에 API·소유·스레드 규칙을 기록했다. first-page 이전에는 새 IO·탐색·백업 작업을 넣지 않았다.
- 사용자 지시로 탭·키·자동 페이지 넘김의 180 ms 애니메이션 명세를 폐기했다. PageView는 즉시 프레임 교체를 유지한다.

검사:

- `tools/typecheck.sh`: 성공 (오류 0).
- `tools/unittest.sh`: **1,120개 전부 통과**. LINE golden hash `071717a86d158ac8` 유지.
- PARAGRAPH golden 검사는 해시를 출력한 뒤 계약 단계에서 의도적으로 보류한다. E1 구현 후 확정한다.
- `tools/snapshot_contracts.sh`: 계약 스냅샷 갱신.
- `git diff --check`, CI 셸 문법 검사 통과.

전체 검사로 확인한 추가 계약 정리:

- 책 소유 테이블 검사에 lookups 추가. 해당 DELETE와 구버전 orphan sweep도 함께 연결했다.
- 앱 매핑 검사는 device-local DROPPED_KEYS를 구분한다. 기기 밝기 설정이 백업되지 않는다는 별도 회귀를 추가했다.
- 근거와 예외는 PLAN §3.14 뒤에 기록했다. 나머지 기존 테스트의 목적과 보장은 유지한다.

다음: E1 → E2 → RC-P → RC-S → RU 및 extras/data/library/settings/notes → RC-A 통합 → W2 → CI/성능/리뷰.
