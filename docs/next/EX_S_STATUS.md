# EX-S 선택 팝업·인용 구현 결과 (2026-10-03)

선택 팝업을 한 줄 5칸으로 바꾸었다. 인용문 색, 팔레트, 마지막 색, 단어장 기록, 문장 문맥, 인용문 내보내기를 구현했다.
ReaderActivity 연결(NotePlaceHost, 하이라이트 style 비교)은 RC-A, DB의 style 읽기와 쓰기는 DA-C, 단어장 기록은 DA-N이 맡는다.

- **한 줄 5칸 (U13, C26):** 복사 · 인용 · 메모 · 사전·번역 · ⋮.
  - 칸 너비는 (W − 16dp)/5, 높이는 56 dp, 라벨은 13 sp(좁은 화면에서는 10 sp까지 자동 축소), `borderBox(radiusDp = 0f)`.
  - 칸 구성은 순수 객체 `SelectionActions.ids/split`가 정하고 테스트로 확인한다.
  - ⋮ 메뉴 순서: 색 골라 인용… · 공유 · 문단 · 검색 · 웹 검색 · 여기서 읽기 · 문구 지우기(TXT만).
- **인용 칸:** 마지막 색의 `QuoteSwatch`와 9 sp "▾"를 보여 준다.
  - 탭: 즉시 저장. 성공 토스트는 없고, 실패 토스트 "저장하지 못했습니다"만 남겼다.
  - 길게 누르기, 또는 ⋮ → 색 골라 인용…: 인용 칸에 붙은 `QuotePalette`가 열린다. 고른 색으로 저장하고 `extras.quoteStyle`에 기억한다.
  - 메모: 메모를 입력한 뒤 마지막 색으로 저장한다.
- **e-ink 1회 갱신:** 새 인용의 "quotes" 하이라이트 목록(QuoteCache + 새 인용, DB와 같은 순서)을 `clear()`와 같은 main 메시지에서 적용한다.
  - IO에서 저장하고 다시 읽은 결과는 같은 decor가 되어 다시 그리지 않는다.
  - 실패하면 하이라이트를 되돌린다(1회 갱신 + 토스트).
  - QuoteCache가 아직 없을 때는 낙관적 표시를 하지 않는다. 다시 읽은 결과만 그린다.
- **기존 인용:** 팔레트 행(현재 색에 링)이 [복사 · 메모 · 인용 삭제 · 사전·번역 · ⋮] 위에 있다.
  - 선택 범위가 인용과 같으면 선택 칠을 그리지 않는다.
  - 색 탭: 페이지와 링을 즉시 바꾸고 팝업은 열어 둔다. IO에서 `updateQuoteStyle`로 저장한다.
  - 연속 탭이면 마지막 탭의 결과만 적용한다(세대 번호). 확인 창은 없다.
- **위치 정보:** `snapshot()`이 `NotePlaceHost.notePlace(DocPosition(section, selStart))`로 위치를 얻어 `addQuote(…, style, place)`에 넘긴다.
- **단어장 기록:** 사전·번역과 웹 검색은 `clear()` 전에 `LookupSnapshot`을 만든다.
  - 기록 조건: `TextActions.lookUp(onPicked)`, `webSearch(onDone)`가 앱 실행에 성공했을 때만. `recordLookups`가 켜져 있으면 `ReaderIo`에서 `Lookups.record`를 조용히 실행한다.
  - 선택 창을 취소하거나 실행에 실패하면 기록하지 않는다.
  - `TextActions.start`는 이제 Boolean을 반환한다. 기존 호출부는 그대로 컴파일된다.
- **순수 객체:**
  - `LookupContext.sentence`: 문장 경계, 양쪽 150자에서 공백 기준 "…" 자르기, U+FFFC 제거, 공백 정리. 서로게이트 쌍을 자르지 않는다.
  - `WebSearchTemplate`: 검색 URL과 기록할 사이트 host.
  - `QuoteExport.tagged/shareAll/single`: 2색 이상일 때만 "[초록] “…”" 꼬리표를 붙인다. 1색이면 기존 출력과 바이트 단위로 같다.
  - `QuoteHighlights`: DB 순서 유지, `sig`가 다른 인용 제외.
  - `PaletteGeometry`.
- **QuoteSwatch:** 색 점(1 px 회색 테두리), 밑줄 "가", ink 견본(회색 띠와 선 모양 5종), 체크 링(2 dp, 3 dp 바깥). onDraw는 할당하지 않는다.
- **QuotePalette:** 6칸, 칸마다 max(48dp, (W−16dp)/6) × 56 dp, 12 sp 라벨. 애니메이션과 elevation이 없고, 바깥을 터치하면 닫힌다. PanelRegistry에 등록한다.
  - 대화상자 안에서 열면 그 창을 부모로 쓰고, 팝업 안에서 열면 activity 창을 부모로 쓴다.
- **A §2.5 원점 fallback:** `LayoutKeys.geometry`의 contentLeft/Top을 쓴다(여백만 반영하고 header 항은 없다). 스크롤 모드의 가상 페이지도 같은 content box 기준이므로 같은 값이 된다(테스트).
- **TTS 검증(S):** TtsController는 수정하지 않았다.
  - `onRange`는 같은 section일 때 `off ≥ page.end`이면 follow한다. `follow`는 다음 페이지면 `nextPage()`, 아니면 `goTo(remember=false)`를 부른다.
  - `navRestart`는 600 ms 뒤 가상 페이지를 기준으로 판단한다.
  - 사용자가 움직이는 동안의 억제와 정지 시 재무장은 RC-A가 해야 한다(rcaNotes).
  - 경계(seam)의 아래 section에서 문장이 보여도 `navRestart`는 다시 시작한다(section 비교). 영향은 작은 동작 차이다.

결정·편차:
- 기존 인용의 ⋮에는 "색 골라 인용…"을 넣지 않았다. 팔레트 행이 이미 있기 때문이다.
- 여러 줄을 선택했을 때 "문구 지우기"는 회색으로 표시하지 않는다. 탭하면 기존 설명 토스트를 보여 준다(kit popupMenu는 비활성 항목의 탭을 받지 않음).
- 기존 인용의 색 변경은 낙관적으로 즉시 반영한다(명세는 IO 후 반영). e-ink 1회 갱신 원칙을 따른 것이다.

검사:
- 모듈 모드(`--own reader/extras --own engine/EngineFixtures.kt --skip reader/extras/CleanupPacksPerfTest.kt`, 이름 길이 제한 때문에 디렉터리 단위로 지정): typecheck 성공, unittest 164개 통과.
- 전체 트리: typecheck 성공, unittest **1,221개 전부 통과**.
- 새 테스트: SelectionActionsTest 9, LookupContextTest 9, QuoteExportTest 4, PaletteGeometryTest 4.
- 독립 리뷰 2회(명세, 버그): 지적 사항(낙관 목록의 빈 캐시, 색 변경 순서 역전, 대화상자 부모, 서로게이트, 테스트 범위)을 반영했다.
  - 같은 책에 인용 두 개를 아주 빠르게 연속 저장하면 첫 인용이 잠깐 사라질 수 있다. 이 문제는 남아 있고 다음 reload에서 복구된다.

남은 일: RC-A 연결, DA-C의 style 저장·조회, EX-N의 `refreshQuoteHighlights`에 style 반영, CI 17/65 스크린샷, 기기 확인(Comet에서 1회 갱신).
