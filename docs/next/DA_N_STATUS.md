# DA-N 노트 읽기 측 구현 결과 (2026-10-03)

노트 허브의 읽기 측(SQL·페이지 키·캐시·검색 키 스캔), 단어장 기록, 내보내기/공유, 디버그 시더를 구현했다.

- `NotesSql`(순수): 모든 arm이 `k, id, b, t, s, o`를 별칭으로 가진다. 탭별 arm(MEMOS = QM+MM, WORDS = L/L1),
  단일 arm은 `ORDER BY t DESC, id DESC`(k 없음), 다중 arm은 `t DESC, k, id DESC`, OLDEST는 반대.
  페이지 p>0은 `LIMIT 51 OFFSET 50p−1`. 책 순서 페이지는 `SELECT * FROM (…) ORDER BY CASE b …` 한 문장.
  상세는 `substr(…, 1, 601/401)` PK 조회. 카운트는 스칼라 서브쿼리 한 문장. 각 빌더 KDoc에 `PLAN:` 줄을 적었다.
- `NotesKeys`(순수): 검색 중에는 키 스캔 한 번(k,id,b,t,s,o,n,st)으로 카운트·색 카운트·두 날짜 순서·책 목록·책 순서를 메모리에서 만든다.
- `Notes`: 모든 캐시는 `generation()`(= `Library.notesGen` + 단어장 쓰기 카운터)로 무효화. 고아 행은 LEFT JOIN으로 "(삭제된 책)".
  `firstOfBook`은 직전 행과 책이 다를 때 true(책 순서만). `BookSpans`, `NotesPaging`(prefetch) 순수 객체.
  추가 API: `generation()`, `share(refs, q, now): NotesShare`(TXT, 50,000자 상한, 항목 경계에서 자르고 "…(나머지 N개는 '내보내기'로 저장하세요)").
- `Lookups`: 같은 책+word_key+section+start 10분 이내면 기존 행 갱신, 아니면 INSERT(한 트랜잭션). 없는 책·빈 단어·`recordLookups` 꺼짐 → −1, 예외 없음.
  `LookupWords.key`: NFC, 둘레 문장부호/괄호 제거, 공백 정리, ASCII 소문자, 100자.
- `NotesExport`: hub.md §10.1/§10.2 형식 그대로, 책 단위 스트리밍, 2색 이상일 때만 색 태그, 휴지통/파일 없음 표시,
  이스케이프 추가(`~ = $ % &`, 줄 시작 `- + N. N)`, 앞 공백 제거, U+FFFC·제어문자·줄바꿈 정규화, 제목·저자·장·메모·단어·앱 모두).
- `DebugSeed`: DEBUG 전용 리시버 `DEBUG_SEED_NOTES`(`--ei n`, `--ez clear`, `--ez bench`). 별도 시드 책(휴지통+파일 없음)에 넣고 §5.8 항목을 `RANotes`로 측정.

결정/차이:
- wordsOnce면 ALL 탭에도 L1을 쓴다(전체 카운트와 목록 일치).
- L1 책 순서 페이지는 단어를 전체 책에서 먼저 묶은 뒤 페이지의 책으로 거른다(실제 SQLite 검증에서 발견한 불일치 수정).
- 색 필터는 인용문 탭에서만 적용, 카운트의 인용문 수도 그때만 좁힌다.
- `Library.notesGen`은 private set이라 단어장 쓰기는 자체 카운터를 올린다. 각 쓰기 지점에 `// R3 merge(DA-C): Library.notesChanged()` 주석.
- 헤더 카운트 줄은 0인 종류도 모두 표기(예시와 동일). 퍼센트는 내림.

검사:
- 모듈 모드 typecheck/unittest: 성공, 새 테스트 44개 통과.
- 전체 `tools/typecheck.sh` 성공, `tools/unittest.sh` **1,239개 전부 통과**.
- Python sqlite3 3.45로 2,300여 문장을 1만 노트 픽스처에 실행: 오류 없음, 단일 arm `*_created`/메모 `*_memo` 계획,
  카운트 TEMP B-TREE 없음, SQL 순서·카운트·책 목록·책 페이지가 메모리 계산과 384건 일치.
- 독립 리뷰어 2명 라운드는 리드의 마감 지시로 생략했다.

남은 일: 병합 시 `Library.notesChanged()` 호출 추가, 기기에서 `DEBUG_SEED_NOTES`로 §5.8 예산 측정, `tools/check_sql.py`에 PLAN 단언 반영.
