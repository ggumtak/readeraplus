# EX-P 읽기 설정 팝업 구현 결과 (2026-10-03)

읽기 설정 팝업을 PLAN §1.6.3 순서로 다시 구성했다. 대상: ReadingSettingsPopup.kt, CompactUi.kt, ExtrasFormat.kt.

- 메인 9줄, 스크롤 없음 (U polish 8): 스타일 · 글꼴 · 글자 크기 · 굵기 · 줄 간격 · 문단 간격 · 들여쓰기 ·
  "정렬 [왼쪽│양쪽]   줄바꿈 [어절│글자]" 한 줄 · 더보기 ›. 더보기 줄은 글자와 chevron만 있고 회색 요약은 없앴다.
- 치수: ROW_DP 44, STEP_DP 44, LABEL_SP 15, VALUE_SP 16, HEADER_SP 13, 토글 높이 36dp.
  일반 줄 위에는 LINE_LIGHT 선을 양쪽 12dp 안쪽으로 긋는다. 검은 선은 섹션 머리말과 더보기 위에만 있다.
- 세그먼트 (polish 18): 새 `CompactSegments`. 1px 테두리 하나, 1px 구분선, 모서리 없음. 선택 칸은 검은 바탕에 흰 보통 글씨다.
  굵게 폭 예약 방식은 없앴다. 프리셋, 정렬/줄바꿈, 페이지 나눔, 끊어진 줄 합치기가 이것을 쓴다.
- 위치 (polish 7, U §2.6): 가운데 정렬로 `showAtLocation(root, TOP|CENTER_HORIZONTAL, 0, top)`.
  폭 = min(W − 16dp, 400dp), top = Overlay.topInset + 8dp, HEIGHT_FRACTION 0.56.
  메뉴 고정 분기는 없앴다. 열 때 항상 바를 숨긴다.
- 더보기 아래:
  - 페이지 넘김: 넘기는 방식 (스크롤 선택 시 `DeviceClass.probeAsync`) · 화면 터치 · 볼륨 키 (H3의 3가지 선택 유지)
  - 글자: 글자 간격
  - 페이지:
    - 좌우/상하 여백: −40…+40, "0" = 40dp, `Fmt.signed`로 "−10" 표시, 값은 live region
    - 페이지 여백: 끄면 두 stepper를 숨긴다
    - 흑백 반전
    - 페이지 나눔: 줄 단위/문단 단위. 즉시 적용하며 debounce하지 않는다
    - 외톨이 줄 방지: 문단 단위일 때 요약 "한 쪽보다 긴 문단에만 적용"
  - TXT/EPUB: 책 형식 순서대로
  - 상태 표시:
    - 위/아래 슬롯 3칸: 44dp 터치, 36dp 상자. 빈 칸은 점선 테두리에 "없음"
    - 설명 "아래 오른쪽: 시계"
    - 선택 목록: StatusSampleHost의 실시간 값, 없으면 example. `CompactList`를 maxHeightFraction 0.8로 연다
    - 진행 막대: 요약 "화면 맨 아래 가는 선"
    - 상태 글자 크기: hasHeader || hasFooterText일 때만 보인다
    - StatusFit 판정이 실패하면 회색 안내문을 표시한다
- 상태 변경은 모두 `applySettings` 한 번으로 전달한다. relayout인지 repaint인지는 host(LayoutKeys)가 정한다.
- `CompactList.show`와 `PopupGeometry.dropdown`에 `maxHeightFraction`(기본 0.56)을 추가했다.
  목록의 note(실시간 제목)는 폭의 45%에서 말줄임으로 자른다.

결정/차이:

- `compactRowBackground`는 기본을 검은 전폭 선으로 유지하고, 연한 선은 `light = true`일 때만 쓴다. 다른 lane의 RulesDialog 모양을 바꾸지 않기 위해서다.
- 더보기 펼침 상태는 지금처럼 프로세스 동안 기억한다. 펼친 채 다시 열면 스크롤할 수 있다. 메인 9줄은 접힌 상태 기준이다.

검사:

- module mode `tools/typecheck.sh --own …` 성공, `tools/unittest.sh --own …` **40개 통과**.
- 전체 `tools/typecheck.sh` 성공, 전체 `tools/unittest.sh` **1,201개 통과**.
- 새 PopupGeometryTest가 다루는 것:
  - 폭, top = inset + 8dp, 0.56
  - 1440px에서 메인 792+2px ≤ 806px
  - dropdown 0.8
  - "−10"
  - 슬롯 설명, 글자 크기 표시 조건, 안내문, 외톨이 요약
- 이전 55%/420dp 검사는 CompactSettingsTest에서 옮겼다.
- 리뷰어 2명(명세/버그)의 지적 2건을 반영했다: 긴 제목 note, RulesDialog 선 색.

남은 일: RC-A 추가 연결은 없다. 기존 `ReaderPanels.showReadingSettings` 경로를 그대로 쓴다.
`StatusSampleHost` 구현은 RC-A/ReaderActivity의 몫이다. 화면 캡처와 e-ink 확인은 CI 52/53과 기기 검사에서 한다.
