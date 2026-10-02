# 1차 리뷰 22건 검증 (2026-10-02)

기준: `10a314e9033ae6e9cfe81be26e2f8040354bab26`. JSON의 배열 순서가 아래 번호다.
10건은 이미 기준 커밋에 반영돼 있었다. 같은 코드를 다시 변경하지 않고 구현과 기존 회귀 테스트를 확인했다.
나머지 12건은 이번 변경으로 처리했다. 잘못된 지적으로 기각한 항목은 없다.

| 번호 | 판단 | 확인·처리 근거 |
|---|---|---|
| 1 | 이미 수정 | `ReadingTracker.resume(now, today)`와 자정 이후 재개 테스트. |
| 2 | 이미 수정 | `flush()`가 `hasPage`를 해제하고 `closeEndPanel()`이 다시 등록. `flushClosesThePageAndStopsCountingIt` 확인. |
| 3 | 이미 수정 | `BackupData.txtParseVersion`, `BackupJson.remapsTextPosition`, `Backup.markTextPositions`. 구버전 백업의 비율 복원 테스트. |
| 4 | 이미 수정 | `LayoutKeys.pageCountKey`에 형식별 `pv` 포함. `keyCarriesTheFormatsParseVersion` 확인. |
| 5 | 이미 수정 | `syncReaderSettings`의 `inFront` 조건. 설정 화면에서 여러 번 저장해도 복귀 때 최종값 한 번 적용. |
| 6 | 이미 수정 | `turnedInBackground`는 `ReaderHost` 탐색 경로만 설정. 설정 재조판으로 복귀 시 새로고침하지 않음. |
| 7 | 이미 수정 | `endPanel` nullable, `endPanel()`은 마지막 페이지의 끝 화면을 처음 보여 줄 때만 생성. |
| 8 | 이미 수정 | `charsLeft(false)`가 `BookSession.charsLeftInChapter` 사용. 목차 없는 책은 여전히 null. |
| 9 | 수정 | 일시적인 오디오 포커스 손실 전에 `pausedByFocus` 설정. `TtsState.holdForeground`가 유휴 종료를 막고 웨이크락은 해제. 사용자 일시정지·영구 손실은 일반 종료 정책 유지. |
| 10 | 문서 수정 | 기존 Handler 정책을 유지. 10분은 깨어 있는 시간 기준이고 깊은 절전 중에는 콜백이 늦어진다는 점을 클래스·타이머 설명에 명시. 포커스 대기는 9번의 별도 정책. |
| 11 | 수정 | 여러 권 선택 라벨은 셀 너비 전체를 사용. 끝 화면·규칙 버튼·스타일 라벨·프리셋·내 스타일에 자동 크기 조절 적용. 프리셋 너비는 선택 여부와 무관하게 고정. |
| 12 | 수정 | `showModeButton()`의 길게 누르기가 현재 `listMode`를 읽음. |
| 13 | 수정 | 접속 코드 뒤 조사를 없애 자연스러운 설명으로 변경. |
| 14 | 수정 | 정보·브라우저 설명 모두 실제 화면 이름 ‘Wi-Fi로 책 받기’ 사용. |
| 15 | 수정 | 목차 머리글과 버튼 터치 높이 40dp, 빠진 화·중복 행도 40dp. |
| 16 | 수정 | 두 TTS 화면이 `ReaderFormat.ttsRate/ttsPitch`를 공유. 터치 칸의 화 이동·없음은 `KeyActions.label` 사용. 기존 설정 포맷 테스트 유지. |
| 17 | 수정 | 메뉴·확인창을 ‘읽은 위치 초기화’로 변경하고 순환 화살표 아이콘 사용. 독서 통계는 유지됨. |
| 18 | 수정 | 제목 필터 중 BACK의 DOWN/UP을 소비하고 UP에서 필터 해제. 다음 BACK은 기존처럼 목차를 닫음. 페이지 키 동작도 유지. |
| 19 | 수정 | 통계에서 책 객체를 전달하고 휴지통 여부 확인 후 열기. 지난 독서 시간은 집계에 유지. |
| 20 | 수정 | 문구 삭제 안내를 줄 전체·문구만 두 선택지에 맞게 변경. |
| 21 | 이미 수정 | 복원 로그에 `ReadingLog.MAX_ADD_*` 상한, `speed()` 포화 처리. `hugeLogCountsAreCappedLikeOneAdd`, `speedSaturatesInsteadOfWrapping` 확인. |
| 22 | 이미 수정 | 헤더는 짧은 소켓 읽기와 전체 10초 기한을 함께 사용. 본문만 긴 읽기 제한 적용. `aTrickledHeadIsCutOffAtItsDeadline` 확인. |

검사 도구도 보완했다. 컴파일 실패를 `|| true`로 숨기던 경로를 제거하고, 테스트 목록은 임시 파일로 읽어
`/dev/fd`가 없는 환경에서도 전체 테스트를 실행한다. 누락된 컴파일러에 대해 typecheck가 127로 실패하는 것도 확인했다.

첫 페이지 이전에 새 작업을 추가하지 않았다. 이번 UI·TTS 수정은 해당 기능을 사용할 때만 실행된다.
실제 기기의 큰 글자 표시와 e-ink 체감 속도는 APK 설치 후 확인이 필요하다.

최종 로컬 검사: `tools/typecheck.sh` 오류 0건, `tools/unittest.sh` 1,064개 전부 통과
(기존 1,045개와 새 TTS/H1 회귀 19개). 수정 diff를 재검토했고 shell 문법·공백 검사도 통과했다.
