# CI 레인 (에뮬레이터 검사) 결과 (2026-10-03)

`tools/ci/screenshots.sh`를 PLAN §5.3 표의 순서와 이름으로 확장했다. Kotlin 파일은 건드리지 않았다.
실제 `[screens]` 실행은 모든 레인이 합쳐진 뒤 통합 단계(PLAN §5.1 8)에서 한다.

- 실행 순서: 01, 41(+41b), 02, 10, 11, 12(+`rawshot 12b`), 13(+rawshot), 13b–13h, 14, 14b, 14c, 10b(+rawshot,
  no_relayout), 14d, 15, 16, 17, 20–22, 60–69, 69b, 30–32(perf mark, `first_is 31 32`), 40, 42(+42b), 43, 44,
  45, 46(+CHECK 46), 50, 51, 80–90, 52–57, 92–93(W2, 메뉴 항목이 없으면 기록하고 계속), 70–78, 95–98(마지막, `pm clear`).
- 기대값은 모두 `CHECK <n> PASS|FAIL <이유>` 줄로 `shots/steps.txt`에 남는다. 작업을 실패시키는 것은 기존 규칙
  (APK 설치 실패)뿐이다. 마지막에 `CHECK crash`가 crash 버퍼와 "draw failed"를 기록한다.
- 새 `tools/ci/perf_log.py` (순수 파이썬): H4의 `RAPerf show <종류> s: o: a: g: Nms` 줄을 읽는다.
  - `first_is A B`: 표시 A 뒤 ~ B까지 마지막 show 줄의 `o:`(같은 구역)가 A의 마지막 show 줄 `a:`와 같으면 PASS.
  - `no_relayout A B`: 두 표시 사이에 `RELAYOUT` 줄이 없으면 PASS.
  - 추가: `same_start`(69: 67과 같은 첫 줄), `last`(56 다음 쪽, 66 구역 변화), `pv_bounds`(`dumpsys activity top`
    뷰 트리에서 PageView 위치, 내용 행 `pv + 80 … pv + 1360` 계산용).
  - 두 표시 사이의 구분은 정확한 줄 일치로 하고, logcat 버퍼가 돌아 A의 마지막 줄이 없으면 monotonic 시각으로 한다.
- `find_node.py`: `class` 모드(예: SeekBar)와 `--box`(경계 좌표) 추가. 13g의 seek bar 드래그와 42b의 ⋮ 오른쪽 끝
  2 px 안쪽 탭에 쓴다.
- `make_samples.py`: S §3.9의 자동 백업 파일(`readeraplus-auto-0badc0de-20260929-2114.json`)을 만든다.
  sample.epub 위치·북마크, `readMode = PAGED`, 좌우 18 / 상하 16 dp, `r.marginBase` 표시 없음.
- 목록 보기 이름은 C29대로 전체 / 요약 / 썸네일 / 그리드. 책 메뉴 열림은 "문서 속성" 또는 현재 트리의 "책 정보"로 본다.
- 여백을 바꾸는 54는 다음 `raw_equal`(55) 전에 상하 여백을 "0"으로 되돌린다(K8). 53은 진행 막대를, 56은 줄 단위를,
  69b는 스크롤 움직임 기기에 맞춤과 페이지 보기를, 45/46은 목록 넘기기 자동을, 90은 인용문 색 표시 자동을 되돌린다.

결정·차이:

- 이름이 겹치던 기존 부가 샷은 새 이름으로 옮겼다: `41_library_compact` → §5.3의 `42_library_compact`,
  `42_multiselect` → `46c_multiselect`, `51_stats` / `52_wifi` / `53_eink_settings`(+53b) → `50b_stats` /
  `50c_wifi` / `50d_eink_settings`(+`50e_eink_advanced`), `14b_settings_more` → §5.3의 `14b_status_slots`.
  15b, 15c, 17b, 18은 이름이 겹치지 않아 그대로 둔다.
- 스크롤 모드는 아직 `RAPerf show` 줄을 남기지 않는다(`showPage`만 남긴다). 그래서 60·66·68·69의 위치 비교는
  CHECK가 아니라 기록(log only)으로 두고 샷을 눈으로 본다. 61은 픽셀로 검사한다: 머리띠(pv..pv+80)와
  바닥띠(pv+1360..)는 EQUAL, 본문 행은 달라야 한다. RC-A/RC-S에 스크롤 쪽 DEBUG show 줄을 요청했다.
- `first_is`는 두 표시 사이의 show 줄 **모두**(스테퍼의 여러 relayout 포함)가 기준 위치에서 시작해야 PASS다.
  `no_relayout`은 B에 show 줄이 하나도 없으면(DEBUG 로그 꺼짐) FAIL이다.
- 17b/18이 TXT 책을 짧은 마지막 쪽에 남기므로, 그 뒤와 52 시작에서 `goto_page 3`으로 본문이 찬 쪽으로 간다.
- 실패한 단계는 `CHECK <단계> FAIL step incomplete`를 남겨, 뒤의 기대값이 조용히 빠지지 않는다.
- 페이지 라벨은 설명 "페이지 이동, N / M"으로 읽는다(13 가운데 x = 360 ± 2, 13d 8 → 3, 14d 한 쪽 다음).
  여백 값(14m, 97)은 "좌우 여백"·"상하 여백" 스테퍼의 값 글자가 "0"인지 본다. 54는 값을 읽으며 "0"까지 되돌린다(54r).
- 13 / 13b의 고정 버튼은 U가 정한 "이 페이지 고정"으로 찾고, 지금 트리의 "이 쪽 고정"을 대체 경로로 둔다.
- 단계 수가 늘어 `STEPS_UNTIL` 기본값을 1200 → 3000초로, screenshots 작업의 `timeout-minutes`를 40 → 90으로
  늘렸다. 95–98은 시간 한도에 600초를 더 받아 항상 마지막에 실행된다. build 작업은 그대로다.

검사:

- `bash -n tools/ci/screenshots.sh`: 성공. `shellcheck -S warning`: 경고 없음.
- `python3 -m py_compile tools/ci/*.py`: 성공.
- `python3 -m unittest tools/ci/test_ci_tools.py`: **31개 전부 통과** (perf_log 파싱·구간·first_is·no_relayout·
  same_start·pv_bounds·CLI, find_node 상자/클래스, raw_equal 12/16바이트 헤더, 복원용 백업 파일).
- Kotlin 검사는 이 레인에 해당 없음(파일 변경 없음).

남은 일:

- 통합 뒤 `[screens]` 실행에서 각 CHECK 줄과 샷을 눈으로 확인(PLAN §5.1 8). 라벨은 각 레인이 스펙대로 만든다는
  전제다. 찾지 못한 라벨은 `NOT FOUND`로 남고, `ui_fail_<step>.xml`이 저장된다.
- 92–93은 W2 병합 뒤 다시 실행.
- 독립 리뷰 2건(스펙 완전성, 버그)의 실제 지적은 모두 반영했다: 13g가 밝기 막대를 끌던 문제(마지막 SeekBar 사용),
  02의 오래된 dump, 툴바 ⋮를 `"메뉴" exact`로 찾기, 50의 좌표 탭 제거, CHECK id 중복 정리 등.
  42–44의 열 수·행 높이 같은 배치 값은 샷으로 눈 확인한다.
