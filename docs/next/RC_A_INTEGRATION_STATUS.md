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
