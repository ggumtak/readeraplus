# ReaderaPlus — architecture & module specs

Personal e-book reader for the Innospace One **Comet** (Android 14 / API 34, 5.84" 1440×720 e-ink, ~276 ppi,
6 GB RAM, weak Cortex-A53-class CPU — probably MediaTek Helio P35). It replicates the *features and flow* of
ReadEra (library shelves, reading settings popup, TOC/bookmarks/quotes, search, TTS, selection) — never its code,
names, icons or assets — and fixes what ReadEra does badly for Korean web novels (TXT chapter detection, blank
lines, paragraph spacing/indent, CP949, e-ink behaviour, fast opening of 15 MB TXT).

> **Release 2 (contract revision R2).** Where this document and the section
> [Contract revision R2](#contract-revision-r2-release-2) at the end differ, R2 wins. The shared APIs, owners and
> stubs of R2 are listed in `docs/R2_INTERFACES.md`.

Hard requirements from the user:
- EPUB + TXT. Opening must feel instant; page turns must be instant.
- Font face (RIDIBatang and Nanum Myeongjo default choices + bundled fonts + user-imported fonts), size,
  weight, line spacing, **paragraph spacing**, indent, margins, alignment.
- Tap zones incl. "tap anywhere → next page", volume/hardware keys.
- Only black text on white (an optional inverted mode is the only other scheme). No colour themes.
- Optimisation is the top priority.

## Build & tooling

- Platform Android only. **No AndroidX / Material / Compose** (the dev container cannot reach Google Maven; CI can,
  but we keep the app dependency-free for speed and size). Only dependency: `kotlinx-coroutines-android`.
- Kotlin 2.1, AGP 8.7.3, Gradle 8.11.1, compileSdk 35, targetSdk 34, **minSdk 26**.
- UI is built **in code** (no layout XML): see `ui/kit/Ui.kt`. Icons are Material Symbols vector drawables in
  `res/drawable/ic_*.xml` (list: `ls app/src/main/res/drawable`). More icons: `python3 tools/fetch_icons.py
  app/src/main/res/drawable <symbol_name> [name:fill]`.
- UI strings are Korean and may be hardcoded (single-locale personal app).
- Local checks (no Android SDK here):
  - `tools/typecheck.sh --own <path>` compiles your owned paths against the frozen contract snapshot of all
    other modules (Robolectric android-all jar = real framework API 35). Run it often. Zero errors required.
  - `tools/unittest.sh --own <path> [fq.TestClass]` runs JUnit4 tests from `app/src/test/java/<same path>`.
    Framework classes that are pure Java (org.json, java.util.zip, android.util.Xml/kxml) work; anything
    native (Paint, Bitmap, SQLite, Typeface) does NOT — keep such code out of unit-tested classes.
  - CI (GitHub Actions) builds the real APK; you can't run it locally.
- Paths below are relative to `app/src/main/java/com/ggumtak/readeraplus/`.

## Ground rules for every module owner

1. Only create/modify files under the paths you own. **Frozen contract files** (never edit; report needed
   changes in your final answer instead): `engine/Content.kt`, `engine/Layout.kt`, `format/BookDocument.kt`,
   `format/Documents.kt`, `settings/*`, `data/Models.kt`, `ui/kit/Ui.kt`, `reader/ReaderHost.kt`,
   `render/FontCatalog.kt`, `App.kt`, `AndroidManifest.xml`, `res/values/*`.
2. Contract stubs (bodies `TODO("...")`) in files you own must be replaced by real implementations **keeping
   every public signature exactly** (names, parameter types/defaults, return types). You may add public
   members, new files, private helpers.
3. Use other modules only through their contract API (the snapshot). Don't depend on their internals.
4. Performance: no work on the main thread beyond view updates; no per-char object allocation in hot loops;
   primitive arrays; cache Typeface/Paint objects; avoid reflection except guarded vendor e-ink hooks.
5. Page turns on every device and mode replace the frame immediately: no fade, slide, curl or timed scroll step.
   E-ink UI: black on white, no animations (no ripples, no smooth scroll, no fades, no indeterminate spinners
   — use static "불러오는 중…" text), 1px black lines, no elevation. Dialogs/popups: `noAnimation()` /
   `animationStyle = 0`. Never update UI on a timer more often than needed (clock updates only on page turn).
6. Write original code. Do NOT copy code from GPL/AGPL projects (OpenReadEra, crengine, KOReader, Legado,
   Librera). Reading them for ideas is fine. Test fixtures must use original sample text (no copyrighted novels).
7. Add JVM unit tests for all pure logic you write (`app/src/test/java/com/ggumtak/readeraplus/<your path>/`).
8. Kotlin style: 4-space indent, no wildcard imports, KDoc on public API, comments only where non-obvious.

---

## engine/ — typesetter (pure Kotlin; owner: ENGINE)

Owns: `engine/` except `Content.kt`, `Layout.kt`. Implements `Typesetter`, `LineGeometry`, `RectPx` in
`engine/Typesetter.kt` (may split into more files). Tests: `app/src/test/.../engine/`.

Model recap (`Content.kt`, `Layout.kt`): a section is one String; paragraphs separated by one `'\n'`; blocks
(`ParagraphBlock`, `ImageBlock` (one U+FFFC char), `RuleBlock` (empty range)) cover the text without the
separators; `StyleRun`s give inline styles. All geometry in px relative to the page content box.

### Typesetter.layout(content) → SectionLayout
1. **Measure** every char once: walk the text in maximal same-style ranges (gaps = `RunStyle.PLAIN`) and call
   `measurer.measure(...)` into one `FloatArray advances` (size = text length). `'\n'` and `OBJECT_CHAR` → 0.
2. **Break lines per block**, available width `W = config.width - insetLeft - insetRight` (insets =
   `style.insetLeftEm/RightEm * emPx`, only when `config.publisherStyles` or the block is a list/quote the parser
   marked — simply always apply insets; parsers only set them when meaningful).
   - First line of a paragraph gets indent `config.indentEm * emPx` when `style.indent && !style.softBreak &&
     align != CENTER/RIGHT`.
   - Break opportunities:
     - after a space (U+0020, U+00A0 is NOT a break), after U+200B (zero width), after `-`/`‐`/`–`/`—` when
       followed by a letter, and around CJK ideographs/kana always.
     - `LineBreakMode.CHAR`: additionally between any two Hangul syllables / Hangul jamo / CJK chars.
     - `LineBreakMode.WORD` (default, keep-all): Hangul words stay intact; only spaces break. A single
       word wider than the line falls back to CHAR breaking inside that word.
     - Never break inside a Latin/digit word (`[A-Za-z0-9]` runs incl. `'` `’` `.` `,` between digits),
       except when it alone exceeds the line (then break at the last char that fits).
     - Never break between a surrogate pair, before a combining mark (Mn/Me), ZWJ U+200D, variation
       selectors (FE00–FE0F), or before a char with 0 advance.
     - **Kinsoku (금칙)**: no break *before* closing punctuation `. , ! ? : ; … ‥ ” ’ " ' 」 』 ) ] } 〉 》 】 〕
       ～ ~ · 、 。 ，` and no break *after* opening punctuation `“ ‘ 「 『 ( [ { 〈 《 【 〔`. Keep `……`, `——`
       and `?!`-style runs together.
   - Greedy fill: accumulate advances; when the next char would exceed `W`, break at the last opportunity; if
     none, force a break at the last fitting char (≥ 1 cluster per line — guaranteed progress).
   - Trailing spaces hang: `LineInfo.end` excludes them; the next line starts after them. Leading spaces of a
     wrapped (non-first) line are skipped.
   - Preformatted blocks (`style.preformatted`): no justification, spaces kept.
3. **Alignment**: effective align = block align if != DEFAULT (only honoured when `config.publisherStyles`
   or the block is a heading), else `config.align`.
   - JUSTIFY: every line except a paragraph's last line (and except preformatted) is justified:
     `slack = W_line - naturalWidth`. If the line has ≥ 1 inner space (between start and end), use
     `EXPAND_SPACES` with `justifyExtra = slack / spaces`, unless that exceeds `1.2 em` per space and the
     line has ≥ 2 chars — then `EXPAND_CHARS` over all visible char gaps (cap: if extra per char > 0.6 em,
     don't justify). Lines with no spaces: `EXPAND_CHARS`.
   - CENTER / RIGHT: offset `x`. LEFT: none.
4. **Vertical metrics**: `lineHeight = config.lineHeightEm * emPx * maxScale` where `maxScale` is the largest
   `sizeScale` in the line; at least `maxAscent + maxDescent` of the styles on the line. Baseline:
   `top + (lineHeight - (maxAscent + maxDescent)) / 2 + maxAscent` (CSS half-leading). Line height is
   em-based on purpose so every font gets the same spacing for the same setting.
5. **Vertical spacing**: before a paragraph add `paragraphSpacingEm * emPx` (+ `marginTopEm * emPx`), except:
   at the top of a page (suppressed), for `softBreak` paragraphs (0), for the very first block. After a block
   add `marginBottomEm * emPx`. Headings (`headingLevel > 0`) get at least `1.0 em` before and `0.6 em`
   after. Empty paragraphs render one blank line (no spacing suppression issues: an empty paragraph at the top
   of a page is dropped from that page, i.e. consumes no space).
6. **Images**: size from `measurer.imageSize(src)` (null → skip the block, no line). Fit: if intrinsic width
   ≥ 40% of `W` → scale to `W` (up or down); else display at 2× intrinsic (CSS px → e-ink px), capped at `W`.
   Then cap height at `config.maxImageHeightFraction * height` and at the page height (scale both).
   Image line: `imageBlock`, `imageWidth/Height`, centred (`x`), `top`/`bottom` = its box. An image that
   doesn't fit the remaining space goes to the next page.
7. **Rules** (`RuleBlock`): a line of height `lineHeight` with `isRule = true` (renderer draws a centred
   short line).
8. **Pagination**: fill pages top to bottom; a line goes to the next page when `y + spaceBefore +
   lineHeight > config.height` and the page already has content. Guarantees: never an empty page (except an
   empty section → exactly one empty page with start=end=0); page ranges are contiguous and cover
   `[0, text.length]`: `pages[0].start == 0`, `pages[i+1].start == pages[i].end`, last `end == length`.
   `pageBreakBefore` starts a new page when the current is non-empty. `keepWithNext` (headings): if the
   heading's last line ends a page and the next block's first line would not fit, move the heading to the next
   page (only if the page has other content). Widow/orphan control when `config.widowOrphanControl`: don't
   leave the first line of a ≥ 3-line paragraph alone at a page bottom, and don't start a page with the last
   line of a ≥ 3-line paragraph alone — move one line (only if the page keeps ≥ 2 lines).
9. `countPages(content)` must return exactly `layout(content).pageCount` without retaining `LineInfo`s
   (share one implementation with a `retain` flag). Memory for counting: advances array only.
10. Performance target (JVM, fake measurer): lay out 1,000,000 chars of Korean-like text in < 1 s, count in
    < 0.6 s. Zero allocations per char. Reuse arrays.

### LineGeometry
- `charPositions(layout, line, out)`: x of each char = `line.x` + prefix sum of advances + justification:
  after each space when `EXPAND_SPACES`, after each visible char (advance > 0) except the last when
  `EXPAND_CHARS`. Returns the right edge. The renderer, selection, TTS and search highlight all use this — it
  defines where glyphs are drawn.
- `hitTest(layout, page, x, y)`: line whose `[top, bottom)` contains y (if y is in a gap, the nearest line
  within half a line height; else -1); then the char whose `[x_i, x_i + advance)` contains x (clamp to
  line ends). Image lines return the image block's start.
- `rangeRects`: one rect per line intersecting `[start, end)` from x(start) to x(end) (x(end) = right edge
  when end ≥ line.end), `top..bottom` of the line.
- `wordAt(text, offset)`: extend over `Character.isLetterOrDigit` (Hangul included) both ways; if offset is
  on a non-word char return that single char. Packed as `(start.toLong() shl 32) or end.toLong()`.

### Tests (required)
Fake measurer: Hangul/CJK = 1.0 em, Latin letters/digits = 0.55 em, space = 0.3 em, punctuation 0.35 em,
em = 20 px, ascent 16 / descent 4. Cover: page-range invariants on random inputs (fuzz ~200 random sections),
countPages == layout.pageCount, no line wider than W (except forced single-cluster lines), kinsoku, WORD vs
CHAR, justification fills W exactly (±0.01) for non-last lines, trailing-space hanging, softBreak, headings
keepWithNext, widows/orphans, images scaling/skip, empty section, pageForOffset, hitTest round-trip with
charPositions, rangeRects, wordAt with Hangul/Latin/punctuation, performance smoke test (1M chars).

---

## format/txt — TXT parser (pure Kotlin; owner: TXT)

Owns `format/txt/`. Implements `TxtDocuments` (open / readMeta / preview / ENCODINGS) and a `BookDocument`.

1. **Encoding**: `options.txtEncoding` if non-empty, else detect: BOM (EF BB BF / FF FE / FE FF); UTF-16
   without BOM (NUL-byte parity in the first 4 KB: ≥ 30% NULs on one parity); strict UTF-8 validation (fast
   hand-written byte loop over the whole file, tolerate a truncated final sequence); otherwise **CP949**.
   CP949 charset name: first supported of `MS949`, `x-windows-949`, `windows-949`, then `EUC-KR` (Android
   supports MS949 via ICU; the JVM has x-windows-949). Never decode Korean as plain EUC-KR if CP949 exists.
2. **Line pipeline** (in this order, per line): replace rules (`txtReplaceRules`, `pattern => replacement`,
   compiled once, invalid ones skipped) → strip `'\r'` → trailing whitespace trim → leading whitespace
   (spaces, tabs, U+3000, U+00A0) removed when `txtStripIndent` → tabs to a space.
3. **Blank lines** (`txtBlankLines`): AUTO = if blank lines are 60–140% of non-blank lines (alternating
   file) remove single blank lines, else keep them; in all modes except KEEP a run of ≥ 2 blank lines becomes
   one empty paragraph (scene break). REMOVE_ALL removes singles always. COLLAPSE turns each run into one empty
   paragraph. KEEP keeps every blank line as an empty paragraph. Scene-break marker lines (`***`, `* * *`,
   `＊＊＊`, `---`, `===`, `◇◇◇`, `◆◆◆`, `ㅡㅡㅡ`, `~~~`, `ooo`, `○○○`, `§`) → centred paragraph
   (`Align.CENTER`, `indent=false`).
4. **Hard-wrap joining** (`txtJoinWrappedLines`, 1 = auto): detect when ≥ 60% of non-blank lines have length
   within 90–100% of the 90th-percentile length L (L ≥ 20) and don't end with `. ? ! … " ” ’ 」 』 ) ~`.
   Join such a line with the next non-blank line (not across blank lines/headings); join with a space if both
   sides are Latin or the original line had trailing whitespace, else join with a space too for Korean (word
   spaces) — i.e. always a single space unless both boundary chars are CJK ideographs/kana.
5. **Chapter detection** (`txtDetectChapters`): candidates are lines ≤ 60 chars after trim; cheap prefilter
   (contains a digit, 화/장/회/편/부/권/막/절, `#`, or starts with a special keyword char) before regex.
   Built-in rules (Kotlin regex, anchored on the trimmed line):
   - K1 `^[<〈《\[【「『(（]?\s*(?:제\s*)?\d{1,5}\s*(?:화|장|회|편|부|권|막|절|話)\s*[>〉》\]】」』)）]?(?:\s*[.:：\-–—~|·]\s*|\s+|$).{0,50}$`
   - K2 `^[<〈《\[【]?\s*(?:EP|Ep|ep|Episode|EPISODE|Chapter|CHAPTER|Ch|CH|#)\s*\.?\s*\d{1,5}(?!\d).{0,50}$`
   - K3 `^[<〈《\[【]?\s*(?:프롤로그|에필로그|서장|종장|서문|외전|번외|특별\s*외전|후일담|막간|작가\s*후기|작가의\s*말|완결\s*후기|후기|Prologue|PROLOGUE|Epilogue|EPILOGUE)(?=$|[\s\d.:：\-–—~|·>〉》\]】」』)）]).{0,40}$`
   - K5 `^[=\-*~#]{3,}\s*(\S.{0,40}?)\s*[=\-*~#]{3,}$` (only if the inner text matches K1–K3 or has a digit)
   - K6 `^\S.{0,30}?\s+\d{1,5}\s*화$` (title + N화)
   - K4 `^\d{1,4}\s*[.)]\s+\S.{0,40}$` only when it wins by the scoring below (list-prone).
   - user regex `txtChapterRegex` (if valid) is tried first.
   Reject lines ending with `다.`, `?”`, `!”`, `."`. Scoring: per rule count matches spaced > 1000 chars apart;
   the best rule (plus K3 specials always) defines chapters; require ≥ 2 chapters. Prune runs of ≥ 3 headings
   with no body between them (a TOC listing at the top). If the first body line equals the heading, drop it
   (duplicate title).
6. **Sections**: each chapter starts a section; text before the first chapter is section 0 (if non-empty).
   Chapters > 60k chars and chapter-less files are split into sections of ~30k chars at paragraph boundaries,
   preferring scene breaks / empty paragraphs within ±30% of the target. `SectionInfo.title` = chapter title
   for the first section of a chapter, null for continuation chunks. TOC: one `TocEntry(level 1)` per chapter.
7. **Headings**: when `txtEmphasizeHeadings`, a chapter heading paragraph gets
   `BlockStyle(headingLevel=2, align=CENTER, indent=false, marginTopEm=1.5f, marginBottomEm=1f, keepWithNext=true)`
   and `StyleRun(bold=true, sizeScale=1.2f)`; otherwise a plain paragraph.
8. **Fast re-open (index cache)**: first open decodes the whole file (off main thread) and builds a
   `TxtIndex`: charset, resolved global decisions (blank-line mode result, join-wrap result), and per section
   the **original byte range** `[byteStart, byteEnd)` (cut at line starts; for UTF-16 at even offsets after a
   newline code unit), title, chapter flag, char length. Persist it under `Documents.cacheDir/txtindex/`
   keyed by hash(path, size, mtime, ParseOptions). Later opens read the index only (a few ms) and
   `loadSection(i)` reads & decodes just that byte range (RandomAccessFile) and normalises it with the stored
   decisions — the result must be identical to the full-parse result for that section (test this!).
   `loadSection` caches the last 4 sections (LRU). Thread-safe (synchronize file access).
9. `readMeta`: title = file name without extension; no authors; encoding sniffed from the first 64 KB.
   `preview(file, maxChars, encoding)`: decode ≤ 64 KB, run the same line pipeline (defaults), return the
   first paragraphs joined with `'\n'`.
10. Performance: full first parse of 15 MB CP949 ≤ ~1 s on device (JVM test: < 1.5 s for 15 MB synthetic);
    indexed open < 50 ms; section load < 20 ms.

Tests: encodings (UTF-8 ±BOM, CP949 incl. UHC-only syllables like 똠/햏/쐈, UTF-16LE/BE ±BOM), each blank-line
mode, scene breaks, hard-wrap joining, chapter rules (positives: `제1화`, `제 12 화 새로운 시작`, `001화`,
`12화. 제목`, `< 1화 >`, `【1화】`, `#1`, `EP.3`, `Chapter 7`, `프롤로그`, `외전 1화`, `=== 3화 ===`,
`어떤 소설 12화`; negatives: `1화는 재미있었다고 그가 말했다.`, `후기가 좋다고 하더라.`, dialogue lines,
`1. 사과` lists), TOC pruning, duplicate title removal, section splitting sizes, block/offset invariants,
**index-cache equivalence** (cached open produces identical SectionContent text/blocks/runs), performance.

---

## format/epub — EPUB parser (pure Kotlin; owner: EPUB)

Owns `format/epub/`. Implements `EpubDocuments` and a `BookDocument`.

- `java.util.zip.ZipFile` kept open while the document is open (thread-safe reads). Entry names are
  URL-decoded and normalised (`a/b/../c` → `a/c`), matched case-sensitively first then case-insensitively.
- Write a small **tolerant XML/HTML tokenizer** (pure Kotlin; no XmlPullParser) used for container.xml, OPF,
  NCX, nav and XHTML: tags with attributes (quoted/unquoted), self-closing, comments, CDATA, `<!DOCTYPE>`,
  processing instructions, namespace prefixes stripped for matching (`dc:title` → `title`,
  `epub:type` kept as attribute name `epub:type` too), entities (`&amp; &lt; &gt; &quot; &apos; &nbsp; &hellip;
  &mdash; &ndash; &lsquo; &rsquo; &ldquo; &rdquo; &middot; &bull; &laquo; &raquo; &times; &copy; &reg; &trade;
  &deg; &prime;`, numeric dec/hex; unknown → literal). Malformed input must never throw: unclosed tags, stray
  closers, `<br>` without slash, attributes without values.
- OPF: metadata (title, creators (all), language, publisher, description (strip tags), series from
  `calibre:series`/`calibre:series_index` or EPUB3 `belongs-to-collection` + `group-position`), manifest,
  spine (all itemrefs in order; `linear="no"` items too), cover (`properties="cover-image"`, `<meta
  name="cover">`, guide `type="cover"` → its first image, manifest id/href containing "cover" with an image
  type, else first image of the first spine item).
- TOC: EPUB3 nav (`properties="nav"`, `<nav epub:type="toc">` nested `<ol>/<li>/<a>`), else NCX (spine `toc`
  attr or media-type `application/x-dtbncx+xml`), nested levels; href → (spine index, anchor). Fallback when
  empty: one entry per spine item that has a heading/title (use `<title>` or first h1–h3 text; skip untitled).
- One section per spine item (no splitting). `SectionInfo.approxChars` from the entry's uncompressed size / 3.
- **XHTML → SectionContent** (the core): build the text + blocks + style runs + anchors.
  - Skip `head` (but collect `<style>` text and `<link rel="stylesheet">` hrefs), `script`, `style`,
    `noscript`, `rt`, `rp`, `svg` except its `<image>`.
  - Blocks: `p div h1–h6 blockquote li dt dd pre section article header footer aside figure figcaption
    table tr td th ul ol dl body center address nav`: close the current paragraph at block start/end
    (nested blocks don't nest paragraphs: text directly inside a div that also contains <p>s becomes its own
    paragraph). `hr` → `RuleBlock`. `br` → end the current paragraph and start a `softBreak` continuation
    (so `<p>a<br/>b</p>` = two paragraphs, second with softBreak=true). `img`/svg `image` → `ImageBlock`
    (src resolved against the XHTML path → zip path).
  - Whitespace: collapse runs to one space, trim at paragraph start/end (except `pre`: keep, and each line of
    a pre is a softBreak paragraph). `&nbsp;` stays U+00A0 (not collapsed). A paragraph containing only
    whitespace/nbsp → empty paragraph; collapse consecutive empty paragraphs to one; drop leading/trailing
    empty paragraphs of the section.
  - Inline styles → `StyleRun`s: b/strong → bold; i/em/cite/dfn/var → italic; u/ins → underline;
    s/strike/del → strike; sup/sub → baselineShift ±1 and sizeScale 0.75; small → 0.85; big → 1.2;
    code/tt/kbd/samp → monospace; `a[href]` → link. Merge adjacent equal runs.
  - Headings: bold, sizeScale h1 1.5, h2 1.35, h3 1.2, h4 1.1, h5/h6 1.0; `BlockStyle(headingLevel=n,
    indent=false, keepWithNext=true, align=CENTER unless CSS says otherwise)`.
  - Lists: li → paragraph prefixed with "• " (ul) or "N. " (ol), `indent=false`, `insetLeftEm = 1.2 * depth`.
    Blockquote → `insetLeftEm = 1.5`, `insetRightEm = 1`. Tables: each row → one paragraph, cells joined by
    "  ·  ", `indent=false`.
  - Anchors: every `id` (and `a[name]`) → `anchors[id] = offset at that point`.
  - **Mini CSS** (only when `epubPublisherStyles`; always parse `display:none` and `text-align:center` for
    headings): selectors `tag`, `.class`, `tag.class`, `#id`, comma lists; for complex selectors use the last
    compound (approximation). Specificity: id > class > tag, later wins. Also `style="..."` attributes.
    Properties: `text-align` (left/center/right/justify), `text-indent` (0 → indent=false),
    `font-weight` (bold/≥600), `font-style: italic`, `font-size` (em/%/rem/keywords → sizeScale clamp 0.7–2.0,
    ignore px/pt), `display:none`, `margin-top/bottom` (em → clamp 0–3), `margin-left`/`padding-left` (em,
    clamp 0–4 → insetLeftEm), `page-break-before`/`break-before: always|page` → pageBreakBefore,
    `text-decoration` underline/line-through, `vertical-align` super/sub, `white-space: pre`. Ignore
    font-family, colors, backgrounds, line-height, letter-spacing (the user's settings win).
- `loadImage(src)`: zip bytes. `coverImage()`: bytes of the detected cover. `resolveLink(from, href)`:
  scheme → null; `#id` → same section; `file#id` → spine index (+ anchor offset, loading that section).
  `resolveToc(entry)`: offset from the section's anchors (0 if missing).
- Cache the last 4 converted sections (LRU). Converting a 300 KB XHTML must take < 150 ms on JVM.
- `readMeta`: container + OPF metadata only.

Tests: build EPUBs in-test with `ZipOutputStream` (EPUB2+NCX, EPUB3+nav, missing TOC, cover variants,
relative paths `../Images/a.jpg`, URL-encoded names). XHTML conversion cases: entities, whitespace, br,
nested divs, headings, lists, tables, images, anchors, CSS class rules, display:none, malformed markup.

---

## render/ — Android drawing side (owner: RENDER)

Owns `render/` except `FontCatalog.kt`. Implements everything stubbed in `render/Render.kt` (split into files
as you like, same package & signatures).

- **FontManager**: `init` registers bundled fonts (`FontCatalog.BUNDLED`, ids as listed) + system entries
  (`FontCatalog.SYSTEM_SERIF` "시스템 명조", `SYSTEM_SANS` "시스템 고딕") + user fonts from
  `filesDir/fonts/*.ttf|otf|ttc` and, if readable, `/sdcard/Fonts` and `/sdcard/fonts` (id `user:<file
  name>`; display name = the font's family name read from its `name` table (nameID 16 then 1, prefer the
  Korean record (language 0x0412) if present), else the file name). `init` must be cheap: don't open font
  files until `typeface()` or `fonts()` needs names (parse `name` tables lazily, cache).
  - `typeface(id, weight, italic)`: cached per (id, weight bucket 100, italic). Bundled: `Typeface.Builder(
    assets, path)`; files: `Typeface.Builder(File)`; variable fonts (detected via `fvar` table) use
    `setFontVariationSettings("'wght' N")`. Static fonts: use the bold file when `weight ≥ 600` and one
    exists, else the regular file. Italic: `Typeface.create(base, weight, true)` (API 28+) or no-op (the
    measurer applies skew). Unknown id → default (`FontCatalog.DEFAULT_ID`, `nanummyeongjo` since R2).
  - `syntheticStroke(id, weight, sizePx)`: static fonts only. `w = weight` minus 300 if the bold file is used
    (i.e. bold file ≈ 700), result `max(0, (w - 400) / 100f) * 0.012f * sizePx` (so 900 ≈ 6% of size).
  - `importFont(context, uri)`: copy via ContentResolver into `filesDir/fonts/` (validate the sfnt header
    `00 01 00 00` / `OTTO` / `true` / `ttcf`), register, return. `deleteUserFont` only for files under
    `filesDir/fonts`.
- **AndroidTextMeasurer**: `emPx = TypedValue.applyDimension(SP, settings.fontSizeSp)`. `paintFor(style)`
  cached per `RunStyle`: `TextPaint(ANTI_ALIAS_FLAG or SUBPIXEL_TEXT_FLAG or LINEAR_TEXT_FLAG)`, typeface from
  FontManager (bold → weight+300 capped at 900; monospace → `Typeface.MONOSPACE`), `textSize = emPx *
  sizeScale` (×0.75 for super/sub — the parser already sets sizeScale; don't double apply),
  `letterSpacing = settings.letterSpacingPm / 1000f`, synthetic stroke via `Style.FILL_AND_STROKE` +
  `strokeWidth`, italic skew `-0.2f` when the typeface isn't italic, underline/strike flags, color black.
  `measure` uses `paint.getTextWidths(String, start, end, FloatArray)` into a reusable temp array, then
  zeroes `'\n'` and `OBJECT_CHAR`. `metrics(style)`: from `paint.fontMetrics` (ascent = -ascent), cached.
- **ImageCache**: bounds via `BitmapFactory` `inJustDecodeBounds`; decode with `inSampleSize` (largest power
  of 2 keeping ≥ target), then scale to fit; `LruCache` by bytes; `RGB_565` when no alpha. Composite
  transparent images on white.
- **PageRenderer.draw**: background white (black if `invert`). Header (chapter title, ellipsized, small font
  `statusFontSizeSp`, system sans) centred in the top margin area above the content box (R3 2026-10-05: in its own
  band at the top edge, see the R3 revision below); footer left/right
  strings in the bottom margin area; both only when non-null. Then highlights (under text): QUOTE light grey
  fill `#D8D8D8` + 1px underline, SELECTION `#A8A8A8` fill, SEARCH `#C0C0C0` fill + 1px outline, TTS
  underline 2px + `#E0E0E0` fill (inverted variants when `invert`). Text lines: use
  `LineGeometry.charPositions` (reusable FloatArray) and draw **segments** — split at style changes and at
  expansion points — with `canvas.drawText(text, s, e, x, baseline + shift, paint)`; skip whitespace-only
  segments; superscript shift `-0.35 em`, subscript `+0.2 em`; underline/strike/link underline as lines.
  Images: `drawBitmap(src, null, dstRect, filterPaint)`. Rules: centred line 25% of width, 1dp.
  Bookmarked: a black ribbon (small pentagon) at the view's top-right corner. No allocations per draw beyond
  first use (reuse Paint/RectF/arrays). Night mode draws images through one shared inverting `ColorMatrixColorFilter`.
- **Covers.thumbnail(context, book, w, h)**: disk cache `cacheDir/covers/<id>_<mtime>_<w>x<h>.png`,
  memory cache handled by the caller. EPUB: `EpubDocuments.open` → `coverImage()` → sampled decode →
  centre-crop to w×h (fit if aspect differs a lot) on white. No cover → typographic placeholder (title in bold
  on white with a 1px border). TXT: `TxtDocuments.preview(file, 1200, book.encoding)` → typeset with
  `Typesetter` + an `AndroidTextMeasurer` built from a small ReaderSettings (fontSizeSp such that ≈ 18 lines fit,
  default font, lineHeight 1.5, paragraph spacing 0.6, indent 0, LEFT, margins 8%), draw page 0 on white with
  a 1px grey border — like ReadEra's mini first page.
- **Eink** (all reflection cached once and wrapped in try/catch; must never crash):
  - **Bigme "xrz" framework first** (the Comet is very likely a Bigme ODM design; verified on Bigme Android 14
    firmware to be callable from normal apps): `Class.forName("xrz.framework.manager.XrzEinkManager")`,
    instance via its `(Context)` constructor (application context). Methods (by name, reflectively):
    `setRefreshModeByView(View, int)`, `setRefreshModeByWindow(Window, int)`, static `forceGlobalRefresh(int)`.
    Mode constants: GC16=4, CLEAN=176, HD=177, DEFAULT=178, FAST=179, REGAL=180. Add public
    `Eink.prepareReaderView(view, mode)` (applies `AppSettings.einkMode` on the page view when the vendor control exists;
    the default `EINK_MODE_SYSTEM` leaves the device's per-app setting alone, like ReadEra) and
    `Eink.vendorName(): String?` ("Bigme xrz" / "Rockchip" / "Onyx" / null) for a debug/info row.
    Never call the persistent `DisplayPolicyManager.setRefreshModeForPackage`.
  - `Eink.fullRefresh(view, method, flashMs)` (R2): exactly one method. EINK_REFRESH_AUTO = Bigme GC16 → Bigme
    CLEAN → Rockchip `sendOneFullFrame()` → Onyx `View.refreshScreen` → NTX `postInvalidateDelayed(…, mode)` →
    flash; GC16 / CLEAN = xrz `forceGlobalRefresh` with the firmware's `EinkRefreshMode` numbers (else 4 / 176);
    FLASH = one frame for `flashMs` (50..1000). A missing or detectably failing device method falls back to the
    flash. `forceGlobalRefresh` is `void`: returning normally means "sent", never "refreshed" — [테스트] decides.
    The flash frame is black, or white over a dark (night) background. `fullRefresh(view)` uses `configure()`.
- Set `textLocale = Locale.KOREAN` on every TextPaint (Korean glyph variants for Hanja in fallback CJK fonts).
- Never use `Charset.forName("CP949")` anywhere (Android ICU maps it to a wrong IBM table) — use MS949.
Tests: font name-table parser (build a minimal sfnt in-test), stroke math, anything pure. (Most of render is
Android-native: keep it simple and correct.)

---

## data/ — library database, scanning, backup (owner: DATA)

Owns `data/` except `Models.kt`. Implements `Library`, `FileScanner`, `Backup`, `BookFileProvider`.

- `LibraryDb : SQLiteOpenHelper("library.db", v2 since R2 — see "Contract revision R2")`, WAL on, `PRAGMA synchronous=NORMAL`. Tables:
  `books(id INTEGER PK AUTOINCREMENT, path TEXT UNIQUE NOT NULL, file_name, title, author, series,
  series_index REAL, format TEXT, size INTEGER, mtime INTEGER, added_at, last_read_at DEFAULT 0,
  pos_section DEFAULT 0, pos_offset DEFAULT 0, progress REAL DEFAULT 0, favorite, to_read, have_read, trashed
  (INTEGER 0/1), review TEXT DEFAULT '', encoding TEXT DEFAULT '', language, reading_seconds DEFAULT 0)`;
  indexes on last_read_at, title, author, series. `bookmarks(id, book_id, section, offset, snippet, note,
  created_at)`, `quotes(id, book_id, section, start, end_, text, note, created_at)`, `collections(id, name
  UNIQUE, created_at)`, `book_collections(book_id, collection_id, PK both)`, `page_counts(book_id,
  layout_key, counts BLOB, updated_at, PK(book_id, layout_key))` — keep only the 3 newest keys per book.
  Foreign-key-like cleanup done manually on remove.
- Shelf semantics: READING_NOW = `last_read_at > 0 AND have_read = 0 AND trashed = 0` (recent first,
  regardless of sort); ALL = not trashed; FAVORITES/TO_READ/HAVE_READ flags; AUTHORS/SERIES/FORMATS/FOLDERS/
  COLLECTIONS grouped (`groups()` returns label + count; books with `group` filter; empty author → "작가 미상",
  books without series excluded from SERIES); DOWNLOADS = path contains `/Download/`; TRASH = trashed. Setting
  to_read clears have_read and vice versa; setting have_read also sets progress untouched.
- Sorting: RECENT (last_read_at DESC, added_at DESC), TITLE (title COLLATE NOCASE), AUTHOR, ADDED DESC,
  SIZE DESC, PROGRESS DESC. Query: LIKE on title, author, file_name (escape `%_`).
- `addOrUpdateFile(file)`: unchanged size+mtime → return existing; else `Documents.readMeta` (catch all →
  file-name title) and upsert. Title fallback = file name without extension.
- `FileScanner.scan`: roots = `Settings.app.scanFolders` or `defaultRoots` (primary external storage +
  removable volumes derived from `context.getExternalFilesDirs(null)` by cutting at `/Android/`).
  Iterative walk (ArrayDeque), skip hidden dirs, `Android/data`, `Android/obb`, excluded folders, and
  symlink loops (canonical path set). Files: `.epub`, `.txt` (≥ 1 KB), case-insensitive. Batch upserts in one
  transaction; entries under scanned roots whose files vanished are removed (bookmarks etc. kept? no — delete
  them too, unless the book is trashed: keep trashed until emptied). Progress callback every ~50 files.
- `Backup.export`: JSON (org.json) `{version:1, createdAt, settings:{reader:{...}, app:{...}} (raw
  SharedPreferences dump of Settings.raw()), books:[{path, fileName, size, title, author, series,
  seriesIndex, favorite, toRead, haveRead, trashed, review, encoding, posSection, posOffset, progress,
  lastReadAt, readingSeconds, collections:[names], bookmarks:[...], quotes:[...]}], collections:[names]}`.
  `import`: match by path, else fileName+size; restore everything; create collections; restore prefs; return
  count. Must be robust to missing fields.
- `BookFileProvider`: `content://<pkg>.files/book/<id>` → the book file (read-only), `getType` by format,
  `query` supports OpenableColumns (DISPLAY_NAME, SIZE). `uriFor(pkg, id)`.
- Everything thread-safe (one helper, SQLite handles locking); functions blocking.

Tests: whatever is pure (SQL builders, backup JSON mapping with org.json, path helpers). SQLite itself can't
run locally — be extra careful with SQL strings.

---

## ui/library — library screens (owner: LIBRARY)

Owns `ui/library/`. `LibraryActivity` (launcher, standard launch mode so relaunching from the home screen keeps an open reader).

- Root `FrameLayout`: main column (toolbar + optional search row + status row + list) and a **drawer panel**
  overlay (shown/hidden instantly, no animation; width min(80%, 320dp), white with a 1px right border; a
  transparent full-screen click-catcher closes it). Back closes drawer → search → group → finishes.
- Toolbar: hamburger (`ic_menu`), title = shelf label (or group name with a back arrow), `ic_search`,
  `ic_more_vert` (menu: 정렬 → chooser of `LibrarySort`, 보기 → 목록/표지, 도서 스캔, 파일 열기, 설정).
- Drawer items in order with icons (labels: `Shelf.X.label`, renamed in R2 to 읽고 있는 책 / 모든 책 / 읽을 책 /
  다 읽은 책): 읽고있는 문서 `ic_autorenew`, 책 & 문서 `ic_menu_book`, 즐겨찾기
  `ic_star`, 읽을 문서 `ic_schedule`, 읽던 문서 `ic_done_all`, 작가 `ic_person`, 시리즈 `ic_sell`, 컬렉션
  `ic_library_books`, 형식 `ic_layers`, 폴더 `ic_folder`, 다운로드 `ic_download`, 휴지통 `ic_delete`; divider;
  설정 `ic_settings`, 파일 열기 `ic_file_open`, 도서 스캔 `ic_refresh`, 정보 `ic_info`. Header: app name.
  Selected item: bold + `Ink.PRESSED` background. Show counts right-aligned when cheap.
- **Book card** (list mode, like ReadEra but B/W): 1px bordered card with 8dp margins; left: cover 96×136dp
  (`Covers.thumbnail` async, placeholder = white box with border); right column: title (18sp bold, max 3
  lines), "TXT, 3.4MB" (14sp grey; + author when present), progress line (thin black bar with a filled
  portion and a dot, + "34%"), actions row: `ic_star`/`ic_star_fill` (favorite), `ic_schedule`/
  `ic_schedule_fill` (to read), `ic_done_all`/`ic_done_all_fill` (have read), `ic_library_books` (collections
  dialog), `ic_more_vert` (book menu). Tap card → `ReaderActivity.open(context, id)`. Long press → book menu.
- Grid mode ("표지"): `GridView`, 3 columns on phones (auto by width 110dp), cover + 2-line title.
- Book menu: 읽기, 문서 속성 (`ReaderPanels.showDocumentInfo(activity, book, null)`), 파일 공유
  (`BookFileProvider.uriFor` + ACTION_SEND with FLAG_GRANT_READ_URI_PERMISSION), 컬렉션에 추가, 제목/작가
  편집, 인코딩 변경 (TXT: `TxtDocuments.ENCODINGS` + "자동"; saves `Library.setEncoding` and
  `Covers.invalidate`), 읽은 기록 초기화, 휴지통으로 이동. In TRASH: 복원, 영구 삭제 (confirm; option to delete
  the file too), toolbar action 휴지통 비우기.
- Collections dialog: checkbox list of collections + "새 컬렉션". COLLECTIONS shelf group rows: long press →
  이름 변경 / 삭제; toolbar action "새 컬렉션".
- Grouped shelves (작가, 시리즈, 컬렉션, 형식, 폴더): list of `ShelfGroup` rows (label + count) → tap → books.
- Search: toolbar search toggles an EditText row; filter as you type (debounce 250 ms) within the shelf.
- Covers: a fixed 2-thread executor + `LruCache<String, Bitmap>` (~12 MB); bind by tag to avoid stale images.
- Loading: all DB calls on `Dispatchers.IO` with a `MainScope()` cancelled in `onDestroy`; reload the list in
  `onResume` (progress changes). Empty states: helpful text + buttons (권한 허용 / 도서 스캔 / 파일 열기).
- **Permissions**: API 30+: if `!Environment.isExternalStorageManager()` show a panel explaining 모든 파일
  접근 권한 with a button → `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (package URI; fallback
  `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`); API ≤ 29: `requestPermissions(READ_EXTERNAL_STORAGE)`.
  If the settings intent can't be resolved (some e-reader firmwares hide it) fall back to the generic
  `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`, then offer adding folders via SAF `ACTION_OPEN_DOCUMENT_TREE`
  (persist the tree permission; convert `primary:X` to a path) and show the adb hint
  `adb shell appops set com.ggumtak.readeraplus MANAGE_EXTERNAL_STORAGE allow`.
  After granted (onResume) → scan automatically. Auto-rescan in background on start if the last scan (raw pref
  `lastScanAt`) is > 30 min old; show "스캔 중… N" in a status row (no spinner).
- 파일 열기: `ACTION_OPEN_DOCUMENT` (mime `application/epub+zip`, `text/plain`, `*/*`) → copy to
  `getExternalFilesDir("books")` (or filesDir/books) keeping the display name → `Library.addOrUpdateFile` →
  open it.
- `openLastOnStart`: in `onCreate` (not on recreation), if enabled and `Library.lastOpened()` exists and its
  file exists → open the reader immediately.
- 정보: dialog with app version (BuildConfig.VERSION_NAME), a short description, and licenses
  (`assets/fonts/licenses/FONTS.txt` contents in a scrollable dialog).

---

## reader/ (core) — reading screen (owner: READER)

Owns `reader/` except `reader/ReaderHost.kt` and `reader/extras/`. `ReaderActivity : Activity(), ReaderHost`.

- Intent: `EXTRA_BOOK_ID`, or `ACTION_VIEW` uri (file:// → path; content:// → try resolving a real path via
  `DocumentsContract` "primary:…" ids and `MediaStore` `_data`, readable → use it; else copy to
  `getExternalFilesDir("books")/<display name>` (reuse if same size) → `Library.addOrUpdateFile`).
- Window: `ReaderTheme`; fullscreen per `AppSettings.fullscreen` (API 30+ `WindowInsetsController.hide(
  systemBars())` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; else legacy flags); `layoutInDisplayCutoutMode
  = SHORT_EDGES`; when not fullscreen, pad the page for system bar insets (the Comet cuts off apps that ignore
  insets). Keep screen on: `FLAG_KEEP_SCREEN_ON` cleared after (system `SCREEN_OFF_TIMEOUT` + 10 min) of no
  interaction (reset on page turns/touches). Brightness: `AppSettings.brightness` (−1 = system) via window
  attributes. Orientation lock from `AppSettings.orientationLock`.
- Views: root `FrameLayout` → `PageView` (custom View, full size) + chrome overlays + "돌아가기" chip +
  loading text. `pageView.parent` MUST be this FrameLayout (selection handles are added to it).
- **BookSession** (reader/BookSession.kt): owns the `BookDocument`, current `ReaderSettings`, the
  `LayoutConfig` (content box = view size − margins (dp→px; `pageMargins=false` → 4dp) − header/footer
  heights when shown (≈ statusFontSize × 2.2)), one main-side `AndroidTextMeasurer` + `ImageCache`, an LRU of
  3–4 `SectionLayout`s, layout on `Dispatchers.Default` (single-threaded executor for foreground layouts), and
  prefetching of the next and previous sections after each display.
  - `layoutKey` = stable hash string of (every layout-affecting ReaderSettings field, parse options, content
    width/height, density, font file identity).
  - **Page counting**: load `Library.pageCounts(bookId, key)`; if missing, count in the background with a
    separate measurer (`Typesetter.countPages` per section, low priority thread), publish progress, then
    `Library.savePageCounts`. Restart on settings/size change. Until complete, totals are estimated from
    counted sections' pages/char ratio (blended with a geometry prior) × `SectionInfo.approxChars`; labels never
    show a "~" (user request) — estimates are plain numbers.
  - Global page (1-based) = Σ counts[0 until section] + pageInSection + 1.
- Navigation: next/prev within the section; crossing sections uses the prefetched layout (else lay out,
  showing nothing new until ready — no spinner flash for < 300 ms). `goTo(pos, remember)`: remember pushes the
  previous position to a stack; show the "← 돌아가기 (p. N)" chip until used, dismissed (its × button) or
  another jump. Save position (`Library.savePosition(id, section, page.start, progress)`) debounced 1 s and
  in `onPause`; add reading time in `onPause`.
- **PageView**: draws via `PageRenderer.draw` with `PageDecor` (all highlight owners for this section +
  quotes of the book (loaded at open & refreshed when changed), bookmark flag if any bookmark offset is in the
  page range, header = current chapter title (last TOC entry at/before the position; else SectionInfo.title;
  else book title), footer left = "12 / 3259", right = `34%  ·  14:05  ·  80%` per footer
  flags (+ "챕터 N쪽 남음" when `footerChapterLeft`). Battery from the sticky `ACTION_BATTERY_CHANGED`
  read on page turns only.
  - Touch: implement taps in `onTouchEvent` directly (no `onSingleTapConfirmed` latency). Taps are never
    throttled (only a duplicate report of the same touch within 40 ms is dropped; turns requested while a
    section is still laying out are queued and applied together). Order: selection active → `SelectionController.onTouchEvent`; link under finger → navigate
    (remember=true); corners: top-right 15%×10% → bookmark when `bookmarkByTouch`, top-left → invert when
    `invertByTouch`; else zone action per `TapZoneMode`: LEFT_RIGHT = left 1/3 PREV, centre cell MENU, rest
    NEXT; ALL_NEXT = centre cell MENU, left 12% strip PREV, everything else NEXT; TOP_BOTTOM = top 40% PREV,
    bottom 40% NEXT, middle band: centre MENU, sides NEXT; CUSTOM = `customTapZones` 3×3. Long-press (500 ms,
    small slop) → `SelectionController.startAt` when `longPressSelect`. Horizontal swipe (> 60dp, faster than
    vertical) → next (right-to-left) / prev when `swipeToTurn`; vertical swipe when `verticalSwipe` (up =
    next). Left-edge (10%) vertical drag → brightness when `brightnessSwipe` (overlay "밝기 40%").
  - Keys (`dispatchKeyEvent`, consume DOWN and UP so the volume panel never shows): next = VOLUME_DOWN (if
    `volumeKeysTurn`, swapped by `invertVolumeKeys`), PAGE_DOWN, DPAD_RIGHT, DPAD_DOWN, SPACE, MEDIA_NEXT,
    plus `AppSettings.nextPageKeys`; prev = VOLUME_UP, PAGE_UP, DPAD_LEFT, DPAD_UP, Shift+SPACE,
    MEDIA_PREVIOUS, plus `prevPageKeys`; MENU/ENTER/DPAD_CENTER → toggle chrome; BACK → close
    selection/TTS bar/chrome first. Ignore auto-repeat faster than 150 ms.
  - E-ink refresh: every `einkRefreshEvery` turns and on section change when `einkRefreshOnChapter` →
    `Eink.fullRefresh(pageView)`.
  - Auto page turn: toggled from the overflow menu; `Handler` every `autoTurnSeconds`; any touch stops it.
- **Chrome** (hidden by default, centre tap toggles; tapping the page while visible hides it): top panel
  (white, 1px bottom line): row [back `ic_arrow_back`, spacer, TTS `ic_volume_up`, search `ic_search`, TOC
  `ic_toc`, settings `ic_settings`, more `ic_more_vert`], book title line, brightness row
  [`ic_brightness_auto` toggle (filled look when system), SeekBar 0–100, `ic_expand_less` collapse]; bottom
  panel (1px top line): row [spacer, page label "12 / 3259" centred, rotation lock
  `ic_screen_lock_rotation`/`ic_screen_rotation`, bookmark toggle `ic_bookmark`/`ic_bookmark_fill`] and a
  SeekBar over total pages (or 0–1000 progress while estimating) — while dragging show a preview label
  "p. N · 챕터 제목"; on release `goTo(remember=true)`. No live page rendering while dragging.
  Overflow menu: 페이지 이동 (`ReaderPanels.showGoTo`), 북마크 추가/삭제, 자동 넘김 켜기/끄기, 화면
  새로고침, 내 리뷰 (`showReview`), 추가… (즐겨찾기 / 읽을 문서 / 읽던 문서 / 컬렉션), 문서 속성
  (`showDocumentInfo`), 파일 공유, 일반 설정 (`SettingsActivity.open`).
- Wire extras: TTS → `TtsController(this).start()`; search → `ReaderPanels.showSearch(this)`; TOC →
  `showContents(this)`; gear → `showReadingSettings(this, gearView)`. Instantiate `SelectionController(this)`
  and forward as described; call `onPageChanged()` after every page change; `TtsController.onUserNavigated()`
  on manual turns during TTS.
- `applySettings(new)`: `Settings.saveReader(new)`; if parse options changed → reopen the document (keep
  position; if section count changed use progress ratio) else rebuild measurer/config, clear layouts, re-layout
  keeping the current offset, restart counting.
- `onConfigurationChanged` (rotation) → re-layout for the new size. Close the document in `onDestroy` after
  cancelling jobs; release TTS.
- Open speed: show the first page as soon as its section is laid out; everything else (quotes, bookmarks,
  counts, prefetch) after. Show "불러오는 중…" only if opening exceeds 300 ms. Errors → message with the reason
  and a 닫기 button (never crash on bad files).

---

## reader/extras — panels, TOC, search, selection, TTS (owner: EXTRAS)

Owns `reader/extras/`. Implements `ReaderPanels`, `SelectionController`, `TtsController` against
`ReaderHost` only.

- `showReadingSettings(host, anchor)`: a `PopupWindow` (no animation, white, 1px border) anchored under the
  top bar (the bars hide while it is open), width min(86%, 330dp), height ≤ 55%, compact 36dp rows, the
  less-used rows behind "더보기"; style presets (웹소설 / 전자책 / 종이책 since R2) on top. Header
  "읽기 설정 · EPUB, TXT". Rows (each change → `host.applySettings(copy)` immediately; steppers debounce
  250 ms):
  폰트 페이스 (value + dropdown → font chooser dialog: each row rendered in its own typeface, sample "가나다
  한글 Aa 123", + "폰트 추가…" → SAF import via `FontManager.importFont` then select it), 폰트 크기 (−/+
  0.5sp, 8–60), 폰트 굵기 (SeekBar 100–900 step 50, label), 줄 간격 (−/+ 5%, 100–300%), 문단 간격 (−/+
  10%, 0–300%), 들여쓰기 (−/+ 0.25em, 0–4em), 글자 간격 (−/+ 1%, −10%–+20%), 글자 정렬 (양쪽 정렬 / 왼쪽
  정렬), 줄바꿈 (어절 단위 / 글자 단위), 페이지 여백 (switch) + 좌우 여백 / 상하 여백 steppers (dp),
  상단 챕터 제목 (switch), 하단 정보 (switch) + items (쪽수, 챕터 남은 쪽, %, 시계, 배터리), 흑백 반전
  (switch), TXT section: 빈 줄 처리 (자동 / 모두 제거 / 하나로 / 유지), 원본 들여쓰기 제거, 챕터 자동
  인식, 챕터 제목 강조, 줄 합치기 (자동/켬/끔), 챕터 규칙(정규식) (prompt), 치환 규칙 (multiline prompt);
  EPUB section: 출판사 스타일 사용; footer buttons: 기본값 복원, 일반 설정 (`SettingsActivity.open(ctx,
  SettingsActivity.PAGE_PAGE_TURNING)`).
- `showContents(host, tab)`: `fullScreenDialog`: toolbar (back, book title), tab row 목차 · 북마크 · 인용문
  (selected = bold + 3dp underline), ListView per tab.
  - 목차: `document.toc` indented by level (16dp/level), page number right-aligned (resolve each entry with
    `document.resolveToc` + `host.pageLabel` on a background thread, fill in progressively), current chapter
    bold with a ▶ marker and scrolled into view. Empty → "이 문서에는 목차가 없습니다" (+ TXT hint "읽기 설정에서
    '챕터 자동 인식'을 켜거나 규칙을 추가하세요").
  - 북마크: `Library.bookmarks` rows (snippet 2 lines, page label, date); tap → goTo; long press → 메모
    편집 / 삭제. 인용문: `Library.quotes` (text 4 lines, note, page); tap → goTo quote start; long press → 복사 /
    공유 / 메모 / 삭제; toolbar action "모두 공유" (text export).
- `showSearch(host, q)`: `fullScreenDialog` with an EditText (IME search action) + results ListView. Search
  on `Dispatchers.Default`: for each section `document.loadSection(i)`, case-insensitive (Latin) substring
  scan with `regionMatches`, snippet ±30 chars with the hit in bold (`SpannableString`), page label via
  `host.pageLabel`. Stream results (update every 200 ms), cap 1000, show "N개 결과" / "검색 중… (섹션 x/y)".
  Tap → `host.goTo(pos)` + `host.setHighlights("search", section, [SEARCH])` + dismiss. Keep the last query
  and results in memory so reopening search shows them.
- `showReview`: dialog with multiline EditText (book review) → `Library.setReview` on IO.
- `showDocumentInfo(activity, book, document)`: scrollable dialog: 제목, 작가, 시리즈, 형식, 크기, 경로,
  인코딩 (TXT), 언어, 추가한 날짜, 마지막으로 읽은 날짜, 진행률, 읽은 시간, 목차 항목 수 (if document),
  설명 (EPUB description). Buttons: 편집 (title/author prompts → `Library.updateMeta`), 닫기.
- `showGoTo(host)`: dialog with a number field: "쪽 번호 (1–N)" or "%" toggle → `host.goTo` (remember=true)
  using the page counts (if not yet known, percent mode only: map percent to section by approxChars).
- **SelectionController**: `startAt(x, y)` → `host.hitTest` → `LineGeometry.wordAt` → selection
  `[s, e)` in the current section (limited to the current page), highlight owner "selection". Two handles
  (24dp black teardrop/bar views added to `host.pageView.parent as FrameLayout`), draggable, updating the
  selection by hit-testing. Action popup (PopupWindow, 1px border, no animation) above the selection (below
  if no room) with buttons: 복사, 인용 (`Library.addQuote` → refresh quotes via
  `host.setHighlights("quotes", …)`), 메모 (quote + note prompt), 공유 (ACTION_SEND text), 검색 (in book),
  사전·번역 (query `ACTION_PROCESS_TEXT` activities; show a chooser list of them; fallback web), 웹 검색
  (`AppSettings.webSearchUrl`), TTS 여기서부터 (optional). A tap outside clears. `onPageChanged` clears.
- **TtsController**: `TextToSpeech` (init once, Korean locale if available); on `start()` speak from the
  current page start: split the section text into sentences (end at `. ! ? … 。` followed by
  space/quote/newline, or at '\n'; chunks ≤ 300 chars split at spaces), queue ~3 utterances ahead with ids
  `"sec:start:end"`; `onStart` (post to main) → highlight "tts" + turn the page when the sentence starts
  after the current page end (`host.nextPage()`/`goTo(remember=false)`); continue into the next section at
  the end. Controls bar overlay at the bottom (white, 1px top line): [× stop] [⚙ 속도/피치/수면 타이머 dialog]
  [page label] [‹ prev sentence] [› next sentence] and a round black play/pause button above it. Audio focus
  (pause on loss), sleep timer (`ttsSleepMinutes`), keep screen on while speaking, `release()` shuts down TTS.
  Rate/pitch from `AppSettings`, saved when changed in the dialog.

---

## ui/settings — app settings (owner: SETTINGS)

Owns `ui/settings/`. `SettingsActivity` with in-activity page stack (toolbar back pops; `EXTRA_PAGE` opens a
sub-page directly). All changes saved immediately via `Settings.saveApp` / `saveReader`.

- Main page (ReadEra-like sections with bold headers):
  - 일반: 파일 스캔 → page; 백업 및 복원 → page; 앱 시작시 문서 읽기 (switch); 모든 파일 접근 권한 (status +
    open system settings).
  - 읽기 설정: 페이지 넘김 및 페이지 표시 → page; 글꼴 관리 → page; Text to speech (TTS) → page;
    사전 · 번역 · 웹 검색 → page; 전체 화면 모드 (switch, "상태표시줄과 네비게이션바 숨김"); 스와이프로 밝기 조절
    (switch, "화면 좌측을 위아래로 스와이프"); 터치로 흑백 반전 (switch, "좌측 상단을 터치"); 터치로 북마크 (switch,
    "우측 상단을 터치"); 화면 켜짐 유지 (switch, "시스템 화면 꺼짐 시간보다 10분 더").
  - 기타: 캐시 비우기 (covers, txt index, page counts via cacheDir cleanup + a raw pref bump), 정보.
- 파일 스캔 page: scan folder list (add via `ACTION_OPEN_DOCUMENT_TREE` → convert tree uri
  `primary:Books` → `/storage/emulated/0/Books`, other volumes `/storage/<id>/...`), remove, excluded folders,
  "지금 스캔" (runs `FileScanner.scan` on IO with a progress line).
- 백업 및 복원: 백업 파일 만들기 (`ACTION_CREATE_DOCUMENT` "readeraplus-backup-yyyyMMdd.json" →
  `Backup.export`), 복원 (`ACTION_OPEN_DOCUMENT` → `Backup.import`, then restart hint).
- 페이지 넘김 및 페이지 표시: tap mode chooser with a **visual 3×3 preview** of the zones (labels 이전/다음/
  메뉴); in CUSTOM mode tapping a cell picks its `TapAction`; switches: 스와이프로 넘김, 세로 스와이프,
  볼륨 키로 넘김, 볼륨 키 반대로, 길게 눌러 텍스트 선택; **"페이지 키 지정"**: a dialog "다음 페이지로 쓸 키를
  누르세요" capturing the next key event (`setOnKeyListener`) and adding its keyCode to
  `nextPageKeys`/`prevPageKeys` (+ list/clear); e-ink 전체 새로고침 주기 (0=끔, 1–20 pages stepper) + 챕터
  시작 시 새로고침; 자동 넘김 간격 (5–300 s); a "키 테스트" row that shows the last pressed keyCode.
- 글꼴 관리: list of `FontManager.fonts()` with a sample in each typeface, source label (기본 / 사용자),
  select-as-reading-font on tap, delete for user fonts, "폰트 추가" (SAF `.ttf/.otf`), note about
  `/sdcard/Fonts`.
- TTS: 속도, 피치 (steppers 0.5–2.0), 수면 타이머 (0/15/30/60 min), TTS 엔진 설정 열기
  (`Intent("com.android.settings.TTS_SETTINGS")`).
- 사전 · 번역 · 웹 검색: web search engine chooser (Google / Naver `https://search.naver.com/search.naver?query=%s`
  / Daum / 사용자 지정 URL), list of installed `PROCESS_TEXT` apps (info only).
- 정보: version, font & icon licenses (`assets/fonts/licenses/*`).

---

## Contract revision R2 (release 2)

R2 is one contract commit that lands before the release-2 feature work (spec section D). The frozen files it changed,
every shared API with its owner, and the owner map are in **`docs/R2_INTERFACES.md`**. This section records the rules
that outlive the release.

### Ranking rules every owner applies (spec section 0)

1. **The Comet comes first.** Anything that adds e-ink updates, startup work, per-page cost, background polling or
   animation is rejected or redesigned.
2. **Fast opening is sacred.** Nothing new runs between `ReaderActivity.startOpen` and `showPage`, with two
   exceptions: one DB or prefs read folded into the IO block that already exists (R2: `BookPrefs.txtOverride`), and a
   cache read that replaces more expensive work on that path, only when that work would actually run (R2: the EPUB
   section-plan cache). Counting, annotations, statistics, reading speed and episode parsing run after the first page
   (`afterOpen`). The first `Settings.app` read of a cold start is on the main thread: it reads plain prefs only (the
   saved styles' JSON is parsed on first use, never in `loadApp`).
3. **Value per effort.** The Korean web-novel TXT workflow before EPUB polish before general features.
4. **Platform APIs only.** No AndroidX, no libraries. The only permissions added in R2: `INTERNET` (the Wi-Fi transfer
   page, while it is open), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (TTS with the screen off).
5. **Every flash the app starts on its own is opt-in.** A new refresh trigger ships switched off unless the user asked
   for that refresh (the 새로고침 key or action, the refresh test). R2 defaults: `einkRefreshEvery = 0`,
   `einkRefreshEveryNight = -1` (same as day), `einkFlashImages = false`; closing a panel only counts one turn toward
   a cadence that is already on. The default e-ink mode stays "system" (the device keeps its own waveform).
6. **At most one `TxtIndexStore.VERSION` bump per release.** Each bump makes every large TXT parse in full once
   (≈ 1–1.5 s for 14 MB on the A53). Release 2 spends it on A5 (3 → 4). While a TXT over 4 MB is parsed in full
   (`TxtDocuments.isBuildingIndex`) the delayed loading text reads "목차를 만드는 중…". The bump is also
   `TxtDocuments.PARSE_VERSION`, part of `LayoutKeys.textSignature`: a saved TXT position is found again once by its
   char fraction, since the new parse may split sections differently.

Per turn: O(1) work and no allocation beyond what exists (the R2 footer items add one string per turn); no idle
redraws, timers or polling; no animations anywhere. Page-turn taps are never debounced and fresh key presses never
throttled (only auto-repeat is paced).

### Page counts: algorithm version and golden hash (A2)

- The page-count key (`LayoutKeys.key`) carries `LayoutKeys.ALGO_VERSION` instead of the app's `VERSION_CODE`, so an
  app update no longer throws every cached count away.
- **Rule:** bump `ALGO_VERSION` exactly when the output of `TypesetPass` (or of the measurer) can change for the same
  input. `LayoutGoldenTest` (`test/.../engine/`) enforces it for the typesetter: it lays out a fixed original corpus
  with the fake measurer (Korean and Latin text, headings, an image, justification, CHAR and WORD breaking), hashes
  every `LineInfo` field and compares with `LayoutKeys.GOLDEN_HASH`; on a mismatch it fails with "layout output
  changed: bump ALGO_VERSION and update GOLDEN_HASH". Measurer changes (`FontManager`, `AndroidTextMeasurer`,
  synthetic stroke) are NOT covered by the test: whoever changes glyph advances or line metrics bumps `ALGO_VERSION`
  by hand.
- Counts are saved partially: the `page_counts` BLOB (little-endian int32 per section) may hold -1 for a section not
  counted yet (or counted as its error page: those are never cached). The reader saves every 25 counted sections, when
  complete and on close (on `ReaderIo`, array copied on the main thread first; an older save never overwrites a newer
  one), but an incomplete array only once its layout generation is 30 s old (`BookSession.SAVE_SETTLE_MS`): the table
  keeps 3 keys per book, and every font size tried in the settings popup would otherwise push out the complete counts
  of the layout the reader returns to. `PageCounts.setKnown` takes the counts (≥ 1), keeps sections already counted in
  the session, and rejects a length mismatch or any value other than a count or -1. Counting order: the section on
  screen, then samples at 25 / 50 / 75 % (EPUB: single-part spine items only), then the rest. The estimate ignores
  counted sections under 2,000 chars once a larger one is counted.

### EPUB section-plan cache (A12-1)

- `format/epub/EpubPlanCache.kt` keeps, per EPUB, the per-spine-item `parts`, `itemChars` and `fragParts` that
  `EpubBook.planSections` computes by scanning items larger than `EpubSplit.SCAN_MIN_BYTES` (192 KB). Key
  `"v${EpubPlanCache.VERSION}|path|size|mtime"`, file `cacheDir/epubplan/<fnv64>.bin` (`DataOutputStream`), newest 200
  kept.
- Looked up only when at least one spine item is larger than `SCAN_MIN_BYTES` (a small EPUB never pays for a
  cache-miss file open). Written after the first page: the reader calls `Documents.writeDeferredCaches()` from
  `afterOpen` through `ReaderIo.launch`, never on the opening thread.
- **Rule:** bump `EpubPlanCache.VERSION` whenever `EpubSplit.partsFor`, `scan` or `assign` change. A golden test (fixed
  synthetic XHTML → expected parts and anchors) guards it, like `LayoutGoldenTest` guards `ALGO_VERSION`.
- A plan is used only when the spine size, the scanned item indices and a hash of the TOC anchors asked for still
  match; the recently-used touch is also deferred to `writeDeferredCaches`.
- TXT replace rules: `RegexLiterals` (`format/txt/RulePrefilter.kt`) extracts the literals a line must contain for each
  rule to match; `LineGate` skips the regex and the String creation for lines without them (sound: syntax it does not
  fully understand → no prefilter for that rule, which then always runs its regex).

### Library database v2

One schema bump for the release (`LibrarySchema.DB_VERSION = 2`):

- `reading_log(day, book_id, seconds, pages, chars, PRIMARY KEY(day, book_id)) WITHOUT ROWID` + index on `book_id`.
  `day` = local date as yyyymmdd. Written only by `ReadingLog.add` (UPDATE `… = … + ?`, INSERT when no row changed,
  one transaction: SQLite 3.18 has no UPSERT), from the reader's pause (the `ReadingTracker` delta: a page counts
  when shown ≥ 2 s, at most 300 s) and from TTS while the reader is in the background. Read by the statistics page
  and `ReadingLog.cpm` (reading speed for "남은 시간").
- `book_prefs(book_id INTEGER PRIMARY KEY, txt_override TEXT, finished_at INTEGER NOT NULL DEFAULT 0,
  episode_label TEXT)`: a book's own TXT options (JSON of `TxtOverride`, merged with the global settings only by
  `reader/TxtOverrides.kt` `ReaderSettings.withTxt`), when it was finished, its file-name episode badge (T2-13). The
  open path reads `txt_override` by primary key inside its existing IO block — a table, not a prefs file, so a cold
  open from a file manager never parses every book's rules.
- `quotes.style INTEGER NOT NULL DEFAULT 0` (highlight look, used from T2-3).
- Migration (`LibraryDb.onUpgrade`): `CREATE_ALL` (all `IF NOT EXISTS`: new tables and indexes), then
  `LibrarySchema.upgradeStatements(oldVersion, columnsOf)`: the `ALTER TABLE … ADD COLUMN` statements of versions
  after `oldVersion`, each skipped when `PRAGMA table_info` already lists the column (a file that went v2 → an older
  build → v2). `onDowngrade` keeps everything. Nothing is ever dropped.
- Removing a book (`Library.deleteBookRows`) deletes its `reading_log` and `book_prefs` rows; the backup exports both
  keyed by path.

### UI kit (R2)

- `Ui.kt` (frozen): `stepperRow` does nothing at its limits (no redraw, no save, no re-layout); `sliderRow` uses a plain
  black-dot thumb (no animated platform thumb, no split track); `prompt()` hides the caret until the field is touched
  (`inkCursor`).
- `ui/kit/InkNumPad.kt` (EXTRAS_NAV, not frozen): numeric entry (page, %, 화) in a dialog with a big number label, a
  3×4 grid of 56dp buttons (1–9, ⌫, 0, 이동) and an optional hint ("1–540"); pure state in `NumPadState` (digits,
  max length, clamp). The system keyboard slides in and resizes the dialog window (several full e-ink updates); the pad
  costs one small update per digit. Text input (Hangul) keeps the system IME.
- `ui/kit/InkPager.kt` (EXTRAS_NAV, not frozen): `ListView.inkPaging(bar)` pages a list a screen at a time (a page =
  visible rows − 1, `setSelection(first ± page)`); a vertical drag or fling beyond touch slop is consumed and becomes
  exactly one page jump on `ACTION_UP` (no scroll frames); taps reach the rows; the "3 / 27" indicator is updated right
  after each `setSelection` (which never raises `onScrollStateChanged(IDLE)`). Volume / learned page keys page too.
- `res/values-v31/themes.xml`: the API 31+ splash screen is plain white without an icon (`AppTheme` extends
  `Base.AppTheme`; `ReaderTheme` inherits it). LibraryActivity and ReaderActivity remove the splash view without an
  exit animation.

### Debug timing logs (RAPerf)

`reader/PageView.kt` `ReaderPerf` logs under the tag `RAPerf`: "open <id>: first page N ms" (from `startOpen` to the end
of the first page's draw, always, INFO) and "turn N ms" (from the input event to the end of the turned page's
`onDraw`) only when enabled before the app starts:

```
adb shell setprop log.tag.RAPerf DEBUG
adb shell am force-stop com.ggumtak.readeraplus
adb logcat -s RAPerf
```

The release gates compare these numbers with the Wave 0 baseline recorded on the Comet: the cached reopen of the
14.8 MB TXT within +10 ms, page-turn time unchanged, library cold start (`am start -W`) within +5 %.


## Contract revision R3 (2026-10-02)

- **Chrome / insets:** reader bars and the return chip overlay the page. Pinned chrome is removed; page view size
  depends only on InsetsGate-approved system insets. Popups/dialogs preserve the underlying geometry.
- **Status / margins:** six `StatusItem` slots and a progress lane. Until 2026-10-05 they drew inside the margins
  (text box = view minus margins). Since then (user: "위 여백은 위 아래 애들을 제외하고 본문영역에서만 계산해야지")
  each band has its own place at its screen edge (`StatusBands`: whole dp from the settings only, never from what is
  on screen), and the text box = view minus a display cutout's band, the bands and the margins: the 위·아래 여백 are
  the paper between a band and the text, 0 puts the text right under the header, and no margin hides or shrinks a band
  (no '가려짐' note). Another item in a slot repaints; a band that comes, goes or changes height relays out anchored
  (`LayoutKeys.bandsChanged`). Defaults keep the text box 40 dp from the edges (sides 20 dp, MaruViewer; top 18 dp
  under the 22 dp header band, bottom 22 dp over the 18 dp progress line; a fullscreen camera band is left out like a
  system bar, the header centred between it and the text box as before). Model/renderer reuse buffers; redraw only
  for a changed visible value or changed dot pixel. `footerEpisode`/`footerTimeLeft` become typed slots.
- **Pagination:** `PageBreakMode.LINE` preserves the golden output; PARAGRAPH keeps a whole paragraph when it fits.
  Relayout opens an anchored generation so the first character stays; its changed section is masked from saved
  counts. Scroll stitches `PageInfo.lead` and real line bodies; it never uses page-bottom blank space.
- **Scroll:** STEP is the e-ink default, one release/command = one draw. SMOOTH is live finger movement on phones.
  All page commands, including SMOOTH mode, are immediate with no page-turn animation. Frame updates render only;
  the settle pipeline updates anchor, decor, position and cadence once per gesture.
- **Notes:** schema v3 adds note places, styles, lookups, missing/review times and return marks. Indexes (including
  partial predicates) never reference columns added by ALTER. Notes hub reads SQL only, never a book file.
  Primitive one-shot jumps use `NoteSig` + fraction fallback and anchor matching; peek does not save progress until
  a manual turn. `Library.notesGen` invalidates hub caches. Quote looks are colour or level-exact e-ink patterns;
  library/hub lists use measured immediate paging. No synchronization service is introduced.
- **Recents:** ResumeState marker plus exact Activity state restores the book. Intentional close clears it;
  temporary UI_HIDDEN leaves image caches intact. A newer DB/TTS position takes precedence over old instance state.
- **Brightness:** LightController selects window/device paths with a per-firmware verdict and explicit device
  opt-in. DeviceLight owns a serial IO writer and original-value restore policy. Local decisions never travel in
  JSON backups. Probes and writes start after the first page.

Source API ownership and thread contracts are in `R3_INTERFACES.md`; the merged implementation order is
`next/wave2/PLAN.md`. Phase 0 contains deliberately inert W1 stubs, not completed product features.
