# Remote lane brief (R3 parallel build, 2026-10-03)

You are ONE lane of a parallel build. Fourteen other cloud sessions are building the other lanes at the same time, each
in its own container and on its own branch; the lead session merges every branch into `ccr-0ef3b80a-k64gx2`.
Your lane definition is `docs/next/lanes/<LANE>.md` (named in your launch message). Do exactly that lane, completely and
carefully. This is production code for the user's own e-ink reader (Bigme/Innospace "Comet", ~720×1440, e-ink) and phones.

## 0. Setup (first, ~2 minutes)

```bash
mkdir -p /opt/tc && cd /opt/tc && M=https://repo1.maven.org/maven2 && \
curl -sSfL -o android-all-15.jar $M/org/robolectric/android-all/15-robolectric-12650502/android-all-15-robolectric-12650502.jar && \
curl -sSfL -o kotlinx-coroutines-core-jvm-1.9.0.jar $M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar && \
curl -sSfL -o junit-4.13.2.jar $M/junit/junit/4.13.2/junit-4.13.2.jar && \
curl -sSfL -o hamcrest-core-1.3.jar $M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar && \
curl -sSfL -o json-20240303.jar $M/org/json/json/20240303/json-20240303.jar && \
curl -sSfL -o kotlinc.zip https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip && \
unzip -q -o kotlinc.zip && unzip -tq android-all-15.jar | tail -1
```
Then, in the repository root and BEFORE you edit anything: `tools/snapshot_contracts.sh` (freezes the base tree as
the module-mode contract snapshot in /opt/tc/contracts; never run it again afterwards).
Baseline (already verified by the lead on this commit): full typecheck green, 1,195 unit tests OK.

Repository: /home/user/readeraplus (branch ccr-0ef3b80a-k64gx2), an Android e-book reader "ReaderaPlus" in Kotlin (platform Views, no AndroidX). Package root: app/src/main/java/com/ggumtak/readeraplus/ ; tests under app/src/test/java/com/ggumtak/readeraplus/ at the same relative path.

Where things are:
- Build plan (authoritative): docs/next/wave2/PLAN.md — §0 precedence and user priorities, §1 conflict resolutions (C1–C35) and merged algorithms/orders (§1.6, incl. §1.6.3 final settings rows), §4 the lanes. When PLAN says `scratchpad/<x>`, read `docs/next/<x>`.
- Contract (frozen signatures, owners, threading): docs/R3_INTERFACES.md, docs/ARCHITECTURE.md.
- Specs: S = docs/next/scroll/SPEC.md; U = docs/next/ui/UI_SPEC.md (+ docs/next/ui/chrome.md, brightness.md, audit.md); N = docs/next/notes/NOTES_SPEC.md (+ docs/next/notes/highlights.md, hub.md, library.md); R = docs/next/wave2/recents.md; A = docs/next/wave2/anchor.md; the user's own requests = docs/next/wave2/USER_ADDENDUM.md. Precedence: USER_ADDENDUM > PLAN's explicit resolutions > A and R > S, U, N (each on its own subject).
- Done and committed before this run: Step H (H1–H4), Phase 0 contract (every shared type/field/schema v3, stubs marked `// R3 stub (owner: X)` or `TODO("owner: X")`), E1 engine, E2 renderer, RC-P session/counts, RC-S scroll core. Their notes: docs/next/*_STATUS.md.

Standing user directives:
- NO page-turn animation anywhere: taps, keys and auto paging replace the screen instantly in every mode and on every device (overrides any 180 ms step / startScroll text in the specs).
- Priorities: books open instantly (nothing new before the first page), instant page turns, e-ink first (one screen update per action, no animation, no grey-only state), a refined UI, and the reading position never moves when anything is shown/hidden or a setting changes.
- All UI text is Korean, exactly as the specs write it.

Rules:
1. Edit ONLY your lane's files (listed below; new files you create inside your lane's paths count). Frozen (Phase 0 only): settings/*, data/SettingsJson.kt, data/LibrarySchema.kt, data/Models.kt, engine/Layout.kt, render/Render.kt, render/StatusDecor.kt, ui/kit/Ui.kt, ui/kit/Toggle.kt, reader/ReaderHost.kt, the frozen block of reader/extras/ReaderPanels.kt, App.kt, AndroidManifest.xml, res/**, docs/** (except your own status note). Other lanes are editing the other files in parallel on other branches: never touch, revert, reformat or "fix" files outside your list, or the merge breaks. If you need something outside your files, work with the existing contract and record the need under contractRequests.
2. ReaderActivity.kt, ReaderMenus.kt, ReaderFormat.kt, ReaderMath.kt, ReaderWindow.kt, KeyMap.kt, PageView.kt and the other RC-A files are wired LATER by the RC-A integration lane. Don't edit them; list precisely what RC-A must call/wire for your work (rcaNotes: which method, when, on which thread, with what arguments).
3. Replace every `// R3 stub (owner: <your tag>)` and `TODO("owner: <your tag>")` in your files with the real implementation; at the end `grep -n 'R3 stub\|TODO("owner' <your files>` must print nothing. Keep the public signatures frozen in docs/R3_INTERFACES.md (callers in other files compile against them); you may add private/internal helpers and new files inside your lane's paths.
4. Threading: Views, BookSession state and decor on main; DB/file IO off main through the existing job helpers; no IO, probe, DB write, counting, backup or device-light work before the reader's first page. Draw, turn and status-update paths allocate nothing.
5. Put pure logic into pure Kotlin objects/classes with JUnit4 tests. android.graphics (Canvas/Paint/Bitmap), SQLite and View classes are native-backed and do not run on the JVM test classpath (android-all jar); org.json and plain java work. Look at existing tests for patterns.
6. Local checks (no Android SDK; toolchain in /opt/tc; contract snapshot /opt/tc/contracts = the committed HEAD when this run began — NEVER run tools/snapshot_contracts.sh):
     tools/typecheck.sh <OWN>      and      tools/unittest.sh <OWN>
   <OWN> repeats `--own <path relative to the package root>` for EVERY main file AND EVERY test file of your lane, including new ones (a test file you don't list is not compiled; a new main file you don't list is not compiled either). You can append a test class FQN to unittest.sh to run just that class. Both must be green at the end (unittest prints `OK (N tests)`). Module mode is the fast loop; at the end ALSO run the full-tree versions (no arguments) — in your container only your lane differs from the base, so both must be green. A compile takes 1–3 minutes; another lane may be compiling concurrently.
7. Git: commit only your lane files plus your two notes (see §Finish). Never rewrite history; never touch other branches.
8. Match the surrounding code style (naming, KDoc/comment density, idioms). No new libraries.
9. Write a short Korean status note at docs/next/<STATUS FILE> in the style of docs/next/RC_S_STATUS.md: what was implemented, decisions/deviations, the checks you ran with test counts, and what remains for RC-A wiring / CI / device checks.
Return the structured result: every path you created, modified or deleted (repo-relative, e.g. app/src/main/java/com/ggumtak/readeraplus/reader/ReaderChrome.kt, including tests and the status note), a summary, the checks with results, contractRequests, rcaNotes, deviations (spec lines you deliberately did differently, and why), and complete=true only when every task of the lane is done.
## Finish (in this order)

1. As soon as the lane compiles (module-mode typecheck green), make a WIP commit of your lane files and push it
   (`git push -u origin HEAD:<your branch>`; the branch is named in your launch message), so nothing is lost if the
   container goes away. Push again after every major step.
2. Independent review: launch two reviewer subagents (Agent tool, in parallel), each given this brief, your lane file
   and `git diff <base>..HEAD` (base = the commit you started from). Reviewer A checks spec completeness (every TASK
   item, every Korean string, order and state the spec names; stubs left). Reviewer B hunts bugs (crashes, threading,
   lifecycle, persistence/SQL, e-ink: one update per action and no animation, allocations in draw/turn/status paths,
   anything that can move the reading position, frozen-signature changes, edits outside the lane). Tell them to
   verify each finding in the code, report only real ones with file/line/fix, and not to edit files.
3. Fix every real finding; finish anything missing. Module-mode checks green, then the FULL-tree checks green:
   `tools/typecheck.sh` and `tools/unittest.sh` (must end `OK (N tests)`).
4. Write the Korean status note `docs/next/<STATUS FILE>` (style of docs/next/RC_S_STATUS.md) and the machine-readable
   result `docs/next/lanes/<LANE>.result.json`:
   `{"lane": "...", "files": [repo-relative paths created/modified/deleted], "summary": "...", "checks": "...",
     "contractRequests": [...], "rcaNotes": [...], "deviations": [...], "complete": true|false}`
   rcaNotes = exactly what the ReaderActivity integration must call/wire for your work (method, when, thread, args).
   contractRequests = changes you needed in frozen or other lanes' files (you did NOT make them).
5. Commit with a concise English title (≤ 72 chars) ending in ` [skip ci]`, a 2–6 line English body, then a blank
   line and exactly:
   ```
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01K8wKjcQ2JYJBU84tS9qh1b
   ```
   Push to your branch (retry network errors up to 4 times with 2/4/8/16 s backoff). Do NOT open a pull request,
   do NOT push to any other branch, do NOT merge anything. Then reply with a 5-line summary and stop.

Time: the lead merges branches as they arrive; aim to have the reviewed final push within about 60–75 minutes.
Correctness and completeness first, but never sit on unpushed work.
