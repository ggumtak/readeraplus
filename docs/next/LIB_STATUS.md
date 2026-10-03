# LIB 서재 레인 구현 결과 (2026-10-03)

서재의 네 가지 보기, 쪽 단위 넘기기, 독서 노트 진입점, 자동 백업 트리거와 복원 제안, polish 14를 구현했다.

- **onCreate 순서 (C35):** `InstallState.ensure` → `startMode` → RESUME/OPEN_LAST는 `startOpenLast`, 아니면 `ensureUi`.
  첫 목록이 그려진 뒤(pre-draw에서 post) `ReaderIo`로 `DeviceLight.restoreIfStale`, 이어서 `cached == null`이면 `DeviceClass.probeAsync`.
- **refreshVisible:** 페이징 결정 → 복원 제안 확인(접근 권한 + `offerPending`이면 스캔 보류, 새로 허용된 스캔 포함) → 스캔 예약 → 자동 백업 유휴 대기 → 상태 줄 → 목록.
- **자동 백업 트리거 1:** `isDue`이면 창 포커스·무입력 10초 대기(자동 스캔과 같은 재시작 방식). 발화하면 `AutoBackup.schedule(ctx, 0)`로 백업 스레드에서
  `runNow`를 실행한다. busy()는 시작 뒤 터치/키, 화면 이탈, 리더가 앞에 있음, 스캔 중을 알린다. 화면을 떠나면 대기가 취소된다.
  이 설치의 첫 기록 뒤 상태 줄에 한 번만 "자동 백업을 다운로드/ReaderaPlus/backup에 저장했습니다 · 설정 → 백업 및 복원에서 끌 수 있습니다"를 보인다. 실제로 표시될 때 기록한다.
- **AutoRestorePrompt (새 파일):** IO에서 후보와 `pickDefault`를 구한다. 내용 있는 후보가 없으면 `settleOffer` 후 보류한 스캔을 시작한다.
  다이얼로그는 "이전 기록 복원", 취소 불가, 애니메이션 없음이다. 버튼은 [새로 시작] / [다른 백업 보기](후보 2개 이상) / [복원]이다.
  [복원]은 "복원하는 중…" → `AutoBackup.restore` + 빈 commit → "책 N권의 기록을 복원했습니다" → 첫 스캔 → `recreate()` 순이다.
  이 설치에 읽은 기록이 있으면 늦은 응답 줄을 덧붙인다. API < 30의 권한 허용 콜백도 제안이 남아 있으면 스캔을 미룬다.
- **네 가지 보기:** 전체(카드), 요약(48×68 표지, 2줄 제목, `compactMeta`, ⋮ 전체 높이, 행 높이 쪽 단위 80 / 스크롤 88 dp),
  썸네일/그리드는 GridView 하나를 함께 쓴다(`LibraryGridMath`: 열, 셀 높이 184/138, 제목 줄 수 고정, 셀 높이 정확).
  보기 전환은 이미 읽은 행을 다시 묶는다. 쿼리 0, 표지 생성 0이고 첫 번째로 보이는 책을 유지한다.
  표지는 모든 보기에서 정규 크기(96×136 dp − 2) 하나만 쓴다. 다중 선택 표시는 2 dp 테두리 + 20 dp 체크 상자다.
- **목록 넘기기:** `ListPaging.paged(listPaging, DeviceClass.cached)`. 쪽 단위에서는 fast scroller 없음(C2), `InkPagerBar` + `ListPager`, 숫자 패드 "쪽 번호".
  전체/요약은 측정 기반(잘린 행이 다음 쪽 첫 행), 썸네일/그리드는 높이에 맞춘 고정 행이다.
  쪽이 보인 뒤 다음 쪽 표지를 미리 읽는다(`CoverLoader.prefetch`, 화면 요청 뒤에 대기). 키 넘김도 pager를 쓴다.
- **polish 14:** 카드 padding (10,10,6,10), 테두리 없음, LINE_LIGHT 1 px 구분선(양쪽 8 dp 안쪽), 눌림은 PRESSED 채움, 표지 1 px 테두리 유지,
  목록 paddingEnd 12 dp. e-ink에서는 CardButton 배경(눌림 상태) 없음.
- **독서 노트(N §10.1):** 서랍에서 휴지통 뒤에 독서 노트 · 단어장을 두고, 개수는 기존 지연 IO 작업에서 `notesGen` 캐시로 읽는다.
  책 메뉴에는 책 정보 다음에 "독서 노트"를 둔다. 영구 삭제/휴지통 비우기는 노트 수를 먼저 읽는다. 노트가 있으면 경고 문구와 [독서 노트] 버튼을 보이고,
  개수 읽기에 실패하면 경고 없이 묻는다. 휴지통 행은 "파일 없음"이다.
- InkTouch: `ListPager.bindBar()`(막대 하나를 두 목록이 공유), 측정 쪽의 step = 완전히 보이는 행 수, 막대가 숨었을 때는 onScroll 갱신 생략.

결정/차이:
- 휴지통 책 메뉴는 기존 "책 정보"를 유지한다: 복원 · 책 정보 · 독서 노트 · 영구 삭제. CI는 "책 정보"를 찾는다.
- 후보 검색이 IO 오류로 실패하면 제안을 확정하지 않고 다음 방문에 다시 묻는다.
- 상태 줄은 쪽 단위에서도 목록 아래 padding을 확보한다. 가려진 행을 본 것으로 세지 않기 위해서다. 2줄 백업 안내 때는 56 dp를 확보한다.
- 그리드/그리드 아이콘은 새 drawable이 없어 둘 다 ic_grid_view, 전체는 ic_article을 쓴다.

검사:
- `tools/typecheck.sh --own ui/library --own ui/kit/InkTouch.kt --own ui/kit/InkTouchTest.kt`: 성공. 모듈 `unittest`: 72개 통과.
- 전체 `tools/typecheck.sh`: 성공. 전체 `tools/unittest.sh`: **1,208개 전부 통과**(새 LibraryGridMathTest 7개, LibraryTextTest +6개).
- 리뷰 2회(스펙·버그)의 지적을 모두 반영했다: 썸네일↔그리드 전환 셀 재사용, 백업 single-flight, 쪽 단위에서 상태 줄에 가려진 행,
  안내 누락, 날짜 문구 갱신, onPause 레이아웃, 그리드 첫 맞춤 이중 그리기, API<30 스캔 보류. 리뷰 뒤 수정은 재리뷰하지 않았다.

남은 일: RC-A 연결은 필요 없다(서재 단독). `AutoBackup`/`InstallState`(DA-C), `Notes`(DA-N), `DeviceLight`(RU)의 실제 구현이 병합되면 동작한다.
CI 41–46 단계 및 Comet에서 쪽 넘김 한 번에 e-ink 한 번 갱신, 콜드 스타트 +5% 이내를 기기에서 확인해야 한다.
