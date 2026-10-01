# Library views, page thumbnails and the ⋮ touch bug: design spec

Status: design only (read-only pass). Nothing in the repo was edited. The working tree was read on 2026-09-30 with R2
uncommitted.

Sources read:
- `ui/library/LibraryViews.kt`, `LibraryActivity.kt`, `CoverLoader.kt`, `LibraryText.kt`, `LibraryDialogs.kt`
- `ui/kit/Ui.kt` (`einkListView`, `iconButton`, `pressableBackground`) and `ui/kit/InkPager.kt`
- `reader/BookSession.kt`, `PageCounts.kt`, `LayoutKeys.kt` (`PageGeometry`), `ReaderActivity.kt` (`showPage`,
  `buildDecor`, `pageLabelOf`, `globalPageOf`, `goToPage`, `reloadAnnotations`), `PageView.kt` (tap slop)
- `render/PageRenderer.kt`, `Render.kt`, `Covers.kt`, `ImageCache.kt`
- `engine/Layout.kt`, `reader/extras/ContentsDialog.kt`, `ReaderPanels.kt` (host capabilities)
- `settings/ReaderSettings.kt` (`LibraryListMode`), `res/values/themes.xml`, `docs/R2_INTERFACES.md`,
  `docs/ARCHITECTURE.md`

Screens: ReadEra premium sheet (`ui_ref/premium/*`), ReadEra library "전체" (`readera/70cc4018`), the drawer
(`790ff839`) and the contents dialog (`041f3916`). Ours: `01_library.png`, `40_library_after.png`, `15_toc.png`.

Related specs this one relies on or overrides:
- `notes/highlights.md`: `EinkScreen.likely()` and quote styles. Thumbnails get the styles for free through the
  renderer.
- `ui/audit.md` #14 / §2.8: the card padding and hairlines are adopted. The fast-scroll theme tweak is **superseded**,
  see §1.5.
- The notes-hub spec (all quotes and bookmarks in one place) is written separately. §4 lists what this spec reserves
  for it.

---

## 0. Decisions in one screen

| Question | Decision |
|---|---|
| ⋮ bug, root cause | The library `ListView` has the **platform fast scroller always visible** (`LibraryActivity.kt:428-431`). Its touch zone is at least **48 dp wide** at the right edge, at the thumb's height. It **intercepts ACTION_DOWN before any child** and then drags with **no slop**, mapping the absolute finger Y onto the whole list. The ⋮ button spans x = 303–348 dp. 36 of its 45 dp, icon included, lie inside the zone (x ≥ 312 dp). When the thumb is level with a card, a tap on that card's ⋮ never reaches the button, and the slightest jitter jumps the list by several cards. |
| ⋮ bug, fix | (1) The platform fast scroller is **removed** from the library list and grid. (2) Card buttons become `CardButton`s: from ACTION_DOWN they keep the list from stealing the gesture until the finger has moved **20 dp** vertically (the tap slop `PageView` already uses), and they have no long-click toast. (3) On e-ink the library is **paged** (`PagedListView` / `PagedGridView` + `InkPagerBar`: a drag = one page, ◀ ▶, page keys, and the label opens the number pad) instead of free scroll. That replaces fast-scroll seeking. Pure helpers `TapSlop` and `PageDrag` are JVM-tested. |
| View modes | Four modes, as in ReadEra: **전체** (today's card), **요약** (88 dp row), **썸네일** (3-column covers with title), **그리드** (4-column small covers). Enum names stay (they are persisted): `LIST` = 전체, `COMPACT` = 요약, `GRID` = 썸네일, and a new `COVERS` = 그리드. This is a contract request. |
| Setting scope | **Global**, one pref as today (`a.libraryListMode`), backed up. Grouped shelves (작가, 시리즈, …) keep their group rows. The books inside a group use the global mode. |
| Covers | One **canonical cover bitmap** (96×136 dp, today's list size) for every mode. The ImageView scales it. Switching modes never regenerates covers, and every mode shares one memory cache. |
| Cost per row | Zero DB work per row. Every string is precomputed in `BookRow.of` on IO. Switching modes re-binds the rows already loaded, with no query. |
| Paging | `AppSettings.libraryPaging` (contract request): 자동 (default: paged when `EinkScreen.likely()`), 쪽 단위, 스크롤. In paged mode, rows are **fitted to the page**: whole cards only, no cut row. The Comet shows 4 전체 cards, 7 요약 rows, 9 썸네일 or 16 그리드 covers per page. |
| 페이지 썸네일, where | A 4th tab **"썸네일"** in the contents dialog (목차 · 북마크 · 인용문 · 썸네일), plus a reader overflow item "페이지 썸네일" that opens that tab. |
| 페이지 썸네일, what | A paged grid of mini pages. On the Comet it is 4×3 = 12 per grid page. The current page is framed 3 dp. Bookmark ribbon, quote and note marks are shown, and highlights are drawn by the renderer (quote styles, search). Night mode is inverted like the page. A tap goes to that page (`PageJumpHost.goToPage`, remembered for 돌아가기). The label under each thumbnail is the **footer's own number** (`counts.globalPage`). |
| 페이지 썸네일, how | Lazy and off the main thread. A section is laid out only when a visible grid page needs it (`session.layout`, the existing LRU). One background "reader-thumbs" thread draws with its own `PageRenderer` into RGB_565 bitmaps at canvas scale. The bitmaps go into an LRU of **8 MB** on the Comet (about 80 thumbnails). Images are peek-only (no decode). Nothing runs before the tab opens. |
| 페이지 썸네일, e-ink | **One redraw per grid page.** The grid keeps showing until the next grid page is complete, for up to 700 ms (warm: about 60–90 ms). The next grid page is pre-rendered in the background. Phones fill cells progressively. |
| Costs (A53) | Thumbnail render ≈ 4–7 ms. A grid page of 12 ≈ 50–90 ms. A cold section adds 150–300 ms of layout, usually 0–1 per grid page. Memory is about 97 KB per thumbnail. The open path is unchanged. |
| Premium triage | Sync: **no** (privacy rule). Background TTS: T1-11. **Notes hub: must** (separate spec). Dictionary history: separate spec. Quote colours: `notes/highlights.md`. User fonts: already exists. |

---

## 1. The ⋮ bug (b)

### 1.1 What the user sees

"In the book list, when I try to press the ⋮ at the far right, it's taken as a scroll and the list keeps going up or
down." A tap on ⋮ either does nothing or jumps the list by several cards. It happens on some cards and not others, and
the affected card changes as the list moves.

### 1.2 Root cause (code + AOSP behaviour)

**Our configuration.**
- The library list, `LibraryActivity.kt:425-434`:
  ```kotlin
  listView = einkListView().apply {
      isFastScrollEnabled = true
      isFastScrollAlwaysVisible = true   // "Always shown: the auto-hiding fast scroller fades in/out …"
      scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
      itemsCanFocus = true
  }
  ```
- The grid is the same, at `:453-454`. There is no left/right padding, and the Material fast-scroll thumb is drawn at
  the right edge. It is the black 8 × 48 dp bar and grey track visible in `01_library.png` at x = 352–360 dp.

**Geometry of the card** on the Comet (360 dp wide), from `LibraryViews.kt:150-203`:
- root padding is 8 dp;
- the card has left 8 / right 4 dp padding;
- the cover is 96 dp, and the text column has 12 dp padding.

The actions row therefore runs from x = 124 to 348 dp, which is 224 dp. Its 5 weight-1 buttons are 44.8 × 48 dp each.
**⋮ occupies x = 303–348 dp, with its icon centred at 325.6 dp**, in the bottom 48 dp of the card.

**Platform fast scroller** (`android.widget.FastScroller`, API 21+):
1. **Touch zone.** `isPointInsideX` widens the thumb to the minimum touch target
   (`fast_scroller_minimum_touch_target`, 48 dp). With the thumb at the right edge, the zone is **x ≥ 360 − 48 = 312
   dp**. Vertically it is the thumb's 48 dp band. The zone moves with the thumb as the list moves. The theme cannot
   narrow it: the minimum comes from an internal dimen, not from the thumb drawable.
2. **It wins before any child.** `AbsListView.onInterceptTouchEvent` asks `mFastScroll.onInterceptTouchEvent(ev)`
   first. On ACTION_DOWN inside the zone it returns **true at once** when the list is not in a scrolling container.
   `isInScrollingContainer()` is false here: every ancestor is a FrameLayout or LinearLayout, and those return false
   from `shouldDelayChildPressedState()`. So the ⋮ ImageButton never receives the DOWN, gets no pressed state and does
   no click.
3. **It drags with no slop.** From then on, every ACTION_MOVE while dragging calls `scrollTo(getPosFromMotionEvent(y))`.
   The AOSP source even says "TODO: Ignore jitter". The mapping is **absolute**: the list jumps so that the thumb
   centre sits under the finger. A finger 20 dp off the thumb centre, over a thumb range of about 560 dp, moves a
   200-book list by about 200 × 20 / 560 ≈ **7 cards** on the first jitter pixel.
4. An UP with no MOVE only ends the "drag", so the menu does not open. The user presses again, a little longer, the
   fingertip rolls, and the list jumps. That is the reported "스크롤이 클릭돼서 화면이 내려가거나 올라가".

**Overlap.** 36 of the 45 dp of the ⋮ button lie in the zone whenever the thumb's band crosses that card's action row.
With about 4 cards per screen and 157 dp per card, this happens on one card most of the time. The collection button
(x 258–303 dp) is outside the zone, which is why only "the far right ⋮" is affected. In the grid, the same zone covers
the right third of the right column's covers.

**Secondary contributors** (fixed at the same time):
- `Ui.kt:212` `iconButton` sets `setOnLongClickListener { toast(description) }`. A slow e-ink press of ≥ 500 ms on ⋮
  outside the zone shows a "더보기" toast instead of the menu.
- Everywhere else on the card, the list's own drag slop is the platform `touchSlop` (8 dp). A rolling fingertip on any
  card button beyond 8 dp starts a scroll and cancels the click. `PageView` already widened its tap slop to
  `max(2 × touchSlop, 20 dp)` for e-ink (hotfix ac2885f). The library never got that.

**Why only now:** InkPager, from T1-1, states that "rows must not hold clickable children". The library cards do hold
them, so InkPager was never applied to the library, and the always-visible fast scroller stayed as its e-ink "seek"
tool.

### 1.3 The fix (LIBRARY; no frozen file)

**Step 1: no platform fast scroller in the library.**
- The list uses `isFastScrollEnabled = false`. The same goes for the grid. `isFastScrollAlwaysVisible` is removed.
- Scroll mode (phones) keeps `isVerticalScrollBarEnabled = true`. That bar is static: `fadeScrollbars=false` in the
  theme, it is 4 dp wide and it is not interactive.
- Paged mode hides it, because the pager label shows the position.
- Seeking moves to the pager bar (step 3). On phones, fling, search, sort and shelves cover it.

**Step 2: `CardButton`, a tap-tolerant button.** It replaces `ctx.iconButton(...)` in the card's action row and in the
요약 row's ⋮ column.
```kotlin
/** Card action button: a press that starts on it stays a tap until the finger moves [TapSlop] away (e-ink). */
internal class CardButton(context: Context) : ImageButton(context) {
    private val slop = TapSlop.px(ViewConfiguration.get(context).scaledTouchSlop, resources.displayMetrics.density)
    private var downX = 0f
    private var downY = 0f
    private var guarding = false

    init {
        isLongClickable = false          // no "더보기" toast: a long press clicks on UP like a short one
        isFocusable = false
        background = pressableBackground()
        scaleType = ScaleType.CENTER
        imageTintList = ColorStateList.valueOf(Ink.BLACK)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; guarding = true
                parent?.requestDisallowInterceptTouchEvent(true)   // propagates up to the list / pager
            }
            MotionEvent.ACTION_MOVE -> if (guarding && TapSlop.releaseToList(e.x - downX, e.y - downY, slop)) {
                guarding = false
                parent?.requestDisallowInterceptTouchEvent(false) // next MOVE: the list intercepts, we get CANCEL
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> guarding = false
        }
        return super.onTouchEvent(e)
    }
}
```
- A mostly vertical drag that starts on a button still scrolls or pages the list once it passes 20 dp. The first
  scroll step is (20 − 8) dp.
- Horizontal wander is left to `View.onTouchEvent`. It drops the pressed state once the finger leaves the button's
  bounds by more than `touchSlop`.
- `requestDisallowInterceptTouchEvent` is reset by the framework on every DOWN, so nothing leaks between gestures.

**Step 3: paged library on e-ink.** Details in §2.6.
- `PagedListView` and `PagedGridView` override `onInterceptTouchEvent` / `onTouchEvent` with the pure `PageDrag`.
  Rows with buttons are fine: the view sees the DOWN in `onInterceptTouchEvent`, unlike InkPager's OnTouchListener.
- A drag beyond the slop becomes one page on UP.
- `InkPagerBar` (reused) sits under the list. The label opens `InkNumPad` "쪽 번호 (1–N)", which replaces fast-scroll
  seeking.

**Step 4: hit targets.** In 전체, the five action buttons stay 1/5 of the row × 48 dp (≈ 45 × 48 dp: fine). In 요약,
⋮ is a 48 dp wide column spanning the full row height (88 dp). The grids have no ⋮: a long press opens the book menu,
as the GRID mode does today.

### 1.4 Pure helpers (JVM-tested)

```kotlin
// ui/library/LibraryTouch.kt
internal object TapSlop {
    /** Same tolerance as the reader's taps (PageView.tapSlop): max(2 × touchSlop, 20 dp). */
    fun px(touchSlop: Int, density: Float): Float = maxOf(touchSlop * 2f, 20f * density)
    /** A press that began on a card button becomes the list's gesture: mostly vertical, beyond [slop]. */
    fun releaseToList(dx: Float, dy: Float, slop: Float): Boolean = abs(dy) > slop && abs(dy) >= abs(dx)
}

/** Drag → page decision of the paged library (and the thumbnail grid). */
internal class PageDrag(private val slop: Float) {
    private var downX = 0f
    private var downY = 0f
    var dragging = false
        private set
    fun down(x: Float, y: Float) { downX = x; downY = y; dragging = false }
    /** True once this gesture is a page drag (vertical, beyond the slop); stays true until [up]/[cancel]. */
    fun move(x: Float, y: Float): Boolean {
        if (!dragging && abs(y - downY) > slop && abs(y - downY) >= abs(x - downX)) dragging = true
        return dragging
    }
    /** +1 next page (finger went up), -1 previous, 0 not a page drag. */
    fun up(y: Float): Int { val d = if (!dragging) 0 else if (y < downY) 1 else -1; dragging = false; return d }
    fun cancel() { dragging = false }
}
```

Tests, in `test/.../ui/library/LibraryTouchTest`:
- `TapSlop.px(8·2, 2f)` = 40 px, and `px(40, 2f)` = 80.
- `releaseToList`: (0, 39) → false; (0, 41) → true; (50, 41) → false (horizontal wins); (-3, -41) → true.
- `PageDrag`: down, then move within the slop, then up → 0. down → move(0, −41) = true → up → +1. A downward drag →
  −1. `cancel` → the next `up` returns 0. Diagonal (60, 45) → never a page drag.
- A guard test, `LibraryUiConfig.FAST_SCROLL == false`, a const read by `buildUi`, so re-enabling it is a deliberate
  test change.

### 1.5 Rejected alternatives (and the audit item it overrides)

- **Slim theme thumb plus `paddingEnd 12dp`** (`ui/audit.md` #14 / §2.8). The drawable gets thinner, but the 48 dp
  minimum touch target does not: 12 dp of padding still leaves about 28 dp of the ⋮ column in the zone. **Superseded.**
  The rest of #14 (10 dp padding, hairlines instead of a card border) is adopted in §2.3.
- **Wrapping the list in a parent whose `shouldDelayChildPressedState()` is true.** The scroller then uses a *pending*
  drag. That drag still starts after `TAP_TIMEOUT` (≈ 100 ms) on any MOVE inside the zone, and slow e-ink presses
  exceed that.
- **Shifting the event's x in `onInterceptTouchEvent`** so FastScroller sees it outside the zone. It works, but it is
  a hack on internals with no public contract. With no fast scroller, the need is gone.
- **Only `requestDisallowInterceptTouchEvent` in ⋮.** It does nothing: FastScroller has already intercepted the DOWN
  before the button sees anything (§1.2-2).

### 1.6 Manual check (device, both before and after)

1. Scroll the library until the scrollbar thumb is level with a card's ⋮, then tap ⋮. Before: nothing happens, or the
   list jumps on a slightly longer press. After: the menu opens.
2. Press ⋮ for 1 s. Before: a "더보기" toast. After: the menu opens.
3. Start a vertical swipe on ⋮. After: it scrolls on a phone, and turns one page on e-ink, once past 20 dp.
4. Tap the right edge of a cover in the grid's right column: the book opens.

---

## 2. Library view modes (a)

### 2.1 ReadEra → ours

ReadEra premium: "전체, 요약, 썸네일, 그리드 등 라이브러리 안에 있는 책과 문서를 취향에 맞게 커스터마이징하여 표시". Its free
"전체" is the card in `70cc4018`: a first-page cover, title, format and size, a progress line and 5 actions.

| ReadEra | Ours (label) | Enum (persisted name) | Exists today |
|---|---|---|---|
| 전체 | 전체 | `LIST` (label "목록" → "전체") | yes: the card |
| 요약 | 요약 | `COMPACT` (label "간단히" → "요약") | enum only. T1-13's row is not in the tree yet. If it lands first, align it with §2.4. |
| 썸네일 | 썸네일 | `GRID` (label "표지" → "썸네일") | yes: 3-column grid (resized, §2.5) |
| 그리드 | 그리드 | **`COVERS`** (new, label "그리드") | no |

The enum names must stay: `a.libraryListMode` stores the name, and unknown names fall back to `LIST`. A backup with
`COVERS` restored on an older build therefore shows 전체. The labels are a frozen-file change (§5). The KDoc must say
plainly that GRID is 썸네일 and COVERS is 그리드.

### 2.2 Shared rules

- **One cover size.** `CoverLoader.bind(ctx, view, book, CANON_W, CANON_H)` with `CANON_W = dp(96) − 2` and
  `CANON_H = dp(136) − 2`: the size the list cards use now. The ImageView is sized per mode and scales with
  `CENTER_CROP` (downscaling only; 썸네일 shows it 1:1).
  - Result: the memory key and disk file are identical in all modes.
  - Mode switches cost no regeneration. Today, list and grid ask for different sizes, so each generates its own set.
  - RGB_565 at 190 × 270 px is about 103 KB per cover. The 12 MB LRU holds about 116 covers, enough for 7 pages of the
    densest mode.
- **Strings on IO.** Extend `BookRow` (built in `reload()` on `Dispatchers.IO`) with:
  - `metaLine` ("TXT, 3.4MB · 3일 전", or "TXT, 3.4MB · 시리즈명 3" when in a series);
  - `compactMeta` ("작가 · TXT 3.4MB · 다 읽음");
  - `isNew` (never opened).

  Pure functions in `LibraryText`: `lastRead(now, lastReadAt)`, `seriesLabel(series, index)` and `compactMeta(...)`.
  No per-row DB call: author, series, size, progress and flags all come from the one `Library.books` query. The
  collection icon keeps its single set query.
- **Mode switch without a query.** `chooseMode` sets the mode, calls `showBooks(currentRows, keepFirstVisible)` and
  saves the pref. The rows are already in `bookAdapter.rows` / `gridAdapter.rows`. It does **not** call
  `reload(scrollTop = true)` as today (`LibraryActivity.kt:1246-1255`). The first visible book stays first: set
  `setSelection(firstBookIndex)` on the new view.
- **Adapters.**
  - 전체 and 요약 use `listView` with separate adapters (`BookListAdapter`, `CompactAdapter`). `setAdapter` flushes
    recycled views, so holders never mix.
  - 썸네일 and 그리드 use `gridView` with one `BookGridAdapter(kind)`. Holder reuse checks `holder.kind == kind`.
    `numColumns` and the cell size are set per mode from `LibraryGridMath`.
- **Book menu.** The flag items (즐겨찾기 / 읽을 책 / 다 읽은 책) appear when `listMode != LIST`. Today they appear only
  for GRID (`LibraryDialogs.kt:82`).
- **"새 책" (R2 LIBRARY checklist)** for a never-opened book:
  - 전체: it replaces the empty percent;
  - 요약: it ends the meta line;
  - 썸네일 and 그리드: it replaces the progress line as 10 sp grey text.
- **Selection state (T1-13 multi-select).** A 2 dp black frame around the cover plus an `ic_check_box` 20 dp at the
  cover's top-left, in every mode. No grey fill (it dithers on e-ink).
- **Chooser.** Overflow → "보기: 전체" → a single-choice dialog, each option with a one-line description:
  - "전체 — 표지 · 정보 · 버튼"
  - "요약 — 작은 표지와 한 줄 정보"
  - "썸네일 — 표지 3열"
  - "그리드 — 작은 표지 4열"

  The optional T1-13 toolbar toggle cycles in enum order (LIST → COMPACT → GRID → COVERS), as R2_INTERFACES.md
  §2 says. Icons: 전체 `ic_view_agenda` (new), 요약 `ic_view_list`, 썸네일 `ic_grid_view`, 그리드 `ic_apps` (new).
  See §5 for the two new drawables. Until they exist, reuse `ic_article` / `ic_grid_view`.

### 2.3 전체 (LIST): the card, tuned

The audit's #14 layout is adopted, with sizes chosen so that 4 cards fill a Comet page exactly.

```
┌ 10 ┬──────96──────┬12┬──────────── weight 1 ─────────────┬ 2 ┐   row min 149 dp (paged: fitted, §2.6)
│    │   cover      │  │ 제목 18sp medium, ≤3 lines, ×1.1   │   │   top/bottom padding 6 dp (fitted up to +slack/2)
│    │   96×136     │  │ 작가 14sp grey, 1 line (GONE if "")│   │
│    │  1px border  │  │ TXT, 3.4MB · 3일 전  14sp grey     │   │
│    │              │  │ (spacer, weight)                   │   │
│    │              │  │ ●━━━━━━━━○────────●   34%  (13sp)  │   │   ProgressLineView 14 dp tall; % min 40 dp
│    │              │  │ [★][⏲][✓✓][▥][⋮]  5 × CardButton  │   │   48 dp tall, 1/5 width each
└────┴──────────────┴──┴────────────────────────────────────┴───┘
─────────────────────────────── 1 px Ink.LINE, inset 8 dp ───────
```

- No card border: the audit says a double border next to the cover's border is noise. The cover keeps its 1 px border
  (the "paper" edge of the TXT mini page).
- Pressed state is `Ink.PRESSED` on the row.
- The progress fill is 2 dp with a 3.5 dp dot, per the audit's P2.

### 2.4 요약 (COMPACT)

```
┌12┬──48──┬12┬───────────── weight 1 ──────────────┬──48──┐   row min 80 dp (fixed 88 in scroll mode)
│  │cover │  │ 제목 16sp bold, ≤2 lines            │      │   padding 10 dp top/bottom
│  │48×68 │  │ ★ 작가 · TXT 3.4MB · 다 읽음 13sp g │  ⋮   │   ⋮ = CardButton, 48 dp × full row height
│  │      │  │ ●━━━━━○───────────●  34%  12sp      │      │   ProgressLineView 10 dp; % min 36 dp
└──┴──────┴──┴─────────────────────────────────────┴──────┘
────────────── 1 px Ink.LINE, inset 12 dp ─────────────────
```

- Tap on the row opens the book. A long press or ⋮ opens the book menu, which holds the flags (§2.2).
- The flags are shown as text in `compactMeta`, precomputed:
  - a leading "★ " when the book is a favourite;
  - a trailing "· 읽을 책" or "· 다 읽음";
  - "· 새 책" for a never-opened book.
- The cover is the canonical bitmap drawn at 48 × 68 dp (exactly 0.5×).

### 2.5 썸네일 (GRID) and 그리드 (COVERS)

| | 썸네일 (GRID) | 그리드 (COVERS) |
|---|---|---|
| Column rule | `cols = max(2, (W − 16 + 6) / (110 + 6))` (dp): **3** at 360, 3 at 411, 6 at 720 | `cols = max(3, (W − 16 + 6) / (80 + 6))`: **4** at 360, 4 at 411, 8 at 720 |
| Cell (360 dp) | 110.7 × 184 dp | 81.5 × 138 dp |
| Cover | **96 × 136** (canonical 1:1), centred | 76 × 108 (0.79×) |
| Under the cover | 3 dp gap, progress line 6 dp (no %) or "새 책", 3 dp, title 12 sp bold ≤ 2 lines centred (≈ 30 dp), 2 dp | 3 dp gap, progress line 4 dp or "새 책" 10 sp, 2 dp, title 11 sp 1 line centred (≈ 15 dp), 2 dp |
| Spacing / padding | h 6, v 6, list padding 8 | h 6, v 6, padding 8 |
| Per Comet page (596 dp list height) | 3 rows × 3 = **9** | 4 rows × 4 = **16** |
| Tap / long press | open / book menu | open / book menu |

The TXT mini-page cover already shows the title line, so the 그리드 caption is one line only. Pure maths lives in
`LibraryGridMath` (`columns(mode, widthDp)`, `cellSize(...)`, `coverSize(...)`), unit-tested with the numbers above.

### 2.6 Paging (e-ink) and scrolling (phones)

**Setting (§5).** `AppSettings.libraryPaging` = `LIB_PAGING_AUTO` 0 (default) / `LIB_PAGING_PAGED` 1 /
`LIB_PAGING_SCROLL` 2. Auto resolves to paged when `EinkScreen.likely()`, the brand-string check from
`notes/highlights.md`: microseconds, no reflection. SettingsPage row: "서재 넘기기 · 자동 (이 기기: 쪽 단위)".

**Views.** `PagedListView : ListView` and `PagedGridView : GridView` (LIBRARY, `ui/library/LibraryPaging.kt`) have a
`paged` flag and a `PageDrag`.

When `paged` is true:
- `onInterceptTouchEvent`:
  - DOWN → `drag.down`, return false (the rows get it);
  - MOVE → `return drag.move(x, y)` (true = take it; the row gets CANCEL);
  - anything else → false.
- `onTouchEvent` consumes everything:
  - UP → `drag.up(y)` → `pager.page(d)`;
  - CANCEL → `drag.cancel()`.
- `super` is never called, so there is no scroll, no fling and no overscroll frames.

When `paged` is false, both methods delegate to `super`.

**Pager** (`LibraryPager(view: AbsListView, bar: InkPagerBar)`):
- `page(dir)` = `setSelection(first ± step)` with `step = fullyVisibleRows × cols`: the partial row, if any, leads the
  next page. It reuses `PagerMath.total` / `page` / `label` for "3 / 27".
- The bar is placed under the content frame. The status strip still overlays the list bottom above it.
- The bar label opens `InkNumPad("쪽", 1..total)` → `setSelection((n − 1) × step)`.
- Page keys: `dispatchKeyEvent` → `scrollPage(dir)` → `pager.page(dir)` when paged; `scrollListBy` otherwise.

**Fit rows to the page** (paged only).
- On the list's `onSizeChanged`: `LibraryPageMath.fit(listH, minRowH) → (rows, rowH)` with
  `rows = listH / minRowH` and `rowH = listH / rows`.
- Holders take `rowHeight` as `minimumHeight`; the extra is split into top and bottom padding.
- If `rows` changes, call `notifyDataSetChanged()` once. Only rotation or window size changes trigger this.
- Grids fit the same way: `cellH = (listH − 2 × pad + vSpacing) / rows − vSpacing`, with the cover size unchanged.

Comet numbers, list height ≈ 596 dp (640 − 44 bar):

| Mode | minRowH | Rows per page |
|---|---|---|
| 전체 | 149 | 4 |
| 요약 | 80 | 7 (rowH 85) |
| 썸네일 | 184 + 6 | 3 → 9 books |
| 그리드 | 138 + 6 | 4 → 16 books |

Every page shows whole cards only, so no row is half-drawn at the bottom, and a page is one layout and one draw.

**Cover prefetch** (paged, e-ink). After a page shows, `CoverLoader.prefetch(ctx, rows[last+1 .. last+step], W, H)`
decodes the next page's covers into the memory LRU with no views. It uses the same 2 LIFO threads at background
priority, and disk hits cost about 5–10 ms each on the A53. The next page then binds every cover from memory: **one
e-ink update per page** instead of one plus one per late cover. New API on `CoverLoader` (LIBRARY): `fun prefetch(context,
books: List<Book>, w, h)`. It skips keys that are cached, failed or pending.

**Scroll mode (phones).** Free scroll with a static thin scrollbar, fling, and no fast scroller. Rows have fixed heights
(전체 149, 요약 88). Hardware keys scroll by a screen, as today.

### 2.7 Performance checklist

- Library cold start: unchanged. `PagedListView` is created instead of `ListView`, and `EinkScreen.likely()` is a string
  check. No new query or file read.
- Per-row bind: a few `setText` calls, an `if`-guarded `setImageResource` (the existing `shownIcons` cache) and a
  `CoverLoader.bind` memory hit. No allocation beyond the platform's.
- Per page (paged): one `setSelection`, one layout, one draw. Covers are prefetched.
- Per mode switch: 0 DB queries, 0 cover generations, one layout.

---

## 3. 페이지 썸네일 (c)

### 3.1 UX

**Entry.**
- The contents dialog gets a 4th tab: **목차 · 북마크 · 인용문 · 썸네일**. Each tab is 90 dp wide on 360 dp.
- A reader overflow item, **"페이지 썸네일"** (`ic_grid_view`), opens the dialog on that tab.
- The tab is disabled ("책을 여는 중입니다…") until a page is shown.
- In the opt-in scroll reading mode (scroll spec) the tab is hidden for v1: there are no pages to show.

**Grid** (a custom `ThumbGridView`: one View that draws every cell, no adapter).
```
┌────────────────────────────────────────────┐  toolbar 56 · tabs 45 · hairline
│ ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐      │  padding 12, gaps 8
│ │ mini │  │ mini │  │▛▀▀▀▀▜│  │ mini◣│      │  thumb 78×156 dp on the Comet (page aspect)
│ │ page │  │ page │  │▌cur ▐│  │ page │      │  current page: 3 dp black frame
│ │      │  │ ▒▒▒  │  │▙▄▄▄▄▟│  │      │      │  ◣ bookmark ribbon (8×12 dp, top-right)
│ └──────┘  └──────┘  └──────┘  └──────┘      │  ▒ quote fills (renderer), ❝ / ✎ marks bottom-left
│    9        10      **11**      12          │  page label 12 sp (bold for the current page), 16 dp
│  … 3 rows …                                  │
├────────────────────────────────────────────┤
│ ◀ 이전          1 / 272 · 11쪽        다음 ▶ │  InkPagerBar (label tap → InkNumPad "쪽 번호 1–3259")
└────────────────────────────────────────────┘
```

- **Cell label** = `counts.globalPage(section, idx)`, the same number the footer shows. It is an estimate while
  counting runs, exactly as the footer is, and has no "~".
- **Tap** a thumbnail → `(host as PageJumpHost).goToPage(section, idx, remember = true)` + dismiss.
- **Paging**: swipe (vertical or horizontal, `PageDrag` extended to either axis) → next/previous grid page; also ◀ ▶,
  page keys (`dialog.inkPagerKeys`) and the number pad (→ the grid page containing page N).
- **Opening** the tab shows the grid page that contains the current page.
- **Marks** are drawn by `ThumbGridView` at cell scale:
  - bookmark ribbon at the top-right;
  - "❝" when the page has a quote;
  - "✎" when a quote on it has a note;
  - a search-hit dot when the last search has hits there (v1.1).

  The renderer draws the quote, search and TTS fills inside the bitmap. At about 0.22 scale they read as grey (or
  coloured) bands. Night mode inverts the bitmaps (the renderer uses `settings.invert`), as ReadEra's "주-야간 모드가
  적용되며" describes.

### 3.2 Grid geometry (`ThumbGridMath`, pure)

```kotlin
internal object ThumbGridMath {
    const val PAD_DP = 12f; const val GAP_DP = 8f; const val LABEL_DP = 16f; const val MIN_THUMB_DP = 56f
    /** Smallest column count in 3..8 that gives ≥ 3 rows with thumbs ≥ 56 dp wide; else the densest that fits. */
    fun layout(widthDp: Float, heightDp: Float, aspect: Float /* viewH / viewW */): Grid  // cols, rows, thumbW, thumbH (dp)
    fun perPage(g: Grid): Int = g.cols * g.rows
    fun gridPageOf(page: Int, perPage: Int): Int = ((page - 1).coerceAtLeast(0)) / perPage
    fun firstOf(gridPage: Int, perPage: Int): Int = gridPage * perPage + 1
    fun gridPages(total: Int, perPage: Int): Int = ((total + perPage - 1) / perPage).coerceAtLeast(1)
}
```

| Screen | Body (dp) | Aspect | Result |
|---|---|---|---|
| Comet | 360 × 574 (720 − 56 − 45 − 1 − 44) | 2.0 | 3 columns give 106.7 × 213 → 2 rows ✗. **4 columns: 78 × 156 → 3 rows = 12.** |
| Phone | 411 × 655 | 2.17 | 3 and 4 columns → 2 rows. **5 columns: 69 × 150 → 3 rows = 15.** |
| Landscape tablet | 800 × 500 | 0.625 | 3 columns → 3 rows of 253 × 158 — yes, 9 per page. |

Tests cover each row of this table, `gridPageOf` / `firstOf` round trips, total = 0 or 1, and a clamp when N >
total.

### 3.3 Pipeline

**Owners.**
- READER_A: new `reader/PageThumbs.kt`, which uses only BookSession's public API, and the `PageThumbsHost` impl in
  ReaderActivity.
- RENDER: one `PageRenderer` parameter.
- EXTRAS_NAV: the tab and `ThumbGridView`.

```
ThumbsTab (main)                ReaderActivity / PageThumbs (main)                  "reader-thumbs" thread (BACKGROUND prio)
 select tab / page ───────────▶ requestThumbs(first, n, wPx, hPx, progressive, cb)
                                 cancel previous job
                                 1. resolve p → counts.locate(p) = (sec, idx) for p in [first, first+n) ∩ [1, total]
                                 2. secs not in session.peek → session.layout(sec)   (reader-layout thread, as today;
                                    stores in the LRU → counts.set → exact pages)      ≤ 3 rounds: re-resolve after each
                                 3. final (sec, idx.coerceIn(0, pageCount-1)), label = counts.globalPage(sec, idx)
                                 4. per cell: key → LRU hit? else decor = decorFor(sec, layout, idx) (quotes, owner
                                    highlights, marks; bookmarked = false, no header/footer) ──────────────▶ render:
                                                                                     bmp = createBitmap(w, h, RGB_565)
                                                                                     canvas.scale(w / viewW)
                                                                                     thumbRenderer.draw(canvas, layout, idx,
                                                                                       contentLeft, contentTop, viewW, viewH, decor)
                                 5. put in LRU ◀───────────────────────────────────── (isActive checked between cells)
 cb(ThumbBatch) ◀──────────────  e-ink: once, when complete (or 700 ms → partial, then complete)
                                 phone: every ≥ 100 ms with the cells done so far
                                 6. then prefetch the next grid page (same path, no callback; cancelled by any request)
```

- **Layouts.** `session.layout(sec)` is the existing foreground path. It needs no new BookSession API.
  - It stores in the reader's LRU (`MAX_CACHED = 4`; the current section is protected), so a tap into that section is
    instant.
  - It evicts the reader's prefetched neighbours, which costs nothing in practice: `showPage` prefetches `section ± 1`
    again on every page shown.
  - Rejected: a separate 2-entry thumb cache in BookSession. It adds complexity to READER_B's LRU and only saves one
    background layout, in the rare case of closing the dialog while on a section's last page.
- **Estimated counts.** `locate` works on estimates for uncounted sections. After laying out the sections a grid page
  touches, their counts are exact and the mapping is resolved again, at most 3 rounds (in practice 1). Earlier
  uncounted sections can still shift the numbering by an offset. That is the same offset the footer shows, which is
  the requirement.
- **Renderer on the thumb thread.** `PageRenderer` is single-threaded. `PageThumbs` keeps one per generation on its
  thread: `PageRenderer(ctx, AndroidTextMeasurer(ctx, gen.settings) { session.images.size(it) }, session.images,
  decodeImages = false)`.
  - `SectionLayout` / `SectionContent` are immutable after the layout, so reading them from two threads is safe.
  - `ImageCache.peek` is an `LruCache.get` and thread-safe.
- **RENDER change**: `PageRenderer(..., decodeImages: Boolean = true)`. When false:
  - image lines use `images?.peek(src, w, h)` and fall back to the existing outline box;
  - `prefetchNeighbours` is skipped.

  Illustrated EPUB pages the reader has already shown keep their pictures, and no decode runs for thumbnails (v1.1:
  a small-size decode path).
- **Bitmaps.**
  - RGB_565: no alpha, half the memory, and 16 grey levels on e-ink are fine.
  - A pool of 2 recycled bitmaps per size is used only for LRU evictions that are not on screen. Simplest correct v1:
    no pool, and let GC collect them.
- **LRU.** `LruCache<ThumbKey, Bitmap>`, sized by `allocationByteCount`.
  - Budget = `min(8 MB, memoryClass MB × 1 MB / 32)` on e-ink, `min(16 MB, …)` on phones.
  - `ThumbKey(genId, section, pageIndex, wPx, hPx, decorVersion)`.
  - Cleared on a generation change (settings, rotation, TXT re-parse), on `onTrimMemory ≥ RUNNING_LOW`, and on session
    close.
- **`decorVersion`** (ReaderActivity) is bumped when `reloadAnnotations` delivers, on `setHighlights` and on a
  bookmark toggle. Stale thumbnails re-render lazily when next shown, with no redraw by themselves.
- **Threading.**
  - One daemon thread is created on the first request, not at open.
  - `LatestTaskRunner`-style: a new request supersedes queued work.
  - The coroutine job is cancelled on a new request, on dismiss, and on session close.
  - Results for a generation that is no longer current are dropped.
- **Marks.** `decorFor` (READER_A) reuses `addOverlapping` and `isBookmarked`. Notes come from
  `QuoteCache.get(book.id)`, rows with a non-empty `note`. Marks are bit flags on `ThumbCell`:
  `MARK_BOOKMARK 1, MARK_QUOTE 2, MARK_NOTE 4, MARK_SEARCH 8`.

### 3.4 One e-ink update per grid page

- **Opening the tab**, or the dialog on the tab: request the current grid page first. Switch the body (and the tab
  underline) when the batch is complete, or after at most 700 ms. This is the same pattern as `EpisodeWait` for the TOC
  header. Warm is about 60–90 ms, so the tab switch and the thumbnails are **one** update.
- **Paging**: the old grid stays until the new batch is complete (≤ 700 ms), then one `invalidate()`. A cold section
  over 700 ms shows numbered placeholders (frame + page number), then fills in: 2 updates, rare.
- **Prefetch**: after a batch completes, the next grid page in the direction of travel is rendered into the LRU, so
  most page flips are warm, with one update and no wait.
- **No idle redraws**: counting progress does not redraw the grid. The pager total and labels refresh on the next grid
  page change.
- **Phones** (`progressive = true`): placeholders show at once and cells fill as they finish, throttled to ≥ 100 ms
  between invalidates.

### 3.5 Host capability (frozen `ReaderPanels.kt` signatures → contract request, §5)

```kotlin
/**
 * Optional ReaderHost capability (페이지 썸네일), checked with `host as? PageThumbsHost`. Implemented by ReaderActivity
 * (READER_A) with reader/PageThumbs; used by ContentsDialog's 썸네일 tab (EXTRAS_NAV). Jumps use PageJumpHost.goToPage.
 * Main thread only; nothing is created before the first request.
 */
interface PageThumbsHost {
    /** Pages as the footer counts them (estimated until totalPagesKnown()); 0 while no page is shown. */
    fun thumbTotal(): Int
    /** Global 1-based page on screen (the footer's number). */
    fun thumbCurrent(): Int
    /** Page view height / width (thumbnail shape); 0 when unknown. */
    fun thumbAspect(): Float
    /**
     * Renders global pages [first, first + count) at [widthPx]×[heightPx] off the main thread (laying out sections as
     * needed) and reports on the main thread: once when complete, or — with [progressive] — as cells finish (≥ 100 ms
     * apart). Without [progressive] a batch not complete after 700 ms is reported partially, then again complete.
     * A newer request (or [cancelThumbs]) supersedes this one: its callback is not called again.
     */
    fun requestThumbs(first: Int, count: Int, widthPx: Int, heightPx: Int, progressive: Boolean, onBatch: (ThumbBatch) -> Unit)
    fun cancelThumbs()
}

class ThumbCell(val page: Int, val section: Int, val pageIndex: Int, val bitmap: Bitmap?, val marks: Int) {
    companion object { const val MARK_BOOKMARK = 1; const val MARK_QUOTE = 2; const val MARK_NOTE = 4; const val MARK_SEARCH = 8 }
}
/** [total]/[current] as of this batch (the tab updates its pager from them); [complete] = every cell has a bitmap or failed. */
class ThumbBatch(val first: Int, val cells: List<ThumbCell>, val total: Int, val current: Int, val complete: Boolean)
```

**ContentsDialog (EXTRAS_NAV):**
- Tabs become 4. `arrayOfNulls(3)` → `(4)`, and `tab.coerceIn(0, 3)`.
- `pagers` becomes `arrayOfNulls<PageTarget>(4)`. `PageTarget` is a new one-method interface in `ui/kit/InkPager.kt`
  (`fun page(dir: Int): Boolean`), implemented by `InkPager` and `ThumbsTab`, so `inkPagerKeys` works for every tab.
- The "모두 공유" icon stays tab-2-only.
- New file `reader/extras/ThumbsTab.kt`, with the `ThumbGridView` drawing and `PageDrag` taps.
- `ReaderPanels.showContents(host, tab = 3)` is the entry used by the overflow item. It is an existing signature with
  a new value; READER_A adds the menu item.

### 3.6 Cost estimates

**Comet** (Helio-P35-class A53 at ~2 GHz, 720 × 1440 px, density 2):

| Step | Estimate | Basis |
|---|---|---|
| Section layout (TXT ~30k chars, 18 sp) | 150–300 ms | Scroll specs' measured-range estimate, same typesetter. Runs on the reader-layout thread. 0 when the section is the current one or cached. |
| Pages per TXT section | ~50–70 | 30k chars / ≈ 500 chars per page. A grid page of 12 usually needs **1** section, sometimes 2. |
| Thumbnail render (1 page, 156 × 312 px) | 4–7 ms | `eraseColor` on 97 KB ≈ 0.1 ms. ~22 lines × ~8 segments ≈ 180 `drawTextRun` calls at ~20–30 µs. Highlights ≈ 0. Images: peek or box. |
| First-use glyph rasterisation at the thumb size | +15–30 ms, once per session | ~1–2k distinct Hangul glyphs at ~15 µs |
| Grid page of 12, warm (section cached) | **≈ 50–90 ms** → one e-ink update | |
| Grid page of 12, cold (1 new section) | ≈ 200–400 ms | still under the 700 ms single-update budget |
| Memory per thumbnail | 156 × 312 × 2 B = **97 KB** | LRU 8 MB ≈ 82 thumbs ≈ 7 grid pages; one grid page ≈ 1.2 MB |
| Open path / page turn | **+0 / +0** | nothing constructed before the first request; no per-turn work (`decorVersion` bumps only on annotation changes) |

**Phone** (Snapdragon 7xx, 1080 × 2400 px, density 2.625):
- A thumbnail is 69 dp ≈ 181 × 393 px ≈ 142 KB.
- Render ≈ 1–2 ms each, so ≈ 20–30 ms per grid page of 15.
- Layout 25–60 ms per section.
- LRU 16 MB ≈ 110 thumbs.

**EPUB.**
- Spine items are split at about 40k chars (`EpubSplit.PART_TARGET_CHARS`), so the cost per section is similar.
- Illustrated pages draw pictures already decoded at reading size (peek), otherwise a 1 px box.
- Chapters with very short sections (one per chapter) can put 3–6 sections on one grid page, cold: 3–6 layouts of
  small sections, each ≈ 20–60 ms. Warm after the first visit.

### 3.7 Edge cases

- **Counting incomplete.** Totals and labels are estimates exactly as in the footer. When counting completes while the
  tab is open, nothing redraws; the next page flip picks the new numbers up. The numpad's hint uses the total at the
  time it opens.
- **1-page book / empty section.** One cell. An error section shows its error page, like the reader.
- **Rotation / settings change** while the dialog is up: `ReaderPanels.dismissAll` already closes panels on a
  relayout; if it does not, the tab re-requests on `generation` change.
- **TTS speaking.** TTS highlights are owner highlights and appear in the thumbnail of the spoken page. Harmless.
- **Very long books** (3259 pages / 12 = 272 grid pages): the numpad jumps directly. Memory stays bounded by the LRU.
- **Search hits (v1.1).** `SearchPanel` keeps its last results in memory. `decorFor` can mark pages whose section
  offsets fall in them (binary search per rendered page).

### 3.8 Tests (JVM)

- `reader/ThumbGridMathTest`: the geometry table, round trips, and clamps.
- `reader/ThumbMapTest`: with a real `PageCounts` (pure):
  - complete counts: for every p, `globalPage(locate(p)) == p`;
  - partial counts, after `set(sec, pages, chars)`: re-resolution converges in ≤ 3 rounds, and all cells of a grid
    page map to distinct (sec, idx) pairs;
  - `idx.coerceIn` when an estimate over-reaches.
- `reader/ThumbKeyTest`: equality and hash; the key changes with `decorVersion`, `genId` and size.
- `reader/ThumbBudgetTest`: `budget(memoryClassMb, eink)` gives 8 / 16 MB caps and the 1/32 rule.
- `ui/library/LibraryTouchTest` (§1.4) also covers `PageDrag` for the grid, with the horizontal-axis variant.

---

## 4. The rest of the premium sheet (triage)

| Premium item | Decision | Where |
|---|---|---|
| Synchronization (Google Drive) | **No.** House rule: no network, no accounts. Covered by the local auto-backup (scroll/SPEC.md) and the Wi-Fi transfer page. | — |
| Background play | Being built | T1-11 (`TtsService`) |
| **섹션: 인용문 (quotes, notes, bookmarks, reviews in one place)** | **Must: the user's priority.** | Notes-hub spec. This spec reserves a drawer row for it, "인용문 · 메모" (`ic_format_quote`, placed after 즐겨찾기). Thumbnails share `QuoteCache` / marks, so there is nothing to coordinate beyond that. |
| Section: Dictionary (looked-up words) | Yes, local only (PROCESS_TEXT lookups logged) | Separate spec (dictionary history) |
| 인용문 색 표시 | Yes | `notes/highlights.md` |
| 라이브러리 뷰 | Yes | §2 |
| 페이지 썸네일 | Yes | §3 |
| 내 글자체 | Exists | FontsPage + `/sdcard/Fonts` |

---

## 5. CONTRACT REQUESTS (frozen files: lead edits them)

```kotlin
// settings/ReaderSettings.kt — labels (names are persisted: unchanged) + one mode appended
/** Library view (ReadEra's 전체 / 요약 / 썸네일 / 그리드). Names are stored: GRID is 썸네일, COVERS is 그리드. */
enum class LibraryListMode(val label: String) { LIST("전체"), COMPACT("요약"), GRID("썸네일"), COVERS("그리드") }

// settings/ReaderSettings.kt — AppSettings
/** Library list movement: [LIB_PAGING_AUTO] (paged on e-ink screens), [LIB_PAGING_PAGED], [LIB_PAGING_SCROLL]. */
val libraryPaging: Int = LIB_PAGING_AUTO,
// top level
const val LIB_PAGING_AUTO = 0
const val LIB_PAGING_PAGED = 1
const val LIB_PAGING_SCROLL = 2

// settings/Settings.kt — key "a.libraryPaging" (int), missing/out of range → LIB_PAGING_AUTO.
// data/SettingsJson.kt — appToJson/appFromJson map "a.libraryPaging" clamped 0..2 (+ a SettingsJsonR2Test-style case).

// reader/extras/ReaderPanels.kt — frozen signatures: PageThumbsHost, ThumbCell, ThumbBatch exactly as in §3.5.

// ui/kit/Ui.kt — KDoc of einkListView only: drop "like the library" (the library no longer uses the fast scroller;
// a list whose rows hold buttons must not enable it — see LibraryActivity's FAST_SCROLL note).
```

- `res/drawable`: `ic_view_agenda.xml` and `ic_apps.xml` (Material Symbols Outlined, Apache 2.0, same source and style
  as the existing icons). They are optional: reuse `ic_article` / `ic_grid_view` until they land.
- **No `LibrarySchema` change and no DB bump.**
- `EinkScreen.likely()` comes from `notes/highlights.md` §2.4 (RENDER). If that spec is not taken, add the same brand
  check under the same name.

---

## 6. Owners and order

| # | Owner | Work | Depends on |
|---|---|---|---|
| 1 | LIBRARY | **⋮ fix now, independent of everything else**: `FAST_SCROLL = false` for list and grid, `CardButton` in the action row, `TapSlop` + tests. Ships alone as a hotfix. | — |
| 2 | lead | §5 contract (enum labels + COVERS, `libraryPaging`, `PageThumbsHost`) | — |
| 3 | RENDER | `PageRenderer(decodeImages)`; `EinkScreen.likely()` (shared with highlights) | — |
| 4 | LIBRARY | Modes §2.2–2.5 (`BookRow` strings, `CompactAdapter`, grid kinds, canonical covers, no-query switch, chooser text); paging §2.6 (`PagedListView` / `PagedGridView`, `LibraryPager`, fit rows, `CoverLoader.prefetch`, numpad); menu flags for non-LIST | 1, 2, 3 |
| 5 | READER_A | `reader/PageThumbs.kt`, `PageThumbsHost` on ReaderActivity (`decorFor`, `decorVersion`, trim and close hooks), overflow "페이지 썸네일" | 2, 3 |
| 6 | EXTRAS_NAV | ContentsDialog 4th tab, `ThumbsTab` + `ThumbGridView`, `PageTarget` in InkPager | 2, 5 |
| 7 | SETTINGS | "서재 넘기기" row (자동 (이 기기: …) / 쪽 단위 / 스크롤); MainPage mode row picks up the 4 labels | 2 |

Step 1 is small (≈ 40 lines) and removes the reported bug on both the Comet and phones. It should not wait for the
view work.

---

## 7. Risks

- **Phones lose drag-to-seek** in very large libraries. Mitigations: fling, search, sort and shelves. A user can also
  choose 쪽 단위 on a phone to get the pager's numpad jump. If users ask, a thin custom seek rail can live in a
  **dedicated 16 dp gutter** outside the cards, and never over them again.
- **The 20 dp release slop** makes swipes that start on a button feel 12 dp "stickier". This matches the reader's tap
  slop, so the behaviour is consistent.
- **Fit-rows depends on the list height.** With a keyboard or search row open the height shrinks, which refits rows
  and costs one `notifyDataSetChanged`. It only happens on size changes.
- **Thumbnail text at about 0.22 scale** is shape, not reading. That is intended for navigation, as in ReadEra. A
  "크게" toggle (3 columns × 2 rows) is a cheap v1.1 if the user wants it legible.
- **Thumbnail layouts can evict the reader's prefetched neighbours.** The cost is at most one background layout, and
  the next page shown re-prefetches (§3.3).
- **Estimate shifts while counting.** Labels can change between two visits to the same grid page. The footer shows the
  same shift, which is the requirement.
