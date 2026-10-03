> 2026-10-04: RC-A 통합 및 썸네일·노트·복원 연결 보완. 현재 인계는 [HANDOFF_NEXT.md](HANDOFF_NEXT.md), 변경/검사는 [RC_A_INTEGRATION_STATUS.md](RC_A_INTEGRATION_STATUS.md). CI 화면·코멧 실기기 확인은 남아 있음.

# 다음 작업 인수인계 (2026-10-02)

## 2026-10-03 병렬 진행 (빌드 25 이후)
- 새 컨테이너에서 로컬 도구(kotlinc 2.1, android-all 15)를 다시 설치. 직전 세션의 미커밋 RU 작업은 유실되어 처음부터 다시 했다.
- 남은 W1 11개 레인 + RC-A 3개 파트 + CI + W2를 각각 별도 클라우드 세션·브랜치(`ccr-0ef3b80a-k64gx2-lane-*`)에서
  동시에 구현하고, 각 세션이 리뷰어 2명으로 자체 검증한 뒤 이 브랜치로 squash 병합했다. 레인 정의: `lanes/*.md`,
  레인 결과(연결 메모·계약 요청·편차): `lanes/*.result.json`, 상태: `*_STATUS.md`.
- 병합 완료(W1 전부): RU-C(메뉴 화면·돌아갈 위치·상태 모델), RU-L(밝기), EX-P(읽기 설정 팝업), EX-S(선택·인용 색),
  EX-N(목차의 인용 색·칩·노트 링크), DA-C(노트 쓰기·스캔 안전·v3 sweep·check_sql.py), DA-B(백업 v3·자동 백업·설치 표식),
  DA-N(노트 조회·단어장·내보내기·디버그 시드), LIB(서재 4보기·쪽 넘김·자동 복원 제안), SET(설정 화면), NOTES(독서 노트 화면),
  CI(§5.3 스크린샷 순서·perf_log.py), W2(썸네일 파이프라인·격자 탭; 리더 연결은 통합 단계).
- 병합 후 연결: Lookups·백업 가져오기 → `Library.notesChanged()`, BackupPage → `AutoBackup.findCandidates(…, includeOwn)`·`countFiles`.
- 검사(W1 전부 병합, 9a8ac14): 전체 typecheck 성공, JVM **1,475개 전부 통과**.
- RC-A(ReaderActivity 통합): 세 파트(RCA-U/S/N)를 별도 작업 트리 `integ-rca`에서 병합·충돌 해결·리뷰 중.
  끝나면 이 브랜치에 합치고 `[screens]` 빌드로 에뮬레이터 검사를 돌린다.
- 알려진 남은 항목: RC-A 통합·리뷰 완료, W2 리더 연결(4번째 탭·호스트·⋮ 항목), 스크롤 settle의 RAPerf show 줄(CI 요청),
  QuoteSwatch 측정 크기 그리기(SET 요청), 인용 노트 열기 계약(EX-N 요청), ReturnHost 위치 clamp(RU-C 요청),
  CI 에뮬레이터 실행과 Comet 기기·성능 검사.

## 2026-10-02 진행
- 사용자 추가 지시: **페이지 넘김 애니메이션은 모든 기기·모드에서 완전히 사용하지 않는다.**
  탭·키·자동 넘김은 다음 화면으로 즉시 교체한다. 기존 scroll 명세의 180 ms step 애니메이션은 폐기한다.
- 1차 리뷰 22건 검증: 10건은 기존 구현·회귀 테스트에서 이미 수정, 나머지 12건 처리.
  근거: `wave1_review_resolution.md`.
- H1 최근 앱 복귀: 현재 책 id·정확한 위치의 instance state, 열린 책 표식, 루트 재시작 복원,
  정상 닫기 때 표식 해제, 2회 재시도 제한, 이미지 캐시의 UI_HIDDEN 유지, 최근 종료 진단을 구현.
- H1 JVM 회귀 17개와 TTS 포커스 정책 테스트 2개 추가. `tools/ci/screenshots.sh`에는 70–78 최근 앱 시나리오 추가.
- 아래 ‘지금 상태’는 빌드 15의 출발점을 설명한다. 현재 변경의 검사·빌드는 `H1_STATUS.md`에 기록한다.
- H1 Actions #17: APK 빌드 성공, 최근 앱 시나리오 전부 PASS (본문 차이 0%).
- H2·H3·H4 구현 및 JVM 검사 완료. `H234_STATUS.md`에 결과를 기록한다.
- Phase 0 계약 완료: 타입체크 성공, JVM 1,120개 통과. `PHASE0_STATUS.md`, `../R3_INTERFACES.md` 참고.
- E1 엔진 완료(2026-10-03): 타입체크 성공, JVM 1,139개 통과. LINE 출력 유지, PARAGRAPH golden 확정,
  anchor 배치·연속 본문 간격 검사 통과. `E1_STATUS.md` 참고.
- E2 렌더러 완료: 타입체크 성공, JVM 1,154개 통과. 상태 표시·인용문 무늬·스크롤 이미지 조회를 구현했다.
  `E2_STATUS.md` 참고.
- RC-P 세션·페이지 수 완료: 타입체크 성공, JVM 1,163개 통과. anchor를 유지하는 layout/count와 안전한 캐시 저장,
  화면 구간 LRU 보호를 구현했다. `RC_P_STATUS.md` 참고.
- RC-S 순수 위치 계산 완료: 타입체크 성공, JVM 1,180개 통과. `RC_S_MATH_STATUS.md` 참고.
  PageView의 스크롤 draw/touch·접근성 분기도 구현했다(타입체크 성공, JVM 1,180개 통과).
  `RC_S_INPUT_STATUS.md` 참고.
- RC-S 본체 완료: 타입체크 성공, JVM 1,195개 통과. `RC_S_STATUS.md` 참고.
  다음 작업은 RU와 나머지 W1 레인이며, 새 스크롤 UI는 RC-A 통합에서 활성화한다.
- 다음 작업은 W1 레인 → 통합 → W2 → CI·성능·리뷰다.

## 지금 상태
- **빌드 15 (이 커밋)**: R2 계약 + 1차 기능 묶음이 들어간 **검토 전 테스트 빌드**.
  - 포함: 목차 2.0(페이지 넘김 목록·화 번호 이동·빠진 화), 끝 화면/다음 권 이어 읽기, e-ink 새로고침 2.0, 키 지정·길게 누르기 동작,
    회차/남은 시간 표시, 읽기 기록(통계), 내 스타일 저장, 책별 TXT 설정, 치환 규칙 관리자·"이 문구 지우기", 화면 꺼도 TTS,
    Wi-Fi 전송, 서재 간단히 보기·여러 권 선택, 프리셋 이름 웹소설/전자책/종이책, 기본 글꼴 나눔명조.
  - 타입체크 깨끗, JVM 테스트 1045개 통과(한 번은 타이밍성 테스트 1개가 흔들림 → 재실행 통과).
  - **아직 안 한 것**: 이 묶음의 리뷰 지적사항 수정(`wave1_review_findings.json`, 22건), 2차 리뷰.
- 서명 키 고정 완료(빌드 13부터 같은 인증서 d075b203…): 이후 빌드는 덮어 설치 가능.

## 다음 주 순서
1. `wave1_review_findings.json` 22건 검증·수정 → 2차 리뷰 → 커밋 `[screens]`.
2. **H1 최근 앱 복귀 버그** (`wave2/recents.md`): 앱이 정리돼도 읽던 책·페이지로 바로 복귀. 별도 빠른 빌드로.
3. `wave2/PLAN.md` 순서대로: Step H(⋮ 터치 수정, 볼륨 방향 3택, InsetsGate) → Phase 0 계약 → 15개 레인 병렬 →
   통합 → 리뷰 → CI 스크린샷 비교(ReadEra 참조) 반복.
   - 스크롤 모드: `scroll/SPEC.md`
   - UI 개편(가운데 쪽 번호, 핀=돌아갈 위치, 밝기, 하단 3칸+진행 막대, 다듬기): `ui/UI_SPEC.md`
   - 독서 노트·단어장·인용문 색·서재 보기·페이지 썸네일: `notes/NOTES_SPEC.md`
   - 사용자 추가 요청 U1~U6(최근 앱, 하단 표시 독립, 상하 여백 40=0, 페이지 나누기 문단/줄, 볼륨 반전, 위치 고정):
     `wave2/USER_ADDENDUM.md`, `wave2/anchor.md`(엔진 프로토타입 `wave2/proto/engine.diff`)
4. 시장 분석·최종 백로그: `market/`.
