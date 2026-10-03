# RC-P 세션·페이지 수 결과 (2026-10-03)

- Generation이 AnchorSpec을 보관하고 해당 section의 layout/count에 같은 offset을 적용한다. 오류 페이지에는 anchor를 적용하지 않는다.
- viewport/settings 재배치는 받은 anchor를 유지한다. anchor는 count-cache key에 넣지 않았다.
- 현재 화면의 PageCounts는 anchored 값을 사용한다. 저장 복사본은 기존 un-anchored 캐시 값으로 바꾸거나 -1로 마스킹한다. 오류 section 마스킹도 유지한다.
- 캐시가 없고 anchor 때문에 페이지 나눔이 달라진 경우에만, 모든 section 계산 후 해당 section을 anchor 없이 한 번 더 센다. 이 추가 계산도 기존 count delay 뒤에 실행한다.
- 화면에 보이는 section 범위 전체를 LRU에서 보호한다. 모든 캐시 구간이 화면에 보이는 동안은 MAX_CACHED를 잠시 넘을 수 있다.
- TXT 제목과 EPUB partCounts를 공유하는 지연 경계 맵 구현. 잘못된 spine 분할 정보는 경계·샘플 플래그를 만들지 않는다.
- DEBUG RAPerf의 count 종료 로그에는 counted/cached/extra와 시간을 기록한다. 일반 실행에서는 문자열을 만들지 않는다.

검사:

- `tools/typecheck.sh`: 성공.
- `tools/unittest.sh`: **1,163개 전부 통과**.
- range eviction, cache 복사본과 화면 page label의 독립, 잘못된 cache count/index, Generation별 anchor, TXT/EPUB 경계 및 손상된 분할 정보 회귀 통과.
- 기존 PageCounts/LayoutKeys 검사와 두 엔진 golden 유지. `git diff --check` 통과.
- 문단 모드 추가 반복 측정(동일 JVM, 12회 준비 후 9회 중앙값 × 3): LINE layout 22.36–22.47/count 21.72–23.44 ms, PARAGRAPH 22.20–22.61/21.50–22.02 ms. 100만 자 문서에서 속도 차이는 측정 변동 범위다.

ReaderActivity의 anchor 전달·스크롤 host 연결은 계획대로 RC-A 통합에서 진행한다. 다음 레인은 RC-S다.
