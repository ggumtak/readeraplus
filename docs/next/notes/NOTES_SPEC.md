# NOTES_SPEC: 독서 노트 · 단어장 · 인용문 색 · 서재 보기 · 페이지 썸네일 · 서재 ⋮ 오작동 (task #19, contract R3)

> **2026-10-04 사용자 지시로 대체:** 목록 넘기기의 "자동 = e-ink 쪽 단위"는 없어졌다. 서재와 독서 노트는 기본으로 모든 기기에서
> 스크롤하고 "쪽 단위 (한 화면씩)"를 고를 때만 쪽 단위다 (`ListPaging.paged(setting)`, PLAN 맨 위 지시). 아래의 e-ink 기본 쪽 단위 서술은 그 전 설계다.

Status: the buildable spec for task #19. It was written read-only against the working tree of 2026-09-30 (R2
uncommitted, `LibrarySchema.DB_VERSION = 2` in the tree). Paths are relative to
`app/src/main/java/com/ggumtak/readeraplus/`; tests live under `app/src/test/java/…/<same path>`.

**[Δ] Adversarial review (2026-09-30).** Every change is marked **[Δ]** where it applies, and §21 is the changelog.
The SQL claims were measured with a seeded SQLite 3.45 database (2,000 books, 10,000 quotes, 3,000 bookmarks, 3,000
lookups): `scratchpad/notes/critic/sqlcheck.py`. Timings are x86. The Comet's A53 is taken as ≈ ×6–8 slower.

**How to read this document.** It is normative. Three design reports sit next to it:

- `hub.md`: the notes hub, the word history and the jump contract.
- `highlights.md`: quote colours.
- `library.md`: library views, paging and page thumbnails.

A line "adopted: hub.md §10" means that section is normative as written, except where this document changes it.
Where a report and this document disagree, **this document wins**.

The sibling specs `../scroll/SPEC.md` (scroll mode, margins, auto-backup) and `../ui/UI_SPEC.md` (chrome, return
point, brightness, status slots, polish) win on their own subjects. This document adapts to them:

| Sibling item | How this spec uses it |
|---|---|
| UI_SPEC `ReturnNav.onJump` | return point after a jump |
| UI_SPEC `DB_VERSION = 3` | the one shared schema bump |
| UI_SPEC kit tokens | `Ink.LINE_LIGHT`, `keepAll` |
| UI_SPEC polish 13 | one-row selection popup |
| UI_SPEC polish 14 | library card |
| UI_SPEC R11 | `PageDecor(status = null)` for off-screen drawing |
| scroll SPEC `DeviceClass` | e-ink detection |
| scroll SPEC `PageRenderer.drawChrome/drawBody` | thumbnails |
| scroll SPEC `AutoBackup` | the sync substitute |

**The user's request** (2026-09-30, verbatim):

> 책 문서 고르는 칸에서 맨 오른쪽에 ... 이거 누르려고 하면 스크롤이 클릭돼서 자꾸 화면이 내려가거나 올라가 수정해줘.
> 그리고 이 사진들을 참고해서 넣을 만한거 있으면 넣어줄래? 물론 앱이 무거워지는 건 괜찮지만 대신 책 로딩하고 불러오는 거나
> 이런 최적화는 최대한 그대로 빨라야해 그리고 특히 인용문이나 즐겨찾기 한 곳에 모으는 기능은 꼭 넣어주면 좋겠어.

Reading of the request:

- **"인용문이나 즐겨찾기 한 곳에 모으는 기능"** is ReadEra's premium item "섹션: 인용문, 메모 …": "모든 책과 문서의 모든
  인용문, 메모, 북마크 그리고 리뷰는 한 곳에 모아집니다". "즐겨찾기" here means the saved places (북마크), not the
  favourite *books*, which already have their own 즐겨찾기 shelf.
- The hub therefore gathers quotes, memos, bookmarks, reviews, and (from "Section: Dictionary") looked-up words.
  Its 북마크 tab is one tap away, and its empty state names 북마크 explicitly.
- **"책 로딩 … 그대로 빨라야"** becomes hard gates:
  - opening a book: +0 work before the first page (RAPerf gate +10 ms on the cached 14.8 MB TXT);
  - page turn: unchanged;
  - library cold start: within +5 %.
- A heavier APK and heavier *secondary* screens are acceptable.

---

## 0. Decisions at a glance

| # | Topic | Decision |
|---|---|---|
| 1 | **The must-have** | **독서 노트** (`ui/notes/NotesActivity`, its own Activity). Tabs **전체 · 인용문 · 메모 · 북마크 · 리뷰 · 단어**. Order 최신순 / 오래된 순 / 책별 (최근 읽은 책 먼저 / 제목순). Filters: book, text search, colour. Multi-select, recolour, delete, share, export to Markdown/TXT. Entry points: drawer "독서 노트" and "단어장", the book menu, the reader ⋮ menu, and the TOC dialog's 북마크/인용문 tabs. Nothing of it loads on library start or book open. |
| 2 | Tap a note | The book opens **at the note**, with the quote or word marked on the first frame. Where you were reading becomes the return chip "‹ N 페이지로" (UI_SPEC `ReturnNav`). **Peek rule:** the saved position is not overwritten until the first manual turn, so Back after a look loses nothing. |
| 3 | Cost where it matters | Open: +6 `getExtra` calls and a branch, no I/O, the same single layout. Turn: one map removal and a boolean. Library cold start: +0 (drawer counts are lazy, in the existing drawer-open IO job). |
| 4 | Schema | **v3**, shared with UI_SPEC's `book_prefs.return_mark`. `quotes`/`bookmarks` + `chapter`, `frac`, `sig`. `books` + `review_at`, `missing_at`. New `lookups` table. Five indexes, **none on an added column** (a real upgrade hazard, tested). **[Δ]** Plus two partial memo indexes on v1 columns, and an orphan sweep on upgrade for files that went through an R2 build (§4.1). |
| 5 | Quote colours | Six styles in `quotes.style`: 0 **노랑** (the default; every existing quote), 1 초록, 2 파랑, 3 빨강, 4 보라, 5 밑줄. Two looks: **색** (pastel fills) and **흑백 무늬** (exact 16-level greys plus line patterns). Setting 자동 = 흑백 무늬 on e-ink (`DeviceClass`), 색 elsewhere. The picker is in the selection popup (인용 shows the last-used swatch; long-press opens the palette), on an existing quote, and in the TOC and hub rows. |
| 6 | 단어장 (Section: Dictionary) | Every 사전·번역 / 웹 검색 pick is recorded with its sentence, chapter, %, app and time. On by default, stored only on the device, switch in settings. A cancelled chooser records nothing. The same pick within 10 min updates one row. |
| 7 | 라이브러리 뷰 | **전체 · 요약 · 썸네일 · 그리드** (enum names `LIST`/`COMPACT`/`GRID` kept, `COVERS` added). One canonical cover bitmap for every view, and a view switch runs no query. **E-ink: paged library** (a drag = one page, ◀ ▶, number pad) with whole cards per page. Setting "목록 넘기기" 자동/쪽 단위/스크롤. |
| 8 | ⋮ bug | Root cause: the always-visible platform fast scroller takes **every** touch within 48 dp of the right edge (any height, because the Material theme has a track) and jumps the list. **H0 hotfix, ships first and alone:** `InkListView`/`InkGridView` give the scroller only touches that start on its 12 dp strip. Card buttons become `CardButton` (20 dp tap slop, no long-press toast). **[Δ]** H0 also keeps every control out of that strip (the 요약 ⋮ sits flush right today), puts the grid's scroller outside its padding, and CI proves the jitter case (a 6 px roll), not just a tap (§3). |
| 9 | 페이지 썸네일 | Yes, in the last wave (W2): a 4th TOC tab "썸네일" plus reader ⋮ "페이지 썸네일". Paged 4×3 grid on the Comet, marks for bookmarks, quotes and notes, day/night. Rendered lazily off the main thread with the scroll spec's `drawChrome`/`drawBody`. Zero cost on open and turn. Hidden in scroll mode. |
| 10 | Synchronization | **No cloud sync, no accounts, no network.** Offered instead: auto-backup (scroll SPEC §3), "백업 파일에서 복원" that **merges** (positions newer-wins, notes union), Wi-Fi 전송 for book files, and notes export. A two-way "sync folder" is **not done**: §13 gives the reasons. |
| 11 | Keeping notes | A book whose file vanished and that has notes goes to 휴지통 as "(파일 없음)" instead of being deleted with its notes. It returns by itself when the file reappears. Delete and empty-trash dialogs say how many notes go with the book. |
| 12 | Background play | T1-11 (`TtsService`) already covers it. Nothing here. |
| 13 | 내 글자체 | Exists (FontsPage). Nothing here. |
| 14 | Owners | The UI_SPEC lanes (READER_A, READER_UI, READER_B, RENDER, EXTRAS_TOOLS, EXTRAS_NAV, DATA, LIBRARY, SETTINGS, CONTRACT) plus a new **NOTES** lane (`ui/notes/*`, new files only). Files are disjoint. In a joint run, one agent per lane does all three specs' edits in its files (§15). |

---

## 1. The Premium sheet, item by item

| ReadEra Premium item | Verdict | Where |
|---|---|---|
| Synchronization (Google Drive) | **No** (house rule: no network, no accounts). Substitutes in §13. | §13 |
| Background play | Already built as T1-11 | – |
| **섹션: 인용문, 메모 …** | **Yes, must.** 독서 노트 | §5, §6, §9 |
| Section: Dictionary | **Yes.** 단어 tab + drawer "단어장" | §5.4, §7.1.6, §9.11 |
| 인용문 색 표시 | **Yes.** 6 styles, colour and ink looks | §7.1, §8 |
| 라이브러리 뷰 (전체, 요약, 썸네일, 그리드) | **Yes.** 4 views + e-ink paging | §10 |
| 페이지 썸네일 | **Yes, last wave.** Zero open or turn cost | §12 |
| 내 글자체 | Exists | – |

---

## 2. Phases and order

| Phase | Who | What | Depends on |
|---|---|---|---|
| **H0** (now) | LIBRARY | ⋮ hotfix (§3): new `ui/kit/InkTouch.kt`, `LibraryActivity`, `LibraryViews`, `InkTouchTest`, CI step `41`. No frozen file. | nothing (lands on top of R2, before R3) |
| **Phase 0** (serial) | CONTRACT (lead) | §4, applied **in the same pass** as the scroll SPEC §4.1 and UI_SPEC §1 contract steps. Also the skeletons (§4.10), the contract tests (§4.12), then `tools/typecheck.sh` and `tools/snapshot_contracts.sh`. | R2 merged, H0 merged |
| **W1** (parallel) | DATA, READER_A, EXTRAS_TOOLS, EXTRAS_NAV, RENDER, NOTES, LIBRARY, SETTINGS | §5–§11 | Phase 0 |
| **W2** (parallel, after W1) | READER_A, EXTRAS_NAV | 페이지 썸네일 (§12) | W1, plus the scroll SPEC's RENDER `drawChrome`/`drawBody` implemented |
| **Phase 2** (lead) | CONTRACT | Full `tools/unittest.sh`, `[screens]` CI run, §17 checks, Comet device pass (§14 gates), adversarial review. `grep -rn 'R3 stub\|TODO("owner' app/src/main/java` prints nothing. | W1 (and W2) |

W1 is safe to run in parallel with the scroll and UI specs' phase 1: every cross-lane API used here exists as a
phase-0 signature (§4.10).

---

## 3. H0: the library ⋮ hotfix (LIBRARY; ships alone)

### 3.1 Root cause (confirmed in code and in `ui_ref/ours/02_drawer.png`, where the grey full-height track shows)

- `LibraryActivity.buildUi` sets `isFastScrollEnabled = true` and `isFastScrollAlwaysVisible = true` on both the list
  (≈ l.428-430) and the grid (≈ l.453-454). The theme gives it a **track drawable**.
- In `android.widget.FastScroller`, `isPointInside(x, y)` = `isPointInsideX(x) && (mTrackDrawable != null ||
  isPointInsideY(y))`. With a track, **any y counts**.
- `isPointInsideX` widens the 8 dp thumb to the 48 dp minimum touch target, so on a 360 dp screen the zone is
  **x ≥ 312 dp, over the whole list height**. (library.md §1.2 said "the thumb's band"; that is wrong because of the
  track. Every ⋮ is affected, which is why it happens "자꾸".)
- `AbsListView.onInterceptTouchEvent` asks the scroller first. On a DOWN inside the zone it intercepts at once, since
  the list is not in a scrolling container. The ⋮ never sees the touch.
- Every MOVE, even one pixel of fingertip jitter, then calls `scrollTo(position of the finger's y)`, and the list
  jumps to the finger's height. Pressing low jumps toward the end, pressing high toward the start.
- The card's ⋮ (`LibraryViews.kt` ≈ l.201, the 5th of five weight-1 buttons) spans x ≈ 303–348 dp. That is 36 of
  its 45 dp inside the zone. The "34%" label and the right third of the grid's right column are inside too.
- **Secondary.** `Ui.iconButton` shows a toast on long press ("더보기" after a slow e-ink press). The list's own 8 dp
  slop cancels a click when the fingertip rolls.

### 3.2 New file `ui/kit/InkTouch.kt` (LIBRARY owns it; not frozen)

```kotlin
package com.ggumtak.readeraplus.ui.kit

/** Pure (JVM-tested): what x an always-visible right-side platform fast scroller may see for an ACTION_DOWN. */
object FastScrollGuard {
    /** The strip the scroller keeps: its ≤ 8 dp thumb/track plus slack; equals the library list's 12 dp end gutter. */
    const val GRAB_DP = 12f
    /** The platform grab zone (48 dp minimum touch target) + 8 dp for OEM variation. */
    const val ZONE_DP = 56f
    /**
     * Unchanged outside the zone and on the strip; inside the zone but left of the strip → just left of the zone.
     * [Δ] [insetEndPx] = the scroller container's right inset (paddingEnd for INSIDE_* scrollbar styles, else 0): the
     * platform zone is then `x ≥ width − inset − 48 dp`, and without it the 8 dp OEM slack is used up by the padding.
     */
    fun shieldedX(x: Float, width: Int, density: Float, insetEndPx: Int = 0): Float {
        if (width <= 0) return x
        val grabLeft = width - insetEndPx - GRAB_DP * density
        val zoneLeft = width - insetEndPx - ZONE_DP * density
        return if (x >= grabLeft || x < zoneLeft) x else zoneLeft - 1f
    }
}

/** Pure: the reader's tap tolerance (PageView.tapSlop) for list buttons. */
object TapSlop {
    fun px(touchSlop: Int, density: Float): Float = maxOf(touchSlop * 2f, 20f * density)
    /** A press that began on a card button becomes the list's gesture: mostly vertical and beyond [slop]. */
    fun releaseToList(dx: Float, dy: Float, slop: Float): Boolean = abs(dy) > slop && abs(dy) >= abs(dx)
}

/**
 * ListView for e-ink whose rows may hold buttons. Same defaults as `einkListView()`. When the platform fast scroller
 * is on, it only gets touches that start on its own strip ([FastScrollGuard]); the rows (and their ⋮) get the rest.
 */
open class InkListView(context: Context) : ListView(context) {
    init {
        divider = null; dividerHeight = 0; overScrollMode = OVER_SCROLL_NEVER; isVerticalFadingEdgeEnabled = false
        selector = ColorDrawable(Color.TRANSPARENT); isScrollbarFadingEnabled = false; cacheColorHint = Color.TRANSPARENT
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = guardDown(ev) { super.onInterceptTouchEvent(it) }
    override fun onTouchEvent(ev: MotionEvent): Boolean = guardDown(ev) { super.onTouchEvent(it) }
}
/** GridView twin of [InkListView] (same guard). */
open class InkGridView(context: Context) : GridView(context) { /* the same two overrides */ }

internal inline fun AbsListView.guardDown(ev: MotionEvent, sup: (MotionEvent) -> Boolean): Boolean {
    // [Δ] Qualified: an extension function does not see the receiver's static Java members (compile error otherwise).
    if (!isFastScrollEnabled || ev.actionMasked != MotionEvent.ACTION_DOWN || layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        return sup(ev)
    }
    // [Δ] The platform scroller's container excludes the padding for INSIDE_* scrollbar styles (FastScroller
    // .updateContainerRect), which moves its grab zone left by paddingEnd.
    val inset = if (scrollBarStyle == View.SCROLLBARS_INSIDE_OVERLAY || scrollBarStyle == View.SCROLLBARS_INSIDE_INSET) paddingEnd else 0
    val x = FastScrollGuard.shieldedX(ev.x, width, resources.displayMetrics.density, inset)
    if (x == ev.x) return sup(ev)
    val copy = MotionEvent.obtain(ev).apply { setLocation(x, ev.y) }
    try { return sup(copy) } finally { copy.recycle() }
}

/** Card action button: a press that starts on it stays a tap until the finger moves [TapSlop] away; no long-press toast. */
class CardButton(context: Context, iconRes: Int, description: String, onClick: (View) -> Unit) : ImageButton(context) {
    private val slop = TapSlop.px(ViewConfiguration.get(context).scaledTouchSlop, resources.displayMetrics.density)
    private var downX = 0f; private var downY = 0f; private var guarding = false
    init {
        setImageResource(iconRes); contentDescription = description
        imageTintList = ColorStateList.valueOf(Ink.BLACK); background = pressableBackground()
        scaleType = ScaleType.CENTER; isFocusable = false; isLongClickable = false
        setOnClickListener(onClick)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; guarding = true; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (guarding && TapSlop.releaseToList(e.x - downX, e.y - downY, slop)) {
                guarding = false; parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> guarding = false
        }
        return super.onTouchEvent(e)
    }
}
```

Why the guard works:
- Only the list's own `onInterceptTouchEvent`/`onTouchEvent` see the shifted copy. `ViewGroup.dispatchTouchEvent`
  still hands the **original** event to the children, so ⋮ gets its tap.
- Rows span the full width, so the list records the DOWN on the same row, and a drag that starts on a card still
  scrolls normally.
- FastScroller decides at DOWN, so it never starts a drag outside its strip.
- The visible strip (x ≥ W − 12 dp) works as before.
- If an OEM firmware used a grab zone wider than 56 dp, the guard degrades to today's behaviour; it never makes
  things worse.
- **[Δ] GridView caveat.** For a ListView the shifted x is harmless (rows span the width). For a GridView,
  `AbsListView.onTouchDown` picks the cell with `pointToPosition(x, y)`, so the shifted x chooses the **column**. It
  lands in the same cell only while the last column starts left of the shifted point. That holds today, because
  cells are ≥ 76 dp and the shift is ≤ 44 dp. `LibraryGridMathTest` must assert it for 360, 411 and 720 dp in both
  grid views: *last column left edge < W − inset − 57 dp*. A future narrow-cell grid would otherwise open the
  neighbour's book, or nothing if the point falls in a gap.
- **[Δ] The strip must hold no control.** The guard only helps a control that lies left of the 12 dp strip.
  - The 전체 card's ⋮ ends at W − 12 dp (root padding 8 + card padding 4), so it is fine.
  - The tree's **요약** row (`LibraryViews` ≈ l.372-384) puts its 48 dp ⋮ slot flush right (`line` right padding
    0). The strip covers the right quarter of that ⋮, and a tap there still seeks.
  - The grid has 8 dp padding, so the right column's last 4 dp are in the strip.

  H0 fixes both (§3.3).

### 3.3 Library wiring (LIBRARY)

- `LibraryActivity.buildUi`:
  - `listView = InkListView(this).apply { clipToPadding = false; setPadding(0, dp(4), 0, dp(8)); isFastScrollEnabled = true; isFastScrollAlwaysVisible = true; scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY; itemsCanFocus = true; adapter = bookAdapter }`
  - `gridView = InkGridView(this).apply { …unchanged… }`
  - **[Δ]** `gridView.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY`, like the list. The scroller's container is
    then the full width, its thumb sits over the padding, and the full 8 dp OEM slack returns. Grid padding right
    becomes 12 dp, and `gridAdapter.cellWidth` uses left 8 + right 12.
  - **[Δ]** The 요약 row: `line` right padding 0 → 12 dp, so its ⋮ ends at W − 12 dp. UI_SPEC polish 14 later moves
    that 12 dp to the list's `paddingEnd`. The invariant stays either way: **no clickable pixel of a row at x ≥ W −
    12 dp**.
  - Nothing else changes.
- `LibraryViews.BookCardHolder.btn(...)` builds `CardButton(ctx, res, desc) { row?.let(onClick) }` with
  `LinearLayout.LayoutParams(0, dp(48), 1f)`. The ⋮ content description becomes **"책 메뉴"**, so CI can find it
  apart from the toolbar's ⋮. The 요약 row's ⋮, when it exists, uses `CardButton` too.
- Keep `Ui.iconButton` as is (frozen). Other screens are not affected.
- **[Δ] W1 (LIBRARY), not H0:** on e-ink (`DeviceClass.cached == true`), `CardButton.background = null`, with no
  pressed state. Today a ⋮ tap costs three e-ink updates: pressed (after `tapTimeout`, or the pre-pressed flash on UP),
  released (64 ms later), then the menu. The menu or the flag icon is the feedback, as with `InkPagerBar`'s buttons.
  Phones keep `pressableBackground()`.

### 3.4 Tests and checks

- `test/.../ui/kit/InkTouchTest` (JVM):
  - `shieldedX`: x < zone → same; zone ≤ x < strip → `zoneLeft − 1`; x on the strip → same; width 0 → same.
    Density 2 and 2.625. **[Δ]** Also with `insetEndPx = dp(8)`: the zone and strip move left by the inset, and the
    result is < `W − inset − 48 dp` (the platform zone), with ≥ 8 dp of slack at densities 2, 2.625 and 2.8125.
  - `TapSlop.px(16, 2f) = 40`, `px(40, 2f) = 80`.
  - `releaseToList`: (0, 39) false; (0, 41) true; (50, 41) false; (−3, −41) true.
- CI step `41_library_more`, right after `01_library` (720×1440 at density 2):
  - `tap_label "책 메뉴"` (the first card's ⋮, x ≈ 650 px, inside the old zone), then `dump`.
  - `find_node.py /tmp/ui.xml "문서 속성"` (a library book-menu item) must print coordinates. Log `CHECK 41 PASS|FAIL`, then shot `41_library_more`
    showing the book menu.
  - `back`, then `adb shell input swipe 700 1200 700 1190 300`: a 10 px drag on the strip. The list moves (the
    scroller still works), and the step is logged only.
  - **[Δ] "The list did not move" is checked, not just claimed.** `dump` before the tap, then print the first card
    title's bounds with `find_node.py`. After the menu closes, they must be equal.
  - **[Δ] CHECK 41b (the user's actual case: fingertip jitter).** `input tap` sends no MOVE, so the old code fails
    CHECK 41 only because the menu doesn't open. The *jumping* needs a move. Run `input swipe X Y X+2 Y+6 150` on
    the same ⋮ (a 6 px roll).
    - The menu must open.
    - The first card's bounds must be unchanged.
    - The old code fails both: the scroller seeks to the finger's y.
  - **[Δ] CHECK 42b** (in step 42, 요약 view): `input tap` at the first row's ⋮ **right edge − 2 px**, read from its
    bounds. The menu must open. This guards the strip-overlap rule of §3.2.
- **Manual (Comet):**
  1. Tap ⋮ on any card: the menu opens and the list doesn't move.
  2. Hold ⋮ for 1 s: the menu opens, with no toast.
  3. Swipe starting on ⋮: after 20 dp the list scrolls.
  4. Drag the scrollbar itself: it seeks as before.

### 3.5 Why the guard and not removal (resolves hub.md §14 vs library.md §1)

Removing the fast scroller (library.md) is robust, but it takes away the Comet's only way to seek in a long library
until paging lands. The guard fixes the bug with no product change and ships today. In W1 (§10.3):
- **paged mode** (the e-ink default) has **no** fast scroller; the pager and number pad seek;
- **scroll mode** (phones) keeps the guarded fast scroller.

That also removes library.md's main risk ("phones lose drag-to-seek"). UI_SPEC polish 14's thin thumb drawables are
kept: they are visual only, and the guard is what fixes the touch.

---

## 4. Contract (phase 0; the lead; merged with the scroll SPEC and UI_SPEC contract steps)

### 4.1 `data/LibrarySchema.kt` → v3

`DB_VERSION = 3`, shared with UI_SPEC §1.5; its `return_mark` entries stay as written there. KDoc line: "v3 (R3): notes
hub (`lookups`; `chapter`/`frac`/`sig` on quotes and bookmarks; `books.review_at`, `books.missing_at`) and
`book_prefs.return_mark`."

```sql
-- Fresh v3 files (CREATE_*): the new columns are appended at the end of each table.
CREATE TABLE IF NOT EXISTS books(
  … every v2 column unchanged …, meta_locked INTEGER NOT NULL DEFAULT 0,
  review_at INTEGER NOT NULL DEFAULT 0,        -- v3: when the review was last written (0 = unknown / none)
  missing_at INTEGER NOT NULL DEFAULT 0)       -- v3: > 0 = moved to trash by the scanner because the file vanished

CREATE TABLE IF NOT EXISTS bookmarks(
  id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER NOT NULL, section INTEGER NOT NULL DEFAULT 0,
  char_offset INTEGER NOT NULL DEFAULT 0, snippet TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT 0,
  chapter TEXT NOT NULL DEFAULT '',            -- v3: chapter title at creation ('' = none / unknown)
  frac REAL NOT NULL DEFAULT -1,               -- v3: char fraction of the book at the note, 0..1; -1 = unknown
  sig TEXT NOT NULL DEFAULT '')                -- v3: [Δ] NoteSig.of(textSignature, file size) (§4.9); '' = unknown (legacy)

CREATE TABLE IF NOT EXISTS quotes(
  id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER NOT NULL, section INTEGER NOT NULL DEFAULT 0,
  start_offset INTEGER NOT NULL DEFAULT 0, end_offset INTEGER NOT NULL DEFAULT 0,
  quote_text TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL DEFAULT 0,
  style INTEGER NOT NULL DEFAULT 0,            -- v2: QuoteStyles id (0 노랑 … 5 밑줄; unknown ids draw as 0)
  chapter TEXT NOT NULL DEFAULT '', frac REAL NOT NULL DEFAULT -1, sig TEXT NOT NULL DEFAULT '')

CREATE TABLE IF NOT EXISTS lookups(            -- v3: 단어장 (every dictionary / web-search pick)
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER NOT NULL,
  word TEXT NOT NULL DEFAULT '',               -- the selection, trimmed, ≤ 200 chars
  word_key TEXT NOT NULL DEFAULT '',           -- LookupWords.key(word): grouping, "N회"
  section INTEGER NOT NULL DEFAULT 0,
  start_offset INTEGER NOT NULL DEFAULT 0,
  end_offset INTEGER NOT NULL DEFAULT 0,
  context TEXT NOT NULL DEFAULT '',            -- the sentence around it, ≤ 300 chars
  chapter TEXT NOT NULL DEFAULT '',
  frac REAL NOT NULL DEFAULT -1,
  sig TEXT NOT NULL DEFAULT '',
  via INTEGER NOT NULL DEFAULT 0,              -- 0 사전·번역 app, 1 웹 검색, 2 web fallback (no app installed)
  app TEXT NOT NULL DEFAULT '',                -- app label ("파파고") or search host ("search.naver.com"), ≤ 100
  note TEXT NOT NULL DEFAULT '',               -- "뜻 메모", ≤ 20,000
  created_at INTEGER NOT NULL DEFAULT 0)
```

`ADDED_COLUMNS` (each version 3; each skipped when `PRAGMA table_info` already lists it):

```kotlin
AddedColumn("quotes", "chapter", 3, "ALTER TABLE quotes ADD COLUMN chapter TEXT NOT NULL DEFAULT ''"),
AddedColumn("quotes", "frac", 3, "ALTER TABLE quotes ADD COLUMN frac REAL NOT NULL DEFAULT -1"),
AddedColumn("quotes", "sig", 3, "ALTER TABLE quotes ADD COLUMN sig TEXT NOT NULL DEFAULT ''"),
AddedColumn("bookmarks", "chapter", 3, "ALTER TABLE bookmarks ADD COLUMN chapter TEXT NOT NULL DEFAULT ''"),
AddedColumn("bookmarks", "frac", 3, "ALTER TABLE bookmarks ADD COLUMN frac REAL NOT NULL DEFAULT -1"),
AddedColumn("bookmarks", "sig", 3, "ALTER TABLE bookmarks ADD COLUMN sig TEXT NOT NULL DEFAULT ''"),
AddedColumn("books", "review_at", 3, "ALTER TABLE books ADD COLUMN review_at INTEGER NOT NULL DEFAULT 0"),
AddedColumn("books", "missing_at", 3, "ALTER TABLE books ADD COLUMN missing_at INTEGER NOT NULL DEFAULT 0"),
// + UI_SPEC §1.5: AddedColumn("book_prefs", "return_mark", 3, ADD_RETURN_MARK)
```

`CREATE_INDEXES` gains (columns that exist since v1, or columns of the new table only):

```sql
CREATE INDEX IF NOT EXISTS quotes_created ON quotes(created_at)
CREATE INDEX IF NOT EXISTS bookmarks_created ON bookmarks(created_at)
CREATE INDEX IF NOT EXISTS lookups_created ON lookups(created_at)
CREATE INDEX IF NOT EXISTS lookups_book ON lookups(book_id)
CREATE INDEX IF NOT EXISTS lookups_word ON lookups(word_key)
-- [Δ] Partial indexes over v1 columns only (the rule below holds). They serve the 메모 tab and every memo count
-- without reading past quote_text: `note` is stored after it, so `q.note <> ''` on a table scan walks each long
-- quote's overflow pages. SQLite ≥ 3.8 (Android 8 has 3.18). Measured: QM page 0 = `SCAN q USING INDEX quotes_memo`.
CREATE INDEX IF NOT EXISTS quotes_memo ON quotes(created_at) WHERE note <> ''
CREATE INDEX IF NOT EXISTS bookmarks_memo ON bookmarks(created_at) WHERE note <> ''
```

`CREATE_ALL` = the existing tables + `CREATE_LOOKUPS`, then `CREATE_INDEXES` (tables before indexes).

**Upgrade hazard (a rule and a test).** `LibraryDb.onUpgrade` runs `CREATE_ALL`, tables **and indexes**, *before*
the ALTERs. An index naming a column that an ALTER adds would fail on a v2 file ("no such column"). The whole upgrade
transaction would roll back and the library could not open. **Rule: no `CREATE_INDEXES` entry may name a column
listed in `ADDED_COLUMNS`.** Reviews are few, so the review arm scans `books` with no index.

Upgrade cost: eight constant-time ALTERs plus two index builds (≈ 20 ms per 10,000 rows), once. Everything is
SQLite 3.18-safe: no UPSERT, window functions or RETURNING.

**[Δ] Measured cost.** On 10k quotes and 3k bookmarks: the ALTERs plus all index builds take 7.3 ms (x86); the two
partial indexes take 2.8 ms. `ALTER … frac REAL NOT NULL DEFAULT -1` is accepted, and old rows read `-1.0` (real).

**[Δ] Where the upgrade runs.** It runs on `Library.init`'s "library-db-open" thread, and the first library query
waits for it. So does `startOpenLast`. The one-time upgrade therefore sits on the critical path of the **first**
launch after the update. The ≤ 50 ms budget covers it; the +5 % cold-start gate is measured from the second launch.

**[Δ] Orphan sweep (downgrade safety).** When an R2 build opens a v3 file, `onDowngrade` keeps the data and
SQLiteOpenHelper stamps the file **v2**. That build's `deleteBookRows` (remove, empty trash, scanner drop) does not
know `lookups`, so it leaves orphan rows. The hub would count them (`drawerCounts`, `counts`) but could not list them
under a book: placeholders forever, or a missing `NoteBook`.
- `LibrarySchema.UPGRADE_SWEEP` (new, run by `LibraryDb.onUpgrade` after the ALTERs, in the same transaction, when
  `oldVersion < 3`):
  - `DELETE FROM lookups WHERE book_id NOT IN (SELECT id FROM books)`;
  - the same for `quotes` and `bookmarks`, as a defence.
- Every re-upgrade after a downgrade has `oldVersion = 2`, so it sweeps. `books.id` is AUTOINCREMENT, so an orphan
  can never attach to a new book.
- The hub still maps an unknown book id to `NoteBook(title = "(삭제된 책)", missing = true)` instead of throwing.
- Other downgrade effects, all benign:
  - an R2 build's 복원 leaves `trashed = 0, missing_at > 0`, so `NoteBook.missing` = `missing_at > 0 AND trashed = 1`,
    and the next v3 scan clears it;
  - an R2 build writes no `review_at`, so such a review sorts by `last_read_at`;
  - an R2 build's backup drops the v3 fields.

`data/MetaInfo.kt` `DataLimits` (DATA edits it; it is not frozen) gains `CHAPTER = 200`, `WORD = 200`,
`CONTEXT = 300`, `APP = 100` and `QUOTE_STYLE_MAX = 15`.

### 4.2 `data/Models.kt`

```kotlin
data class Book(/* …unchanged… */ val readingSeconds: Long = 0,
    /** R3: > 0 = the scanner moved this entry to trash because its file vanished ("(파일 없음)"). */
    val missingAt: Long = 0,
)

data class Bookmark(
    val id: Long, val bookId: Long, val section: Int, val offset: Int, val snippet: String, val createdAt: Long,
    val note: String = "",
    /** R3: where it sits, for screens that can't lay the book out (NotePlace). */
    val chapter: String = "", val frac: Float = -1f, val sig: String = "",
)

data class Quote(
    val id: Long, val bookId: Long, val section: Int, val start: Int, val end: Int, val text: String,
    val note: String = "", val createdAt: Long,
    /** Highlight look, `quotes.style` (QuoteStyles: 0 노랑 … 5 밑줄; unknown ids draw as 0). R3. */
    val style: Int = 0,
    val chapter: String = "", val frac: Float = -1f, val sig: String = "",
)

/** Where a note sits, computed by the reader at creation (NotePlaceHost). */
data class NotePlace(val chapter: String, val frac: Float, val sig: String) {
    companion object { val UNKNOWN = NotePlace("", -1f, "") }
}

enum class NoteKind(val code: Int, val label: String) { QUOTE(1, "인용문"), BOOKMARK(2, "북마크"), REVIEW(3, "리뷰"), LOOKUP(4, "단어") }
enum class NotesTab(val label: String) { ALL("전체"), QUOTES("인용문"), MEMOS("메모"), BOOKMARKS("북마크"), REVIEWS("리뷰"), WORDS("단어") }
enum class NotesOrder(val label: String) {
    NEWEST("최신순"), OLDEST("오래된 순"), BOOK_RECENT("책별 · 최근 읽은 책 먼저"), BOOK_TITLE("책별 · 제목순");
    val byBook: Boolean get() = this == BOOK_RECENT || this == BOOK_TITLE
}

/** What the hub lists. [bookId] null = all books; [text] = search words; [style] = colour filter (QUOTES tab only). */
data class NotesQuery(
    val tab: NotesTab = NotesTab.ALL, val order: NotesOrder = NotesOrder.NEWEST,
    val bookId: Long? = null, val text: String = "", val style: Int? = null,
    /** WORDS tab: one row per word (its latest lookup). */
    val wordsOnce: Boolean = false,
)

/** Identity of one note across the four sources. REVIEW's id is the book id. */
data class NoteRef(val kind: NoteKind, val id: Long) {
    fun packed(): Long = (kind.code.toLong() shl 56) or (id and 0x00FF_FFFF_FFFF_FFFFL)
    companion object { fun unpack(v: Long): NoteRef? }   // null for an unknown kind code
}

/** One hub row; [body] ≤ 600 chars (quote text, bookmark snippet, review, lookup sentence), [note] ≤ 400. */
data class NoteRow(
    val ref: NoteRef, val bookId: Long,
    val section: Int, val start: Int, val end: Int,
    val body: String, val bodyCut: Boolean, val note: String, val noteCut: Boolean,
    val word: String, val wordCount: Int,       // LOOKUP only (wordCount ≥ 1)
    val style: Int,                             // QUOTE only
    val via: Int, val app: String,              // LOOKUP only
    val chapter: String, val frac: Float, val sig: String,
    val time: Long,
)
data class NoteBook(val id: Long, val title: String, val author: String, val path: String,
                    val trashed: Boolean, val missing: Boolean, val lastReadAt: Long, val count: Int)
data class NotesCounts(val quotes: Int, val memos: Int, val bookmarks: Int, val reviews: Int, val words: Int) {
    val all: Int get() = quotes + bookmarks + reviews + words
    fun of(tab: NotesTab): Int
}
data class Lookup(
    val id: Long, val bookId: Long, val word: String, val section: Int, val start: Int, val end: Int,
    val context: String, val chapter: String, val frac: Float, val sig: String,
    val via: Int, val app: String, val note: String, val createdAt: Long,
)
```

The `Quote` and `Bookmark` additions are all defaulted and come after the existing parameters, so every positional
call compiles. Phase 0 fixes the one named/positional fallout (`Backup.kt`'s `Quote(newId, …)`).

### 4.3 `settings/ReaderSettings.kt` and `settings/Settings.kt`

```kotlin
/** Library views (ReadEra 전체 / 요약 / 썸네일 / 그리드). Stored by name: GRID is 썸네일, COVERS is 그리드. Toolbar cycle = entry order. */
enum class LibraryListMode(val label: String) { LIST("전체"), COMPACT("요약"), GRID("썸네일"), COVERS("그리드") }

data class AppSettings(
    /* …existing fields, scroll SPEC and UI_SPEC fields… */
    /** How quote colours are drawn: [HL_LOOK_AUTO] (e-ink → 흑백 무늬, else 색), [HL_LOOK_COLOR], [HL_LOOK_INK]. */
    val highlightLook: Int = HL_LOOK_AUTO,
    /** Library, 독서 노트 and their choosers: [LIST_PAGING_AUTO] (쪽 단위 on e-ink), [LIST_PAGING_PAGED], [LIST_PAGING_SCROLL]. */
    val listPaging: Int = LIST_PAGING_AUTO,
    /** "찾아본 단어 기록": 사전·번역 / 웹 검색 picks go to 단어장 (device only). */
    val recordLookups: Boolean = true,
)
const val HL_LOOK_AUTO = 0
const val HL_LOOK_COLOR = 1
const val HL_LOOK_INK = 2
const val LIST_PAGING_AUTO = 0
const val LIST_PAGING_PAGED = 1
const val LIST_PAGING_SCROLL = 2
```

`Settings.kt` keys: `a.highlightLook` (int, out of 0..2 → 0), `a.listPaging` (int, out of 0..2 → 0),
`a.recordLookups` (bool, missing → true). `loadApp` reads plain values only. An unknown `libraryListMode` name falls
back to `LIST`, so a backup holding `COVERS` restored by an older build shows 전체.

### 4.4 `data/SettingsJson.kt`

`appToJson`/`appFromJson` map the three fields (ints clamped to 0..2), plus the `COVERS` enum name. No new
TRANSIENT entry: the hub's raw UI prefs (`notes.tab`, `notes.order`, `notes.wordsOnce`, `extras.quoteStyle`) are
harmless to back up.

### 4.5 `render/Render.kt` (RENDER's file; a shared type, so phase 0 edits it as UI_SPEC does with `PageDecor`)

```kotlin
/** A highlighted range. [style] is read only for QUOTE (QuoteStyles id; unknown → 0). */
class Highlight(val start: Int, val end: Int, val kind: HighlightKind, val style: Int = 0)
```

### 4.6 `reader/extras/ReaderPanels.kt`: frozen block additions

```kotlin
/** Chapter title, char fraction and parse signature of [pos] in the open book. Main thread, O(log chapters).
 *  Implemented by ReaderActivity; NotePlace.UNKNOWN while no session exists. */
interface NotePlaceHost { fun notePlace(pos: DocPosition): NotePlace }

/** 페이지 썸네일 (W2): as library.md §3.5, verbatim: PageThumbsHost { thumbTotal(); thumbCurrent(); thumbAspect();
 *  requestThumbs(first, count, widthPx, heightPx, progressive, onBatch); cancelThumbs() }, class ThumbCell(page,
 *  section, pageIndex, bitmap, marks) with MARK_BOOKMARK 1 / MARK_QUOTE 2 / MARK_NOTE 4 / MARK_SEARCH 8, class
 *  ThumbBatch(first, cells, total, current, complete). */
```

The W2 interfaces land in phase 0 so that W2 needs no second contract step.

### 4.7 `AndroidManifest.xml`

```xml
<!-- 독서 노트 (notes hub + 단어장). Not exported; opened from the library, the reader and settings. -->
<activity android:name=".ui.notes.NotesActivity" android:exported="false" />
```

The theme is inherited (`AppTheme`: white, no transitions). No new permission. `res/**`: no change required. Two
optional icons (`ic_view_agenda`, `ic_apps`, Material Symbols Outlined via `tools/fetch_icons.py`) may be added for
the view chooser. Until then, use `ic_article` / `ic_grid_view`.

### 4.8 Intent extras (fixed names)

| Target | Extra | Type | Meaning |
|---|---|---|---|
| `ReaderActivity` | `book_id` (existing) | Long | the book |
| | `jump_section` | Int | note section (≥ 0) |
| | `jump_offset` | Int | note start offset (≥ 0) |
| | `jump_end` | Int | > offset: mark `[offset, end)` until the next manual turn; else -1 |
| | `jump_frac` | Float | 0..1 fallback when the coordinates no longer fit; -1 = unknown |
| | `jump_sig` | String | parse signature the coordinates belong to; "" = unknown / EPUB |
| | `jump_anchor` | String | ≤ 64 chars of text expected at the offset |
| `NotesActivity` | `notes_tab` | String | `NotesTab.name`; wins over the remembered tab |
| | `notes_book` | Long | filter to one book (-1 = all) |

`ReaderActivity` is exported (VIEW intents). Jump extras from another app can only position a book the user
already has; `ReaderJump.from` rejects negative, NaN and out-of-range values. That is harmless.

### 4.9 Files phase 0 writes complete (pure and small; two or more lanes need them from minute one)

1. **`reader/ReaderJump.kt`** (READER_A owns it afterwards):

   ```kotlin
   /** A place to open a book at. Extras are primitives, so a recreated intent still carries them. */
   data class ReaderJump(val section: Int, val offset: Int, val end: Int = -1, val frac: Float = -1f,
                         val sig: String = "", val anchor: String = "") {
       fun put(i: Intent): Intent
       companion object {
           const val EXTRA_SECTION = "jump_section"; const val EXTRA_OFFSET = "jump_offset"; const val EXTRA_END = "jump_end"
           const val EXTRA_FRAC = "jump_frac"; const val EXTRA_SIG = "jump_sig"; const val EXTRA_ANCHOR = "jump_anchor"
           const val ANCHOR_MAX = 64
           fun from(i: Intent): ReaderJump?                         // null: no jump extras, or unusable values
           internal fun sanitize(section: Int, offset: Int, end: Int, frac: Float, sig: String?, anchor: String?): ReaderJump?  // pure core
           fun strip(i: Intent): Intent                             // removes the six jump extras
           fun of(row: NoteRow): ReaderJump                         // anchor = quote body / word / snippet, first 64 chars
           /**
            * Pure. Where to open: sig mismatch on a TXT (currentSig != null && sig != "" && sig != currentSig) with
            * frac ≥ 0 → locate(frac); section in 0 until sectionCount → DocPosition(section, offset); frac ≥ 0 →
            * locate(frac); else null (caller: saved position + toast).
            * [Δ] currentSig = NoteSig.of(textSignature, sizeBytes), never null inside a session, so the mismatch rule
            * covers EPUB too.
            */
           fun resolve(j: ReaderJump, currentSig: String?, sectionCount: Int, locate: (Float) -> DocPosition): DocPosition?
       }
   }
   /**
    * [Δ] What `sig` stores (quotes, bookmarks, lookups). `LayoutKeys.textSignature` covers only the parse options.
    * An edited or replaced file with the same options kept the same sig, so its stale offsets were trusted, and an
    * EPUB had no sig at all. Pure: `(textSignature ?: "e") + ":" + sizeBytes`. A copy to another device keeps the
    * size; an edit almost always changes it. '' (legacy rows) = unknown → coordinates are trusted, as before.
    */
   object NoteSig { fun of(textSignature: String?, sizeBytes: Long): String }
   /** Pure: does [anchor] appear at [offset] of [text], ignoring whitespace and U+FFFC, up to 24 non-space chars? Empty = true. */
   object JumpAnchor { fun matches(text: CharSequence, offset: Int, anchor: String): Boolean }
   ```

   `ReaderActivity.open(context, bookId, jump: ReaderJump? = null)`: phase 0 adds the parameter (`jump?.put(intent)`).
   The stub ignores the jump on the reading side until READER_A lands §6.

2. **`render/QuoteStyles.kt`** (RENDER owns it afterwards). These are the pure tables of highlights.md §2.1–2.2 and
   §3.1, verbatim:
   - `YELLOW=0 … UNDERLINE=5`, `COUNT=6`, `MAX_STORED=15`, `LINE_*`;
   - `of`, `label` (노랑 초록 파랑 빨강 보라 밑줄), `tag` ("[초록]");
   - `colorFill(style, night)`, `colorLine`, `inkGrey`, `inkLine`, `thumbGrey`.

   `IntArray` tables, no allocation.

3. **`ui/kit/InkTouch.kt` paging part** (added to H0's file; LIBRARY owns it afterwards):

   ```kotlin
   /** Pure: drag → page decision (vertical; [axisBoth] also horizontal, for the thumbnail grid). */
   class PageDrag(private val slop: Float, private val axisBoth: Boolean = false) {
       fun down(x: Float, y: Float); fun move(x: Float, y: Float): Boolean; fun up(x: Float, y: Float): Int; fun cancel()
       val dragging: Boolean
   }
   /** Pure: rows per page and row height for fixed-height rows (library views). */
   object PageFit { fun fit(listH: Int, minRowH: Int): Pair<Int, Int> }   // (rows ≥ 1, rowH = listH / rows)

   // InkListView / InkGridView gain:
   /** true: a drag beyond the slop = one page on UP (no scroll frames, no fling, no fast scroller); taps reach rows and their buttons. */
   var paged: Boolean
   var pager: ListPager?

   /** Glue between a paged InkListView/InkGridView, an InkPagerBar and InkNumPad. Main thread. */
   class ListPager(val list: AbsListView, val bar: InkPagerBar, private val cols: Int = 1) {
       /** Fixed rows per page (library) or 0 = measure like InkPager (variable-height rows: the hub). */
       var rowsPerPage: Int
       /** Label under the list: default PagerMath.label; the hub appends " · 128개". */
       var labelSuffix: String
       var onPaged: ((first: Int, last: Int) -> Unit)?        // prefetch hook (covers, hub pages)
       fun page(dir: Int): Boolean
       fun showRow(index: Int)
       fun openNumPad()                                        // bar label tap → InkNumPad("쪽 번호", 1..total)
       fun update()
   }
   /** Pure: paged = PAGED, or AUTO and the device is e-ink. [eink] = DeviceClass.cached(ctx) (null = unknown → false). */
   object ListPaging { fun paged(setting: Int, eink: Boolean?): Boolean }
   ```

   The paged `onInterceptTouchEvent` works like this:
   - DOWN → `drag.down`, return false (rows get it);
   - MOVE → `return drag.move(x, y)`;
   - otherwise false.

   The paged `onTouchEvent` consumes everything: UP → `pager.page(drag.up(x, y))`, CANCEL → `drag.cancel()`. `super`
   is never called while paged. When `paged == false`, both methods run the H0 guard path.

### 4.10 Skeletons phase 0 lands (bodies `TODO("owner: X")`, or a safe `// R3 stub (owner: X)`)

| File | Owner after | Phase-0 contents |
|---|---|---|
| `data/Notes.kt` (new) | DATA | the `Notes` object of §5.3 with safe stubs: counts all 0, empty pages, `drawerCounts` = [0, 0] |
| `data/Lookups.kt` (new) | DATA | `Lookups` + `LookupWords` signatures (§5.4); `record` returns -1 |
| `data/NotesExport.kt` (new) | DATA | `enum class Format(val ext: String, val mime: String) { MARKDOWN("md", "text/markdown"), TXT("txt", "text/plain") }`; `write` is `TODO` |
| `data/Library.kt` (existing) | DATA | new signatures of §5.1 as stubs that delegate to the old calls (style/place ignored); `notesGen` field |
| `render/QuoteLook.kt` (new) | RENDER | `object QuoteLook { val generation: Int; fun update(mode: Int, eink: Boolean?); fun ink(): Boolean }`, stub `ink() = false` |
| `reader/extras/QuoteSwatch.kt` (new) | EXTRAS_TOOLS | `class QuoteSwatch(context, style: Int, sizeDp: Int, ink: Boolean) : View` with `var style`, `var isChecked`; stub draws nothing |
| `reader/extras/QuotePalette.kt` (new) | EXTRAS_TOOLS | `object QuotePalette { fun show(anchor: View, current: Int?, onPick: (Int) -> Unit): PopupWindow? }`, stub returns null |
| `ui/notes/NotesActivity.kt` (new) | NOTES | the companion `EXTRA_TAB`, `EXTRA_BOOK_ID`, `open(context, tab: NotesTab? = null, bookId: Long = -1L)` complete; the Activity body shows `emptyMessage("준비 중")` |

### 4.11 Docs

- `docs/ARCHITECTURE.md` gets a "Contract revision R3 → notes" block:
  - schema v3 and the **no-index-on-added-column** rule;
  - the jump contract and the **peek rule**;
  - `notesGen`;
  - "the hub never opens a book file";
  - highlight looks (`QuoteStyles` / `QuoteLook`, ink levels multiple of 0x11);
  - list paging (`InkListView`, "a list whose rows hold buttons uses InkListView, never the plain fast scroller");
  - "Synchronization: none; see NOTES_SPEC §13".
- `docs/R3_INTERFACES.md` (shared with the siblings) gets every §4.9–§4.10 signature and the §5.1 Library additions.
- `ui/kit/Ui.kt` KDoc of `einkListView`: drop "like the library"; add "rows with buttons: use `InkListView`".

### 4.12 Contract tests (phase 0)

- `data/LibrarySchemaV3Test`:
  - a fresh v3 has every column;
  - `upgradeStatements(1)` and `(2)` list the nine v3 ALTERs (with `return_mark`) once each, and `(3)` lists none;
  - the column guard skips an existing column;
  - **no `CREATE_INDEXES` entry names an `ADDED_COLUMNS` column** (parse `ON t(col, …)`, strip `COLLATE`);
    **[Δ]** the parser also reads the partial-index `WHERE …` clause, and no column named there may be an added
    column either;
  - `CREATE_LOOKUPS` precedes its indexes in `CREATE_ALL`.
  - **[Δ]** `UPGRADE_SWEEP` runs for `oldVersion` 1 and 2 and not for 3. `check_sql.py` covers "v3 with lookups →
    delete a book the R2 way (no lookups delete) → re-upgrade → no orphan".
- `settings/SettingsStoreTest` (+): round trip of `highlightLook`, `listPaging`, `recordLookups`; clamps; `COVERS`
  round trip; an unknown list-mode name → `LIST`.
- `data/SettingsJsonNotesTest`: mapping and clamps; an old backup keeps the device values.
- `reader/ReaderJumpTest`:
  - `sanitize` round trip;
  - negative section or offset, NaN or infinite frac → null; end ≤ offset → -1; anchor cut to 64;
  - the `resolve` table (4 rows);
  - `JumpAnchor.matches`: whitespace and U+FFFC ignored, changed text fails, empty anchor matches, offset past the
    end fails.
- `render/QuoteStylesTest` (highlights.md §10):
  - unknown ids → 0;
  - ink greys are multiples of 0x11 in 0xBB..0xEE;
  - pairwise distinct looks;
  - contrast day ≥ 12 : 1, night ≥ 8 : 1 (sRGB luminance computed in the test);
    - **[Δ] colour fills only**. Ink 0xBB under black text is 10.9 : 1 and would fail a blanket check. Ink greys are
      tested for level exactness and pairwise distinctness instead.
    - Computed values: day 16.5 / 14.7 / 13.5 / **12.05** / 12.95; night 8.09 / 9.3 / 9.8 / 10.7 / 10.3.
    - 빨강 day and 노랑 night pass by < 0.1, so compute in `Double` with no intermediate rounding.
  - labels and tags;
  - `thumbGrey` is never "none";
  - `QUOTE_STYLE_MAX == DataLimits.QUOTE_STYLE_MAX`.
- `ui/kit/InkTouchTest` (+): `PageDrag` (within slop → 0; up-drag → +1; down → −1; cancel; diagonal (60, 45) never a
  vertical page drag; `axisBoth` left-drag → +1); `PageFit.fit(596·2, 149·2) = (4, 298)`, `fit(100, 149) = (1, 100)`;
  `ListPaging.paged` table.

---

## 5. DATA (W1)

### 5.1 `Library` additions (defaulted parameters stay source-compatible with every caller)

```kotlin
fun addQuote(bookId: Long, section: Int, start: Int, end: Int, text: String, note: String = "",
             style: Int = 0, place: NotePlace? = null): Quote
fun addBookmark(bookId: Long, section: Int, offset: Int, snippet: String, place: NotePlace? = null): Bookmark
fun updateQuoteStyle(id: Long, style: Int)                 // clamps to 0..DataLimits.QUOTE_STYLE_MAX
fun setQuoteStyles(ids: Collection<Long>, style: Int)      // one transaction (hub batch recolour)
fun deleteQuotes(ids: Collection<Long>)                    // one transaction
fun deleteBookmarks(ids: Collection<Long>)
fun clearReviews(bookIds: Collection<Long>)                // review = '', review_at = 0
fun setReview(bookId: Long, text: String)                  // now also review_at = now (0 when blank)
fun setTrashed(bookId: Long, value: Boolean)               // false also clears missing_at
/** Backfill: only rows whose frac < 0 are touched (sig stays ''). One transaction. */
fun fillNotePlaces(bookId: Long, quotes: Map<Long, NotePlace>, bookmarks: Map<Long, NotePlace>)
/** In-memory change counter of everything the notes hub shows. Every cache of §5.3.6 is keyed by it. */
@Volatile var notesGen: Long = 0; private set
```

**`notesGen` is incremented** after the commit of every write that changes what the hub shows:
- quote and bookmark add, delete, note, style and place changes;
- review set and clear;
- every `Lookups` write;
- `deleteBookRows` (remove, empty trash, scanner drop);
- `setTrashed`, the scanner's trash and revive, `updateMeta` (titles show in the hub);
- **[Δ]** `writeFile` / `moveFile` (a refreshed file changes the parsed title and the path the hub's open check and
  the export read), and the backup's placeholder inserts (§5.6);
- `Backup.import`;
- **[Δ]** the reader's position save on pause or close (`last_read_at` feeds the hub's BOOK_RECENT order and the R
  arm's time; once per pause / close, not per page turn).

`resetProgress` does not bump it: it touches no notes. `deleteBookRows` also runs `DELETE FROM lookups WHERE
book_id = ?`.

### 5.2 `LibrarySql` / `BookRows` changes

| Constant | Change |
|---|---|
| `INSERT_QUOTE` | `INSERT INTO quotes(book_id, section, start_offset, end_offset, quote_text, note, created_at, style, chapter, frac, sig) VALUES (?,?,?,?,?,?,?,?,?,?,?)`. `LibrarySqlTest`'s placeholder count changes from 7 to **11**. |
| `SELECT_QUOTES`, `SELECT_ALL_QUOTES` | select `…, created_at, style, chapter, frac, sig` (`BookRows.quote` reads columns 8..11 when `columnCount > 8`) |
| `INSERT_BOOKMARK` | + `chapter, frac, sig` (9 args) |
| `SELECT_BOOKMARKS` | + `chapter, frac, sig` |
| `UPDATE_QUOTE_STYLE` (new) | `UPDATE quotes SET style = ? WHERE id = ?` |
| `SET_REVIEW` | `UPDATE books SET review = ?, review_at = ? WHERE id = ?` |
| `UPDATE_QUOTE_PLACE` / `UPDATE_BOOKMARK_PLACE` (new) | `UPDATE quotes SET chapter = ?, frac = ? WHERE id = ? AND frac < 0` (same for bookmarks) |
| `DELETE_LOOKUPS_OF_BOOK` (new) | `DELETE FROM lookups WHERE book_id = ?` |
| `SELECT_IDS_WITH_NOTES` (new) | `SELECT book_id FROM quotes UNION SELECT book_id FROM bookmarks UNION SELECT id FROM books WHERE review <> '' UNION SELECT book_id FROM lookups` |
| `SELECT_IDS_WITH_USER_DATA` | + `UNION SELECT book_id FROM lookups` |
| `SET_MISSING` / `CLEAR_MISSING` (new) | `UPDATE books SET trashed = 1, missing_at = ? WHERE id = ? AND trashed = 0` **[Δ]** (`AND trashed = 0`: a book the user put in 휴지통 must never become revivable); `UPDATE books SET trashed = 0, missing_at = 0 WHERE id = ? AND missing_at > 0` |
| `SELECT_MOVE_CANDIDATES` **[Δ]** | `… WHERE file_name = ? AND size = ? AND (trashed = 0 OR missing_at > 0) …`. Today it has `trashed = 0`, so a missing (scanner-trashed) entry could never be matched when the user moves the file back or opens it from a file manager. `Library.addOrUpdate`'s move path then also runs `CLEAR_MISSING`. |
| `COUNT_NOTES_OF_BOOKS` (new) | builder in `NotesSql` (variable ids): the sum of quotes, bookmarks, reviews and lookups of the given books |
| book selects | include `missing_at`; `BookRows.book` reads it by column name when present |

### 5.3 The read side: `data/NotesSql.kt` (pure, JVM-tested) + `data/Notes.kt`

Every call is blocking, runs on IO and is thread-safe. `NotesSql` builders return `SqlQuery(sql, args)` with the
arguments in placeholder order. **A list query never selects `quote_text`, `note` or `review` whole**: quotes may be
100,000 chars, and 50 whole rows can overflow the 2 MB CursorWindow.

#### 5.3.1 Arms (narrow key form: `k, id, b, t, s, o` = kind, row id, book id, sort time, section, offset)

**[Δ] Every arm aliases every column** (`AS k, AS id, AS b, AS t, AS s, AS o`), not only Q. As written, the M, R and
L arms have no aliases. A single-arm statement (the BOOKMARKS, REVIEWS and WORDS tabs) then fails to prepare:
"no such column: t", reproduced on SQLite 3.45. Compound statements only worked because they take their names from
the first arm. The search key scan (§5.3.7) adds `n` (has a memo, 0/1) and `st` (style, Q only; else -1).

| Arm | SELECT … FROM | Base WHERE |
|---|---|---|
| Q | `SELECT 1 AS k, q.id AS id, q.book_id AS b, q.created_at AS t, q.section AS s, q.start_offset AS o FROM quotes q` | `1` |
| QM | as Q | `q.note <> ''` |
| M | `SELECT 2, m.id, m.book_id, m.created_at, m.section, m.char_offset FROM bookmarks m` | `1` |
| MM | as M | `m.note <> ''` |
| R | `SELECT 3, b.id, b.id, CASE WHEN b.review_at > 0 THEN b.review_at ELSE b.last_read_at END, -1, -1 FROM books b` | `b.review <> ''` |
| L | `SELECT 4, l.id, l.book_id, l.created_at, l.section, l.start_offset FROM lookups l` | `1` |
| L1 | `SELECT 4 AS k, l.id AS id, l.book_id AS b, MAX(l.created_at) AS t, l.section AS s, l.start_offset AS o FROM lookups l … GROUP BY l.word_key` | `1` |

- L1 relies on SQLite's documented bare-column rule (3.7.11+, so 3.18 is fine): with one `MAX()`, the bare columns
  come from the row holding the max. That makes the row the **latest lookup of the word**, even after a dedupe
  refreshed an older row's `created_at`.
- Tabs map to arms:
  - ALL = Q, M, R, L;
  - QUOTES = Q; MEMOS = QM, MM; BOOKMARKS = M; REVIEWS = R;
  - WORDS = L, or L1 when `wordsOnce`.
- Filters are appended to every arm's WHERE, using that arm's alias:
  - book: `AND q.book_id = ?` (R: `AND b.id = ?`);
  - colour (QUOTES only): `AND q.style = ?`;
  - search: per token (`LibrarySql.searchTokens`, at most 8; `p = '%' + LibrarySql.escapeLike(t) + '%'`):
    - Q/QM: `AND (q.quote_text LIKE ? ESCAPE '\' OR q.note LIKE ? ESCAPE '\' OR q.book_id IN (SELECT id FROM books WHERE title LIKE ? ESCAPE '\'))`
    - M/MM: the same over `m.snippet`, `m.note`, title
    - R: `AND (b.review LIKE ? ESCAPE '\' OR b.title LIKE ? ESCAPE '\')`
    - L/L1: `AND (l.word LIKE ? ESCAPE '\' OR l.context LIKE ? ESCAPE '\' OR l.note LIKE ? ESCAPE '\' OR l.book_id IN (SELECT id FROM books WHERE title LIKE ? ESCAPE '\'))`
- Trashed and missing books are **not** excluded. Their notes stay visible, marked (§9.3).

#### 5.3.2 Page keys: date orders

- **Multi-arm** (ALL, MEMOS): `<arm> UNION ALL <arm> … ORDER BY t DESC, k, id DESC LIMIT ? OFFSET ?`. OLDEST uses
  `t ASC, k, id ASC`.
- **Single-arm** (QUOTES, BOOKMARKS, REVIEWS, WORDS): `ORDER BY t DESC, id DESC`, with **no `k`**. Checked on SQLite
  3.45: the constant `k` in ORDER BY forces "USE TEMP B-TREE FOR ORDER BY". Without it, the Q arm is `SCAN q USING
  INDEX quotes_created` with no sort.
- `PAGE_ROWS = 50`. Page `p > 0` asks `LIMIT 51 OFFSET 50p − 1`: the extra first row is the previous page's last
  row, and its day decides the first row's day header.
- **[Δ] Measured.**
  - Single-arm pages are `SCAN … USING INDEX *_created` at any depth (0.07–0.19 ms).
  - Multi-arm pages merge (`MERGE (UNION ALL)`, a partial sort per arm), but OFFSET walks the merge. Page 0 takes
    0.25 ms, and the page at offset 7,500 takes 7.5 ms (x86, ≈ 50 ms on the A53).
  - The page budget becomes **≤ 40 ms for p ≤ 20, ≤ 80 ms beyond** (numpad jumps to the far end).

#### 5.3.3 Page keys: book orders

1. **Books with notes** under the query. This also feeds the book chooser.

   ```sql
   SELECT bk.id, bk.title, bk.author, bk.path, bk.trashed, bk.missing_at, bk.last_read_at, n.c
   FROM (SELECT b AS bid, COUNT(*) AS c FROM (<arms, same filters>) GROUP BY b) n JOIN books bk ON bk.id = n.bid
   ```

   Sorted in Kotlin: BOOK_TITLE by `NaturalOrder` title ("2권" before "10권"); BOOK_RECENT by `last_read_at DESC`,
   then natural title. `BookSpans` (pure) turns the counts into prefix sums, and `locate(row) → (bookIndex, inner)`.
2. **Rows of one book** in reading order: `<arms with AND x.book_id = ?> ORDER BY s, o, k, id LIMIT ? OFFSET ?`. This
   uses `*_book` plus a small sort of one book's rows. A page that crosses books runs it once per book (1–2
   queries). The first row of each book carries the book header.

   **[Δ]** "1–2 queries" holds only for books with many notes. Lookups spread over many books give pages of one-note
   books, which would mean up to 50 statements per page (≈ 50 ms on the A53). Use one statement for the page's books:
   `SELECT * FROM (<arms with AND x.book_id IN (?, …)>) ORDER BY CASE b WHEN ? THEN 0 WHEN ? THEN 1 … END, s, o, k,
   id LIMIT ? OFFSET ?`, where OFFSET is the first book's inner offset and there are ≤ 50 books (≤ 100 placeholders).
   The outer `SELECT * FROM (…)` is required: a compound SELECT's ORDER BY may not hold an expression. That is "1st
   ORDER BY term does not match any column", checked.

#### 5.3.4 Details (second phase; at most four PK lookups per page, ≤ 51 placeholders)

```sql
SELECT q.id, q.book_id, q.section, q.start_offset, q.end_offset, substr(q.quote_text, 1, 601), substr(q.note, 1, 401),
       q.style, q.chapter, q.frac, q.sig, q.created_at FROM quotes q WHERE q.id IN (?, …)
SELECT m.id, m.book_id, m.section, m.char_offset, m.snippet, substr(m.note, 1, 401), m.chapter, m.frac, m.sig,
       m.created_at FROM bookmarks m WHERE m.id IN (?, …)
SELECT b.id, substr(b.review, 1, 601), b.review_at, b.last_read_at, b.progress FROM books b WHERE b.id IN (?, …)
SELECT l.id, l.book_id, l.word, l.section, l.start_offset, l.end_offset, l.context, substr(l.note, 1, 401), l.chapter,
       l.frac, l.sig, l.via, l.app, l.created_at, (SELECT COUNT(*) FROM lookups l2 WHERE l2.word_key = l.word_key)
FROM lookups l WHERE l.id IN (?, …)
```

- `bodyCut`: the substring came back 601 chars long. The shown body is cut to 600, and no `length()` is used.
- Rows are put back in key order in Kotlin.
- Reviews use `frac = progress`.
- Titles come from a `NoteBook` map loaded once per `notesGen`, filled by `SELECT … FROM books WHERE id IN (…)` for
  ids not yet in the map.

#### 5.3.5 API

```kotlin
object Notes {
    const val PAGE_ROWS = 50; const val BODY_CHARS = 600; const val NOTE_CHARS = 400
    fun counts(q: NotesQuery): NotesCounts                                  // one UNION ALL … GROUP BY k statement
    fun styleCounts(q: NotesQuery): IntArray                                // index = style 0..QUOTE_STYLE_MAX (Q arm, filters except style)
    fun books(q: NotesQuery): List<NoteBook>                                // sorted per q.order (natural title when !byBook)
    fun page(q: NotesQuery, index: Int, books: List<NoteBook>?): NotesPage  // books required when q.order.byBook
    fun refs(q: NotesQuery): LongArray                                      // packed NoteRef of every row (select all, export)
    fun fullText(ref: NoteRef): Pair<String, String>?                       // (body, note), untruncated
    fun drawerCounts(): IntArray                                            // [quotes + bookmarks + reviews, lookups]
    fun countForBooks(bookIds: Collection<Long>): Int                       // delete / empty-trash warnings
    fun export(refs: LongArray?, q: NotesQuery, format: NotesExport.Format, out: java.io.Writer, now: Long): Int
}
class NotesPage(val index: Int, val rows: List<NoteRow>, val before: NoteRow?, val firstOfBook: BooleanArray)
internal object BookSpans { fun prefix(counts: IntArray): IntArray; fun locate(prefix: IntArray, row: Int): Long /* book shl 32 | inner */ }
```

- The counts statement is `SELECT k, COUNT(*) FROM (<Q> UNION ALL <M> UNION ALL <R> UNION ALL <L|L1> UNION ALL
  <QM relabelled 5> UNION ALL <MM relabelled 5>) GROUP BY k`.
  - **[Δ] Only when a text search is active, and then from the key scan of §5.3.7, not as its own statement.**
  - Without search (hub open, tab switch, book filter), counts are one statement of scalar subqueries over covering
    and partial indexes: `SELECT (SELECT COUNT(*) FROM quotes [WHERE book_id = ?]), (… bookmarks …), (… books WHERE
    review <> '' [AND id = ?]), (… lookups … | SELECT COUNT(DISTINCT word_key) …), (… quotes WHERE note <> '' …),
    (… bookmarks WHERE note <> '' …)`.
  - Measured: 0.29 ms, against 3–4 ms for the UNION ALL … GROUP BY form. That form materialises every row through a
    co-routine and a temp B-tree, and its QM arm walked quote overflow pages. On the A53 that is ≈ 2 ms vs 25–30 ms,
    and the 15 ms budget is only met by the scalar form.
  - `styleCounts` without search: `SELECT style, COUNT(*) FROM quotes [WHERE book_id = ?] GROUP BY style`.
- `drawerCounts` =
  `SELECT (SELECT COUNT(*) FROM quotes) + (SELECT COUNT(*) FROM bookmarks) + (SELECT COUNT(*) FROM books WHERE review <> ''), (SELECT COUNT(*) FROM lookups)`.

#### 5.3.6 Caches (all keyed by `Library.notesGen`)

- `counts` by (gen, bookId, text, style, wordsOnce); the tab and the order don't change counts.
- `books` by (gen, tab, text, style, wordsOnce).
- `drawerCounts` by gen.
- `styleCounts` by (gen, bookId, text).
- The NoteBook title map by gen.

#### 5.3.7 [Δ] Search: one key scan per query, not three or four

As written, one search keystroke (after the debounce) scans the quote text 3–4 times:
- counts: the Q arm and the QM arm, each with the LIKE filter;
- the page: for a rare word, the ORDER BY index scan cannot stop early;
- `styleCounts` on the 인용문 tab.

Measured at 25–30 ms per scan on x86 with 10k mixed-length quotes, and 8.6 ms with 150-char quotes. That is ≈ 60–200
ms per scan on the A53, so 150 ms per search is out of reach.

**Rule.** While `q.text` is not blank:
- `Notes` runs **one** statement: every arm of the tab (ALL: Q, M, R, L) with the search filters, selecting the key
  columns plus `n` and `st`, with no ORDER BY.
- It keeps the rows in memory as parallel arrays (`NotesKeys`, pure: ≤ 20k rows × 8 ints ≈ 0.6 MB), cached by
  (gen, text, bookId, wordsOnce).
- Counts (memos = `n == 1`), `styleCounts` (`st`), the date orders (an in-memory sort by t, k, id), `BookSpans` and
  the book list are all derived from that array. Only the details run per page (PK lookups, §5.3.4).
- A tab or colour-chip change inside the same search costs no query, just a filter over the array.

Measured on x86 with 150-char quotes, the one scan took 8.6 ms, ≈ 60 ms on the A53, inside the 150 ms budget.
`NotesKeysTest` (pure) covers sort orders, counts, memo relabelling and the style filter against the SQL results on
the seeded fixture.

### 5.4 `data/Lookups.kt` (adopted: hub.md §5, "Lookups" and the dedupe SQL)

```kotlin
object Lookups {
    const val VIA_APP = 0; const val VIA_WEB = 1; const val VIA_WEB_FALLBACK = 2
    const val DEDUPE_MS = 10 * 60_000L
    /** IO. Same book + word_key + section + start within DEDUPE_MS refreshes that row (created_at, via, app, context);
     *  else INSERT. One transaction. Returns the row id; -1 for a missing book (never throws). Caps with DataLimits. */
    fun record(bookId: Long, word: String, section: Int, start: Int, end: Int, context: String,
               place: NotePlace?, via: Int, app: String, now: Long = System.currentTimeMillis()): Long
    fun setNote(id: Long, note: String)
    fun delete(ids: Collection<Long>)
    fun clearAll()                         // "단어장 비우기"
    fun count(): Int
}
/** Pure. NFC, trim, strip surrounding quotes/brackets/punctuation (“”‘’"'「」『』()[]《》〈〉.,!?…·~), collapse whitespace,
 *  ASCII-lowercase, ≤ 100 chars. Korean particles are NOT stripped ("비명을" ≠ "비명"). */
object LookupWords { fun key(word: String): String }
```

### 5.5 Scanner: notes are never lost silently (`FileScanner`)

- `KnownBook` gains `missingAt`. `SyncPlan` gains `trash: List<Long>` and `revive: List<Long>`. It stays pure and is
  tested in `ScanPlanTest`.
- For a **vanished, unmoved entry**:
  - id in `SELECT_IDS_WITH_NOTES` → `trash` (`SET_MISSING now`);
  - otherwise → `gone`, as today.
- For an entry with `missingAt > 0` **found again** at its path (or matched as moved) → `revive` (`CLEAR_MISSING`).
- A user's own 휴지통 (`missing_at = 0`) is never revived automatically. 복원 (`setTrashed(false)`) clears
  `missing_at`.
- **[Δ] `plan()` as it stands cannot do this.** `FileScanner.plan` skips every trashed entry
  (`if (k.trashed || found.containsKey(k.path)) continue`), and only non-trashed vanished entries are `movable`.
  Missing entries are trashed, so they were never revived or re-pointed. The rules:
  - A trashed entry with `missingAt > 0` and a file found at its path → `revive`, and its size/mtime change → `todo`
    as usual.
  - Such an entry also joins `movable` (name + size), so a moved file re-points it (and revives it) instead of
    creating a new book with no notes.
  - A trashed entry with `missingAt = 0` stays skipped, as today (kept until the trash is emptied, never marked
    missing).
  - `SELECT_IDS_WITH_NOTES` is a lambda queried only when some entry vanished, like `userDataIds`. A periodic
    auto-scan that finds nothing gone runs no extra statement.
- Opening a missing book shows the existing "file not found" path. The hub says "책 파일을 찾을 수 없습니다 (노트는
  남아 있습니다)" (§9.4).

### 5.6 Backup (DATA: `Backup.kt`, `BackupJson.kt`; `BackupJson.VERSION` stays 1; every field optional both ways)

- **quote:** `style` (written only when ≠ 0; parsed with `coerceIn(0, 15)`), `chapter`, `frac`, `sig` (written only
  when `frac ≥ 0`).
- **bookmark:** `chapter`, `frac`, `sig`.
- **book:** `reviewAt`, `missingAt`, and `lookups: [{word, section, start, end, context, chapter, frac, sig, via,
  app, note, createdAt}]` (capped with `DataLimits`).
- UI_SPEC's `returnMark` travels in `book_prefs`.

Restore merge rules (**union; nothing is ever deleted by a restore**):

| Item | Rule |
|---|---|
| quotes | Matched by `section:start:end` as today. A new one is INSERTed with style and place. An existing one gets `style` only when its style is 0 and the backup's isn't; `chapter/frac/sig` only when its `frac < 0`; `note` only when empty (today's rule). |
| bookmarks | Matched by `section,offset` as today; place filled only when `frac < 0`. |
| lookups | Deduped on `(word_key, section, start_offset, created_at)`. |
| review | Restored as today; `review_at = max(current, backup)`. |
| **position** | **Newer wins:** apply only when `b.lastReadAt > current.lastReadAt` (or current is 0). Today's rule overwrites a newer device position with an older backup; this is what makes "restore from another device" safe (§13). The scroll SPEC §3.3 already assumes it. |
| missing | `b.missingAt > 0` and the file was found by the resolver → `trashed = 0, missing_at = 0`. |
| **[Δ] review** | Today's `RESTORE_FLAGS` does `review = COALESCE(NULLIF(?, ''), review)`, so any non-empty backup review overwrites a newer one written on this device. New rule: apply it only when `b.reviewAt > current.review_at`, or the current review is empty. Old backups have no `reviewAt`, so they fill only empty reviews. |
| **[Δ] flags** | `RESTORE_FLAGS` also writes `trashed`, `to_read`, `have_read` and **`encoding`** unconditionally. An older backup would re-trash, un-finish or, worst, **change a TXT's forced encoding**, which changes its parse and moves every quote's offsets. These four follow the position's newer-wins test (`deviceNewer` = `current.lastReadAt > b.lastReadAt`: keep the device's). `favorite` = OR. `reading_seconds` = MAX, as today. |
| **[Δ] quotes, other coordinates** | Two devices with different TXT options store the same quote at different offsets, and matching only on `section:start:end` would duplicate every such quote. Second key: same book, same `quote_text`, and `sig` values that differ → the same quote (fill style and place as above; no insert). |
| **[Δ] books not on this device** | Today `resolveBooks` skips a backup book whose file is not here, and **its notes are dropped silently**, which contradicts "union; nothing is lost". A backup book with ≥ 1 note (quote, bookmark, review or lookup) that did not resolve is inserted as a placeholder: `INSERT_BOOK` with the backup's path, name, size, format, title and author, then `SET_MISSING now`. Its notes are inserted under it. It shows in 휴지통 and the hub as "(파일 없음)", and §5.5's revive/move rules bring it back when the file arrives. Books without notes are still skipped, so a 500-book phone backup does not flood the Comet's trash. |

`notesGen++` after the import. The scroll SPEC's auto-backup hashes the whole snapshot JSON, so recolours, note edits
and 단어장 changes are all detected. highlights.md §4's fingerprint concern does not apply to that design.

### 5.7 Export (`data/NotesExport.kt`, pure, streaming to a `Writer`)

**Adopted: hub.md §10.1 (Markdown) and §10.2 (TXT) verbatim**, with these rules:
- **Order:** books in the hub's book order (natural title for date orders). Inside a book: review, quotes, bookmarks,
  words, each in reading order.
- **Memory:** full texts are read book by book, so one book's rows are in memory at a time.
- **Colour tag:** only when the exported quotes use ≥ 2 styles, the meta line starts with the tag:
  - Markdown `— [초록] 12화 과거로 · 37% · 2026-09-12 21:04`;
  - TXT `  — [초록] 12화 …`.

  A single-style export is byte-identical to the untagged format.
- **Book marks:** a trashed book's heading gets " (휴지통)", a missing one " (파일 없음)".
- **Markdown escaping:** as hub.md §10.1 (`NotesExport.md(text)`).
- **[Δ] Escaping, completed.** hub.md §10.1 misses what 옵시디언 and GFM also parse. Additions:
  - **Also escaped:** `~` (strike-through), `=` (`==highlight==`), `$` (math), `%` (`%%comment%%` hides text in
    옵시디언) and `&` (entities: `&nbsp;` would render as a space).
  - **At a line start**, also `=` (a setext heading inside a quote) and `N)`.
  - **Leading spaces and tabs** of every body line are stripped. Four or more would make an indented code block
    inside the `>` quote, and indented Korean TXT paragraphs are common.
  - **Normalised before escaping:** U+FFFC and C0 controls other than `\n` are removed, `\t` becomes a space,
    `\r\n` / `\r` / U+2028 / U+2029 become `\n`.
  - **Scope.** Escaping applies to **every** book- or user-derived field, not only bodies: title (heading), author,
    chapter (meta line), memo, word, app label. Chapter titles come from the book's TOC and may hold `*`, `#`, `_`.
  - `NotesExportTest` gets one golden line per rule.
- **[Δ] File names** (`NotesText.fileName`, tested):
  - strip `/\:*?"<>|`, C0 controls and U+007F;
  - drop leading dots (hidden files) and trailing dots and spaces (FAT/exFAT);
  - cut the title at 40 **code points**, never splitting a surrogate pair;
  - an empty result → "독서노트-20260930.md".
- **Share** (`ACTION_SEND`, TXT format): capped at 200,000 chars, cut at an item boundary with "\n…(나머지 N개는
  '내보내기'로 저장하세요)".
  - **[Δ] Cap 50,000 chars, not 200,000.** 200,000 is not "the binder limit":
    - `Intent.migrateExtraStreamToClipData` copies `EXTRA_TEXT` into a ClipData item for `ACTION_SEND`, and the
      chooser migrates its inner intent too;
    - `Parcel` strings are UTF-16, so 200k chars is ≈ 800 KB in one transaction, against the 1 MB process-wide
      binder buffer: `TransactionTooLargeException`, or a silent failure in the chooser.
  - 50k chars ≈ 200 KB with the copy.
  - The same constant (`TextActions.SHARE_MAX_CHARS`, EXTRAS_TOOLS) replaces the 200k in
    `ContentsDialog.shareAllQuotes` (EXTRAS_NAV).

### 5.8 DATA budgets (Comet A53, a seeded DB of 10,000 notes over 500 books, log tag `RANotes`)

| Call | Target |
|---|---|
| `counts` | ≤ 15 ms ([Δ] scalar form, §5.3.5; ≈ 2 ms expected) |
| `page` (keys + details) | ≤ 40 ms ([Δ] p ≤ 20; ≤ 80 ms for deeper multi-arm pages, §5.3.2) |
| `books` | ≤ 30 ms ([Δ] measured 6.4 ms x86 ≈ 45 ms on the A53 without search: it is only run for book orders and the chooser, and cached by gen; budget ≤ 50 ms) |
| search page | ≤ 150 ms ([Δ] only with the one key scan of §5.3.7; the seeder must use realistic quote lengths: 70 % ≤ 200 chars, 5 % ≥ 1,500) |
| `drawerCounts` | ≤ 5 ms |
| `record` | ≤ 5 ms |
| `export` of 10,000 notes | ≤ 3 s, off main |
| v2 → v3 upgrade | ≤ 50 ms |

FTS is **not** used: LIKE over a personal library is within budget, and FTS would add triggers to every quote write.

---

## 6. READER_A (W1): open at a note, peek, places, colours on the page

### 6.1 Open path (`startOpen`): still "nothing new before the first page"

Where `start` is computed today (the `remap` line):

```kotlin
val jump = ReaderJump.from(intent)                                    // 6 getExtra calls, main thread, no I/O
val sig = LayoutKeys.textSignature(eff, d.format, b.encoding)         // already computed for remap: reuse the value
val saved = if (remap != null) s.counts.locateFraction(remap) else DocPosition(b.posSection, b.posOffset)
val target = jump?.let { ReaderJump.resolve(it, sig, s.sectionCount) { f -> s.counts.locateFraction(f) } }
val start = target ?: saved
```

The same single `s.layout(sec)` follows, so the first page costs the same. When `jump != null`:

**[Δ]** `sig` above becomes `NoteSig.of(LayoutKeys.textSignature(eff, d.format, b.encoding), b.sizeBytes)`. The
remap line keeps using the plain `textSignature`, and the session caches both after the first page.

1. `setIntent(ReaderJump.strip(intent))`, so a recreation opens at the saved position, not at the note again.
   - **[Δ] That covers only the in-process object.** After process death (routine on the Comet while the user is in
     another app), the system recreates the activity from the **launch** intent, jump extras included.
     `setIntent` does not reach the system's ActivityRecord. A user who read on from a note would be thrown back to
     it, in peek mode again.
   - Fix: `onSaveInstanceState` puts `"jump_done" = true` once a jump was consumed. `onCreate` strips the jump
     extras before `startOpen` when `savedInstanceState?.getBoolean("jump_done") == true`.
   - `ReaderJumpTest` can't cover this (Android); it goes in the Comet manual pass: jump, turn 3 pages, Home,
     `adb shell am kill`, reopen from recents → the book shows the turned page.
2. If `jump.end > jump.offset` and `target == DocPosition(jump.section, jump.offset)` (the stored coordinates were
   used, not the `frac` fallback), put
   `Highlight(offset, end, SEARCH)` under owner `OWNER_JUMP = "jump"` for that section **before `showPage`**, in the
   same map `setHighlights` fills, with no redraw call. The first frame shows the mark, so there is no second e-ink
   update. `userTurn` removes it next to `OWNER_SEARCH` (O(1)).
   - **[Δ] Only if the anchor matches.** The section is laid out at this point, so run
     `JumpAnchor.matches(l.content.text, off, jump.anchor)` first. It is O(anchor) and in memory.
     - A miss → no mark. Otherwise a changed file would flash a mark on the wrong words, and §6.4's move would be a
       second update showing different text.
     - The mark is `[off, min(end, l.content.length))`: `off` is already clamped to the section, `end` was not.
   - **[Δ]** Step 3's chip is set in the **same main-thread message** as `showPage`, with no `post`, so the page, the
     mark and the chip are one frame and one e-ink update.
3. After `showPage`: `if (!isOnCurrentPage(saved)) returnNav.onJump(saved)` (UI_SPEC §3.5; with the chrome hidden it
   shows the chip "‹ N 페이지로"). The return target is **where the user was reading**. If UI_SPEC has not landed,
   use `pushReturn(saved)`.
4. **Peek** (§6.2): `peekUntilTurn = true`.
5. `target == null` (unusable coordinates): open at `saved`, toast "노트가 있던 곳을 찾지 못했습니다".

### 6.2 Peek rule

`private var peekUntilTurn = false`. While it is true, skip:
- `savePositionNow`;
- the scheduled position save;
- `writeTextPosition`;
- the pause/stop save.

So `pos_*`, `progress` and `last_read_at` stay as they were: Back after a quick look leaves the book exactly where the
user had it, and 읽고 있는 책 keeps its order.

It is cleared (then normal saving resumes) by:
- the first manual turn (tap, key, swipe);
- TTS start, auto-turn start;
- a remembered jump (TOC, search, go-to, seek, link);
- using the return strip or chip;
- "여기서 읽기";
- in scroll mode, the first user scroll settle.

The reading tracker is unchanged. Cost: one boolean check per save.

### 6.3 The same book already open (`onNewIntent`)

- Today it returns early when `id == bookRef?.id`. New behaviour: when a jump is present,
  `goTo(resolve(...) ?: return toast, remember = true)`, plus the mark and the anchor check. There is no reopen and
  no peek, because the book was already being read and this is a normal remembered jump.
- ReaderActivity is `singleTask`, so a hub opened from the reader is finished by this launch. A hub opened from the
  library stays below the reader, and Back returns to it.
- Another book: `closeCurrentBook()` then `startOpen(intent)`, as today, and §6.1 applies.

### 6.4 Anchor check (after the first page; O(anchor) in the common case)

In `afterOpen` (and after §6.3's `goTo`), when `jump.anchor` is not empty:

1. `JumpAnchor.matches(layout.content.text, offset, anchor)`: one in-memory loop, the common case, and nothing else
   happens. **[Δ]** Skipped when the target came from the `frac` fallback (sig mismatch): `offset` is then an old
   coordinate, and matching at it means nothing. Go straight to step 2, searching outward from the located section.
2. On a miss: a cancellable job on `Dispatchers.Default` runs `JumpAnchor.find(text, anchor)` (pure, READER_A adds
   it to `ReaderJump.kt`; whitespace-insensitive; −1 when absent). It searches
   `document.loadSection(i).text` for sections `section ± 3`, then all sections outward, stopping at the first hit.
   This is the same source `SearchPanel` reads. The job is cancelled by a manual turn or by closing.
   - **[Δ] Capped.** "All sections" of the 14.8 MB TXT is ≈ 1,565 section loads: seconds of A53 CPU and tens of MB
     of short-lived strings, competing with page counting right after open.
   - The search stops after 48 sections or 3 M chars, whichever comes first, then falls to step 4. Each section is
     one unit, with `ensureActive()` between units.
   - A note that moved further than that is found by the reader's own search, which the step-4 toast can mention.
3. Found → `goTo(found, remember = false)`, the mark moves, toast "노트 위치를 다시 찾았습니다".
4. Not found → toast "노트가 있던 곳을 찾지 못해 가까운 위치를 열었습니다".
5. The found position is **not** written back to the note in v1 (that would rewrite user data).

### 6.5 Places and writers

- `ReaderActivity : NotePlaceHost`. `notePlace(pos)` returns:
  - the title of `chapterIndex.indexAt(pos)` (cleaned, ≤ 200; "" without a TOC);
  - `counts.charProgress(section, offset)`;
  - `sig` = the session's text signature, cached once after the first page ("" for EPUB).

  It returns `NotePlace.UNKNOWN` while there is no session.
- `toggleBookmark`: `Library.addBookmark(id, sec, off, snippet, place = notePlace(DocPosition(sec, off)))`. The place
  is computed on main before the IO launch.
- **Backfill.** When `reloadAnnotations` delivers quotes and bookmarks, rows with `frac < 0` get places on main (≤ 500
  rows per open, ≈ 5 µs each). Then one `ReaderIo.launch { Library.fillNotePlaces(...) }`. This happens once per row,
  ever.
  - **[Δ] Chunked.** On the A53 a place costs more like 10–20 µs: a TOC binary search, `charProgress`, and cleaning a
    title of up to 200 chars. 500 rows in one message is 5–10 ms on main right after the first page, the moment the
    user turns.
  - So work in chunks of 64 rows per `handler.post`, which interleaves with input. Write once at the end, or per 256
    rows.
  - Sig: backfilled rows keep `sig = ''` (unknown), as written. The sig at creation time is not knowable later.
- **[Δ] Stale coordinates are not drawn.**
  - In `reloadAnnotations`, a QUOTE whose `sig` is non-empty and ≠ the session's `NoteSig` (TXT options, encoding or
    file changed) is left out of `quotesBySection`. Its offsets point at other text, and today such a quote
    highlights the wrong words.
  - The TOC 인용문 row shows it with the meta suffix "· 위치 바뀜". Tapping it goes through the jump path, which
    finds it by `frac` plus the anchor.
  - Legacy rows (`sig = ''`) draw as before.
  - Cost: one string compare per quote, once per load.

### 6.6 Quote colours on the page (adopted: highlights.md §5)

1. `reloadAnnotations`: `Highlight(q.start, q.end, HighlightKind.QUOTE, q.style)`.
2. **`sameDecor` also compares `style`.** Extract it into pure `reader/DecorDiff.kt` (`same(a, b)`), tested. Without
   this, a recolour never repaints.
3. `applyAppSettings`: `QuoteLook.update(app.highlightLook, DeviceClass.cached(this))`. If `QuoteLook.generation`
   changed, call one `refreshDecor()` and invalidate. That redraw is user-initiated.
4. `afterOpen`: `if (DeviceClass.cached(this) == null) ReaderIo.launch { DeviceClass.probe(appCtx); main { QuoteLook.update(…) } }`.
   The next draw picks it up, with no forced redraw. This is the **same single probe call** the scroll SPEC places in
   `afterOpen`; do not add a second one.

### 6.7 Menus (`ReaderMenus.kt`)

- "독서 노트" (`ic_format_quote`) after "내 리뷰" → `NotesActivity.open(this, NotesTab.ALL, book.id)`.
- W2: "페이지 썸네일" (`ic_grid_view`) after "페이지 이동" → `ReaderPanels.showContents(this, 3)`. It is hidden in
  scroll mode.

### 6.8 Scroll mode

The jump `start` is the scroll anchor too: the scroll SPEC's open path takes the same `start`. `OWNER_JUMP` is an
owner highlight, so it draws in scroll mode. Peek ends at the first user settle. Nothing else differs.

---

## 7. EXTRAS (W1)

### 7.1 EXTRAS_TOOLS (`SelectionController.kt`, `ExtrasUi.kt`, new `QuoteSwatch.kt`, `QuotePalette.kt`, `LookupContext.kt`, `QuoteExport.kt`)

1. **`QuoteSwatch`** (adopted: highlights.md §6.1). A static View with no ripple.
   - Colour mode: a filled dot (20 dp in popups, 12 dp in rows) with a 1 px `Ink.GRAY` ring; 밑줄 is "가" over a
     2 px line.
   - Ink mode: a 30 × 20 dp sample (22 × 14 dp in rows) of the band and line around a black "가".
   - `isChecked` draws a 2 dp black ring 3 dp outside.
   - UI swatches always use **day** values.
2. **`QuotePalette.show(anchor, current, onPick)`**: a one-row `PopupWindow`.
   - `borderBox`, `animationStyle = 0`, `elevation = 0`; an outside touch dismisses.
   - 6 cells, each `max(48dp, (W − 16dp) / 6)` wide and 56 dp tall: the swatch above a 12 sp label (노랑 초록 파랑 빨강
     보라 밑줄). `current` is ringed.
   - A tap calls `onPick` and dismisses. It is registered with `PanelRegistry.popup`.
   - `internal`: `ui/notes` is in the same module.
3. **Last-used style**: raw pref `extras.quoteStyle` (Int, default 0), written on every palette pick.
4. **Selection popup.** UI_SPEC polish 13 builds the one row of 5 (복사 · 인용 · 메모 · 사전·번역 · ⋮). This spec
   adds, in the same file and lane:
   - the 인용 cell shows `QuoteSwatch(last, 20 dp)` in place of its icon, with a 9 sp "▾" at its lower right;
   - **tap 인용** → `saveQuote(snap, note = "", style = last)`, then `clear()`. One tap, and **no success toast**
     (the quote appearing is the confirmation; a toast costs the Comet two updates). The failure toast stays;
     - **[Δ] One update, not two.** Today `clear()` repaints at once, and the IO insert, then
       `Library.quotes()`, then `refreshQuoteHighlights` repaints again when it returns: two e-ink updates. Instead,
       in the same main-thread message as `clear()`, add the new `Highlight(start, end, QUOTE, style)` to the
       section's "quotes" owner list (a copy of `QuoteCache` plus the new one). The IO insert and reload then find
       `sameDecor` true and draw nothing.
     - On failure, remove it: one update, plus the failure toast.
   - **long-press 인용**, or **⋮ → "색 골라 인용…"** (first overflow item) → the palette anchored at the 인용 cell;
     a pick saves with that style and sets `last`;
   - **메모** → the note prompt, then a quote in `last` style.
5. **Existing quote** (`editingQuote != null`): a palette row sits above UI_SPEC's row (복사 · 메모 · 인용 삭제 ·
   사전·번역 · ⋮):

   ```
   ┌──────┬──────┬──────┬──────┬──────┬──────┐  palette row 56 dp; current ringed
   │ (●)  │  ●   │  ●   │  ●   │  ●   │  가̲   │
   │ 노랑  │ 초록  │ 파랑  │ 빨강  │ 보라  │ 밑줄  │
   ├──────┴─┬────┴───┬──┴─────┬┴──────┴┬─────┤
   │  복사   │  메모   │ 인용 삭제 │ 사전·번역 │  ⋮  │  UI_SPEC's row of 5
   └────────┴────────┴────────┴────────┴─────┘
   ```

   - While the selection equals the quote, the selection fill is not drawn (`setHighlights("selection", sec,
     emptyList())`), so the real colour shows. The handles stay.
   - **Swatch tap** → IO `Library.updateQuoteStyle(q.id, s)` + `Library.quotes(bookId)` →
     `ContentsDialog.refreshQuoteHighlights(host, sec, all)` → `last = s`, the ring moves, and **the popup stays
     open** (one partial update). Recolouring has no confirm.
6. **Places and lookups.**
   - `snapshot()` adds `place = (host as? NotePlaceHost)?.notePlace(DocPosition(section, selStart))`, and
     `saveQuote` passes `style` and `place`.
   - Before `clear()`, `lookUp()` / `webSearch()` capture a `LookupSnapshot`: `bookId`,
     `word = selectedText().take(200)`, `section`, `start`, `end`, `place`, and
     `context = LookupContext.sentence(layout.content.text, selStart, selEnd)`.
   - `TextActions.lookUp(activity, text, onPicked: ((via: Int, app: String) -> Unit)? = null)` calls `onPicked` when
     an app, "웹 검색" (site host) or the no-app fallback is picked, and **never on cancel**.
   - `TextActions.webSearch(ctx, text, onDone: (() -> Unit)? = null)` calls `onDone` after `startActivity` succeeds.
   - The callbacks run `if (Settings.app.recordLookups) ReaderIo.launch { Lookups.record(…) }`. Recording is silent.
7. **`LookupContext.sentence(text, start, end, max = 300)`** (pure):
   - bounds from `SentenceSplitter`'s terminal and closing rules and '\n';
   - at most 150 chars each way, cut at a space with "…";
   - U+FFFC removed, whitespace collapsed.
8. **`QuoteExport.tagged(quotes)`** = distinct styles ≥ 2, used by `ContentsDialog.shareAllQuotes`.
   - The format is highlights.md §7: "[초록] “…”".
   - A single-quote share is never tagged.
   - `QuoteCache` rows carry `Quote.style` as they are.

### 7.2 EXTRAS_NAV (`ContentsDialog.kt`)

- **Quote rows** (인용문 tab):
  - the row becomes `[text / 메모 / meta (weight 1)] [swatch column 48 dp]`. The swatch is at the top, aligned with
    the first text line, and the whole column is the touch target;
  - tap it → `QuotePalette.show(column, q.style)` → IO update → `refreshQuoteHighlights(host, q.section, all)` (only
    when `!stale()`) → re-bind that row;
  - **[Δ] The column must not be a clickable child.**
    - ContentsDialog's lists are `InkPager` lists. InkPager's drag detection is an `OnTouchListener` on the list, and
      its own KDoc says "rows must not hold clickable children".
    - A drag that starts on a clickable swatch never reaches the listener's DOWN. The ListView then intercepts after
      its slop and **scrolls smoothly** (frames on e-ink), or DragToPage compares against a stale `downY` and pages
      the wrong way.
    - Instead, the row keeps one click listener. A row `OnTouchListener` records the DOWN x and returns false. The
      click handler opens the palette when `x ≥ row.width − 48 dp`, and otherwise jumps as today. The same applies
      to any future control in these rows.
    - (The hub has no such limit: its rows sit in `InkListView`, which intercepts in `onInterceptTouchEvent`.)
  - the long-press menu gains **"색 바꾸기"** (`ic_ink_highlighter`).
- **Chips** above the list, only when the book's quotes use ≥ 2 styles: `[전체 12] [● 5] [● 3] [가̲ 4]`.
  - Chips are 44 dp tall with a 1 px border; the selected one is a black fill with white text.
  - A tap filters in memory, with no query. The tab label reads "인용문 5 / 12".
  - 모두 공유 shares the filtered set. The filter is not persisted.
- `refreshQuoteHighlights` maps `Highlight(…, QUOTE, it.style)`.
- **Link to the hub:** on the 북마크 and 인용문 tabs, a toolbar icon `ic_open_in_new` (content description "모든 책의
  노트") → `NotesActivity.open(ctx, NotesTab.BOOKMARKS / QUOTES)`.
- W2: the 4th tab (§12).

---

## 8. RENDER (W1): the two looks (adopted: highlights.md §2 and §3)

- **`QuoteLook`** replaces highlights.md's `EinkScreen` and uses the scroll SPEC's `DeviceClass`:

  ```kotlin
  object QuoteLook {
      @Volatile var generation: Int = 0; private set
      /** mode = AppSettings.highlightLook; eink = DeviceClass.cached(ctx). Bumps generation only when ink() flips. */
      fun update(mode: Int, eink: Boolean?)
      /** INK → true; COLOR → false; AUTO → eink == true. Any thread, no reflection. */
      fun ink(): Boolean
  }
  ```

- **`PageRenderer`:**
  - one preallocated fill `Paint` per style;
  - `syncLook()` at the top of `drawHighlights` (a volatile int compare when nothing changed);
  - **two passes per text line**: fills, then lines (quote lines, search box, TTS underline), so a selection fill no
    longer hides a quote's underline;
  - `drawQuoteLine(kind, …)` draws THIN / THICK / DASHED (a `drawRect` loop on a grid anchored at x = 0) / BOX.

  Nothing is allocated per draw. Dash maths is in pure `render/DashMath.kt`.
- **Ink levels (day; night = 255 − v):**

  | Style | Fill | Line |
  |---|---|---|
  | 노랑 | 0xDD | thin |
  | 초록 | 0xEE | dashed (thick) |
  | 파랑 | 0xCC | none |
  | 빨강 | 0xBB | thick |
  | 보라 | none | box |
  | 밑줄 | none | thick |

  `t1 = max(1px, round(0.5dp))`, `t2 = max(2px, round(1dp))`, dash `round(3dp)` / gap `round(2dp)`.
- **Colour fills:**
  - day `FFE37A / B9E4A2 / AFD3F5 / FFB0AB / D9C2F2` under black text;
  - night `5E4F00 / 24502A / 1D4570 / 6E2626 / 4D3470` under white text;
  - 밑줄 = a `t2` line in the text colour.
- Optional (RENDER's call): snap SEARCH 0xC0 → 0xBB, TTS 0xE0 → 0xEE, SELECTION 0xA8 → 0xAA.
- **[Δ] The weakest pairs in greys.**
  - 노랑 / 파랑 are one level apart (0xDD / 0xCC). They differ only by a **1 px** underline (`t1` at density 2).
  - In the Comet's fast refresh modes (A2/DU-like, binary or dithered), fills may threshold away. 파랑 (fill only)
    would then vanish, and 빨강 (fill + `t2` line) would equal 밑줄 (`t2` line).
  - The device pass adds a check: all 6 looks in the Comet's fastest mode.
  - If confirmed, the fallback is small and keeps stored values: 파랑 gets `LINE_DOTTED` (1 px dots, 1 px gaps,
    `DashMath`), and 빨강 keeps its line while its fill goes to 0xAA (still ≥ 9 : 1 under black). The test's grey
    range then becomes 0xAA..0xEE.
- **`DeviceClass`** is the scroll SPEC's (§1.11, same lane). If that spec is not in the run, RENDER writes it exactly
  as specified there.

---

## 9. NOTES (W1): the 독서 노트 screen (`ui/notes/*`, new lane; adopted: hub.md §8–§9 with the changes below)

### 9.1 Files

| File | Contents |
|---|---|
| `NotesActivity.kt` | Screen, state, toolbar, tabs, filter row, search row, selection mode, the export flow's `onActivityResult`. The companion comes from phase 0 (§4.10). |
| `NotesAdapter.kt` | Windowed adapter and row holders (one holder class; the parts per kind are shown or hidden). |
| `NotesMenus.kt` | Row menus, the 전체 보기 dialog, editors, the book chooser, the colour chooser, delete confirmations, share. |
| `NotesText.kt` | Pure labels: `time`, `dayHeader`, `bookHeader`, `place`, `countBadge`, `fileName`, `emptyText`, `scopeLabel`. |

### 9.2 Screen (Comet 360 × 720 dp)

```
┌────────────────────────────────────────────┐
│ ←   독서 노트                        🔍   ⋮ │ toolbar 56 dp: back 48, title 20 sp bold, search 48, more 48
├────────────────────────────────────────────┤ 1 px Ink.LINE
│ 전체 │인용문│ 메모 │북마크│ 리뷰 │ 단어   │ tabs 48 dp: 6 × (W/6) = 60 dp, 15 sp; selected bold + 3 × 40 dp bar
├────────────────────────────────────────────┤ 1 px
│ [모든 책 ▾]  [최신순 ▾]  [모든 색 ▾]        │ filter row 44 dp: chips 32 dp, 14 sp, h-pad 12 dp, 1 px border,
├────────────────────────────────────────────┤ radius 16 dp, 8 dp gaps; book chip max 150 dp (…); colour chip 인용문 tab only
│ 오늘 · 9월 30일 (화)                         │ group header 32 dp, 14 sp bold, pad-left 16 dp, LINE_LIGHT under
│ “그 순간, 멀리서 기차가 지나가는 소리가   (●)│ body 16 sp ×1.2, ≤ 4 lines │ right column 48 dp:
│ 들려왔다. 호수 위로 물안개가 피어오르자      │                             │  swatch cell 48×40 (quotes)
│ 세상이 조용해졌다.”                        ⋮ │                             │  ⋮ CardButton 48×48 "노트 메뉴"
│ 메모  이 장면을 다시 읽어 볼 것               │ note 14 sp, "메모" bold, ≤ 3 lines
│ 인용문 · 《리더플러스 샘플》 · 1화 · 3% · 21:04 │ meta 13 sp Ink.GRAY, 1 line
├────────────────────────────────────────────┤ LINE_LIGHT, inset 16 dp
│ 🔖 3화 겨울                                ⋮│ bookmark: 16 dp icon + title 16 sp bold (note line 1, else chapter)
│ 새벽 공기는 생각보다 차가웠고, 창문 너머로  │ snippet 15 sp Ink.GRAY ≤ 2 lines
│ 북마크 · 《big-cp949》 · 41% · 어제 20:10     │
├────────────────────────────────────────────┤
│ 비명                                3회  文 │ word 18 sp bold 1 line · "3회" 12 sp grey · [다시 찾기] 48×48 (ic_translate)
│ …타인의 비명이 퍼졌다. 튕긴 총알이…       ⋮│ sentence 15 sp, word bold, ≤ 3 lines
│ 뜻  외마디 소리                              │ note 14 sp
│ 단어 · 《먼치킨 대마법사…》 · 12화 · 37% · 파파고 · 9월 28일│
├────────────────────────────────────────────┤ 1 px
│ ◀ 이전        2 / 14 · 128개        다음 ▶ │ InkPagerBar 44 dp (paged only); label tap → InkNumPad "쪽 번호"
└────────────────────────────────────────────┘
```

- **Row:** `pressableBackground()`. Padding 16 dp left, 12 dp top and bottom, 0 right. The content column has
  weight 1; the right column is 48 dp.
  - Quote rows: swatch cell (48 × 40 dp, a `QuoteSwatch` of 12 dp or ink 22 × 14 dp, content description "색 바꾸기")
    above ⋮.
  - Word rows: [다시 찾기] above ⋮.
  - Every ⋮ is a `CardButton` with content description "노트 메뉴".
  - Rows are separated by `Ink.LINE_LIGHT` inset 16 dp (UI_SPEC token).
- **Meta line:**
  - the kind label ("인용문 · ") starts it in the 전체 tab only;
  - the book part is omitted when filtered to one book or grouped by book;
  - trashed book: "《제목》(휴지통)"; missing file: "《제목》(파일 없음)".
- **메모 tab:** the note is the body (16 sp, ≤ 4 lines), then the quoted text (14 sp grey, “…”, ≤ 2 lines).
- **리뷰 row:** "리뷰" 13 sp bold + book title 16 sp bold (1 line), the review 15 sp (≤ 6 lines), meta "리뷰 · 57% ·
  9월 12일".
- **Book-order header** (replaces the day header in book orders): 40 dp, "《제목》 · 12" 15 sp bold, author 13 sp
  grey on the right, ellipsized. A tap filters to that book.
- **Label texts** (`NotesText`):
  - time: today "21:04", yesterday "어제 21:04", this year "9월 28일", older "2025.12.03";
  - day headers: "오늘 · 9월 30일 (화)", "어제 · 9월 29일 (월)", "9월 28일 (일)", "2025년 12월 3일 (수)";
  - place: "12화 과거로 · 37%" (chapter ellipsized at 24 chars); "37%" without a chapter; the chapter alone when
    `frac < 0`; nothing when both are unknown. Percent = `floor(frac × 100)`.
- **No page numbers:** they depend on font and layout, and the hub never opens a book file.

### 9.3 Row kinds and gestures

| Gesture | Quote / memo | Bookmark | Review | Word |
|---|---|---|---|---|
| tap row | open the book at it (§6) | open at it | review editor | open at its sentence (word marked) |
| tap swatch / [다시 찾기] | palette → recolour | – | – | `TextActions.lookUp(this, word)` (records nothing) |
| ⋮ or long press | 책에서 보기 · 전체 보기 · 복사 · 공유 · 메모 편집 · 색 바꾸기 · 선택 · 삭제 | 책에서 보기 · 메모 편집 · 복사 · 공유 · 선택 · 삭제 | 리뷰 편집 · 책 열기 · 복사 · 공유 · 선택 · 리뷰 지우기 | 다시 찾기 · 문맥 보기 · 웹 검색 · 뜻 메모 · 복사 · 선택 · 삭제 (+ 모든 기록 보기 when grouped) |

- **Opening** (tap, 책에서 보기, 문맥 보기):
  - on IO, check `File(book.path).isFile`;
  - missing → toast "책 파일을 찾을 수 없습니다 (노트는 남아 있습니다)";
  - else `ReaderActivity.open(this, bookId, ReaderJump.of(row))`.
- **전체 보기** (shown when `bodyCut` or the body exceeds 4 lines): `alert()` with a `ScrollView` of `Notes.fullText`
  (16 sp ×1.25) and [복사] [공유] [닫기].
- **Editors:** `multilinePrompt` with the full text → `Library.updateQuoteNote` / `updateBookmarkNote` /
  `Lookups.setNote` / `setReview` on IO. The list reloads through `notesGen`.
- **Share, one row:**
  - quote: the `ContentsDialog.quoteShareText` format;
  - bookmark: "제목 · 12화 · 37%\n“snippet”\n메모: …";
  - word: "비명 — “sentence” (제목)".
- **삭제:** `confirm` (§19 wording) → IO delete → toast "삭제했습니다". An open reader refreshes its highlights on
  focus (`reloadAnnotations`, existing 1 s guard).

### 9.4 Multi-select (from a row's "선택" or overflow "여러 개 선택")

```
│ ✕   12개 선택                 ⤴   ⇪   ●   🗑 │ 56 dp: close 48, title 18 sp bold, 공유 48, 내보내기 48 (ic_upload),
                                                  색 바꾸기 48 (ic_ink_highlighter; only when a quote is selected), 삭제 48
```

- Rows show `ic_check_box` / `ic_check_box_outline_blank` (24 dp) in a 40 dp column before the content. Entering or
  leaving re-binds visible rows only. A tap toggles the row, and so does a long press.
- The selection is a `LongHashSet` of `NoteRef.packed()`. It survives paging and tab switches. Filter chips are
  disabled while selecting.
  - **[Δ]** There is no `LongHashSet` on the platform, and no AndroidX. Use a `HashSet<Long>`: 10k boxed entries are
    about 0.5 MB, and only while selecting.
  - `onSaveInstanceState` stores it as a `LongArray`. **[Δ]** Above 20,000 entries it writes the array to a file in
    `cacheDir` and stores only the file name, to keep the Bundle under the 1 MB binder limit. Never the query with a
    "select all" flag: that would bring back rows the user unchecked (and then delete or export them) and drop refs
    selected in other tabs. A file that is gone at restore restores no rows. A pending export's refs are saved the
    same way; when their file is gone, the picker's result toasts "내보내지 못했습니다" and writes nothing.
- Toolbar ⋮: 모두 선택 (`Notes.refs(q)` on IO: every row under the query) · 선택 해제.
- **색 바꾸기** → `QuotePalette` → `Library.setQuoteStyles(quoteIds, s)` → toast "인용문 {n}개의 색을 바꿨습니다".
- **삭제** → `confirm("노트 삭제", "선택한 {n}개를 삭제할까요?" + (" 리뷰는 책에서 지워집니다." if reviews), "삭제")`.
- Back or ✕ leaves selection mode.

### 9.5 Overflow, chooser dialogs, search

- **Overflow ⋮:** 정렬… · 책 선택… · 여러 개 선택 · 내보내기… · 공유 · ✓ 같은 단어 한 번만 (단어 tab only; raw pref
  `notes.wordsOnce`) · ✓ 찾아본 단어 기록 (`AppSettings.recordLookups`).
- **정렬:** a chooser of `NotesOrder.label`.
- **Book chooser** (chip or overflow): a `fullScreenDialog`.
  - toolbar "책 선택"; a filter `EditText` (hint "책 제목"); an `InkListView` of "모든 책 · {n}", then `NoteBook`
    rows;
  - each row: "《제목》" 16 sp + count right in grey, author 13 sp grey below, " (휴지통)"/" (파일 없음)" suffix;
  - paged per `ListPaging`; data from `Notes.books(q.copy(bookId = null))`.
- **Colour chooser** (the "모든 색 ▾" chip): a chooser dialog: "모든 색 · {n}", then one row per style with count > 0
  (swatch 14 dp + label + count), from `Notes.styleCounts`. It is shown only when `QuoteStyles.COUNT > 1` and the
  인용문 tab is active.
- **Search** (🔍 toggles a 52 dp row): `EditText` 17 sp, hint "인용문·메모·단어·책 제목 검색", `inkCursor(singleLine =
  true)`, [✕] clears. Debounce 300 ms; the previous job is cancelled. A second 🔍 tap closes it and clears the text.

### 9.6 Empty states (centre; 17 sp Ink.GRAY, ×1.3, 32 dp padding; text through `keepAll`)

| Tab / case | Text |
|---|---|
| 전체 | 아직 모은 노트가 없습니다\n\n책을 읽다가 글자를 길게 눌러 '인용'이나 '메모'를 누르거나 북마크를 추가하면\n모든 책의 인용문·메모·북마크·리뷰가 여기에 모입니다 |
| 인용문 | 인용문이 없습니다\n\n본문을 길게 눌러 문장을 선택한 뒤 '인용'을 누르세요 |
| 메모 | 메모가 없습니다\n\n문장을 선택하고 '메모'를 누르거나\n인용문·북마크의 메뉴에서 메모를 남기세요 |
| 북마크 | 북마크가 없습니다\n\n읽는 중에 메뉴의 북마크 버튼을 누르세요 (+ "\n화면 오른쪽 위 모서리를 눌러도 됩니다" when `bookmarkByTouch`) |
| 리뷰 | 리뷰가 없습니다\n\n책 메뉴의 '내 리뷰'나\n책을 다 읽은 뒤 나오는 화면에서 남길 수 있습니다 |
| 단어 | 찾아본 단어가 없습니다\n\n글자를 길게 눌러 '사전·번역'이나 '웹 검색'을 누르면\n찾아본 단어와 그 문장이 여기에 기록됩니다 |
| 단어, recording off | 단어 기록이 꺼져 있습니다 + `outlineButton` [기록 켜기] |
| search | ‘{검색어}’와 일치하는 노트가 없습니다 |
| one book | 이 책에는 노트가 없습니다 |

### 9.7 List mechanics (e-ink first)

- **The list is an `InkListView`** with `paged = ListPaging.paged(app.listPaging, DeviceClass.cached(ctx))` and
  `ListPager(list, bar)`:
  - `rowsPerPage = 0` (variable heights; InkPager's rule: step = fully visible rows, and a cut row leads the next
    page);
  - `labelSuffix = " · {count}개"`.
  - In scroll mode the bar is GONE and there is **no fast scroller**.
- **Windowed adapter:**
  - `getCount()` = `counts.of(tab)` under the query;
  - pages of 50 by row index, LRU of 6 pages (≈ 300 rows ≈ 300 KB);
  - a row not loaded yet binds a 64 dp placeholder "불러오는 중…" (14 sp grey) and requests its page;
  - when the page arrives, `notifyDataSetChanged()` runs only if a visible position falls in it;
  - `ListPager.onPaged` makes sure the pages of `first − 25` and `last + 25` are loaded, so a normal page turn never
    shows a placeholder.
  - **[Δ] Far jumps (number pad, `showRow` after a filter) fetch first, then move.** The target page is requested,
    and `setSelection` runs when it arrives, or after 250 ms at most. Otherwise the 64 dp placeholders are laid out,
    then replaced by taller rows: two e-ink updates, and a page whose rows shift under the user.
- **One e-ink update to open:** the first draw is held for up to 250 ms until page 0 and the counts arrive (the
  pattern of `LibraryActivity.startOpenLast`).
- **Rules:**
  - tab, chip and order changes are one redraw each;
  - no animations, ripples or timers ("오늘/어제" is computed at bind time);
  - a row's ⋮ is a `CardButton` (20 dp slop), which works inside the paged list because `InkListView` intercepts in
    `onInterceptTouchEvent` (InkPager's `OnTouchListener` could not, which is why InkPager is not used here).

### 9.8 State

- **Raw prefs:** `notes.tab`, `notes.order`, `notes.wordsOnce`.
- **`onSaveInstanceState`:** tab, order, book filter, search text, first visible position, selection.
- Intent extras win over the remembered tab. `notes_book` sets the book filter and the chip "《제목》 ✕".
- **`onResume`:**
  - `Library.notesGen` changed → drop the page cache, reload counts, keep the first visible position (clamped) →
    one redraw;
  - otherwise nothing happens (no idle redraw).

### 9.9 The 단어 tab (Section: Dictionary)

- **Rows:**
  - word (the selection's first line; long selections read "문장 앞부분…");
  - "N회" when `wordCount > 1`;
  - the sentence with the word bold (`StyleSpan(BOLD)` on the first case-insensitive hit; no hit → no bold);
  - "뜻" note;
  - meta "단어 · 《책》 · place · app · time".
- **같은 단어 한 번만** (L1): one row per `word_key`, its latest lookup. The row menu gains "모든 기록 보기", which
  sets the search text to the word and turns grouping off.
- **Actions:** [다시 찾기] → the dictionary chooser (records nothing). 웹 검색 → `TextActions.webSearch(this, word)`.
  문맥 보기 = tap → the book at the sentence with the word marked (`ReaderJump(section, start, end, frac, sig,
  anchor = word)`).
- **Privacy:** rows live only in `library.db` and in backups. The only network use is the web search the user starts.

### 9.10 Export and share flow

1. 내보내기… opens a chooser "내보내기 형식": "Markdown (.md) · 메모 앱·옵시디언" / "텍스트 (.txt)".
2. `ACTION_CREATE_DOCUMENT` (`CATEGORY_OPENABLE`, type from `Format.mime`, falling back to `text/plain`).
   `EXTRA_TITLE` is `독서노트-20260930.md`, or `독서노트-<제목 40자>-20260930.txt` when filtered to one book; the
   name strips `/\:*?"<>|`.
   - **[Δ] Never `text/plain` for a `.md` name.** The local DocumentsProvider (`FileUtils.splitFileName`) appends the
     MIME type's extension when the name's extension does not map back to it, which gives `독서노트-20260930.md.txt`.
     Use `text/markdown` (API 26–28 keep the name as given; newer ones map it to `md`).
     `application/octet-stream` is the fallback only if no activity resolves.
   - Name rules: §5.7 [Δ].
3. `onActivityResult` → IO: `openOutputStream(uri, "wt")` → `BufferedWriter(UTF-8, no BOM, "\n")` →
   `Notes.export(selection or null, q, format, w, now)`.
   - **[Δ] Mode `"w"` first, `"wt"` on failure.** Some cloud providers reject `"wt"`, and a newly created document has
     nothing to truncate.
   - **[Δ] The export survives the hub.**
     - The export job runs on an app-level scope (`ReaderIo`), not the Activity's. Leaving the hub during a 3 s
       export would otherwise cancel it and leave a truncated file. The toast uses the application context.
     - The pending format and the selection or query go into `onSaveInstanceState` when DocumentsUI opens. On the
       Comet the hub's process can die while the picker is up, and `onActivityResult` then reaches a recreated
       Activity.
4. The page-bar label shows "내보내는 중…" (static text, no progress updates).
5. Toast "노트 {n}개를 내보냈습니다", or through `ui/kit/Errors` "내보내지 못했습니다: …".

공유 sends the TXT format of the list or selection through `TextActions.share`, capped as in §5.7, with the toast
"노트가 많아 앞부분만 공유합니다".

### 9.11 NOTES budgets (Comet)

| Step | Target |
|---|---|
| `onCreate` → first rows drawn | ≤ 150 ms (one e-ink update) |
| page turn (loaded) | one layout, one draw, 0 queries |
| numpad jump far away | ≤ 40 ms fetch, at most one placeholder frame |
| tab, order or filter change | ≤ 80 ms to the new first screen |
| search | ≤ 150 ms for 10,000 notes |
| memory | ≤ 2 MB of rows (6 pages) |

---

## 10. LIBRARY (W1)

### 10.1 Entry points and note safety

- **Drawer.** After 휴지통: a `hairline()`, then `drawerRow(ic_format_quote, "독서 노트")` and `drawerRow(ic_translate,
  "단어장")`, then the existing divider and 설정 ….
  - Counts are `Notes.drawerCounts()` (`[0]` for 독서 노트, `[1]` for 단어장), fetched **inside the existing lazy
    `ensureCounts` IO job** (drawer open only) and cached by `notesGen`. 0 → no number.
  - Taps → `NotesActivity.open(this)` and `open(this, NotesTab.WORDS)`.
- **Book menu:** "독서 노트" (`ic_format_quote`) after "문서 속성" → `NotesActivity.open(ctx, NotesTab.ALL, book.id)`.
- **영구 삭제 / 휴지통 비우기:** first read `Notes.countForBooks(ids)` on IO. When n > 0:
  - the message adds "\n\n이 책의 인용문·메모·북마크·리뷰·단어 {n}개도 함께 지워집니다. 먼저 독서 노트에서 내보낼 수 있습니다.";
  - a neutral button [독서 노트] opens the hub filtered to that book (single book), or unfiltered for empty-trash.
- **Trash rows** with `book.missingAt > 0` show "파일 없음" in the meta line. The book menu there offers 복원 ·
  독서 노트 · 영구 삭제.

### 10.2 Four views (adopted: library.md §2.1–§2.5; the card numbers are reconciled with UI_SPEC polish 14)

| View (label) | Enum | Layout | Per Comet page (paged) |
|---|---|---|---|
| **전체** | `LIST` | The card. Cover 96 × 136 dp (1 px border), 12 dp gap, text column: title 18 sp **bold** ≤ 3 lines ×1.1, author 14 sp grey 1 line (GONE if blank), meta "TXT, 3.4MB · 3일 전" 14 sp grey, spacer, progress line 14 dp + "34%" 13 sp (min 40 dp), 5 × `CardButton` 48 dp. No card border: `LINE_LIGHT` separators inset 8 dp. Card padding `(10, v, 6, v)` dp with v = 10 in scroll mode and v = max(6, (rowH − 137) / 2) when fitted. List `paddingEnd 12dp` (UI_SPEC). | 4 (rowH 149) |
| **요약** | `COMPACT` | 48 × 68 cover (the canonical bitmap at 0.5×), title 16 sp bold ≤ 2 lines, `compactMeta` 13 sp grey ("★ 작가 · TXT 3.4MB · 다 읽음 / 새 책"), progress 10 dp + % 12 sp, ⋮ `CardButton` 48 dp × full row height. Row min 80 dp (88 in scroll mode). | 7 (rowH 85) |
| **썸네일** | `GRID` | `cols = max(2, (W − 16 + 6) / (110 + 6))`, cover 96 × 136 centred, progress 6 dp or "새 책" 10 sp, title 12 sp bold ≤ 2 lines centred | 9 (3 × 3) |
| **그리드** | `COVERS` (new) | `cols = max(3, (W − 16 + 6) / (80 + 6))`, cover 76 × 108, progress 4 dp or "새 책", title 11 sp 1 line centred | 16 (4 × 4) |

- **Strings on IO.** `BookRow` gains `metaLine`, `compactMeta` and `isNew`, built in `reload()` on IO by pure
  `LibraryText` functions. No per-row DB call.
  - **[Δ] `compactMeta` stays lazy.** The tree's `BookRow.compactLine()` builds it on first bind and memoises it.
    `reload()` sits on the cold-start critical path (IO, then the first draw), and building it for every row there
    costs 2,000 extra relative-time strings for a 2k-book library, for a view most users never open.
  - Only `meta` (today) and `isNew` (a boolean) are eager.
- **No-query switch.** `chooseMode` re-binds the rows already loaded (`showBooks(currentRows)`), keeps the first
  visible book, and saves the pref.
- **Book menu flags** (즐겨찾기 / 읽을 책 / 다 읽은 책) appear for every view except `LIST`.
- **Chooser** "보기" (overflow and MainPage):
  - "전체 — 표지 · 정보 · 버튼"
  - "요약 — 작은 표지와 한 줄 정보"
  - "썸네일 — 표지 3열"
  - "그리드 — 작은 표지 4열"

  Toolbar toggle icons: 전체 `ic_view_agenda` (or `ic_article`), 요약 `ic_view_list`, 썸네일 `ic_grid_view`, 그리드
  `ic_apps` (or `ic_grid_view`).
- **Multi-select state** (T1-13): a 2 dp black frame around the cover plus `ic_check_box` (20 dp) at its top-left, in
  every view.

### 10.3 Paging (e-ink default) and scrolling (phones)

- `paged = ListPaging.paged(app.listPaging, DeviceClass.cached(ctx))` for `listView` and `gridView`, plus the
  `ListPager` with `InkPagerBar` under the content frame.
  - An unknown device class counts as scroll, until the probe (§10.5) flips it; the switch happens at the next
    `onResume` rebuild.
- **[Δ] Fixed rows only where the holder height is exact.** The 전체 card's height is **not** fixed:
  - the title is ≤ 3 lines of 18 sp × 1.1, and the author line is GONE when blank;
  - with Noto CJK metrics (≈ 1.45 em per line) a card is ≈ 146 dp with a 1-line title and ≈ 200 dp with 3 lines.

  `minimumHeight = rowH (149)` cannot make 4 fit. A "4-per-page" page then shows a cut 4th card, and
  `setSelection(first + 4)` scrolls it past: the user never sees it whole. The 요약 row has the same problem (2-line
  titles).
  - **LIST and COMPACT** page by measurement (`rowsPerPage = 0`, InkPager's rule: the cut row leads the next page).
  - **GRID and COVERS** use fixed rows. Their cells get an exact height: the title uses `setLines(2)` / `setLines(1)`
    (not `maxLines`), and LayoutParams height = cell height.
  - `LibraryGridMathTest` asserts measured cell height == rowH at 360 and 411 dp.
  - CI 45's "exactly 4 whole cards" becomes "no card is cut, except the one that is the first card of page 2
    (check 46)".
- **Paged:**
  - `isFastScrollEnabled = false`;
  - rows fitted per page with `PageFit` (`rowsPerPage = rows × cols`) **[Δ] (GRID and COVERS only)**;
  - holders take `rowH` as `minimumHeight` **[Δ] (as exact height, grids only)**;
  - fitting runs in `onSizeChanged`, and `notifyDataSetChanged` only when rows exist and `rows` changed, so it adds no
    pass at cold start (the adapter is still empty);
  - page keys page;
  - the label opens `InkNumPad("쪽 번호", 1..total)`.
- **Scroll:** the H0 guarded fast scroller, fling, and the static thin scrollbar.
- **Cover prefetch (paged):** after a page shows, `CoverLoader.prefetch(ctx, next page's books, CANON_W, CANON_H)`
  decodes into the memory LRU on the existing 2 background threads. The next page then binds from memory, giving
  **one e-ink update per page**.

### 10.4 Covers

One canonical size, `CANON_W = dp(96) − 2`, `CANON_H = dp(136) − 2`, for every view. The ImageView scales it with
`CENTER_CROP`, downscaling only. The memory key and disk file are the same in all views, so a view switch never
regenerates covers.

**[Δ]** One `GridView` instance serves 썸네일 and 그리드. A mode switch sets `numColumns` and the cell size. Cold
start builds no second GridView, so it keeps today's view count.

### 10.5 Device class

After the first frame: `if (DeviceClass.cached(this) == null) ReaderIo.launch { DeviceClass.probe(applicationContext) }`.
This is off main and never on cold start's critical path. If the scroll SPEC's UI owner already adds this call in
the same function, keep one.

### 10.6 LIBRARY budgets

- **Cold start:** `am start -W` within +5 % of the R2 baseline.
  - `InkListView` costs the same as `ListView`.
  - `DeviceClass.cached` is a prefs read plus a string check.
  - No new query, and no extra layout pass.
- **Page flip:** one `setSelection`, one layout, one draw, covers from memory.
- **View switch:** 0 queries, 0 cover generations, one layout.
- **Drawer open:** one extra COUNT statement (≤ 5 ms) inside the existing IO job.

---

## 11. SETTINGS (W1)

- **MainPage, "일반" section:**
  - "서재 보기": the existing `valueRow`, which picks up the four labels by itself; its chooser uses the §10.2
    descriptions.
  - New **"목록 넘기기"** `valueRow` right after it:
    - options 자동 (이 기기: 쪽 단위|스크롤) / 쪽 단위 / 스크롤 → `listPaging`;
    - summary "서재와 독서 노트를 한 화면씩 넘깁니다 (e-ink 권장)".
- **MainPage, "읽기 설정" section:** new **"인용문 색 표시"** `valueRow`.
  - Options: "자동 (이 기기: 흑백 무늬|색)" / "색" / "흑백 무늬" → `highlightLook`.
  - Summary under the value: a static strip of the 6 `QuoteSwatch`es (22 × 14 dp) in the chosen look.
  - Note: "e-ink 화면에서는 색이 비슷한 회색으로 보여 무늬로 구분합니다 (노랑 = 회색+밑줄, 초록 = 옅은 회색+점선, 파랑 =
    회색, 빨강 = 진한 회색+굵은 밑줄, 보라 = 테두리, 밑줄 = 밑줄만)".
- **LookupPage, new section "단어장":**
  - switch "찾아본 단어 기록", summary "선택한 글자를 사전·번역·웹 검색으로 찾으면 단어장에 문장과 함께 남깁니다. 기기
    안에만 저장됩니다" → `recordLookups`;
  - row "단어장 열기" → `NotesActivity.open(ctx, NotesTab.WORDS)`;
  - row "단어장 비우기": `confirm("단어장 비우기", "찾아본 단어 {n}개를 모두 지울까요?", "비우기")` → `Lookups.clearAll()`.
- **BackupPage** (after the scroll SPEC's auto-backup section): a `note()` line
  - "다른 기기에서 만든 백업을 복원하면 두 기기의 인용문·메모·북마크·단어장이 합쳐지고, 읽던 위치는 더 최근 것이 남습니다.
    지워지는 것은 없습니다."
  - **[Δ]** This sentence is true only with §5.6's review, flags and placeholder rules. Append: " 이 기기에 없는 책의
    노트는 휴지통에 '(파일 없음)'으로 보관됩니다."
  - The privacy note adds "단어장".

---

## 12. W2: 페이지 썸네일 (READER_A + EXTRAS_NAV; adopted: library.md §3 with these changes)

**Changes to library.md §3:**
1. **Renderer.** No new `PageRenderer` constructor flag. `reader/PageThumbs.kt` keeps its own `PageRenderer` per
   generation on a single "reader-thumbs" daemon thread (BACKGROUND priority, created on the first request). Per cell:
   - `drawChrome(canvas, PageDecor(highlights, bookmarked = false, status = null), …)` for the background (UI_SPEC R11:
     `status = null`, never the reader's shared `StatusDecor`);
   - then the scroll SPEC's `drawBody(canvas, layout, idx, left, top, clipTop, clipBottom, highlights)`, which never
     decodes images;
   - both on `canvas.scale(w / viewW)` into an RGB_565 bitmap.

   This needs no new RENDER API: it depends on the scroll SPEC's RENDER work being implemented.
2. **Quote bands:** the thumb renderer draws highlights with the normal paints. At ≤ ¼ scale the lines vanish, which is
   acceptable. Optionally RENDER uses `QuoteStyles.thumbGrey` when `canvas` scale < 0.3 (a flag on the renderer
   instance, not per draw).
3. **Entry:**
   - the 4th tab "썸네일" (tabs 목차 · 북마크 · 인용문 · 썸네일, 90 dp each);
   - reader ⋮ "페이지 썸네일" → `showContents(host, 3)`;
   - the tab is hidden in scroll mode and disabled ("책을 여는 중입니다…") until a page is shown.
4. **Grid:** 4 × 3 on the Comet (78 × 156 dp thumbs), 5 × 3 on a 411 dp phone (`ThumbGridMath`, pure). The current
   page gets a 3 dp frame. Labels 12 sp use the footer's own number (`counts.globalPage`). Marks: ribbon (bookmark),
   ❝ (quote), ✎ (note), and a search dot in v1.1.
5. **One e-ink update per grid page:** wait up to 700 ms for a complete batch, then prefetch the next grid page in the
   direction of travel. Phones fill progressively (≥ 100 ms apart).
6. **Memory:** LRU of `min(8 MB, memoryClass/32)` on e-ink and `min(16 MB, …)` on phones, keyed
   `(genId, section, idx, wPx, hPx, decorVersion)`. It is cleared on a generation change, `onTrimMemory ≥
   RUNNING_LOW` and session close.
   - **[Δ] The key misses repaint-only changes.** Day/night (`invert`), the text and background colours and a weight
     stroke step are `BookSession.Change.REPAINT`: **no new generation**. `Generation.settings` also keeps the old
     values. A renderer built "per generation" from `gen.settings` and keyed by `genId` would serve and draw day
     thumbnails after switching to night. The fixes:
     - ReaderActivity keeps `paintVersion`, bumped on every REPAINT and `applyReaderColors` change.
     - The key gains `paintVersion` and `QuoteLook.generation`.
     - The thumb renderer takes `session.settings` at request time and is rebuilt when `paintVersion` changes.
   - **[Δ] `decorVersion` is per section** (an `IntArray` sized to the section count). It is bumped only for sections
     whose quotes or bookmarks changed. Transient owners (TTS, selection, jump, search) are **not** drawn in
     thumbnails and do not bump it. A single global counter bumped by every TTS sentence would invalidate the whole
     LRU while TTS runs. Search hits come back in v1.1 as marks, not fills.
   - **[Δ] Give the reader its neighbours back.**
     - Thumbnail layouts go through the reader's 4-entry section LRU and evict `curSection ± 1`.
     - On dismiss without a thumb tap there is no `showPage`, so nothing re-prefetches them. The next section-boundary
       turn would pay a 150–300 ms layout on the A53.
     - `PageThumbsHost.cancelThumbs()`, which the tab already calls on dismiss, also re-prefetches
       `curSection ± 1` in the background when a thumbnail layout ran in this dialog session (READER_A). No new API.
7. **`ui/kit/InkPager.kt` gains** `interface PageTarget { fun page(dir: Int): Boolean }`, implemented by `InkPager` and
   `ThumbsTab`, so `inkPagerKeys` works for all four tabs. `PageDrag(axisBoth = true)` handles grid swipes.

**Files:**
- READER_A: `reader/PageThumbs.kt` (new), `PageThumbsHost` on ReaderActivity (`decorFor`, `decorVersion` bumps in
  `reloadAnnotations`, `setHighlights` and bookmark toggles), the menu item.
- EXTRAS_NAV: `ContentsDialog.kt` (4 tabs: `arrayOfNulls(4)`, `coerceIn(0, 3)`), `reader/extras/ThumbsTab.kt` (new,
  `ThumbGridView`: one View that draws every cell), `InkPager.kt` (`PageTarget`).

**Budgets:**

| Case | Target |
|---|---|
| warm grid page (section cached) | ≤ 90 ms |
| cold grid page (1 new section) | ≤ 400 ms, under the 700 ms single-update cap |
| per thumbnail | ≈ 4–7 ms, ≈ 97 KB |
| open, turn | **+0**: nothing is created before the first request; `decorVersion` bumps only on annotation changes |

---

## 13. Synchronization: the decision

ReadEra's item syncs "books, documents, reading progress, bookmarks, and quotes with Google Drive". **We do not
build it.** The house rules are no network and no accounts. The only network use stays the web search and the
Wi-Fi transfer page that the user opens.

**What we offer instead** (all local, all built or specified):

| Need | Offer | Where |
|---|---|---|
| "Don't lose my notes" | **자동 백업**: daily, to `Download/ReaderaPlus/backup/`, survives uninstall. It carries positions, quotes (colours, places), memos, bookmarks, reviews, 단어장 and the pinned return point. The restore offer runs on reinstall. | scroll SPEC §3; §5.6 here |
| "Move to a new device / phone ↔ Comet" | **백업 파일에서 복원**, which now **merges**: notes are unioned, nothing is deleted, colours and places are only filled in, and positions are **newer-wins** (§5.6). Copy the backup file by USB, Wi-Fi 전송 or any file tool. | BackupPage (existing) + §5.6 |
| "Use my own cloud" | The auto-backup folder is a plain folder. Syncthing, FolderSync or a USB copy can carry it, without our app touching the network. BackupPage explains this in one line. | §11 |
| Book files | **Wi-Fi 전송** page (R2) | existing |
| Read notes elsewhere | 독서 노트 → 내보내기 (Markdown for 옵시디언 and memo apps, or TXT) | §9.10 |

**Not doing: an automatic two-way "sync folder"** (each device writes its own `readeraplus-sync-<id8>.json` and
merges the others on start). It looks simple and is not safe:

1. **Deletions.** A quote deleted on the Comet comes back from the phone's file. Fixing that needs per-note UUIDs,
   tombstones and `updated_at` on every table (a schema v4 and a change to every writer).
2. **Edits.** A memo edited on both devices has no winner without `updated_at`. Last-writer-wins by file time loses
   text.
3. **Book identity.** Paths differ between devices. The fileName + size heuristic mis-matches re-encoded or
   re-downloaded TXT files.
4. **TXT coordinates.** They depend on each device's parse options (`sig`). A merged quote can land on the wrong text
   and needs the §6.4 anchor search just to be shown.
5. **External sync tools.** They write partial files and "conflict copies" while the app reads, so a silent merge on
   start could resurrect notes or move positions **backwards**. That is worse than no sync.
6. **Cost.** A merge on start competes with "fast opening" on the A53.

Revisit only if the user asks explicitly. The prerequisites would be `uuid`, `updated_at` and tombstones (schema v4)
and an explicit "지금 동기화" button, never an automatic merge.

---

## 14. Performance budgets and release gates

| Where | Budget | Gate / how measured |
|---|---|---|
| **Open a book** (normal) | +0 work before the first page | RAPerf "open … first page": the cached 14.8 MB TXT within **+10 ms** of the R2 baseline (Comet) |
| **Open a book at a note** | = a normal open (same single layout; +6 `getExtra`, a map put) | RAPerf, note jump vs normal open of the same book: within noise |
| **Page turn** | +1 map removal (`OWNER_JUMP`), +1 boolean per save; highlight draw: second loop over 0–5 items, no allocation | RAPerf "turn N ms" unchanged on a page with 5 quotes of mixed styles |
| **Library cold start** | +0 queries, +0 layout passes | `am start -W` within **+5 %** |
| Drawer open | +1 COUNT (≤ 5 ms) on IO in the existing lazy job | `RANotes` log |
| Hub open → first rows | ≤ 150 ms, one e-ink update | `RANotes` |
| Hub page / tab / search | ≤ 40 / 80 / 150 ms (10,000 notes) **[Δ]** (page ≤ 80 ms beyond p 20; search only with the one key scan, §5.3.7; seeder with realistic quote lengths) | `RANotes` with the debug seeder |
| Selection 인용 | one IO insert; the popup closes at once, no toast | – |
| Lookup record | one IO statement after the pick; never blocks the chooser | – |
| Library page flip (paged) | one layout + draw, covers from memory | visual: one e-ink update per page |
| View switch | 0 queries, 0 cover generations | – |
| Thumbnail grid page | warm ≤ 90 ms, cold ≤ 400 ms, one e-ink update | `RAThumbs` log |
| DB upgrade v2 → v3 | ≤ 50 ms once (10,000 notes) | first start after update |
| APK | ≤ +150 KB code, no new required resources | CI artifact size |
| Idle | no timers, no polling, no idle redraws | `dumpsys gfxinfo` flat at rest |

Debug-only seeder: `adb shell am broadcast -a com.ggumtak.readeraplus.DEBUG_SEED_NOTES --ei n 10000`, a receiver
registered only in debug builds (`BuildConfig.DEBUG`), owned by DATA (`data/DebugSeed.kt`). It is never in release.

---

## 15. Owner map (disjoint files; compatible with the scroll SPEC and UI_SPEC)

Lane names are UI_SPEC §7.1's (the R2 names plus READER_UI) plus the new **NOTES**. The scroll SPEC's coarser lanes
contain them:
- READER_CORE ⊇ READER_A + READER_B + READER_UI;
- ENGINE_RENDER ⊇ RENDER;
- EXTRAS ⊇ EXTRAS_TOOLS + EXTRAS_NAV;
- UI ⊇ LIBRARY + SETTINGS.

**In a joint run, one agent per lane does all three specs' edits in its files.** NOTES owns only new files.

| Lane | Files this spec touches (main + tests) | Work | The same files in the sibling specs |
|---|---|---|---|
| **CONTRACT** (lead, phase 0) | `data/LibrarySchema.kt`, `data/Models.kt`, `data/SettingsJson.kt`, `settings/ReaderSettings.kt`, `settings/Settings.kt`, `render/Render.kt` (`Highlight.style`), frozen block of `reader/extras/ReaderPanels.kt`, `AndroidManifest.xml`, `ui/kit/Ui.kt` (KDoc), `docs/**`, `tools/ci/screenshots.sh`; complete files `reader/ReaderJump.kt`, `render/QuoteStyles.kt`, the `ui/kit/InkTouch.kt` paging part; skeletons §4.10; fallout (`Backup.kt` `Quote(...)` call); contract tests §4.12 | §4, the §17 CI steps | UI_SPEC §1 and the scroll SPEC §4.1, same pass |
| **DATA** | `data/Library.kt`, `LibrarySql.kt`, `BookRows.kt`, `Backup.kt`, `BackupJson.kt`, `FileScanner.kt`, `MetaInfo.kt`, new `Notes.kt`, `NotesSql.kt`, `NotesExport.kt`, `Lookups.kt`, `DebugSeed.kt`; new `tools/check_sql.py`; **[Δ]** `data/LibraryDb.kt` (runs `UPGRADE_SWEEP`; the constant itself is contract, §4.1), new pure `data/NotesKeys.kt` (§5.3.7) | §5 | scroll: `AutoBackup`, `InstallState`, `Backup` origin/summary; UI: `BookPrefs.returnMark`, backup `returnMark` |
| **READER_A** | `reader/ReaderActivity.kt`, `reader/ReaderMenus.kt`, `reader/ReaderJump.kt` (after phase 0), new `reader/DecorDiff.kt`; W2: new `reader/PageThumbs.kt` | §6, §12 | scroll: every scroll branch; UI: ReturnNav/Light wiring, status decor |
| **READER_UI**, **READER_B**, **FORMAT** | none | – | UI_SPEC's and scroll SPEC's work only |
| **RENDER** | `render/PageRenderer.kt`, `render/QuoteLook.kt` (after phase 0), `render/QuoteStyles.kt` (after phase 0), new `render/DashMath.kt`; `render/DeviceClass.kt` only if the scroll SPEC is not in the run | §8 | scroll: `drawChrome/drawBody/drawOverlay/prefetchPage`, `DeviceClass`; UI: `drawStatus`, `StatusMath`, `ProgressMath` |
| **EXTRAS_TOOLS** | `reader/extras/SelectionController.kt`, `ExtrasUi.kt`, new `QuoteSwatch.kt`, `QuotePalette.kt` (after phase 0), `LookupContext.kt`, `QuoteExport.kt` | §7.1 | UI: polish 13 (one-row popup), `hasHeader`; scroll: `ReadingSettingsPopup` |
| **EXTRAS_NAV** | `reader/extras/ContentsDialog.kt`; W2: new `reader/extras/ThumbsTab.kt`, `ui/kit/InkPager.kt` (`PageTarget`) | §7.2, §12 | UI: polish 10 (TOC title) |
| **NOTES** (new) | new `ui/notes/NotesActivity.kt` (after phase 0), `NotesAdapter.kt`, `NotesMenus.kt`, `NotesText.kt` | §9 | none |
| **LIBRARY** | `ui/library/LibraryActivity.kt`, `LibraryViews.kt`, `LibraryDialogs.kt`, `LibraryText.kt`, `CoverLoader.kt`, new `LibraryGridMath.kt`; `ui/kit/InkTouch.kt` (H0, then maintenance) | H0 §3; §10 | scroll: auto-backup trigger, `AutoRestorePrompt`; UI: polish 14, `DeviceLight.restoreIfStale` |
| **SETTINGS** | `ui/settings/MainPage.kt`, `LookupPage.kt`, `BackupPage.kt` | §11 | scroll: `PageTurningPage`, `BackupPage`, `MainPage`; UI: status page, light pages |

**Cross-lane calls in W1** (all exist as phase-0 signatures, so every lane compiles alone with `tools/typecheck.sh
--own`):
- NOTES → `Notes`, `Lookups`, `NotesExport`, `Library.*` (DATA); `ReaderActivity.open(…, jump)`, `ReaderJump`
  (READER_A / contract); `QuotePalette`, `QuoteSwatch`, `TextActions` (EXTRAS_TOOLS); `QuoteStyles` (contract);
  `InkListView`, `ListPager`, `CardButton` (kit); `DeviceClass` (scroll phase 0).
- LIBRARY, READER_A, EXTRAS_NAV and SETTINGS → `NotesActivity.open` (phase 0 complete).
- EXTRAS_TOOLS → `Lookups.record`, `NotePlaceHost`, `Library.addQuote(style, place)`, `updateQuoteStyle`.
- READER_A → `Library.addBookmark(place)`, `fillNotePlaces`, `QuoteLook`.
- SETTINGS → `QuoteSwatch`, `Lookups.count/clearAll`, `NotesActivity.open`.

**Order inside a lane** (the smallest broken window):
- DATA: SQL and `BookRows` first, then `Notes`.
- READER_A: §6.6 (`sameDecor`) first.
- EXTRAS_TOOLS: palette and swatch before the popup.
- LIBRARY: entry points before views and paging.

---

## 16. JVM tests (`tools/unittest.sh`; Android-native code stays out of tested classes)

| Lane | Test | Cases |
|---|---|---|
| contract | §4.12 | as listed |
| DATA | `data/NotesSqlTest` | arms per tab (MEMOS = QM + MM; WORDS L vs L1); filters on every arm with the right alias; search tokens bound in placeholder order and escaped (`%`, `_`, `\`); single-arm ORDER BY has no `k`; OLDEST reverses; page `p` → `LIMIT 51 OFFSET 50p − 1`; counts statement relabels memos as 5; detail queries use `substr(…, 1, 601/401)` and never a bare `quote_text`, `note` or `review`; **[Δ]** every arm aliases `k, id, b, t, s, o`; every single-arm statement is emitted for BOOKMARKS, REVIEWS and WORDS (and prepared by `check_sql.py`); unfiltered and book-filtered counts use the scalar form; the book-order page is one `SELECT * FROM (…) ORDER BY CASE …` statement |
| DATA | `data/NotesPagingTest` | `BookSpans.prefix/locate` (boundaries, empty books, last row); page-window maths; prefetch pages for a visible range |
| DATA | `data/NotesExportTest` | golden Markdown and TXT for a fixture (2 books, every kind, multi-line quote, memo, trashed and missing book, empty sections omitted, one-book scope, selection scope); tag only with ≥ 2 styles; Markdown escaping table; header counts = rows written; share cap cut at an item boundary with the suffix; **[Δ]** escapes `~ = $ % &`, a line-start `=` and `N)`, stripped leading indentation (no code block), U+FFFC/controls/U+2028 normalised, escaped titles/chapters/memos; share cap 50,000 |
| DATA | `data/LookupWordsTest` | NFC, punctuation and quotes stripped, ASCII lowercase, whitespace, ≤ 100 chars, Hangul particles untouched |
| DATA | `data/LibrarySqlTest` (edit) | `INSERT_QUOTE` 11 placeholders, `INSERT_BOOKMARK` 9; new constants prepare against the v3 schema |
| DATA | `data/BackupJsonTest` (+) | v3 fields round trip (style omitted when 0, place omitted when frac < 0, reviewAt, missingAt, lookups); an old backup gets defaults; caps and clamps |
| DATA | `data/BackupMergeTest` (new, pure `BackupMerge` extracted from `applyLibrary`) | style only onto 0; place only onto frac < 0; note only onto empty; lookups dedupe key; **position newer-wins** (older backup doesn't move a newer device position; never-read device takes the backup); missing revived; **[Δ]** review newer-wins by `reviewAt` (an old backup fills only an empty review); `trashed`/`to_read`/`have_read`/`encoding` kept when the device is newer; favourite OR; same-text quote with another sig → no duplicate; an unresolved backup book with notes → placeholder (trashed, missing) with its notes; one without notes → skipped |
| DATA | `data/ScanPlanTest` (+) | vanished with notes → `trash`; without → `gone`; moved → re-pointed; `missingAt > 0` found again → `revive`; user-trashed (`missingAt = 0`) never revived; **[Δ]** a missing entry whose file reappears at another path (same name + size) → re-pointed **and** revived; a user-trashed entry whose file vanished stays as is (no `missing_at`); the notes-ids lambda is not called when nothing vanished |
| DATA | `data/SqlDumpTest` (+) + `tools/check_sql.py` | the dump adds a `notes` object with sample statements for every tab × order × filter; `check_sql.py` (Python 3 `sqlite3`, run by the lead) creates v3 fresh, v1-with-data → v3, v2 → v3, and v3 → "v2 build" → v3 (no duplicate ALTER), prepares every statement and asserts `EXPLAIN QUERY PLAN`: single-arm date pages `USING INDEX *_created` with no "TEMP B-TREE FOR ORDER BY"; book pages `*_book`; details by PK. **[Δ]** The `*_created` assertion covers **unfiltered** single-arm statements only: with a book filter the planner rightly picks `*_book` plus a small sort. The 메모 tab uses `quotes_memo`/`bookmarks_memo`; unfiltered counts use covering or partial indexes with no "TEMP B-TREE"; the downgrade orphan case is included (§4.12) |
| DATA **[Δ]** | `data/NotesKeysTest` (pure) | §5.3.7: in-memory date orders (t DESC, k, id DESC; OLDEST), counts with memo relabelling, the style filter, `BookSpans` from keys, all equal to the SQL results on the fixture |
| READER_A | `reader/ReaderJumpTest` (+) | `JumpAnchor.find`: whitespace-insensitive, −1 when absent, the first hit wins; **[Δ]** `NoteSig.of` (EPUB "e:size", TXT "sig:size"); `resolve` with an EPUB sig mismatch → `locate(frac)`; a legacy '' sig → coordinates |
| READER_A | `reader/DecorDiffTest` | a style change → not the same; start, end and kind as before |
| READER_A | `reader/PeekRuleTest` (pure `PeekRule`: `shouldSave(peek)`, `clearsPeek(event)`) | turns, TTS, auto-turn, remembered jumps and return use clear it; chrome toggles, dialogs and recreate do not |
| RENDER | `render/DashMathTest` | dashes aligned on the grid across two fragments; clipped to `[l, r)`; zero width → none |
| RENDER | `render/QuoteLookTest` | `update` bumps generation only when `ink()` flips; AUTO + null → colour; INK/COLOR fixed |
| EXTRAS_TOOLS | `reader/extras/LookupContextTest` | bounds at `. ! ? … 。` + closing quotes and `\n`; 150-char cut with "…"; U+FFFC removed; selection over two sentences; clamped start/end |
| EXTRAS_TOOLS | `reader/extras/QuoteExportTest` | no tag for one style; tags for ≥ 2; a single-quote share is never tagged |
| EXTRAS_TOOLS | `reader/extras/PaletteGeometryTest` | cell width `max(48dp, (W − 16dp)/6)` at 360 and 411 dp; ring inset |
| NOTES | `ui/notes/NotesTextTest` | `place`, `time` (today, yesterday, this year, other year), `dayHeader` (Korean weekday), `bookHeader`, "N회", file-name sanitising, `scopeLabel` ("모든 책 · 전체", "《제목》 · 인용문", "선택한 노트 12개", "· 검색: …") |
| NOTES | `ui/notes/NotesWindowTest` (pure `NotesWindow`: page cache + LRU + "visible page arrived?") | LRU of 6; placeholder for a missing page; a short page triggers a recount; a notify only when a visible row changed |
| LIBRARY | `ui/kit/InkTouchTest` (H0 + phase-0 cases) | §3.4, §4.12 |
| LIBRARY | `ui/library/LibraryGridMathTest` | columns and cells for 360 / 411 / 720 dp in both grid views; `PageFit` numbers of §10.2 (4 / 7 / 9 / 16 per Comet page) **[Δ]** (4 and 7 are targets only: LIST and COMPACT page by measurement, §10.3); **[Δ]** the last column's left edge < W − inset − 57 dp (§3.2 GridView caveat) |
| LIBRARY | `ui/library/LibraryTextTest` (+) | `metaLine`, `compactMeta` (★, 읽을 책, 다 읽음, 새 책), `lastRead` |
| SETTINGS | `ui/settings/SettingsFormatTest` (+) | "자동 (이 기기: 흑백 무늬)" and "자동 (이 기기: 쪽 단위)" summaries for true / false / null device class |
| W2 READER_A | `reader/ThumbGridMathTest`, `ThumbMapTest`, `ThumbKeyTest`, `ThumbBudgetTest` | library.md §3.8 |

---

## 17. CI screenshot checks (`tools/ci/screenshots.sh`; CONTRACT per UI_SPEC §8.2; `[screens]` runs)

The emulator is 720 × 1440 at density 320 (= 2.0). Shot numbers avoid the scroll SPEC's 60–68 and 70–73 and
UI_SPEC's 10b/13b–f/14b–c/51. Each `CHECK` logs PASS or FAIL to `steps.txt` and never fails the job. Steps 42+ run
after `40_library_after`, so earlier shots are unchanged.

| Shot | Steps | Must show / CHECK |
|---|---|---|
| `41_library_more` (H0) | after `01_library`: `tap_label "책 메뉴"`; `dump`; `find_node "문서 속성"` | CHECK 41: the book menu is open (the node exists) and the list did not move **[Δ]** (the first title's bounds, before vs after). **CHECK 41b**: a 6 px roll on ⋮ (`input swipe X Y X+2 Y+6 150`) → menu open and the bounds unchanged (§3.4). **CHECK 42b** (in step 42): a tap 2 px inside the 요약 ⋮'s right edge → menu open |
| `42_library_compact` | push 12 more samples (`adb shell cp /sdcard/Download/sample-utf8.txt /sdcard/Download/extra-NN.txt`, NN = 01..12); drawer → "도서 스캔"; overflow → "보기" → "요약" | 88 dp rows, ⋮ column, "새 책" meta |
| `43_library_thumbs` | "보기" → "썸네일" | 3 columns, covers 96 × 136, titles ≤ 2 lines |
| `44_library_grid` | "보기" → "그리드" | 4 columns, one-line titles; then "보기" → "전체" |
| `45_library_paged` | settings → "목록 넘기기" → "쪽 단위"; back to the library | pager bar "1 / 4"; exactly 4 whole cards **[Δ]** (→ no cut card other than the one shown whole at the top of page 2; the label total may be an estimate); no fast-scroll thumb. Then `input swipe 360 1000 360 600 300` → shot `46_library_page2`: "2 / 4". CHECK 46: the label reads "2 / 4". Restore "자동" afterwards. |
| `17_selection` (UI_SPEC's, updated) | as today | one row of 5; the 인용 cell shows a yellow dot with "▾" |
| `80_quote_saved` | in sample-utf8.txt: `input swipe 300 700 300 700 900`, `tap_label "인용"` | the word has a yellow fill; **no toast** |
| `81_palette` | select another word (`input swipe 300 820 300 820 900`); find "인용" and long-press it (`input swipe x y x y 900`) | the 6-cell palette (노랑 … 밑줄), 노랑 ringed; then `tap_label "초록"` → shot `81b_green`: a green fill |
| `82_quote_popup` | long-press on the first quote (300, 700) | the palette row above "복사 · 메모 · 인용 삭제 · 사전·번역 · ⋮", 노랑 ringed, no grey selection fill over the quote; `back` |
| `83_toc_quotes` | open the TOC → tab "인용문" | 2 rows with swatch columns; chips "[전체 2] [● 1] [● 1]"; the toolbar link "모든 책의 노트" |
| `84_lookup` | select a word → "사전·번역" → `back` (cancel) → select again → "사전·번역" → `tap_label "웹 검색"` → `back` (leave the browser) | logged only |
| `85_notes_hub` | back to the library → drawer (shot `85a_drawer`: rows "독서 노트 2", "단어장 1") → "독서 노트" | 전체 tab: day header "오늘 · …", 2 quote rows with swatches, 1 word row; pager or list per device class |
| `86_notes_quotes` | `tap_label "인용문"` | 2 rows; filter row with "모든 색 ▾" |
| `87_notes_jump` | tap the first row's text | the reader at the quote with the search-style mark; CHECK 87: `find_node "페이지로" contains` finds the chip |
| `88_notes_select` | `back` (to the hub); long-press a row → "선택" | "1개 선택" bar with 공유 · 내보내기 · 색 바꾸기 · 삭제; `back` |
| `89_notes_words` | `tap_label "단어"` | the word row with "다시 찾기", the sentence with the word bold, "웹 검색 · …" meta (or the 단어 empty state if no browser: logged) |
| `90_highlight_ink` | settings → "인용문 색 표시" → "흑백 무늬"; open sample-utf8.txt | quote 1 grey band + thin underline; quote 2 lighter band + dashed underline. Then restore "자동". |
| `92_thumbs` (W2) | reader ⋮ → "페이지 썸네일" | 4 × 3 grid (5 × 3 is fine on a 411 dp width), current page framed, labels = footer numbers, ribbons and ❝ marks |
| `93_thumbs_next` (W2) | `input swipe 600 900 100 900 300` | the next grid page; pager label advanced |

---

## 18. Risks

| # | Risk | Mitigation |
|---|---|---|
| 1 | **Index on an added column** breaks the upgrade and the library can't open | The rule plus `LibrarySchemaV3Test` plus `check_sql.py` over v1 → v3 and v2 → v3 files |
| 2 | **The open path grows** (jump, place, probe) | Jump = extras only; place and backfill after the first page; probe on IO after `afterOpen`; RAPerf gate +10 ms; review item "nothing between `startOpen` and `showPage` except `ReaderJump.from` and the `OWNER_JUMP` map put" |
| 3 | **Peek leaves a stale position** when the user reads on without turning (e.g. only TTS or scroll) | Every progress-making action clears peek (§6.2), tested in `PeekRuleTest` |
| 4 | **Stale note coordinates** after a TXT re-parse | `sig` → frac fallback; the anchor check re-finds the text; the toast is honest; notes are never rewritten automatically (v1) |
| 5 | **CursorWindow overflow** from huge quotes | Lists select `substr` only; `fullText` for one row; test asserts no bare column |
| 6 | **The fast-scroll guard depends on an internal 48 dp constant** | 56 dp margin; failure mode = today's behaviour; paged mode (e-ink default) has no fast scroller at all |
| 7 | **Paged library surprises the Comet user** | "목록 넘기기" → 스크롤 restores the old behaviour; the number pad replaces drag-to-seek |
| 8 | **Ink labels say colour names** on e-ink | Intentional (the stored value is a colour; the same quote is yellow on a phone); the settings note lists the mapping |
| 9 | **Scanner trash-instead-of-drop** is a visible behaviour change (books appear in 휴지통) | "(파일 없음)" label; automatic revive when the file returns; 영구 삭제 warns with the note count |
| 10 | **단어장 privacy** | Local only, a switch, "단어장 비우기", in backups only; nothing is sent anywhere |
| 11 | **Hot-file collisions** (`ReaderActivity`, `PageRenderer`, `SelectionController`, `ContentsDialog`, `LibraryActivity`, `MainPage`, `Backup*`) with the sibling specs | One agent per lane in a joint run (§15); every shared type lands in phase 0 |
| 12 | **`DeviceClass` unknown on first run** of an unlisted e-ink device | Colours and scroll paging until the probe (IO) finishes; the next draw or rebuild switches; the user's explicit setting always wins |
| 13 | **Thumbnails depend on the scroll SPEC's `drawBody`** | W2 is gated on it; if the scroll spec slips, W2 slips; nothing in W1 depends on it |
| 14 | **Export of a very large set** (10,000 notes, long quotes) | Streaming, one book in memory at a time, off main, ≤ 3 s budget |
| 15 | **Restore semantics changed** (position newer-wins) | Safer in every case; the old rule could move a newer position back; tested |
| 16 **[Δ]** | **Process death after a note jump** re-applies the jump from the launch intent | `jump_done` in the saved state (§6.1); Comet manual check with `am kill` |
| 17 **[Δ]** | **Stale TXT/EPUB coordinates** after an edited or replaced file with unchanged options | `NoteSig` includes the file size; the mark is drawn only after the anchor matches; stale quotes are not drawn (§6.5) |
| 18 **[Δ]** | **Downgrade to an R2 build** leaves orphan lookups and `missing_at` on restored books | Orphan sweep on every `oldVersion < 3` upgrade; `NoteBook.missing` requires `trashed = 1`; unknown book ids map to "(삭제된 책)" |
| 19 **[Δ]** | **Fast/binary refresh modes** erase fills (파랑 invisible, 빨강 = 밑줄) | Comet device check; `LINE_DOTTED` fallback for 파랑 (§8) |
| 20 **[Δ]** | **Share of a long list** throws `TransactionTooLargeException` | 50k-char cap (EXTRA_TEXT is copied into ClipData) |
| 21 **[Δ]** | **Restore floods 휴지통** with placeholders | Only unresolved backup books that carry notes get a placeholder row |

---

## 19. Korean strings (all new UI text)

| Place | Text |
|---|---|
| drawer, toolbar | 독서 노트 · 단어장 |
| tabs | 전체 · 인용문 · 메모 · 북마크 · 리뷰 · 단어 |
| orders | 최신순 · 오래된 순 · 책별 · 최근 읽은 책 먼저 · 책별 · 제목순 |
| chips | 모든 책 ▾ · 《제목》 ✕ · 모든 색 ▾ · 최신순 ▾ |
| search hint | 인용문·메모·단어·책 제목 검색 |
| page bar | {p} / {n} · {count}개 · 내보내는 중… · 불러오는 중… |
| row labels | 메모 · 뜻 · {n}회 · (휴지통) · (파일 없음) · 리뷰 · **[Δ]** (삭제된 책) · 위치 바뀜 (TOC quote meta) |
| content descriptions | 노트 메뉴 · 색 바꾸기 · 다시 찾기 · 책 메뉴 · 모든 책의 노트 |
| row menus | 책에서 보기 · 전체 보기 · 복사 · 공유 · 메모 편집 · 색 바꾸기 · 선택 · 삭제 · 리뷰 편집 · 책 열기 · 리뷰 지우기 · 다시 찾기 · 문맥 보기 · 웹 검색 · 뜻 메모 · 모든 기록 보기 |
| overflow | 정렬… · 책 선택… · 여러 개 선택 · 내보내기… · 공유 · 같은 단어 한 번만 · 찾아본 단어 기록 |
| selection | {n}개 선택 · 모두 선택 · 선택 해제 · 공유 · 내보내기 · 색 바꾸기 · 삭제 |
| dialogs | 인용문 삭제 / 이 인용문을 삭제할까요? · 북마크 삭제 / 이 북마크를 삭제할까요? · 리뷰 지우기 / ‘{제목}’의 리뷰를 지울까요? · 단어 기록 삭제 / ‘{단어}’ 기록을 삭제할까요? · 노트 삭제 / 선택한 {n}개를 삭제할까요? ( 리뷰는 책에서 지워집니다.) · 인용문 메모 · 북마크 메모 · 뜻 메모 · 리뷰 · 색 · 책 선택 · 내보내기 형식 |
| export chooser | Markdown (.md) · 메모 앱·옵시디언 · 텍스트 (.txt) |
| toasts | 삭제했습니다 · {n}개를 삭제했습니다 · 인용문 {n}개의 색을 바꿨습니다 · 노트 {n}개를 내보냈습니다 · 내보내지 못했습니다: … · 노트가 많아 앞부분만 공유합니다 · 책 파일을 찾을 수 없습니다 (노트는 남아 있습니다) · 노트 위치를 다시 찾았습니다 · 노트가 있던 곳을 찾지 못해 가까운 위치를 열었습니다 · 노트가 있던 곳을 찾지 못했습니다 · 복사했습니다 (API < 33) |
| selection popup | 인용 · 색 골라 인용… · (palette) 노랑 · 초록 · 파랑 · 빨강 · 보라 · 밑줄 |
| TOC | 색 바꾸기 · 전체 {n} · 인용문 {k} / {n} · 모든 책의 노트 · 썸네일 · 책을 여는 중입니다… |
| reader ⋮ | 독서 노트 · 페이지 썸네일 |
| library | 독서 노트 · 단어장 · 파일 없음 · 보기 · 전체 — 표지 · 정보 · 버튼 · 요약 — 작은 표지와 한 줄 정보 · 썸네일 — 표지 3열 · 그리드 — 작은 표지 4열 · 새 책 · 쪽 번호 |
| delete warning | 이 책의 인용문·메모·북마크·리뷰·단어 {n}개도 함께 지워집니다. 먼저 독서 노트에서 내보낼 수 있습니다. · [독서 노트] |
| settings | 목록 넘기기 · 자동 (이 기기: 쪽 단위) · 자동 (이 기기: 스크롤) · 쪽 단위 · 스크롤 · 서재와 독서 노트를 한 화면씩 넘깁니다 (e-ink 권장) · 인용문 색 표시 · 자동 (이 기기: 흑백 무늬) · 자동 (이 기기: 색) · 색 · 흑백 무늬 · (§11 note) · 단어장 · 찾아본 단어 기록 · 선택한 글자를 사전·번역·웹 검색으로 찾으면 단어장에 문장과 함께 남깁니다. 기기 안에만 저장됩니다 · 단어장 열기 · 단어장 비우기 · 찾아본 단어 {n}개를 모두 지울까요? · 비우기 |
| backup page | 다른 기기에서 만든 백업을 복원하면 두 기기의 인용문·메모·북마크·단어장이 합쳐지고, 읽던 위치는 더 최근 것이 남습니다. 지워지는 것은 없습니다. |
| empty states | §9.6 |
| export headers | hub.md §10: 독서 노트 · 내보낸 날짜 · 범위 · 모든 책 · 선택한 노트 {n}개 · 검색 · 책 {n}권 · 인용문 · 메모 · 북마크 · 리뷰 · 단어 · 작가 미상 · (휴지통) · (파일 없음) |

---

## 20. Questions from the reports, resolved

| Question (source) | Decision |
|---|---|
| Fold into v2 or bump to v3 (hub.md §21.1) | **v3**, one bump shared with UI_SPEC's `return_mark`; R2 (v2) will have shipped |
| Owner of `ui/notes` (hub.md §21.2) | New **NOTES** lane (new files only) |
| Write a re-found anchor back into the note (hub.md §21.3) | **No** in v1 |
| Scanner trashes instead of dropping (hub.md §21.4) | **Yes**, with `missing_at`, "(파일 없음)" and automatic revive |
| Fast scroller: guard or remove (hub.md §14 vs library.md §1) | **Guard now (H0)**; paged mode has none; scroll mode keeps the guarded one |
| Where the probe lives (highlights `EinkScreen` vs scroll `DeviceClass`) | **`DeviceClass`** (scroll SPEC); `EinkScreen` is not built |
| Style labels (hub.md §3.6 "0 기본(회색)" vs highlights) | **highlights.md**: 0 노랑 … 5 밑줄 |
| `addQuote` parameter order (hub vs highlights) | `(…, note, style, place)` |
| Drawer row name (library.md "인용문 · 메모" vs hub "독서 노트") | **독서 노트** + **단어장**, after 휴지통 |
| 썸네일/그리드 mapping (hub.md §15 vs library.md §2) | **library.md**: GRID = 썸네일 (3 columns with titles), COVERS = 그리드 (4 columns) |
| Page thumbnails: defer (hub) or build (library) | **Build in W2**, gated on the scroll SPEC's `drawBody`; zero open or turn cost |
| `PageRenderer(decodeImages)` (library.md §3.3) | **Dropped**: `drawBody` never decodes |
| The auto-backup fingerprint misses recolours (highlights.md §4) | Moot: the scroll SPEC hashes the whole snapshot JSON |
| Setting name `libraryPaging` (library.md) | **`listPaging`**: it also pages the hub and its choosers |
| Colour on notes-hub rows vs the ⋮ column | The right column holds the swatch (top) and ⋮ (below) |

---

## 21. [Δ] Changelog (adversarial review, 2026-09-30)

The review was read-only against the working tree of 2026-09-30. SQL was measured with
`scratchpad/notes/critic/sqlcheck.py` (SQLite 3.45; 2k books, 10k quotes, 3k bookmarks, 3k lookups). Severity: **B**
= breaks (wrong behaviour, crash, data loss); **P** = budget or e-ink cost; **H** = hardening.

| # | Sev | Where | Finding → change |
|---|---|---|---|
| 1 | B | §3.2–3.3 H0 | The tree's 요약 ⋮ sits flush right (row right padding 0). The 12 dp strip still covers its right quarter, so the bug stays there. → H0 moves it 12 dp in. Invariant: no clickable pixel at x ≥ W − 12 dp. CHECK 42b. |
| 2 | B | §3.2 | The GridView uses INSIDE_OVERLAY with 8 dp padding, so the platform zone is W − 56 dp and the 56 dp guard has 0 slack. The shifted x also chooses the grid **column**. → The grid goes to OUTSIDE_OVERLAY, `shieldedX(…, insetEndPx)` takes the inset, and a test asserts the last-column invariant. |
| 3 | B | §3.2 code | `LAYOUT_DIRECTION_RTL` is unresolved inside an extension function (compile error). → `View.LAYOUT_DIRECTION_RTL`. |
| 4 | H | §3.4, §17 | `input tap` sends no MOVE, so CI never reproduced the *jumping*. → CHECK 41b (a 6 px roll on ⋮); "the list did not move" compares title bounds. |
| 5 | P | §3.3 | A ⋮ tap costs 3 e-ink updates (pressed, released, menu). → W1: `CardButton` has no pressed state on e-ink. |
| 6 | B | §5.3.1 | The M, R and L arms have no aliases, so the single-arm BOOKMARKS/REVIEWS/WORDS statements fail ("no such column: t", reproduced). → Every arm aliases every column. |
| 7 | P | §5.3.5 | The UNION ALL … GROUP BY counts took 3–4 ms on x86 (≈ 25–30 ms on the A53, budget 15 ms). The QM arm walks quote overflow pages. → Scalar COUNT(*) counts (0.29 ms) plus partial indexes `quotes_memo` / `bookmarks_memo` (v1 columns, rule-safe). |
| 8 | P | §5.3.7 (new) | One search = 3–4 LIKE scans of the quote text, 25–30 ms each on x86. → One key scan per query, kept in memory (`NotesKeys`); counts, style counts, sorts and spans come from it. |
| 9 | P | §5.3.3 | Book-order pages over one-note books ran up to 50 statements. → One `SELECT * FROM (…) ORDER BY CASE b …` statement. A bare compound ORDER BY expression is rejected, checked. |
| 10 | P | §5.3.2, §5.8 | Deep multi-arm OFFSET pages took 7.5 ms on x86 (≈ 50 ms on the A53). → Page budget ≤ 80 ms beyond p 20; `books` ≤ 50 ms; the seeder uses realistic quote lengths. |
| 11 | B | §4.1 | Downgrade: an R2 build deletes books without their lookups, and the hub then counts rows it can't list. → `UPGRADE_SWEEP` on every `oldVersion < 3` upgrade (re-upgrades are v2); "(삭제된 책)" fallback; `missing` requires `trashed`. |
| 12 | B | §5.2, §5.5 | `plan()` skips all trashed entries and `SELECT_MOVE_CANDIDATES` has `trashed = 0`, so missing books could never be revived or re-pointed as specified. `SET_MISSING` could also make a user-trashed book revivable. → Plan rules, a candidate filter, `AND trashed = 0`, and a lazy notes-ids query. |
| 13 | B | §5.6 | Restore: `RESTORE_FLAGS` overwrote a newer review and wrote trashed/read flags/**encoding** unconditionally; an encoding change moves every TXT quote. Notes of books not on this device were dropped silently. Different TXT options duplicated quotes. → Newer-wins for review and flags, favourite OR, a same-text quote match, placeholder rows for unresolved books that have notes. |
| 14 | B | §4.9, §6.1 | `textSignature` ignores the file itself. An edited or replaced TXT (same options) or any EPUB trusted stale offsets, and the first frame could mark the wrong words. → `NoteSig` = sig + size; mark only after `JumpAnchor.matches`; `end` clamped; the frac path skips the offset check. |
| 15 | B | §6.1 | `setIntent(strip)` does not survive process death: the system recreates from the launch intent and re-jumps to the note (peek again). → `jump_done` in the saved instance state. |
| 16 | P | §6.4 | The anchor search over "all sections" could load ≈ 1,565 sections of the 14.8 MB TXT after open. → Capped at 48 sections or 3 M chars, cancellable per section. |
| 17 | P | §6.5 | A 500-row place backfill in one main message is 5–10 ms right after the first page. → Chunks of 64 per post. Stale-sig quotes are no longer drawn at wrong offsets (TOC "위치 바뀜"). |
| 18 | P | §7.1 | 인용 = 2 e-ink updates (clear, then the IO repaint). → Optimistic highlight in the same message as `clear()`. |
| 19 | B | §7.2 | A clickable swatch column inside InkPager rows breaks InkPager's drag-to-page (its documented contract): smooth scrolling on e-ink, or a wrong-direction page. → A position-based tap on the row, with no clickable child. |
| 20 | H | §4.12, §8 | "Contrast ≥ 12 : 1" read as covering ink greys fails on 0xBB (10.9 : 1). 빨강 is 12.05. → Colour fills only, computed in Double. Fast/binary refresh modes may erase 파랑 and merge 빨강/밑줄 → device check and a `LINE_DOTTED` fallback. |
| 21 | B | §5.7, §9.10 | Export: 옵시디언/GFM syntax left live (`~ = $ % &`, setext `=`, indented code blocks from TXT indents); only bodies were escaped; file names could keep controls, dots or a split surrogate; the `text/plain` fallback yields `.md.txt`; `"wt"` is rejected by some providers; the export was tied to the Activity scope and lost on process death. → All fixed as specified. |
| 22 | B | §5.7 | The share cap of 200k chars is ≈ 800 KB per transaction (EXTRA_TEXT is copied into ClipData, UTF-16): `TransactionTooLargeException`. → 50k, also for `shareAllQuotes`. |
| 23 | B | §9.4 | `LongHashSet` exists neither on the platform nor in the repo (no AndroidX). → `HashSet<Long>`; a large selection is saved as its query. |
| 24 | P | §9.7 | Numpad jumps drew placeholders, then real rows (2 updates, shifting rows). → Fetch, then `setSelection` (≤ 250 ms). |
| 25 | B | §10.3 | The fixed-row paged 전체/요약 views: card heights vary with 1–3 title lines (≈ 146–200 dp > rowH 149), so a cut card was skipped by the next page. → Measured paging for LIST/COMPACT; fixed rows only for exact-height grid cells; CI 45 reworded. |
| 26 | P | §10.2, §10.4 | Building `compactMeta` for every row in `reload()` adds work on the cold-start path, and a 4th view must not build a 2nd GridView. → `compactMeta` stays lazy (tree's memo); one GridView. |
| 27 | B | §12 | The thumbnail key/renderer ignored REPAINT-only changes (day/night keeps `genId`, `gen.settings` is stale) → wrong-colour thumbs. A global `decorVersion` bumped per TTS sentence flushed the LRU. Thumb layouts evicted the reader's neighbours with no re-prefetch. → `paintVersion` + `QuoteLook.generation` in the key; per-section versions without transient owners; re-prefetch in `cancelThumbs`. |
| 28 | H | §5.1 | `notesGen` did not bump on file refresh or move (titles and paths shown and exported). → Added. |

**Checked and left as written.**
- **Cold start:** the drawer counts are lazy (0.11 ms); `DeviceClass.cached` reads the already loaded settings
  prefs; no new query or layout pass.
- **SQL portability:** L1's bare-column MAX (SQLite 3.7.11+), `substr`, partial indexes (3.8+) and row values are all
  within SQLite 3.18.
- **Upgrade:** `ALTER … REAL NOT NULL DEFAULT -1` is accepted; every v3 ALTER is O(1) (no CHECK or STRICT, so no
  table scan); the upgrade total is 7.3 ms on x86.
- **Per turn:** O(1) (a map removal and a boolean).
- **Colours:** the ink greys are exact levels; the colour-fill contrasts pass.
- **Thumbnail memory:** 97 KB each, an 8 MB LRU; covers use RGB_565 in a 12 MB LRU.
- **minSdk 26:** every platform API used is ≤ 26: `MotionEvent.obtain/setLocation`, `imageTintList`,
  `PopupWindow.elevation`, `LruCache`, `java.time`, `ACTION_CREATE_DOCUMENT`, `StyleSpan`.
