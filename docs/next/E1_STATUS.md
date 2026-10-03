# E1 엔진 결과 (2026-10-03)

- PARAGRAPH 모드: 한 페이지에 들어가는 문단을 통째로 옮기고, 제목과 다음 문단이 함께 들어갈 때만 묶어서 옮긴다.
- anchorBreak: 읽던 위치의 줄부터 새 페이지를 열고 위치·페이지·재배치 여부를 layout/count 양쪽에서 동일하게 반환한다.
- pageLead: 페이지 상단에서 생략한 문단 간격·빈 문단·장면 전환 간격을 보존한다. 본문을 연속으로 이어 붙여도 문장과 공백이 빠지지 않는다.
- LINE golden `071717a86d158ac8` 유지. PARAGRAPH golden `c9982a735d4822a9`를 확정했다(프로토타입과 일치).
- 첫 페이지 전에 IO·탐색 작업을 추가하지 않았다. 페이지 넘김 애니메이션도 추가하지 않았다.

검사:

- `tools/typecheck.sh`: 성공.
- `tools/unittest.sh`: **1,139개 전부 통과**. 기존 엔진·fuzz·review·성능 테스트는 수정하지 않았다.
- StitchTest: 무작위 문서 200개 × 높이 4개 × 문단/줄 × widow/orphan on/off × anchor on/off. 텍스트·이미지·구분선 순서와 절대 y가 기준 연속 배치와 일치(허용 오차 0.5 px).
- PageLeadTest: 문단 경계, 문단 내부, 빈 문단, 강제 장면 전환, 첫 여백, orphan 이동, 제목 묶음, trailing blank, anchor 경계 검사 통과. 모든 경우 layout/count 일치.
- 같은 JVM에서 P0 엔진과 E1을 번갈아 3회 비교: 100만 자 CHAR 기존 layout 21–22/count 20–21 ms, E1 22–23/21–22 ms; WORD 기존 21–22/20–21 ms, E1 21–22/20 ms. 차이는 측정 변동 범위이며 count 할당량은 0 KB다.
- `git diff --check`: 통과.

다음: E2 렌더러 → RC-P 세션/페이지 수 → RC-S 스크롤 → 나머지 W1 → RC-A 통합 → W2 → CI·기기 검증.
