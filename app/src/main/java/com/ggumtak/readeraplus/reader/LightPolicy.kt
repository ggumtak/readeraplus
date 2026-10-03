package com.ggumtak.readeraplus.reader

/**
 * The pure decisions behind the brightness flow (UI_SPEC §4.3–4.4, brightness.md §4.3–4.4 and §6): the verdict
 * reducer, the one-time question, what a restore puts back, automatic confirmation and the Korean row texts.
 * No Android types: unit-tested by `LightPolicyTest`. Every text is a constant (bind() allocates nothing).
 */
internal object LightPolicy {
    /** Sessions with an unanswered question after which the reader stops asking (the switch stays). */
    const val MAX_ASKS = 3
    /** A finished drag waits this long before the automatic confirmation reads the device back. */
    const val CONFIRM_DELAY_MS = 400L

    /** [restoreActions] bits. */
    const val RESTORE_LEVEL = 1
    const val RESTORE_MODE = 2

    const val SWIPE_SUBTITLE = "화면 왼쪽 가장자리를 위아래로 밀어 밝기를 바꿉니다"
    const val SWIPE_SUBTITLE_NONE = "이 기기에서는 밝기 스와이프를 쓸 수 없습니다"
    const val DEVICE_OFF = "전면광이 안 바뀔 때 켜세요 · 기기 전체 밝기를 바꿉니다"
    const val DEVICE_ON_RESTORE = "기기 전체 밝기를 바꿉니다 · 리더를 나가면 원래대로"
    const val DEVICE_ON_KEEP = "기기 전체 밝기를 바꿉니다 · 나가도 그대로 유지"
    const val DEVICE_ON_KEEP_AUTO = "기기 전체 밝기를 바꿉니다 · 나가도 그대로 유지 (자동 밝기는 다시 켜짐)"
    const val DEVICE_NO_PERMISSION = "'시스템 설정 수정' 권한이 필요합니다 · 눌러서 허용"
    const val DEVICE_NONE = "이 기기는 앱이 전면광을 바꿀 수 없습니다"
    const val PANEL_SUBTITLE = "색온도(따뜻한 빛)는 기기 조명에서 바꿉니다"
    const val PANEL_SUBTITLE_NONE = "밝기와 색온도는 기기 조명에서 조절합니다"

    /**
     * The verdict after the answer [yes] to question [ask] (§4.3 edges). [DeviceLight.VERDICT_UNKNOWN] means "no
     * verdict yet": for ASK_WINDOW + 아니요 the reader offers the device path next.
     */
    fun nextVerdict(ask: Int, yes: Boolean): Int = when (ask) {
        LightController.ASK_WINDOW -> if (yes) DeviceLight.VERDICT_WINDOW else DeviceLight.VERDICT_UNKNOWN
        LightController.ASK_DEVICE -> if (yes) DeviceLight.VERDICT_DEVICE else DeviceLight.VERDICT_NONE
        else -> DeviceLight.VERDICT_UNKNOWN
    }

    /**
     * The question after the first finished drag of a session with verdict UNKNOWN: on the device path ASK_DEVICE;
     * else ASK_WINDOW on e-ink only (phones get verdict WINDOW silently, [silentWindow]); never after [MAX_ASKS]
     * unanswered sessions.
     */
    fun firstDragAsk(deviceOn: Boolean, eink: Boolean, asks: Int): Int = when {
        asks >= MAX_ASKS -> LightController.ASK_NONE
        deviceOn -> LightController.ASK_DEVICE
        eink -> LightController.ASK_WINDOW
        else -> LightController.ASK_NONE
    }

    /** Phones (not e-ink, window path): verdict WINDOW without a question. */
    fun silentWindow(deviceOn: Boolean, eink: Boolean): Boolean = !deviceOn && !eink

    /**
     * Crash rule (brightness.md §4.2, C35/K13): the first write of an enable records the device's own value only when
     * no original is pending. A reader opened straight after a crash (no library list, so no restoreIfStale) keeps the
     * pending original and never re-records it: the current level is the crashed reader's own.
     */
    fun recordOriginal(pending: Boolean): Boolean = !pending

    /**
     * What a restore writes back ([RESTORE_LEVEL] / [RESTORE_MODE] bits), UI_SPEC §4.3 fixes 1–2:
     * - nothing after a reinstall ([stampOk] false: an auto-backup restored a months-old original);
     * - the level only when asked ([level], "리더를 나가면 원래 밝기로"), an original exists and the device still shows
     *   our last level ([LightCurve.stillOurs]); a level the user set meanwhile wins;
     * - the mode whenever the original was automatic: auto-brightness always comes back.
     */
    fun restoreActions(stampOk: Boolean, level: Boolean, orig: Int, current: Int, last: Int, origAuto: Boolean): Int {
        if (!stampOk) return 0
        var r = 0
        if (level && orig >= 0 && LightCurve.stillOurs(current, last)) r = r or RESTORE_LEVEL
        if (origAuto) r = r or RESTORE_MODE
        return r
    }

    /**
     * Automatic confirmation (brightness.md §4.4): a readable LM3630A node that changed, or (device path) a vendor
     * light key that followed our screen_brightness write, answers 예. A node that did not change is no "아니요".
     */
    fun autoConfirm(ask: Int, cold0: Int, cold1: Int, keys0: Map<String, String>?, keys1: Map<String, String>?): Boolean {
        if (ask == LightController.ASK_NONE) return false
        if (cold0 >= 0 && cold1 >= 0 && cold0 != cold1) return true
        if (ask != LightController.ASK_DEVICE || keys0 == null || keys1 == null) return false
        for ((k, v) in keys1) { val before = keys0[k]; if (before != null && before != v) return true }
        return false
    }

    /** The vendor light state for [autoConfirm]: keys naming cold/warm/light (not the public screen_brightness). */
    fun vendorLightKeys(all: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in all) if (VENDOR_RE.containsMatchIn(k)) out[k] = v
        return out
    }
    private val VENDOR_RE = Regex("(?i)(cold|warm|light)")

    fun swipeSubtitle(none: Boolean): String = if (none) SWIPE_SUBTITLE_NONE else SWIPE_SUBTITLE

    /** The "기기 밝기 직접 조절" subtitle (UI_SPEC §4.4 options rows). */
    fun deviceSubtitle(on: Boolean, restore: Boolean, noPermission: Boolean, none: Boolean, origAuto: Boolean): String = when {
        none -> DEVICE_NONE
        on && noPermission -> DEVICE_NO_PERMISSION
        !on -> DEVICE_OFF
        restore -> DEVICE_ON_RESTORE
        origAuto -> DEVICE_ON_KEEP_AUTO
        else -> DEVICE_ON_KEEP
    }

    /** "기기 조명 설정 열기" shows with a warm channel or at verdict NONE. */
    fun panelRowVisible(none: Boolean, warm: Boolean): Boolean = none || warm
    fun panelSubtitle(none: Boolean): String = if (none) PANEL_SUBTITLE_NONE else PANEL_SUBTITLE
}
