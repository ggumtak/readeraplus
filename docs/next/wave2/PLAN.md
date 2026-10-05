# PLAN.md — wave 2 build plan: R3 = scroll + UI + NOTES + user addendum U1–U6

> **사용자 변경 지시 (2026-10-05): 탐색줄(진행 선)은 ReadEra처럼 은은하게.**
> "그리고 밑에 언더 바 있잖아 탐색줄? 이것처럼 은은하게 나오게 바꿔줘 대놓고 빡!! 하고 보이는 게 아니라 있었구나 하면서 볼
> 정도로 사진을 최대한 카피해". 사용자가 보낸 ReadEra 사진(같은 S25, 흰 바탕 · 검은 바탕)을 픽셀로 따른다(`ProgressMath`):
> `max(1, round(0.67 dp))` 선(S25 2 px, 코멧 1 px)과 양 끝 · 현재 위치의 점 세 개가 모두 같은 크기 · 같은 색(S25 14 px,
> 코멧 9 px: 4.67 dp에 가장 가깝고 선과 가운데가 맞는 크기), 끝 점 바깥 끝은 페이지 뷰 양옆에서 7 dp(본문 칸이 아니라 화면
> 폭), 가운데는 아래 끝에서 8 dp 위(S25 선 2315–2316 행 · 점 2309–2322 행 · x 21–34 / 1045–1058, 코멧 선 1423 행 · 점
> 1419–1427 행 · x 14–22 / 697–705). 선은 두 끝 점의 가운데 사이, 점이 그 위에 온다. 색은 상태 색이 아니라 바탕을 조금 바꾼
> 회색(`PagePalette.progressLine` / `progressDot`, 팔레트마다 한 번 계산): 밝은 바탕은 검정 쪽으로 46 · 75/255(18 · 29.4 %),
> 어두운 바탕은 흰색 쪽으로 31 · 50/255(12.2 · 19.6 %). 흰 바탕 #D1D1D1 · #B4B4B4, 검은 바탕 #1F1F1F · #323232로 사진과
> 같고, 마루뷰어는 #4B4B4B · #5A5A5A(금색 아님). e-ink(`DeviceClass` e-ink 또는 아직 모름)는 16단계 회색에 맞추고 선은 바탕과
> 두 단계, 점은 선과 한 단계 이상 떨어지게 한다(`inkProgressLine` / `inkProgressDot`: 흰 바탕 #CCCCCC · #BBBBBB, 흑백 반전
> #222222 · #333333, 마루뷰어 #555555 · #666666). 첫 쪽 · 마지막 쪽에서는 위치 점이 끝 점과 겹치므로 그리지 않는다. 상태
> 줄 글자 색, 아래 띠(끝 4 + 진행 막대 12 + 2 dp = 18 dp), 본문 자리는 그대로이고, 점은 진행 막대 띠 안(코멧에서 아래 끝 위
> 12 px)에 있어 돌아가기 칩은 그대로 그 띠 위다. 이 지시로 UI_SPEC §5.4의 `drawProgress`
> 표(1 px 선, 끝 캡, 6 dp 점, 상태 색)와 아래 지시의 "진행 점은 2302"를 대체한다. CI 13w(흰 바탕 · 마루뷰어 · 흑백 반전의 선
> · 끝 점 색과 자리), 점검표 §11f.
>
> **사용자 변경 지시 (2026-10-05): 이동 기록 줄 = ReadEra 방식 (이전 · 다음).**
> "이전이 없으면 왼쪽이 사라지고 이전이 있으면 왼쪽이 생기는 방식이어야지. 오른쪽은 다음이 있으면 생기고 없으면 없고"
> (ReadEra 화면 두 장: 1749쪽에서 "< 1 페이지로 | 지우기 | 150 페이지로 >", 1쪽에서 "지우기 | 1749 페이지로 >"). 고정 위치
> 하나와 다른 곳 하나(UI_SPEC §3 ★1 · ★2 · ★4)를 두던 방식을 브라우저처럼 '이전' · '다음' 목록 두 개로 바꿨다(UI_SPEC §3).
> 기억하는 이동(목차 · 검색 · 북마크 · 페이지 이동 · 쪽 이동 막대 · 링크 · 노트)은 떠나는 쪽을 이전 목록 맨 위에 넣고 다음
> 목록을 비운다. 왼쪽 "‹ N쪽으로"는 이전 목록에서, 오른쪽 "M쪽으로 ›"는 다음 목록에서 지금 보는 쪽이 아닌 가장 최근
> 곳이고, 그런 곳이 없으면 그 칸은 보이지 않는다(칸 자리는 남아 지우기가 움직이지 않는다). 왼쪽을 누르면 지금 쪽이 다음
> 목록 맨 위로, 오른쪽을 누르면 이전 목록 맨 위로 간다. 지우기는 두 목록을 비운다. 아래 막대의 고정(핀, "이 페이지
> 고정")은 지금 쪽을 이전 목록 맨 위에 넣고 다음 목록은 그대로 둔다. 핀으로 넣은 이전 목록 맨 위가 지금 쪽이면 아이콘이
> 채워지고("고정 해제") 다시 누르면 그 자리를 뺀다. 핀을 눌러도 줄의 더 오래된 곳은 그대로 보인다. 줄에 회색 "📌 N쪽" 같은
> 누를 수 없는 표시는 없다. 검토 수정(같은 날): 쪽을 넘기지 않고 이동을 거듭해도 떠난 쪽이 모두 남는다(1쪽 → 1749 → 넘기지
> 않고 150이어도 150에서 "‹ 1749쪽으로"). ★3(처음 떠난 쪽)은 칩에만 남는다. 메뉴를 닫은 채 이동하면 뜨는 칩
> "‹ N쪽으로 | ✕"는 그대로다(손으로 2쪽 넘기기, ✕, 기록 사용, 핀, 지우기에 사라짐). 두
> 목록은 각 20곳까지 책마다 저장되어 다시 열어도 남고(예전에 저장한 고정 위치 하나는 이전 목록으로 읽는다), TXT를 다른
> 설정으로 다시 나누면 각 위치를 글자 비율로 다시 찾는다. CI 13b–13i · 13u, 점검표 11-2–11-5 · 11e-5 · 11e-13 · 11f
> (11f-8–11f-10: 핀을 눌러도 줄이 그대로, 칩은 처음 떠난 쪽, 코멧에서 탭 한 번에 갱신 한 번).
>
> **사용자 변경 지시 (2026-10-05): 위·아래 여백은 상태 표시줄을 뺀 본문 영역 기준.**
> "아니지 위 여백은 위 아래 애들을 제외하고 본문영역에서만 계산해야지". 위 상태 줄 · 아래 상태 줄 · 진행 막대는 화면 끝에
> 자기 띠를 갖고(`StatusBands`, 설정만으로 정한 정수 dp: 위 띠 = 끝 4 dp + 글자 상자 + 2 dp, 11 sp에서 22 dp; 아래 띠 =
> 끝 4 dp + 진행 막대 12 dp + 2 dp(본문이 진행 점에 닿지 않게), 아래 글자가 있으면 + 글자 상자 + 2 dp; 진행 선만이면 18 dp,
> 아래 글자까지 36 dp; 항목이 모두 '없음'이면 0), 위·아래 여백은 그 띠와 본문 사이다. 여백 0이면 본문이 위 띠 바로
> 밑에서 시작하고, 작은 여백이 상태 줄을 가리거나 줄이지 않으므로 '가려짐' 안내는 없앴다. 메뉴 · 선택 · TTS · 쪽 수 세기 · 빈 제목 · 스크롤 모드는 본문 상자를 움직이지 않는다. 슬롯의 항목을 다른
> 항목으로 바꾸면 다시 그리기만 하고, 띠가 생기거나 없어지거나 높이가 바뀌면(모두 '없음' ↔ 항목, 진행 막대, 상태 글자
> 크기) 첫 글자를 지키며 다시 배치한다. 숫자는 코멧에서 본문이 그대로이게 맞췄다("코멧에서 본문 지금 자리 그대로 되게
> 숫자 맞춰줘"): 위 18 dp · 아래 22 dp(40 dp − 기본 띠), 상하 여백 "0"이 각 쪽의 기본값(범위 −22..+62, 각 0..80 dp; 한
> 단계는 두 쪽을 2 dp씩 옮기고, 기본값 선을 벗어난 값도 튀지 않는다: `VerticalMargin.step`). 아래 글자를 켜면 아래 띠가
> 18 dp 커지는 만큼 본문이 짧아진다(코멧 36 px). S25 전체 화면은 사용자가 보낸 업데이트 전 화면을 따랐다("S25 전체 화면도
> 지금 자리 유지해줘 걍 최대한 이거랑 여백 넓이랑 그리고 여백 알고리즘을 따라해봐"): 카메라 띠(87 px)를 시스템 막대처럼
> 빼고 그 아래에 위 띠를 잡아 두며, 본문은 207..2220, 진행 점은 2302로 그 화면과 같다(진행 선은 위 지시로 ReadEra 자리). 위 상태 줄도 그 화면의 방식대로 카메라
> 띠와 본문 사이 가운데(글꼴 상자 기준, `StatusFit.headerBaseline`)에 그려 글자가 147–181 px로 그 화면과 같은 줄에 온다. 카메라
> 띠가 없는 화면(코멧, 막대가 보이는 S25)의 위 줄은 마루뷰어처럼 위 끝 4 dp 아래 그대로다. 책갈피 리본도 그 화면처럼 카메라
> 띠 아래(87 px)에서 내려온다. 글자 크기는 고른 그대로이고, 글자 잉크가 띠의 글자 상자보다 클 때(큰 시스템 글꼴 배율)만 한
> 번 줄인다(`StatusFit.fitTextPx`); 잉크는 늘 자기 자리 안에 둔다. 예전 뜻(화면 끝에서 잰 값,
> `r.marginBaseV` 40 또는 없음)으로 저장된 위·아래 여백은 읽을 때 한 번 그 설정의 띠만큼 줄여 본문 자리를 지킨다(설정 ·
> 백업 · 내 스타일; 내 스타일은 상태 줄이 없어 기본 띠로). 새로 저장하면 `r.marginBaseV` = 2. 이 지시로 U2/C5의 "본문
> 상자는 상태 띠에 자리를 내주지 않는다", 아래 마루뷰어 지시의 "상하 여백 40 dp = '0'"과 "S25에서는 카메라 구멍 띠 안",
> CI 10b · 52 · 53의 "다시 배치 없음, 본문 픽셀 그대로"를 대체한다: 이제 첫 글자(`first_is`)와 본문 위쪽 줄(pv + 80 …
> pv + 680의 `raw_equal`)이 그대로인지, 새 본문 아래 끝과 아래 띠 사이가 빈 종이인지(10b_margin) 본다.
>
> **사용자 변경 지시 (2026-10-05): 마루뷰어 여백 · 상태 표시줄.**
> 좌우 여백 기본값은 마루뷰어와 같은 20 dp다(S25에서 본문 왼쪽 x ≈ 60 px, 폭의 5.6 %). 퀵옵션과 읽기 설정의 좌우 여백 "0"이
> 20 dp이고 범위는 −20..+60(0..80 dp)이다. 상하 여백은 그대로 40 dp = "0"이다. 저장값은 계속 실제 dp이고, 예전 기본값을
> 그대로 둔 좌우 여백(R3 40/40, R2 18/18)만 한 번 20/20이 된다(설정 · 백업 · 내 스타일). 위 상태 표시줄 기본값은 마루뷰어의
> 줄: 왼쪽 배터리 아이콘(채운 만큼이 잔량, 숫자 없음)과 시계, 가운데 책 제목(길면 앞을 줄여 끝을 남긴다), 오른쪽 쪽 번호.
> 기존 설치에도 한 번 적용한다(아래 상태 표시줄 · 진행 막대 · 글자 크기는 그대로). 위 상태 줄은 화면 위 끝에 붙고(글자 위
> 끝이 위 끝에서 4 dp; 전체 화면의 S25에서는 카메라 구멍 띠 안), 아래 줄은 진행 선 바로 위(또는 아래 끝 4 dp 위)에 붙는다.
> 위 여백 가운데 정렬(U2)은 이 지시로 대체한다. 본문 상자는 그대로라 쪽 나눔과 첫 글자가 바뀌지 않는다. 이 문서의 "40 dp =
> '0'"(S §2.2)은 좌우에 대해 이 지시로 대체한다.
>
> **사용자 변경 지시 (2026-10-02): 페이지 넘김 애니메이션 없음.**
> 탭·볼륨 키·기기 버튼·자동 넘김은 PAGED/SCROLL, STEP/SMOOTH, 휴대폰/e-ink 모두 즉시 이동한다.
> 이 문서의 180 ms step/startScroll 애니메이션과 관련 예외·성능 기준은 이 지시로 대체한다.
> SMOOTH의 직접 손가락 드래그와 페이지 넘김 명령은 별개이며, 페이지 넘김 명령은 보간 프레임을 만들지 않는다.
>
> **사용자 변경 지시 (2026-10-04): 퀵옵션 정리와 매끄러운 스크롤.**
> ⚙ 퀵옵션은 글자 크기 · 굵기 · 줄 간격 · 문단 간격 · 글꼴과 "전체 읽기 설정 ›"만 둔다. 나머지는 모두 설정(읽기 설정,
> 이 책의 TXT 정리, 넘김·화면 설정)으로 옮기고, 퀵옵션 항목도 설정에 함께 있다 (§1.6.3).
> 목차 · 북마크 · 인용문 목록과 스크롤 모드는 일반 목록처럼 손가락을 따라 움직이고 놓으면 관성으로 이어서 움직인다
> (e-ink 포함; 스크롤 모드의 "자동"은 이제 손가락을 따라 이동, "손을 떼면 이동"은 선택으로 남음). 페이지 넘김 명령
> (탭 · 키 · 자동 넘김)은 위 지시대로 계속 즉시 이동한다.
>
> **사용자 변경 지시 (2026-10-04): 서재도 스크롤.**
> 서재와 독서 노트 목록은 기본(목록 넘기기 "스크롤")으로 e-ink에서도 손가락을 따라 움직이고 놓으면 관성으로 이어서
> 움직인다. "자동 = e-ink는 쪽 단위"는 없어졌고, 목록 넘기기는 "스크롤"(저장값 자동·스크롤)과 "쪽 단위 (한 화면씩)"
> 두 가지다. 쪽 단위를 고르면 이전처럼 끌기 한 번이 한 쪽이다 (C2의 e-ink 기본 쪽 단위는 이 지시로 대체).
>
> **사용자 변경 지시 (2026-10-04): 웹소설 스타일 = 마루뷰어 화면 (색 포함).**
> 스타일 "웹소설"(`StylePreset.MARU`)은 사용자가 보낸 마루뷰어 화면을 그대로 따른다: 나눔명조 Regular(400), 줄 간격
> 200%, 문단 사이 빈 줄 한 줄(문단 간격 200%), 왼쪽 정렬 · 어절 줄바꿈 · 들여쓰기 없음 · 글자 간격 0, 그리고 새 화면 색
> "마루뷰어"(`PageTheme.MARU`: #323232 바탕, #DDDDDD 글자, 오른쪽 아래로 짧은 검은 그림자, 상태 표시 금색 #F0D096).
> 화면 색은 읽기 설정 → 스타일 → 화면 색에서도 따로 고른다. 기본값은 흰 바탕(e-ink 우선)이고, 흑백 반전이 켜져 있으면
> 흑백 반전이 이긴다. 화면 색 변경은 다시 그리기만 하고 다시 배치하지 않는다. 글자 크기와 여백은 프리셋이 바꾸지 않는다
> (마루뷰어 왼쪽 여백 ≈ 28–31 dp, 우리 기본 40 dp).
>
> **사용자 변경 지시 (2026-10-04): 설정만이 아니라 앱 안 문구 전체, 그리고 설정 묶음이 한눈에 보이게.**
> 리더 · 서재 · 독서 노트 · 대화상자 · 알림의 문구를 모두 쉬운 한국어로 바꿨다(68aa271, 79cd1a5, a3b8826). 설정은 항목 수는
> 그대로 두고 묶음을 또렷하게: 첫 화면은 읽기 · 서재 · 기타 세 묶음, 모든 페이지의 묶음 머리글은 15 sp 굵은 검정이고 첫 묶음을
> 뺀 모든 묶음 위에 화면 폭 검은 줄이 있다(UI_SPEC polish 12의 "간격만으로 구분"을 대체). 넘김·화면 설정은 넘기기·터치·키 ·
> 화면·밝기 · e-ink 화면 세 페이지가 됐다. 리더에서 연 설정에는 서재 묶음 · 백업·복원 · 캐시 비우기가 없다. 퀵옵션에 좌우 여백 ·
> 상하 여백이 다시 들어왔다(7행, 384 dp). 상태 표시 "챕터 쪽 번호"는 챕터 안의 쪽 "2/32". 위 지시들의 이름도 이 지시로
> 바뀌었다: 넘김·화면 설정 → 위 세 페이지, 목록 넘기기 "쪽 단위 (한 화면씩)" → "한 화면씩", 서재 보기 전체 · 요약 · 썸네일 ·
> 그리드 → 자세히 · 간단히 · 큰 표지 · 작은 표지, 돌아가기 "N 페이지로" → "N쪽으로". 행 순서는 §1.6.3, CI는 §5.3.
> 같은 날의 리뷰 반영: 묶음 머리글은 18 sp, 묶음 사이 20 dp 간격과 2 px 검은 줄, 묶음 안 행 사이에는 줄이 없다. 첫 화면은 읽기
> 화면 · 조작·기능 · 서재 · 책 가져오기 · 기타 다섯 묶음(한 묶음에 한 종류의 행), 화면·밝기의 상태 표시줄은 위쪽 · 아래쪽 두
> 묶음(행 이름은 왼쪽 · 가운데 · 오른쪽), "e-ink 화면" → "e-ink 새로고침", 리더 · 서재 ⋮ 메뉴도 줄로 묶음을 나눈다. 문장에 "="를
> 쓰지 않고("왼쪽 1/3은 이전, 나머지는 다음", "아래 키로 다음 페이지"), 세는 쪽과 쪽 번호는 "쪽", 화면 한 장은 "페이지"다(§1.6.3).
> 챕터 쪽 번호는 쪽 번호와 같은 띄어쓰기 "2 / 32", 크기는 어디서나 "3.4MB", 서재 ⋮의 스캔은 "지금 스캔", "페이지 썸네일" →
> "페이지 미리보기".
>
> **사용자 변경 지시 (2026-10-05): 리더 메뉴 다듬기 (메뉴 막대 색 · 그림자 · 이동 기록 줄).**
> 기능은 그대로 두고 색 · 간격 · 크기 · 경계 · 상태 표시를 다듬는다(UI_SPEC §2.1 토큰, `render/ChromePalette`). 위 · 아래
> 막대는 화면 색을 따른다: 흰 바탕은 아주 옅은 회색, 마루뷰어는 본문에 가까운 어두운 회색(강조색은 상태 표시의 금색), 흑백
> 반전은 거의 검은색. 세피아 화면 색은 없다(추가하지 않음). 막대와 본문 사이에 짧은 그림자(4 dp, 흑백 반전은 1 px 선), 막대 안
> 줄은 옅게, 겹친 줄은 뺐다. 이동 기록 줄은 아래 패널 바로 위, 본문 색 바탕에 세 칸(왼쪽 이전 위치 · 가운데 지우기 · 오른쪽
> 다음 위치)으로 고정되고 기록 로직은 그대로다. 글자 크기는 제목(18 sp 굵게) > 쪽 표시(현재 쪽 17 sp 굵게, " / 전체"는 작고
> 옅게) > 이동 기록 줄(14 sp) 순서다(ReadEra와 같은 순서). 두 슬라이더는 휴대폰 2 dp 트랙과 16 dp 손잡이(e-ink 3 / 18 dp), 48 dp
> 터치 높이. 막대의 모든 버튼과 이동 기록 줄은 48 dp 터치 영역이다. 휴대폰에서는 버튼 눌림 표시, 켜진 북마크 · 고정 · 회전 잠금의
> 강조색(눌림과 구별되는 진한 원), 메뉴 표시 · 숨김 0.18 / 0.15초 페이드(시스템 애니메이션 배율 0이면 즉시). **e-ink 예외:** 이
> 지시의 그림자 · 페이드 · 눌림 표시는 e-ink에 없다. e-ink는 동작 하나에 갱신 한 번, 경계는 1 px 단색 선, 흰 바탕은 전과 같은
> 흑백이다(색만 화면 색을 따른다). 메뉴는 계속 본문 위에 겹치는 막이라 열고 닫아도 본문이 다시 배치되거나 읽던 자리가 바뀌지
> 않는다. 쪽 넘김은 계속 즉시(2026-10-02). CI 13t–13v. **이번 단계의 예외(리드 확인 대기):** 막대에서 여는 ⋮ 메뉴 · ⚙ 빠른
> 설정 · 회전 선택 · 목차와 검색 전체 화면 창은 화면 색과 상관없이 지금처럼 흰 바탕이다. 화면 색을 따르게 하는 것은 후속 과제다.


Status: build plan, read-only against the repo. It was written on 2026-09-30 against HEAD `92be04f` ("WIP checkpoint: R2
contract + tier-1 feature lanes") plus the R2 working tree (39 changed or new files, still being edited by another
workflow). Paths are relative to `app/src/main/java/com/ggumtak/readeraplus/`, and tests live under
`app/src/test/java/com/ggumtak/readeraplus/<same path>`. Line numbers are not used: symbols only.
**[Δ]** An adversarial critic pass edited this file in place. Edits are marked [Δ], and §7 is the changelog (K1–K16).

## 0. Sources, precedence, baseline

| Tag | Source | Subject |
|---|---|---|
| **S** | `scratchpad/scroll/SPEC.md` | scroll mode (stitched pages, `PageInfo.lead`), side margins 40 dp = "0", auto backup + restore offer |
| **U** | `scratchpad/ui/UI_SPEC.md` (+ `ui/*.md`) | chrome, pin = return point, brightness, status slots + progress line, polish |
| **N** | `scratchpad/notes/NOTES_SPEC.md` (+ `notes/*.md`) | 독서 노트 hub, 단어장, quote colours, library views + paging, ⋮ fix (H0), page thumbnails (W2) |
| **R** | `scratchpad/wave2/recents.md` | U1 recents → library bug, U5 volume-key direction |
| **A** | `scratchpad/wave2/anchor.md` (+ `wave2/proto/`) | U2 bands in the margins, U3 vertical margins 40 = "0", U4 page-break mode, U6 position stability |
| **ADD** | `scratchpad/wave2/USER_ADDENDUM.md` | the user's U1–U6 |

**Precedence.** ADD, then this PLAN's explicit resolutions (§1), then A and R (they already amend S/U/N), then S, U and N,
each on its own subject. When a spec line is voided below, the PLAN wins, and the lane that owns the file applies the
PLAN version.

**User priorities that decide ties.** Books open instantly (nothing new before the first page). Page turns are instant
(paged + tap stays the default). E-ink first (one update per action, no animation, no grey-only state). Refined UI.
The reading position never moves when anything is shown or hidden, or when a setting changes.

**Baseline facts the specs did not know about (found in the R2 working tree):**

1. `ui/library/LibraryViews.kt` already has `FastScrollEdge` (EDGE 24 dp, CLAIM 96 dp, x shifted to 0, intercept
   only), `LibraryListView` and `LibraryGridView`. The compact row's ⋮ has a 16 dp right gap. There is a new test,
   `ui/library/FastScrollEdgeTest.kt`. This is a partial version of N's H0 (§1, C2).
2. `render/PageRenderer.kt` has R2's `FooterFit` (`FooterFitTest`) for the one-string footer. U deletes that path (C5).
3. `ReaderActivity.finish()` is already overridden (no close animation). There are no `onStart`, `onStop` or
   `onSaveInstanceState` overrides.
4. `LibraryListMode` labels are "목록 / 간단히 / 표지". N renames them.
5. The popup order is `fillMore`: 페이지 넘김 (화면 터치, 볼륨 키 switch) → 글자 → 페이지 (여백, 상단 챕터 제목, 하단 정보 표시,
   상태 크기, 흑백 반전, 외톨이 줄 방지) → TXT/EPUB → footer items.
6. Drawables: everything the specs name exists except `ic_view_agenda` and `ic_apps` (N, optional; fallbacks
   `ic_article` / `ic_grid_view`).
7. `LibrarySchema.DB_VERSION = 2`, and `ADDED_COLUMNS` holds `quotes.style` (v2).
8. **[Δ] R2 files still in flight that this plan edits** (critic pass, `git diff --stat` of the working tree): 
   `reader/LayoutKeys.kt` (`textSignature` now starts `t1|v<TxtDocuments.PARSE_VERSION>`), `ui/settings/KeyNames.kt` +
   `KeyNamesTest` (T1-4: "키 지정" now writes `AppSettings.keyBindings` through `KeyAssign.bind(app, code, action)`;
   `assign(app, code, next)` and `list()` are gone; new `entries`, `actionOf`, `readerEffectLabel`), `PageTurningPage.kt`
   (+311 lines), `SettingsLogic.kt` (+163), `SettingsPage.kt`, `MainPage.kt` ("설정 초기화" now keeps TXT defaults, keys,
   library view), `LibraryActivity/Views/Dialogs.kt`, `SettingsFormatTest`. `format/txt`: `TxtIndexStore.VERSION` 3 → 4
   (= `PARSE_VERSION`, A5 author notes), so every TXT section split changes once with R2.
9. **[Δ]** `PageView` exposes no text to accessibility (no node for uiautomator), and there is **no RAPerf "relayout"
   line** today (only "open … first page" and DEBUG "turn N ms"). Checks that read the page's first word or count
   relayouts need the H4 log line (§2 H4).

**Gate 0 (before step H).** R2 is merged and committed, `git status` is clean, and `tools/typecheck.sh` plus
`tools/unittest.sh` are green. Run `tools/snapshot_contracts.sh`. Re-grep every symbol this plan names (R2 may have
renamed some). **[Δ]** Re-read every file of fact 8 before the H/P0 step that edits it (H3: `KeyNames`, `PageTurningPage`;
P0: `LayoutKeys` keeps R2's `textSignature`, `PageTurningPage`/`SettingsLogic` fallout counts; SET: `MainPage` reset).

---

## 1. Conflicts between the five sources, and their resolutions

Each row names the sources, what collides, the decision, and who applies it (lane names from §4).

### 1.1 Data and contract

| # | Conflict | Decision | Applied by |
|---|---|---|---|
| **C1** | **Schema v3 is claimed twice.** U §1.5 adds `book_prefs.return_mark`. N §4.1 adds `quotes`/`bookmarks` `chapter, frac, sig`, `books.review_at, missing_at`, the `lookups` table, 5 + 2 indexes and `UPGRADE_SWEEP`. U plans "LibrarySchemaV2Test + v3 cases", while N plans a new `LibrarySchemaV3Test`. | There is **one `DB_VERSION = 3`**. `ADDED_COLUMNS` holds v2 `quotes.style` plus **nine v3 entries**: quotes ×3, bookmarks ×3, books ×2, and `book_prefs.return_mark` (`ADD_RETURN_MARK`). Fresh `CREATE_*` statements carry every column, and `CREATE_BOOK_PREFS` ends with `return_mark TEXT`. `CREATE_LOOKUPS` comes before its indexes. **Rule: no `CREATE_INDEXES` entry names an `ADDED_COLUMNS` column, in `ON (…)` or in a partial `WHERE`.** The two partial memo indexes use v1 `note` only. `UPGRADE_SWEEP` runs in `onUpgrade` after the ALTERs when `oldVersion < 3`. There is one test file, `data/LibrarySchemaV3Test`; **[Δ]** `LibrarySchemaV2Test` needs behavioural edits, not only compile ones: it asserts `DB_VERSION == 2`, `upgradeStatements(1) { withStyle } == []` and `upgradeStatements(2) { error("not needed") } == []`, all false (the last one throws) under v3. P0 moves those three cases into `LibrarySchemaV3Test` with v3 expectations and keeps V2Test's shape, idempotence and SQLite-version cases. `DataLimits` (MetaInfo.kt) gains CHAPTER 200, WORD 200, CONTEXT 300, APP 100 and QUOTE_STYLE_MAX 15 in phase 0, because the contract test asserts them. | P0 (schema, test); DA-C (`LibraryDb` runs the sweep, `BookPrefs`, backups) |
| **C2** | **The ⋮ fix exists three times.** The tree has `FastScrollEdge` (24/96 dp, x→0, intercept only). N H0 has `ui/kit/InkTouch.kt` (`FastScrollGuard` 12/56 dp, inset-aware, x→zoneLeft−1, intercept **and** touch, grid `OUTSIDE_OVERLAY`, `CardButton`). N W1 turns off the fast scroller in paged mode, and U polish 14 adds thin thumb drawables. | **H2 replaces the tree's version with N §3 exactly.** It creates `ui/kit/InkTouch.kt`, deletes `FastScrollEdge`, `LibraryListView` and `LibraryGridView` from `LibraryViews.kt`, and rewrites `FastScrollEdgeTest` as `ui/kit/InkTouchTest`. Why N's constants win: (a) shifting to x = 0 makes a GridView's `onTouchDown` pick **column 0** whenever no child consumes the DOWN; (b) the tree skips `onTouchEvent`, so a DOWN on a non-clickable pixel of a card (e.g. "34%") still reaches `FastScroller.onTouchEvent` and seeks; (c) a 96 dp claim breaks N's grid invariant for the 80 dp 그리드 cells. **Invariant: no clickable row pixel at x ≥ W − 12 dp.** The tree's 16 dp 요약 gap already satisfies it. Phase 0 appends N §4.9.3's paging part to `InkTouch.kt`. Paged mode (the e-ink AUTO default) has no fast scroller. Scroll mode keeps the guarded one, drawn with U's thin drawables (visual only). | H2; P0 (paging part); LIB |
| **C3** | **Model and settings fields from four specs** touch the same frozen files: `ReaderSettings`, `Settings`, `SettingsJson`, `UserStyles`, `Models`. | These are independent fields and keys, merged into one pass (§3.1–§3.6). There are three JSON test files: `SettingsJsonR3Test` (S + A), `SettingsJsonStatusTest` (U) and `SettingsJsonNotesTest` (N). | P0 |
| **C4** | **Margin migrations.** S has `SideMargin` (marker `r.marginBase`, legacy 18/18). A has `VerticalMargin` (marker `r.marginBaseV`, legacy 16/16) on the same "40 = 0" scale. S §2.5 expects `LayoutKeysTest` values 36→80 / 648→560 on the old geometry. | The markers stay **separate** (independent migrations, A §3.1). Both objects live in the new **`settings/Margins.kt`** (the prototype's file). Defaults are 40/40/40/40. `LayoutKeysTest` takes **A §6.4's numbers** (80, 80, 560 × 1280), which supersede S §2.5 and U §8.1. `UserStyles` writes both `marginBase` and `marginBaseV`. "설정 초기화" and "기본값 복원" give 40 on all four sides. Default users get one recount per book from the width change, and none from height: A §2.6 shows the same 1280 px box as R2. | P0 |
| **C5** | **Footer and band geometry.** U §5.1 has the bands reserve box height, with NONE↔item = relayout and `mbEff = max(mb, lane)`. S has the bands fixed in the viewport plus `fitFooter`. A (U2) puts the bands inside the margins, makes every status change a repaint, and adds `StatusFit` shrink-then-hide. | **A wins.** `LayoutKeys.geometry(s, viewW, viewH, density)` loses `statusPx`, `STATUS_BAND` is deleted, and `layoutPart` normalises the 6 slots, `progressBar` and `statusFontSizeSp`. U keeps its `StatusDecor`, `StatusModel`, `StatusMath`, `ProgressMath` and per-version `drawBand` cache. `drawStatus` follows A §2.3 (fit per band; lane = `StatusFit.lane(bottom margin)`), and `drawProgress` takes the lane height. **S's `fitFooter` and `FitFooterTest` are dropped**: `StatusDecor` has no footer string and `drawBand` is already allocation-free. **R2's `FooterFit` and `FooterFitTest` are deleted** with the legacy footer path; any still-relevant cases move to `StatusMathTest`. Voided: U §5.1 geometry and table, U §5.5 "NONE ↔ item relays out", the U §5.7 relayout row, U CI `10b` "the page relaid out once" (now: the text is pixel-identical, §5.3), and the U §8.1 `LayoutKeysTest` rows (replaced by A §6.4). `StatusFit.LANE_DP` refers to `ReaderSettings.PROGRESS_LANE_DP`. **2026-10-05 (user):** replaced by the bands' own places (note at the top): the geometry takes `StatusBands` heights, `layoutChanged` compares them (`bandsChanged`), shrink-then-hide and `fitsDp` are gone. | P0 (geometry, `layoutPart`, `StatusFit`); E2 |
| **C6** | **`PageDecor` and `Highlight` are changed twice in `render/Render.kt`.** U gives `PageDecor(highlights, bookmarked, status, statusVersion)` and drops header/footer strings. N adds `Highlight.style`. | Both land in P0, with default parameters. | P0 |
| **C7** | **The `ReaderPanels` frozen block.** U adds `StatusSampleHost`; N adds `NotePlaceHost`, plus `PageThumbsHost`, `ThumbCell` and `ThumbBatch` for W2. | All of them land in P0. W2 needs no second contract step. | P0 |
| **C8** | **Instance state.** N §6.1 adds `jump_done`. R §4.2 adds `rp.book/section/offset/at` and a by-id intent rebuild. | **R supersedes `jump_done`**: a restored reader rebuilds a by-id intent with no jump extras. N adds only `rp.peek` (restores `peekUntilTurn`). | H1; RC-A (`rp.peek`) |
| **C9** | **`DeviceClass` is shared** by S (scroll STEP/SMOOTH), U (`DeviceLight.looksEink`) and N (`QuoteLook` AUTO, `ListPaging` AUTO, `CardButton` pressed state). There are three probe call sites, and U fix 4 bans matching the codename "comet" (Pixel 9 Pro Fold) and brand-only Hisense. | One file, `render/DeviceClass.kt` (E2). P0 lands `einkByBuild` (U fix-4 rules: maker by MANUFACTURER/BRAND, Hisense by model A5/A7/A9/Touch, never by device codename) and `stamp` **complete** (pure), `cached()` (stamped pref, else `einkByBuild` ? true : null) and `probe()` = `einkByBuild` without a write. It **never throws**. **New in this plan:** `fun probeAsync(context: Context, onDone: ((Boolean) -> Unit)? = null)`, single-flight on a private daemon thread, a no-op when `cached != null`, with `onDone` posted to the main looper. Call sites: reader `afterOpen` (always; this replaces S's scroll-only fallback and N §6.6's probe), library after its first frame (N §10.5), and the "넘기는 방식 → 스크롤" choice in the popup, the settings page and the ⋮ menu (S §1.2). Every consumer reads `cached()`. | P0 stub; E2 body |
| **C10** | **Backup payload.** S adds `origin`/`summary` before `books` and a snapshot hash; U adds `returnMark` in `book_prefs`; N adds quote style/place, lookups, `reviewAt`, `missingAt`, merge rules and placeholders. | One owner (DA-C) for `Backup.kt`, `BackupJson.kt` and the new pure `BackupMerge.kt`. `BackupJson.VERSION` stays 1, and every field is optional both ways. The header fields (`version, createdAt, origin, summary`) are written before `books`. S's snapshot hash covers every N/U field automatically. | DA-C |
| **C11** | **Dropped vs transient keys.** U: `DROPPED_KEYS` = legacy status keys + `a.pinChrome` + `reader.brightnessCollapsed` + `a.brightnessDevice`. S: `TRANSIENT += installid, restoreoffer, backupauto, deviceclass`. R: `reader_resume` and U's `reader_light` are separate prefs files that never enter the JSON. | All of them apply. There is no Android Auto Backup rules file (S §3.1). If one is ever added, exclude `reader_light.xml`, `reader_resume.xml` and the `deviceClass`/`installId` keys. | P0 |

### 1.2 Engine and rendering

| # | Conflict | Decision | Applied by |
|---|---|---|---|
| **C12** | **`TypesetPass` is edited by S and A.** S adds `pageLead` bookkeeping at 3 `K_EMPTY` drop sites plus before `val top`. A's `commit()` → `place(anchored)` restructure adds a forced `emitPage` and a **4th** drop site; A also changes `decideCut` for PARAGRAPH. | One agent (E1). **Order:** apply `wave2/proto/engine.diff` first (golden-verified), then S §1.3's bookkeeping **inside `place()`**, including the 4th drop site (A §5.3, last bullet). `LayoutGoldenTest.layoutOutputMatchesTheGoldenHash` must stay green unchanged (`ALGO_VERSION` stays 1). `lead` is not hashed. `StitchTest` adds anchor ∈ {−1, random} × `pageBreak` ∈ {LINE, PARAGRAPH}, and the reference stays the un-anchored H = 10⁷ layout. | E1 |
| **C13** | **Highlight drawing.** S says the paged `draw()`/`drawLine()` stay byte-identical, and `drawBody` shares `drawHighlights`. N adds two passes (fills, then lines), per-style paints, `QuoteLook` and `drawQuoteLine`/`DashMath`. U changes `drawStatus`, lazy highlight lists and `sameDecor` + `statusVersion`. Scroll per-page highlight caches and thumbnails draw highlights too. | S's byte-identity applies to **S's own additions** only. The paged `draw()` changes only inside `drawHighlights` (N §8) and `drawStatus` (U §5.4 + A §2.3). `drawLine` stays untouched, and `drawBody` and the thumbnails call the same `drawHighlights`. There are no strip draws (design C's `drawStrip` was rejected in S): scroll uses `drawChrome`, `drawBody` and `drawOverlay` only. Change detection is **one pure `reader/DecorDiff.same(a, b)`** comparing highlights (start, end, kind, **style**), `bookmarked` and `statusVersion`. Removing `OWNER_JUMP` or `OWNER_SEARCH` also calls `scroll?.onHighlightsChanged(section)`. Thumbnails use `PageDecor(status = null)` and never draw transient owners (N §12.6). | E2; RC-A |
| **C14** | **Status bands in scroll mode.** S passes `drawChrome(…, contentHeight, viewWidth, viewHeight)`; A fits the bands into the margins. | `drawChrome` calls the same fitted `drawStatus`, and the bottom margin is `viewH − (top + ch)`. There is no mode branch in the status code. | E2 |
| **C15** | **Page counts.** S says "the count path is untouched". A counts the anchor section anchored in-session, masks it from the cache (`CountSaves.maskAnchor`) and adds at most one un-anchored count. N thumbnail labels and U status labels read `counts.globalPage`. | A applies. All in-session labels (footer, seek bar, TOC, thumbnails, return strip) come from the one anchored generation, so they agree. The cache never stores an anchored count, and the key is unchanged. Accepted: ±1 page between sessions in the anchor section (A risk 2). | RC-P |

### 1.3 Reader

| # | Conflict | Decision | Applied by |
|---|---|---|---|
| **C16** | **The open path.** N resolves a jump target, marks `OWNER_JUMP` before `showPage`, then calls `returnNav.onJump(saved)` and sets peek. R restores a saved place ahead of the DB row. A computes the start before publishing the session, anchors the generation at it and uses `AnchorMath.pageFor`. S uses `showAt` with TOP/CONTEXT placement. | The merged algorithm is §1.6.1. **Decision:** a note jump opens with **natural pagination**: generation anchor null, page = `pageForOffset(target)`, `anchor` = page start (A's JUMP rule), and CONTEXT placement in scroll mode for a mid-line target. Anchoring at the note would force a short page before every note visit. Restored and normal opens are anchored at their start (A §5.6). Precedence: restored place > note jump > TXT fraction remap > DB row. | RC-A |
| **C17** | **What the anchor is after a JUMP.** Today it is the exact target. A makes it the page start (TOC, search, link, go-to, return point, bookmark, TTS follow). S (scroll) recomputes it as the first half-visible line. N's mark is independent. | A in paged mode, S in scroll mode. `ReturnHost.currentPosition()` = page start (paged) or the virtual page start (scroll). Accepted: a font increase right after a mid-page search hit can push the hit to the next page (A risk 5). | RC-A |
| **C18** | **Pinned chrome and page resizing.** U §2.6 deletes pinned chrome and renames `applyPinnedArea` → `applyPageInsets`. A §5.1 adds `InsetsGate` around the insets listener. S has two stale lines ("Close unpinned chrome", "pinned chrome" in the relayout parity row). | H4 lands `InsetsGate` first and calls the existing `applyInsets(i)`. P0's fallout sets `app.pinChrome → false`. RC-A performs U §2.6's full removal and makes `applyInsets(i)` store the insets and call `applyPageInsets()` (insets only). The S lines read "close the chrome" and drop "pinned chrome". After this, **the PageView size depends only on the gated system insets.** | H4; P0; RC-A |
| **C19** | **`onTrimMemory`.** R: `UI_HIDDEN` (every visit to recents) drops the images, so the condition changes. S adds `scroll?.onTrimMemory()`. N W2 clears the thumbnail LRU at `RUNNING_LOW` and above. | One gate: `val low = level >= TRIM_MEMORY_BACKGROUND \|\| level == TRIM_MEMORY_RUNNING_LOW \|\| level == TRIM_MEMORY_RUNNING_CRITICAL`. When `low` is true, run `session?.trimMemory()`, `scroll?.onTrimMemory()` and `thumbs?.clear()`. | H1 (the condition); RC-A |
| **C20** | **`ReaderActivity` lifecycle hooks come from 4 specs** (R marker and state, S presence and auto-backup, U light and return mark, N probe, places and anchor check). | The fixed orders are in §1.6.2. | H1; RC-A |
| **C21** | **`viewPart()` and `applyAppSettings()`.** U drops `a.pinChrome` and adds `a.brightnessDevice`. S adds `a.readMode` and `a.scrollStyle`. N needs `a.highlightLook` to repaint live. U makes `LightController` the only writer of `page.brightnessSwipe`. | `viewPart` = today's list − `a.pinChrome` + `a.brightnessDevice`, `a.readMode`, `a.scrollStyle`, `a.highlightLook`. The volume keys are read live and need no entry. The `applyAppSettings` order is in §1.6.2. | P0 (drop pinChrome); RC-A |
| **C22** | **`ReaderMenus` ⋮ items.** S adds "스크롤로 보기 / 페이지로 보기" and "자동 스크롤". U adds "북마크 추가/삭제" when the top-row bookmark is hidden (< 352 dp). N adds "독서 노트" after "내 리뷰", and in W2 "페이지 썸네일" after "페이지 이동" (hidden in scroll mode). | Final order: 목차 … 페이지 이동 · (W2) 페이지 썸네일 (페이지 미리보기, in groups since the 2026-10-04 review: §1.6.3) · (narrow) 북마크 추가/삭제 · 스크롤로 보기/페이지로 보기 · 자동 넘김/자동 스크롤 · … 내 리뷰 · 독서 노트 · …. Existing items keep their places. | RC-A |
| **C23** | **Return point vs TOC/search/note jumps.** U ReturnNav rules ★1–★5; N `onJump(saved)` after an open-at-note; A anchors. | ReturnPoints stores offsets, so anchors don't affect it. For an open-at-note, `onJump(saved)` runs in the **same main-thread message** as `showPage` (one e-ink update). `onNewIntent` with a jump for the same book → `goTo(remember = true)` → the normal chain rule. | RC-A |
| **C24** | **Chip placement vs U2 bands.** U: `bottomMargin = insets[3] + 12 dp + 4 dp`. A: the footer text band now sits in the bottom margin. | Keep U's formula. The chip is an overlay: it may cover the footer band for up to 2 turns and never resizes the page (U6a). | RU; RC-A |
| **C25** | **`ReaderHost` KDoc.** S covers the scroll window and motion; U covers `goTo(remember)` = return point. | Both land in P0 (KDoc only). | P0 |

### 1.4 Extras, UI and CI

| # | Conflict | Decision | Applied by |
|---|---|---|---|
| **C26** | **The selection popup.** U polish 13 makes one row of 5. N adds the 인용 swatch + ▾, long-press → palette, a first overflow item "색 골라 인용…", a palette row for an existing quote, lookup recording and an optimistic quote highlight. A deletes the header-band term in the `origin()` fallback. U's fallout says `showHeader → hasHeader`. | One agent (EX-S). Build U13's structure and `SelectionActions.split` first, then N §7.1. **Overflow order:** 색 골라 인용… · 공유 · 문단 · 검색 · 웹 검색 · 여기서 읽기 · 문구 지우기 (TXT only). **Existing quote:** palette row above [복사 · 메모 · 인용 삭제 · 사전·번역 · ⋮]. **Origin fallback:** A's version, with **no header term at all**. | EX-S |
| **C27** | **The popup "페이지 넘김" section.** S puts "넘기는 방식" first. R (H3) replaces the volume switch with a 3-way "볼륨 키" chooser. U keeps `MAIN_ROWS = 9` above the fold. | 넘기는 방식 · 화면 터치 · 볼륨 키, all under 더보기 (§1.6.3). **Superseded 2026-10-04:** the popup is the quick rows; these rows are on 설정 → 넘기기·터치·키 (68aa271; was 넘김·화면 설정), the volume keys as one "볼륨 키" chooser again (§1.6.3). | H3; EX-P |
| **C28** | **Settings rows** (popup, PageTurningPage, MainPage, BackupPage, AboutPage, LookupPage) are each added to by 3–5 specs. | The final ordering is §1.6.3. | EX-P; SET |
| **C29** | **Library list-mode labels.** The tree has 목록/간단히/표지; N has 전체/요약/썸네일/그리드 plus `COVERS`. | N applies in P0 (enum labels; `COVERS` added; unknown → LIST). CI steps that tap "목록"/"표지" are updated by CI. **2026-10-04 (a3b8826):** the labels are 자세히 · 간단히 · 큰 표지 · 작은 표지 (stored names unchanged); CI follows. | P0; CI |
| **C30** | **CI shot names collide.** S uses 60–69 and 70–73 (restore). R uses 70–78. A uses 72–77. N uses 41–46 and 80–93. U uses 10b, 13b–13h, 14b and 14c; R U5 uses **14c** too. | Unique numbers are assigned in §5.3: A → **52–57**, R U5 → **14d**, S restore → **95–98**. S 60–69, R 70–78, N 41–46/80–93 and U keep theirs. The S block ends with a restore-PAGED step (`69b`). The S restore block runs last, because `pm clear` wipes everything. | CI |
| **C31** | **Two pixel-compare helpers.** U: `rawshot` + `tools/ci/raw_equal.py` (exact rows of raw RGBA). R: `tools/ci/same_page.py` (PNG, skips the top 8 % and bottom 12 %). | Keep both, owned by CI. `raw_equal` is for "nothing moved" checks inside one process run (13b, 13c, 57; 2026-10-05: 10b/52/53: the first lines stayed (`contenttop`) with `first_is`; `--uniform` for rows of paper only, 10b_margin). `same_page` is for "the same page came back" across restarts and kills (71–78). | CI |
| **C32** | **Test-file names.** `FitFooterTest` (S), `FooterFitTest` (tree), `LibrarySchemaV2Test+v3` (U), `FastScrollEdgeTest` (tree). | `FitFooterTest` is not created. `FooterFitTest` is deleted (E2). `LibrarySchemaV3Test` is the single file. `FastScrollEdgeTest` becomes `ui/kit/InkTouchTest`. | E2; P0; H2 |
| **C33** | **Brightness strip vs scroll gesture.** | Unchanged rule (S §1.12). `LightController` is the only writer of `page.brightnessSwipe` (U §2.3). | RU; RC-A |
| **C34** | **N W2 thumbnails depend on S `drawChrome`/`drawBody` and A's anchored generations.** | W2 starts after E2 and RC-P are merged, **[Δ] and after RC-A's and EX-N's own W1 work is merged** (W2 edits `ReaderActivity`, `ContentsDialog` and `ReaderMenus`, which those lanes are still changing in W1). Thumbnail layouts go through `session.layout` of the current (anchored) generation, so thumbnail pages equal reader pages. The key adds `paintVersion` + `QuoteLook.generation` (N §12.6). | W2 (RC-A, EX-N) |
| **C35** | **Library `onCreate`.** S puts `InstallState.ensure` first; R adds the `startMode` decision; U calls `restoreIfStale` after the first list; N probes after the first frame. | Order: `InstallState.ensure` → `startMode` → (RESUME / OPEN_LAST: `startOpenLast`) or `ensureUi`. After the first list is drawn: `DeviceLight.restoreIfStale` (IO), then `DeviceClass.probeAsync`. `refreshVisible`: the restore offer check (S §3.4), then the idle backup wait (S §3.2). **[Δ]** RESUME / OPEN_LAST draw no list, so `restoreIfStale` does not run there. That is safe only because a reader keeps an existing `pending` original and never re-records it (brightness.md §4.2 crash rule). RU keeps that rule and adds a `LightCurveTest`/`DeviceLight` case for it. | H1; LIB; RU |

### 1.5 Voided or edited spec lines (the owning lane applies the PLAN version)

- S §1.1 gate "paged shots differ only by the new margins" → "…only by the margins (S, A), the default footer (none),
  the progress line and the new chrome (U), and quote colours where quotes exist (N)".
- S §1.8 `fitFooter` and S §1.14 `FitFooterTest`: dropped (C5).
- S §1.10 `onScrollStart` "Close unpinned chrome" → "close the chrome". S §1.12 relayout row: drop "pinned chrome".
- S §1.15 shots `70_restore_offer…73_backup_page` → `95…98` (C30).
- **[Δ]** S §1.15 `14_reading_settings` "the first row is 넘기는 방식 · 페이지 넘김" → 넘기는 방식 sits under 더보기 (C27,
  §1.6.3); the main section is U's 9 rows.
- **[Δ]** A §6.6 / §8 and H4 "RAPerf shows no relayout line" → the DEBUG `RAPerf show …` line added by H4 (baseline
  fact 9); A §6.6 `74`/`76` "same first word via find_node" → the logged page start (§5.3 rows 40, 42).
- S §1.11 / §1.10 probe fallback in `applyReadMode`/`afterOpen` "in scroll mode" → always, via `probeAsync` (C9).
- S §2.5 `LayoutKeysTest` numbers → A §6.4 (C4).
- U §5.1 in full, the U §5.5 relayout sentence, the U §5.7 relayout row, the U §8.1 `LayoutKeysTest` rows and U §8.2
  `10b` "relaid out once" (C5).
- U §1.10 fallout `SelectionController`: `showHeader → hasHeader` → delete the term (A §2.5).
- N §6.1 `jump_done` (C8). N §3.2–§3.3 H0 file list: reconciled with the tree (C2, H2). N §6.6 step 4 probe →
  `probeAsync` in `afterOpen` (C9).
- N §17 shot 45 "exactly 4 whole cards" → N's own [Δ] wording (no cut card except the one leading page 2).
- A §6.6 shot numbers 72–77 → 52–57. A §7 "phase 0 touches only the `LayoutKeys` signature" → P0 lands A §2.2 in full
  (C5, §3.9).
- R §9.6 shot `14c_volume_mode` → `14d_volume_mode`.

### 1.6 Merged algorithms and orders

#### 1.6.1 `ReaderActivity.startOpen` (RC-A; merges R §4.2, N §6.1, A §5.5, S §1.10)

```kotlin
// main thread, after the book row b, document d, effective settings eff and the session s exist
val place = restoredPlace; restoredPlace = null                              // R: consumed once
val jump = if (place == null) ReaderJump.from(intent) else null               // N: 6 getExtra, no I/O (a restored intent has none)
val sigPlain = LayoutKeys.textSignature(eff, d.format, b.encoding)
val noteSig = NoteSig.of(sigPlain, b.sizeBytes)                               // N [Δ]
val remap = TextPositions.remapFraction(storedPos, sigPlain, b.posSection, b.posOffset, b.progress)
val saved = if (remap != null) s.counts.locateFraction(remap) else DocPosition(b.posSection, b.posOffset)
val kept = place?.let { ReaderRestore.start(it, b.id, b.lastReadAt, remapped = remap != null) }   // R
val target = if (kept == null && jump != null)
    ReaderJump.resolve(jump, noteSig, s.sectionCount) { f -> s.counts.locateFraction(f) } else null // N
val start = kept ?: target ?: saved
val sec = start.section.coerceIn(0, s.sectionCount - 1)
anchor = DocPosition(sec, start.offset.coerceAtLeast(0))                      // A: before publishing the session
s.listener = sessionListener; session = s; adopted = true
viewReady.await()
val (vw, vh) = pageTargetSize()
s.setViewport(vw, vh, if (target != null) null else AnchorSpec(sec, anchor.offset))   // PLAN C16
s.startCounting(COUNT_DELAY_MS)
val l = s.layout(sec) ?: …error path as today…
val off = start.offset.coerceIn(0, l.content.length)
val idx = if (target != null) l.pageForOffset(off) else AnchorMath.pageFor(l, off)
if (target != null && jump!!.end > jump.offset && target == DocPosition(jump.section, jump.offset) &&
    JumpAnchor.matches(l.content.text, off, jump.anchor))
    putOwnerHighlight(OWNER_JUMP, sec, Highlight(off, min(jump.end, l.content.length), SEARCH))       // N: no redraw call
preloadImages(s, l, idx)
if (session !== s) return
// Kind stays OPEN (the RAPerf open trace; no cadence turn). Paged: a note opens at its page start (anchor = page start,
// A's JUMP rule). Scroll: the branch gets the exact offset and uses CONTEXT placement (25 % down) when the target is
// mid-line, TOP otherwise (S §1.10 JUMP rule, applied to an open at a note through a private `openedAtNote` flag).
showPage(sec, l, idx, Nav.OPEN, anchorOffset = if (target != null && scroll == null) -1 else off)
if (target != null) {                                                          // same main-thread message: one e-ink update
    if (!isOnCurrentPage(saved)) returnNav.onJump(saved)                       // U §3.5 (the chip shows with the chrome hidden)
    peekUntilTurn = true                                                       // N §6.2
    setIntent(ReaderJump.strip(intent))
}
if (jump != null && target == null) toast("노트가 있던 곳을 찾지 못했습니다")
```

`onNewIntent`: for the same book with a jump, run N §6.3 (`goTo(resolve…, remember = true)`, mark, anchor check). A
different book runs `closeCurrentBook()` and then `startOpen(intent)`.

#### 1.6.2 Lifecycle orders (RC-A; H1 lands R's lines first)

- **`onCreate`:**
  1. `ReaderPresence.inFront = true` (S)
  2. `ReaderWindow.setup`
  3. `buildViews()`: `light = LightController(lightHost)`, `returnNav = ReturnNav(this, returnHost)`,
     `chrome = ReaderChrome(this, chromeActions, returnNav.dock, light)`, `light.attach(chrome)`, add
     `returnNav.chip` to root, insets listener through `InsetsGate` (H4)
  4. `light.onCreate()` (U)
  5. R's restored-place intent rebuild
  6. `startOpen(start)`

  **No prefs write before the first page** (R's marker, S's `ensure` and U's cleanup all happen later or elsewhere).
- **`afterOpen`** (after the first page, main thread; each IO step is launched, never awaited):
  1. `ResumeState.opened(id)` (R)
  2. `InstallState.ensure(this)` (S)
  3. `light.afterFirstPage()` (U)
  4. `DeviceClass.probeAsync(appCtx) { QuoteLook.update(app.highlightLook, it); scroll?.onDeviceClass(); refresh once if QuoteLook.generation changed }` (S + N)
  5. return-mark load (U §3.3, guarded by id and session)
  6. `if (settings.shows(EPISODE)) scheduleEpisodes()` (U)
  7. when a jump was consumed: the N §6.4 anchor check (capped at 48 sections / 3 M chars)
  8. the note-place backfill runs on `reloadAnnotations` delivery (N §6.5, 64 rows per `post`)
- **`applyAppSettings`:**
  1. `light.onAppSettingsApplied()` (U; the only writer of `page.brightnessSwipe`)
  2. `QuoteLook.update(app.highlightLook, DeviceClass.cached(this))`, then one `refreshDecor()` if the generation changed (N)
  3. `applyReadMode()` (S; returns at once when PAGED and `scroll == null`)
  4. `applyPageInsets()` (U)
- **`onResume`:** `light.onResume()`; refresh the cached tz / 24 h / battery; `refreshDecor(onlyIfChanged = true)` (U
  §5.3). **`onPause`:** `scroll?.stopMotion()` (S) → position save unless `peekUntilTurn` (N) →
  `ResumeState.paused()` (R) → `light.onPause()` (U).
- **`onStart`:** `AutoBackup.cancelScheduled()`. **`onStop`** (not during a configuration change):
  `AutoBackup.schedule(appCtx, 5_000) { ReaderPresence.inFront }` (S; both overrides are new).
- **`finish()`:** `ResumeState.clear()`, then `super.finish()` and the existing no-animation transition.
  **`onDestroy`:** `if (isFinishing && !isChangingConfigurations) ResumeState.clear()` (R);
  `light.onDestroy(isFinishing)` (U); thumbnails and scroll `detach`.
- **`onSaveInstanceState`:** R's `rp.*`, plus N's `rp.peek`.
- **`onTrimMemory`:** the C19 gate.

#### 1.6.3 Final settings rows

**2026-10-04, at the user's request (68aa271, 79cd1a5, a3b8826): plain Korean everywhere, and 설정's groups easy to
tell apart.** One look on every settings page: section headers **18 sp** bold black (larger than the 17 sp row titles)
on the rows' 16 dp start line, right on their rows (the first row's own 10 dp padding is the only gap); above every
section but a page's first, a 20 dp gap and a full-width black rule of 1 dp, at least 2 px (`groupLinePx`), then 16 dp
to the header: about 30 dp of white from the last row's text, so a group's pause is far larger than a row's (all drawn
by the header itself, `LinearLayout.section`; this reverses UI_SPEC polish 12's spacing-only groups). The rows inside a
group have no lines. A black chevron on a row that opens another screen, a grey drop-down on a row that opens a chooser;
notes 14 sp grey, wrapped between words, inside the section they explain; the few warnings black; a disabled row keeps
its reason in #555; rows that depend on a switch are hidden while it is off; stepper buttons are "<title> 줄이기" /
"<title> 늘리기". Choices read "값 (설명)" and only choosers mark "(기본)"; quotes are ‘’, sentences 합니다체; no "="
in a sentence ("왼쪽 1/3은 이전, 나머지는 다음", "아래 키로 다음 페이지"). **Page words:** a counted page or a page number
is "쪽" ("3쪽으로", "10쪽마다", "한 쪽보다 긴 문단에만", "1–N쪽"); the screen page as a thing is "페이지" ("다음 페이지",
"페이지 이동", "페이지 넘김", "이 페이지 고정", "페이지 미리보기"). Sizes are the library card's ("3.4MB", `LibraryText.formatSize`)
everywhere. The ⋮ menus have groups too (`MenuItem.groupStart`: the same black rule at the top of the row, inside its
48 dp): the reader's 페이지 이동 · 페이지 미리보기 · (북마크) | 스크롤로 보기 · 자동 넘김 · 화면 새로고침 | 독서 노트 · 책
정보 · 내 리뷰 · 즐겨찾기·컬렉션 | 설정; the library's 정렬 · 보기 · (새 컬렉션 / 휴지통 비우기) | 파일 열기 · Wi-Fi로 책
받기 | 지금 스캔 · 스캔 폴더 추가 | 설정.

**Quick options ⚙ (`ReadingSettingsPopup`; 672e85d replaced the 9-row popup and its 더보기, 68aa271 brought the
margins back).** Only what is changed while reading, no scroll, nothing that expands; 48 dp rows and buttons, 384 dp in
all (`PopupGeometry.QUICK_HEIGHT_DP`, under the Comet's 56% cap):

0. 전체 읽기 설정 › · [닫기] (one 48 dp top bar; opens 설정 → 읽기 설정 through the reader, with the open book, `OpenBook`)
1. 글자 크기
2. 굵기
3. 줄 간격
4. 문단 간격 (kept at the user's request; GPT's review proposed only four rows)
5. 좌우 여백 (−40 … +40 in steps of 2, "0" = the default margin; a step turns 여백 사용 on: `QuickFields.withSide`.
   While 여백 사용 is off both steppers show the margin the page has, the minimal 4 dp ("−36", `QuickFields.sideUi`),
   and the first step starts there and leaves the other axis at 4 dp: nothing moves but the margin stepped)
6. 상하 여백 (the same, `QuickFields.withVertical` / `verticalUi`)
7. 글꼴 (drop-down list)

The seven rows edit the same global `ReaderSettings` fields as 읽기 설정, with the same steps, ranges and values; a change
is applied onto the saved settings (`QuickFields.onto`), steppers debounced 250 ms. Nothing else was dropped: everything
else lives in 설정.

**설정 main list (`MainPage`): small groups, one kind of row each** (the review of 2026-10-04: the old 읽기 block of
eight look-alike rows filled the first screen without a break, and 서재 mixed pages, choosers and a switch).

| Group | Rows in order |
|---|---|
| 읽기 화면 | 이 책의 TXT 정리 › (opened from a TXT book only) · 읽기 설정 › · 글꼴 관리 › · 화면·밝기 › · e-ink 새로고침 › |
| 조작·기능 | 넘기기·터치·키 › ("터치: 좌우 넘김 · 볼륨 키 · 지정 키 2개", "스크롤" first in scroll mode) · 듣기 설정 › · 사전·번역·검색 › |
| 서재 | 정렬 · 보기 (자세히 / 간단히 (한 줄) / 큰 표지 (3열) / 작은 표지 (4열), the library's own chooser texts) · **목록 넘기기** (스크롤 / 한 화면씩; N) · 시작할 때 읽던 책 열기 (R §4.8; "끄면 서재부터 · 앱이 강제로 닫혔을 때는 그 책으로") |
| 책 가져오기 | 책 스캔 › · Wi-Fi로 책 받기 › |
| 기타 | 읽기 기록 › · 백업·복원 › · 캐시 비우기 · 설정 초기화 (keeps the TXT defaults, scan folders, assigned keys, library sort and view, 목록 넘기기, 자동 백업, 찾아본 단어 기록, 기기 밝기 직접 조절, web search and voice: `SettingsReset`; summary "읽기 · 화면 · 듣기 설정을 기본값으로") · 정보 › |

Opened from the reader (`OpenBook.info` set; memory only, no IO) the list has no 서재 and 책 가져오기 groups, no 읽기 기록,
no 백업·복원 (a restore under an open book is unsafe) and no 캐시 비우기 (no walk over the open book's cache): 읽기 화면 ·
조작·기능 · 기타 (설정 초기화 · 정보); a TXT book gets 이 책의 TXT 정리 first.
Whatever needs those rows opens 설정 from the library (the CI does: 45/46, 89p, 98).

**설정 → 읽기 설정 (`ReadingPage`; the main list's 읽기 설정 and the quick options' link):**

| Section | Rows in order |
|---|---|
| (note) | 모든 책에 적용됩니다. |
| 스타일 | 추천 스타일 (웹소설 = 마루뷰어 화면 · 나눔명조, incl. its colours; 전자책; 종이책; "기본" for the defaults' look, "직접 설정" when nothing matches) · 내 스타일 (saved styles, 현재 설정을 새 스타일로 저장…, 관리…; a style carries its 화면 색) · **화면 색** (흰 바탕 (기본) / 마루뷰어 (어두운 회색 바탕); a repaint, no re-layout) · **흑백 반전** (here since 68aa271; wins over 화면 색) |
| 글자 | 글꼴 · 글자 크기 · 굵기 · 글자 간격 |
| 문단 | 줄 간격 · 문단 간격 · 들여쓰기 · 정렬 · 줄바꿈 (단어 단위 / 글자 단위) |
| 여백·페이지 | 여백 사용 (off hides the next three) · **좌우 여백** · **상하 여백** · note · **페이지 나눔** (줄 단위 (기본) / 문단 단위 (페이지 아래가 빌 수 있음)) · 외톨이 줄 방지 |
| 파일 | 이 책의 TXT 정리 › (the reader's TXT book only) · TXT 정리 기본값 › · EPUB 출판사 스타일 |
| 다른 설정 | 모든 설정 › (the main list, without its library rows while a book is open; only when opened straight from the quick options, with no main list under it) |
| 되돌리기 | 기본값으로 되돌리기 (흑백 반전 and the TXT options kept; 화면 색 back to 흰 바탕) |

**설정 → 이 책의 TXT 정리 (`BookTxtPage`):** 본문: 인코딩 · 빈 줄 처리 · 줄 앞 공백 지우기 · 끊어진 줄 합치기 · 바꾸기
규칙 › | 챕터: 챕터 자동 인식 · 챕터 제목 강조 · 챕터 규칙 (정규식) (both hidden while 자동 인식 is off) | 기본값 (only while
the book has options of its own): 모든 TXT 책에 적용 · 이 책 설정 지우기. Each change is saved at once (BookPrefs, in
order); the reader takes the edits back in onResume (`OpenBook.take`): new TXT options are one re-parse however many rows
changed, a new encoding re-opens the book. **TXT 정리 기본값 (`TxtDefaultsPage`)** has the same 본문 · 챕터 rows for
every TXT book without options of its own.

Settings changes reach the open book once, when the reader is back in front (`onResume` compares
`Settings.reader.withTxt(override)` with what the session has): one re-layout, the first character kept.

**설정 → 넘기기·터치·키 (`PageTurningPage`, SET; with 화면·밝기 and e-ink 새로고침 it replaces 넘김·화면 설정, 68aa271):**

| Section | Rows in order | Source |
|---|---|---|
| 넘기기 | 넘기는 방식 (페이지 넘김 (기본) / 스크롤 (위아래로 읽기); SCROLL starts `probeAsync`) · 스크롤 움직임 (SCROLL only: 손가락을 따라 (기본) / 손을 떼면 이동) · note · 끝까지 읽으면 ‘다 읽은 책’으로 · 자동 넘김 간격 · note | S §1.2 |
| 화면 터치 | 좌우 넘김 · 어디든 다음 · 어디든 이전 · 위아래 넘김 · 직접 지정 (radio rows) · the preview (3×3 editor in 직접 지정) · 왼쪽 위 터치로 흑백 반전 · 오른쪽 위 터치로 북마크 · 다음·이전 바꾸기 (no **메뉴 고정**: deleted in P0) | U §2.6 |
| 스와이프·길게 누르기 | 좌우 스와이프로 넘김 (in SCROLL: "좌우로 밀면 한 화면씩") · 위아래 스와이프로 넘김 (**disabled** in SCROLL, `setRowEnabled`) · 길게 눌러 선택 · 길게 누르기 시간 (hidden while off) | S §1.2 |
| 버튼·키 | **볼륨 키** (one chooser: 아래 키로 다음 페이지 (기본) / 위 키로 다음 페이지 / 넘기지 않음 (소리 크기 조절); disabled with "키 지정에서 정함" while a volume key has another action; 키 지정 with a volume key and 다음/이전 페이지 sets the direction) · 페이지 키를 길게 누르면 · 키 지정 · the assigned keys · 키 테스트 | R §9.3–9.4 |

**설정 → 화면·밝기 (`ScreenPage`, new 68aa271):**

| Section | Rows in order | Source |
|---|---|---|
| 위쪽 상태 표시줄 | 왼쪽 / 가운데 / 오른쪽 (valueRows titled by the place only, the band is the header's; the chooser's title says both, "위 왼쪽"; it lists "쪽 번호 (12 / 3259)" with **챕터 쪽 번호 (2 / 32)**, the page within its chapter, right under it, and "배터리 (80)": the page draws the icon and the bare number) | U §5.5, A §2.7, R2 |
| 아래쪽 상태 표시줄 | 왼쪽 / 가운데 / 오른쪽 (the same) · 진행 막대 · 상태 글자 크기 (while a band shows text) · note · A's fit warning | U §5.5, A §2.7 |
| 화면 | 전체 화면 · 화면 켜짐 유지 · 화면 방향 · **인용문 색 표시** (with the swatch strip) · note | N §11 |
| 밝기 | 스와이프로 밝기 조절 · **기기 밝기 직접 조절** · **나갈 때 원래 밝기로** (only while the switch above is on) · **밝기 방식 다시 묻기** · **기기 조명 설정** › | U §4.6 |

**설정 → e-ink 새로고침 (`EinkPage`, new 68aa271 as "e-ink 화면", renamed in the review of 2026-10-04: too close to
화면·밝기 above it; the main row's summary "10쪽마다 새로고침 · 어두운 화면 5쪽마다"):** 자동 새로고침: 전체 새로고침 (N쪽마다
/ 끔) · 어두운 화면에서 · 새 챕터에서 새로고침 · 그림 페이지에서 새로고침 · the device's own ghost clearing and the
double-flash warning | 화면 모드: 화면 모드 · note · 고급 (folded: 새로고침 방식 · 깜빡임 길이 · note · 새로고침 시험 · 진단).

**Other pages (SET):** 듣기 설정 (음성: 목소리 · 속도 · 음높이 | 읽는 동안: 읽는 문장 표시 · 멈춤 예약 | 음성 엔진: 음성
엔진 설정 ›) · 사전·번역·검색 (웹 검색 | 사전·번역 앱 | **단어장**: 찾아본 단어 기록, N §11) · 책 스캔 (스캔 | 스캔할 폴더 |
제외할 폴더) · 글꼴 관리 (읽기 글꼴 | 글꼴 추가) · **백업·복원** (백업: 백업 파일 만들기 · the privacy note (it covers every backup) | **자동 백업** (S §3.8): 매일 자동
백업 · 지금 자동 백업 (while on) · 자동 백업 파일 지우기 | 복원: 목록에서 복원 · 파일에서 복원 · N §11's merge
note with the [Δ] sentence) · 정보 (version | 라이선스 | 문제 해결: 기기 정보·조명 진단, folded: the device lines with
**최근 종료** (R §4.7, API 30+) and **조명 진단** (U §4.6)).

---

## 2. Step H — hotfix-first (serial, after Gate 0, before phase 0)

Each item is small and independent of the three specs. They land **strictly in the order H1 → H2 → H3 → H4** (one agent
for all of H, or one agent per item run one after another): H1 and H4 share `ReaderActivity.kt`, H1 and H2 share
`LibraryActivity.kt`, and H3's settings rows sit on H1's `MainPage`/`AboutPage` edits. Frozen files (`App.kt`, the
manifest) are edited here with the lead's permission. **After H:** `tools/typecheck.sh`,
`tools/unittest.sh` and `tools/snapshot_contracts.sh`, then a `[screens]` run. **[Δ]** The H commit is a complete build
on its own (CI APK artifact), so the user gets U1, U5, the ⋮ fix and the dialog fix without waiting for W1. P0 starts
from that commit and does not wait for the user's verdict.

### H1 — U1: returning from recents shows the same book and page (R §4, §6, §7)

| File | Change |
|---|---|
| `reader/ResumeState.kt` (new) | R §4.1 verbatim (prefs `reader_resume`; `opened/paused/noteAttempt/clear/pending`; `activitiesCreated`; `MAX_TRIES = 2`) |
| `App.kt` | `ResumeState.init(this)` after `Settings.init(this)` |
| `reader/ReaderActivity.kt` | R §4.2: `onSaveInstanceState` (`rp.book/section/offset/at`); `onCreate` by-id restored intent (`restoredPlace`); `startOpen` uses `ReaderRestore.start` (`kept ?: remap ?: row`); `afterOpen` first line `ResumeState.opened`; `onPause` `ResumeState.paused()`; the existing `finish()` override gains `ResumeState.clear()`; `onDestroy` clears when finishing; the comment on the content-uri `setIntent` is corrected; **`onTrimMemory` condition** (C19) |
| `reader/ReaderMath.kt` | `object ReaderRestore` (R §4.3) |
| `ui/library/LibraryText.kt` | `enum StartMode`, `startMode(...)`, `shouldOpenLast` KDoc fix (R §4.4) |
| `ui/library/LibraryActivity.kt` | the `onCreate` `when (startMode…)` block; `startOpenLast(resumeId)` (R §4.4) |
| `AndroidManifest.xml` | `LibraryActivity`: `android:alwaysRetainTaskState="true"` (R §4.6) |
| `ui/settings/AboutPage.kt` | 기기 정보 → "최근 종료" (R §4.7) |
| `ui/settings/MainPage.kt` | "앱 시작 시 읽던 책 열기" subtitle (R §4.8) |
| `tools/ci/same_page.py` (new) | copy of `scratchpad/wave2/recents_ci/same_page.py` |
| `tools/ci/screenshots.sh` | the R §7 block, named per §5.3 (70–78), placed before the final logcat lines |

- **Tests:** `ui/library/LibraryTextTest` (`startMode_*`, R §6), `reader/ReaderMathTest` (`restoreStart_*`).
- **Accept:**
  - JVM green.
  - CI steps 70–78: every `top_is` and `same` prints PASS (today B, C, D and E fail).
  - RAPerf "open … first page" unchanged (one nullable field read).
  - `am start -n` CI starts still show the library (40_library_after is unchanged).

### H2 — N H0: the library ⋮ taps and the list never jumps (N §3, adapted to the tree, C2)

| File | Change |
|---|---|
| `ui/kit/InkTouch.kt` (new) | N §3.2: `FastScrollGuard` (GRAB 12, ZONE 56, `insetEndPx`), `TapSlop`, `InkListView`, `InkGridView`, `guardDown` (with `View.LAYOUT_DIRECTION_RTL` qualified), `CardButton` |
| `ui/library/LibraryViews.kt` | delete `FastScrollEdge`, `LibraryListView` and `LibraryGridView`; `BookCardHolder.btn` → `CardButton` (the ⋮ description becomes **"책 메뉴"**); `CompactRowHolder`'s ⋮ → `CardButton`, keeping the tree's 16 dp right gap |
| `ui/library/LibraryActivity.kt` | `InkListView` / `InkGridView`; grid `scrollBarStyle = SCROLLBARS_OUTSIDE_OVERLAY`; grid padding right 12 dp and `cellWidth` uses 8 + 12 |
| test `ui/library/FastScrollEdgeTest.kt` | deleted → new `ui/kit/InkTouchTest.kt` (N §3.4 cases) |
| `tools/ci/screenshots.sh` | step `41_library_more`, CHECK 41 and CHECK 41b (N §17), right after `01_library` |

- **Accept:** `InkTouchTest` green; CHECK 41 and 41b PASS; on the Comet, the four manual checks of N §3.4.

### H3 — U5: volume-key direction (R §9)

| File | Change |
|---|---|
| `reader/KeyMap.kt` | `VolumeMode`, `volumeMode`, `withVolumeMode`, `volumeBound` (R §9.5) |
| `reader/ReaderFormat.kt` | `volumeMode(m)`, `volumeModeShort(m)` labels |
| `reader/extras/ReadingSettingsPopup.kt` | `addPageTurning`: the switch → `dropdownRow("볼륨 키", …)` with 3 entries (R §9.2) |
| `ui/settings/PageTurningPage.kt` | "볼륨 키로 넘김" live summary; "볼륨 키 방향 반전" renamed and disabled when off or bound (R §9.3) |
| `ui/settings/SettingsPage.kt` | `View.setRowEnabled(enabled)` next to `setSummary` (later reused by S and U) |
| `ui/settings/SettingsLogic.kt` | `SettingsFormat.volumeSummary(app)` |
| `ui/settings/KeyNames.kt` | **[Δ] re-derived against R2's T1-4 editor** (baseline fact 8): R §9.4's `assign(app, code, next)` no longer exists; "키 지정" now captures a key and then asks for an action (`KeyAssign.bind(app, code, action)` → `keyBindings`). A binding on a volume key beats the direction switch, so R's G4 trap comes back through the new editor. Rule: `bind(app, VOLUME_UP or VOLUME_DOWN, NEXT or PREV)` **folds into the direction** (`KeyMap.withVolumeMode`: UP+NEXT or DOWN+PREV → `UP_NEXT`, else `DOWN_NEXT`). It removes NEXT/PREV bindings and learned entries for both volume keys and stores no binding. Any other action on a volume key stays a binding (`volumeBound` → both volume rows disabled with R's summary "키 지정에서 볼륨 키 동작을 정했습니다"). `normalizeVolume` also strips volume NEXT/PREV **bindings**. `entries`/`actionOf` never list a folded key. The action chooser's text for a volume key + 다음/이전 reads "볼륨 위 → 다음 페이지 (볼륨 아래는 이전 페이지)". Also: `fillKeys` hides folded volume keys, and `readerEffect` goes through `KeyMap.action` (R2's `readerEffectLabel` already shows bindings first; keep it) |

- **Tests:** `KeyMapTest.volumeModeRoundTrip`, **[Δ]** `KeyNamesTest.bindVolumeFoldsIntoDirection` (replaces
  `assignVolumeFoldsIntoDirection`: the four (key, NEXT/PREV) cases, old learned and bound volume entries stripped,
  `bind(VOLUME_DOWN, MENU)` stays a binding and makes `volumeBound` true),
  `KeyNamesTest.readerEffectFollowsBindings`, `LibraryTextTest` inverted + off → 0, `SettingsFormatTest` volume
  summaries.
- **CI:** `14d_volume_mode` (§5.3).
- **Accept:** a volume press uses the new direction at once; no relayout or redraw on change.

### H4 — U6a: dialogs and popups never resize the page (A §5.1)

| File | Change |
|---|---|
| `reader/InsetsGate.kt` (new) | A §5.1 verbatim |
| `reader/ReaderActivity.kt` | listener through `insetsGate.offer`; `focusSince`; `settleInsets` 400 ms after focus gain; `onConfigurationChanged` sets `configChanged` + `requestApplyInsets()`; `forced` when nothing has been shown yet, on a configuration change or on a fullscreen setting change |
| test `reader/InsetsGateTest.kt` (new) | A §6.2 `insetsGateHoldsBackBarsThatADialogShowed` |
| `reader/PageView.kt` (`ReaderPerf`) + `reader/ReaderActivity.kt` `showPage` | **[Δ]** one DEBUG-gated line per shown page: `RAPerf show <OPEN\|TURN\|JUMP\|RELAYOUT> s:<section> o:<page start> a:<anchor offset> g:<generation id> <ms since the request>`. It is behind the existing once-read `ReaderPerf.turns` flag, so a normal build pays one static boolean read and allocates nothing. It is the only way CI and the device pass can see relayouts and the first char (baseline fact 9). RC-A keeps it |
| `tools/ci/screenshots.sh` | step `57_dialog_no_reflow` (§5.3); **[Δ]** `adb shell setprop log.tag.RAPerf DEBUG` before the first `am start`, and a helper `perf_mark <name>` that logs `logcat -d -s RAPerf \| tail -1` into `steps.txt` |
| `tools/ci/raw_equal.py` (new) + `rawshot` helper | **[Δ]** moved here from the CI lane (57 needs them at H time; CI maintains them) |

- **Accept:** 57 prints EQUAL; rotation still relays out (forced); **[Δ]** no `RAPerf show RELAYOUT` line between the
  two perf marks around opening and closing 페이지 이동.

---

## 3. Phase 0 — the merged contract (serial, one agent, the lead's role)

**Rules.**
- The work is mechanical: signatures, fields, KDoc, stubs (`// R3 stub (owner: X)` safe default, or
  `TODO("owner: X")` where nobody calls it first), and compile fallout. No behaviour beyond what the tests below pin.
- It ends with `tools/typecheck.sh` green, the §3.14 contract tests green, and `tools/snapshot_contracts.sh`.
- Frozen for W1 (only P0 edits them): `settings/*`, `data/SettingsJson.kt`, `data/LibrarySchema.kt`,
  `data/Models.kt`, `engine/Layout.kt`, `render/Render.kt`, `render/StatusDecor.kt`, `ui/kit/Ui.kt`,
  `ui/kit/Toggle.kt`, `reader/ReaderHost.kt`, the frozen block of `reader/extras/ReaderPanels.kt`, `App.kt`,
  `AndroidManifest.xml`, `res/**`, `docs/**`.

### 3.1 `settings/ReaderSettings.kt`

- **`ReaderSettings`:**
  - `marginLeftDp = 40`, `marginRightDp = 40` (S §2.2); `marginTopDp = 40`, `marginBottomDp = 40` (A §3.1).
  - **Remove** `showHeader`, `showFooter`, `footerPage`, `footerChapterLeft`, `footerEpisode`, `footerTimeLeft`,
    `footerPercent`, `footerClock`, `footerBattery` and the companion `TIME_LEFT_*` (moved to `StatusMigration`).
  - **Add** `headerLeft = NONE`, `headerCenter = CHAPTER`, `headerRight = NONE`, `footerLeft/Center/Right = NONE`,
    `progressBar = true` (U §1.1).
  - Computed members `hasHeader`, `hasFooterText`, `shows(item)`, `slot(band, pos)`, `withSlot(band, pos, item)`.
    Companion `PROGRESS_LANE_DP = 12`.
  - **Add** `pageBreak: PageBreakMode = PageBreakMode.LINE`, next to `widowOrphanControl` (A §4.1; import
    `engine.PageBreakMode`).
- **`enum class StatusItem`** (12 entries, U §1.1, with `elastic`).
- **`enum class ReadMode`** (PAGED, SCROLL) and **`enum class ScrollStyle`** (AUTO, SMOOTH, STEP) with labels (S §1.2).
- **`AppSettings`:**
  - **remove** `pinChrome`;
  - **add** `readMode = PAGED`, `scrollStyle = AUTO`, `autoBackup = true` (S);
  - **add** `brightnessDevice = false` and `brightnessRestore = true`, and change the `brightness` KDoc (U);
  - **add** `highlightLook = HL_LOOK_AUTO`, `listPaging = LIST_PAGING_AUTO`, `recordLookups = true` and the six
    `const val`s (N §4.3).
- **`LibraryListMode`:** LIST("전체"), COMPACT("요약"), GRID("썸네일"), COVERS("그리드") (N).

### 3.2 New `settings/Margins.kt` and `settings/StatusMigration.kt`

- `Margins.kt`: `object SideMargin` (S §2.2 verbatim: `KEY = "r.marginBase"`, legacy 18) and `object VerticalMargin`
  (A §3.1 verbatim: `KEY = "r.marginBaseV"`, `STYLE_KEY = "marginBaseV"`, legacy 16). The prototype file is
  `wave2/proto/src/.../settings/Margins.kt`.
- `StatusMigration.kt`: U §1.2 verbatim (`MARKER_KEY`, `LEGACY_KEYS`, `Legacy.from(prefs|json)`, `Slots`, `migrate`).

### 3.3 `settings/Settings.kt`

- **`saveReader`:**
  - the 6 slot keys (enum names) and `r.progressBar`;
  - remove `StatusMigration.LEGACY_KEYS`;
  - `putInt(SideMargin.KEY, 40)`, `putInt(VerticalMargin.KEY, 40)`;
  - `r.pageBreak` (enum name).
- **`loadReader`** (plain values only; nothing written on load):
  - the status migration when `MARKER_KEY` is absent;
  - side 18/18 without `r.marginBase` → 40/40;
  - vertical 16/16 without `r.marginBaseV` → 40/40;
  - `r.pageBreak`, unknown → LINE.
- **`saveApp`:**
  - remove `a.pinChrome` and `reader.brightnessCollapsed`;
  - put `a.brightnessDevice`, `a.brightnessRestore`;
  - put `a.readMode`, `a.scrollStyle`, `a.autoBackup`;
  - put `a.highlightLook`, `a.listPaging`, `a.recordLookups`.
- **`loadApp`:** all of the above with defaults. An unknown enum name gives the default; ints outside 0..2 give 0.
  `libraryListMode` accepts `COVERS`; an unknown name gives LIST.

### 3.4 `settings/UserStyles.kt`

`toJson` puts `"marginBase": 40` and `"marginBaseV": 40`. `fromJson` maps legacy 18/18 (sides) and 16/16 (top and
bottom) without their markers to 40/40.

### 3.5 `data/SettingsJson.kt`

- **`readerToJson`:** the 6 slot keys, `r.progressBar`, `SideMargin.KEY`, `VerticalMargin.KEY` and `r.pageBreak`.
  Legacy status keys are never written.
- **`readerFromJson`:**
  - slot keys → slots; else legacy keys → `StatusMigration.migrate(Legacy.from(o)).applyTo(r)`;
  - `r.progressBar`;
  - the side and vertical legacy margin rules (S §2.3, A §3.2);
  - `r.pageBreak` (`enumOf(…, base)`);
  - clamps unchanged.
- **`appToJson` / `appFromJson`:** `readMode`, `scrollStyle`, `autoBackup`, `brightnessRestore`, `highlightLook`,
  `listPaging` (clamped 0..2), `recordLookups` and the `COVERS` enum name. **Not** `brightnessDevice`.
  `a.pinChrome` is dropped.
- **`DROPPED_KEYS`** (new) = `StatusMigration.LEGACY_KEYS` + `a.pinChrome` + `reader.brightnessCollapsed` +
  `a.brightnessDevice`. `addUnmapped` and `unmappedFromJson` skip them.
- **`TRANSIENT`** += `installid`, `restoreoffer`, `backupauto`, `deviceclass`.

### 3.6 `data/LibrarySchema.kt` (v3, C1) and `data/Models.kt`

- **`LibrarySchema`:**
  - `DB_VERSION = 3` and its KDoc line;
  - `CREATE_BOOKS` gains `review_at` and `missing_at`; `CREATE_BOOKMARKS` and `CREATE_QUOTES` gain `chapter, frac,
    sig`; `CREATE_BOOK_PREFS` gains `return_mark`;
  - new `CREATE_LOOKUPS` (N §4.1);
  - `ADD_RETURN_MARK` and the nine v3 `AddedColumn`s;
  - `CREATE_INDEXES` += `quotes_created`, `bookmarks_created`, `lookups_created`, `lookups_book`, `lookups_word`,
    `quotes_memo` (partial), `bookmarks_memo` (partial);
  - `CREATE_ALL` = tables, then `CREATE_LOOKUPS`, then indexes;
  - `UPGRADE_SWEEP` (3 `DELETE … NOT IN (SELECT id FROM books)`).
- **`data/MetaInfo.kt` `DataLimits`** (a fallout edit in a DATA file): `CHAPTER`, `WORD`, `CONTEXT`, `APP`,
  `QUOTE_STYLE_MAX`.
- **`Models.kt`** (N §4.2): `Book.missingAt`; `Bookmark` + `chapter, frac, sig`; `Quote` + `style, chapter, frac, sig`;
  `NotePlace`, `NoteKind`, `NotesTab`, `NotesOrder`, `NotesQuery`, `NoteRef` (`packed`/`unpack`), `NoteRow`,
  `NoteBook`, `NotesCounts`, `Lookup`. Everything is defaulted and appended.

### 3.7 `engine/Layout.kt` and `engine/Typesetter.kt` (signatures)

- **`Layout.kt`:**
  - `PageInfo(start, end, lines, lead: Float = 0f)` + `companion BREAK_GAP_EM = 2f` (S §1.3);
  - `enum class PageBreakMode { LINE, PARAGRAPH }`;
  - `LayoutConfig.pageBreak = LINE` as the **last** parameter (A §4.1);
  - `SectionLayout(…, anchorBreak = -1, anchorPage = -1, anchorShifted = false)`;
  - `class PageTally(pages, anchorPage, anchorShifted)` (A §5.3).
- **`Typesetter.kt`:** `layout(content, anchorBreak: Int = -1)` and `count(content, anchorBreak): PageTally`, with
  stub bodies that ignore the anchor (`// R3 stub (owner: E1)`). `countPages` is unchanged.
- **[Δ] How `wave2/proto/engine.diff` is split.** The diff has hunks in `Layout.kt`, `Typesetter.kt` **and**
  `TypesetPass.kt`. It dry-runs cleanly against today's `engine/` (checked 2026-09-30); `engine/` is not in R2's working
  tree. If E1 applied the whole diff after P0, the `Layout.kt` hunks would be rejected as "previously applied", and the
  `Typesetter.kt` hunk's context would not match P0's stub. So:
  - P0 applies **all `Layout.kt` hunks verbatim**, then adds S's `PageInfo.lead`.
  - P0 applies the **`Typesetter.kt` hunk verbatim**, instead of hand-written stubs.
  - P0 applies the **first two `TypesetPass.kt` hunks** (`@@ -123` constructor parameter `anchorBreak`; `@@ -137` the
    `keepParas`/`blockH`/`blockFits`/`anchorLeft`/`anchorPage`/`anchorShifted` declarations). Nothing reads them yet,
    so the output is unchanged: `anchorPage` stays −1 and `anchorShifted` stays false. The golden test proves it at
    P0 exit.
  - E1 applies the remaining `TypesetPass.kt` hunks (`@@ -386` onward, filtered diff, `patch -p1`). Their context is
    untouched by P0.
  - `// R3 stub (owner: E1)` then marks only the inert fields' KDoc.

### 3.8 `render/` shared types, complete pure files and stubs

| File | P0 content |
|---|---|
| `render/Render.kt` | `PageDecor(highlights, bookmarked, status: StatusDecor? = null, statusVersion = 0)`; header, footerLeft, footerRight and battery deleted (U §5.2); `Highlight(start, end, kind, style: Int = 0)` (N §4.5). KDoc: off-screen renderers pass `status = null` (U R11). |
| `render/StatusDecor.kt` (new) | **complete** (U §5.2: `StatusSlot`, `StatusBand`, `StatusDecor.lane/progress/version`) |
| `render/StatusFit.kt` (new) | **complete** (A §2.3; prototype-tested; `LANE_DP` = `ReaderSettings.PROGRESS_LANE_DP.toFloat()`); EX-P and SET call `fitsDp` (2026-10-05: bands of their own, `fitsDp` and the fit note removed; note at the top) |
| `render/ProgressMath.kt` (new) | **[Δ] complete** (pure; U §5.4 table with A §2.3's lane-aware radii: `yc(viewH, lane)`, `rDot(lane, density)`, `rCap`, `x0/x1(viewW, density)`, `dotX(f, …)`, `trackPx(viewW, lane, density)`). RC-A (U §5.3 `trackPx` for `StatusModel.update`) and RC-S (scroll settle) need the **same** formula as E2's `drawProgress`. If the formulas differ, a moved dot is not detected, or it triggers extra e-ink updates. Without this file in P0, RC-A would depend on E2's new file with no skeleton. `ProgressMathTest` moves to P0; E2 maintains both |
| `render/QuoteStyles.kt` (new) | **complete** (N §4.9.2; highlights.md §2.1–2.2, §3.1 tables) |
| `render/QuoteLook.kt` (new) | stub `generation = 0`, `update` no-op, `ink() = false` |
| `render/DeviceClass.kt` (new) | `PREF_KEY`, `einkByBuild` and `stamp` **complete** (C9 rules), `cached` (stamped pref, else `einkByBuild ? true : null`), `probe` = `einkByBuild` (no write), `probeAsync` stub = run `onDone(cached ?: false)` on main. Never throws. |
| `render/PageRenderer.kt` | stubs `drawChrome`, `drawBody` (returns false), `drawOverlay`, `prefetchPages` (S §1.8 signatures; **no `fitFooter`**); `drawStatus` reads `decor.status` and draws nothing (U fallout) |

### 3.9 `reader/` contract, complete pure files and skeletons

| File | P0 content |
|---|---|
| `reader/ReaderHost.kt` | KDoc: scroll-mode `currentPage` window + "never keep a PageInfo", and motion suppression (S §4.1); `goTo(pos, remember)` = return point (U §1.7) |
| `reader/LayoutKeys.kt` | **A §2.2 in full:** `geometry(s, viewW, viewH, density)`; `STATUS_BAND` deleted; `config(…).pageBreak`; `layoutPart` normalising the status fields; `key()` appends `\|pb=` only for PARAGRAPH (A §4.3); `GOLDEN_HASH_PARAGRAPH = "TBD"` |
| `reader/Anchors.kt` (new) | **complete** from `wave2/proto/src/.../reader/Anchors.kt` (`AnchorSpec`, `AnchorMath`, `TextRefind`) |
| `reader/ReaderJump.kt` (new) | **complete** (N §4.9.1: `ReaderJump`, `NoteSig`, `JumpAnchor.matches`; the extras constants) |
| `reader/BookSession.kt` | signatures `setViewport(w, h, anchor: AnchorSpec? = null)`, `updateSettings(new, anchor: AnchorSpec? = null)`, `touch(section, shownTo = section)`, `startsUnit(section) = false` (stub); `rebuild` calls the new `geometry` |
| `reader/PageView.kt` | `interface ScrollInput` (S §1.7, including `cancelDrag`, `a11yStep`), `var scroll: ScrollInput? = null`, `Callbacks.onScrollStart()` with a no-op default |
| `reader/ScrollMath.kt` (new) | S §1.5: `StripSource`, `ScrollPos`, `Step`, `SettleKind`, `Placement`, `ScrollMath` signatures with `TODO("owner: RC-S")` bodies, `ScreenCounter` |
| `reader/ScrollReader.kt` (new) | S §1.6: `ScrollReader` class + `Host` interface + `VirtualPage`; bodies `TODO("owner: RC-S")` (created only in SCROLL) |
| `reader/StatusModel.kt` (new) | `StatusInputs` complete; `StatusModel.update` stub (clears the decor, returns false); `sample` returns null; `StatusText` signatures (U §5.3) |
| `reader/ReturnNav.kt` (new) | `ReturnHost` complete; `ReturnNav` API with empty `FrameLayout`s and no-op bodies; `ReturnPoints` and `ReturnMarkCodec` signatures; `PIN_FLOATS = false` (U §3.4) |
| `reader/LightController.kt` (new) | `LightHost` complete; `LightController` API (U §4.2); stub `onAppSettingsApplied` = today's `ReaderWindow.applyBrightness` |
| `reader/LightCurve.kt`, `reader/DeviceLight.kt`, `reader/LightProbe.kt` (new) | signatures (U §4.2); `DeviceLight.looksEink` = `DeviceClass.cached(ctx) ?: DeviceClass.probe(ctx)` |
| `reader/ChromeMath.kt` (new) | signatures `labelMaxWidth`, `stripShort`, `bookmarkFits` |
| `reader/ReaderChrome.kt` | the new constructor `(ctx, actions, returnDock, light)` and `Actions` (U §2.5); bodies adapted to compile |
| `reader/ReaderActivity.kt` (fallout only) | `app.pinChrome` → `false`; `viewPart` drops `a.pinChrome`; `buildDecor` returns `PageDecor(hl, bookmarked)`; `footerEpisode` → `shows(StatusItem.EPISODE)`; `chromeActions` → the new `Actions`; construct `LightController(lightHost)` and `ReturnNav(this, returnHost)` with **private object** hosts whose members delegate to existing code or are `TODO("owner: RC-A")` (the stubs never call them); `geometry` call; `open(context, bookId, jump: ReaderJump? = null)` (`jump?.put(intent)`) |

### 3.10 Extras, data, notes, kit skeletons

| File | P0 content |
|---|---|
| `reader/extras/ReaderPanels.kt` (frozen block) | `StatusSampleHost` (U §1.7); `NotePlaceHost` (N §4.6); `PageThumbsHost`, `ThumbCell` (`MARK_*`), `ThumbBatch` (N §4.6 / library.md §3.5) |
| `reader/extras/QuoteSwatch.kt`, `QuotePalette.kt` (new) | stubs (N §4.10) |
| `reader/extras/ExtrasUi.kt` (fallout) | `TextActions.SHARE_MAX_CHARS = 50_000` (EX-N's `ContentsDialog.shareAllQuotes` uses it) |
| `reader/extras/ReadingSettingsPopup.kt` (fallout) | `hideBars = true`; delete 상단 챕터 제목, 하단 정보 표시, `footerItems()`, 남은 시간 (U §1.10) |
| `reader/extras/SelectionController.kt` (fallout) | delete the header-band term from the `origin()` fallback (A §2.5) |
| `data/Library.kt` | the N §5.1 signatures as stubs delegating to the old calls (style and place ignored); `@Volatile var notesGen` |
| `data/BookPrefs.kt` | `returnMark(id)` → null; `setReturnMark` no-op (U §3.3) |
| `data/AutoBackup.kt`, `data/InstallState.kt` (new) | S §3.2/§3.3 API; stubs `Outcome.DISABLED`, empty list, null, 0; `ensure`/`verify` no-op; `offerPending = false` |
| `data/Notes.kt`, `data/Lookups.kt`, `data/NotesExport.kt` (new) | N §4.10 stubs (counts 0, empty pages, `record = -1`, `Format` enum, `write` = `TODO`) |
| `data/Backup.kt` (fallout) | the `Quote(newId, …)` call |
| `ui/notes/NotesActivity.kt` (new) | companion `EXTRA_TAB = "notes_tab"`, `EXTRA_BOOK_ID = "notes_book"`, `open(ctx, tab = null, bookId = -1)` complete; body `emptyMessage("준비 중")` |
| `ui/kit/InkTouch.kt` | N §4.9.3 paging part appended: `PageDrag`, `PageFit`, `InkListView/InkGridView.paged` + `pager`, `ListPager`, `ListPaging` |
| `ui/kit/Ui.kt` | `Ink.LINE_LIGHT`; `pressableBackground` without `state_selected`; `label()` PHRASE + KOREAN on API 33+; `keepAll()`; `row()` padding (16, 10, 16, 10) + `keepAll` summary; `sectionHeader` (16, 24, 16, 8); `einkListView` KDoc (N); `showNoAnim()` also calls `matchSystemBars` (A §5.1 belt and braces) |
| `ui/kit/Toggle.kt` | `InkToggle.onDraw` off = filled black knob (U §1.6) |
| `render/Covers.kt` (fallout) | `headerCenter = NONE, progressBar = false` |
| `ui/library/LibraryActivity.kt` (fallout) | **[Δ]** the exhaustive `when (listMode)` icon mapping gains `COVERS -> R.drawable.ic_grid_view` (it does not compile otherwise). No other change: until LIB lands, COVERS falls through to the 전체 list adapter |
| `ui/settings/PageTurningPage.kt` (fallout) | delete "메뉴 고정" and the 7 legacy status rows |
| `ui/settings/SettingsLogic.kt` (fallout) | legacy status field use → slots |
| `App.kt` | `if (BuildConfig.DEBUG) DebugSeed.register(this)`; P0 lands a no-op `data/DebugSeed.kt` stub (N §14) |

### 3.11 Manifest, res, intent extras

- **`AndroidManifest.xml`:** `xmlns:tools`; `<uses-permission android:name="android.permission.WRITE_SETTINGS"
  tools:ignore="ProtectedPermissions" />` commented "기기 밝기 직접 조절 only (opt-in)" (U §1.8);
  `<activity android:name=".ui.notes.NotesActivity" android:exported="false" />` (N §4.7). (`alwaysRetainTaskState`
  landed in H1.)
- **`res/`:**
  - `drawable/fast_scroll_thumb.xml` (4×40 dp black) and `drawable/fast_scroll_track.xml` (1 dp `#AAAAAA`, inset
    1.5 dp);
  - `values/themes.xml` `Base.AppTheme`: `fastScrollThumbDrawable` / `fastScrollTrackDrawable`;
  - optional `ic_view_agenda` and `ic_apps` via `tools/fetch_icons.py`.
- **Intent extras (fixed names):**
  - `ReaderActivity`: `book_id` (existing), `jump_section`, `jump_offset`, `jump_end`, `jump_frac`, `jump_sig`,
    `jump_anchor` (N §4.8).
  - `NotesActivity`: `notes_tab`, `notes_book`.
- **Saved-state keys** (private to ReaderActivity): `rp.book`, `rp.section`, `rp.offset`, `rp.at` (H1), `rp.peek`
  (RC-A).

### 3.12 Docs

- **`docs/ARCHITECTURE.md`:**
  - Chrome: bars are overlays, no pinned chrome, the page size depends only on the gated insets.
  - Status: slots and progress line in bands of their own at the screen's edges, text box = view − bands − margins
    (2026-10-05, note at the top; until then drawn **inside the margins**, text box = view − margins), zero
    allocation, the redraw rule.
  - Pagination: anchored relayout (the first character stays; one generation's lifetime; the count cache is masked)
    and `PageBreakMode`.
  - Scroll: settle vs frame, STEP on e-ink, the SMOOTH exception.
  - Margins: 40 dp = "0".
  - Notes: schema v3 with the no-index-on-added-column rule, the jump contract, the peek rule, `notesGen`, "the hub
    never opens a book file", highlight looks, list paging, "no sync".
  - Recents: `ResumeState`, instance state.
  - Brightness: two paths with a per-firmware verdict.
  - The R2 rows `footerEpisode` / `footerTimeLeft` → `StatusItem`.
- **`docs/R3_INTERFACES.md`** (new): every §3.1–§3.11 signature, owner, users and threading, plus H1–H4's public API
  (`ResumeState`, `ReaderRestore`, `StartMode`, `InsetsGate`, `VolumeMode`, `InkTouch`).

### 3.13 Compile fallout in tests (compile only; the owners add cases)

`LayoutKeysTest` (rewritten per A §6.4, contract), `ReaderReviewFixesTest` (`geometry` signature),
`CompactSettingsTest`, `ReaderFormatTest`, `ReaderR2FeaturesTest`, `UserStylesTest`, `SettingsStoreTest`,
`SettingsJsonTest`, `SettingsJsonR2Test`, `SettingsMappingTest` (`bookmarkByTouch` instead of `pinChrome`),
`LibrarySchemaV2Test`, `LibrarySqlTest`, `BackupR2Test`, `FooterFitTest` (deleted with `FooterFit` only when E2
removes it; P0 keeps both compiling).

**[Δ] Missing from the list above (grep of the removed fields and enums over `app/src/test`):**

| Test | What breaks | P0 edit |
|---|---|---|
| `reader/extras/PopupStateTest` | `night.copy(…, footerClock = false, …)` does not compile | drop the argument (the case tests style matching, not the footer) |
| `ui/settings/SettingsFormatTest` | `ReaderSettings.TIME_LEFT_*` and `SettingsFormat.TIME_LEFT` / `timeLeft` (removed with the legacy status fields) | delete the `timeLeft` case; SET adds slot-label cases |
| `ui/library/LibraryTextTest` | behaviour: `nextListMode(GRID)` becomes `COVERS` (N: the toolbar cycles in entry order) | assert LIST → COMPACT → GRID → COVERS → LIST |
| `data/LibrarySchemaV2Test` | behaviour under v3 (C1 [Δ]) | the three version/upgrade cases move to `LibrarySchemaV3Test` |

These are the **only** existing tests whose assertions P0 may change. Every other existing test stays green unchanged.
The P0 exit ("existing behaviour tests unchanged") reads with these four named exceptions.

### 3.14 Contract tests (phase 0, green at exit)

| Test | Cases |
|---|---|
| `settings/SettingsStoreTest` (+) | S: `readMode`/`scrollStyle`/`autoBackup` defaults, round trip, unknown → default; side-margin cases (S §2.5). U: legacy status migration, legacy keys removed after save, every item in every slot, `a.pinChrome` removed, brightness keys round trip. A: `verticalMarginsMoveFromTheOldDefaultTo40`, `pageBreakModeRoundTrips`. N: `highlightLook`/`listPaging`/`recordLookups` round trip and clamps, `COVERS`, unknown → LIST |
| `settings/UserStylesTest` (+) | S `marginBase`, A `legacyStyleVerticalMarginsMove` |
| `settings/SideMarginTest`, `settings/VerticalMarginTest` (new) | S §2.5, A §6.5 |
| `settings/StatusMigrationTest` (new) | U §8.1 table |
| `data/SettingsJsonR3Test` (new) | S (app fields, transient, legacy side margins) + A (`oldBackupTopBottom16Restores40`, `newBackupKeepsADeliberate16`, `backupWithoutMarginKeysKeepsTheDevice`, `pageBreakTravelsInBackups`) |
| `data/SettingsJsonStatusTest` (new) | U §8.1 (incl. `a.brightnessDevice` never exported or restored, `DROPPED_KEYS`) |
| `data/SettingsJsonNotesTest` (new) | N §4.12 |
| `data/LibrarySchemaV3Test` (new) | N §4.12 + U: fresh v3 columns incl. `return_mark`; `upgradeStatements(1)`/`(2)` list the nine v3 ALTERs once, `(3)` none; the column guard; **no index on an added column (incl. partial WHERE)**; lookups before its indexes; `UPGRADE_SWEEP` for old 1 and 2, not 3 |
| `ui/kit/KitResourcesTest` (+) | `keepAll` (U) |
| `ui/kit/InkTouchTest` (+) | `PageDrag`, `PageFit`, `ListPaging` (N §4.12) |
| `reader/ReaderJumpTest` (new) | N §4.12 contract part |
| `render/QuoteStylesTest` (new) | N §4.12 (colour contrasts computed in Double; ink levels exact) |
| `render/StatusFitTest` (new) | A §6.3 |
| `reader/LayoutKeysTest` (rewritten) | A §6.4 |
| `reader/AnchorsTest` (new) | A §6.2 (except the InsetsGate case, already in H4) |
| `render/DeviceClassTest` (new, pure part) | `einkByBuild` positives (Innospace, Bigme, Onyx/Boox, Hisense A5/A7/A9) and negatives (`("Google","google","Pixel 9 Pro Fold")`, a Hisense LCD phone); `stamp` |
| `engine/LayoutGoldenTest` | unchanged hash green; `paragraphModeMatchesItsGoldenHash` present. **[Δ]** Not `@Ignore`: it prints `GOLDEN_HASH_PARAGRAPH = <hash>` and then `assumeTrue(LayoutKeys.GOLDEN_HASH_PARAGRAPH != "TBD")`, so every E1 run prints the value and the test is skipped, not red, until the lead sets it. P0 is the only editor of this file: E1 does not edit it, and `layoutAll(configs)` lands here |
| `render/ProgressMathTest` | **[Δ]** moved from E2 (U §8.1 cases, plus A §2.3/§2.6 at 1440 px, density 2: lane 24 → `yc` 1428, `rDot` 6; lane 20 → 1430, 6; lane 16 → 1432, 6; a bottom margin of 8 px → lane 0, nothing drawn; `trackPx` shrinks with `rDot`) |

**Exit:** typecheck green; the whole unit suite green (existing behaviour tests unchanged **[Δ] except the four named in
§3.13**); `snapshot_contracts.sh`; `grep -rn 'R3 stub\|TODO("owner'` lists exactly the stubs named above.

**Implementation reconciliation (2026-10-02):** two additional mechanical schema/mapping fallout cases were found
by the full suite. `ReadingLogSqlTest.everyTableOfABookIsClearedWithIt` includes `lookups` and still checks a DELETE
statement for every book-owned table; P0 adds that DELETE and upgrade orphan sweep with the table.
`SettingsMappingTest.everyAppFieldIsMapped` exempts `DROPPED_KEYS`, since `a.brightnessDevice` is deliberately local.
`SettingsJsonStatusTest` separately proves that the choice is neither exported nor restored. No legacy data path
is excluded from testing. These extend §3.13's four exceptions; behaviour assertions unrelated to R3 remain intact.

---

## 4. Lanes (W1 in parallel, then W2)

Every lane runs `tools/typecheck.sh --own <paths>` and `tools/unittest.sh --own <paths>` against the P0 snapshot. No
lane edits another lane's file or a frozen file; needs go to "CONTRACT REQUESTS" in the lane's final report. Lanes are
file-disjoint, so merge order is free. RC-A integrates last in practice, because it wires every API.

**[Δ] Module-mode rule** (from `tools/unittest.sh`). `--own p` copies the live `main/<p>` and only `test/<p>`, and the
snapshot holds `main` only. So:
- A lane that owns single files must also list each of its test files (for example `--own reader/ReaderFormat.kt
  --own reader/ReaderFormatTest.kt`). Otherwise its tests are not compiled.
- RU adds `--own AllocCounter.kt` (the helper sits at the test root).
- E1 lists `engine/LayoutDigest.kt` and `engine/EngineFixtures.kt`.
- **No lane passes a shared directory** (`--own reader`, `--own reader/extras`, `--own render`, `--own data`,
  `--own ui/kit`). That would compile other lanes' in-progress files live.

**Overview (the requested lanes, split by files where one agent would be overloaded):**

| Lane | Sub-lane | Agent |
|---|---|---|
| ENGINE_RENDER | **E1** engine, **E2** render | 2 |
| READER_CORE | **RC-P** pagination/session, **RC-S** scroll, **RC-A** ReaderActivity (hot-file lane) | 3 |
| READER_UI | **RU** (may split into RU-C chrome/return/status and RU-L light) | 1–2 |
| EXTRAS | **EX-P** popup, **EX-S** selection, **EX-N** navigation | 3 |
| DATA | **DA-C** core + backup, **DA-N** notes read side | 2 |
| LIBRARY | **LIB** | 1 |
| SETTINGS | **SET** | 1 |
| NOTES | **NOTES** | 1 |
| CI | **CI** | 1 |
| W2 (after E2 + RC-P merge, **[Δ]** and RC-A + EX-N W1) | RC-A thumbnails + EX-N `ThumbsTab` | same agents |

### E1 — engine (ENGINE_RENDER)

- **Files:** `engine/TypesetPass.kt`, `engine/Typesetter.kt` (bodies).
- **Tests:** new `engine/StitchTest.kt`, `engine/PageLeadTest.kt`, `engine/AnchorBreakTest.kt`,
  `engine/ParagraphModeTest.kt`, `engine/LayoutDigest.kt` (fixture); **[Δ]** `engine/LayoutGoldenTest.kt` is P0's
  (not edited here); `engine/EngineFixtures.kt` extended as in `wave2/proto/test`.
- **Tasks:**
  1. Apply **the `TypesetPass.kt` hunks from `@@ -386` on** of `wave2/proto/engine.diff` (**[Δ]** P0 applied the
     `Layout.kt` and `Typesetter.kt` hunks and the first two `TypesetPass.kt` hunks, §3.7): `place(anchored)`, the
     PARAGRAPH `blockH/blockFits` measuring, `decideCut` whole-block + `chainFits`, the `finish` clamp (A §4.2, §5.3).
  2. S §1.3 `pageLead` bookkeeping inside `place()`: the 3 existing drop sites, **the 4th (after the forced emit)**,
     `if (s == 0) pageLead += topGap(0)`, the `emitPage` carry, and `finish()` keeping `lead`.
  3. Report `GOLDEN_HASH_PARAGRAPH` from the line the golden test prints (prototype: `c9982a735d4822a9`). **[Δ]** If
     it differs from the prototype value, E1 explains why in its report. The `lead` bookkeeping is not hashed, so it
     cannot be the cause.
- **Accept:**
  - unchanged `layoutOutputMatchesTheGoldenHash` green;
  - `Typesetter{,Fuzz,Review,Perf}Test` green unchanged;
  - `countPages == pageCount` and `count(c, a)` == layout in every new test;
  - StitchTest property (0.5 px) incl. anchored × PARAGRAPH;
  - TypesetterPerfTest 1 M chars within noise of 16/14 ms.

### E2 — render (ENGINE_RENDER)

- **Files:** `render/PageRenderer.kt`, `render/DeviceClass.kt`, `render/QuoteLook.kt`, `render/StatusFit.kt` (maint),
  `render/QuoteStyles.kt` (maint), new `render/StatusMath.kt`, `render/ProgressMath.kt` (**[Δ]** maint: P0 lands it
  complete), `render/DashMath.kt`, `render/Covers.kt`. Read-only: `Render.kt`, `StatusDecor.kt` (frozen).
- **Tests:** new `render/DeviceClassTest` (+ probe/stamp cases), `StatusMathTest`, `ProgressMathTest` (maint),
  `StatusDrawCacheTest`, `DashMathTest`, `QuoteLookTest`; delete `FooterFitTest` (C5); `RibbonMathTest`,
  `BatteryMathTest` unchanged.
- **Tasks:**
  1. **Scroll draw (S §1.8):** `drawChrome`, `drawBody` (never decodes; private `drawImagePeek`), `drawOverlay`,
     batched `prefetchPages` on the existing image thread. `draw()` and `drawLine()` get no S changes.
  2. **Status (U §5.4 + A §2.3):**
     - `drawStatus` fitted per band (`bandSize` cached on `(room, statusPx)`, `bandPaint[band]`);
     - `drawBand` with `StatusMath.allocate` and the per-`(version, cw, inset, ts)` x/width cache;
     - `drawProgress(lane)`;
     - `tnum` on `statusPaint`;
     - delete `FOOTER_SEP`, `footerSepWidth`, `ellipsizedHeader`'s footer twin and `FooterFit`.
  3. **Quote looks (N §8):** `QuoteLook`; one preallocated fill `Paint` per style; `syncLook()`; two passes per line
     (fills, then lines); `drawQuoteLine` THIN/THICK/DASHED/BOX; `DashMath`; the optional thumbnail-scale flag.
  4. **`DeviceClass` (S §1.11 + C9):** `probe` = `Eink.vendorName() != null || einkByBuild`, writes the stamped
     pref; `probeAsync` single-flight daemon thread, `onDone` on main.
  5. `Covers.kt`: `status = null`.
- **Accept:**
  - 0 allocation per draw. **[Δ]** `StatusDrawCacheTest` only checks the cache key (`Paint`/`Canvas` are native and
    cannot run on the JVM). The allocation claim is checked by review (no `new`, no boxing, no string building in
    `drawStatus`/`drawBand`/`drawProgress`) and on the phone with the SMOOTH `gfxinfo` gate. SMOOTH frames cost
    6 `drawText` + 1 rect + 3 circles;
  - paged shots differ only as in §1.5;
  - thumbnails and covers pass `status = null`.

### RC-P — pagination and session (READER_CORE)

- **Files:** `reader/BookSession.kt`, `reader/PageCounts.kt`, `reader/LayoutKeys.kt` (maint after P0),
  `reader/Anchors.kt` (maint), `reader/InsetsGate.kt` (maint).
- **Tests:** `reader/BookSessionHelpersTest` (+ `pickVictim(lru, from, to)` range, + `anchoredCountsNeverReachTheCache`),
  `reader/AnchorsTest` (maint), `reader/PageCountsTest` (unchanged), `reader/LayoutKeysTest` (maint).
- **Tasks:**
  1. S §1.9: `touch(section, shownTo)` range protection via the extracted pure `pickVictim`; `startsUnit` (lazy
     `BooleanArray`: TXT title != null; EPUB first part of a spine item).
  2. A §5.4:
     - `Generation.anchor` + `anchorFor`;
     - `setViewport` / `updateSettings` / `rebuild(anchor)`;
     - `layoutOnThread` anchored (never the error page);
     - `countOnThread(gen, section, anchored)` → `PageTally`;
     - `store()` records `anchorShifted`;
     - `countAll`: `anchorCached` + one un-anchored recount;
     - `saveCounts` → `CountSaves.maskAnchor`.
  3. `rebuild` uses the `statusPx`-free `geometry` (P0).
  4. **[Δ]** One DEBUG-gated line at the end of `countAll`: `RAPerf count counted=<n> cached=<m> extra=<0|1> <ms>`
     (behind `ReaderPerf.turns`; for the §5.4 cache gate).
- **Accept:** JVM tests green; the page-count cache never holds an anchored value; no key change for LINE.

### RC-S — scroll mode core (READER_CORE)

- **Files:** `reader/ScrollMath.kt`, `reader/ScrollReader.kt`, `reader/PageView.kt`.
- **Tests:** new `reader/ScrollMathTest` (S §1.14, incl. the fuzz, 200 empty sections, `anchorAfter`, `ScreenCounter`).
- **Tasks:**
  1. S §1.5 in full.
  2. S §1.6: slots (8, re-peek all on every store), live `session.touch(first, last)`, the virtual page (rounded strip
     tops), per-page highlight cache (binary search, shared `emptyList()`), the draw loop, prefetch bounded by the LRU,
     blocked-section handling, the settle pipeline, sticky anchor, `stopMotion` settles, `userMoving`, `onDeviceClass()`
     (applied at the next DOWN).
  3. S §1.7 PageView: the `onDraw` branch; the touch table incl. CANCEL and the second finger; a lazy
     `VelocityTracker` (recycled and nulled on detach); `computeScroll`; accessibility scroll actions.
  4. S §1.11 STEP/SMOOTH, and throttled forced SMOOTH on e-ink (by not invalidating).
- **Accept:**
  - the paged `PageView` path is byte-identical below one null check;
  - 0 allocations per SMOOTH frame;
  - `ScrollMathTest` green.

### RC-A — `ReaderActivity` and friends (READER_CORE; **the single hot-file lane**)

- **Files:** `reader/ReaderActivity.kt`, `reader/ReaderMenus.kt`, `reader/ReaderFormat.kt`, `reader/ReaderMath.kt`,
  `reader/ReaderWindow.kt`, `reader/KeyMap.kt` (maint), `reader/ReaderJump.kt` (maint + `JumpAnchor.find`),
  `reader/ResumeState.kt` (maint), `reader/EndPanel.kt`, `reader/ReadingTracker.kt`, `reader/ChapterIndex.kt`,
  `reader/TapZones.kt`, `reader/IntentFiles.kt`, `reader/UriPaths.kt`, `reader/TxtOverrides.kt` (only if needed), new
  `reader/DecorDiff.kt`, new `reader/PeekRule.kt`. W2: new `reader/PageThumbs.kt` (+ `ThumbGridMath`, `ThumbMap`,
  `ThumbKey`, `ThumbBudget`).
- **Tests:** `ReaderFormatTest`, `ReaderReviewFixesTest`, `ReaderR2FeaturesTest` (drop `returnChip`,
  `footerLeft/Right`; `previewLabel` "1234쪽 · …"); new `DecorDiffTest`, `PeekRuleTest`; `ReaderJumpTest` (+ `find`,
  `NoteSig`, EPUB sig mismatch); `ReaderMathTest` and `KeyMapTest` (maint). W2: `ThumbGridMathTest`, `ThumbMapTest`,
  `ThumbKeyTest`, `ThumbBudgetTest`.
- **Every edit in `ReaderActivity.kt`, by source:**

  | Source | Sections | Edits |
  |---|---|---|
  | **U** | §2.3 | delete `PREF_BRIGHTNESS_COLLAPSED`; stop writing `page.brightnessSwipe` in `applyAppSettings` |
  | | §2.6 table | delete `pinShown`, `pinPending`, `pinnedArea`, `chromeBarHeights`, `togglePin`, the `barsResized` registrations, the `!app.pinChrome` guards and the pinned `openReadingSettings` branch; `applyPinnedArea` → `applyPageInsets` (insets only); `onBarsResized` = `updateChipPosition` |
  | | §3.5 1–10 | ReturnNav wiring: replace `returnStack` and the chip code; `pushReturn` → `onJump`; `onManualTurn`; `onPinHere`; the private `returnHost` object; `bindChrome`; counts complete; `reopenDocument` `markFraction`/`reparsed`; `closeCurrentBook` `reset`; the `afterOpen` pin load with guard |
  | | §4.2 hooks | `LightController` wiring and the private `lightHost`; delete `setBrightness`; `a.brightnessDevice` in `viewPart` |
  | | §5.3 | `buildDecor(sample)`; `StatusInputs` fill scope; `chapterStartsHere`; clock without `Calendar`; cached battery `IntentFilter`; the mutation invariant; lazy highlight list; `StatusSampleHost`; `scheduleEpisodes` condition |
  | | polish 16–17 | `ReaderFormat` deletes `footerLeft`, `footerRight`, `returnChip`; `previewLabel` |
  | **S** | §1.10 table | `scroll` field; `applyReadMode`; `switchMode`; every `scroll?.let` branch (showPage, navigateTo/preloadImages, turn, flushTurns, holdAction, `onScrollSettled`, `onScrollStart`, host navigation suppression during motion, `goTo` to a visible line, `isOnCurrentPage`, buildDecor/refreshDecor/repaint, `progress`, `currentLayout`/`currentPage`/`currentPosition`, hitTest/glyphAtView/fingerOnChar, `onLongPress`, `handleTap` `focusAt`/`clearFocus`, `toggleBookmark`, `jumpChapter` via private `jumpTo`, highlight invalidation, `onSectionStored`, relayout/`onViewSizeChanged`/`reopenDocument` `stopMotion` first, `onPause`, `onTrimMemory`, `closeCurrentBook`, `onCreate` `inFront`, `onStart`/`onStop` AutoBackup, `afterOpen` `InstallState.ensure`); the delegation grep (S §1.10) must come back clean |
  | | §1.2 | the ⋮ toggle starts `probeAsync` |
  | | §5.6 of U | scroll settle status inputs (anchor line page, bar, `chapterStartsHere`); STEP settle in the same task as `invalidate` |
  | **N** | §6.1–6.5 | the open path (§1.6.1 here); peek (`PeekRule`); `onNewIntent` jump; the anchor check job (capped); `NotePlaceHost`; `toggleBookmark` with place; chunked backfill; stale-sig quotes not drawn, **[Δ] refined:** a QUOTE is drawn when its `sig` equals the session's `NoteSig`, **or**, for a legacy `''` or a mismatched sig, when `JumpAnchor.matches(section text, q.start, q.text)` holds. The check runs lazily the first time the section's layout is used for decor, with one compare of ≤ 24 visible chars per quote, cached per (generation, section). Why: R2 moves every TXT section split once (`TxtIndexStore.VERSION` 3 → 4) and puts `PARSE_VERSION` into `textSignature`. Legacy quotes would then highlight wrong words, and every later parser bump would hide all N-era TXT quotes. The TOC "· 위치 바뀜" suffix uses the same result when known, else the sig |
  | | §6.6 | `Highlight(..., q.style)`; `DecorDiff`; `QuoteLook` in `applyAppSettings`; the probe callback |
  | | §6.7 | menus |
  | | W2 §12 | `PageThumbsHost`, `paintVersion`, per-section `decorVersion`, `cancelThumbs` re-prefetch |
  | **A** | §5.5 | `keepHere()`; the `startOpen` reorder; `onViewSizeChanged` (`stopMotion`, then `setViewport(…, keepHere())`); `applyToSession` `updateSettings(eff, keepHere())`; `relayout` `AnchorMath.pageFor`; `display()` JUMP → page start; `reopenDocument` needle and `AnchorSpec` |
  | | §5.1 | InsetsGate integration with `applyPageInsets` (C18) |
  | **R** | §10 | `rp.peek` (H1 did the rest) |
  | | C19 | the trim gate extended to scroll and thumbnails |

- **`ReaderMenus.kt`:** C22 order.
- **`ReaderFormat.kt`:** U deletions, `previewLabel`, H3 labels kept.
- **Accept:**
  - all RC-A tests green;
  - the delegation grep is clean;
  - `grep -n 'pinChrome\|applyPinnedArea\|returnStack\|PREF_BRIGHTNESS_COLLAPSED'` prints nothing;
  - nothing new between `startOpen` and `showPage` except `ReaderJump.from`, the `OWNER_JUMP` put and `AnchorSpec`
    (review item);
  - **[Δ]** H4's `RAPerf show` line kept, plus a DEBUG `RAPerf afterOpen <ms>` around §1.6.2's `afterOpen` steps
    (§5.4 gate).

### RU — READER_UI

- **Files:** `reader/ReaderChrome.kt`, `reader/ReturnNav.kt`, `reader/StatusModel.kt`, `reader/ChromeMath.kt`,
  `reader/LightController.kt`, `reader/LightCurve.kt`, `reader/DeviceLight.kt`, `reader/LightProbe.kt`.
- **Tests:** new `reader/StatusTextTest`, `StatusModelTest`, `ReturnPointsTest`, `ReturnMarkCodecTest`,
  `LightCurveTest`, `ChromeMathTest`; new test helper `app/src/test/java/com/ggumtak/readeraplus/AllocCounter.kt`
  (E1's `TypesetterPerfTest` is not edited).
- **Tasks:**
  - U §2 (visual system, top bar with the width guard, lazy options panel, bottom bar with the fixed label width, API
    §2.5, icon-swap state);
  - U §3.2–3.4 (`ReturnPoints` ★1–★5, codec, dock/chip lazy views, fit rule, `PIN_FLOATS`);
  - U §4.2–4.5 (`LightCurve`, `DeviceLight` with fixes 1–4, `LightProbe`, `LightController` verdict flow, dialogs);
  - U §5.3 (`StatusModel`, `StatusText`, zero allocation);
  - polish 1, 4, 6, 10 (chrome title), 11, 15, 16.
- **Accept:**
  - `StatusModelTest`: 0 bytes after warm-up;
  - every setter is a no-op when the value is unchanged;
  - nothing inflated before the first page (options rows, dock and chip are lazy).

### EX-P — reading-settings popup (EXTRAS)

- **Files:** `reader/extras/ReadingSettingsPopup.kt`, `reader/extras/CompactUi.kt`, `reader/extras/ExtrasFormat.kt`.
- **Tests:** `CompactSettingsTest` (edit), `PopupGeometryTest` (new or extend: width `min(W − 16dp, 400dp)`, top =
  inset + 8 dp, `HEIGHT_FRACTION 0.56`, `dropdown(maxHeightFraction = 0.8)`).
- **Tasks:**
  - U §5.5 status block, §2.6 popup branch, polish 7, 8 (9 main rows, merged 정렬/줄바꿈), 18 (segmented control);
  - S §1.2 넘기는 방식 row (+ `probeAsync`) and §2.4 좌우 여백;
  - A §3.3 상하 여백, §4.4 페이지 나눔 + 외톨이 summary, §2.7 note;
  - keep H3's 볼륨 키 row;
  - `CompactList.show` / `PopupGeometry.dropdown` `maxHeightFraction`;
  - the §1.6.3 order.
- **Accept:** the popup never scrolls on the main section at 1440 px; every status change is a repaint (checked by CI
  52/53; 2026-10-05: an item for another is a repaint, a band that comes or goes an anchored relayout, CI 52/53 check
  the first character); margin steppers speak "−10".

### EX-S — selection and quoting (EXTRAS)

- **Files:** `reader/extras/SelectionController.kt`, `reader/extras/ExtrasUi.kt` (`TextActions`, `QuoteCache`),
  `reader/extras/ExtrasText.kt`, `reader/extras/TtsController.kt` (only if S's motion rules require it; verify
  first), `reader/extras/QuoteSwatch.kt`, `reader/extras/QuotePalette.kt`, new `reader/extras/SelectionActions.kt`,
  `reader/extras/LookupContext.kt`, `reader/extras/QuoteExport.kt`, the non-frozen part of
  `reader/extras/ReaderPanels.kt`.
- **Tests:** new `SelectionActionsTest`, `LookupContextTest`, `QuoteExportTest`, `PaletteGeometryTest`;
  `OriginCalibratorTest`, `HandleAnchorTest`, `TtsHelpersTest` (maint).
- **Tasks:**
  - U polish 13 (then C26);
  - N §7.1 1–8 (swatch, palette, last style, optimistic one-update quote, existing-quote palette row, places,
    `LookupSnapshot` + `lookUp(onPicked)` / `webSearch(onDone)` recording, `LookupContext`, `QuoteExport`);
  - A §2.5 (P0 did the deletion; verify the origin with the new geometry);
  - S's EXTRAS verification (TTS `follow`/`onRange`/`navRestart` vs the virtual page; report anything).
- **Accept:** 인용 costs one e-ink update and no toast; a cancelled chooser records nothing; the selection origin is
  correct in paged and scroll mode (CI 17, 65).

### EX-N — navigation dialogs (EXTRAS)

- **Files:** `reader/extras/ContentsDialog.kt`, `reader/extras/SearchPanel.kt`, `reader/extras/InfoDialogs.kt`,
  `reader/extras/Episodes.kt`, `reader/extras/FontChooser.kt`, `reader/extras/RulesDialog.kt`,
  `reader/extras/RuleList.kt`, `reader/extras/TtsService.kt`. W2: new `reader/extras/ThumbsTab.kt`,
  `ui/kit/InkPager.kt` (`PageTarget`), `ui/kit/InkNumPad.kt`.
- **Tests:** `TocTextTest`, `EpisodesTest` (maint).
- **Tasks:**
  - N §7.2: the swatch column as a position-based row tap (no clickable child), chips, "색 바꾸기", `Highlight` style,
    the hub link, `SHARE_MAX_CHARS`;
  - U polish 10: TOC title 20 sp bold;
  - W2 N §12: the 4th tab, `ThumbGridView`, one update per grid page, `PageTarget`, `PageDrag(axisBoth)`.
- **Accept:** InkPager drag-to-page still works on quote rows; CI 83 (and W2 92–93).

### DA-C — data core and backups (DATA)

- **Files:** `data/Library.kt`, `data/LibrarySql.kt`, `data/BookRows.kt`, `data/LibraryDb.kt`, `data/FileScanner.kt`,
  `data/MetaInfo.kt`, `data/Backup.kt`, `data/BackupJson.kt`, `data/BookPrefs.kt`, `data/AutoBackup.kt`,
  `data/InstallState.kt`, new `data/BackupMerge.kt`, new `tools/check_sql.py`.
- **Tests:** `LibrarySqlTest` (11 / 9 placeholders), `BackupJsonTest` (+ S origin/summary header order and header
  reader; + N v3 fields), new `BackupMergeTest` (N §16), `ScanPlanTest` (+), `SqlDumpTest` (+ notes statements), new
  `AutoBackupPolicyTest`, `InstallStateTest`, `BookPrefsBackupTest`; `BackupR2Test` (maint).
- **Tasks:**
  - N §5.1 (writes + `notesGen` bumps), §5.2 SQL table, §5.5 scanner (trash/revive/move), §5.6 backup fields and
    merge rules incl. placeholders, `UPGRADE_SWEEP` execution;
  - U §3.3 (`returnMark`, backup `"returnMark"`, `resetProgress` clears it);
  - S §3.1–3.7 (locations, atomic writes, triggers API, phases with `busy()` between queries and every 256 KB,
    `snapshotJson` with sorted raw prefs, hash, `pickDefault`, `isBlank`, `InstallState` `ensure`/`verify`, header
    reader);
  - `check_sql.py` covers v1→v3, v2→v3, v3→"v2 build"→v3 (N §16).
- **Accept:** v2→v3 upgrade ≤ 50 ms on 10k notes; nothing is deleted by a restore; `check_sql.py` plans as asserted.

### DA-N — notes read side (DATA)

- **Files:** `data/Notes.kt`, new `data/NotesSql.kt`, new `data/NotesKeys.kt`, `data/NotesExport.kt`, `data/Lookups.kt`,
  `data/DebugSeed.kt`.
- **Tests:** new `NotesSqlTest`, `NotesPagingTest`, `NotesExportTest`, `LookupWordsTest`, `NotesKeysTest`.
- **Tasks:** N §5.3 (arms with every column aliased, page keys, book orders as one statement, details with `substr`,
  scalar counts, caches keyed by `notesGen`, the one key scan per search), §5.4 `Lookups` + `LookupWords`, §5.7 export
  (escaping additions, 50k share cap), §14 `DebugSeed`.
- **Accept:** the N §5.8 budgets with the seeder (`RANotes`).

### LIB — library (LIBRARY)

- **Files:** `ui/library/LibraryActivity.kt`, `LibraryViews.kt`, `LibraryDialogs.kt`, `LibraryText.kt`,
  `CoverLoader.kt`, `LibraryJobs.kt`, `LibraryImport.kt`, `BookSelection.kt`, new `ui/library/LibraryGridMath.kt`, new
  `ui/library/AutoRestorePrompt.kt`; `ui/kit/InkTouch.kt` (maint).
- **Tests:** `LibraryTextTest` (+ `metaLine`/`compactMeta`/`lastRead`), new `LibraryGridMathTest` (incl. the last-column
  invariant `< W − inset − 57 dp`), `InkTouchTest` (maint), `BookSelectionTest`, `ProgressThrottleTest`.
- **Tasks:**
  - S §3.2 trigger 1 (idle 10 s), §3.4 `AutoRestorePrompt` + scan hold + "다른 백업 보기" + late-answer line, the
    one-time status line, `InstallState.ensure` first in `onCreate` (C35);
  - U §4.3 `restoreIfStale` after the first list, polish 14 (card, separators, list `paddingEnd 12dp`);
  - N §10 (drawer rows + lazy counts, book menu, delete/empty-trash warnings, trash rows, 4 views with one GridView,
    measured paging for LIST/COMPACT and fixed rows for GRID/COVERS, `ListPager` + number pad, cover prefetch,
    `probeAsync` after the first frame, `CardButton` without a pressed state on e-ink);
  - keep H1's `startMode` and H2's guard.
- **Accept:** `am start -W` within +5 %; 0 queries on a view switch; one e-ink update per library page; CI 41–46.

### SET — settings pages (SETTINGS)

- **Files:** `ui/settings/PageTurningPage.kt`, `MainPage.kt`, `BackupPage.kt`, `AboutPage.kt`, `LookupPage.kt`,
  `SettingsPage.kt`, `SettingsLogic.kt`, `KeyNames.kt`, `SettingsActivity.kt` (only if a page is added).
- **Tests:** `SettingsFormatTest` (+ "자동 (이 기기: 흑백 무늬 | 쪽 단위 | e-ink → 손을 떼면 이동)" for true / false /
  null), `KeyNamesTest` (maint).
- **Tasks:** §1.6.3 rows:
  - S §1.2 (넘기는 방식 section, disabled 세로 스와이프, MainPage summary), §2.4, §3.8;
  - U §4.6 (brightness rows, 조명 진단 with the 30 s change watch), §5.5 (상태 표시줄), polish 12 (section without the
    hairline, 24 dp chevron and ▾);
  - A §3.3, §4.4, §2.7;
  - N §11;
  - R H1/H3 rows kept;
  - **[Δ]** `MainPage.resetSettings` ("설정 초기화"). R2 already keeps the TXT defaults, the key bindings and the
    library view. It must also keep the R3 privacy and device choices `autoBackup`, `recordLookups` and
    `brightnessDevice`, plus `listPaging` (library view). Otherwise a user who turned off auto backup or lookup
    recording gets it switched back on silently. Reader settings reset to 40/40/40/40, LINE and the default slots
    (C4). Add the kept fields to the dialog text.
- **Accept:** CI 50, 51 and 14d; TalkBack reads disabled rows as unavailable.

### NOTES — 독서 노트 hub (new files only)

- **Files:** `ui/notes/NotesActivity.kt` (after P0), new `ui/notes/NotesAdapter.kt`, `ui/notes/NotesMenus.kt`,
  `ui/notes/NotesText.kt`, `ui/notes/NotesWindow.kt` (pure).
- **Tests:** new `ui/notes/NotesTextTest`, `ui/notes/NotesWindowTest`.
- **Tasks:** N §9 in full: screen, row kinds, gestures, multi-select as `HashSet<Long>` (a query when > 20k), overflow,
  choosers, search, empty states, the windowed adapter with fetch-then-move, one update to open, state, the 단어 tab,
  export (`text/markdown`, "w" then "wt", app-scope job, saved state), share.
- **Accept:** the N §9.11 budgets; CI 85–89.

### CI — emulator checks

- **Files:** `tools/ci/screenshots.sh`, `tools/ci/raw_equal.py` (U §8.2, reads the header; **[Δ]** created by H4,
  maint here), `tools/ci/same_page.py` (H1, maint), `tools/ci/find_node.py` (maint), `tools/ci/make_samples.py` (only
  if extra samples are needed), **[Δ]** new `tools/ci/perf_log.py` (pure: parses `RAPerf show` lines; `first_is A B`
  = the page start after mark B equals the anchor at mark A; `no_relayout A B`).
- **Tasks:** the §5.3 order and names; the helpers `rawshot` (H4), `top_is`, `same`, `overview_back` (H1),
  `perf_mark` (H4); `CHECK n PASS|FAIL` lines in `steps.txt`; nothing fails the job except the existing crash rule;
  label changes for the list modes (C29).
- **Accept:** a `[screens]` run produces every §5.3 name; `crash.txt` is empty.

### W2 — page thumbnails (after E2 and RC-P are merged, **[Δ]** and after RC-A's and EX-N's W1 work)

RC-A: `reader/PageThumbs.kt` + `PageThumbsHost` (N §12 changes 1–6). EX-N: `ThumbsTab`, `InkPager.PageTarget`
(change 7). **Accept:** warm ≤ 90 ms, cold ≤ 400 ms, one update per grid page; +0 on open and turn; CI 92–93.

---

## 5. Integration (phase 2, the lead)

### 5.1 Checklist

1. Merge every W1 lane, then `tools/typecheck.sh` (full tree) and `tools/unittest.sh` (full).
2. Set `LayoutKeys.GOLDEN_HASH_PARAGRAPH` from the line `paragraphModeMatchesItsGoldenHash` prints (**[Δ]** it is
   `assume`-skipped, not `@Ignore`d, while the value is "TBD"), then re-run. The test must now pass, not skip. The LINE
   hash is unchanged, so `ALGO_VERSION` stays 1.
3. `grep -rn 'R3 stub\|TODO("owner' app/src/main/java` prints nothing.
4. Run S §1.10's delegation grep on `ReaderActivity.kt`; every hit is correct for real pages or sits behind a
   `scroll` branch.
5. `grep -rn 'pinChrome\|showHeader\|showFooter\|footerPage\|STATUS_BAND\|FooterFit\|fitFooter\|FastScrollEdge\|LibraryListView\|jump_done'`
   on main and test prints nothing (only the migration constants in `StatusMigration`).
6. `python3 tools/check_sql.py`.
7. `tools/snapshot_contracts.sh`; update `docs/R3_INTERFACES.md` with anything lanes requested.
8. A `[screens]` CI run: check every §5.3 expectation and each CHECK line.
9. W2 merge, then steps 1, 3 and 8 again.
10. The perf gates (§5.4) on the Comet, then the device pass (§5.5).
11. An adversarial review pass over the three hot files (`ReaderActivity`, `PageRenderer`, `TypesetPass`) and the
    open path.

### 5.2 Full JVM test list (`tools/unittest.sh`)

New tests are marked ✚; ✎ marks an edit or extension; the rest run unchanged and must stay green.

| Package | Tests |
|---|---|
| `engine/` | ✚ StitchTest, ✚ PageLeadTest, ✚ AnchorBreakTest, ✚ ParagraphModeTest, ✚ LayoutDigest (fixture), ✎ LayoutGoldenTest (+paragraph), ✎ EngineFixtures, TypesetterTest, TypesetterFuzzTest, TypesetterReviewTest, TypesetterPerfTest, BreakClassTest, GlyphAtTest, LineGeometryTest |
| `render/` | ✚ DeviceClassTest, ✚ StatusFitTest, ✚ StatusMathTest, ✚ ProgressMathTest ([Δ] P0), ✚ StatusDrawCacheTest, ✚ DashMathTest, ✚ QuoteLookTest, ✚ QuoteStylesTest, ✗ FooterFitTest (deleted), BatteryMathTest, CharBuffersTest, CoverKeysTest, CoverTextTest, DefaultFontTest, FontFilesTest, FontMathTest, FontWeightFloorTest, ImageCacheTest, ImageCoverageTest, LatestTaskRunnerTest, RefreshCallsTest, RescanGateTest, RibbonMathTest, SfntReaderTest, TextSegmentsTest |
| `reader/` | ✚ ScrollMathTest, ✚ AnchorsTest, ✚ InsetsGateTest (H4), ✚ ReaderJumpTest, ✚ DecorDiffTest, ✚ PeekRuleTest, ✚ StatusTextTest, ✚ StatusModelTest, ✚ ReturnPointsTest, ✚ ReturnMarkCodecTest, ✚ LightCurveTest, ✚ ChromeMathTest, ✎ LayoutKeysTest (A §6.4), ✎ BookSessionHelpersTest, ✎ ReaderMathTest (H1), ✎ KeyMapTest (H3), ✎ ReaderFormatTest, ✎ ReaderReviewFixesTest, ✎ ReaderR2FeaturesTest, ChapterIndexTest, DurationFormatTest, PageCountsTest, ReaderBuild9FixesTest, ReadingTrackerTest, TapZonesTest, TxtOverridesTest, UriPathsTest; W2 ✚ ThumbGridMathTest, ThumbMapTest, ThumbKeyTest, ThumbBudgetTest |
| `reader/extras/` | ✚ SelectionActionsTest, ✚ LookupContextTest, ✚ QuoteExportTest, ✚ PaletteGeometryTest, ✚/✎ PopupGeometryTest, ✎ CompactSettingsTest, CleanupPacksPerfTest, EpisodesTest, ExtrasMessagesTest, ExtrasReviewFixes2Test, FormatTest, HandleAnchorTest, OriginCalibratorTest, PopupStateTest, ReviewFixesTest, RuleListTest, SentenceSplitterTest, TextSearchTest, TocTextTest, TtsHelpersTest |
| `settings/` | ✚ SideMarginTest, ✚ VerticalMarginTest, ✚ StatusMigrationTest, ✎ SettingsStoreTest, ✎ UserStylesTest |
| `data/` | ✚ LibrarySchemaV3Test, ✚ SettingsJsonR3Test, ✚ SettingsJsonStatusTest, ✚ SettingsJsonNotesTest, ✚ NotesSqlTest, ✚ NotesPagingTest, ✚ NotesExportTest, ✚ LookupWordsTest, ✚ NotesKeysTest, ✚ BackupMergeTest, ✚ AutoBackupPolicyTest, ✚ InstallStateTest, ✚ BookPrefsBackupTest, ✎ LibrarySqlTest, ✎ BackupJsonTest, ✎ ScanPlanTest, ✎ SqlDumpTest, ✎ LibrarySchemaV2Test (compile), BackupR2Test, BookFileProviderTest, DataPathsTest, LanUploadTest, NaturalOrderTest, NextPartTest, ReadingLogDaysTest, ReadingLogSqlTest, ReviewRegressionTest, RowsAndMetaTest, ScanWalkTest, SettingsForwardCompatTest, SettingsJsonR2Test, SettingsJsonTest, SettingsMappingTest |
| `ui/kit/` | ✚ InkTouchTest (H2 + P0), ✎ KitResourcesTest, ErrorsTest, InkMessageLayoutTest, NumPadStateTest |
| `ui/library/` | ✚ LibraryGridMathTest, ✎ LibraryTextTest (H1, H3, LIB), ✗ FastScrollEdgeTest (→ InkTouchTest), BookSelectionTest, ProgressThrottleTest |
| `ui/settings/` | ✎ SettingsFormatTest, ✎ KeyNamesTest, EinkChoicesTest, ErrorLinesTest, FolderSetsTest, KeyCaptureTest, StatsModelTest, TapZoneModelTest, TreePathsTest, TtsVoicesTest |
| `ui/notes/` | ✚ NotesTextTest, ✚ NotesWindowTest |
| `format/**` | unchanged |
| test root | ✚ AllocCounter (helper, not a test) |

### 5.3 CI screenshots (`tools/ci/screenshots.sh`, emulator 720×1440 at density 2, in run order, unique names)

`raw_equal` = `tools/ci/raw_equal.py A B Y0 Y1` over PageView rows only. **"Content rows"** below means the text box in
screen rows: `pv + 80 … pv + 1360` at density 2, where `pv` is the PageView's top from `dumpsys` bounds (0 in
fullscreen); the header band (above) and the footer band and lane (below) are excluded. `same` = `tools/ci/same_page.py`. `top_is` = the
dumpsys top activity. Every CHECK and PASS/FAIL line is logged and never fails the job.

**[Δ]** The content rows `80…1360` hold only at 상하 여백 "0" (2026-10-05: and the default bands; with a footer item, a
36 dp band from 14c/10b and again from 52b, the box ends at `pv + 1324` and rows 1324…1360 are margin paper, fine for an
EQUAL or DIFF of the same settings). Every step that changes the vertical margins restores
"0" before the next `raw_equal`. **Position checks** use the H4 log line, not `find_node`: `PageView` has no
accessibility text, so a uiautomator dump never contains the page's words. `perf_mark X` records the last
`RAPerf show` line; `perf_log.py first_is X Y` passes when the page start `o:` after Y equals the anchor `a:` at X;
`perf_log.py no_relayout X Y` passes when no `RELAYOUT` line lies between the marks.

| # | Shot | Steps (short) | Must show / check | Source |
|---|---|---|---|---|
| 1 | `01_library` | as today | cards with 20 px padding, light separators, thin fast-scroll thumb (scroll mode on the emulator) | U, N |
| 2 | `41_library_more` + CHECK 41, 41b | `tap_label "책 메뉴"`; dump; then a 6 px roll `input swipe X Y X+2 Y+6 150` on the same ⋮ | menu open ("책 정보" found) and the first title's bounds unchanged, in both cases | N (H2) |
| 3 | `02_drawer` | as today | 독서 노트 · 단어장 rows between 다 읽은 책 and 컬렉션 (the drawer's second group since a3b8826, on screen without scrolling; no counts yet) | N |
| 4 | `10_txt_page1` | as today | **no footer text**; progress line on row 1423 between end dots x 14–22 and 697–705 (rows 1419–1427; 2026-10-05, ReadEra's 탐색줄 in faint greys: was row 1415, x 24..696), dot at the start; header band 0..44 with MaruViewer's line (배터리 아이콘 · 시계, 책 제목, 쪽 번호; glyph box 8..40); text box 40..680 × 80..1360 (2026-10-05: the 18 dp top and 22 dp bottom margins count from the 22 dp header band and the 18 dp progress line's band) | U, A |
| 5 | `11_txt_page2`, `12_txt_tap_right` (+ `rawshot 12b`) | as today | — | — |
| 6 | `13_txt_chrome` (+ rawshot) | as today | back · 🔖 🔊 🔍 ☰ ⚙ ⋮; one-line title at x = 40; brightness row with ⌄, no grey square; "3 / 167" centred at x = 360 ± 2, bold, not underlined; ⟳ + outline pin; ⏮ seek ⏭; no strip | U |
| 7 | `13b_pin` | `tap_label "이 페이지 고정"` | filled pin ("고정 해제") and **no history row** (2026-10-05, ReadEra's row: the pinned page is the one on screen, nothing to go to; was "(pin) 3쪽" · "지우기"); `raw_equal 13 13b 360 1100` EQUAL | U |
| 8 | `13c_pin_close` | tap 360 700 | chrome closed, **no turn**: `raw_equal 12b 13c` over PageView rows below the header's clock (`pv + 48 …`, 2026-10-05) EQUAL; no chip | U |
| 9 | `13d_strip`, `13d_return`, `13d_forward` | volume-down ×5; tap 360 720; `tap_label "3쪽으로"`; `tap_label "8쪽으로"` | label "8 / 167", row "‹ 3쪽으로" · 지우기, no right item; then "3 / 167", **no left item** · 지우기 · "8쪽으로 ›", pin outline; then "8 / 167", "‹ 3쪽으로" only (2026-10-05). 지우기 at x 358..362 in every state (`history_cols`) | U |
| 10 | `13e_brightness_opts` | `tap_label "밝기 옵션"` | the row stays, ⌃, "스와이프로 밝기 조절" (off, filled knob), "기기 밝기 직접 조절"; no question (not e-ink) | U |
| 11 | `13f_clear` | after 12 (on q): `tap_label "지우기"`; the bars close for `rawshot 10a_pre`, then open for 13 | row gone, pin outline | U |
| 12 | `13g_seek_chip`, `13h_chip_gone`, `13i_*` (+ `rawshot 10a_pre`) | after 10 (on 8): two seeks with the menu open (the first lands on m), close; volume-down ×2; open: "m쪽으로", "이 페이지 고정", "8쪽으로", "3쪽으로", "8쪽으로", "m쪽으로", "q쪽으로" (2026-10-05) | chip "‹ 8쪽으로 \| ✕" (the chain's first origin, not m) above the progress line; gone after 2 turns. **The user's two ReadEra shots (two seeks, no page turned between):** on q "‹ m쪽으로" only; on m "‹ 8쪽으로" · 지우기 · "q쪽으로 ›"; the pin there fills and the row stays; on 8 "‹ 3쪽으로" · 지우기 · "m쪽으로 ›"; on 3 지우기 · "8쪽으로 ›" only; forward three times, on q "‹ m쪽으로" only | U |
| 13 | `14_reading_settings` | as today | **68aa271:** centred popup (16 ± 1 px gaps): one top bar 전체 읽기 설정 › · 닫기, then 글자 크기 · 굵기 · 줄 간격 · 문단 간격 · 좌우 여백 · 상하 여백 (96 px rows) · 글꼴, no 더보기, no scrollbar; CHECK 14q: the popup's 좌우 여백 / 상하 여백 read "0"; "전체 읽기 설정" opens 설정 → 읽기 설정 (여백·페이지: "좌우 여백 0", "상하 여백 0", CHECK 14m), BACK returns to the same page | U, S, A |
| 14 | `14b_status_slots` | ⋮ → 설정 → 화면·밝기 → 위쪽 상태 표시줄 · 아래쪽 상태 표시줄 (its first two sections; each band's rows 왼쪽 / 가운데 / 오른쪽, read under their header: `slot_row`, `ui_rows.py` "header › row") | 위 [배터리 아이콘 · 시계][책 제목][쪽 번호] (MaruViewer's line, 2026-10-05), 아래 all 없음; 진행 막대 on | U |
| 15 | `14c_slot_list` | the 가운데 row under 아래쪽 상태 표시줄; "쪽 번호 (12 / 3259)" | 12 items with examples ("챕터 쪽 번호 (2 / 32)" right under 쪽 번호); "없음" checked | U |
| 16 | `10b_footer_slots` (+ `rawshot 10b`) | after 14c's `back` (popup closed, chrome hidden, same page as `10a_pre`) | footer centre "N / M" centred on the text column above the line; **2026-10-05:** the footer's band (36 dp; the progress line's alone 18 dp) ends the text box 36 px higher, one anchored relayout: **`raw_equal 10a_pre 10b` over the box's upper part `pv + 80 … pv + 680` (`contenttop`: half the smaller box less a line, as an anchored relayout may move a keep-with-next run of up to half a page): EQUAL** (the first lines stay) and **`first_is 10a_pre 10b`** (the first character stays; was `no_relayout` with the whole content rows); **`10b_margin`: the rows between the new box bottom and the footer's band, `pv_bottom − 112 … pv_bottom − 72` (4 px under the box left for a last line's shadow), are paper only (`raw_equal.py --uniform`)**: the box really ends above the band | U, A |
| 17 | `14d_volume_mode` | ⋮ → 설정 → 넘기기·터치·키 → 버튼·키 → 볼륨 키 (one chooser again, 68aa271; was the popup's 더보기) → "위 키로 다음 페이지"; back; VOLUME_UP; tap 360 720; find "페이지 이동, " | the row read "아래 키로 다음 페이지" and its chooser lists the 3 entries (14d_list); the label is one page further; then restore "아래 키로 다음 페이지 (기본)" and close the chrome | R (H3) |
| 18 | `15_toc`, `16_search` | as today | TOC title 20 sp bold | U |
| 19 | `17_selection` | as today | one row of 5 (복사 · 인용 · 메모 · 사전·번역 · ⋮); the 인용 cell shows a yellow dot with ▾ | U, N |
| 20 | `20_epub_page1`, `21_epub_page4`, `22_epub_page9` | as today | narrower column (560 px) | S |
| 21 | `60_scroll_on` … `69_scroll_again` | S §1.15 steps | S §1.15 expectations; **plus:** header, footer, progress line and return strip stay fixed while the text scrolls (61; their live values — 쪽 번호, the clock, the dot — may change: under 1500 px, in the header only over its glyph rows `pv … pv + 48`; the paper between them and the text box, `pv + 48 … pv + 80`, is EQUAL: `61_header_gap`; and the paper between the text box and the footer's band, `pv_bottom − 116 … pv_bottom − 72` (14c's footer item: a 36 dp band under the 22 dp margin), is EQUAL: `61_footer_gap`) | S |
| 22 | `69b_back_to_paged` | ⋮ → "페이지로 보기"; 스크롤 움직임 → 손가락을 따라 (기본) | logged only (restores defaults for later steps) | PLAN |
| 23 | `30_big_txt`, `31_big_txt_later`, `32_big_txt_reopen` | as today; **[Δ]** `perf_mark 31` before closing, `perf_mark 32` after the reopen | reopen shows the same first line (anchored open): **[Δ]** `first_is 31 32` | A |
| 24 | `40_library_after` | as today | library (no resume: `am start -n` has no action) | R |
| 25 | `42_library_compact` + CHECK 42b | 12 extra samples, ⋮ (더보기) → 지금 스캔 (a3b8826: the scan left the drawer; 지금 스캔 as 설정 → 책 스캔's row since the 2026-10-04 review), 보기 → 간단히; tap 2 px inside the ⋮'s right edge | 88 dp rows, ⋮ column, "새 책"; 42b: menu open | N |
| 26 | `43_library_thumbs`, `44_library_grid` | 보기 → 큰 표지 / 작은 표지 (were 썸네일 / 그리드), then back to 자세히 | 3 columns (96×136) / 4 columns, one-line titles | N |
| 27 | `45_library_paged`, `46_library_page2` + CHECK 46 | 설정 from the library → 서재 → 목록 넘기기 → 한 화면씩; swipe up | pager "1 / N", no cut card except the one leading page 2, no fast-scroll thumb; "2 / N"; restore 스크롤 | N |
| 28 | `50_settings`, `51_status_page` (+ `50b_stats`, `50c_wifi`, `50d_eink_settings`, `50e_eink_advanced`) | the library's ⋮ (더보기) → 설정; 설정 → 화면·밝기 (위쪽 / 아래쪽 상태 표시줄 first); drawer → 읽기 기록; ⋮ → Wi-Fi로 책 받기; 설정 → e-ink 새로고침, its 고급 opened | five groups 읽기 화면 · 조작·기능 · 서재 · 책 가져오기 · 기타, 18 sp headers with a 2 px black rule above every section but the first, filled off knobs, trailing controls end at x = 688; six slot rows (two sections) + 진행 막대 | U, S, A |
| 29 | `80_quote_saved` | select in sample-utf8.txt → 인용 | yellow fill, **no toast** | N |
| 30 | `81_palette`, `81b_green` | long-press 인용 → palette → 초록 | 6 cells with 노랑 ringed; then green fill | N |
| 31 | `82_quote_popup` | long-press the first quote | palette row above "복사 · 메모 · 인용 삭제 · 사전·번역 · ⋮", no grey selection fill | N |
| 32 | `83_toc_quotes` | TOC → 인용문 | swatch column, chips "[전체 2] [● 1] [● 1]", link "독서 노트 (모든 책)" | N |
| 33 | `84_lookup` | 사전·번역 cancel, then 웹 검색 | logged | N |
| 34 | `85a_drawer`, `85_notes_hub` | drawer → 독서 노트 | "독서 노트 2", "단어장 1"; 전체 tab with day header, 2 quote rows, 1 word row | N |
| 35 | `86_notes_quotes`, `87_notes_jump` + CHECK 87 | (before 85: sample-utf8.txt read 3 pages past the quotes, so its saved place is off the quote's page, §1.6.1) 인용문 tab; tap the first row | filter row; the reader at the quote with the mark; the chip "‹ N쪽으로" found | N |
| 36 | `88_notes_select`, `89_notes_words` | long-press → 선택; 단어장 tab | "1개 선택" bar; word row with 다시 찾기 and the word in bold | N |
| 37 | `90_highlight_ink` | 설정 → 화면·밝기 → 인용문 색 표시 → 흑백 무늬; open sample-utf8.txt, 3 pages back to the quotes | grey band + thin line / lighter band + dashed; restore 자동 | N |
| 38 | `52_footer_toggle_same_text` | open sample TXT; ⋮ → 설정 → 화면·밝기 → 아래 가운데 = 없음 (no footer: 10b had set it); back; `rawshot 52a`; the same page → 아래 가운데 = 쪽 번호, 아래 오른쪽 = 배터리 아이콘 · 시계, 위 가운데 = 없음; back; `rawshot 52b` | **2026-10-05 (the margins count from the bands):** the footer's band that comes moves the box's bottom, one anchored relayout: **`raw_equal 52a 52b` over `contenttop` (`pv + 80 … pv + 680`): EQUAL** and **`first_is 52a 52b`**; the header keeps its band (배터리 아이콘 · 시계 and 쪽 번호 stay; it loses its title), so the box's top stays. Was: the content rows EQUAL and `no_relayout` | A |
| 39 | `53_progress_toggle_same_text` | 화면·밝기 → 진행 막대 off; back; rawshot | **2026-10-05:** the footer's band loses its lane (36 → 22 dp), one anchored relayout: `contenttop` EQUAL and **`first_is 52b 53`** (was: the content rows EQUAL, `no_relayout`); restore on | A |
| 40 | `54_margin_v_exact` | **[Δ]** `perf_mark 54a`; ⚙ → 전체 읽기 설정 → 상하 여백 +10; back; `perf_mark 54b`; shot; then 상하 여백 back to 0 | **[Δ]** `first_is 54a 54b` (the exact first char; height-only change) | A |
| 41 | `55_font_up_down` | 글자 크기 +1, then −1 | `raw_equal` with the shot before the change: EQUAL (content rows valid again: 54 restored "0"; 52b's footer item is on, so the box ends at `pv + 1324` and rows 1324…1360 are margin paper, the same in both shots) | A |
| 42 | `56_page_break_paragraph` | **[Δ]** `perf_mark 56a`; ⚙ → 전체 읽기 설정 → 페이지 나눔 = 문단 단위; back; `perf_mark 56b`; next page | **[Δ]** `first_is 56a 56b`; the next page starts at a paragraph start (logged `o:` of the next `show TURN` is a block start: checked by eye from the shot); restore 줄 단위 | A |
| 43 | `57_dialog_no_reflow` | fullscreen on; `rawshot 57_open`, wait 3 s, `rawshot 57_still_b` (CHECK `57_still` EQUAL); one warm-up 페이지 이동 open/cancel; `rawshot 57_before`; 페이지 이동 open (number pad seen), cancel (dialog gone, one more BACK if not); `rawshot 57_after` | `raw_equal 57_before 57_after` EQUAL; **[Δ]** `no_relayout`. The cold first frame differs from every redraw by a few hundred edge pixels per screen (CI 28–32, rasterization, no relayout): logged as `57_firstframe (info)` | A (H4) |
| 44 | `92_thumbs`, `93_thumbs_next` (W2) | ⋮ → 페이지 미리보기 (페이지 썸네일 until the 2026-10-04 review); swipe | 4×3 (or 5×3) grid, current page framed, labels = footer numbers, marks; next grid page | N |
| 45 | `70_before` … `78_closed_then_recents` | R §7 block (kill, force-stop, history intent `-f 0x10100000`, `install -r`, second book + kill, don't-keep-activities, Back control) | `top_is ReaderActivity` + `same` PASS for 71–77; 78: `top_is LibraryActivity` | R (H1) |
| 46 | `95_restore_offer`, `96_restored`, `97_restored_margins`, `98_backup_page` | S §3.9 (crafted backup, `pm clear`, appops) — **last** | the offer dialog; 96: 8 s after 복원, drawer → 읽고 있는 책 shelf: toolbar title 읽고 있는 책, drawer closed, 샘플 EPUB listed; "좌우 여백 0" and "상하 여백 0" from a legacy 18/16 backup; the 자동 백업 section (설정 from the library → 백업·복원: the list 설정 shows over a book has no 백업·복원) | S, A |

Every shot: `logcat -b crash` is empty and there is no "draw failed".

### 5.4 Perf gates (Comet unless noted; the R2 baseline is measured at Gate 0)

| Gate | Threshold | How |
|---|---|---|
| Open a book (cached 14.8 MB TXT) | RAPerf "open … first page" ≤ baseline + 10 ms | RAPerf log, 5 runs, median |
| Open at a note vs a normal open of the same book | within noise | RAPerf |
| Recents resume | the library window never draws (shot 72/73); `am start -W -f 0x10100000` TotalTime ≤ library cold start + reader open (baseline) + 50 ms | `am start -W`, same_page |
| Page turn | RAPerf "turn N ms" within noise, also on a page with 5 mixed-style quotes and with header + 3 footer slots + line | RAPerf |
| Scroll STEP (Comet) | step ≤ paged turn + 2 ms; 0 frames while dragging | RAPerf, gfxinfo |
| Scroll SMOOTH (phone 60/120 Hz) | janky < 1 %, p95 < vsync, UI thread p95 < 4 ms over 10 flings; 0 allocations per frame | `dumpsys gfxinfo framestats` |
| Library cold start | `am start -W` ≤ baseline + 5 % (2nd launch after the update) | am start |
| Engine | 1 M chars layout/count within noise of 16/14 ms; PARAGRAPH within noise of LINE | TypesetterPerfTest (JVM) |
| DB upgrade v2 → v3 | ≤ 50 ms with 10k notes (first launch) | log |
| Notes hub | counts ≤ 15 ms, page ≤ 40 ms (≤ 80 ms past p 20), search ≤ 150 ms, drawer ≤ 5 ms, first rows ≤ 150 ms | `RANotes` + `DEBUG_SEED_NOTES 10000` |
| Thumbnails (W2) | warm ≤ 90 ms, cold ≤ 400 ms | `RAThumbs` |
| Memory | scroll vs paged ±1 MB; hub ≤ 2 MB of rows | `dumpsys meminfo` |
| Idle | no redraws or timers at rest (clock only on turn/settle/resume) | `dumpsys gfxinfo` flat for 60 s |
| Status | 0 bytes per turn (StatusModelTest) | JVM |
| APK | ≤ +150 KB code for NOTES; the total is reported | CI artifact |
| **[Δ]** Relayout (a setting change) | font +1 on the 14.8 MB TXT: `RAPerf show RELAYOUT … ms` ≤ R2 baseline + 5 ms (the anchor costs 1 compare per item). Status slot, progress line, status size, 흑백 반전 and 인용문 색 표시 changes: **0** `RELAYOUT` lines and one e-ink update each | H4 log line, 5 runs |
| **[Δ]** Warm recents return (process alive) | Home → recents → the card: 0 `show` lines (no layout, the frame is reused), first draw ≤ 1 frame after `onResume`, no image re-decode (`UI_HIDDEN` no longer trims, C19) | RAPerf, gfxinfo |
| **[Δ]** `afterOpen` main-thread work | ≤ 4 ms on the Comet for all 8 steps of §1.6.2 together (each IO step launched, never awaited). The first turn within 1 s of the first page is within noise of a normal turn | DEBUG `RAPerf afterOpen N ms` (RC-A adds it next to the H4 line) |
| **[Δ]** Page-count cache after an anchored session | Reopen a fully counted book after a session whose anchor shifted the section: 0 sections recounted (the masked value or the one extra un-anchored count reached the cache). PARAGRAPH gets its own cached counts | DEBUG `RAPerf count counted=N cached=M`; JVM `anchoredCountsNeverReachTheCache` |
| **[Δ]** Auto backup from the reader (`onStop` + 5 s) | on the A53 with 1,000 books: `dumpsys meminfo` PSS back within +2 MB of its pre-run value 10 s after `WROTE`/`UNCHANGED`. The transient `JSONObject` tree (≈ 10–20 MB, S §3.2) must not linger and raise the chance of a background kill (U1). Returning to the reader mid-run logs `BUSY` within one query or 256 KB | `dumpsys meminfo`, logcat |

### 5.5 Device checks (one Comet session, one phone, the user's Samsung)

- **Comet, paged defaults:**
  - text column 560 px, box 1280 px high (same lines per page vertically as R2);
  - "좌우 여백 0" / "상하 여백 0";
  - header in the top margin;
  - progress line visible also in the fastest refresh mode;
  - "(pin) N쪽" and dashed "없음" visible in the fastest mode (U D2);
  - all 6 quote looks in the fastest mode, with the `LINE_DOTTED` fallback if 파랑/빨강 vanish (N §8).
- **Position stability (A §8):**
  - toggle every slot, the line and status 8 ↔ 16: the text never moves, one update each;
  - 상하 여백 −10 ↔ +10: the same first char;
  - font +1 +1 −1 −1: the identical page;
  - fullscreen + 페이지 이동 / rename / popup: no relayout;
  - 줄 단위 ↔ 문단 단위: the current page is unchanged;
  - TXT 빈 줄 / 들여쓰기 toggles: the same sentence at the top;
  - reopen after rotating in the library: the saved first char at the top;
  - rotate 5× (phone): no creep.
- **Recents (R §8):**
  1. 활동 보관 안 함;
  2. no background processes;
  3. 강제 중지;
  4. install over it;
  5. Back then 강제 중지 → library;
  6. 정보 → "최근 종료" lists real reasons.
- **Volume:** 볼륨 키 → 위 키로 다음 페이지 (설정 → 넘기기·터치·키) works once back in the book; 키 지정 with a volume key sets the
  direction.
- **Scroll:** S §5.2 Comet STEP items 1–9 and phone SMOOTH items 1–9.
- **Brightness:** U D1 (answer the question; 조명 진단 screenshots), D5 (force-stop put-back test).
- **Chrome:** U D2 (centred crisp label, no grey squares, pin never flips pages), D3 (Korean line breaks), D4 (pin
  persistence).
- **Notes and library:** N §3.4 ⋮ manual 1–4; note jump → turn 3 → Home → `am kill` → recents: the turned page (R
  supersedes `jump_done`); paged library, one update per page; the hub opens in one update.
- **Auto backup:** S §5.2 items 1–12 (incl. an upgrade from R2 → no offer; a Google-restore reinstall → a new
  `installId`).
- **Perf:** the §5.4 gates on the Comet.

---

## 6. Open points for the user (non-blocking; defaults are chosen)

1. **Pin display** (U R13): pinned places show only with the menu open. If the user wants the link on the page,
   `ReturnNav.PIN_FLOATS = true` (one constant).
2. **Page-break default** (A §4.1): 줄 단위 (today's pages). 문단 단위 costs about +22 % pages on web novels.
3. **Brightness** (U D1): the Comet's front light may ignore both paths; the verdict flow says so honestly.
4. **Workaround until H1 ships** (R §3): 최근 앱 → 아이콘 → "이 앱 잠금"; battery → 제한 없음.
5. **[Δ] Peek + a kill that removes the activities** (N peek × U1): after "노트로 열기" without a turn, a force-stop
   or update restarts the task from its root. The library's RESUME then opens the book at the **last saved reading
   place**, not at the note page, because peek never saves. When the activity records survive (LMK, `rp.*` bundle) the
   note page and the peek come back exactly. This is accepted and documented. Making RESUME return to the note would
   need the reader to write the peeked place to `reader_resume` on every pause.

---

## 7. [Δ] Changelog: adversarial critic pass (2026-09-30)

I checked this plan against the specs, `recents.md`, `anchor.md`, `wave2/proto/` and the live tree (HEAD `92be04f` + R2
working tree). Each item names the attack, the evidence and the edit.

| # | Attack | Finding (evidence) | Edit |
|---|---|---|---|
| K1 | Ordering with R2 | R2's working tree rewrites `KeyNames`: "키 지정" now asks for an action and writes `keyBindings` through `KeyAssign.bind`; `assign(app, code, next)` is gone. H3's fold-into-direction was written for `assign`. Through the new editor, a volume binding would bring back R's G4 trap (both volume keys turn forward) | H3 `KeyNames` row re-derived: `bind(volume, NEXT/PREV)` folds into `VolumeMode`; other actions stay bindings; `normalizeVolume` strips bindings; test renamed `bindVolumeFoldsIntoDirection`. Gate 0 lists R2's in-flight files (baseline fact 8) |
| K2 | Ordering with R2, dropped data | R2 moves every TXT section split once (`TxtIndexStore.VERSION` 3 → 4) and adds `PARSE_VERSION` to `textSignature`. Consequences: (a) R1-era quotes (legacy `''` sig) would highlight the wrong words; (b) N's `NoteSig` embeds `textSignature`, so every later parser bump would hide all N-era TXT quotes from the page | RC-A: draw a quote on sig match **or** a lazy `JumpAnchor.matches(text, start, q.text)`, cached per (generation, section); the TOC's "· 위치 바뀜" uses the same result |
| K3 | Phase-0 gaps | P0 fallout misses `LibraryActivity`'s exhaustive `when (listMode)` (COVERS does not compile), `PopupStateTest` (`footerClock`), `SettingsFormatTest` (`TIME_LEFT_*`). Behaviour tests turn red at P0 exit: `LibraryTextTest.nextListMode(GRID)` and `LibrarySchemaV2Test` (asserts version 2; `upgradeStatements(2){error()}` throws under v3). C1 claimed "compile edits only" | §3.10 row; §3.13 table of four named exceptions; C1 corrected; P0 exit wording |
| K4 | Cross-lane code without a skeleton | `trackPx` (U §5.3) lives in RC-A and RC-S, but the lane-aware dot geometry (A §2.3) was only in E2's new `ProgressMath`. Two formulas would mean missed or extra e-ink updates | P0 lands `render/ProgressMath.kt` complete with its test; E2 maintains it |
| K5 | Phase 0 vs E1 | `engine.diff` also patches `Layout.kt` and `Typesetter.kt`, which P0 lands. Applying the whole diff after P0 rejects hunks, and P0's hand-written stub breaks the `Typesetter` hunk's context. The diff dry-runs cleanly on today's `engine/` | P0 applies the `Layout.kt` + `Typesetter.kt` hunks and the two inert `TypesetPass` declaration hunks verbatim; E1 applies the rest (`@@ -386` on) |
| K6 | Golden tests | `@Ignore` hid the PARAGRAPH hash from E1's own runs, and E1 and P0 both claimed to edit `LayoutGoldenTest` | print + `assumeTrue(hash != "TBD")`; P0 is the only editor; the lead pastes the printed value |
| K7 | CI checks that cannot work | `PageView` exposes no text to uiautomator, so `54`/`56`/`32` could never find "the same first word". No RAPerf "relayout" line exists, yet H4's accept and A §8 rely on one. `57` used `raw_equal.py`, which the CI lane creates after H4 | H4 adds a DEBUG `RAPerf show <kind> s: o: a: g: ms` line, `perf_mark`, `raw_equal.py` + `rawshot`. The CI lane adds `perf_log.py` (`first_is`, `no_relayout`). Rows 16, 23, 38–43 rewritten |
| K8 | CI numbering / order | No duplicate names: checked every source (existing 01–50; U 10a/10b/12b/13b–h/14b/14c/51; R 14d, 70–78; A 52–57; N 41–46, 80–90, 92–93; S 60–69b, 95–98). But `54` left 상하 여백 at +10, so `55`'s constant content rows (80…1360) covered the footer band | 54 restores "0"; §5.3 rule: every margin-changing step restores before the next `raw_equal` |
| K9 | Lane isolation | `unittest.sh --own p` copies only `test/<p>`. File-level lanes would compile none of their tests, `AllocCounter` (test root) would be missing for RU, and `--own reader` would pull other lanes' live files | §4 module-mode rule |
| K10 | Ordering inside W | W2 edits `ReaderActivity`/`ContentsDialog`/`ReaderMenus`, which RC-A and EX-N change in W1 | W2 also waits for RC-A and EX-N W1 (C34, §4 table, W2 heading) |
| K11 | Missing perf gates | Not gated: relayout time and "0 relayouts" for repaint-only changes; warm recents return; the 8-step `afterOpen` main-thread cost (first turn after open); count-cache hits after an anchored session; memory after an auto backup triggered from the reader's `onStop` (a 10–20 MB JSON tree in a backgrounded process raises U1's kill risk) | five §5.4 rows plus the DEBUG `count` and `afterOpen` lines (RC-P, RC-A) |
| K12 | Dropped requirement (privacy) | R2's "설정 초기화" rebuilds `AppSettings()` keeping only some fields. The R3 opt-outs `autoBackup` and `recordLookups` (and `brightnessDevice`, `listPaging`) would be silently re-enabled or reset | SET task |
| K13 | U1 × brightness | RESUME/OPEN_LAST draw no library list, so `restoreIfStale` never runs on that path. This is safe only through the reader's "keep an existing pending original" rule | C35 note; RU test case |
| K14 | U1 × N peek | A root restart after a peek returns to the last saved place, not the note page | §6 open point 5 (accepted, documented) |
| K15 | Stale voids | S `14_reading_settings` "first row 넘기는 방식" contradicts C27; A's "relayout line" / `find_node` checks | §1.5 bullets |
| K16 | Shipping U1 early | H was verified but never shipped on its own | H's commit is a standalone build (§2 intro) |

**Checked and found sound (no edit):**
- Lane file sets are disjoint. `CountSaves` lives in `BookSession.kt` (RC-P), not in RC-A's `ReaderMath.kt`. H1/H3
  touch RC-A/SET/LIB files only serially before P0.
- Every U1–U6 item and every spec feature has an owner:
  - S: scroll, side margins and auto backup across E1/E2/RC-*/DA-C/LIB/SET.
  - U: §2–§5 and polish 1–18, each mapped.
  - N: H0, hub, 단어장, colours, library views and paging, W2.
  - A: U2–U4, U6a/b.
  - R: U1, U5.
- Nothing shown or hidden resizes `PageView` after C18. The chrome never shows the system bars
  (`setChromeVisible` has no insets call). The TTS bar, strip, chip, selection, search bar and end panel are overlays;
  dialogs and popups are gated.
- Status toggles are REPAINTs: `BookSession.updateSettings` relays out only on `layoutChanged(layoutPart…)`. A
  `SettingsForwardCompatTest` hazard was ruled out: `hasHeader`/`hasFooterText` are `get()` properties with no backing
  field.
- Anchored layouts keep the LINE golden hash (`lead` is not hashed; the digest enumerates fields). No existing test
  pins JUMP-anchor semantics.
