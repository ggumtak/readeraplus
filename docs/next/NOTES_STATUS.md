# NOTES 독서 노트 화면 구현 결과 (2026-10-03)

`ui/notes/`에 N §9(hub.md §8–§9 채택분)의 독서 노트 화면을 구현했다. 데이터는 `data/Notes`·`Lookups`·`NotesExport`와
`data/Library` 쓰기만 frozen 시그니처대로 호출한다. 책 파일은 열지 않는다(열기 전 `File.isFile` 확인만 IO에서 한다).

- 파일: `NotesActivity`(화면·상태·로드·선택·내보내기), `NotesAdapter`(창 어댑터, 종류별 부분을 숨기는 단일 holder),
  `NotesMenus`(행 메뉴·전체 보기·편집기·삭제 확인·책/정렬/색 선택·overflow·공유·형식 선택), `NotesText`(순수 라벨),
  `NotesWindow`(순수 창 계산). companion(EXTRA_TAB, EXTRA_BOOK_ID, open)은 그대로 두었다.
- 화면: 툴바 56 dp, 탭 6개(전체·인용문·메모·북마크·리뷰·단어; `NotesTab` 순서), 칩 행(모든 책 ▾ / 《제목》 ✕, 정렬,
  인용문 탭에서만 색), 검색 행(300 ms debounce, 🔍 두 번째 탭은 닫고 지움), 날짜/책 머리글, 종류별 행, 빈 상태 §9.6 문구.
- 목록: `InkListView` + `ListPager`(rowsPerPage 0, " · N개"). 스크롤 모드는 bar GONE, fast scroller 없음.
  - 50행 페이지, LRU 6페이지, 64 dp "불러오는 중…". 보이는 페이지일 때만 notify하고, ±25행을 미리 읽는다.
  - 먼 이동(쪽 번호)은 대상 페이지(필요하면 다음 페이지까지)를 받은 뒤 이동한다. 최대 250 ms를 기다린다.
  - 처음 열 때는 counts와 첫 페이지가 올 때까지 첫 draw를 최대 250 ms 잡아 화면 갱신 1회로 연다.
  - reload 위치가 페이지 끝 근처이면 다음 페이지도 같은 IO 작업에서 읽는다(placeholder로 2회 갱신하지 않음).
  - 페이지 로드는 화면에 보이는 행의 query(`windowQ`)로만 요청한다. reload 실패 시 이전 query로 되돌린다.
- 선택: `HashSet<Long>`(NoteRef.packed). 20,000개 초과면 saved state에 query("모두 선택")로 저장한다.
  공유·내보내기·색 바꾸기(인용문 선택 시)·삭제·⋮(모두 선택/선택 해제). 일괄 삭제 후 선택 종료는 reload와 한 번에 그린다.
- 상태: raw pref `notes.tab`/`notes.order`/`notes.wordsOnce`. saved state에 탭·정렬·책·검색어·색·첫 행·선택·대기 중 내보내기를 저장한다.
  intent extra가 저장된 탭보다 우선한다. onResume은 `notesGen`이 바뀐 경우에만 reload한다.
- 내보내기: 형식 선택 → `ACTION_CREATE_DOCUMENT`(format.mime). 시작 실패 시에만 octet-stream으로 재시도한다
  (API 30+ package visibility 때문에 resolveActivity 사전 확인은 쓰지 않음). "w" 다음 "wt"로 열고 UTF-8로 쓴다.
  작업은 `ReaderIo`(앱 범위)에서 돈다. 진행 중에는 page bar에 "내보내는 중…"을 표시한다.
- 공유: TXT export를 50,000자에서 항목 경계로 자르고 "노트가 많아 앞부분만 공유합니다"를 표시한다.
- 성능 로그: `adb shell setprop log.tag.RANotes DEBUG`. open(첫 행까지, counts/books/page 분리), reload, page, export 시간을 남긴다.

결정/차이:

- 전체 보기는 측정 전이라 `bodyCut`, 줄바꿈 4개 이상, 160자 초과일 때 보인다.
- 모든 기록 보기는 묶음 상태이고 `wordCount > 1`일 때만 보인다.
- 스펙에 없는 문구: 접근성 설명 "검색"·"더보기"·"선택 끝내기"·"지우기", 쪽 번호 오류 "1~N쪽 사이로 입력하세요".
- `QuotePalette.show`가 null을 돌려주면(EX-S 미병합 등) 스타일 라벨 chooser "색"으로 대신한다.

검사:

- `tools/typecheck.sh --own ui/notes`, `tools/unittest.sh --own ui/notes`: 성공, **22개 통과**(NotesTextTest 11, NotesWindowTest 11).
- 전체 `tools/typecheck.sh`: 성공.
- 전체 `tools/unittest.sh`: 1,217개 중 `TxtPerfTest.cleanupPacksLineStage` 1개만 실패했다.
  동시 컴파일 부하 때문인 성능 시간 초과로 보며, 이 lane과 무관하다. 단독 재실행에서는 OK(3개).
- 리뷰 2회(완성도·버그)를 했다. 지적된 6건(MIME 사전 확인, 시간 분리, 빈 상태 갱신, 진행 중 query 혼합,
  페이지 경계 placeholder, 삭제 이중 갱신)을 모두 고쳤다.

남은 것: DA-N/DA-C/EX-S 병합 후의 실제 데이터 확인, CI 85–89 스크린샷, Comet 기기에서 N §9.11 예산(RANotes) 측정.
