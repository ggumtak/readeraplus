# RC-S 위치 계산 결과 (2026-10-03)

RC-S의 순수 계산 부분을 완료했다. **ScrollReader·PageView 입력 연결과 RC-A 통합은 아직 진행 중이며 새 스크롤 UI가 활성화된 상태는 아니다.**

- pageLead와 챕터 간격을 포함한 strip 높이, 양방향 이동, 책 끝·알 수 없는 다음 구간 경계 처리 구현.
- 한 화면씩 넘김은 아직 보이지 않은 줄에서 시작한다. 이전 화면은 이전 줄까지 온전히 보이는 줄 위치로 이동한다. 긴 줄·빈 항목에서도 진행한다.
- TOP/CONTEXT 위치 배치, 줄 맞춤, 반 이상 보이는 첫 줄의 anchor, 실제 page index, 마지막 완전한 줄의 clip, 이동 거리, sticky anchor, 수동 이동 화면 수 계산 구현.
- 한 번의 strip walk는 64개로 제한한다. 빈 구간을 지나던 cursor가 실제 본문 끝을 기억해 긴 빈 tail을 반복해서 읽지 않고 마지막 본문 viewport로 복귀한다. 배치 시 해당 힌트는 초기화한다.
- 탭·키·자동 넘김은 즉시 위치 교체에 사용할 계산이다. 시간 보간이나 페이지 넘김 애니메이션을 넣지 않았다.

검사:

- `tools/typecheck.sh`: 성공.
- `tools/unittest.sh`: **1,180개 전부 통과**.
- 독립적으로 펼친 좌표 기준과 90,000개 무작위 이동 비교. 양끝 clamp·실제 이동 거리·끝 판정 일치.
- 실제 엔진의 LINE/PARAGRAPH·anchor·widow/orphan 조합으로 60개 책을 앞뒤로 넘겨 줄 누락·잘린 이전 줄 없음 확인.
- 200개 연속 빈 section, 긴 빈 tail, 전부 빈 책, 준비되지 않은 section, 큰 줄, TOP/CONTEXT·줄 맞춤·sticky anchor·화면 수 회귀 통과.
- 20,000회 반복 프레임 계산의 JVM 할당 검사는 호출 계측의 고정 비용 범위(<4 KB)로 통과. 줄 순회는 index 방식이며 프레임마다 Iterator·임시 위치 객체를 만들지 않는다.
- 두 golden 및 기존 성능 검사 유지. `git diff --check` 통과.

다음: RC-S ScrollReader/PageView 연결. generation 변경 동안 기존 화면을 유지하고, 준비되지 않은 section과 capped empty walk에서는 마지막 유효 화면을 유지하도록 연결한다.
