# DA-C 상태 (R3 병렬 빌드 · 데이터 코어)

## 구현
- **N §5.1 쓰기 API** (`Library.kt`): `addQuote(style, place)`, `addBookmark(place)`, `updateQuoteStyle`(0..15 클램프), `setQuoteStyles`·`deleteQuotes`·`deleteBookmarks`·`clearReviews`(각 한 트랜잭션), `fillNotePlaces`(`frac < 0`인 행만, sig는 그대로), `setReview`(review_at = now, 빈 리뷰는 0), `setTrashed(false)`는 missing_at도 지움. 휴지통으로 보낼 때(`setTrashed(true)`, `trash`)도 missing_at = 0이라, 사용자가 버린 책을 스캐너가 되살리지 않음.
- **notesGen**: `internal fun notesChanged()`가 커밋 **후에** 올림(동기화된 ++). 노트·리뷰 쓰기, 삭제·휴지통 비우기, 스캐너 drop·trash·revive, `updateMeta`, writeFile·moveFile을 부르는 쪽(addOrUpdate, 스캐너 배치) 모두. `resetProgress`는 올리지 않음.
- **N §5.2 SQL** (`LibrarySql`/`BookRows`): INSERT_QUOTE 11개, INSERT_BOOKMARK 9개 자리표시자. 새 칸은 `IFNULL(?, 기본값)`이라, R2식으로 인수 6·7개만 바인딩해도(현재 Backup.kt) NOT NULL 오류가 없음. 그 밖에 UPDATE_QUOTE_STYLE, UPDATE_*_PLACE, SET_REVIEW(+review_at), CLEAR_REVIEW, RESTORE_REVIEW, SET_MISSING(`AND trashed = 0`), CLEAR_MISSING, UNTRASH, TRASH, SELECT_IDS_WITH_NOTES, USER_DATA(+lookups), MOVE_CANDIDATES(`trashed = 0 OR missing_at > 0`, 이동 경로에서 CLEAR_MISSING). BOOK_COLUMNS 끝에 missing_at(24칸)을 붙였고 이름으로 읽음. 인용문·북마크 select에 style·place를 넣었고 열 개수로 판별해 읽음.
- **N §5.5 스캐너**: `Known.missingAt`, `SyncPlan.trash/revive`. 사라진 책에 노트가 있으면 trash, 없으면 gone. missing인 책의 파일이 같은 경로에 다시 있거나 옮겨진 것이 확인되면 revive(re-point 포함). 사용자 휴지통은 건드리지 않음. noteIds는 사라진 책이 있을 때만 한 번 조회. 이동 판정은 살아 있는 항목이 missing 동명 항목보다 우선. `addOrUpdate`에서도 같은 경로로 돌아온 missing 책을 되살림.
- **C1**: `LibraryDb.onUpgrade` = CREATE_ALL → (CREATE_ALL 이후) PRAGMA로 ALTER 계산 → v3 미만이면 UPGRADE_SWEEP + `NOTES_SWEEP`(quotes·bookmarks 고아 행, N §4.1 "방어"). 순수 함수 `upgradeTail`로 테스트하고 소요 시간 로그를 남김. 문장 수는 상수이고, 10k 인용 + 3k 북마크 v2→v3가 6–8 ms(x86, python sqlite).
- **U §3.3**: `BookPrefs.returnMark/setReturnMark`(PK 읽기 / UPDATE→INSERT, 지우면 prune). `PRUNE_BOOK_PREFS`는 return_mark가 남은 행을 지우지 않음. `resetProgress`가 return_mark를 지움.
- **tools/check_sql.py**: Kotlin 소스에서 상수를 직접 파싱함. 새 v3 파일에서 모든 문장 prepare, v1→v3, v2→v3(시간 예산 50 ms), v3→"v2 빌드"→v3(중복 ALTER 없음, 고아 행만 정리), 3.18 문법 검사, EXPLAIN QUERY PLAN(*_created, *_memo, *_book, PK, TEMP B-TREE 없음)을 확인. `--dump`로 SqlDumpTest JSON(2,208문)을 prepare하고, dump `notes`의 expect/forbid 계획도 검사.
- `DataLimits` v3 상수를 줄마다 KDoc을 단 형식으로 정리. 새 파일 `NoteWrites.kt`(순수 규칙).

## 검사
- 모듈: `typecheck --own data` 통과, `unittest --own data` OK (197 tests). 파일 목록이 길면 출력 디렉터리 이름이 너무 길어져서, 이 컨테이너에서는 data/ 전체를 own으로 실행함(다른 data 파일은 base와 같음).
- 전체: `tools/typecheck.sh` 통과, `tools/unittest.sh` OK (1218 tests).
- `python3 tools/check_sql.py`(+ `--dump`) 전부 통과.
- 리뷰 2회(스펙 A, 버그 B). 실제 지적 사항은 모두 반영하고, 다른 레인 소관인 것은 contractRequests로 넘김.

## 남은 일
- DA-B: `Backup.restorePrefs`가 merge 결과가 null이면 `DELETE_BOOK_PREFS_OF_BOOK`를 실행해 return_mark를 지움 → `SET_PREFS_ROW(null,0,null)` + `PRUNE_BOOK_PREFS`로 바꿀 것. import 후 `Library.notesChanged()` 호출. reviewAt은 SELECT_ALL_BOOKS_FOR_BACKUP의 index 25, 복원은 RESTORE_REVIEW.
- DA-N: Lookups 쓰기 후 `Library.notesChanged()`. NotesSql 샘플(탭 × 순서 × 필터)을 SqlDumpTest의 `notes` 키에 넣을 것(expect/forbid를 넣으면 check_sql.py가 계획까지 검사). 병합 후 SqlDumpTest에 추가.
- 기기: 첫 실행 업그레이드 로그(`LibraryDb` "upgrade v2 → v3 … ms")가 50 ms 이하인지 확인.
