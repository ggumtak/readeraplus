# ReaderaPlus: screenshot polish audit (build 12 compared with ReadEra)

Status: audit only. Nothing in the repo was edited.
Inputs:
- Every image in `ui_ref/ours/*.png` (18 screens, emulator 720×1440 px at 2.0 density, so dp = px / 2).
- Every image in `ui_ref/readera/*.jpg` (16 photos, 1080 px wide at about 2.625 density, so dp = px × 1.16 / 2.625 at the
  displayed size).
- The code at HEAD `b3dbc72`. Another workflow is editing the tree right now, so line numbers are **HEAD** line numbers
  and every reference also names the symbol, so it can still be found after edits. Where the working tree already
  differs, this is marked "(WT: …)".
- `scroll/design-A-tall-section.md` and `scroll/design-B-stitched-pages.md` (the scroll SPEC.md was not written yet).
  Both designs draw the header and footer bands **fixed** and scroll only the content box. Section 5 keeps this audit
  consistent with them.

Paths are relative to `app/src/main/java/com/ggumtak/readeraplus/`. "Frozen" marks a contract file (`ui/kit/Ui.kt`,
`settings/*`, `res/values/*`, `AndroidManifest.xml`). A change there goes through the contract step.

---

## 0. Top 15 (ranked)

| # | P | What looks cheap or broken | Where | Fix (short) |
|---|---|---|---|---|
| 1 | P0 | The page label "3 / 167" floats left of centre in the bottom bar (centre x = 107 dp on a 360 dp screen). It is also bold 700, and the WT underlines it like a web link. | `reader/ReaderChrome.kt:154-175` (`pageLabel`, `lp(0,WRAP,1f)`) | Make the row a `FrameLayout` 52 dp tall. Centre the label on the **full width** (`Gravity.CENTER`), 18 sp medium with tabular figures, no underline. Put a 2-icon cluster (rotation, pin) at the end. Move the bookmark off this bar. See A1. |
| 2 | P0 | The pin is "메뉴 고정" (keeps the bars open and shrinks the page), and it makes pages jump back and forth. It should pin the current page as a return point, shown as ReadEra's "‹ 10 페이지로  지우기" strip. | `ReaderChrome.kt:173,319-325`; `ReaderActivity.kt:623-628, 822-825, 1506-1577` (`pinnedArea`, `applyPinnedArea`, `togglePin`); `ReadingSettingsPopup.kt:92` | Delete pinned-chrome and all of its relayout paths. Add a per-book pin plus the strip in the bottom panel. See A4. |
| 3 | P0 | The brightness slider does nothing on the Comet. Only `WindowManager.LayoutParams.screenBrightness` is set, and the e-ink front light ignores it. | `reader/ReaderWindow.kt:63-79` (`applyBrightness`, `systemBrightness`) | Add a `BrightnessDriver` that writes `Settings.System.SCREEN_BRIGHTNESS` (WRITE_SETTINGS), keeps the window override on phones, and adds a permission row. See A3. |
| 4 | P0 | The chevron right of the brightness slider hides the brightness row. In ReadEra it opens an options panel ("스와이프로 밝기 조절" + switch). A second "밝기 보이기" icon then appears in the title row. | `ReaderChrome.kt:118-122, 142-145, 343-346`; `ReaderActivity.kt:402, 1885-1888` (`PREF_BRIGHTNESS_COLLAPSED`) | Use `ic_expand_more` / `ic_expand_less` to expand an options block under the slider. Remove `brightnessShow` and the collapse pref. See A5. |
| 5 | P0 | The footer is on every page and always on: "1 / 167 …… 0% · 2:39 · ▭ 100". It is two crowded clusters with double-spaced middots, and the user cannot choose left, centre or right content. | `render/PageRenderer.kt:179-228` (`drawStatus`), `:553` `FOOTER_SEP`; `ReaderActivity.kt:1233-1257` (`buildDecor`); `reader/ReaderFormat.kt:35-53`; `settings/ReaderSettings.kt:39-47` (frozen) | Use three bottom slots (left, centre, right), one item each, **default all 없음**. Add a ReadEra-style **progress line** (1 px + dots), on by default and needing no text band. The model is allocation-free. See A2. |
| 6 | P0 | A grey #CCCCCC square sits behind the brightness-auto icon, which looks like a stuck pressed button. The same happens to rotation-locked and pinned icons, and to the selected drawer row. | `ui/kit/Ui.kt:161-165` `pressableBackground()` adds `state_selected → Ink.PRESSED` (frozen); `ReaderChrome.kt:331,337`; `ui/library/LibraryActivity.kt:697` | Selection is shown by the glyph only (auto ↔ sun icon, outline ↔ filled). Use `pressableBackground(selectedFill = false)`, or a chrome-local pressed-only drawable. |
| 7 | P1 | The reading-settings popup sits flush with the screen top and is off-centre: 46 dp gap on the left, 4 dp on the right. A 46 dp sliver of cut page text shows beside it, and every 36 dp row is split by a full black line, so it reads like a spreadsheet. | `reader/extras/ReadingSettingsPopup.kt:126` (`showAtLocation(TOP or END, 4dp, place.top)`); `extras/ExtrasFormat.kt:337-368` (`PopupGeometry`); `extras/CompactUi.kt:56-73` | Use `TOP or CENTER_HORIZONTAL`, width `screenW − 16 dp` (max 400 dp), top = inset + 8 dp. Row lines become light (#AAAAAA) and inset 12 dp. Black lines stay only between groups and on the border. |
| 8 | P1 | Popup touch targets are too small: rows 36 dp, stepper buttons 36 dp, toggles 28 dp tall. House rule is ≥ 44 dp. | `CompactUi.kt:37-53` (`ROW_DP=36`, `STEP_DP=36`, toggle `minHeight=28`) | Rows 44 dp, steppers 44×44, toggles 36 dp visual inside a 44 dp row. Put 정렬 and 줄바꿈 on one row, so the main block is 9 × 44 = 396 dp (the 55 % cap). |
| 9 | P1 | Korean UI text breaks inside words: "…문서를 이 / 어서 봅니다" (settings), "테스트 문장 / 입니다" (search). This looks unfinished everywhere. | `Ui.kt:184-200` `label()` (frozen); `SearchPanel.kt:392`; settings summaries | Break Korean at word boundaries (keep-all). On API 33+ use `lineBreakWordStyle = LINE_BREAK_WORD_STYLE_PHRASE` with `textLocale = KOREAN` inside `label()`. Below API 33, and as the fallback if the device still breaks mid-word, a `keepAll()` helper inserts U+2060 between Hangul syllables of one word. **Verify on the Comet.** |
| 10 | P1 | The chrome title "sample-cp949" starts at 16 dp while the back-arrow glyph starts at 20 dp. Titles are `DEFAULT_BOLD` (700) everywhere: 18 sp chrome, 19 sp TOC, 20 sp toolbars. That is heavy and uneven. | `ReaderChrome.kt:115-116`; `Ui.kt:194, 245`; `extras/ContentsDialog.kt:72` | Align the title start to 20 dp. Use one title style: 20 sp medium (500) in toolbars, 18 sp medium for the chrome's book title. Add `bold = Weight.MEDIUM` to `label()`. |
| 11 | P1 | Page numbers are not tabular. The centred label and the right-aligned footer items shift sideways on every turn ("1" vs "8" width), which the e-ink shows as a flicker on the static parts. | `ReaderChrome.kt:160`; `PageRenderer.kt:46-54` (`statusPaint`) | Set `fontFeatureSettings = "tnum"` on the page label and on `statusPaint` once (no per-draw cost). |
| 12 | P1 | Settings: the off switch is an empty outlined pill with a hollow knob and reads as disabled. The switch's right edge sits 7 dp right of the chevron and ▾ glyphs, so there are three trailing keylines. Section separators are full-bleed black lines. | `ui/kit/Toggle.kt:55-70` (frozen kit); `Ui.kt:263-278` `row()` padding end 12 dp; `ui/settings/SettingsPage.kt:80-96` | Off state = outlined track with a **filled black knob**; on state = black track with a white knob. One 48 dp trailing slot, content centred, row padding end 4 dp, so all trailing glyph centres sit at x = W − 28 dp. Section break = 16 dp space, no line. |
| 13 | P1 | The selection popup is a ragged 5 + 4 grid (a hole bottom-right), with 12 sp labels in 62 dp cells ("여기서 읽기" is cramped). | `extras/SelectionController.kt:377-396` (`cols = 5`) | Use one row of 5 like ReadEra: 복사, 인용, 메모, 사전·번역, ⋮ 더보기. The rest (공유, 문단, 검색, 웹 검색, 여기서 읽기) go in `popupMenu`. Cells are `(W−16dp)/5` wide, 56 dp tall, labels 13 sp. |
| 14 | P1 | Library card: padding is uneven (8 dp top/left, 4 dp bottom/right). A double border (card + cover) plus an 8 dp fast-scroll track overlaps the card's right border. | `ui/library/LibraryViews.kt:150-160`; `LibraryActivity.kt:424-432`; `res/values/themes.xml` (frozen) | Padding 10 dp on all sides. Drop the card border in favour of a 1 px hairline between cards, inset 8 dp. Theme `fastScrollTrackDrawable` = 1 px line, thumb 4×40 dp black. List padding end 12 dp. |
| 15 | P1 | The "← 돌아가기 (p. N)" chip has a different visual language from the new pin strip: bold 16 sp, English "p.", a boxed chip bottom-left. | `ReaderActivity.kt:383-398, 2068-2117`; `ReaderFormat.kt:86` `returnChip` | Same component language as the strip: "‹ 3614 페이지로" 15 sp medium, a 44 dp "×", 1 px border, 0 radius. The wording is shared through `ReaderFormat.goBack(page)`. |

The P2 items are in section 3.

---

## 1. The user's six points, as specifications (all P0)

### A1. Bottom bar: page label centred, ReadEra structure

**Now.** The bottom bar is a black hairline, then a row [pageLabel (weight 1, centred inside the room left of the
icons) | rotation 48 | bookmark 48 | pin 48], then the seek bar. The label's centre is (360 − 4 − 144) / 2 + 4 ≈ 110 dp,
and the screenshot measures 107 dp. That is the "awkward, floating left-centre" look. WT adds
`UNDERLINE_TEXT_FLAG` (an underlined bold number reads as a hyperlink, which looks cheap) and a seek row
[⏮ | seek | ⏭].

**ReadEra (ee05e923, d905abfd, f110dcfa).** The label "10 중 3614" is centred on the screen, medium weight, about 17 sp.
Two icons sit on the right (rotation and pin). Below them is a full-width seek bar. There is no bookmark icon in the bar.

**Spec.**
```
bottom (vertical, white, clickable)
├─ hairline 1px black                                  (existing)
├─ pinStrip 40dp  (only while a pin exists, see A4)
├─ lightLine 1px #AAAAAA inset 16dp both sides          (only with the strip)
├─ labelRow: FrameLayout, height 52dp
│    ├─ pageLabel  WRAP×48dp, layout_gravity=CENTER, 18sp medium, "tnum",
│    │             padding 12dp h, pressable (→ 페이지 이동), NO underline,
│    │             maxWidth = W − 2·(clusterW + 8dp)   (Comet: 360 − 2·(100+8) = 144dp),
│    │             autosize 15–18sp (TextView uniform autosize: static, no animation)
│    └─ cluster   horizontal, layout_gravity=END|CENTER_VERTICAL, marginEnd 4dp:
│                 [rotation 48][pin 48]
└─ seekRow 44dp: [⏮ 44][SeekBar weight1, padding 12dp h][⏭ 44]      (WT's chapter buttons)
```
- **Bookmark icon:** remove it from the bar. It stays in the overflow ("북마크 추가/삭제"), on the top-right corner tap
  (`bookmarkByTouch`) and on the ribbon. With 3 icons the centred label would have only 56 dp of room.
- **Width check:** "12345 / 23259" at 18 sp medium, tabular, is about 125 dp, which fits 144 dp. The autosize floor
  of 15 sp covers the 1.3× font scale.
- **Performance:** the label text is set only when it changes (existing guard). Tabular figures keep its width constant,
  so a turn redraws only the glyph box.

### A2. Footer: three slots and a progress line (default: no footer text, progress line on)

**Now.** `showFooter` and `footerPage/ChapterLeft/Percent/Clock/Battery` (WT adds `footerEpisode` and `footerTimeLeft`)
are concatenated into two strings: `footerLeft` and `footerRight` + battery. `ReaderFormat.footerLeft` builds a
`StringBuilder` + `String` on **every turn** (WT), and `buildDecor` allocates a `PageDecor` plus an `ArrayList` per turn.
This breaks the "O(1), no allocation per turn" rule.

**ReadEra (4f482943, 7d420a01).** There is no footer text, only a hairline across the bottom, about 9 dp above the
edge. It has a small dot at each end and a position dot. It spans about 10 dp to W − 10 dp, which is wider than the text
column.

**Spec (settings, frozen `ReaderSettings` / `Settings` / `SettingsJson`):**
```kotlin
enum class StatusItem(val label: String) {      // stored by name; append only
    NONE("없음"), CHAPTER("챕터 제목"), BOOK("책 제목"),
    PAGE("쪽 (12 / 3259)"), PAGE_ONLY("쪽 (12)"), PERCENT("진행률 (34%)"),
    CHAPTER_LEFT("챕터 남은 쪽"), EPISODE("화 (123/540화)"), TIME_LEFT("남은 시간"),
    CLOCK("시계"), BATTERY("배터리"), CLOCK_BATTERY("시계 + 배터리")
}
// ReaderSettings:
val footerSlots: List<StatusItem> = listOf(NONE, NONE, NONE)   // left, centre, right
val progressLine: Boolean = true
// Optional, same model for the top (마루뷰어 look 7a7a0a23 puts everything in one top line):
val headerSlots: List<StatusItem> = listOf(NONE, CHAPTER, NONE) // = today's "상단 챕터 제목"
```
- **Migration** (`Settings.readReader`): if a slot key is absent, apply the new defaults. The user asked that "not
  chosen" means no footer. `showHeader=false` maps to header slots all NONE. The old flags stay readable for one release,
  then are dropped.
- **Layout band** (`reader/LayoutKeys.kt:46-70`, `geometry`): `footer = if (footerSlots.any { it != NONE }) band
  else 0`. The same applies to `header`. The band depends only on whether any slot is set, not on which items, so
  choosing a different item never re-paginates. **Progress line:** drawn centred in the bottom margin. It needs space
  only when `mb < 10 dp` (the "페이지 여백" switch off gives 4 dp). Then `footer += 10dp − mb`. Put
  `progressLine && !pageMargins` into `layoutPart` so page-count keys stay correct.
- **Drawing** (`PageRenderer.drawStatus`):
  - Left slot: start-aligned at `contentLeft`. Right slot: end-aligned at `contentLeft + cw`. Centre slot: centred on
    `contentLeft + cw/2`.
  - Priority when space runs out: fixed-width items (page, %, clock, battery, episode) never ellipsize. CHAPTER and BOOK
    ellipsize into what is left. The centre gets `cw − 2·max(leftW, rightW) − 2·gap`, with `gap = 1 em` of the status
    font. A side slot holding a title gets `cw − otherSideW − centreW − 2·gap`, placed from its edge.
  - No separators ("  ·  " is gone). CLOCK_BATTERY draws "14:05" + 6 dp + the battery icon + digits.
  - Baseline: the existing `centredBaseline(bandTop, viewH − lineBand)`.
- **Progress line:** y = `round(viewH − mbPx/2) + 0.5`. It spans x = 12 dp to W − 12 dp, like ReadEra's page-edge
  line. If it looks detached with 40 dp side margins, use the text column instead (one constant).
  - Line: 1 px `fg`, no anti-aliasing.
  - End caps: filled circles r = 1.5 dp.
  - Marker: filled circle r = 3 dp at `x0 + (x1−x0)·progress`, snapped to whole px.
  - No chapter ticks: 3,000 episodes would draw a solid bar.
  - Inverted mode: `fg` / `bg` as already done.
- **Allocation-free model**, replacing the per-turn Strings:
  ```kotlin
  class StatusLine {                      // one per PageView, reused
      val kind = arrayOfNulls<StatusItem>(3)
      val chars = Array(3) { CharArray(40) }; val len = IntArray(3)
      var title: String? = null           // CHAPTER / BOOK: existing String reference, no copy
      var progress = 0f; var battery = -1; var line = false
      var version = 0                      // bumped when anything changed → sameDecor is one int compare
  }
  ```
  Integers are written into `chars[i]` by a small `IntChars.put(buf, pos, n)`. It is drawn with
  `canvas.drawText(char[], 0, len, x, y, paint)` and `paint.measureText(char[], 0, len)`. Ellipsized titles are cached
  per (String identity, available width), the way `ellipsizedHeader` already does (`PageRenderer.kt:268-278`).
  `ReaderFormat.footerLeft/Right` remain for tests and labels only.
- **Refresh:** only on page turns and decor rebuilds, as today (the clock never ticks by itself). TIME_LEFT and EPISODE
  use the WT's cached values (`scheduleEpisodes`, per-chapter spans) and never scan per turn.
- **Settings UI:**
  - Reading-settings popup, "더보기 → 페이지": replace "하단 정보 표시" and its item switches with
    "하단 왼쪽 / 가운데 / 오른쪽". These are three `dropdownRow`s whose `CompactList` has radio entries and the value on the
    right, e.g. "시계".
  - Add a switch "진행 막대" ("하단에 읽은 위치를 가는 선으로").
  - Keep "상태 표시 글자 크기" (visible when any slot is set).
  - Mirror the same rows in 설정 → 페이지 넘김 및 페이지 표시.
- **Defaults and cost:** a fresh install shows only the progress line. The first page draws one extra line and three
  circles, with no text measuring.

### A3. Brightness that works on the Comet

**Now.** `ReaderWindow.applyBrightness` sets the per-window `screenBrightness` (`ReaderWindow.kt:63-72`). The device
research already warned that this may not drive an e-ink front light (`research_research_device.md:182`), and the user
confirms it does nothing. `systemBrightness()` assumes a 0..255 range.

**Spec.** `reader/BrightnessDriver.kt` (READER owner):
- Mode `WINDOW` (phones, and the fallback): keep today's behaviour.
- Mode `SYSTEM` (e-ink, i.e. `Eink.vendorName() != null`, or when the user turns it on): when
  `Settings.System.canWrite(ctx)`, set `SCREEN_BRIGHTNESS_MODE = MANUAL` once and write
  `SCREEN_BRIGHTNESS = round(v·255)`. Allow 0 (the front light off is valid on e-ink, unlike the 0.01 floor on LCD).
  - Write on a single-thread executor, throttled to ≤ 10/s while dragging, plus the final value on release.
  - Keep the window override at `BRIGHTNESS_OVERRIDE_NONE` so the two do not fight.
- When the permission is missing, the options panel (A5) shows the row "전면광 밝기 바꾸기 허용" / "시스템 밝기를
  바꾸려면 권한이 필요합니다". The row opens `Settings.ACTION_MANAGE_WRITE_SETTINGS` with `package:` uri. Also declare
  `<uses-permission android:name="android.permission.WRITE_SETTINGS"/>` (frozen manifest).
- Optional probe, logged once and **off the open path**: list `XrzEinkManager` methods whose names contain
  "Light" or "Bright". Show them in 정보 so the user can report the vendor API.
- **Timing:** nothing before the first page. The saved brightness is applied after the first page's draw.
  `PREF_LAST_BRIGHTNESS` stays as is.
- **Icon state:**
  - `brightness < 0` (system): `ic_brightness_auto`.
  - Manual: `ic_brightness_medium`.
  - No grey background (item 6).
  - Moving the slider switches the icon to manual at once. Today `saveApp` runs only on release, so the icon stays
    "auto" during the drag.

### A4. Pin = "return to this page", not "keep the menu open"

**Now.** `AppSettings.pinChrome`: `togglePin()` → `applyPinnedArea()` changes the page view's top and bottom margins
→ `onViewSizeChanged` → relayout. `onBarsResized` (a layout listener on both bars) calls `applyPinnedArea` again. The
title, page label and brightness row change bar heights after each `bindChrome`, so the relayout keeps landing on a new
page start. That is the "flipping like crazy". The settings popup also changes behaviour when pinned
(`ReadingSettingsPopup.kt:88-100`).

**ReadEra (ee05e923, d905abfd).** A band sits just above the bottom bar. It has grey "‹ 10 페이지로" at the left
(16 dp) and "지우기" starting near the centre. It is visible while the menu is shown.

**Spec.**
- **Remove:**
  - `AppSettings.pinChrome` (frozen; keep the key readable and ignored).
  - `pinShown`, `pinPending`, `pinnedArea()` and the pinned branch of `applyPinnedArea()`. The page margins become
    insets only.
  - The `onBarsResized → applyPinnedArea` path (the chip position update stays).
  - `ReadingSettingsPopup` `hideBars` always true.
  - The "pinned chrome" rows in both scroll designs.
- **Data:** a per-book `pin: DocPosition?` in `BookPrefs` (`pin.section`, `pin.offset`), loaded after the first page.
  It is saved when set or cleared and travels in backups.
- **Pin button** (`ic_push_pin` / `ic_push_pin_fill`): a tap sets the pin to the current page start, replacing any old
  pin. The icon becomes filled. There is no toast: the strip appearing is the feedback. Long-press toasts "현재 페이지
  고정" (the existing iconButton behaviour).
- **Strip** (inside `bottom`, above the label row, so the page never changes size):
  - Height 40 dp, white.
  - Two halves, each the full height and each a pressable touch target of at least 44 dp:
    - Left half: "‹ 10 페이지로", 15 sp medium, black, padding start 16 dp. Tapping it calls `jumpTo(pin, remember =
      true)`: the return chip then offers the way back.
    - Right half: "지우기", 15 sp, `Ink.GRAY`, text starting at 50 % (matching ReadEra). Tapping it clears the pin,
      hides the strip and outlines the icon.
  - When the current page is the pinned page, the left text is `Ink.DISABLED` and not clickable.
  - The page number is the global page label (estimate while counting, never "~", as in the existing rule).
- **Cost:** the strip is built once. Showing, hiding or changing its text only happens while the chrome is visible.
  Nothing is done per turn when the chrome is hidden.

### A5. The icon right of the brightness slider opens options

- Remove `brightnessShow` (`ReaderChrome.kt:118-122`), `setBrightnessCollapsed`, `onBrightnessCollapsed` and
  `PREF_BRIGHTNESS_COLLAPSED` (`ReaderActivity.kt:106, 402, 1885-1888`).
- The chevron is `ic_expand_more` (options closed) or `ic_expand_less` (open), 48 dp, content description "밝기 옵션".
- The options block is inside `top`, under the brightness row, `GONE` by default and closed again each time the chrome
  hides:
  1. `switchRow`-style: "스와이프로 밝기 조절" / "화면 왼쪽을 위아래로 밀어 밝기를 조절합니다". It binds
     `AppSettings.brightnessSwipe`.
  2. (A3) "전면광 밝기 바꾸기 허용", only when needed.
  3. Optional: "시스템 밝기 따르기" as a switch. It is the same as the auto icon, for users who do not discover that
     icon.
  - Rows are 56 dp, padding 16/8/12/8, title 16 sp, summary 13 sp `Ink.GRAY`. Two lines at most, with keep-all breaks
    (item 9).
  - A light 1 px line sits above the block. The black hairline stays at the very bottom of `top`.
- Expanding changes the top bar's height. With pinned-chrome gone (A4), that no longer re-lays out the page.

### A6. "Care much more about the UI": the house tokens that make fixes stick

Add these to `ui/kit/Ui.kt` (frozen) so every owner draws from one scale:

| Token | Value | Use |
|---|---|---|
| `Ink.LINE` | #000000 1 px | Panel edges, group separators, popup borders |
| `Ink.LINE_LIGHT` (new) | #AAAAAA 1 px (a solid e-ink grey level) | Row separators inside lists and popups, inset 12–16 dp |
| `Ink.PRESSED` | #CCCCCC | **Pressed only**, never "selected" |
| Type: title | 20 sp medium | Toolbars (library, settings, TOC, search) |
| Type: chrome title | 18 sp medium, max 2 lines | Reader top bar |
| Type: body row | 17 sp regular | Settings, TOC, drawer |
| Type: summary | 14 sp `Ink.GRAY` | Row summaries |
| Type: section | 14 sp bold, 24 dp top / 8 dp bottom padding | Settings sections (no line) |
| Type: compact | 15 sp label / 16 sp value | Reading-settings popup |
| Type: status | 11 sp (default) regular, `tnum` | Page header and footer |
| Keylines | 16 dp start; the trailing slot is centred at W − 28 dp | All rows |
| Touch | ≥ 44 dp (popup) / 48 dp (bars, rows) | Everything tappable |
| `label(weight=…)` | `Typeface.create(Typeface.SANS_SERIF, 500, false)` (API 28+), else `"sans-serif-medium"` | Replaces `DEFAULT_BOLD` for titles and labels. Hangul falls back to regular where the CJK font has no 500. That is still calmer than 700. |

---

## 2. Screen by screen

Measurements are from the screenshots (ours: px / 2 = dp).

### 2.1 Reader page (10, 11, 12, 20, 21, 22, 30–32)

| P | Issue | Code | Fix |
|---|---|---|---|
| P0 | Footer: always on, two clusters, "  ·  " separators, battery glued to the time. See A2. | `PageRenderer.kt:179-228`; `ReaderActivity.kt:1233-1257` | A2 |
| P1 | Digits are proportional: "1%" and "16%", "9 / 55" and "1 / 55" shift the right cluster sideways on every turn. | `PageRenderer.kt:46-54` | `statusPaint.fontFeatureSettings = "tnum"`, set once in the constructor |
| P1 | The header chapter title (11 sp black, centred, baseline 20 dp from the top) repeats the heading on the first page of a chapter ("프롤로그" over "프롤로그", "제1화 시작" over "제1화 시작"). That looks sloppy. | `ReaderActivity.kt:1241` (`chapterTitle`) | Skip the header on a page whose first line belongs to a heading block of the same chapter: `PageInfo.start == chapter.start`. This is an O(1) compare. |
| P2 | Status text uses `Typeface.SANS_SERIF` at 11 sp. On the 320 dpi Comet that is 22 px, with a Hangul x-height of about 11 px, which is thin next to the 20 sp serif body. | `ReaderSettings.statusFontSizeSp = 11f` (frozen) | Default 12 sp. Keep the "상태 표시 글자 크기" stepper. |
| P2 | The big TXT's total changes between runs ("1 / 43828" → "1 / 32719", 30, 31) while it is estimated. The user asked for no "~", so the jump is visible. | `BookSession` estimate blending (`PageCounts`) | Reader owner: seed the prior from the persisted counts of the same `layoutKey` family (same font, different margins), or show "12쪽" without a total until counting finishes. This is a labels decision; ask the user. |

### 2.2 Chrome, top (13)

| P | Issue | Code | Fix |
|---|---|---|---|
| P0 | Grey square behind the brightness icon (`isSelected` → PRESSED). | `ReaderChrome.kt:337`; `Ui.kt:163` | Item 6 / A3 icon swap |
| P0 | The chevron collapses the row instead of opening options. | `ReaderChrome.kt:142-145` | A5 |
| P1 | The title starts at 16 dp; the back-arrow glyph starts at 20 dp. Bold 700. | `ReaderChrome.kt:115-116` | `titleRow.setPadding(20dp, 0, 16dp, 8dp)`, 18 sp medium |
| P1 | The brightness row sits right under the title with a 6 dp gap and no separation. In ReadEra it is its own band. | `ReaderChrome.kt:115, 125` | Title bottom padding 8 dp, then a 1 px `LINE_LIGHT` line inset 16 dp, then the brightness row (48 dp) |
| P2 | Slider thumb 20 dp with 20 dp side padding: the track starts 72 dp from the left while the icon cell ends at 52 dp. | `ReaderChrome.kt:212-227` | Side padding 12 dp for both seek bars (the thumb still fits in `thumbOffset`) |

### 2.3 Chrome, bottom (13)

| P | Issue | Code | Fix |
|---|---|---|---|
| P0 | The label is off-centre, bold, and (WT) underlined. | `ReaderChrome.kt:154-175` | A1 |
| P0 | The pin means "menu pin", with the relayout bug. | see A4 | A4 |
| P1 | Three right icons, where ReadEra has two. The bookmark duplicates the corner tap and the overflow. | `ReaderChrome.kt:171-172` | Remove it from the bar (A1) |
| P2 | Seek preview box ("p. 1234 · 제3장") uses English "p.". | `ReaderActivity` `onSeekPreview` | "1234쪽 · 제3장 …", in the same wording as the strip and chip |

### 2.4 Reading-settings popup (14)

| P | Issue | Code | Fix |
|---|---|---|---|
| P1 | Asymmetric (46 dp left, 4 dp right), touching the top edge, with a sliver of cut page text on the left. | `ReadingSettingsPopup.kt:126`; `PopupGeometry.width/settings` | `Gravity.TOP or CENTER_HORIZONTAL`, x = 0. Width = `min(screenW − 16dp, 400dp)`. `settings(...)`: top = inset + 8 dp, `EDGE_DP` for the bottom. |
| P1 | A black 1 px line between every row makes a grid look. | `CompactUi.kt:56-73` (`compactRowBackground`) | `LINE_LIGHT`, inset 12 dp. Black only before the section headers ("페이지 넘김", "글자", "페이지", "TXT 파일", "EPUB 파일") and before "더보기". |
| P1 | Rows 36 dp, steppers 36 dp, toggles 28 dp tall. | `CompactUi.kt:37-53, 104-110` | `ROW_DP=44`, `STEP_DP=44`, toggle `minHeight=36` inside the 44 dp row. Merge 정렬 + 줄바꿈 into one row ("정렬 [왼쪽│양쪽]   줄바꿈 [어절│글자]"), so `MAIN_ROWS = 9` → 396 dp = 55 % of 720 dp. |
| P1 | Segment toggles are separate boxes 4 dp apart, radius 3 dp. The selected one is bold, so its width changes. | `CompactUi.kt:104-120` | Joined segmented control: one 1 px border with a 1 px divider, no radius. Selected = black fill + white regular text (no weight change, so the reserved-bold-width hack goes). |
| P2 | Label 14 sp versus value 15 sp is almost the same size, so the hierarchy is weak. The screenshot reads ~16 sp for both. | `Compact.LABEL_SP/VALUE_SP` | Label 15 sp regular, value 16 sp medium, tabular |
| P2 | "더보기  화면 터치 · 여백 · 상태 표시 · TXT · EPUB" in grey is redundant noise. | `ReadingSettingsPopup.kt` `moreRow` | "더보기" plus a chevron only. Put "일반 설정 ›" as a right-aligned link in the same row (ReadEra has "일반 설정" at the bottom). |
| P2 | No title, so the popup appears without context. | — | Optional 40 dp header "읽기 설정" (15 sp bold) with a 44 dp "×". Only if the 55 % budget allows; otherwise skip. |

### 2.5 Selection (17)

| P | Issue | Code | Fix |
|---|---|---|---|
| P1 | 5 + 4 grid with a hole; 12 sp labels in 62 dp cells. | `SelectionController.kt:377-396` | One row of 5 plus an overflow (item 13). `background = borderBox(radiusDp = 0f)`; ReadEra uses 2 dp, and 0 matches the rest of ours. |
| P2 | The popup overlaps the line above the selection (10 dp gap). With 200 % line spacing it covers two lines. | `SelectionController.kt:423` | `y = winTop − h − 6dp` is fine, but prefer below the selection when the selection is in the upper 40 % of the page. That follows the reading direction and covers text already read. |
| P2 | The handles are flat-top half discs (22 dp). They read as blobs. | `SelectionController.kt:~603` (`sizePx = 44dp`) | Teardrop: a 16 dp circle with a 1 px stem to the text edge. Keep the 44 dp touch box. |

### 2.6 TOC (15)

| P | Issue | Code | Fix |
|---|---|---|---|
| P2 | The current chapter uses a tiny "▶" 12 sp glyph in the left gutter plus bold. It looks like a stray bullet. | `ContentsDialog.kt:292` | Drop the glyph. Current row: bold, plus a 3 dp black bar on the start edge (full row height, a drawable layer, no layout change). |
| P2 | Title 19 sp here versus 20 sp toolbars and 18 sp chrome. | `ContentsDialog.kt:72` | Use the kit toolbar style (20 sp medium) |
| P2 | Tabs spread in thirds. ReadEra groups them centred with an underline the tab's width. | `ContentsDialog.kt:84` | Acceptable as is. Keep the 3 dp underline; make it text width + 16 dp (it already is). |

### 2.7 Search (16)

| P | Issue | Code | Fix |
|---|---|---|---|
| P1 | Mid-word breaks ("문장 / 입니다"). | `SearchPanel.kt:392` | Item 9 |
| P2 | Page number is 17 sp black, vertically centred. TOC uses 15 sp grey. | `SearchPanel.kt:393` | 15 sp `Ink.GRAY`, `Gravity.TOP` with 2 dp top padding, the same as TOC |
| P2 | Snippets cut mid-token ("3.1…"). | `SearchPanel` snippet builder | Cut at the last space within ±8 chars of the limit |

### 2.8 Library (01, 40)

| P | Issue | Code | Fix |
|---|---|---|---|
| P1 | Card padding 8/8/4/4: the cover is 7.5 dp from the top border and 4 dp from the bottom. | `LibraryViews.kt:160` | `setPadding(10dp, 10dp, 6dp, 10dp)`; the ⋮ button supplies its own inset |
| P1 | Double border (card + cover) and card gaps of 8 dp. | `LibraryViews.kt:150-158` | No card border. A 1 px `LINE_LIGHT` between cards, inset 8 dp. Pressed = PRESSED fill. The cover keeps its 1 px border: it is the "paper" edge of the TXT mini page. |
| P1 | The fast-scroll track (8 dp grey, full height) touches the cards' right border. | `LibraryActivity.kt:424-432`; `themes.xml` | Theme: `android:fastScrollTrackDrawable` = 1 px `LINE_LIGHT` inset shape, `android:fastScrollThumbDrawable` = 4×40 dp black rect. List `paddingEnd 12dp`, `scrollBarStyle = OUTSIDE_OVERLAY` (kept). |
| P2 | Title 18 sp bold 700, three lines. ReadEra is 20 sp regular. | `LibraryViews.kt:173` | 18 sp medium, line spacing 1.1 (kept) |
| P2 | The progress fill is a 3 dp black bar with a 4.5 dp dot. Heavy next to the 1 dp track. | `LibraryViews.kt:68-104` | Fill 2 dp, position dot 3.5 dp. The end dots stay at 2.5 dp. |

### 2.9 Drawer (02)

| P | Issue | Code | Fix |
|---|---|---|---|
| P2 | The selected shelf uses a #CCCCCC full-row fill (dithers on e-ink) plus bold. | `LibraryActivity.kt:697` | Bold label, filled icon variant, and a 4 dp black start bar. No fill. The count stays grey. |
| P2 | The header "리더플러스" is 20 sp bold next to 17 sp items. | `LibraryActivity.kt:629` | 20 sp medium |

### 2.10 Settings (50)

| P | Issue | Code | Fix |
|---|---|---|---|
| P1 | The off switch is a hollow pill; there are three trailing keylines. | `Toggle.kt:55-70`; `Ui.kt:263-278`; `SettingsPage.kt:91-96` | Item 12 |
| P1 | Summary text breaks mid-word ("이 / 어서"). | `Ui.kt:274` | Item 9 |
| P2 | Section header 14 sp bold with a full-bleed black line above each section. | `SettingsPage.kt:80-83`; `Ui.kt:258-260` | No line. 24 dp top gap, header 14 sp bold (kept). ReadEra separates sections by colour and space only. |
| P2 | Chevron 22 dp grey versus ▾ 24 dp black. | `SettingsPage.kt:91-96` | Both 24 dp `Ink.GRAY` in the common 48 dp trailing slot |

---

## 3. Remaining P2 checklist (not in the top 15)

- Toolbar title sizes: unify at 20 sp medium (library, settings, TOC, search field text 18 sp).
- Settings `valueRow` ▾ versus `navRow` ›: keep both glyphs but give them one colour (`Ink.GRAY`) and one size (24 dp).
- The return chip's position is `insets[3] + 6 dp` from the bottom when the chrome is hidden, so it sits on the footer
  band. With A2 it should sit above the footer band and the progress line: `bottom = footerBand + mb + 6dp`.
- Seek preview, chip and strip wording: always "N 페이지로" / "N쪽". Never the English "p.".
- The error panel's buttons are stacked, fixed at 180 dp and bold: fine. Give them the new medium weight for
  consistency.

---

## 4. Order of work (so the fixes do not collide)

1. The kit tokens (A6) and the `pressableBackground` selected-state change, through the contract step (`Ui.kt`,
   `themes.xml`).
2. READER: A4 (remove pinned chrome first, which deletes most relayout paths), then A1, A5, A3.
3. RENDER + READER: A2 (settings keys through the contract step, `LayoutKeys`, `PageRenderer`, `StatusLine`).
4. EXTRAS: the popup (7, 8), selection (13), TOC and search.
5. LIBRARY and SETTINGS: 12, 14 and the P2 items.
6. Re-shoot on CI: `13_txt_chrome` (label centred, strip visible after a pin), `13b_brightness_options`,
   `14_reading_settings` (centred, 44 dp rows), `10_txt_page1` (no footer text, progress line), `10b_footer_slots`
   (left = chapter, centre = page, right = clock + battery), `17_selection`, `50_settings`, `01_library`.

## 5. Consistency with the scroll-mode designs (A and B)

- **Status bands:** both designs keep them fixed and scroll only the content box. The 3-slot footer and header draw in
  those fixed bands unchanged. `drawStatus` (design A) or `drawChrome` (design B) calls the same slot code.
- **Progress line in scroll mode:** it uses the character progress that both designs already compute
  (`counts.charsFrom` / top-line progress). It is redrawn only when the decor is rebuilt (top page changed, or a jump),
  never per scroll frame, so the marker moves at most once per page-equivalent.
- **Page slots** show the paged-equivalent label, as both designs already specify. CHAPTER uses the top line's chapter.
- **Pinned chrome:** remove the "pinned chrome → relayout" rows (design A §relayout table, design B "Pinned chrome
  (메뉴 고정)"). The pin now stores a `DocPosition`, and `jumpTo` works the same way in scroll mode.
- **40 dp side margins shown as "0":** slot text aligns with the text column (`contentLeft .. contentLeft + cw`), so
  it moves in with the margins. The progress line uses fixed 12 dp page-edge insets (ReadEra), so it stays full-width.
  If that looks detached with 40 dp margins, switch the line to the column (one constant). The band reservation rule in
  A2 does not depend on side margins.
