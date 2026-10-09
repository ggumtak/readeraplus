# RU-L 밝기 제어 구현 결과 (2026-10-03)

밝기 로직(LightCurve·DeviceLight·LightProbe·LightController)을 구현했다. 순수 판단은 새 `LightPolicy`로 분리했다.
밝기 행과 옵션 패널의 뷰는 RU-C(ReaderChrome)가 맡는다. 이 레인은 고정된 setter와 LightHost로만 화면을 다룬다.
ReaderActivity 연결은 RC-A가 한다.

- **LightCurve**: `out = p²`, `pos = √out`, `level`/`fraction`(1..255, NaN은 0), `isExternal`, fix 1의 `stillOurs`
  (|현재 − 마지막| ≤ max(2, 마지막/32)). 기기 경로 전용이다. 창 경로는 기존처럼 선형 `ReaderWindow.applyBrightness(pos)`.
- **DeviceLight**: 직렬 `reader-light` HandlerThread 한 개를 처음 쓸 때 만든다.
  - 드래그 중 메인 스레드는 float 하나와 CAS, 재사용 Runnable만 쓴다. 쓰기는 100 ms에 한 번 이하.
  - 원래 값·모드·설치 stamp는 첫 쓰기 전에 commit한다.
  - fix 1: `K_LAST`는 드래그가 끝날 때(`settle`)와 활성화 후 첫 쓰기 때만 저장한다. 복원은 `stillOurs`일 때만 원래 밝기를 되돌린다.
  - fix 2: 자동 모드는 "나가도 그대로 유지"에서도 되돌린다 (`restore(level=false)`).
  - fix 3: 활성화마다 light 스레드에서 `canWrite`를 한 번 확인한다. 권한이 없으면 pending을 기록하지 않는다.
  - fix 4: e-ink 판정은 `DeviceClass`만 쓴다.
  - C35/K13: 이미 있는 pending 원래 값은 다시 기록하지 않는다 (`LightPolicy.recordOriginal`).
  - 다른 설치에서 온 pending은 새로 기록한다. 복원 중 SecurityException이 나면 pending을 유지한다.
  - 외부 변경(기기 패널)이 생기면 그 값이 되돌릴 원래 값이 된다. 원래 모드는 유지한다.
  - verdict·질문 횟수는 `reader_light` prefs에만 둔다 (FINGERPRINT 단위, 백업 제외).
- **LightProbe**: 조명 키 조회, LM3630A cold/warm 노드, xrz getter(리플렉션, 읽기만), 조명 진단 `report()` 줄.
- **LightController**:
  - 수명주기 §4.3: onCreate는 창 속성만 다룬다. 기기 쓰기·verdict·관찰자·warm probe는 afterFirstPage 이후에 시작한다.
  - 리더를 떠나면(onPause) 복원한다. 화면 꺼짐, 자체 설정 화면(markOwnLaunch), 구성 변경 재생성일 때는 복원하지 않는다.
  - verdict 흐름 §4.4: 폰은 조용히 WINDOW로 정한다. ASK_WINDOW는 최대 3세션이다. ASK_DEVICE는 기기 경로가 켜져 있고 UNKNOWN이면 매번 묻는다.
  - 자동 확인은 400 ms 뒤 IO에서 노드·벤더 키를 비교한다.
  - 대화상자 A/B/C, 권한 페이지(package uri → uri 없음 → 대화상자 C), 권한 재허용 감지.
  - `page.brightnessSwipe`의 유일한 쓰기 주체다 (C33).
  - 일시정지 중에는 기기 쓰기를 하지 않는다 (늦은 IO 결과가 복원 뒤에 쓰지 않도록).

검사:

- `tools/typecheck.sh` (모듈·전체): 성공.
- `tools/unittest.sh` 모듈 16개, 전체 **1,211개 통과** (기준 1,195 + 새 16).
- 새 테스트 `LightCurveTest`(곡선·범위·255단계 왕복·isExternal·stillOurs·KEY_RE·C35 크래시 규칙·진단 줄)와
  `LightPolicyTest`(verdict 다섯 경로·질문 제한·복원 판단·자동 확인·한국어 문구).
- 독립 리뷰 두 건(사양·버그)에서 나온 지적을 모두 반영했다. 자동 모드 유실, 복원 뒤 쓰기, 실패한 복원, 백업된 pending,
  재생성 깜빡임, K_LAST 갱신, ASK_DEVICE 제한, origAuto 첫 표시가 그 대상이다.

RC-A 연결과 남은 일:

- §1.6.2 순서대로 연결한다: `light.onCreate/afterFirstPage/onAppSettingsApplied/onResume/onPause/onDestroy(isFinishing)`,
  `markOwnLaunch`(설정 화면 시작 직전), `brightnessStart → currentPos()`, `onBrightness → onDrag`, `bindChrome → bind()`.
- `applyAppSettings`·`setBrightness`의 직접 `ReaderWindow.applyBrightness` 호출과 `page.brightnessSwipe =` 대입을 지운다.
- LIB: 첫 목록 뒤 `ReaderIo.launch { DeviceLight.restoreIfStale(applicationContext) }`.
- RU-C: `onOpenPanel`은 기기 조명 설정(ACTION_DISPLAY_SETTINGS)을 연다. 옵션 패널 토글이 아니다.
- 기기 확인: Comet에서 질문에 답한다(D1). 조명 진단 스크린샷을 찍는다. 강제 종료 뒤 서재에서 복원되는지 본다(D5).
