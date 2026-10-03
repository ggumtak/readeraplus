# RCA-U (RC-A part U) 구현 결과 (2026-10-03)

ReaderActivity의 U 영역(크롬, 복귀 지점, 밝기 연결, 상태 슬롯)을 연결했다. ReaderChrome·ReturnNav·LightController·
StatusModel·ChromeMath의 실제 본문은 RU 레인이 채운다. 이 브랜치만으로는 그 스텁이 그대로이므로, 화면 동작은 병합 뒤에 확인한다.

- **고정 메뉴 제거 (U §2.6, C18):** `pinShown`, `pinPending`, `pinnedArea`, `chromeBarHeights`, `togglePin`을 삭제했다.
  크롬 막대의 `barsResized` 등록, `!app.pinChrome` 자리였던 조건과 고정 상태의 `openReadingSettings` 분기도 삭제했다.
  `applyPinnedArea`는 `applyPageInsets`(인셋만 반영)로 바꿨다. `applyInsets(i)`는 인셋을 저장하고 `applyPageInsets()`를 부른다.
  이제 PageView 크기는 InsetsGate를 거친 시스템 인셋만 따른다. 메뉴가 열려 있을 때 탭이나 스와이프를 하면 항상 메뉴만 닫는다.
  `onBarsResized`는 `updateChipPosition`만 하며, extras가 띄우는 하단 막대에만 등록한다.
- **밝기 (U §2.3, §4.2):** `PREF_BRIGHTNESS_COLLAPSED`, `PREF_LAST_BRIGHTNESS`, `setBrightness`,
  `chrome.setBrightnessCollapsed/setBrightness` 호출을 삭제했다.
  - `applyAppSettings`의 첫 줄은 `light.onAppSettingsApplied()`이다. `page.brightnessSwipe`는 더 이상 직접 쓰지 않는다.
    값을 쓰는 곳은 private `lightHost.setPageBrightnessSwipe` 하나뿐이다.
  - 그 밖의 연결:
    - `onCreate`: `buildViews()` 직후 `light.onCreate()`
    - `onResume`: `light.onResume()`
    - 가장자리 스와이프: `light.currentPos()`와 `light.onDrag(v, done)`, 기존 오버레이 표시
    - `bindChrome`: `light.bind()`
    - ⋮ "설정": `openAppSettings()` → `light.markOwnLaunch()` 후 SettingsActivity
  - `viewPart`에 `a.brightnessDevice`를 추가했다.
- **복귀 지점 (U §3.5, C24):**
  - 삭제: `returnStack`, `MAX_RETURN_STACK`, `CHIP_HIDE_TURNS`, chip/chipLabel, `turnsSinceJump`, push/show/use/dismiss 함수.
  - 생성 순서: `buildViews`에서 light, returnNav, ReaderChrome 순으로 만들고 `light.attach(chrome)`를 부른다.
    `returnNav.chip`은 BOTTOM|START로 root에 붙인다.
  - 호출 지점:
    - `goTo`, `goToPage`, `goToProgress`(seek 해제와 링크 포함)는 기존 가드를 유지하고 `returnNav.onJump(currentPosition())`을 부른다.
    - `onManualTurn`은 `returnNav.onManualTurn()`을 부른다.
    - `setChromeVisible`은 `onChromeShown/onChromeHidden`을 부른다.
    - `bindChrome`은 `returnNav.bind()`와 `chrome.setPinned(pinned, markOnScreen())`을 부른다.
    - 카운트 완료 시 열려 있는 크롬을 다시 bind한다(복귀 막대 포함).
    - `reopenDocument`: 세션 교체 직전에 `markFraction()`, 교체 후 `reparsed(f, exact = EPUB && 섹션 수 동일)`를 부른다.
    - `closeCurrentBook`: `returnNav.reset()`.
  - private `returnHost`는 TODO 없이 구현했다:
    - `currentPosition`은 `ReaderHost.currentPosition()`에 맡긴다(RCA-S가 스크롤 분기를 추가한다).
    - `globalPageOf`는 counts를 쓰고, `jumpToReturn`은 `jumpTo(s, o, -1)`이다.
    - `charProgressOf`, `locateFraction`, `textSignature`를 구현했다.
    - `saveReturnMark`는 `ReaderIo.launch { BookPrefs.setReturnMark }`이다.
    - `onReturnChanged`는 크롬이 열려 있으면 `bindChrome()`, 이어서 `updateChipPosition()`을 부른다.
  - 칩 위치: `bottomMargin = insets[3] + 12dp + 4dp`, extras 막대가 있으면 그 위, `leftMargin = insets[0] + 8dp`.
    오버레이이므로 페이지 크기는 바뀌지 않는다.
- **상태 슬롯 (U §5.3):** `buildDecor(sample)`이 `StatusInputs`를 채운 뒤
  `status.update(settings, inputs, trackPx)`로 공유 `StatusDecor`를 갱신한다.
  결과는 `PageDecor(hl, bookmarked, decor, decor.version)`이다.
  - 입력은 슬롯에 보이는 항목만 채운다. 막대 위치는 `progressBar`일 때만 채우며, 책 마지막 쪽이면 1이다.
  - `chapterStartsHere`(polish 16)를 계산한다. 회차가 아직 파싱되지 않았거나 번호가 없으면 -1로 두어 슬롯이 비게 한다.
  - 시계는 Calendar 없이 순수 `StatusClock.minuteOfDay`로 구한다. 시간대와 24시간제 여부는 onResume 뒤 첫 샘플에서 다시 읽는다.
    배터리는 캐시된 IntentFilter로 1분에 한 번 읽는다.
  - `sample = true`는 showPage와 onResume(`refreshDecor(onlyIfChanged = true, sample = true)`)에서만 쓴다.
    나머지 갱신은 직전 시계·배터리 값을 그대로 쓰므로, 자기 데이터가 바뀌었을 때만 다시 그린다.
  - 오래된 프레임 검사는 `buildDecor`보다 먼저 한다(변경 불변식). 하이라이트 목록은 겹치는 것이 있을 때만 만든다.
  - `trackPx`는 렌더러와 같은 규칙(`StatusFit.lane` + `ProgressMath.trackPx`)이다.
  - `StatusSampleHost.statusSample`은 별도 입력 객체를 채운 뒤 `status.sample`을 부른다.
  - `scheduleEpisodes`는 EPISODE 슬롯이 있을 때만 동작한다. 쓰이지 않던 `episodeLabel`, `timeLeftLabel`, `clock()`은 삭제했다.
- **ReaderFormat (polish 17):** `footerLeft`(두 오버로드), `footerRight`, `returnChip`을 삭제했다.
  `previewLabel`은 "1234쪽 · 제3장 …" 형식이다.
- **C22 (좁은 화면):** ⋮ "북마크 추가/삭제"는 `!ChromeMath.bookmarkFits(rowW, density)`일 때만 "페이지 이동" 바로 뒤에 나온다.
  rowW는 root 폭에서 좌우 인셋을 뺀 값이다.
- `onCreate` 첫 줄에 `ReaderPresence.inFront = true`를 넣었다(S §1.10). H4의 `RAPerf show` 줄은 그대로 두었다.

검사:

- 모듈 모드 `tools/typecheck.sh`: 성공. `tools/unittest.sh`: **38개 통과** (ReaderFormatTest, ReaderR2FeaturesTest,
  ReaderReviewFixesTest, 새 StatusClockTest).
- 전체 트리 `tools/typecheck.sh`: 성공. `tools/unittest.sh`: **1,196개 전부 통과**
  (기준 1,195개에서 삭제한 footer 테스트 2개를 빼고 StatusClockTest 3개를 더함).
- 독립 리뷰 2건(명세 완결성, 버그)을 반영했다:
  - 넘김 경로의 박싱과 iterator를 없앴다(남은 시간은 primitive -1, 빈 맵 가드, 인덱스 루프).
  - 다시 켠 시계·배터리 슬롯에 오래된 값이 그려지지 않게 했다.
  - 재배치가 대기 중일 때는 `refreshDecor`/`repaint`가 공유 decor를 건드리지 않는다.
  - `onResume`은 설정 반영 경로에서도 시계를 다시 읽는다.
  - 회차 요청을 넘김마다 다시 예약하지 않는다.
- 확인 grep `pinChrome|applyPinnedArea|returnStack|PREF_BRIGHTNESS_COLLAPSED`, `R3 stub|TODO("owner`: 레인 파일에서 출력 없음.

RC-A 병합과 기기 확인이 남아 있다:

- RCA-N: `afterOpen`의 `light.afterFirstPage()`와 복귀 지점 로드(id·session 가드 후 `returnNav.restore`),
  `onPause`의 마지막 `light.onPause()`, `onDestroy`의 `light.onDestroy(isFinishing)`,
  메모에서 열 때 같은 메시지 안의 `returnNav.onJump(saved)`.
- RCA-S: 스크롤 정착 시 상태 입력 채우기(위 주석 위치), `currentPosition`·`isOnCurrentPage`의 스크롤 분기,
  ScreenCounter마다 `onManualTurn()`.
- 기기에서 확인할 것: 크롬 열기·닫기 때 페이지 재배치가 0회인지, 칩 위치(진행선 위), 좁은 폰에서 ⋮ 북마크 항목,
  시계·배터리 갱신이 넘김과 재개 때만 일어나는지.
