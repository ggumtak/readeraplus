# RU-C 구현 결과 (2026-10-03): 리더 크롬 · 돌아가기 지점(핀) · 상태 모델

밝기 로직(LightController 등)은 RU-L 담당이다. 이 레인은 크롬 뷰(밝기 행·옵션 패널 포함), ReturnNav, StatusModel,
ChromeMath를 구현했다. ReaderActivity 연결은 RC-A가 한다.

- **ReaderChrome (U §2, polish 1·4·6·10·11)**: 위 막대는 [뒤로][여백][북마크][TTS][검색][목차][설정][⋮], 한 줄 제목
  (17 sp 굵게, 패딩 20/0/16/10), 밝기 행 [자동][막대][⌄/⌃] 순서다. 폭 가드: `setVisible(true)`에서 표시 폭 − 좌우
  인셋으로 계산하고, 폭이 바뀔 때만 다시 정한다. 352 dp 미만이면 북마크를 숨긴다(`bookmarkHidden`).
  - 아래 막대는 돌아가기 줄(index 1), 라벨 행, [이전 화][위치 막대][다음 화] 순서다. 라벨 행은 FrameLayout이며
    전체 폭 가운데에 고정 폭(행 − 216 dp) 라벨을 둔다. 라벨은 17 sp 굵게, tnum, 밑줄 없음, 자동 크기 14–17 sp,
    설명 "페이지 이동, N / M"이다. 오른쪽에는 [회전][핀]을 둔다.
  - 상태는 아이콘 교체로만 표시하고 isSelected는 쓰지 않는다. 막대 모양은 자동(속 빈 고리 + 회색 진행선)과
    수동(검은 점) 두 가지이며, 사용자가 드래그를 시작하면 즉시 수동 모양으로 바뀐다. 설명은 "밝기"와
    "페이지 위치"다.
  - 옵션 패널은 처음 열 때 만든다. 질문 행, "스와이프로 밝기 조절", "기기 밝기 직접 조절", "기기 조명 설정 열기"
    순서다. 행 전체가 접근성 단위(checkable)다. 비활성은 GRAY 색과 부제 문구로 표시한다.
  - 판정 NONE이면 "기기 조명 설정에서 조절" 링크가 자동 버튼과 막대를 대신한다. 모든 setter는 값이 같으면
    아무것도 하지 않는다. 사용자 동작은 LightController API(onDrag/onAuto/onSwipeSwitch/onDeviceSwitch/
    onAnswer/onOpenPanel)로만 보낸다.
- **ReturnNav (U §3.2–3.4)**
  - ReturnPoints는 ★1–★5 상태기계다. 칩 표시 여부는 저장하지 않고 `chipVisible`로 계산한다.
  - ReturnMarkCodec 형식은 `m1|sec|off|frac|sig`다. 서명 안에 `|`가 있어도 된다.
  - 줄과 칩은 처음 쓸 때 만든다. 줄의 왼쪽은 "N 페이지로"이고, 핀 쪽이 화면에 있으면 작은 핀 아이콘 + "N 페이지"
    (GRAY, 누를 수 없음)로 바뀐다. 가운데는 "지우기", 오른쪽은 "N 페이지로 ›"다.
  - 칩은 "‹ N 페이지로 | ✕"(15 sp 보통, 1 px 테두리, 각진 모서리)이며 수동 2쪽 넘김 뒤 숨는다.
  - 맞춤 규칙(stripShort)이 걸리면 "‹ N" / "N ›"로 줄인다. 라벨 문자열은 쪽 번호가 바뀔 때만 만든다.
  - `PIN_FLOATS = false`.
- **StatusModel / StatusText (U §5.3, polish 16)**: 6칸을 고정 버퍼로 채운다. 같은 입력이면 false를 돌려주고,
  점 위치는 `round(bar × trackPx)`가 바뀔 때만 변경으로 본다. lane은 설정만 따른다. 챕터 첫 쪽에서는 CHAPTER
  칸을 비운다. `sample()`은 설정 팝업용이다.

결정·편차:
- `setBrightnessCollapsed`는 frozen 목록에 남아 있어 no-op으로 유지했다. RC-A가 호출을 지운다.
- 칩 테두리는 `borderBox(strokeDp = 0)`로 1 물리 px로 맞췄다.
- 시작 직후 점프로 임시 mark가 있는데 저장된 핀이 늦게 도착하면, 임시 mark는 other가 된다. 이때 offer와
  chainOffer도 MARK→OTHER로 옮긴다(표에 없는 경우, 칩이 계속 원래 읽던 곳을 가리킨다).
- 복원 위치의 범위 제한(coerceIn)은 ReturnHost에 구간 수 API가 없어 호스트(globalPageOf/isOnCurrentPage/
  jumpToReturn)가 한다.
- `reset()` 뒤의 `restore()`는 무시한다. 다음 `bind()`나 `onJump()`가 이 상태를 푼다(RC-A의 책·세션 가드와 함께 쓴다).
- 행 폭은 `displayMetrics.widthPixels`로 계산한다(회전 직후 root.width가 아직 옛 값이므로).

검사:
- 모듈 모드: `tools/typecheck.sh <OWN>` 성공. `tools/unittest.sh <OWN>`: OK (47 tests).
- 전체: `tools/typecheck.sh` 성공. `tools/unittest.sh`: **OK (1242 tests)** (기준 1,195 + 47).
- StatusModelTest: 워밍업 뒤 update 10,000회에서 0 바이트(AllocCounter, ThreadMXBean 리플렉션).
- 독립 리뷰 2회(스펙 완전성, 버그 탐색)의 실제 지적은 모두 고쳤다.

RC-A 연결 시 남은 일(자세한 내용은 RU-C.result.json의 rcaNotes):
- `returnNav.bind()`를 모든 showPage와 스크롤 정착 끝(크롬 상태와 무관)에서 호출해야 칩이 보인다.
- 지금 트리의 ReturnHost 멤버는 `TODO("owner: RC-A")`라서 RC-A 병합 전에는 핀을 누르면 예외가 난다. 기기
  테스트는 RC-A 병합 뒤에 한다.
- CI 스크린샷 13–13h, 10b와 Comet 기기에서 다음을 확인한다: 첫 표시가 e-ink 한 번 갱신인지, 라벨 중앙 x=360 ± 2,
  칩 위치.
