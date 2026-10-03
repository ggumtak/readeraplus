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
