# U1 (returning from recents lands on the library) and U5 (volume-key direction): wave-2 spec

Read against HEAD `92be04f` (WIP checkpoint R2 + tier 1) plus the working tree on 2026-09-30. Line numbers are
approximate (≈) because another workflow is still editing these files. Function names are exact.
CI helper written and tested for this spec: `scratchpad/wave2/recents_ci/same_page.py` (stdlib only; its PNG decoder
matches PIL byte for byte on RGB and RGBA files).

---

## 0. Summary

- **Main cause (C1).** Samsung One UI sometimes removes the app's activities but keeps its card in recents. This
  happens after a force-stop by Device care, sleeping apps or the memory cleaner, after an **in-place update** (now
  routine since `b3dbc72`), or after a first crash, which Android 9+ does not report. When the user taps the card,
  Android restarts the task's **root intent**: `LibraryActivity` with `ACTION_MAIN` + `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`.
  `LibraryText.shouldOpenLast` refuses that flag on purpose, and `openLastOnStart` defaults to false. So the library
  shows, and nothing in the app remembers that a book was open.
- **Second defect (C2), same family.** When the activity records survive a process death, Android recreates
  `ReaderActivity` from its **original launch intent**. The reader saves no instance state, and `setIntent()` never
  reaches the system. So a recreated reader can open the wrong book: the first one opened in that instance, not the one
  reached later through `onNewIntent`, 다음 권 or the TTS notification. For a `content://` file it shows
  "책을 열 수 없습니다". Once the NOTES spec lands it also jumps back to a note.
- **Fix, in three parts.**
  - (a) `ReaderActivity.onSaveInstanceState` saves the book id and the exact anchor. `onCreate` rebuilds a plain
    by-id intent from that state.
  - (b) A persistent "reader open" marker (`reader/ResumeState.kt`). It is set after the first page and cleared only
    when the reader is finished.
  - (c) `LibraryText.startMode` uses the marker: a library that is the first activity of a new process reopens the
    marked book. It holds its own draw, so the screen goes snapshot → page with no library frame.
  - Also: exit-reason diagnostics on the 정보 page, and a CI recents suite with seven scenarios.
- **U5 is already about 90 % built.** `AppSettings.invertVolumeKeys` exists and is persisted and backed up. `KeyMap`,
  library paging, the TOC lists and the key test all honour it. Settings → 넘김 has "볼륨 키 반대로". The user did not
  find it, and the popup doesn't offer it. What is left:
  - the popup's "볼륨 키" row becomes a three-way chooser;
  - the settings row is renamed to "볼륨 키 방향 반전", gets live subtitles and is disabled while volume paging is off;
  - "키 지정" with a volume key sets the direction instead of creating a learned key that silently beats the switch;
  - tests.

---

## 1. How a book reaches the reader today (facts)

### 1.1 Manifest (`app/src/main/AndroidManifest.xml`)

| Item | Value | Effect here |
|---|---|---|
| `LibraryActivity` | `standard`, MAIN/LAUNCHER, exported (≈ l.46-56). The comment says a `singleTask` root would clear the task on icon launch. | It is the **root of the app task**, and its launcher intent is the task's base intent. |
| `ReaderActivity` | `launchMode="singleTask"`, exported. Two VIEW filters (content/file, epub/txt). No `taskAffinity`, so it uses the package default. | Started from the library, it joins the library's task: [Library, Reader]. Started from a file manager with no app task, it becomes the root of a new task. A second start delivers `onNewIntent` and clears anything above it. |
| `configChanges` (reader) | `orientation\|screenSize\|screenLayout\|smallestScreenSize\|keyboardHidden\|keyboard\|navigation\|uiMode` | `density`, `fontScale`, `locale`, `fontWeightAdjustment` and asset/theme changes still **recreate** the reader in process. One UI font style and theme changes are among these. |
| Absent everywhere | `noHistory`, `excludeFromRecents`, `documentLaunchMode`, `clearTaskOnLaunch`, `finishOnTaskLaunch`, `alwaysRetainTaskState`, `autoRemoveFromRecents` | None of these causes the bug. |
| `allowBackup="true"` | No rules file | Everything in `shared_prefs/` is part of Google Auto Backup (§4.1 note). |

### 1.2 Launch paths into the reader

| Path | Code | Intent the system records for the activity |
|---|---|---|
| Library tap / 더보기 → 열기 | `LibraryActivity.openBook` → `ReaderActivity.open(ctx, id)` (`EXTRA_BOOK_ID`, `NO_ANIMATION`) | `book_id = id` |
| 앱 시작시 문서 읽기 (`openLastOnStart`, **default false**, `ReaderSettings.kt` ≈ l.205) | `LibraryActivity.startOpenLast` → `onLastBookLoaded` → `ReaderActivity.open` | `book_id` |
| File manager "open with" | VIEW `content://…` or `file://…` → `IntentFiles.resolveBook` (real path, else a copy in `getExternalFilesDir("books")`) | **The VIEW uri** plus its temporary read grant |
| Another book while a reader exists (file manager, TTS notification `TtsService.notification` ≈ l.244, NOTES hub later) | `onNewIntent` → `setIntent(intent)`, `closeCurrentBook()`, `startOpen(intent)` (≈ l.371-379) | **Unchanged.** The system keeps the instance's first intent. |
| 다음 권 (end panel) | `openBook(id)` → `setIntent(Intent(..).putExtra(book_id))` (≈ l.2685-2690) | **Unchanged**, same as above |
| After a content-uri open | `setIntent(Intent(intent).putExtra(EXTRA_BOOK_ID, b.id))` with the comment "A recreated activity reopens by id" (≈ l.752-755) | **Unchanged.** The comment is wrong: `setIntent` only replaces `Activity.mIntent` in this instance. |

### 1.3 Reader lifecycle (`reader/ReaderActivity.kt`)

- `onCreate` (≈ l.305): `ReaderWindow.setup`, `buildViews`, `startOpen(intent)`. **It never reads
  `savedInstanceState`, and there is no `onSaveInstanceState` override.**
- `startOpen` failures always go to `showError` (the panel "책을 열 수 없습니다" with 다시 시도 / 인코딩 선택 / 닫기).
  Only the **닫기** button calls `finish()`. No failure path finishes the activity or goes to the library by itself.
- `onPause` (≈ l.405) saves the position asynchronously (`ReaderIo.launch { Library.savePosition(...) }`,
  `last_read_at = now`) plus the TXT char fraction. It flushes the reading log, and nothing else.
- There is no `onStop` or `onUserLeaveHint` override. `onTrimMemory` (≈ l.462) calls only `session?.trimMemory()`,
  which is `ImageCache.clear()`: bitmaps are never recycled, so this is not a crash risk. `onDestroy` only closes
  things.
- `finish()` call sites:
  - the error panel's 닫기 (≈ l.534);
  - the chrome's back button (`chromeActions.onBack`, ≈ l.2282);
  - `onBackPressed` → `super.onBackPressed()` (≈ l.2257);
  - `endActions.onEndLibrary` (≈ l.2667);
  - `ReaderMenus` 휴지통 (≈ l.159).

  **All of them are user actions. Nothing exits on background.**
- `ReaderWindow` sets only cutout, decor-fits, window animations, fullscreen and brightness. No `FLAG_SECURE`, no
  recents flags.

### 1.4 Library start (`ui/library/LibraryActivity.kt` ≈ l.231-251, `LibraryText.shouldOpenLast` ≈ l.303-310)

```kotlin
fun shouldOpenLast(enabled: Boolean, restored: Boolean, action: String?, flags: Int): Boolean =
    enabled && !restored && action == Intent.ACTION_MAIN &&
        flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
```

The KDoc gives the reason for the exclusion: "brought back from recents (**the reader is still on top of it there**)".
`LibraryTextTest.shouldOpenLast_onlyOnFreshLauncherStart` repeats it. **That model is wrong.** When the task still has
its activities, returning from recents never creates a `LibraryActivity` at all. A `LibraryActivity` created with
`LAUNCHED_FROM_HISTORY` exists only when the task has **no** activities left, i.e. exactly when the reader is gone
(§2). So the one start where reopening the book matters most is the one the code excludes. That is true even for users
who turned 앱 시작시 문서 읽기 on.

---

## 2. What Android does when a recents card is tapped (AOSP)

`ActivityTaskSupervisor.startActivityFromRecents(taskId)`:

1. **The task still has activities** (`task.getRootActivity() != null`): `moveTaskToFront`. The top activity resumes.
   - If the process died, the top activity is **recreated from its ActivityRecord**, i.e. its **original launch
     intent** (`ActivityRecord.intent` is final; `onNewIntent` and `setIntent` never change it) plus the
     `savedInstanceState` bundle from its last `onStop`.
   - Activities below it are recreated only when they come back to the top.
2. **The task has no activities** (the card is kept in recents as an inactive task): `intent = task.intent`
   (**the base/root intent**), `addFlags(FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)`, then `startActivityInPackage(...)`.
   - For a launcher-started task that is `LibraryActivity` with MAIN/LAUNCHER. There is no saved state.

A task keeps its card but loses its activities after any of these:
- a **force-stop**: One UI Device care "최적화", "사용하지 않는 앱을 절전 상태로", 앱 정보 → 강제 중지, memory cleaners;
- an **in-place update** of the APK (package replaced → force-stop). CI builds have installed in place since `b3dbc72`,
  so this now happens on every update;
- a **crash** of the reader. Since Android 9, `AppErrors` shows no dialog on a first crash: the activity silently
  disappears;
- a process death while an activity has **no saved state** (`ActivityRecord.handleAppDied`: `!mHaveState` → removed),
  or one relaunched more than twice in 60 s;
- a reboot (tasks restored from the task persister).

Case 1 with the records kept is ordinary LMK behaviour. Samsung kills cached apps aggressively, and ours runs with
`largeHeap` and big books.

---

## 3. Causes ranked (with evidence)

| # | Likelihood | Cause | Evidence | Symptom |
|---|---|---|---|---|
| **C1** | **High: this is the reported bug** | The task lost its activities (§2 case 2), recents restarts the root → the library. The app keeps no "a book was open" state, and `shouldOpenLast` rejects `LAUNCHED_FROM_HISTORY` (and `openLastOnStart` is off by default). | `LibraryText.shouldOpenLast` + its KDoc and test comment. `LibraryActivity.onCreate` ≈ l.246. `ReaderSettings.openLastOnStart = false`. AOSP `startActivityFromRecents`. The timing fits too: in-place updates began with `b3dbc72`, and the user returns to the app via recents after the installer. | Exactly "the book was closed and I'm on the library". The page itself is saved (DB), so the book opens at the right page if tapped. |
| **C2** | High (occurs), different look | The reader is recreated from its **original** intent (§2 case 1), with no instance state. `setIntent` at ≈ l.376, 754 and 2687 is lost. | No `onSaveInstanceState` in ReaderActivity. The comment at ≈ l.753 assumes `setIntent` survives. The NOTES spec found the same trap for jumps (NOTES_SPEC §6.1 [Δ] `jump_done`). | A wrong book (after `onNewIntent` or 다음 권). A `content://` book whose grant or real path is gone shows "파일을 읽을 수 없습니다". With NOTES, the reader jumps back to the note in peek mode. |
| **C3** | Medium, unverified | A silent first crash when returning from recents (Android 9+ shows no dialog), then the task falls back to its root → the library. | Not found by reading: `onResume` / `onWindowFocusChanged` wrap the extras in `safely {}`, and `ImageCache` never recycles. The CI suite (§7) captures `logcat -b crash`, and the exit-reason line (§4.7) shows `CRASH` on the phone. | The same as C1. The C1 fix also covers it (the marker survives a crash), with a loop guard. |
| **C4** | Low | Config changes not listed in `configChanges` (density, fontScale, locale, fontWeightAdjustment, theme/overlay) recreate the reader **in process** with the original intent. | Manifest ≈ l.59 | Same as C2. Same fix. Do **not** widen `configChanges`: rebuilding the chrome for density/fontScale in place is bigger than a correct recreate. |
| – | Ruled out | finish() on background, `noHistory`/`excludeFromRecents`/`clearTaskOnLaunch`, `NO_ANIMATION` (it does not affect task placement), a `singleTask` library, an open failure → finish, `onTrimMemory` → close. | §1.1, §1.3 | – |
| – | Ruled out (AOSP) | Task reset after 30 min of inactivity. | `ACTIVITY_INACTIVE_RESET_TIME = 0` (disabled). It also applies only to launcher starts with `RESET_TASK_IF_NEEDED`, not to recents. A zero-cost guard is still added (§4.6) for OEM builds that re-enable it. | – |

Side finding (performance, P2): `onTrimMemory(level >= TRIM_MEMORY_RUNNING_LOW)` also fires on `TRIM_MEMORY_UI_HIDDEN`
(20), i.e. **every time the user opens recents**. It drops all decoded EPUB images, so the first frame after
returning re-decodes them. On e-ink that can mean a second update when an image arrives late. Change the condition to
`level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL`.

**Workaround the user can apply today** (Samsung):
- 최근 앱 → ReaderaPlus 아이콘 → "이 앱 잠금" (keep open);
- 설정 → 애플리케이션 → ReaderaPlus → 배터리 → **제한 없음**.

Turning 앱 시작시 문서 읽기 on does **not** help from recents (C1's flag check).

---

## 4. The fix (exact code)

Owners, R2 style:
- READER_CORE owns `reader/ResumeState.kt` (new), `ReaderActivity.kt` and `ReaderMath.kt`;
- LIBRARY owns `LibraryActivity.kt` and `LibraryText.kt`;
- the lead owns `App.kt` and `AndroidManifest.xml`;
- SETTINGS owns `AboutPage.kt`.

Phase 0 lands `ResumeState` in full (it is tiny) and `LibraryText.StartMode` with its tests.

### 4.1 `reader/ResumeState.kt` (new)

```kotlin
package com.ggumtak.readeraplus.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

/**
 * U1: "the reader is showing a book" across process death.
 * - Set after the first page of a book ([opened]).
 * - Cleared only when the reader is finished ([clear]: Back, 닫기, 서재로, 휴지통, task removal, finishAffinity).
 * When Android later restarts the task from its root (recents after a force-stop, an in-place update or a silent
 * crash), LibraryActivity finds the marker and reopens the book.
 * - Separate prefs file: never the settings prefs (the scroll SPEC's empty-prefs install check, §3.3), and writes
 *   happen only after the first page.
 * - An Auto Backup copy restored on another phone is harmless: the id and the file are validated before use.
 */
object ResumeState {
    private const val PREFS = "reader_resume"
    private const val K_BOOK = "bookId"
    private const val K_TRIES = "tries"
    /** Library-started resumes in a row with no normal reader pause in between; a crash loop stops after this. */
    const val MAX_TRIES = 2

    @Volatile private var prefs: SharedPreferences? = null

    /** Activities created in this process (any class). The library resumes only as the first one. */
    @Volatile var activitiesCreated = 0
        private set

    /** App.onCreate: starts the prefs load off the main thread and counts activity creations. */
    fun init(app: Application) {
        if (prefs == null) prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Dispatched from Activity.onCreate's super call: inside LibraryActivity.onCreate the count includes it.
            override fun onActivityCreated(a: Activity, b: Bundle?) { activitiesCreated++ }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    class Pending(val bookId: Long, val tries: Int)

    fun pending(): Pending? {
        val p = prefs ?: return null
        val id = p.getLong(K_BOOK, -1L)
        return if (id > 0) Pending(id, p.getInt(K_TRIES, 0)) else null
    }

    /** Reader, after the first page of [bookId] (afterOpen). Keeps the try count: only a normal pause resets it. */
    fun opened(bookId: Long) {
        val p = prefs ?: return
        if (p.getLong(K_BOOK, -1L) != bookId) p.edit().putLong(K_BOOK, bookId).apply()
    }

    /** Reader.onPause with a book shown: a normal pause ends any crash streak. */
    fun paused() {
        val p = prefs ?: return
        if (p.getInt(K_TRIES, 0) != 0) p.edit().putInt(K_TRIES, 0).apply()
    }

    /** Library, IO thread, right before it starts the reader (commit: a crash just after must still count it). */
    fun noteAttempt() {
        val p = prefs ?: return
        p.edit().putInt(K_TRIES, p.getInt(K_TRIES, 0) + 1).commit()
    }

    /** The reader was finished, or the marked book is gone. */
    fun clear() {
        val p = prefs ?: return
        if (p.contains(K_BOOK) || p.contains(K_TRIES)) p.edit().clear().apply()
    }
}
```

`App.onCreate` gets one line after `Settings.init(this)`: `ResumeState.init(this)`. The prefs file is about 100
bytes. It loads on the framework's loader thread and is ready long before `LibraryActivity.onCreate` reads it.

### 4.2 `ReaderActivity.kt`

**(a) Instance state: the book and the exact place (fixes C2 and C4, and supersedes NOTES §6.1 `jump_done`).**

```kotlin
// companion
private const val STATE_BOOK = "rp.book"
private const val STATE_SECTION = "rp.section"
private const val STATE_OFFSET = "rp.offset"
private const val STATE_AT = "rp.at"

/** Where a recreated activity was (U1); consumed by the first startOpen. */
private var restoredPlace: ReaderRestore.Place? = null

override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    val b = bookRef ?: return          // still resolving a VIEW uri: the launch intent is the source, as today
    outState.putLong(STATE_BOOK, b.id)
    if (curLayout != null) {
        outState.putInt(STATE_SECTION, anchor.section)
        outState.putInt(STATE_OFFSET, anchor.offset)
        outState.putLong(STATE_AT, System.currentTimeMillis())
    }
    // NOTES (when it lands): outState.putBoolean("rp.peek", peekUntilTurn)
}
```

In `onCreate`, replace the last line `startOpen(intent)`:

```kotlin
val place = ReaderRestore.Place.from(
    savedInstanceState?.getLong(STATE_BOOK, -1L) ?: -1L,
    savedInstanceState?.getInt(STATE_SECTION, -1) ?: -1,
    savedInstanceState?.getInt(STATE_OFFSET, 0) ?: 0,
    savedInstanceState?.getLong(STATE_AT, 0L) ?: 0L,
)
val start = if (place != null) {
    // The system recreates from the LAUNCH intent (setIntent never reaches it): reopen what was on screen, by id,
    // with no VIEW uri (its grant may be gone) and no jump extras (the note was already visited).
    restoredPlace = place
    Intent(this, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, place.bookId).also { setIntent(it) }
} else {
    intent
}
startOpen(start)
```

In `startOpen`, the start-position lines (≈ l.776-780) become:

```kotlin
val remap = TextPositions.remapFraction(storedPos, LayoutKeys.textSignature(eff, d.format, b.encoding),
    b.posSection, b.posOffset, b.progress)
val place = restoredPlace
restoredPlace = null
val kept = place?.let { ReaderRestore.start(it, b.id, b.lastReadAt, remapped = remap != null) }
val start = kept ?: if (remap != null) s.counts.locateFraction(remap) else DocPosition(b.posSection, b.posOffset)
```

The comment at ≈ l.752-755 changes to: "Retry (다시 시도) in this instance reopens by id. A recreated instance uses
onSaveInstanceState." The `setIntent` stays because `retryOpen` reads `intent`.

**(b) The marker.** It is written after the first page and never before it (scroll SPEC rule 2 and
`InstallState.ensure` ordering).

```kotlin
// afterOpen(), first line:
bookRef?.let { ResumeState.opened(it.id) }

// onPause(), after savePositionNow(persistText = true):
if (bookRef != null) ResumeState.paused()

// finish() override, before super.finish(): every user exit goes through it (§1.3 list)
ResumeState.clear()

// onDestroy(), first line: task removal from recents, finishAffinity (backup restore), CLEAR_TOP from 서재로
if (isFinishing && !isChangingConfigurations) ResumeState.clear()
```

When the system kills the process, `onDestroy` does not run, or runs with `isFinishing == false`. Either way the marker
survives, which is the point.

**(c) P2.** Apply the `onTrimMemory` condition from §3 (side finding).

### 4.3 `ReaderMath.kt`: new `object ReaderRestore` (pure, tested)

`ReaderMath.kt` is a file of small objects (`TurnMath`, `PageProgress`, `TextPositions`, …), so this is one more
object:

```kotlin
/** U1: the place a recreated reader was showing, from its instance state (pure). */
object ReaderRestore {
    class Place private constructor(val bookId: Long, val section: Int, val offset: Int, val savedAt: Long) {
        companion object {
            /** null when there is no usable book id. A missing position → section -1 (the database row decides). */
            fun from(bookId: Long, section: Int, offset: Int, savedAt: Long): Place? =
                if (bookId <= 0) null else Place(bookId, section, offset.coerceAtLeast(0), savedAt)
        }
    }

    /**
     * The saved-state place wins over the database row only when: it belongs to this book; it has a position; the
     * text signature did not change (else the TXT fraction remap applies); and the row was not written after it. TTS
     * may keep turning pages, and saving them, after onStop.
     */
    fun start(p: Place, bookId: Long, rowWrittenAt: Long, remapped: Boolean): DocPosition? =
        if (p.bookId == bookId && p.section >= 0 && p.savedAt > 0 && !remapped && rowWrittenAt <= p.savedAt)
            DocPosition(p.section, p.offset) else null
}
```

### 4.4 `LibraryText.kt` + `LibraryActivity.kt`

```kotlin
enum class StartMode { LIBRARY, OPEN_LAST, RESUME }

/**
 * What a new library does first (U1).
 * RESUME: the reader was showing [resumeBookId] when this process's predecessor ended without the user closing it.
 * Reopen it on user starts (MAIN: launcher, or recents restarting the task root with LAUNCHED_FROM_HISTORY, which
 * happens exactly when the task lost its activities) and on system recreation ([restored]). Only as the first
 * activity of the process, and fewer than [maxTries] times in a row. An explicit intent with no action (in-app
 * "서재로", adb `am start -n`) never resumes.
 * Otherwise [shouldOpenLast] (the user's "앱 시작시 문서 읽기"), then the library.
 */
fun startMode(
    openLastOnStart: Boolean, restored: Boolean, action: String?, flags: Int,
    firstActivity: Boolean, resumeBookId: Long, resumeTries: Int, maxTries: Int = 2,
): StartMode = when {
    firstActivity && resumeBookId > 0 && resumeTries < maxTries && (restored || action == Intent.ACTION_MAIN) ->
        StartMode.RESUME
    shouldOpenLast(openLastOnStart, restored, action, flags) -> StartMode.OPEN_LAST
    else -> StartMode.LIBRARY
}
```

`shouldOpenLast` is unchanged in logic. Its KDoc is corrected: "…or started from recents
(LAUNCHED_FROM_HISTORY: the task lost its activities; if a book was open, [startMode] resumes it)". The test comment
changes the same way.

`LibraryActivity.onCreate`, replacing the `if (LibraryText.shouldOpenLast(...)) … else ensureUi()` block. It goes
**after** the scroll SPEC's `InstallState.ensure(...)` line.

```kotlin
val i = intent
val first = ResumeState.activitiesCreated == 1
val pending = if (first) ResumeState.pending() else null
when (LibraryText.startMode(app.openLastOnStart, savedInstanceState != null, i?.action, i?.flags ?: 0,
        first, pending?.bookId ?: -1L, pending?.tries ?: 0, ResumeState.MAX_TRIES)) {
    LibraryText.StartMode.RESUME -> startOpenLast(resumeId = pending!!.bookId)
    LibraryText.StartMode.OPEN_LAST -> startOpenLast()
    LibraryText.StartMode.LIBRARY -> ensureUi()
}
```

`startOpenLast(resumeId: Long = -1L)` keeps the existing draw hold, timeout and `onLastBookLoaded`. Only the IO lookup
changes:

```kotlin
val book = withContext(Dispatchers.IO) {
    runCatching {
        if (resumeId > 0) {
            val b = Library.book(resumeId)?.takeIf { !it.trashed && File(it.path).isFile }
            if (b == null) ResumeState.clear() else ResumeState.noteAttempt()
            b
        } else {
            Library.lastOpened()?.takeIf { !it.trashed && File(it.path).isFile }
        }
    }.getOrNull()
}
```

Why this is instant and e-ink friendly:
- The library window never draws while deciding (`holdDraw`).
- On a recents restart the starting window is the task **snapshot**, i.e. the page the user left. It stays up until
  the reader draws the same page: one e-ink update or none, and never a library frame.
- The IO check costs one primary-key read plus one `stat`.
- The reader then opens by id at the DB position, as with a library tap.

Unchanged: `onResume` (`decidingOpenLast` guard), the `openLastTimeout` fallback, and `ensureUi` on the way back
from the reader.

### 4.5 Why these rules cannot misfire

| Guard | Protects against |
|---|---|
| Marker cleared in `finish()` **and** in `onDestroy(isFinishing)` | Resuming a book the user closed. Covers Back, 닫기, 서재로, 휴지통, swipe-away while the process lives, and backup restore (`finishAffinity`). |
| `activitiesCreated == 1` | In-process library creations: 서재로, `makeRestartActivityTask` after a restore, rotation, a hub below the reader. |
| `restored \|\| action == MAIN` | CI and adb explicit starts, and the in-app 서재로 intent. The existing CI steps keep showing the library. |
| `tries < MAX_TRIES`. It is reset only by a normal `onPause`, and `noteAttempt` uses commit. | A reader that crashes in the foreground being reopened forever. The 2nd consecutive resume without a pause stops, and the library shows. |
| Id and file validated on IO | A deleted or trashed book, or a marker restored from a backup on another device. |

### 4.6 Manifest (defensive, zero cost)

On `LibraryActivity`: `android:alwaysRetainTaskState="true"`. AOSP ignores it (the reset is disabled). An OEM build
that re-enables the "reset task after inactivity" rule would otherwise clear the reader on a launcher tap.

### 4.7 Diagnostics: 정보 → 기기 정보 (SETTINGS, API 30+)

`AboutPage` adds the row "최근 종료" to the 기기 정보 list and its "기기 정보 복사" text.
- It lists the last 3 `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 3)`, formatted
  `MM-dd HH:mm 사유 (importance)`.
- Reasons map to: 메모리 부족 (LOW_MEMORY), 사용자/시스템 강제 종료 (USER_REQUESTED, USER_STOPPED), 앱 업데이트
  (PACKAGE_UPDATED), 오류 (CRASH, CRASH_NATIVE, ANR), 신호 (SIGNALED), 기타 plus `description`.
- The query runs on IO when the page opens.

This turns the user's next screenshot into proof of which trigger their phone uses. It costs nothing at start-up.
Below API 30 the row is hidden.

### 4.8 Copy

MainPage "앱 시작시 문서 읽기" subtitle:
"앱이 시작할 때 최근에 읽었던 책이나 문서를 이어서 봅니다. (끄더라도, 읽던 중 시스템이 앱을 닫았다면 그 책으로
돌아갑니다)"

**No new setting.** Restoring what the system took away is not a preference, and the user asked for this to just work.

### 4.9 What does not change

- Launch modes.
- `configChanges`.
- The open path's cost before the first page. Only one nullable field is read.
- `openLastOnStart` semantics.
- `IntentFiles` / `UriPaths`. A restored reader no longer depends on the VIEW uri, and a root-restarted VIEW task is
  re-granted by the system from the file manager's uid.

---

## 5. Behaviour after the fix

| Scenario | Today | After |
|---|---|---|
| Read → recents → back (process alive) | Same page | Same page (unchanged) |
| … process killed, records kept (LMK) | Book from the **launch** intent at the DB position. The wrong book after `onNewIntent` or 다음 권; an error for a lost `content://`. | **The book on screen, at its exact anchor** |
| … activities removed (force-stop, update, crash) | **Library** | Snapshot → the same book and page |
| Launcher icon after such a kill | Library (unless `openLastOnStart`) | The same book and page |
| User pressed Back / 닫기 / 서재로, then anything | Library | Library (marker cleared) |
| Swipe-away from recents, process alive | Library | Library. If the process died first: the book (acceptable; like ReadEra) |
| Reader crashes twice in a row in the foreground | Library | Resume once more, then the library |
| NOTES jump → read on → process death | (with NOTES) back to the note in peek | The page the user read on to. Peek state carried as `rp.peek`. |

---

## 6. Unit tests

- **`LibraryTextTest.startMode_*`** (new):
  - RESUME for MAIN plus `LAUNCHED_FROM_HISTORY` plus first plus id, even with `openLastOnStart = false`;
  - RESUME when `restored`, first and id;
  - not when `!first`;
  - not with action `null`;
  - not when `tries == 2`;
  - OPEN_LAST when there is no marker and the old rules hold;
  - LIBRARY otherwise;
  - VIEW action → never RESUME.
  - The existing `shouldOpenLast` assertions stay; only their comments change.
- **`ReaderMathTest.restoreStart_*`** (new, `ReaderRestore.start`): wrong book → null; `section == -1` → null; `remapped` → null;
  `rowWrittenAt > savedAt` (TTS turned pages) → null; otherwise the place. `Place.from(-1, …) == null`.
- **`ResumeStateTest`**: not needed. It is a thin prefs wrapper; the pure logic lives in `startMode`.
- Contract: `tools/snapshot_contracts.sh` picks up `ResumeState`, `StartMode` and `ReaderRestore`.

---

## 7. CI emulator verification (`tools/ci/screenshots.sh`, API 34 google_apis)

Add `tools/ci/same_page.py` (the tested file in `scratchpad/wave2/recents_ci/same_page.py`). It compares the page body
of two screenshots: the top 8 % and bottom 12 % are skipped, so the clock never matters. It prints PASS, FAIL or SKIP
and never fails the job.

Append this block before the final `adb logcat -d` lines:

```bash
# ---- U1: back from recents must show the same book and page ------------------------------------------------------
top_is() { # top_is <Activity> <step>: the resumed activity, from dumpsys
  local t; t=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity=|mResumedActivity" | tr -d '\r')
  case "$t" in *"$1"*) log "PASS $2: $1 on top";; *) log "FAIL $2: expected $1, got: $t";; esac
}
same() { python3 tools/ci/same_page.py "shots/$1.png" "shots/$2.png" "$2" | tee -a shots/steps.txt; }
overview_back() { # recents, then the centred (most recent) card
  adb shell input keyevent KEYCODE_APP_SWITCH; sleep 3
  adb shell input tap 360 620; sleep 6
}
launcher_intent="-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.ui.library.LibraryActivity"

log "U1 setup: a launcher-rooted task, a book opened from the library, page 5"
adb shell am force-stop $PKG
adb shell am start -W $launcher_intent | tee -a shots/steps.txt; sleep 4
tap_label "샘플 EPUB" contains; sleep 5
for i in 1 2 3 4; do adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 1; done
shot 70_before 2; top_is ReaderActivity 70_before

log "U1-A: killed in the background, records kept (LMK)"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill $PKG; sleep 1
log "pid after am kill: '$(adb shell pidof $PKG | tr -d '\r')' (expected empty)"
overview_back; shot 71_after_kill 0; top_is ReaderActivity 71_after_kill; same 70_before 71_after_kill

log "U1-B: activities removed (force-stop = One UI cleaner / sleeping apps)"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am force-stop $PKG
adb shell dumpsys activity recents | grep -E "realActivity|baseIntent" | grep $PKG | head -2 | tee -a shots/steps.txt
overview_back; shot 72_after_force_stop 0; top_is ReaderActivity 72_after_force_stop; same 70_before 72_after_force_stop

log "U1-C: the exact intent recents sends for a task without activities (deterministic)"
adb shell am force-stop $PKG
adb shell am start -W -f 0x10100000 $launcher_intent | tee -a shots/steps.txt   # NEW_TASK | LAUNCHED_FROM_HISTORY
sleep 5; shot 73_history_intent 0; top_is ReaderActivity 73_history_intent; same 70_before 73_history_intent

log "U1-D: app updated in place while in the background"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb install -r -g "$APK" | tee -a shots/steps.txt
overview_back; shot 74_after_update 0; top_is ReaderActivity 74_after_update; same 70_before 74_after_update

log "U1-E: a second book via onNewIntent, then killed: the SECOND book comes back"
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Download/sample-utf8.txt \
  -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
sleep 4; adb shell input keyevent KEYCODE_PAGE_DOWN; shot 75_second_book 2
adb shell input keyevent KEYCODE_HOME; sleep 2; adb shell am kill $PKG; sleep 1
overview_back; shot 76_second_after_kill 0; top_is ReaderActivity 76_second_after_kill; same 75_second_book 76_second_after_kill

log "U1-F: 'Don't keep activities' (One UI developer option): destroyed and recreated in process"
adb shell settings put global always_finish_activities 1
adb shell input keyevent KEYCODE_HOME; sleep 2
overview_back; shot 77_dont_keep 0; top_is ReaderActivity 77_dont_keep; same 75_second_book 77_dont_keep
adb shell settings put global always_finish_activities 0

log "U1-G (control): the user closed the book with BACK: the library must come back"
back; sleep 2; top_is LibraryActivity 78_closed
adb shell input keyevent KEYCODE_HOME; sleep 1; adb shell am force-stop $PKG
adb shell am start -W -f 0x10100000 $launcher_intent | tee -a shots/steps.txt
sleep 5; shot 78_closed_then_recents 0; top_is LibraryActivity 78_closed_then_recents
```

What to expect:
- Today: A passes (same book), B, C and D **FAIL** (LibraryActivity on top), E **FAIL** by `same` (sample EPUB
  instead of sample-utf8), F passes, G passes.
- After the fix: every step PASSes.

Notes on the steps:
- `am kill` only kills a cached background process. It behaves like LMK, and the logged `pidof` proves the kill.
- B depends on the card surviving `force-stop`. That holds on AOSP 34, and the `dumpsys activity recents` line records
  it. C is the deterministic equivalent.
- The overview tap at (360, 620) hits the focused (most recent) card at 720×1440 / 320 dpi.
- `shots/crash.txt` (existing) catches C3. Any `FATAL EXCEPTION` in it after this block is a finding.

Other specs' CI steps: every existing step starts the library with `am start -n …` (no action). `startMode` never
resumes for those, so the older expectations (e.g. `40_library_after`) stay valid.

---

## 8. Manual check on the Samsung (One UI) and the Comet

1. Read a few pages. Then 개발자 옵션 → **활동 보관 안 함** on → 최근 앱 → back: the same page (F).
2. 개발자 옵션 → **백그라운드 프로세스 수 제한 → 백그라운드 프로세스 없음** → open another app → 최근 앱 → back:
   the same page (A).
3. With the reader in recents, go to 설정 → 애플리케이션 → ReaderaPlus → **강제 중지** → 최근 앱 → ReaderaPlus: the
   same page (B).
4. Install the next CI build over it from the browser or 내 파일 → 최근 앱 → ReaderaPlus: the same page (D).
5. Back to the library, then 강제 중지 → 최근 앱: **the library** (G).
6. 정보 → 기기 정보 → "최근 종료" shows the real reasons (e.g. "앱 업데이트", "메모리 부족").

---

## 9. U5: 볼륨 키 방향 반전

### 9.1 What exists (no model, prefs or backup change needed)

| Piece | Where |
|---|---|
| Field | `AppSettings.invertVolumeKeys: Boolean = false` (`settings/ReaderSettings.kt` ≈ l.182). It applies only when `volumeKeysTurn` (default true). |
| Persistence | `Settings.kt` key `a.invertVolumeKeys` (≈ l.120, l.228). Backup `SettingsJson.kt` (≈ l.144, l.195). |
| Reader | `KeyMap.resolve`: VOLUME_DOWN → PREV / VOLUME_UP → NEXT when inverted. `KeyMap.action` puts `keyBindings` and learned keys first. `dispatchKeyEvent` hands unassigned volume keys to the system while TTS speaks (speech volume). That behaviour is kept. |
| Elsewhere | Library list paging (`LibraryText.keyDirection` / `pageDirection`), TOC and extras lists (`ContentsDialog` ≈ l.879, `ExtrasUi` ≈ l.189), the key test (`KeyNames.readerEffect`) |
| UI | Settings → 페이지 넘김 → 버튼 · 키: "볼륨 키 반대로 / 볼륨 위 = 다음, 볼륨 아래 = 이전" (`PageTurningPage` ≈ l.103). **The reader popup only has "볼륨 키로 페이지 넘김"** (`ReadingSettingsPopup.addPageTurning` ≈ l.492). |
| Tests | `KeyMapTest.volumeSwitches`, `KeyNamesTest` (≈ l.51), `TocTextTest` (≈ l.102), `SettingsJsonTest` (≈ l.33) |

Gaps:
- **G1.** It is invisible where people look while reading (the popup).
- **G2.** The wording "반대로" does not match the user's "방향 반전", and the subtitle never states the current
  mapping.
- **G3.** The invert row stays active while volume paging is off, where it does nothing.
- **G4.** "다음/이전 페이지 키 지정" accepts a volume key into `nextPageKeys`/`prevPageKeys`, and those **win over**
  the switch.
  - A user who first tried 키 지정 (e.g. set VOLUME_UP → 다음) ends up with **both** volume keys turning forward, and
    the switch then seems broken.
- **G5.** `KeyNames.readerEffect` ignores `keyBindings`, so the key test can disagree with the reader.

### 9.2 Popup (EXTRAS_TOOLS, `ReadingSettingsPopup.addPageTurning`)

The switch "볼륨 키로 페이지 넘김" is replaced by one `dropdownRow("볼륨 키", volumeShort(mode))`.
- The **row count is unchanged**, so UI_SPEC §6 P1-8's budget and the "넘기는 방식 first" rule of the scroll SPEC
  hold.
- The section under 더보기 becomes: 넘기는 방식 · 화면 터치 · **볼륨 키**.

| Entry (list) | Short value | Settings |
|---|---|---|
| 아래 = 다음 페이지 (기본) | 아래 = 다음 | `volumeKeysTurn = true, invertVolumeKeys = false` |
| 위 = 다음 페이지 (방향 반전) | 위 = 다음 | `volumeKeysTurn = true, invertVolumeKeys = true` |
| 넘기지 않음 (볼륨 조절) | 끔 | `volumeKeysTurn = false` (`invertVolumeKeys` kept, so switching back restores it) |

How it behaves:
- Choosing an entry calls `Settings.saveApp(KeyMap.withVolumeMode(Settings.app, m))`.
- The value text updates in place.
- It is an `AppSettings` change, so there is **no relayout and no page redraw** (U6: nothing moves).
- The next volume press uses it at once (`app` is read live in `dispatchKeyEvent`).
- A three-way chooser instead of a second switch: no disabled row, one row of height, and the state reads as a
  sentence.

### 9.3 Settings page (SETTINGS, `PageTurningPage`, section "버튼 · 키")

- "볼륨 키로 넘김": the subtitle is live, `SettingsFormat.volumeSummary(app)`:
  - off → "끄면 볼륨 키는 소리 크기를 조절합니다";
  - on → "볼륨 아래 = 다음, 볼륨 위 = 이전";
  - on + inverted → "볼륨 위 = 다음, 볼륨 아래 = 이전".
- "볼륨 키 반대로" is renamed **"볼륨 키 방향 반전"**, subtitle "볼륨 위 키로 다음 페이지를 넘깁니다".
  - While volume paging is off, the row is disabled: title `Ink.DISABLED`, summary "‘볼륨 키로 넘김’을 켜면 쓸 수
    있습니다", not clickable, toggle alpha 0.4.
  - It follows the other toggle live.
  - New helper `View.setRowEnabled(enabled)` in `SettingsPage.kt`, next to `setSummary`.
- Toggling either row also updates the other row's summary.
- The existing note stays.

### 9.4 Learned keys (SETTINGS, `KeyNames.kt` → `KeyAssign`) closes G4

```kotlin
private val VOLUME_KEYS = setOf(KeyNames.VOLUME_UP, KeyNames.VOLUME_DOWN)

/** A volume key sets the volume direction instead of becoming a learned key (a learned key would beat the switch). */
fun assign(app: AppSettings, code: Int, next: Boolean): AppSettings {
    if (code in VOLUME_KEYS) {
        val upIsNext = (code == KeyNames.VOLUME_UP) == next
        return app.copy(volumeKeysTurn = true, invertVolumeKeys = upIsNext,
            nextPageKeys = app.nextPageKeys - VOLUME_KEYS, prevPageKeys = app.prevPageKeys - VOLUME_KEYS)
    }
    return if (next) app.copy(nextPageKeys = app.nextPageKeys + code, prevPageKeys = app.prevPageKeys - code)
    else app.copy(prevPageKeys = app.prevPageKeys + code, nextPageKeys = app.nextPageKeys - code)
}

/** Removes learned volume keys left by older versions (called when either volume row changes). */
fun normalizeVolume(app: AppSettings): AppSettings =
    if (app.nextPageKeys.none { it in VOLUME_KEYS } && app.prevPageKeys.none { it in VOLUME_KEYS }) app
    else app.copy(nextPageKeys = app.nextPageKeys - VOLUME_KEYS, prevPageKeys = app.prevPageKeys - VOLUME_KEYS)
```

The capture dialog text for a volume key: "볼륨 위 → 다음 페이지 (볼륨 아래는 이전 페이지)". The keys list
(`fillKeys`) no longer shows volume keys.

Existing users are not migrated silently: a learned volume key keeps working until either volume row is touched, which
applies `normalizeVolume`. `KeyMapTest.learnedKeysWin` stays valid, because `resolve` is unchanged.

`keyBindings` (T1-4, no editor yet) still win. When a volume key has a binding, both volume rows show the summary
"키 지정에서 볼륨 키 동작을 정했습니다" and are disabled.

### 9.5 Pure helpers (READER_CORE, `KeyMap.kt`)

```kotlin
enum class VolumeMode { OFF, DOWN_NEXT, UP_NEXT }
fun volumeMode(a: AppSettings): VolumeMode =
    if (!a.volumeKeysTurn) VolumeMode.OFF else if (a.invertVolumeKeys) VolumeMode.UP_NEXT else VolumeMode.DOWN_NEXT
fun withVolumeMode(a: AppSettings, m: VolumeMode): AppSettings = when (m) {
    VolumeMode.OFF -> a.copy(volumeKeysTurn = false)
    VolumeMode.DOWN_NEXT -> a.copy(volumeKeysTurn = true, invertVolumeKeys = false)
    VolumeMode.UP_NEXT -> a.copy(volumeKeysTurn = true, invertVolumeKeys = true)
}
/** True when a key binding decides a volume key (the volume rows are then disabled). */
fun volumeBound(a: AppSettings): Boolean =
    a.keyBindings.containsKey(KeyEvent.KEYCODE_VOLUME_UP) || a.keyBindings.containsKey(KeyEvent.KEYCODE_VOLUME_DOWN)
```

The labels go in `ReaderFormat.volumeMode(m)` / `volumeModeShort(m)` and are shared by the popup and the page.
`KeyNames.readerEffect` becomes a mapping of `KeyMap.action(code, shift, app)` (TapAction → Effect; NONE on a volume
key → VOLUME). This closes G5.

### 9.6 U5 tests

- `KeyMapTest.volumeModeRoundTrip`: every mode → settings → mode. OFF keeps `invertVolumeKeys`. Inverted UP → NEXT
  and DOWN → PREV via `action`. PAGE_DOWN is unaffected. A binding on VOLUME_UP wins, and `volumeBound` is true.
- `KeyNamesTest.assignVolumeFoldsIntoDirection`:
  - (UP, next) → invert true, turn true, no volume key in either set;
  - (DOWN, next) → invert false;
  - (UP, prev) → invert false;
  - (DOWN, prev) → invert true;
  - older learned volume keys are stripped;
  - `normalizeVolume` is a no-op without them.
- `KeyNamesTest.readerEffectFollowsBindings`: a binding VOLUME_DOWN → MENU → Effect.MENU.
- `LibraryTextTest`: `keyDirection` inverted. Add the missing case: inverted plus volume off → 0.
- CI step `14c_volume_mode`:
  1. in the popup, `tap_label "더보기" contains`, scroll, then `tap_label "볼륨 키"`, shot the list,
     `tap_label "위 = 다음" contains`, back;
  2. `adb shell input keyevent KEYCODE_VOLUME_UP`, then shot. The page label must be one page further than before.

---

## 10. Merge notes with the three specs

- **Scroll SPEC.**
  - `LibraryActivity.onCreate` order: `InstallState.ensure` → `startMode` decision.
  - `ReaderPresence.inFront = true` in the reader's `onCreate` is unaffected.
  - The marker lives in its own prefs file and is written in `afterOpen`, so the "reader writes nothing to the
    settings prefs before the first page" rule and the empty-prefs install detection hold.
  - In scroll mode, `onSaveInstanceState` saves the same `anchor` that `savePositionNow` saves (the first visible
    character), so both paths agree.
- **NOTES_SPEC §6.1 [Δ] `jump_done`.** Superseded: a restored reader rebuilds a by-id intent with no jump extras.
  NOTES adds only `rp.peek` (restore `peekUntilTurn`, so a recreated peek still does not save). With peek restored,
  `ReaderRestore.start` keeps the peeked page (the DB row is older) and saving stays off until a turn.
- **UI_SPEC.**
  - The popup row budget is unchanged (§9.2).
  - `book_prefs.return_mark` already survives death.
  - The transient return chip stack is not restored. Optional P2: `rp.returns` as an `IntArray` of (section, offset)
    pairs, at most 16.
- **U6.** A restored reader opens with `Nav.OPEN` at the saved anchor, which U6's anchored pagination makes exact.
  Nothing here shows or hides chrome.

## 11. Risks

| Risk | Mitigation |
|---|---|
| A resume the user did not want (they swiped the card away, the process died, later they tap the icon) | Acceptable, like ReadEra: Back returns to the library. With the process alive, `onDestroy(isFinishing)` clears the marker. |
| A crash loop through the resume path | `MAX_TRIES = 2`, reset only by a normal pause; `noteAttempt` uses commit. |
| The Launcher3 overview tap misses on a different emulator image | C (the deterministic intent) and `top_is` from dumpsys don't depend on coordinates. |
| `activitiesCreated` counting in multi-process setups | The app is single-process (no `android:process` anywhere). |
