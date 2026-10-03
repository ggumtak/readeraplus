# 다음 작업 (2026-10-03 인계)

브랜치: `ccr-0ef3b80a-k64gx2` (HEAD 0ca06d1 이후). 계획 원문: `wave2/PLAN.md`, 계약: `../R3_INTERFACES.md`,
레인 결과·연결 메모: `lanes/*.result.json`, 레인별 상태: `*_STATUS.md`, 진행 기록: `README.md` 맨 위.
로컬 검사: `/opt/tc`에 kotlinc 2.1.0 + android-all 15 jar 설치 후 `tools/typecheck.sh`, `tools/unittest.sh`
(설치 명령은 `lanes/BRIEF.md` §0).

## 현재 상태
- W1 레인 11개 + CI + W2(새 파일) 모두 이 브랜치에 병합 완료. 전체 typecheck 성공, JVM 1,475개 통과(9a8ac14).
- RC-A(ReaderActivity 통합)만 미병합. 세 파트(RCA-U/S/N)는 브랜치 `ccr-0ef3b80a-k64gx2-integ-rca`(e6241ba)에서
  충돌 해결까지 병합됨(R3 merge 표시·reader 스텁 0개). **컴파일·테스트·리뷰는 아직 안 함.** 이 브랜치는 68d1874
  기준이라 DA-N·DA-B·EX-S 병합 전 상태다.

## 해야 할 일 (순서대로)
1. `ccr-0ef3b80a-k64gx2-integ-rca`를 `ccr-0ef3b80a-k64gx2`에 병합(겹치는 파일 거의 없음). 전체 typecheck·unittest 통과시키기.
2. RC-A 병합 결과 검토: PLAN §1.6.1(startOpen)·§1.6.2(생명주기 순서)와 줄 단위 비교, S §1.10 위임 grep(scroll/SPEC.md),
   `grep -n 'pinChrome\|applyPinnedArea\|returnStack\|PREF_BRIGHTNESS_COLLAPSED' reader/ReaderActivity.kt` 결과 없음,
   `grep -rn 'R3 stub\|TODO("owner\|R3 merge(' app/src/main/java` 결과 없음.
3. 레인 연결 메모 중 RC-A 몫 반영 확인(`lanes/*.result.json`의 rcaNotes): 특히
   - EX-S: `NotePlaceHost` 구현, `sameDecor`가 `Highlight.style` 비교, `reloadAnnotations`가 `q.style` 전달,
     스크롤 모드에서 `selection.startAt` 전에 `scroll.focusAt(y)`, settle에서 `selection.onPageChanged()`.
   - EX-N: `PageJumpHost` 구현, 창 포커스 때 `reloadAnnotations`, 인용 표시 규칙 = `QuoteRows.placeChanged`.
   - W2: 목차 4번째 탭 "썸네일", `PageThumbsHost`(decorFor·paintVersion·구간별 decorVersion·cancelThumbs 재prefetch),
     trim/닫기 때 `thumbs?.clear()`, ⋮ "페이지 썸네일"(페이지 이동 다음, 스크롤 모드에서 숨김).
   - RU-L: `light.markOwnLaunch()`(설정 열기 직전), 밝기 드래그 콜백 → `light.onDrag`, `bindChrome` → `light.bind()`.
   - RU-C: 복원한 돌아갈 위치 DocPosition을 호스트에서 범위 clamp.
   - CI: 스크롤 settle 경로에서도 DEBUG `RAPerf show <kind> s: o: a: g: Nms` 한 줄.
4. 남은 소규모 계약 보완
   - QuoteSwatch가 정사각형 sizeDp 대신 측정 크기대로 그리기(SET의 22×14dp 견본 줄).
   - 목차에서 위치가 바뀐 인용을 노트 점프 경로로 여는 호스트 기능(RCA-N이 `ReaderActivity.openNote(jump)` 추가; 계약 인터페이스 필요).
   - EX-N `refreshQuoteHighlights`가 EX-S의 `QuoteHighlights.forSection`·`QuoteExport` 재사용(중복 제거).
5. `[screens]`를 커밋 메시지에 넣어 푸시 → CI 빌드 + 에뮬레이터 스크린샷(PLAN §5.3). `steps.txt`의 `CHECK … FAIL` 항목 수정.
6. Comet 기기 확인: PLAN §5.4 성능 기준, §5.5 기기 점검(위치 고정, 최근 앱 복귀, 밝기, 스크롤 STEP, 노트·서재).

## 지켜야 할 사용자 지시
- 페이지 넘김 애니메이션 없음(모든 모드·기기에서 즉시 교체).
- 첫 페이지 전에 새 IO·탐색·백업 없음, 동작당 e-ink 갱신 1회, 표시/숨김·설정 변경에 읽던 위치 고정.
