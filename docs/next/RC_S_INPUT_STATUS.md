# RC-S PageView 입력 연결 결과 (2026-10-03)

PageView의 스크롤 분기를 구현했다. ScrollReader 본체와 ReaderActivity 통합은 아직 진행 중이다.

- draw/touch의 첫 null 검사에서만 스크롤로 분기한다. 그 아래 기존 paged draw/touch 소스는 그대로다.
- 손가락을 따라가는 모드는 touchSlop, 손을 떼면 이동하는 모드는 tapSlop을 사용한다. 움직임 시작 때 slop 거리만큼 본문이 튀지 않으며 마지막 UP 위치도 반영한다.
- 선택·밝기 콜백을 유지하고, CANCEL·두 번째 손가락·detach에서는 드래그를 취소/정리한다.
- VelocityTracker와 최대 속도 조회는 첫 스크롤 DOWN까지 지연한다. detach에서 recycle 후 null로 바꿔 재연결 때 새 tracker를 사용한다.
- 접근성 앞/뒤 이동은 리더의 기존 onWheel 명령 경로로 연결한다. 수동 넘김·자동 넘김 중지 등의 처리는 같은 경로를 사용한다.
- `ScrollInput.onDown()`은 기본 no-op인 선택적 콜백이다. ScrollReader가 기기/방식 변경을 다음 DOWN에서 적용할 때 사용한다.
- 페이지 넘김 애니메이션을 추가하지 않았다.

검사:

- `tools/typecheck.sh`: 성공.
- `tools/unittest.sh`: **1,180개 전부 통과**.
- 기존 paged draw/touch 구간이 변경되지 않았음을 index baseline과 비교했다.
- `git diff --check`: 통과.
- Android 터치·접근성 화면 동작은 ScrollReader/RC-A 연결 뒤 CI에서 확인한다.

다음: ScrollReader 본체(슬롯·고정된 이전 화면·가상 페이지·즉시 넘김·이미지 배치·막힌 구간 처리) 구현.
