# DA-B 백업 구현 결과 (2026-10-03)

백업 v3 필드와 병합, 복귀 표시(returnMark), 자동 백업, 설치 상태를 구현했다. 호출부(리더 onStart/onStop, 서재 유휴 대기와
복원 제안, 설정의 "자동 백업" 구역)는 RC-A · LIB · SET에서 연결한다.

- **BackupJson:** 인용문 style(0이 아닐 때만)·자리(chapter, frac, sig; frac ≥ 0일 때만), 북마크 자리, 책별 reviewAt ·
  missingAt · lookups, prefs.returnMark를 양방향으로 읽고 쓴다. 모두 선택 필드이고 VERSION은 1 그대로다.
  머리글 `origin` · `summary`를 `createdAt` 바로 뒤, books 앞에 쓴다(C10). `readHeader`는 android.util.JsonReader로
  머리글만 읽고 books에서 멈춘다. summary가 없는 옛 파일은 트리 없이 books를 흘려 보내며 센다.
  `write`는 책 하나씩 스트리밍하며 `toJson(data).toString()`과 같은 글자를 만든다.
- **BackupMerge (새, 순수):** N §5.6의 규칙 표를 그대로 구현했다. 위치는 더 최근 쪽이 이긴다. 기기가 더 최근이면
  trashed · to_read · have_read · encoding을 유지한다. favorite은 OR이고, 리뷰는 reviewAt으로 비교한다.
  인용문은 section:start:end로 맞추고, 그다음 같은 글이면서 sig가 다른 것을 같은 인용문으로 본다.
  style은 0에만, 자리는 frac < 0에만, 메모는 빈 것에만 채운다. 단어는 (word_key, section, start, createdAt)로 중복을 막는다.
  이 기기에 파일이 없고 노트가 있는 책은 자리표시 행(휴지통, missing)으로 만든다. 복원은 아무것도 지우지 않는다.
- **BackupSql (새):** 백업 전용 SQL이다. v3 열을 모두 읽고 쓰며 LibrarySql의 열 목록 변경과 무관하다.
- **Backup:** `snapshot`이 질의마다 busy()를 확인한다(책, 북마크, 인용문, 컬렉션, 기록, prefs, 단어, 설정).
  raw prefs는 정렬 순서로 넣는다. 수동 내보내기도 origin(auto=false)과 summary를 가진 스트리밍 파일이다.
  가져오기는 경로를 먼저 모두 맞춘 뒤 이름+크기로 맞춘다(두 단계). 끝에 복원 제안을 정리한다(settleOffer).
- **AutoBackup:** 관문 순서는 사용 설정 → verify → isDue → 저장 위치다. 저장은 File(.tmp → sync → rename) 또는
  MediaStore(IS_PENDING)로 원자적으로 하고, 실패하면 tmp나 행을 지운다. 해시와 쓰기 모두 256 KB마다 busy()를 확인한다.
  해시가 같으면 UNCHANGED, 비었으면 SKIPPED_EMPTY다. 쓰기에 성공한 뒤에만 회전하며, 방금 쓴 파일은 지우지 않는다.
  그 밖에 schedule/cancelScheduled(단일 실행), findCandidates(다른 설치 + Download/Documents 수동 내보내기,
  이름 시각 순, 머리글 5개, 64 MB 제한), restore, deleteFiles, lastWrittenAt, "다운로드/ReaderaPlus/backup"을 구현했다.
- **InstallState:** ensure(main, apply), verify(firstInstallTime이 다르면 새 id, 제안 상태는 그대로), installId/id8,
  offerPending(모르면 pending), settleOffer(commit)를 구현했다.

결정/차이:

- S §3.2의 `snapshotJson`(JSONObject 트리 + `toString(1)`) 대신 모델 스냅샷과 스트리밍 직렬화를 쓴다(K11:
  10–20 MB 트리를 만들지 않는다). 출력은 들여쓰기 없는 JSON이다. 해시는 createdAt=0, origin 없이 스트리밍한 바이트의 SHA-1이다.
- SKIPPED_EMPTY에서 wouldEmpty일 때만 checkedAt을 기록한다. isBlank(새 설치)는 다음 트리거에 다시 본다.
- 같은 분에 MediaStore로 두 번 쓰면 이전 같은 이름 행을 지우고 새로 쓴다("(1)" 이름 방지).
- 복귀 표시는 기기가 더 최근에 읽었고 자기 표시가 있으면 기기 것을 유지한다.
- `findCandidates(context, includeOwn)` 오버로드(설정의 "자동 백업에서 복원"용, S §3.8)와 지울 파일 수를 세는 `countFiles(context, others)`를 추가했다.

검사:

- `tools/typecheck.sh`(모듈 · 전체): 성공.
- `tools/unittest.sh --own data`: 214개 통과. 전체 `tools/unittest.sh`: **1,235개 전부 통과**.
  모듈 모드는 파일 목록이 길어 출력 경로 이름이 너무 길어지므로 `--own data`로 실행했다(이 컨테이너에서 data의 다른 파일은 기준과 같다).
- 새 테스트: BackupMergeTest 14, AutoBackupPolicyTest 10, InstallStateTest 4, BookPrefsBackupTest 4, BackupJsonTest +9.
  BackupR2Test는 그대로 통과했다.
- 검토 두 번(명세 · 버그)을 했고 지적을 모두 고쳤다(경로 우선 두 단계 매칭, 자리표시 id, 회전, MediaStore 이름, deleteFiles의 verify,
  같은 이름 수동 내보내기).

남은 일:

- DA-C 병합 후: `Backup.import`의 `// R3 merge(DA-C)` 자리에서 `Library.notesChanged()`를 호출한다. resetProgress에서
  return_mark를 NULL로 만드는 일과 그 테스트는 DA-C 몫이다.
- `LookupWords.key`(DA-N)가 아직 stub이라, 이 컨테이너에서는 단어가 든 백업의 복원이 예외를 낸다. DA-N 병합 후 해결된다.
- 기기 검사: 실제 파일/MediaStore 쓰기, 1,000권 백업 뒤 PSS, 리더 복귀 시 BUSY 로그, S §3.9 스크린샷 95–98.
