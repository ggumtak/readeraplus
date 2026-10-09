# EX-N 목차 대화상자(인용문·북마크) 구현 결과 (2026-10-03)

N §7.2의 W1 부분과 U polish 10을 구현했다. W2 썸네일(ThumbsTab, InkPager.PageTarget, InkNumPad)은 하지 않았다.

- 인용문 행: `[본문 / 메모 / 메타 (weight 1)] [색 칸 48 dp]`. 견본(QuoteSwatch, 12 dp)은 첫 줄 높이에 맞춰 위쪽에 둔다.
  색 칸은 클릭 가능한 자식이 아니다. 행의 OnTouchListener가 DOWN x만 기록하고 false를 반환하므로,
  InkPager의 DragToPage는 모든 DOWN을 그대로 받는다(드래그 = 한 페이지 이동 유지).
  행 클릭에서 `x ≥ 행 폭 − 48 dp`이면 팔레트(QuotePalette.show(색 칸, 현재 색)), 아니면 이동한다.
  키·접근성 클릭은 DOWN 뒤 1.5초가 지나면 좌표를 쓰지 않으므로 항상 이동한다.
- 색 바꾸기: 팔레트 선택 → IO `Library.updateQuoteStyle` + `Library.quotes` → 책이 그대로면
  `refreshQuoteHighlights` → 탭을 한 번에 다시 구성(e-ink 1회 갱신). 확인 대화상자는 없다. 마지막 색(`extras.quoteStyle`)도 기록한다.
  대화상자가 닫혀도 페이지 하이라이트와 QuoteCache 갱신은 끝까지 수행한다. 길게 누르기 메뉴에 "색 바꾸기"(ic_ink_highlighter)를 추가했다.
- 필터 칩: 2가지 색 이상일 때만 `[전체 N] [● n] …`(44 dp, 1px 테두리, 선택 시 검정 바탕·흰 글자)을 보인다.
  메모리에서만 거르고 저장하지 않는다. 탭 이름은 "인용문 5 / 12" 형식이다. 칩이 5개 이상이면 폭을 나눠
  360 dp 안에 모두 들어가게 했다(가로 스크롤 없음).
- 모두 공유: 거른 목록을 공유한다. 2가지 색 이상이면 각 항목 앞에 "[초록]"을 붙이고, 한 색이면 기존 형식과 같다.
  `TextActions.SHARE_MAX_CHARS`(50,000)에서 마지막 완전한 항목 뒤를 잘라 "\n…"을 붙인다.
- "· 위치 바뀜": PLAN K2 규칙을 따른다. sig가 세션 NoteSig(NotePlaceHost)와 같으면 정상이다.
  다르거나 ''이면, 그 구간이 현재 배치되어 있을 때 `JumpAnchor.matches`의 결과로 판단한다. 알 수 없으면 sig로만 판단한다.
  같은 규칙을 `refreshQuoteHighlights`에도 적용해, 위치가 바뀐 인용문은 그리지 않는다. Highlight에 style을 전달한다.
- "모든 책의 노트"(ic_open_in_new) 아이콘은 북마크·인용문 탭에서만 보인다. 누르면 대화상자를 닫고
  `NotesActivity.open(ctx, BOOKMARKS / QUOTES)`를 연다.
- 목차 제목: 키트 `toolbar()`(20 sp bold)를 쓴다. 키트 툴바 아래 구분선이 하나 추가된다.
- 순수 로직은 새 `QuoteRows`(칩·필터·탭 이름·위치 판정·색 칸 판정·공유 문자열)로 분리하고, `QuoteRowsTest` 9개를 추가했다.

변경 사항(계약 차이):

- 위치가 바뀐 인용문은 `PageJumpHost.goToProgress(q.frac)`로 연다. 화면 비율 기준이라 문자 비율과 조금 다를 수 있고,
  anchor 검색도 하지 않는다. → contractRequest로 남겼다.
- `QuoteExport.tagged`(EX-S의 새 파일)가 기준 커밋에 없어서 `QuoteRows.tagged`로 같은 규칙을 구현했다. 병합 뒤 교체할 수 있다.
- `setQuoteStyles`(일괄 변경)는 허브 전용이라 목차에서는 쓰지 않는다.

검사:

- 모듈 모드 `tools/typecheck.sh --own reader/extras`: 성공. 파일별 --own을 모두 나열하면 출력 디렉터리 이름이
  너무 길어지는 도구 제한이 있다. 레인 밖 extras 파일은 스냅샷과 같다.
- 모듈 모드 unittest(ContentsDialog·QuoteRows와 레인 테스트 5개): **68개 통과**.
- 전체 `tools/typecheck.sh`: 성공. 전체 `tools/unittest.sh`: **1,204개 통과**(기준 1,195 + 9).
- 레인 파일의 `R3 stub` / `TODO("owner` grep 결과: 없음.

남은 일:

- RC-A: NotePlaceHost 구현(sig, EPUB는 ""). 위치가 바뀐 인용문을 여는 문자 비율 + anchor 점프 경로.
- EX-S: QuoteSwatch는 행 안에서 클릭 가능하게 만들지 말 것(setOnClickListener / isClickable 금지).
- CI 83 스크린샷과 기기에서 칩 배치, 견본 정렬, 드래그 페이지 이동을 확인한다.
