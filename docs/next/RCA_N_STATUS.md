# RCA-N (RC-A part N: 노트 열기·peek·인용 색·수명주기) 구현 결과 (2026-10-03)

ReaderActivity의 열기 경로(PLAN §1.6.1)와 afterOpen/onPause/onDestroy 순서(§1.6.2)를 노트 기능과 합쳤다.
다른 파트(RCA-U, RCA-S)의 멤버가 필요한 자리에는 `// R3 merge(RCA-X): …` 주석만 두었다.

## startOpen — PLAN §1.6.1 대응 (줄 단위)

1. `place = restoredPlace; restoredPlace = null` → 그대로.
2. `jump = if (place == null) ReaderJump.from(intent) else null` → 그대로 (getExtra 6회, I/O 없음).
3. `sigPlain = LayoutKeys.textSignature(eff, d.format, b.encoding)` → `sigText`, `noteSig = NoteSig.of(sigText, b.sizeBytes)`.
4. `remap` → 같은 `sigText` 재사용. `saved`, `kept`(ReaderRestore.start), `target`(kept 없고 jump 있을 때만 resolve).
5. `start = kept ?: target ?: saved`, `sec` 클램프, `anchor = DocPosition(sec, …)` — **세션 공개 전**.
6. 세션 서명 캐시(sigSession/sigPlain/sigNote) → `s.listener`, `session = s`, `adopted = true`, `viewReady.await()`.
7. `s.setViewport(vw, vh, if (target != null) null else AnchorSpec(sec, anchor.offset))` (C16).
8. `s.startCounting(COUNT_DELAY_MS)`, `s.layout(sec)` (실패 시 기존 오류 경로).
9. `idx = if (target != null) l.pageForOffset(off) else AnchorMath.pageFor(l, off)`.
10. 저장 좌표가 그대로 쓰였고(`exact`) `JumpAnchor.matches(l.content.text, off, jump.anchor)`일 때만
    `ownerHighlights[OWNER_JUMP] = Highlight(off, min(end, len), SEARCH)` — 다시 그리기 호출 없음.
11. `preloadImages` → `session !== s` 확인 → `showPage(…, Nav.OPEN, anchorOffset = if (target != null) -1 else off)`
    (스크롤의 정확한 offset/CONTEXT 배치는 `// R3 merge(RCA-S)`).
12. 같은 메시지 안에서: `if (!isOnCurrentPage(saved)) returnNav.onJump(saved)`, `peek.start()`,
    `setIntent(ReaderJump.strip(getIntent()))` — 페이지·표시·칩이 한 번의 e-ink 갱신.
13. `jump != null && target == null` → 토스트 "노트가 있던 곳을 찾지 못했습니다".
14. 이후는 기존대로 `writeTextPosition`(peek 중이면 건너뜀) → `afterOpen()`.
    showPage 앞에 새로 들어간 것은 ReaderJump.from, OWNER_JUMP put, AnchorSpec뿐이다. H4의 `RAPerf show` 유지.

## afterOpen — §1.6.2 (8단계, IO는 launch만)

1 `ResumeState.opened(id)` · 2 `ReaderIo.launch { InstallState.ensure(appCtx) }` · 3 `light.afterFirstPage()` ·
4 `DeviceClass.probeAsync(appCtx) { QuoteLook 갱신, generation이 바뀌면 refreshDecor 1회; // R3 merge(RCA-S): scroll?.onDeviceClass() }` ·
5 반환 표시 로드(U §3.3: IO에서 `BookPrefs.returnMark(id)`, main에서 isDestroyed/책 id/세션 확인 후 `returnNav.restore`) ·
6 `if (settings.shows(EPISODE)) scheduleEpisodes()` · 7 노트 점프를 소비했으면 앵커 검사 ·
8 `reloadAnnotations` 결과 도착 시 장소 backfill. 끝에 DEBUG `RAPerf afterOpen <ms>` (ReaderPerf.turns).

## 그 밖의 구현

- **Peek (N §6.2)**: 순수 `PeekRule`. 활성 중에는 savePositionNow·예약 저장·writeTextPosition·pause 저장을 모두 건너뛴다.
  해제: 첫 수동 넘김, TTS 시작, 자동 넘김 시작, 사용자 점프(jumpTo: 목차·검색·이동·seek·링크·화 이동·반환 칩/띠),
  "여기서 읽기"(selection.onReadAloud), 스크롤 첫 정착(RCA-S가 `endPeek(SCROLL_SETTLE)` 호출).
  해제되면 표시 중인 페이지로 저장을 다시 예약한다(해제한 넘김의 저장이 건너뛰어졌기 때문).
  `rp.peek`을 onSaveInstanceState에 저장하고 복원된 장소와 함께 되살린다(C8, jump_done 없음).
- **onNewIntent (N §6.3)**: 같은 책 + 점프 → resolve 실패 시 같은 토스트, 아니면 `goTo(remember = true)`.
  대상 구간이 이미 배치되어 있고 앵커가 맞으면 표시를 점프 전에 넣어 한 번에 그린다. 그 밖에는 앵커 검사.
  다른 책 → `closeCurrentBook()` → `startOpen(intent)`(기존).
- **앵커 검사 (N §6.4)**: 맞으면 끝. 아니거나 frac 대체였으면 Dispatchers.Default에서 `JumpAnchor.search`
  (대상 구간부터 바깥으로 +1, −1, …, 최대 48구간/300만 자, 구간마다 ensureActive). 수동 넘김·사용자 점프·닫기로 취소.
  찾으면 반환점 없이 이동하고 표시도 옮긴다(peek 유지) + "노트 위치를 다시 찾았습니다".
  못 찾으면 "노트가 있던 곳을 찾지 못해 가까운 위치를 열었습니다". 찾은 위치는 노트에 다시 쓰지 않는다.
- **NotePlaceHost**: 장 제목(공백 정리, ≤ 200, TOC 없으면 ""), `counts.charProgress`, 세션 NoteSig(EPUB은 "").
  세션이 없으면 `NotePlace.UNKNOWN`. toggleBookmark는 IO 전에 main에서 장소를 계산해 `addBookmark(…, place)`.
- **Backfill (N §6.5)**: frac < 0인 인용·북마크(열 때마다 최대 500개)를 main에서 64개씩 `handler.post`로 계산하고,
  끝에 `Library.fillNotePlaces` 한 번. sig는 ''. 열기당 한 번.
- **인용 표시 (N §6.5–6.6 + K2)**: `Highlight(q.start, q.end, QUOTE, q.style)`. sig가 세션 NoteSig와 같으면 그리고,
  ''이거나 다르면 `JumpAnchor.matches(구간 텍스트, q.start, q.text)`일 때만 그린다. 구간이 decor에 처음 쓰일 때
  한 번 검사하고 (세션, 구간)별로 캐시한다. 같은 결과를 `QuotePlaceHost.quoteMoved(q)`로 목차의 "· 위치 바뀜"에 제공한다
  (모르면 sig로 판단). 목차/선택이 `setHighlights(OWNER_QUOTES, …)`를 부르면 그 구간 캐시를 버리고 행을 다시 읽어
  (sig·style·text 포함) 다시 검사한다(갱신 1회).
- **DecorDiff.same**: start/end/kind/style, bookmarked, statusVersion 비교(순수). sameDecor가 사용한다.
  OWNER_JUMP/OWNER_SEARCH 제거 자리에 `// R3 merge(RCA-S): scroll?.onHighlightsChanged(section)`.
- **applyAppSettings**: 기존 본문 뒤에 `light.onAppSettingsApplied()` → QuoteLook(+ generation 변화 시 refreshDecor 1회)
  → `// R3 merge(RCA-S): applyReadMode()` → `// R3 merge(RCA-U): applyPageInsets()`. viewPart에 `highlightLook` 추가.
- **onPause**: `// R3 merge(RCA-S): scroll?.stopMotion()` → (peek 아니면) 위치 저장 → `ResumeState.paused()` → … → `light.onPause()`.
  **onDestroy**: ResumeState 규칙 → `light.onDestroy(isFinishing)` → `// R3 merge(RCA-S): scroll?.detach()` → 앵커 작업 취소.
- **ReaderMenus (C22)**: "내 리뷰" 다음에 "독서 노트"(ic_format_quote → `NotesActivity.open(this, NotesTab.ALL, book.id)`).
  W2 썸네일 자리 주석. 북마크(좁은 화면, RCA-U)와 스크롤/자동 스크롤(RCA-S) 항목은 그 파트가 넣는다.

## 결정·차이

- 사용자 점프 전체(jumpTo)가 peek을 끝낸다: 스펙의 "기억되는 점프"와 반환 칩/띠를 포함하고, 다음/이전 화도
  사용자가 직접 움직인 것이라 같이 끝낸다. 앵커 검사의 이동은 jumpTo를 거치지 않아 peek을 유지한다.
- 앵커 검사의 탐색 순서는 "±3 먼저, 그다음 바깥으로"를 하나의 바깥 방향 순서(+1, −1, +2, …)로 구현했다(같은 결과).
- 목차 "· 위치 바뀜"용 계약이 없어 `reader/ReaderJump.kt`에 `QuotePlaceHost`를 새로 두었다(EX-N 사용 요청).
- `returnHost`의 `TODO("owner: RC-A")`와 반환 칩 구 코드는 RCA-U 소유라 그대로 두었다.

## 검사

- `tools/typecheck.sh --own …`(레인 파일): 성공.
- 레인 테스트: `tools/unittest.sh --own reader/ReaderJump.kt … --own reader/ReaderRestoreTest.kt` → OK (29 tests).
  (11개 --own 전체는 출력 경로 이름이 너무 길어 도구가 실패하므로 순수 파일과 테스트만 지정했다.)
- 전체 `tools/typecheck.sh`: 성공. 전체 `tools/unittest.sh`: **OK (1,212 tests)** (기준 1,195 + 새 17개:
  DecorDiffTest 4, PeekRuleTest 4, ReaderJumpTest +8(find, searchOrder, search 상한·취소, K2 판정, EPUB sig 불일치,
  NoteSig, 장 제목 정리), ReaderRestoreTest +1).
- 독립 검토 2회(스펙 완전성, 버그) 반영: OWNER_QUOTES 목록을 검사 없이 캐시하지 않고 다시 읽기, 복원된 peek은 복원 장소가
  쓰일 때만 유지, peek 해제 시 앵커 탐색 취소와 텍스트 서명 기록, 같은 책 노트 점프는 구간을 먼저 배치해 표시와 페이지를
  한 번에 그림, `returnNav.onJump`을 safely로 감쌈, 검색 표시 제거·스크롤 정착 병합 주석, `RAPerf afterOpen N ms` 형식.

## 남은 일 (RC-A 병합 / CI / 기기)

- 병합 시 `// R3 merge(RCA-S)` 주석 6곳과 `// R3 merge(RCA-U): applyPageInsets()`를 실제 호출로 바꾼다.
- RCA-S: 첫 사용자 스크롤 정착에서 `endPeek(PeekRule.Event.SCROLL_SETTLE)`, ScrollReader.Host.highlights(section)은
  `quotesFor(section, s.peek(section)?.content?.text)` + ownerHighlights를 쓴다.
- Comet 수동 확인: 노트에서 열기(페이지·표시·칩 한 번 갱신), 뒤로 → 서재 순서/진행률 그대로, 3쪽 넘김 후 저장 재개,
  프로세스 종료 후 복원 시 peek 유지.
