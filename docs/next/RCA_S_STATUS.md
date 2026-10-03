# RCA-S 스크롤 모드·고정 재배치 연결 결과 (2026-10-03)

ReaderActivity에 ScrollReader(RC-S)를 연결했다. 페이지 모드는 각 진입점의 `scroll` null 검사 한 번 아래 그대로다
(A §5.5의 keepHere/AnchorMath.pageFor/JUMP=쪽 시작은 스펙대로 페이지 모드에도 적용).

- `scroll` 필드, `applyReadMode()`(PAGED이고 scroll == null이면 즉시 반환), `switchMode()`(stopMotion → anchor →
  TOP 배치/ `AnchorMath.pageFor`, 배치 없음, 왕복 정확). 레이아웃 대기 중 전환은 다음 showPage가 붙이거나 뗀다.
- showPage 스크롤 분기(TOP, 줄 중간 JUMP는 CONTEXT), 이미지 선디코드 ±1쪽, turn = 즉시 한 화면(애니메이션 없음),
  flushTurns 최대 10단계, holdAction(TEN/CHAPTER/REPEAT), `onScrollSettled`(anchor·cur*·displayedGenId·추적·
  e-ink 주기·저장·keeper·크롬·TTS·ScreenCounter 수동 넘김·selection), `onScrollStart`(SMOOTH 즉시, STEP은 놓는
  프레임에 크롬 닫기·검색 강조 제거 → 제스처당 e-ink 1회), 이동 중 ReaderHost next/prev/goTo(false) 억제,
  isOnCurrentPage, progress(atBookEnd = 1), 가상 페이지 currentLayout/Page/Index/Position, hitTest/glyphAtView/
  fingerOnChar(가상 페이지, content 원점), 롱프레스 focusAt, 탭 링크 focusAt/clearFocus, 북마크 토글/리본(보이는
  범위), jumpChapter는 private jumpTo, 강조 캐시 무효화, onSectionStored, 막힌 구간 전경 배치("불러오는 중…" 300 ms).
- A §5.5: keepHere(), onViewSizeChanged/applyToSession/relayout/reopenDocument 모두 stopMotion 먼저, 새 세대는
  onGenerationChanged로 고정 프레임 유지, reopen needle+AnchorSpec, anchorBreak.
- 수명주기: onStart/onStop AutoBackup, C19 trim 게이트에 scroll?.onTrimMemory(), viewPart에 a.readMode/a.scrollStyle.
- ⋮ 메뉴: "스크롤로 보기"/"페이지로 보기"(readMode 저장, SCROLL이면 probeAsync), SCROLL에서 "자동 스크롤 켜기/끄기",
  토스트 "자동 스크롤 켜짐 (한 화면/N초)" / "자동 스크롤 꺼짐".
- 순수 판단은 ReaderMath.kt 끝의 `ScrollWiring`(전환 오프셋, 배치, 단계 상한, 문구 등)과 ScrollWiringTest 9건.

결정·차이:
- NEED_SECTION 중 남은 단계는 backlog.restore 대신 ScrollNavigation의 순서 보장 큐에 넣는다(누락 없음, 이중 실행 방지).
- 스크롤 settle의 session.touch/prefetch는 ScrollReader가 이미 수행하므로 중복 호출하지 않는다.
- TTS goTo가 보이는 줄일 때 포커스 구간 변경은 ScrollReader에 공개 API가 없어 미구현(움직임·재그리기만 막음).
- fitFooter는 렌더러에 없어 생략. visibleRanges 콜백이 Int를 박싱하므로 리본 판단에 작은 할당이 남는다.

검사: 모듈 typecheck 성공, 모듈 unittest OK (17), 전체 typecheck 성공, 전체 unittest **OK (1,204)**.
리뷰 2회(스펙 완전성, 버그) 지적 사항 모두 반영.

위임 grep(S §1.10): 모든 결과가 페이지 레이아웃에 맞거나 scroll 분기 뒤에 있다. 남은 직접 사용: startOpen·
onSaveInstanceState(RCA-N, settle된 anchor), bindChrome·chapterPagesLeft·charsLeft·progress(위 쪽 = 스펙),
goToPage(스크롤에서는 항상 반환점), closeEndPanel(위 실제 쪽 추적), page.frame은 showPage/refreshDecor/repaint/
hitTest/glyphAtView/fingerOnChar/closeCurrentBook/attachScroll에서만.

RC-A 병합 시 필요: onPause 첫 줄 `scroll?.stopMotion()`, afterOpen probe 콜백 `scroll?.onDeviceClass()`,
onDestroy의 scroll detach, applyAppSettings 순서에서 `applyReadMode()` 위치, buildDecor 상태 입력(RCA-U, 두 모드 공통 PageDecor),
startOpen의 openedAtNote → CONTEXT. 기기 확인: STEP/SMOOTH 체감, 회전·글꼴 변경 시 위 줄 유지, e-ink 갱신 횟수.
