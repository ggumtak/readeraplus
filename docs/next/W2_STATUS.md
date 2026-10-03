# W2 페이지 썸네일 구현 결과 (2026-10-03)

새 파일과 kit의 `PageTarget`만 구현했다. ContentsDialog의 4번째 탭, ReaderActivity의 `PageThumbsHost` 구현,
⋮ "페이지 썸네일" 항목은 이 lane의 파일이 아니다. 그대로 붙여 넣을 수 있는 코드로 `lanes/W2.result.json`의 rcaNotes에 적었다
(RCA-1~5, EXN-1).

- `reader/PageThumbs.kt`: 첫 요청 때 만든다. 열기와 넘김에서는 +0이다.
  - 매핑은 main에서 한다. `ThumbMap`으로 계산하고, 배치되지 않은 구간은 `session.layout`으로 배치한 뒤 다시 계산한다(최대 3회). 이후 `idx.coerceIn`, 라벨은 `counts.globalPage`.
  - 렌더는 공유 "reader-thumbs" 데몬 스레드에서 한다(BACKGROUND 우선순위, 첫 요청 때 생성).
    - `PageRenderer`는 세대·paintVersion·settings가 바뀌면 다시 만든다(요청 시점의 `session.settings`).
    - 칸마다 `drawChrome(PageDecor(hl, false, null))` 다음 `drawBody`를 균일한 `canvas.scale`로 RGB_565에 그린다. 이미지는 peek만 하고 디코드하지 않는다.
    - 축척이 0.3 미만이면 `renderer.thumbnail = true`(thumbGrey).
  - 갱신 방식:
    - e-ink: 완성된 묶음을 한 번 보고한다. 700 ms가 넘으면 부분 → 완성.
    - 폰: 렌더할 칸이 생길 때만 자리표시를 보이고, 100 ms 이상 간격으로 채운다. 캐시된 쪽은 한 번에 보인다.
    - 완성 후 진행 방향의 다음 격자 쪽을 미리 렌더한다.
  - LRU는 바이트 단위다(`allocationByteCount`, `ThumbBudget`: e-ink 8 MB, 폰 16 MB, memoryClass/32).
    - 키는 `ThumbKey`(genId, section, idx, w, h, decorVersion, paintVersion, QuoteLook.generation).
    - 세대·paint가 바뀔 때, `clear()`(trim), `close()`에서 비운다.
  - `cancel()`: 썸네일 배치가 있었으면 curSection ± 1을 다시 prefetch한다.
- `reader/extras/ThumbsTab.kt`: `ThumbsTab`(PageTarget)와 `ThumbGridView`(View 하나가 모든 칸을 그린다).
  - 칸: 현재 쪽 3 dp 테, 12 sp 라벨(현재 쪽 굵게), 리본 / ❝ / ✎.
  - 새 묶음이 올 때까지 이전 격자를 유지한다. 그다음 칸·라벨·바를 한 프레임에 바꾼다.
  - 조작:
    - `PageDrag(axisBoth = true)` 스와이프, ◀ ▶, 페이지 키로 넘긴다.
    - 라벨을 누르면 숫자 패드("쪽 번호", "1–N", 범위 밖은 clamp)가 열린다.
    - 칸을 누르면 `goToPage(remember = true)` 후 닫는다.
  - `prepare(w, h, ready)`로 탭 전환과 썸네일을 한 번의 갱신으로 보인다.
- `ui/kit/InkPager.kt`: `interface PageTarget`. `InkPager`가 구현한다. `inkPagerKeys`는 `() -> PageTarget?`를 받는다(기존 호출 그대로 컴파일).
- 순수 로직: `ThumbGridMath`, `ThumbMap`, `ThumbKey`, `ThumbBudget`.

결정·차이:
- 가로 태블릿 800×500(0.625): spec 표는 3×3이다. 16 dp 라벨 띠를 넣으면 3열은 2줄만 되므로 4×3(188 dp)이 맞다. 테스트도 4×3이다.
- `drawBody`는 `canvas.width`(축척 전 좌표)로 clip한다. 그래서 `ThumbCanvas`가 page view 폭을 보고한다(RENDER 변경 없이).
- e-ink 판정은 `DeviceClass.cached(ctx) == true`(탭 progressive 여부와 PageThumbs 예산에 같이 쓴다).

검사:
- module 모드 `typecheck.sh`/`unittest.sh`(lane 파일 전부 own): 성공, **OK (38 tests)**.
- 전체 `tools/typecheck.sh`: 성공. 전체 `tools/unittest.sh`: **OK (1,220 tests)** (기준 1,195 + 새 25).
- 새 테스트: ThumbGridMathTest 9, ThumbMapTest 6, ThumbKeyTest 2, ThumbBudgetTest 4, ThumbDragTest 4.
- 독립 리뷰 2회(명세·버그)를 거쳤다. 지적 사항은 모두 고쳤거나 rcaNotes에 반영했다:
  - LRU sizeOf 누락
  - 폰 캐시 쪽의 자리표시 깜빡임
  - 탭 후 prefetch가 점프보다 앞서는 문제
  - paint race
  - 메모의 삽입 위치, 탭 3 직접 열기 한 번 갱신, pagers 캐스트, shareAll

남은 일: RC-A/EX-N 훅(rcaNotes), 스크롤 모드 플래그(아직 없음), CI 92–93 스크린샷, 기기 시간 측정(RAThumbs: 따뜻한 쪽 ≤ 90 ms, 차가운 쪽 ≤ 400 ms).
