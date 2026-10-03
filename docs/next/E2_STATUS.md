# E2 렌더러 결과 (2026-10-03)

- 스크롤용 chrome/body/overlay와 배치 이미지 prefetch 구현. 본문 프레임은 이미지 캐시만 조회하며 디코딩하지 않는다.
- 이미지 요청은 기존 latest-pending 스레드에 한 배치로 전달한다. 실패한 이미지 줄은 별도로 기억해 반복 요청·프레임별 문자열 키 생성을 막는다.
- 상태 표시 6칸을 기존 여백에 맞춰 그린다. 공간이 부족한 제목은 줄이거나 숨기고, 숫자는 자르지 않는다. 진행 막대도 아래 여백 안에서만 그린다.
- 상태 폭·위치 캐시는 상태 객체/버전/폭/리본 여유/글자 크기를 키로 사용한다. 제목 생략은 제목·배정 폭·글자 크기가 바뀔 때만 계산한다. 배터리 숫자는 고정 char 버퍼를 사용한다.
- 인용문 6종의 색/흑백 무늬, 채우기 후 선 그리기, 전역 x 격자의 점선, 썸네일용 채우기 구현.
- 기기 판정은 기기·펌웨어 stamp가 맞는 캐시만 사용하고, 비동기 vendor 탐색은 한 번에 하나만 실행한다. 콜백은 main에서 실행한다.
- 기존 paged `draw()`와 `drawLine()` 소스는 그대로다. FooterFit와 해당 구형 테스트는 PLAN C5대로 제거했다. 표지의 상태 표시는 null이다.

검사:

- `tools/typecheck.sh`: 성공.
- `tools/unittest.sh`: **1,154개 전부 통과**. 두 엔진 golden 유지.
- StatusMath 20,000개 무작위 배치: 슬롯 겹침 없음, 고정 숫자 축소 없음, 최소 제목 폭 준수.
- StatusDrawCache: 10,000개 동일 프레임에서 캐시 재사용, 각 상태·기하 변경에서 갱신.
- QuoteLook, DashMath, foreign-device/firmware cache 회귀 통과. 기존 BatteryMath/RibbonMath/LatestTaskRunner 검사 유지.
- steady draw 경로의 배열·Paint·문자열 생성이 없는지 소스 검토했다. native Canvas/Paint 화면·gfxinfo 검사는 RC-A 통합 뒤 CI/기기 단계에서 확인한다.
- `git diff --check`: 통과.

다음: RC-P 세션/페이지 수 캐시 → RC-S 스크롤 → 나머지 W1 → RC-A 통합 → W2 → CI·기기 검증.
