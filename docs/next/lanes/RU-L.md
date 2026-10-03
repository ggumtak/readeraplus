# Lane RU-L

LANE RU-L (stub owner tag in code: "RU") — brightness: light curve, device light, probe, controller
Main files (relative to app/src/main/java/com/ggumtak/readeraplus/): reader/LightController.kt, reader/LightCurve.kt, reader/DeviceLight.kt, reader/LightProbe.kt
Test files (relative to app/src/test/java/com/ggumtak/readeraplus/): reader/LightCurveTest.kt (new); more pure tests under reader/ if you extract decision helpers (e.g. reader/LightPolicyTest.kt)
Module-mode flags <OWN>: --own reader/LightController.kt --own reader/LightCurve.kt --own reader/DeviceLight.kt --own reader/LightProbe.kt --own reader/LightCurveTest.kt  (+ --own for every new file you add, main or test)
Status note: docs/next/RU_L_STATUS.md

TASKS
PLAN §4 "RU" — this run splits RU in two: YOU own the brightness logic; ReaderChrome.kt (with the brightness row views and options panel), ReturnNav, StatusModel and ChromeMath belong to lane RU-C running in parallel. Drive the chrome ONLY through the frozen ReaderChrome setters (setBrightness, setBrightnessCollapsed, setBrightnessOptionsOpen, setSwipeOption, setLightAsk, setLightDevice, setBrightnessUnavailable, setLightPanelRow) and the LightHost interface (docs/R3_INTERFACES.md).
- U §4.1–4.5 with docs/next/ui/brightness.md as the detailed source: LightCurve (pure: out/pos/level/fraction/isExternal); DeviceLight (Settings.System brightness path with U fixes 1–4 — e-ink detection via DeviceClass, never by device codename; a serial IO thread; set/restore/restoreIfStale/refresh; the verdict and ask counters in the local reader_light prefs file, never in JSON backup); LightProbe (light/warm sysfs nodes and vendor settings keys; report() lines for the 조명 진단 page); LightController (lifecycle §4.3 onCreate/afterFirstPage/onAppSettingsApplied/onResume/onPause/onDestroy; the verdict flow and UI states §4.4; dialogs §4.5; window vs device path; the WRITE_SETTINGS opt-in a.brightnessDevice with the Settings.ACTION_MANAGE_WRITE_SETTINGS fallback; markOwnLaunch; the "리더를 나가면 원래 밝기로" restore).
- C33/U §2.3: LightController is the ONLY writer of page.brightnessSwipe (through LightHost.setPageBrightnessSwipe).
- C35/K13: a reader keeps an existing `pending` original and never re-records it (crash rule, brightness.md §4.2); add a test case for it.
- Nothing before the first page: onCreate stays cheap (no IO); device work starts in afterFirstPage, IO on the DeviceLight thread.
Accept: LightCurveTest (and any pure policy tests) green; no IO on main; restore after a force-stop works through restoreIfStale (the library calls it after its first list — LIB wires that).
