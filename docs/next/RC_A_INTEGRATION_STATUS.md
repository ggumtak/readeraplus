# RC-A 통합 진행 (2026-10-04)

기준: 주 브랜치 33545ac + 통합 브랜치 e6241ba. 두 브랜치의 변경 파일이 겹치지 않음을 GitHub compare로 확인하고 최신 레인 위에 RC-A를 합쳤다.

## 반영
- ReaderActivity의 RC-A U/S/N 통합 및 기존 회귀 테스트.
- 첫 본문 draw 성공 뒤 post하는 PageView.afterFirstFrame으로 afterOpen/지연 TXT 위치 기록 실행. 책/세션 identity로 늦은 콜백 차단.
- 돌아갈 위치를 현재 책 범위로 clamp. 메뉴가 닫힌 상태에서도 페이지 표시/스크롤 settle/쪽수 확정 때 returnNav.bind.
- 사용자 스크롤 settle에서 노트 미리보기(peek)를 끝내고 정상 위치 저장 재개.
- 스크롤 settle에서 RAPerf show OPEN/TURN/JUMP/RELAYOUT 한 줄 기록. 기존 showScroll 중복 로그 제거.
- 페이지 썸네일: PageThumbsHost/Source, 요청 시 생성, 구간별 주석 버전/paint 버전, 취소 시 이웃 prefetch, trim/close/reparse 정리. 목차 네 번째 탭과 ⋮ 항목 연결, 스크롤 모드에서 숨김. 직접 탭 진입은 준비 후 dialog.show.
- NoteJumpHost 계약 추가. 위치가 바뀐 인용은 ReaderJump의 서명/분량/본문 탐색 경로 사용.
- QuoteHighlights를 목차·선택 메뉴에서 공유하며 서명이 달라도 실제 본문이 일치하는 인용은 유지. 공유 색상 태그는 QuoteExport 재사용, 기존 길이 제한 유지.
- 설정의 22×14dp 인용 견본은 ring 공간을 예약하지 않고 실제 측정 크기 안에서 그림.
- JDK 17의 reflection accessor 초기화 비용이 상태 표시 할당 테스트에 섞이지 않도록 AllocCounter 측정 전에 접근자를 예열. 0-byte 합격 기준은 유지.

## 검토
PLAN 1.6.1의 복원 > 노트 > TXT fraction > DB 순서, 자연 페이지/앵커 선택, show 이전 인용 표시를 확인했다.
PLAN 1.6.2의 presence/buildViews/light/restore, afterOpen 순서, pause/stop/destroy, 설정 적용 순서를 확인했다.
scroll SPEC 1.10 중 모드 전환, 가상 페이지, selection.focusAt, TTS 이동 억제, settle/save/decor, generation/insets 경로와 레인 rcaNotes를 검토했다.
애니메이션 관련 ValueAnimator/ObjectAnimator/startScroll/fling/animate 호출 없음. 첫 페이지 전 새 탐색·기기 밝기·백업 작업은 추가하지 않았다.
`R3 stub|TODO("owner|R3 merge(` 및 ReaderActivity의 구형 pinned-chrome/returnStack/brightnessCollapsed 참조 0건.

## 검사 및 남은 일
최종 소스에서 tools/typecheck.sh 종료 0, tools/unittest.sh OK (1505 tests), 35.975초. CI 도구 Python 테스트 31개 통과.
검사 로그: rca-gate-typecheck.log / rca-gate-tests.log (실행 워크스페이스). 별도 출력 디렉터리에서 완주한 최종 검사이며, 이전 임시 검사 출력은 판정에 사용하지 않았다.
[screens] CI로 실제 Android 빌드·에뮬레이터 화면 검증이 필요하다. steps.txt CHECK FAIL과 실제 썸네일/밝기/선택 동작은 결과가 나온 뒤 확인할 것.
코멧 기기는 연결되지 않아 PLAN 5.4 성능/5.5 기기 점검 미실시. 모든 화면 갱신 횟수와 위치 고정은 실제 화면 검증이 남아 있다.

## 독립 리뷰 (2026-10-04)
독립 검증을 거친 리뷰 지적 20건을 모두 반영했다. 건너뛴 항목은 없다.

### 열기 (open-1~6)
- 노트 열기에서 첫 measure가 노트 위치에 고정된 generation을 만들지 않도록 했다. startOpen이 첫 viewport를 직접 잡는 동안(`openOwnsViewport`) onViewSizeChanged는 재배치하지 않는다(C16 자연 페이지 유지).
- 포커스 복귀 때의 주석 재로드는 afterOpen의 첫 로드 뒤에만 실행된다. 닫을 때 `annotationsLoadedAt = 0`으로 되돌려 첫 페이지 전 DB 읽기·backfill 쓰기를 막는다.
- afterOpen은 세션 단위가 아니라 열기 단위로 한 번 실행된다(`afterOpenPending`, 공용 `afterFirstPage`). 재파싱이 세션을 바꾸면 다시 건다.
- showPage/showScroll이 Boolean을 돌려준다. 렌더러가 실패하면 jump 소비, peek, intent strip, afterOpen 예약을 하지 않으므로 [다시 시도]가 노트로 연다. ScrollReader.draw가 본문을 그리지 못한 프레임은 첫 프레임으로 치지 않는다.
- closeCurrentBook에서 restoredPlace를 비운다. 다른 책의 place가 이 책의 노트 jump를 막지 않는다.
- goTo는 remember=false(TTS)일 때만 turnedInBackground를 세운다. 노트 허브에서 같은 책으로 jump해도 resume 때 전체 새로고침이 한 번 더 일어나지 않는다(wiring-note-jump-background-refresh와 같은 수정).

### 스크롤 (scroll-*)
- 섹션을 기다리는 동안 들어온 단계를 viewport 안에 쌓지 않는다(ScrollCommands 제거). 이 단계들은 turn에서 backlog로 가고, 기다리던 단계의 STEP settle이 같은 프레임 안에서 flush한다. 최대 10단계, EDGE면 edgeReached, 중간 NEED_SECTION이면 남은 단계를 backlog.restore(ScrollWiring.flushLeft, 테스트 추가)한다. 기다리는 단계를 멈추는 터치와 레이아웃 실패에서는 backlog를 비운다.
- flush의 중간 단계는 위치만 갱신하고, 마지막 화면에 대해 trackPage/cadence/save/bind/selection을 한 번만 실행한다(scrollBookkeeping).
- CONTEXT 배치(노트에서 열기, JUMP)의 anchor는 처음 절반 이상 보이는 줄이다. TOP 배치만 정확한 offset을 유지한다(테스트 추가).
- STEP 드래그 중 손가락이 닿아 있는 동안 userMoving()이 true다(`held`, ScrollInput.beginDrag). release/cancelDrag/stopMotion에서 해제한다.
- TTS goTo가 화면에 다 보이는 줄로 갈 때 ScrollReader.focusSection으로 그 구간을 가상 페이지로 삼는다. 다시 그리지 않는다.
- 움직이는 동안 fillStatus는 anchor 대신 실시간 top page를 쓴다.
- 키/탭 넘김은 사용자가 움직이고 있지 않을 때 남아 있는 scrollGesture/scrollCloseAtSettle 플래그를 지운다.

### 연결 (wiring-*)
- setHighlights("quotes")는 DB를 다시 읽지 않는다. QuoteCache에서 quoteRows를 바로 다시 만들고, 호출자의 낙관적 목록을 유지한다. reloadAnnotations는 시작 뒤에 인용이 바뀌면 인용 결과를 버리고, QuoteCache.put을 main에서 한다.
- K2 판정이 하나가 되도록 NotePlaceHost.quoteAnchorMatch(기본값 null)를 추가했다. ContentsDialog.anchorMatch는 리더가 배치한 모든 구간에 대해 이 검사를 쓴다. 지적의 대안안을 택한 것으로, 삽입 중인 인용은 아직 QuoteCache에 없어서 캐시만으로 다시 계산할 수 없기 때문이다.
- 돌아갈 위치 clamp는 실제 길이(배치된 구간)로만 offset을 자른다. EPUB 추정 길이 때문에 pin이 앞당겨지지 않는다.
- InstallState.ensure를 afterOpen 2단계에서 main 동기 호출로 바꿨다. 4단계 probe의 설정 쓰기보다 항상 먼저 실행된다.
- 읽기 설정 팝업의 화면 터치 사용자 지정 두 곳과 글꼴 관리 두 곳이 ReaderActivity.openAppSettings(page)를 거쳐 markOwnLaunch를 남긴다.
- 북마크 삽입 완료 시 사용자가 지운 임시 북마크(`removedTemps`)만 DB에서 삭제한다. 책을 닫았거나 바꿨으면 저장된 행을 그대로 둔다.

### 검사
tools/typecheck.sh 종료 0, tools/unittest.sh OK (1506 tests). 애니메이션 추가 없음, 첫 페이지 전 새 작업 없음. 실제 화면 갱신 횟수는 CI 화면과 코멧 실기기에서 확인해야 한다.

## CI 29 점검 결과
CI 29(에뮬레이터 720×1440, density 2, API 34, R8 release)의 FAIL CHECK를 독립 조사 5건의 진단과 대조해 코드로 확인한 뒤 반영했다.
앱 충돌 3건(18:36:20 43_44의 보기 대화상자, 18:36:53 46c, 18:48:54 95 복원 제안)은 모두 대화상자에 decor가 생기기 전 matchSystemBars가 부른 PhoneWindow.getInsetsController NPE로, 3f42e03에서 이미 고쳤다.
RAPerf 줄은 스크립트의 `setprop log.tag.RAPerf DEBUG`로 release에서도 나오므로 no_relayout·first_is 판정은 유효하다.

### 서재 서랍 (02, 42, 50b, 50c, 85a, 85_89)
- 원인: 서랍은 ScrollView이고 52 dp 행 12개(서가)가 720 dp 화면을 채운다. 휴지통 행이 y 1380에서 끝나므로 독서 노트·단어장·설정·읽기 기록·Wi-Fi로 책 받기·도서 스캔은 화면 아래에 있고, uiautomator는 보이지 않는 노드를 덤프에 넣지 않는다. 앱은 NOTES_SPEC §10.1대로 맞다.
- 02: 서랍을 `align "휴지통" 500`으로 올린 뒤 찍고 확인한다. "개수 없음"도 `row_count`로 확인한다.
- 42 도서 스캔, 50b 읽기 기록, 50c Wi-Fi로 책 받기, 85 독서 노트: 새 `drawer_tap`이 서랍을 끌어 올리며 행을 찾는다(scroll_find의 150..1250 창은 끝까지 올린 도서 스캔 y≈1260을 놓친다).
- 85a: 행 이름과 개수는 TextView 두 개라서 "독서 노트 2"라는 노드는 없다. `row_count`(이름 오른쪽 개수, 서랍 뒤 서재 개수 x 623은 제외)로 2/1을 확인한다. 80·84가 인용 2개·단어 1개를 만들어야 PASS이므로, 그 전에는 FAIL이 맞다.

### 설정 진입 (45_46, 50d, 51, 67, 90, 69b 복구)
- 원인: open_settings가 서랍의 설정 행(화면 밖)을 눌렀다. 서재 툴바 ⋮(마지막 "메뉴") → 설정으로 바꿨다. 같은 실행의 50_settings가 이 경로로 성공했다.
- 69b: 선택지는 "자동 (이 기기: …)"이므로 `pick_setting "스크롤 움직임" "자동"`으로 고쳤다. 기존 "기기에 맞춤"은 없는 항목이라 67의 손을 떼면 이동이 복구되지 않았다.

### 작가 서가로 이어진 연쇄 (68_69, 43_44, 46c, 70–74)
- 원인: 67이 서랍을 연 채 실패하고, 68의 show_chrome이 리더가 아닌 화면에서 360 720을 눌러 서랍의 작가 행을 눌렀다. LibraryActivity가 library.shelf로 저장하므로 이후 서재 시작이 모두 작가 서가였다(67 덤프는 모든 책, 68 덤프는 작가). 앱 동작(마지막 서가 복원)은 의도대로다.
- show_chrome은 ReaderActivity가 맨 위일 때만 화면 가운데를 누른다(`on_top`). restart_library는 `library_home`으로 모든 책 서가에서 시작한다. 68은 리더가 없으면 책을 다시 연다.
- 43_44·46c: 작가 서가에는 보기 토글이 없어 ⋮ → 보기 대화상자를 열었고, 거기서 충돌(3f42e03에서 수정)했다. 모든 책 서가에서는 툴바 토글을 쓴다. 46c는 샘플 책이 없으면 런처 좌표를 길게 누르지 않고 CHECK 46c FAIL로 끝낸다.
- 70–74: U1 준비 단계가 작가 서가에서 "샘플 EPUB"을 찾지 못해 책이 열리지 않았다(71–74의 same PASS는 서재끼리 비교). 준비 단계에서 library_home과 보기 전체를 맞추고, 책이 없으면 CHECK 70_setup FAIL을 남긴다. 76–78은 VIEW intent로 열어 PASS였다.

### 선택 막대 (17, 80_82, 83, 84)
- 원인: 선택 막대는 앱에서 유일한 비포커스 PopupWindow다(페이지와 핸들이 바깥 터치를 받아야 한다). 기본 `uiautomator dump`는 포커스 창만 덤프하므로 복사·인용·사전·번역이 보이지 않는다. 80 덤프에는 핸들 두 개만 있었다.
- `dump_all`(`uiautomator dump --windows`)과 `sel_tap`을 추가했다. `select_at`은 막대가 뜰 때까지 48 px씩 아래로 최대 5번 더 길게 누른다(빈 곳은 아무것도 선택하지 않는다).
- 17·80·82·84는 막대를 dump_all로 읽는다. 81 팔레트는 포커스 창이라 기본 덤프 그대로다. 82는 첫 인용의 y(SEL_Y)를 다시 누른다.
- 83: 80이 남긴 선택 때문에 show_chrome의 탭이 선택 해제로만 쓰였다. 시작할 때 남은 선택을 BACK으로 지운다.
- 84: PROCESS_TEXT 앱이 없으면 앱이 바로 웹 검색을 연다(TextActions.lookUp). 이 경우 단계를 실패로 끝내지 않고 로그만 남긴다.

### 13g 돌아가기 칩 번호 (앱 수정)
- 원인: 칩 "N 페이지로"는 globalPageOf(mark)로 번호를 만든다. BookSession은 섹션 4개만 배치해 두므로 두 번 먼 곳으로 탐색하면 섹션 1이 캐시에서 빠지고 글자 비율 추정을 쓴다. 3쪽(s:1 o:210)은 제1화 제목 쪽 바로 다음이라 추정이 0 = 2쪽이 된다(섹션 1은 약 3,300자에 7쪽 안팎, 첫 쪽은 210자). 같은 실행에서 13d의 띠는 섹션 1이 아직 캐시에 있어 3을 보였다.
- 수정: ReturnPageMemo(순수, 할당 없음)가 레이아웃 generation마다 돌아갈 위치의 정확한 쪽 번호를 그 섹션이 배치되어 있을 때 기억한다(4칸, 가장 오래 묻지 않은 칸부터 교체). ReturnNav는 onJump·useMark·useOther에서 이동 전에 출발 위치의 쪽을 물어 둔다. JVM 테스트 ReturnPageMemoTest 5개를 추가했다. 스크립트의 13g FAIL은 실제 칩 문구를 함께 남긴다.

### 스크롤 (61_footer, 64)
- 61_footer: 아래 띠는 움직이지 않지만 14c가 켠 쪽 번호와 진행 점은 위치를 따라 바뀐다(DIFF 164, 이전 실행 148; 글줄이 지나가면 수천 px). 1500 px 미만 차이는 띠 고정·값 변경으로 PASS, 그 이상은 FAIL로 판정한다.
- 64: 61–63이 이미 제2장 첫머리 근처까지 가므로 "제2장"은 화면에 보이는 곳으로의 이동이고, ReaderActivity.goTo는 이때 돌아갈 위치를 만들지 않는다(설계대로). 목차의 넷째 항목(제4장)으로 바꾸고 scroll SPEC 64행을 고쳤다. 65는 select_at을 쓰고 선택이 있을 때만 BACK한다(없으면 BACK이 책을 닫아 66이 서재에서 돈다).

### 57 (보류: 원인 미확인)
- 두 실행 모두 rows 360..1100에서 DIFF 3457이고 no_relayout은 PASS다. 막대·칩·머리말·꼬리말은 이 범위 밖이며, 실행 결과만으로는 무엇이 다른지 알 수 없다(스크린샷 아티팩트를 받을 수 없었다).
- raw_equal.py가 다른 픽셀의 범위(`DIFF n bbox x0,y0-x1,y1`)를 함께 출력하게 해 다음 실행의 CHECK 57이 위치를 알려 주게 했다.
- 같은 경로의 H4 누락은 고쳤다. GoToDialog만 create()+show()를 써서 matchSystemBars를 건너뛰었으므로, 전체 화면에서 대화상자가 떠 있는 동안 시스템 막대가 보였다. 이제 showNoAnim()으로 연다. 이것이 57의 차이 원인인지는 다음 CI에서 bbox로 확인해야 한다.

### 95_98 복원 제안, crash
- 95: pm clear 뒤 AutoRestorePrompt의 "이전 기록 복원" 대화상자를 만들다 18:48:54에 같은 NPE로 충돌했다(InstallState·AutoBackup·백업 감지는 동작). 3f42e03에서 수정했고 스크립트 변경은 없다. 96–98은 다음 CI에서 확인한다.
- crash FAIL은 위 충돌 3건이며 모두 3f42e03에서 고쳤다.

### 보류
- 57의 실제 원인(위 참조). 86–89(노트 허브 내용)와 90의 인용 무늬는 80·84가 PASS한 뒤 다시 본다.

### 검사
bash -n tools/ci/screenshots.sh 통과, CI 도구 Python 테스트 32개 통과, tools/typecheck.sh 종료 0, tools/unittest.sh OK (1511 tests). 애니메이션 추가 없음, 첫 페이지 전 새 작업 없음, 위치 이동 없음.
