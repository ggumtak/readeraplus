package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.TapAction

/**
 * Human-readable names for Android key codes and a description of what the reader does with a key
 * (mirrors the reader key map documented in docs/ARCHITECTURE.md; display only). Pure: key codes are
 * plain ints so this runs on the JVM.
 */
object KeyNames {
    const val UNKNOWN = 0
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val SPACE = 62
    const val ENTER = 66
    const val MENU = 82
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val NUMPAD_ENTER = 160
    const val APP_SWITCH = 187

    private val NAMES: Map<Int, String> = HashMap<Int, String>().apply {
        put(UNKNOWN, "알 수 없는 키")
        put(HOME, "홈")
        put(BACK, "뒤로")
        put(5, "통화")
        put(6, "통화 종료")
        for (d in 0..9) put(7 + d, "숫자 $d")
        put(DPAD_UP, "방향키 위")
        put(DPAD_DOWN, "방향키 아래")
        put(DPAD_LEFT, "방향키 왼쪽")
        put(DPAD_RIGHT, "방향키 오른쪽")
        put(DPAD_CENTER, "방향키 가운데")
        put(VOLUME_UP, "볼륨 위")
        put(VOLUME_DOWN, "볼륨 아래")
        put(POWER, "전원")
        put(27, "카메라")
        for (c in 'A'..'Z') put(29 + (c - 'A'), "$c 키")
        put(55, "쉼표")
        put(56, "마침표")
        put(59, "왼쪽 Shift")
        put(60, "오른쪽 Shift")
        put(61, "Tab")
        put(SPACE, "스페이스")
        put(ENTER, "엔터")
        put(67, "백스페이스")
        put(80, "초점")
        put(MENU, "메뉴")
        put(84, "검색")
        put(85, "재생/일시정지")
        put(86, "정지")
        put(MEDIA_NEXT, "다음 트랙")
        put(MEDIA_PREVIOUS, "이전 트랙")
        put(MEDIA_REWIND, "되감기")
        put(MEDIA_FAST_FORWARD, "빨리 감기")
        put(PAGE_UP, "Page Up")
        put(PAGE_DOWN, "Page Down")
        put(111, "Esc")
        put(112, "Delete")
        put(122, "Home 키")
        put(123, "End 키")
        put(126, "재생")
        put(127, "일시정지")
        for (f in 1..12) put(130 + f, "F$f")
        put(NUMPAD_ENTER, "숫자패드 엔터")
        put(164, "음소거")
        put(166, "채널 위")
        put(167, "채널 아래")
        put(168, "확대")
        put(169, "축소")
        put(176, "설정")
        put(APP_SWITCH, "최근 앱")
        put(219, "어시스턴트")
        put(220, "밝기 낮춤")
        put(221, "밝기 높임")
        put(231, "음성 어시스턴트")
        put(272, "앞으로 건너뛰기")
        put(273, "뒤로 건너뛰기")
        put(274, "한 단계 앞으로")
        put(275, "한 단계 뒤로")
        put(280, "탐색 위")
        put(281, "탐색 아래")
        put(282, "탐색 왼쪽")
        put(283, "탐색 오른쪽")
    }

    /** "볼륨 아래", or "키 코드 N" when unknown. */
    fun name(code: Int): String = NAMES[code] ?: "키 코드 $code"

    /** "볼륨 아래 (25)". */
    fun label(code: Int): String = if (NAMES.containsKey(code)) "${NAMES[code]} ($code)" else "키 코드 $code"

    /** Why [code] can't be given an action ("키 지정"), or null when it can. */
    fun unassignableReason(code: Int): String? = when (code) {
        BACK -> "뒤로 키는 지정할 수 없습니다"
        HOME, APP_SWITCH -> "홈 / 최근 앱 키는 앱에 전달되지 않습니다"
        POWER -> "전원 키는 지정할 수 없습니다"
        else -> null
    }

    /** What the reader does with a key. */
    enum class Effect(val label: String) {
        NEXT("다음 페이지"),
        PREV("이전 페이지"),
        MENU("메뉴 열기/닫기"),
        VOLUME("시스템 볼륨 조절"),
        NONE("동작 없음"),
    }

    /**
     * What the reader does with a key, as the key test shows it: a key binding first ("다음 화", "시스템에 맡김"), then
     * [readerEffect] (mirrors `reader.KeyMap.action`).
     */
    fun readerEffectLabel(code: Int, shift: Boolean, app: AppSettings): String {
        val bound = app.keyBindings[code] ?: return readerEffect(code, shift, app).label
        return if (bound == TapAction.NONE) "시스템에 맡김" else KeyActions.label(bound)
    }

    /** Learned page keys first, then volume keys per settings, then the built-in page / menu keys (no bindings). */
    fun readerEffect(code: Int, shift: Boolean, app: AppSettings): Effect {
        if (code in app.nextPageKeys) return Effect.NEXT
        if (code in app.prevPageKeys) return Effect.PREV
        return when (code) {
            VOLUME_DOWN -> when {
                !app.volumeKeysTurn -> Effect.VOLUME
                app.invertVolumeKeys -> Effect.PREV
                else -> Effect.NEXT
            }
            VOLUME_UP -> when {
                !app.volumeKeysTurn -> Effect.VOLUME
                app.invertVolumeKeys -> Effect.NEXT
                else -> Effect.PREV
            }
            PAGE_DOWN, DPAD_RIGHT, DPAD_DOWN, MEDIA_NEXT, MEDIA_FAST_FORWARD -> Effect.NEXT
            SPACE -> if (shift) Effect.PREV else Effect.NEXT
            PAGE_UP, DPAD_LEFT, DPAD_UP, MEDIA_PREVIOUS, MEDIA_REWIND -> Effect.PREV
            MENU, ENTER, DPAD_CENTER, NUMPAD_ENTER -> Effect.MENU
            else -> Effect.NONE
        }
    }
}

/**
 * The actions a key can be given ("이 키로 할 동작", T1-4), in the chooser's order, with the key wording: episodes are
 * 화 and [TapAction.NONE] hands the key back to the system.
 */
object KeyActions {
    val CHOICES: List<TapAction> = listOf(
        TapAction.NEXT, TapAction.PREV, TapAction.NEXT_CHAPTER, TapAction.PREV_CHAPTER, TapAction.TOC, TapAction.MENU,
        TapAction.BOOKMARK, TapAction.REFRESH, TapAction.INVERT, TapAction.TTS, TapAction.AUTO_TURN, TapAction.GOTO,
        TapAction.NONE,
    )

    fun label(a: TapAction): String = when (a) {
        TapAction.NEXT_CHAPTER -> "다음 화"
        TapAction.PREV_CHAPTER -> "이전 화"
        TapAction.NONE -> "없음(시스템에 맡김)"
        else -> a.label
    }
}

/**
 * Editing of the keys the user assigned: [AppSettings.keyBindings] (key → action, what "키 지정" writes now) and the
 * learned page keys of older versions ([AppSettings.nextPageKeys] / [AppSettings.prevPageKeys]). A key has one job:
 * binding it drops it from the learned sets.
 */
object KeyAssign {
    /** Gives [code] the action [action] (a binding wins over the learned sets in the reader; kept in one place). */
    fun bind(app: AppSettings, code: Int, action: TapAction): AppSettings =
        app.copy(
            keyBindings = LinkedHashMap(app.keyBindings).apply { put(code, action) },
            nextPageKeys = app.nextPageKeys - code,
            prevPageKeys = app.prevPageKeys - code,
        )

    /** The action the user gave [code] (binding or learned page key), or null. */
    fun actionOf(app: AppSettings, code: Int): TapAction? = app.keyBindings[code] ?: when (code) {
        in app.nextPageKeys -> TapAction.NEXT
        in app.prevPageKeys -> TapAction.PREV
        else -> null
    }

    /** Every assigned key with its action, by key code (a binding shadows a learned entry of the same key). */
    fun entries(app: AppSettings): List<Pair<Int, TapAction>> {
        val out = LinkedHashMap<Int, TapAction>()
        for (c in app.nextPageKeys) out[c] = TapAction.NEXT
        for (c in app.prevPageKeys) out[c] = TapAction.PREV
        out.putAll(app.keyBindings)
        return out.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /** Removes whatever [code] was assigned (binding and learned entries). */
    fun remove(app: AppSettings, code: Int): AppSettings =
        app.copy(
            keyBindings = if (code in app.keyBindings) app.keyBindings - code else app.keyBindings,
            nextPageKeys = app.nextPageKeys - code,
            prevPageKeys = app.prevPageKeys - code,
        )

    fun clearAll(app: AppSettings): AppSettings =
        app.copy(keyBindings = emptyMap(), nextPageKeys = emptySet(), prevPageKeys = emptySet())
}

/**
 * Key capture for the "키 지정" dialog. The first non-repeated DOWN of an assignable key is captured
 * ([Step.ASSIGN]); the dialog closes on that key's UP ([Step.CLOSE]) and the action chooser opens. Closing on the UP
 * rather than the DOWN keeps the UP inside the dialog, so it never reaches the settings window (where a volume key's
 * UP would go to the system volume handling).
 */
class KeyCapture {
    enum class Step {
        /** Consume and do nothing (repeats, other keys after a capture, stray UPs). */
        IGNORE,
        /** The key can't be assigned ([KeyNames.unassignableReason]); ask for another. */
        REJECT,
        /** [captured] is the key: show it (the action is chosen after the key-up). */
        ASSIGN,
        /** The captured key was released: close the dialog. */
        CLOSE,
    }

    /** Captured key code, or -1 (key code 0 = KEYCODE_UNKNOWN is a valid capture). */
    var captured: Int = -1
        private set

    fun onKey(down: Boolean, keyCode: Int, repeatCount: Int): Step {
        if (down) {
            if (repeatCount != 0 || captured != -1) return Step.IGNORE
            if (KeyNames.unassignableReason(keyCode) != null) return Step.REJECT
            captured = keyCode
            return Step.ASSIGN
        }
        return if (captured != -1 && keyCode == captured) Step.CLOSE else Step.IGNORE
    }
}
