# 독서 노트 (notes hub) + 단어장 (lookup history): design spec (task #19)

Status: design only, written read-only against the working tree of 2026-09-30 (R2 uncommitted; `LibrarySchema`
`DB_VERSION = 2` in the tree, 1 at HEAD). Paths are relative to `app/src/main/java/com/ggumtak/readeraplus/`.
Sibling specs: `../ui/chrome.md` (return point / pin, §4), `../scroll/*` (auto backup), and whatever quote-colour
spec lands next to this file (`_contrast.py` is its palette check). Where they define the same thing, they win.
The only shared points are the return mechanism (§7.4), the `quotes.style` ids (§3.6) and the schema version (§3.1).

The user's request, verbatim intent: "모든 책의 인용문·메모·북마크·리뷰를 한 곳에 모으는 기능은 꼭" (must have), and add
whatever else from ReadEra Premium is worth it, "책 로딩·불러오기는 최대한 그대로 빨라야" (opening a book must stay as
fast as it is). The ⋮ bug from the same message is §14.

---

## 0. Decisions at a glance

| # | Question | Decision |
|---|---|---|
| 1 | Name | **독서 노트** (drawer, toolbar, menus). Its word tab is **단어**, the drawer shortcut to it **단어장**. |
| 2 | Where | New `ui/notes/NotesActivity` (its own class, never loaded by a library cold start or a book open). Drawer rows 독서 노트 / 단어장, book menu "독서 노트", reader overflow "독서 노트", links in the TOC dialog's 북마크 / 인용문 tabs. Not a `Shelf` (shelves list books, the hub lists notes). |
| 3 | Tabs | **전체 · 인용문 · 메모 · 북마크 · 리뷰 · 단어**: 6 equal cells of 60 dp on a 360 dp screen. 메모 = quotes and bookmarks that carry a note. |
| 4 | Order / grouping | 최신순 (date headers) · 오래된 순 · 책별(최근 읽은 책 먼저) · 책별(제목순). The group header is part of the row view (no view types, no header lookups). |
| 5 | Filters | Book (chip + chooser), text search (all tabs), colour (인용문 tab only). |
| 6 | Paging | e-ink paged list (`ListView.inkPaging`, T1-1). Windowed adapter: `getCount()` = total from the count query; rows come in pages of 50 by **row index** (LIMIT/OFFSET, random access for page jumps), two-phase (narrow keys, then details by primary key), 6 pages cached. |
| 7 | Position shown | Chapter title + % (stored at creation, §3.3). **No page numbers**: they depend on font, margins and layout, which the hub must not compute (no book file is ever opened by the hub). |
| 8 | Tap a note | Opens the book **at the note**, through a new `ReaderActivity` jump contract (§7): the jump replaces the start position (zero extra work before the first page), the saved reading position becomes the return point, and the position is not overwritten until the first page turn ("peek"). |
| 9 | Schema | **v3**: new `lookups` table; `quotes` / `bookmarks` gain `chapter`, `frac`, `sig`; `books` gains `review_at`; 4 indexes. No index on an added column (§3.1, a real upgrade hazard). One bump shared with any other R3 schema work (e.g. `book_prefs.return_mark` if it misses v2). |
| 10 | Dictionary | Every 사전·번역 / 웹 검색 pick is recorded (word, book, section, offsets, sentence, chapter, %, app, time); toggle "찾아본 단어 기록" (default on, local only). Same selection within 10 min updates the row instead of adding one. |
| 11 | Export | Multi-select (or the whole current list) → Markdown or TXT through `ACTION_CREATE_DOCUMENT`; exact formats in §10; share as TXT (≤ 200,000 chars). |
| 12 | Bookmark title? | **No new column.** Bookmarks already have `note` (v1, edited in the TOC dialog). The hub shows the note's first line as the bookmark's title, else its chapter. |
| 13 | Keep notes | A vanished file whose book has notes goes to 휴지통 instead of being dropped with its notes; delete / empty-trash dialogs say how many notes go with it (§12). |
| 14 | ⋮ bug | Root cause: the library list/grid's **always-visible platform fast scroller** grabs every touch that starts within 48 dp of the right edge and jumps the list to the finger's height. The ⋮ sits at 301–348 dp on a 360 dp screen. Fix: the scroller only gets touches that start on its 14 dp strip (§14). |
| 15 | Cost to opening a book / cold start / page turn | **0 / 0 / 0.** The jump replaces the saved position, jump extras are 6 `getExtra` calls, the drawer count is one COUNT in the drawer's existing lazy IO job, per turn only an O(1) map removal and a boolean check. |

---

## 1. Facts found in the code (what the design builds on)

- `quotes(id, book_id, section, start_offset, end_offset, quote_text, note, created_at, style)`: `style` came with v2
  and is not mapped by `Quote` yet ("deferred to R3", R2_INTERFACES §2). `quote_text` may be up to
  `DataLimits.QUOTE` = 100,000 chars (≈ 300 KB), `note` up to 20,000. **A list query must never select them whole.**
  Fifty full rows can overflow the 2 MB CursorWindow.
- `bookmarks(id, book_id, section, char_offset, snippet ≤ 500, note, created_at)`: a bookmark **already has a note**
  (`Library.updateBookmarkNote`, and "메모 편집" in `ContentsDialog.bookmarkMenu`).
- `books.review` exists, but there is **no review timestamp**. Reviews are written by `ReaderPanels.showReview`, the end
  panel and `Backup.import`.
- Indexes today are `quotes_book` and `bookmarks_book`. There is no index on `created_at`.
- Quotes are created in `SelectionController.saveQuote` (from 인용 and 메모), bookmarks in `ReaderActivity.toggleBookmark`
  (a page-start snippet). The quote list is read on the open path only after the first page (`reloadAnnotations` in
  `afterOpen`), which also fills `QuoteCache`.
- 사전·번역 goes through `TextActions.lookUp(activity, text)` (`reader/extras/ExtrasUi.kt:364`): a chooser of
  `PROCESS_TEXT` apps plus "웹 검색". 웹 검색 is `TextActions.webSearch`. **Nothing is recorded today.**
- `ReaderActivity.open(context, bookId)` is the only entry and has no position. `onNewIntent` returns early for the book
  that is already open (`ReaderActivity.kt:375`). Jumps inside the reader use `goTo(pos, remember = true)` →
  `pushReturn` (the chip; replaced by `ReturnPoints.jumped` in chrome.md §4).
- TXT coordinates depend on parse options. The reader already remaps its own reading position through
  `TextPositions` (signature + char fraction, `ReaderMath.kt:338`). Quotes and bookmarks have no such protection
  today.
- `FileScanner.plan` drops vanished entries **together with their quotes and bookmarks** (`gone`, `FileScanner.kt:314`).
  Only the "folder excluded" case checks `SELECT_IDS_WITH_USER_DATA`.
- The library `ListView` and `GridView` both set `isFastScrollEnabled = true` and `isFastScrollAlwaysVisible = true`
  (`ui/library/LibraryActivity.kt:428-430, 453-454`) under `Theme.Material.Light` (a track drawable, so the whole
  height counts). The card's ⋮ is `LibraryViews.kt:201`.
- `ui/kit/InkPager.kt` (`ListView.inkPaging(bar)`) is specified (ARCHITECTURE "UI kit (R2)") but not in the tree
  yet. The hub needs it (§8.9).

---

## 2. Scope

In: the notes hub (all quotes, memos, bookmarks, reviews of all books), the word history (단어 tab + recording), the jump
contract, schema v3 + backup v3 fields, exports, keeping notes when files vanish, and the library ⋮ fix. Also the minimum
the hub needs from quote colours (`style` ids, labels, swatch, filter, change).

Out: how quote colours are drawn on the page (quote-colour spec / RENDER T2-3), sync (§15), page thumbnails (§15).

---

## 3. Data: schema v3

### 3.1 Version and the index hazard

`LibrarySchema.DB_VERSION = 3`. R2 lands before this is built, and CI builds install over each other on the device
(stable signing), so a device may already hold a v2 file. **If no v2 build has reached a device when this lands, fold
everything into v2 instead**: same statements, `AddedColumn(…, version = 2, …)`, `DB_VERSION` stays 2. The guarded
ALTERs make both paths safe. Coordinate with chrome.md §4.3 (`book_prefs.return_mark`): one bump per release.

**Hazard (must be tested).** `LibraryDb.onUpgrade` runs `CREATE_ALL` (tables **and indexes**) *before*
`upgradeStatements` (the ALTERs). An index on a column that an ALTER adds (e.g. `books(review_at)`) would fail on a
v2 file: "no such column", and the whole upgrade transaction would roll back, so the app could not open its library.
Rule: **no index in `CREATE_INDEXES` may name a column listed in `ADDED_COLUMNS`.** `LibrarySchemaV3Test` asserts this
(parse the `ON t(col…)` lists). This design needs no such index: reviews are few, so their arm scans `books`.

### 3.2 SQL (fresh databases: `CREATE_*`; upgrades: `ADDED_COLUMNS`)

```sql
-- quotes (fresh v3; v1/v2 files get the last three by ALTER)
CREATE TABLE IF NOT EXISTS quotes(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER NOT NULL,
  section INTEGER NOT NULL DEFAULT 0,
  start_offset INTEGER NOT NULL DEFAULT 0,
  end_offset INTEGER NOT NULL DEFAULT 0,
  quote_text TEXT NOT NULL DEFAULT '',
  note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT 0,
  style INTEGER NOT NULL DEFAULT 0,
  chapter TEXT NOT NULL DEFAULT '',     -- v3: chapter title at creation ('' = none / unknown)
  frac REAL NOT NULL DEFAULT -1,        -- v3: char fraction of the book at start_offset, 0..1; -1 = unknown
  sig TEXT NOT NULL DEFAULT '')         -- v3: LayoutKeys.textSignature of the parse (TXT); '' = EPUB / unknown

-- bookmarks (fresh v3)
CREATE TABLE IF NOT EXISTS bookmarks(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER NOT NULL,
  section INTEGER NOT NULL DEFAULT 0,
  char_offset INTEGER NOT NULL DEFAULT 0,
  snippet TEXT NOT NULL DEFAULT '',
  note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT 0,
  chapter TEXT NOT NULL DEFAULT '',
  frac REAL NOT NULL DEFAULT -1,
  sig TEXT NOT NULL DEFAULT '')

-- books: one column appended after meta_locked
  review_at INTEGER NOT NULL DEFAULT 0   -- v3: last time the review was written; 0 = unknown (older reviews)

-- new table
CREATE TABLE IF NOT EXISTS lookups(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER NOT NULL,
  word TEXT NOT NULL DEFAULT '',        -- the selection, trimmed, ≤ 200 chars
  word_key TEXT NOT NULL DEFAULT '',    -- LookupWords.key(word): grouping / "3회"
  section INTEGER NOT NULL DEFAULT 0,
  start_offset INTEGER NOT NULL DEFAULT 0,
  end_offset INTEGER NOT NULL DEFAULT 0,
  context TEXT NOT NULL DEFAULT '',     -- the sentence around it, ≤ 300 chars (LookupContext)
  chapter TEXT NOT NULL DEFAULT '',
  frac REAL NOT NULL DEFAULT -1,
  sig TEXT NOT NULL DEFAULT '',
  via INTEGER NOT NULL DEFAULT 0,       -- 0 사전·번역 app, 1 웹 검색, 2 web fallback (no app installed)
  app TEXT NOT NULL DEFAULT '',         -- app label ("파파고") or the search site host ("search.naver.com")
  note TEXT NOT NULL DEFAULT '',        -- "뜻 메모" the user writes in the hub
  created_at INTEGER NOT NULL DEFAULT 0)
```

`ADDED_COLUMNS` gains (all version 3; each skipped when `PRAGMA table_info` already lists the column):

```sql
ALTER TABLE quotes ADD COLUMN chapter TEXT NOT NULL DEFAULT ''
ALTER TABLE quotes ADD COLUMN frac REAL NOT NULL DEFAULT -1
ALTER TABLE quotes ADD COLUMN sig TEXT NOT NULL DEFAULT ''
ALTER TABLE bookmarks ADD COLUMN chapter TEXT NOT NULL DEFAULT ''
ALTER TABLE bookmarks ADD COLUMN frac REAL NOT NULL DEFAULT -1
ALTER TABLE bookmarks ADD COLUMN sig TEXT NOT NULL DEFAULT ''
ALTER TABLE books ADD COLUMN review_at INTEGER NOT NULL DEFAULT 0
```

`CREATE_INDEXES` gains (only columns that exist since v1, or columns of the new table):

```sql
CREATE INDEX IF NOT EXISTS quotes_created ON quotes(created_at)
CREATE INDEX IF NOT EXISTS bookmarks_created ON bookmarks(created_at)
CREATE INDEX IF NOT EXISTS lookups_created ON lookups(created_at)
CREATE INDEX IF NOT EXISTS lookups_book ON lookups(book_id)
CREATE INDEX IF NOT EXISTS lookups_word ON lookups(word_key)
```

`CREATE_ALL` = … + `CREATE_LOOKUPS` (before the indexes). Everything is SQLite 3.18-safe: no UPSERT, no window
functions, no RETURNING. Building the two new `created_at` indexes on upgrade costs about 20 ms per 10,000 rows, once.

### 3.3 Invariants and meaning

- `frac` is `BookSession.counts.charProgress(section, offset)` at creation (approximate for EPUB until sections are
  converted: good enough for "37%" and as a TXT fallback). `-1` = unknown (rows from before v3, restored rows).
- `sig` is `LayoutKeys.textSignature(settings, format, encoding)` of the parse that produced the coordinates. It is
  `''` for EPUB (spine coordinates are stable) and for rows whose parse is unknown. It lets a jump detect that a TXT
  was re-parsed (chapter detection, replace rules, encoding, TXT override) since the note was made (§7.3).
- `chapter` is the title of `ChapterIndex.indexAt(section, offset)`, cleaned (`MetaInfo.clean`, ≤ 200 chars); `''` when
  the book has no TOC.
- **Backfill** (READER_A, after the first page): when `reloadAnnotations` has loaded the open book's quotes and
  bookmarks, rows with `frac < 0` get `chapter` and `frac` from the live session. This is one binary search per row on
  main, and one `Library.fillNotePlaces` transaction on `ReaderIo`, once per row ever. `sig` stays `''` for backfilled
  rows: their parse is unknown, and the anchor check (§7.5) covers them.
- `review_at` is set by `Library.setReview` (`now`, or 0 when the review is cleared). The hub sorts reviews by
  `review_at`, falling back to `last_read_at` when it is 0.
- Deleting a book (`Library.deleteBookRows`) also runs `DELETE FROM lookups WHERE book_id = ?`.

### 3.4 `data/Models.kt` (frozen → contract R3)

```kotlin
data class Quote(
    val id: Long, val bookId: Long, val section: Int, val start: Int, val end: Int,
    val text: String, val note: String = "", val createdAt: Long,
    val style: Int = 0,                 // R3 (column since v2)
    val chapter: String = "", val frac: Float = -1f, val sig: String = "",
)
data class Bookmark(
    val id: Long, val bookId: Long, val section: Int, val offset: Int, val snippet: String, val createdAt: Long,
    val note: String = "", val chapter: String = "", val frac: Float = -1f, val sig: String = "",
)

/** Where a note sits, for screens that cannot lay the book out (the notes hub). Built by the reader at creation. */
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
    val tab: NotesTab = NotesTab.ALL,
    val order: NotesOrder = NotesOrder.NEWEST,
    val bookId: Long? = null,
    val text: String = "",
    val style: Int? = null,
    /** WORDS tab: one row per word (its latest lookup) instead of every lookup. */
    val wordsOnce: Boolean = false,
)

/** Identity of one note row across the four tables. */
data class NoteRef(val kind: NoteKind, val id: Long) {
    fun packed(): Long = (kind.code.toLong() shl 56) or (id and 0x00FF_FFFF_FFFF_FFFFL)
    companion object { fun unpack(v: Long): NoteRef? }
}

/** One hub row. [body] ≤ Notes.BODY_CHARS (quote text, bookmark snippet, review, lookup sentence). */
data class NoteRow(
    val ref: NoteRef, val bookId: Long,
    val section: Int, val start: Int, val end: Int,
    val body: String, val bodyCut: Boolean,
    val note: String, val noteCut: Boolean,
    val word: String,            // LOOKUP only
    val wordCount: Int,          // LOOKUP: lookups of the same word_key (≥ 1)
    val style: Int,              // QUOTE only
    val via: Int, val app: String, // LOOKUP only
    val chapter: String, val frac: Float, val sig: String,
    val time: Long,
)

data class NoteBook(val id: Long, val title: String, val author: String, val path: String,
                    val trashed: Boolean, val lastReadAt: Long, val count: Int)

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

`DataLimits` gains `CHAPTER = 200`, `WORD = 200`, `CONTEXT = 300`, `APP = 100`.

### 3.5 Change counter

`Library.notesGen: Long` (`@Volatile`, in memory, starts at 0) is incremented by every write that changes what the hub
shows: add/delete/update of quotes, bookmarks, lookups and reviews, `setQuoteStyle`, `fillNotePlaces`,
`deleteBookRows` (remove, empty trash, scanner drops), `Backup.import`, `resetProgress` is not included (it touches no
notes). Every cache in §4.6 is keyed by it. The hub compares it in `onResume`, so nothing reloads when nothing changed.

### 3.6 Quote colours (the minimum the hub needs)

Ids and palette belong to the quote-colour spec. The hub only needs this object (RENDER or EXTRAS_TOOLS, pure +
one drawable):

```kotlin
object QuoteStyles {
    const val COUNT: Int                  // 6 if the palette in _contrast.py is adopted
    fun label(style: Int): String         // 0 "기본(회색)", 1 "노랑", 2 "초록", 3 "파랑", 4 "빨강", 5 "보라" (assumed)
    fun swatch(ctx: Context, style: Int, sizeDp: Int = 14): Drawable   // the look on white paper (the hub is never inverted)
    fun clamp(style: Int): Int            // unknown ids → 0
}
```
If the colour spec is not ready, `COUNT = 1`: the hub hides the colour chip and "색 변경", and everything else works.

---

## 4. Queries, indexes, paging, caching

All builders live in pure `data/NotesSql.kt` (JVM-tested like `LibrarySql`). They return `SqlQuery(sql, args)`, with
arguments in placeholder order. `Notes` (`data/Notes.kt`) runs them. Every call is blocking, on IO, and thread-safe.

### 4.1 The four arms (narrow key form)

Each tab is a UNION ALL of **arms**. Each arm yields `(k, id, b, t, s, o)` = kind code, row id, book id, sort time,
section, offset:

| Arm | SELECT | Base WHERE |
|---|---|---|
| Q (quotes) | `SELECT 1 AS k, q.id AS id, q.book_id AS b, q.created_at AS t, q.section AS s, q.start_offset AS o FROM quotes q` | `1` |
| QM (memo quotes) | same as Q | `q.note <> ''` |
| M (bookmarks) | `SELECT 2, m.id, m.book_id, m.created_at, m.section, m.char_offset FROM bookmarks m` | `1` |
| MM (memo bookmarks) | same as M | `m.note <> ''` |
| R (reviews) | `SELECT 3, b.id, b.id, CASE WHEN b.review_at > 0 THEN b.review_at ELSE b.last_read_at END, -1, -1 FROM books b` | `b.review <> ''` |
| L (lookups) | `SELECT 4, l.id, l.book_id, l.created_at, l.section, l.start_offset FROM lookups l` | `1` |
| L1 (one per word) | `SELECT 4, MAX(l.id), MAX(l.book_id), MAX(l.created_at), 0, 0 FROM lookups l` … `GROUP BY l.word_key` | `1` |

Tabs: ALL = Q, M, R, L · QUOTES = Q · MEMOS = QM, MM · BOOKMARKS = M · REVIEWS = R · WORDS = L (or L1 when
`wordsOnce`). In L1, MAX(book_id) is only a placeholder. The detail query reads the real book of row MAX(id).

Filters are appended to every arm's WHERE (the arm alias differs):
- book: `AND q.book_id = ?` (R: `AND b.id = ?`)
- colour (Q only): `AND q.style = ?`
- search, per token `p = '%' + LibrarySql.escapeLike(token) + '%'` (tokens from `LibrarySql.searchTokens`, at most 8):
  - Q/QM: `AND (q.quote_text LIKE ? ESCAPE '\' OR q.note LIKE ? ESCAPE '\' OR q.book_id IN (SELECT id FROM books WHERE title LIKE ? ESCAPE '\'))`
  - M/MM: the same shape over `m.snippet`, `m.note`, title
  - R: `AND (b.review LIKE ? ESCAPE '\' OR b.title LIKE ? ESCAPE '\')`
  - L/L1: `AND (l.word LIKE ? ESCAPE '\' OR l.context LIKE ? ESCAPE '\' OR l.note LIKE ? ESCAPE '\' OR l.book_id IN (SELECT id FROM books WHERE title LIKE ? ESCAPE '\'))`

Trashed books are **not** excluded: their notes stay visible, marked "휴지통" (§12).

### 4.2 Page keys: date orders (NEWEST / OLDEST)

```sql
<arm 1> UNION ALL <arm 2> …
ORDER BY t DESC, k, id DESC          -- OLDEST: t ASC, k, id ASC
LIMIT ? OFFSET ?
```
The order is total (k disambiguates ids across tables). OFFSET gives random access, which the windowed adapter
(§4.5) needs for InkPager and page-number jumps. The rows are narrow (six integers), so even when SQLite sorts the
whole union, the sorter holds a few hundred KB for 10,000 notes. A single-arm tab (QUOTES, BOOKMARKS, WORDS) is
satisfied by the `*_created` index with no sort: `EXPLAIN QUERY PLAN` must not show "USE TEMP B-TREE FOR ORDER BY"
(test, §18).

For the date headers, page `p` asks for `LIMIT 51 OFFSET 50p − 1` (p > 0): the extra first row is the previous
page's last row, whose day decides whether the first row starts a new day.

### 4.3 Page keys: book orders (BOOK_RECENT / BOOK_TITLE)

1. **Books with notes** under the query, which also feeds the book chooser:
   ```sql
   SELECT bk.id, bk.title, bk.author, bk.path, bk.trashed, bk.last_read_at, n.c
   FROM (SELECT b AS bid, COUNT(*) AS c FROM (<arms, same filters>) GROUP BY b) n
   JOIN books bk ON bk.id = n.bid
   ```
   It is sorted in Kotlin: BOOK_TITLE → `NaturalOrder.compare(title)` ("2권" before "10권", which SQL can't do),
   BOOK_RECENT → `last_read_at DESC`, then natural title. The prefix sums of `c` map a row index to (book, inner
   offset): `BookSpans.locate`, pure and tested. L1 uses its own grouped form, so every word counts once.
2. **Rows of one book**, in reading order, review first:
   ```sql
   <arms with AND x.book_id = ?> ORDER BY s, o, k, id LIMIT ? OFFSET ?
   ```
   These use `quotes_book` / `bookmarks_book` / `lookups_book`. A page that crosses a book boundary runs this
   once per book (usually 1–2 queries). The first row of each book carries the book header.

### 4.4 Details (second phase)

At most four PK-indexed queries per page, with ids from the key rows (≤ 51 placeholders, far below SQLite's 999):

```sql
SELECT q.id, q.book_id, q.section, q.start_offset, q.end_offset,
       substr(q.quote_text, 1, 601), substr(q.note, 1, 401), q.style, q.chapter, q.frac, q.sig, q.created_at
FROM quotes q WHERE q.id IN (?, …)

SELECT m.id, m.book_id, m.section, m.char_offset, m.snippet, substr(m.note, 1, 401),
       m.chapter, m.frac, m.sig, m.created_at
FROM bookmarks m WHERE m.id IN (?, …)

SELECT b.id, substr(b.review, 1, 601), b.review_at, b.last_read_at, b.progress
FROM books b WHERE b.id IN (?, …)

SELECT l.id, l.book_id, l.word, l.section, l.start_offset, l.end_offset, l.context, substr(l.note, 1, 401),
       l.chapter, l.frac, l.sig, l.via, l.app, l.created_at,
       (SELECT COUNT(*) FROM lookups l2 WHERE l2.word_key = l.word_key)
FROM lookups l WHERE l.id IN (?, …)
```

`bodyCut` = the substring came back with 601 chars (the shown body is cut to 600). No `length()` is used. Rows are
re-ordered in Kotlin to the key order. Reviews use `frac = progress` (the "37%" of a review is where the reader got
to). Book titles come from the `NoteBook` map, loaded once per hub session and on every `notesGen` change: a
`SELECT id, title, author, path, trashed, last_read_at FROM books WHERE id IN (…)` for ids not in the map yet.

`Notes.fullText(ref)` reads the untruncated body and note of one row (copy, share, 전체 보기, edit, export).

### 4.5 Windowed adapter (UI side, `ui/notes/NotesAdapter.kt`)

- `getCount()` = `counts.of(tab)` under the query (§4.6). `getItem(i)` = the row if its page is loaded, else null.
- `PAGE_ROWS = 50`, `MAX_PAGES = 6` (LRU: ≈ 300 rows ≈ 300 KB). A missing row binds a 64 dp placeholder
  ("불러오는 중…", 14 sp grey) and asks for its page. On arrival, `notifyDataSetChanged()` runs only if a visible
  position is in that page.
- **Prefetch**: after each InkPager jump (its indicator callback), the pages of `first − 25` and `last + 25` are
  ensured. A normal page jump therefore never shows a placeholder. Only a numpad jump far away shows one placeholder
  frame (≈ 30 ms later the real rows).
- Race: when a loaded page has fewer rows than expected (a delete elsewhere), the adapter re-reads counts. `notesGen`
  has changed anyway.

### 4.6 Counts and caches

```sql
-- per-tab counts under the query's book / text / colour filters (one statement)
SELECT k, COUNT(*) FROM (
  <Q> UNION ALL <M> UNION ALL <R> UNION ALL <L or L1>
  UNION ALL SELECT 5 AS k, … <QM> UNION ALL SELECT 5, … <MM>
) GROUP BY k
```
The memo arms re-label their kind as 5, so `memos` comes back as its own row.

- `Notes.counts(q)` is cached by `(notesGen, bookId, text, style, wordsOnce)` (the tab and order don't change counts).
- `Notes.drawerCounts(): IntArray` = `[quotes + bookmarks + reviews, lookups]`:
  `SELECT (SELECT COUNT(*) FROM quotes) + (SELECT COUNT(*) FROM bookmarks) + (SELECT COUNT(*) FROM books WHERE review <> ''), (SELECT COUNT(*) FROM lookups)`,
  cached by `notesGen`. LibraryActivity calls it **inside its existing lazy `ensureCounts` IO job** (drawer open only).
  Library cold start doesn't change.
- `Notes.books(q)` is cached by `(notesGen, tab, text, style, wordsOnce)`.

### 4.7 Budget (Comet, A53, 10,000 notes over 500 books, measured with `RAPerf`-style logs under tag `RANotes`)

| Step | Target |
|---|---|
| Hub `onCreate` → first rows drawn | ≤ 150 ms (views ≈ 30, counts ≈ 15, keys ≈ 25, details ≈ 5, bind ≈ 20) |
| Page fetch (keys + details) | ≤ 40 ms, off main |
| Tab / order / filter change | ≤ 80 ms to the new first screen |
| Search (debounce 300 ms, previous job cancelled) | ≤ 150 ms for 10,000 notes with LIKE scans |
| Drawer open with counts | + 1 query ≈ 5 ms, on IO, once per `notesGen` |
| Book open via a note | = a normal open (same single layout) |

Full-text search index (FTS4) is **not** used. LIKE over a personal library is within budget, and FTS would add a
table, triggers, and writes on every quote. Revisit only if the search gate fails.

---

## 5. Data API (DATA owner)

`data/Library.kt`: appended defaulted parameters stay source-compatible with every caller.

```kotlin
fun addQuote(bookId: Long, section: Int, start: Int, end: Int, text: String, note: String = "",
             place: NotePlace? = null, style: Int = 0): Quote
fun addBookmark(bookId: Long, section: Int, offset: Int, snippet: String, place: NotePlace? = null, note: String = ""): Bookmark
fun setQuoteStyle(ids: Collection<Long>, style: Int)              // one transaction
fun deleteQuotes(ids: Collection<Long>)                           // one transaction
fun deleteBookmarks(ids: Collection<Long>)
fun clearReviews(bookIds: Collection<Long>)                       // review = '', review_at = 0
fun setReview(bookId: Long, text: String)                         // now also review_at = now (0 if blank)
/** Backfill (§3.3): only rows whose frac < 0 are touched. */
fun fillNotePlaces(bookId: Long, quotes: Map<Long, NotePlace>, bookmarks: Map<Long, NotePlace>)
@Volatile var notesGen: Long; internal set
```

`data/Lookups.kt` (new):

```kotlin
object Lookups {
    const val VIA_APP = 0; const val VIA_WEB = 1; const val VIA_WEB_FALLBACK = 2
    const val DEDUPE_MS = 10 * 60_000L
    /**
     * Records one lookup (IO). The same book + word_key + section + start within DEDUPE_MS refreshes that row
     * (created_at, via, app, context) instead of inserting. Returns the row id. Never throws for a missing book.
     */
    fun record(bookId: Long, word: String, section: Int, start: Int, end: Int, context: String,
               place: NotePlace?, via: Int, app: String, now: Long = System.currentTimeMillis()): Long
    fun setNote(id: Long, note: String)
    fun delete(ids: Collection<Long>)
    fun clearAll()                                  // "단어장 비우기" (settings)
    fun count(): Int
}

/** Pure. NFC, trim, strip surrounding quotes/brackets/punctuation (“”‘’"'「」『』()[]《》〈〉.,!?…·~), collapse
 *  whitespace, ASCII-lowercase, ≤ 100 chars. Korean particles are NOT stripped ("비명을" ≠ "비명"): no reliable rule. */
object LookupWords { fun key(word: String): String }
```

Dedupe SQL, then INSERT when it changed nothing (one transaction):
```sql
UPDATE lookups SET created_at = ?, via = ?, app = ?, context = ?
WHERE book_id = ? AND word_key = ? AND section = ? AND start_offset = ? AND created_at >= ?
INSERT INTO lookups(book_id, word, word_key, section, start_offset, end_offset, context, chapter, frac, sig, via, app, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
```

`data/Notes.kt` (new, read side + export):

```kotlin
object Notes {
    const val PAGE_ROWS = 50
    const val BODY_CHARS = 600
    const val NOTE_CHARS = 400
    fun counts(q: NotesQuery): NotesCounts
    fun books(q: NotesQuery): List<NoteBook>                       // sorted per q.order (title order when !byBook)
    fun page(q: NotesQuery, index: Int, books: List<NoteBook>?): NotesPage   // index = page number; books required when q.order.byBook
    fun refs(q: NotesQuery): LongArray                             // packed NoteRef of every row under q (select all / export list)
    fun fullText(ref: NoteRef): Pair<String, String>?              // (body, note), untruncated
    fun drawerCounts(): IntArray
    fun countForBooks(bookIds: Collection<Long>): Int              // delete / empty-trash warnings
    fun export(refs: LongArray?, q: NotesQuery, format: NotesExport.Format, out: java.io.Writer, now: Long): Int  // rows written
}
class NotesPage(val index: Int, val rows: List<NoteRow>, val before: NoteRow?, val firstOfBook: BooleanArray)
```

`LibrarySql` constants change with the columns: `INSERT_QUOTE` (+ style, chapter, frac, sig), `SELECT_QUOTES` /
`SELECT_ALL_QUOTES` (+ style, chapter, frac, sig → `BookRows.quote`), the bookmark equivalents, `SET_REVIEW`
(+ `review_at`), `UPDATE_QUOTE_PLACE` / `UPDATE_BOOKMARK_PLACE` (`… WHERE id = ? AND frac < 0`),
`DELETE_LOOKUPS_OF_BOOK`, and `SELECT_IDS_WITH_NOTES` (§12). `SELECT_IDS_WITH_USER_DATA` gains
`UNION SELECT book_id FROM lookups`.

---

## 6. Writers: where rows come from (reader side)

### 6.1 `NotePlaceHost` (frozen `reader/extras/ReaderPanels.kt`, contract R3; implemented by ReaderActivity)

```kotlin
/** Chapter title, char fraction and text signature of [pos] in the open book. Main thread, O(log chapters). */
interface NotePlaceHost { fun notePlace(pos: DocPosition): NotePlace }
```
ReaderActivity computes the signature once per session (after the first page, cached in a field), the chapter
through its `ChapterIndex`, and `frac` through `counts.charProgress`. It returns `NotePlace.UNKNOWN` while no session
exists.

### 6.2 Quotes and memos (EXTRAS_TOOLS, `SelectionController`)

`snapshot()` also takes `(host as? NotePlaceHost)?.notePlace(DocPosition(section, selStart))`. `saveQuote` passes it
to `Library.addQuote(…, place = snap.place)`. The default style is the last style the user chose (raw pref
`notes.lastStyle`, when the colour spec adds a picker). The popup and the toasts don't change.

### 6.3 Bookmarks (READER_A, `toggleBookmark`)

`Library.addBookmark(b.id, sec, off, snippet, place = notePlace(DocPosition(sec, off)))`. It is computed on main before
the IO launch.

### 6.4 Lookups (EXTRAS_TOOLS)

- `TextActions.lookUp(activity, text, onPicked: ((via: Int, app: String) -> Unit)? = null)`: `onPicked` runs when the
  user picks an app (`VIA_APP`, app label), "웹 검색" (`VIA_WEB`, the site host) or the no-app fallback
  (`VIA_WEB_FALLBACK`). It is not called when the chooser is cancelled.
- `TextActions.webSearch(ctx, text, onDone: (() -> Unit)? = null)`: called after `startActivity` succeeded.
- `SelectionController.lookUp()` / `webSearch()` take a `LookupSnapshot(bookId, word = selectedText().take(200),
  section, start, end, context, place)` **before** `clear()`. `context` = `LookupContext.sentence(layout.content.text,
  selStart, selEnd)`. The callback then runs `ReaderIo.launch { Lookups.record(…) }`, and only when
  `Settings.app.recordLookups`. There's no toast: recording is silent. The hub's "다시 찾기" calls
  `TextActions.lookUp(activity, word)` without `onPicked` (it adds no row).
- `reader/extras/LookupContext.kt` (new, pure):
  ```kotlin
  internal object LookupContext {
      const val MAX = 300
      /** The sentence around [start, end) of [text]: bounds from SentenceSplitter's terminal / closing rules and '\n',
       *  at most 150 chars each way (cut at a space, "…" added), OBJECT_CHAR removed, whitespace collapsed. */
      fun sentence(text: CharSequence, start: Int, end: Int, max: Int = MAX): String
  }
  ```
  The hub bolds the word inside the context by searching `word` in it (first hit; none → no bold).

### 6.5 Reviews

`Library.setReview` stamps `review_at`. Callers don't change.

---

## 7. Jump contract (READER_A): open a book at a note

### 7.1 API (`reader/ReaderJump.kt`, new; `ReaderActivity.open` gains a parameter)

```kotlin
/** A place to open a book at (notes hub). Extras are primitives so a recreated intent carries them. */
data class ReaderJump(
    val section: Int, val offset: Int,
    val end: Int = -1,          // > offset: mark [offset, end) (quote, word) until the next manual turn
    val frac: Float = -1f,      // 0..1 fallback when the coordinates don't fit this parse
    val sig: String = "",       // parse signature the coordinates belong to ('' = unknown)
    val anchor: String = "",    // ≤ 64 chars of text expected at offset (quote start, word, bookmark snippet)
) {
    fun put(i: Intent): Intent
    companion object {
        const val EXTRA_SECTION = "jump_section"; const val EXTRA_OFFSET = "jump_offset"; const val EXTRA_END = "jump_end"
        const val EXTRA_FRAC = "jump_frac"; const val EXTRA_SIG = "jump_sig"; const val EXTRA_ANCHOR = "jump_anchor"
        /** Null when the intent has no jump or the values are unusable (negative section, NaN). */
        fun from(i: Intent): ReaderJump?
        fun strip(i: Intent): Intent
        fun of(row: NoteRow): ReaderJump   // anchor: quote body / word / bookmark snippet, first 64 chars
    }
}

// ReaderActivity.companion
fun open(context: Context, bookId: Long, jump: ReaderJump? = null)
```

### 7.2 Open path (still "nothing new before the first page")

In `startOpen`, after the IO block and the `BookSession`, where `start` is computed today
(`ReaderActivity.kt:777-781`):

```
val jump = ReaderJump.from(intent)        // 6 getExtra calls, main thread
val saved = remap?.let { s.counts.locateFraction(it) } ?: DocPosition(b.posSection, b.posOffset)
val start = jump?.let { resolveJump(it, s, currentSig) } ?: saved
```
The same single `s.layout(sec)` follows, so the first page costs the same. When `jump != null`:
1. `setIntent(ReaderJump.strip(intent))`: a later recreation opens at the saved position, not the note again.
2. `jump.end > jump.offset` → `ownerHighlights[OWNER_JUMP] = listOf(Highlight(offset, end, HighlightKind.SEARCH))` for
   that section **before** `showPage`. The first frame already shows the mark: no second e-ink update. `userTurn`
   removes it next to `OWNER_SEARCH` (O(1)).
3. After `showPage`: `if (!onPage(saved)) returns.jumped(saved, …)` (chrome.md §4; today `pushReturn(saved)`): the
   return target is **where the user was reading**.
4. **Peek**: `peekUntilTurn = true` → `savePositionNow` / the scheduled save and `writeTextPosition` are skipped. The
   first manual turn, TTS start, auto-turn, a TOC/search/go-to jump, or using the return point clears it. If the user
   only looks at the quote and presses Back, the book's saved position and `last_read_at` stay as they were. Cost is
   one boolean check per save.

### 7.3 `resolveJump` (pure part in `ReaderJump`, tested)

```
TXT, currentSig != null, jump.sig != "" && jump.sig != currentSig, jump.frac >= 0  → counts.locateFraction(frac)
jump.section in 0 until sectionCount                                                  → DocPosition(section, offset) (offset clamped after layout)
jump.frac >= 0                                                                         → counts.locateFraction(frac)
else                                                                                   → null (saved position + toast "노트가 있던 곳을 찾지 못했습니다")
```

### 7.4 The same book already open (`onNewIntent`)

Today it returns early when `id == bookRef?.id`. New behaviour: with a jump, run
`goTo(resolveJump(…), remember = true)` plus the same mark and the anchor check, without reopening. Because
ReaderActivity is `singleTask`, a hub started from the reader is finished by this launch (the task is cleared above
the reader). A hub started from the library stays under the reader, so Back returns to the hub.

### 7.5 Anchor check (after the first page, O(anchor))

In `afterOpen` (and after the §7.4 `goTo`): compare `anchor` with the section text at the target, ignoring whitespace
and `OBJECT_CHAR` (`JumpAnchor.matches(text, offset, anchor)`, pure, up to the first 24 non-space chars). If it matches,
nothing happens: the common case, one `regionMatches`-like loop over in-memory text. If not, the anchor is searched on
`Dispatchers.Default` in `section ± 3`, then in the whole document (cancellable, stops at the first hit, same scanning
as `SearchPanel`). Found → `goTo(found, remember = false)`, the mark moves, toast "노트 위치를 다시 찾았습니다". Not found →
toast "노트가 있던 곳을 찾지 못해 가까운 위치를 열었습니다". A found position may be written back to the row
(`Library.fillNotePlaces`-like update of `section/offset/sig`): **optional, v2 of this feature**, because it rewrites
user data.

---

## 8. Hub UI (`ui/notes/`, new owner NOTES_UI; LIBRARY may own it)

Files: `NotesActivity.kt` (screen and state), `NotesAdapter.kt` (window + holders), `NotesMenus.kt` (row menus,
selection, export flow), `NotesText.kt` (pure labels, tested).

```kotlin
class NotesActivity : Activity() {
    companion object {
        const val EXTRA_TAB = "notes_tab"          // NotesTab.name
        const val EXTRA_BOOK_ID = "notes_book"     // Long; filter to one book
        fun open(context: Context, tab: NotesTab? = null, bookId: Long = -1L)
    }
}
```
Manifest: `<activity android:name=".ui.notes.NotesActivity" android:exported="false" />` (theme `AppTheme`: white,
no transitions).

### 8.1 Screen (360 × 720 dp)

```
┌────────────────────────────────────────────┐
│ ←   독서 노트                        🔍   ⋮ │ toolbar 56 dp: back 48, title 20 sp bold, search 48, more 48
├────────────────────────────────────────────┤ 1 px
│ 전체 │인용문│ 메모 │북마크│ 리뷰 │ 단어   │ tabs 48 dp: 6 × 60 dp, 15 sp; selected bold + 3 dp × 40 dp underline
├────────────────────────────────────────────┤ 1 px
│ [모든 책 ▾]  [최신순 ▾]  [모든 색 ▾]        │ filter row 44 dp: chips 32 dp high, 14 sp, 12 dp h-padding,
├────────────────────────────────────────────┤   1 px border radius 16 dp, 8 dp gaps; book chip max 150 dp (…)
│ 오늘 · 9월 30일 (화)                         │ group header 32 dp, 14 sp bold, 16 dp left, 1 px line below
│ “그 순간, 멀리서 기차가 지나가는 소리가     ⋮│ body 16 sp, line spacing 1.2, ≤ 4 lines
│ 들려왔다. 호수 위로 물안개가 피어오르자      │
│ 세상이 조용해졌다.”                          │
│ 메모  이 장면을 다시 읽어 볼 것               │ note 14 sp (label "메모" bold), ≤ 3 lines
│ ▣ 인용문 · 《리더플러스 샘플》 · 1화 · 3% · 21:04│ meta 13 sp grey, 1 line; ▣ = 14 dp style swatch (quotes)
├────────────────────────────────────────────┤ 1 px
│ 🔖 3화 겨울                                ⋮│ bookmark: 16 dp icon + title 16 sp (note line 1 bold, else chapter)
│ 새벽 공기는 생각보다 차가웠고, 창문 너머로  │ snippet 15 sp grey, ≤ 2 lines
│ 북마크 · 《big-cp949》 · 41% · 어제 20:10     │
├────────────────────────────────────────────┤
│ 비명                                3회  文 │ word 18 sp bold (1 line) · "3회" 12 sp grey · [다시 찾기] 48 dp
│ …타인의 비명이 퍼졌다. 튕긴 총알이…       ⋮│ sentence 15 sp, word bold, ≤ 3 lines · ⋮ 48 dp under it
│ 뜻  외마디 소리                              │ note 14 sp
│ 단어 · 《먼치킨 대마법사…》 · 12화 · 37% · 파파고 · 9월 28일│
├────────────────────────────────────────────┤
│    ‹           2 / 14 쪽 · 128개           › │ page bar 44 dp (InkPager): 48 dp buttons, 14 sp centre (tap → InkNumPad)
└────────────────────────────────────────────┘
```

- Row: `pressableBackground()`, padding 16 dp left, 12 dp top/bottom, 0 right. The content column has weight 1. The
  right column is 48 dp wide with ⋮ (48 × 48, 24 dp icon, top-aligned). Word rows put [다시 찾기] (`ic_translate`) above
  ⋮. 1 px hairline under each row.
- The kind label ("인용문 · ") starts the meta line in the 전체 tab only. The book part is left out when the list is
  filtered to one book or grouped by book. A trashed book shows as "《제목》(휴지통)".
- 메모 tab: the note is the body (16 sp, ≤ 4 lines). The quoted text follows as secondary (14 sp grey, “…”, ≤ 2 lines).
- 리뷰 row: "리뷰" tag 13 sp bold + book title 16 sp bold (1 line), review 15 sp (≤ 6 lines), meta
  "리뷰 · 57% · 9월 12일".
- Search row (toggled by 🔍, like the library's): 52 dp, `EditText` 17 sp, hint "인용문·메모·단어·책 제목 검색",
  debounce 300 ms, `inkCursor(singleLine = true)`, [✕] clears, and a second tap closes.
- Colour chip: 인용문 tab only, and only when `QuoteStyles.COUNT > 1`.
- Meta time (`NotesText.time`): today "21:04", yesterday "어제 21:04", this year "9월 28일", older "2025.12.03".
  Day headers: "오늘 · 9월 30일 (화)", "어제 · 9월 29일 (월)", "9월 28일 (일)", "2025년 12월 3일 (수)". Book headers:
  "《제목》 · 12" (the count comes from `NoteBook.count`).
- Place (`NotesText.place(chapter, frac)`): "12화 과거로 · 37%" (chapter ellipsized at 24 chars); "37%" without a
  chapter; the chapter alone when `frac < 0`; nothing when both are unknown. Percent = `floor(frac × 100)`, "0%" allowed.

### 8.2 Interactions

| Gesture | Quote / memo | Bookmark | Review | Word |
|---|---|---|---|---|
| tap | open the book at it (§7) | open at it | review editor | open at its sentence |
| ⋮ / long press | menu ↓ | menu ↓ | menu ↓ | menu ↓ |
| menu | 책에서 보기 · 전체 보기 · 복사 · 공유 · 메모 편집 · 색 변경 · 선택 · 삭제 | 책에서 보기 · 메모 편집 · 복사 · 공유 · 선택 · 삭제 | 리뷰 편집 · 책 열기 · 복사 · 공유 · 선택 · 리뷰 지우기 | 다시 찾기 · 문맥 보기 · 웹 검색 · 뜻 메모 · 복사 · 선택 · 삭제 |

- 책에서 보기 / 문맥 보기 / tap: on IO, `File(book.path).isFile`. If it's missing: toast "책 파일을 찾을 수 없습니다 (노트는
  남아 있습니다)". Otherwise `ReaderActivity.open(ctx, bookId, ReaderJump.of(row))`.
- 전체 보기 (shown when `bodyCut` or the body is over 4 lines): an `alert()` with a `ScrollView` of the full text
  (16 sp, line spacing 1.25, loaded by `Notes.fullText`) and buttons [복사] [공유] [닫기].
- 메모 편집 / 뜻 메모 / 리뷰 편집: `multilinePrompt` (existing internal helper in `reader/extras/ExtrasUi.kt`) with the full
  text → `Library.updateQuoteNote` / `updateBookmarkNote` / `Lookups.setNote` / `setReview` on IO. The row reloads
  through `notesGen`.
- 색 변경: a chooser of `QuoteStyles` labels, each row with its swatch → `Library.setQuoteStyle`.
- 공유 (one row): quote → the existing `ContentsDialog.quoteShareText` format ("“…”\n메모: …\n— 제목, 작가"); bookmark →
  "제목 · 12화 · 37%\n“snippet”\n메모: …"; word → "비명 — “sentence” (제목)".
- 삭제: `confirm` (§20 wording), then `Notes`/`Library` delete on IO, toast "삭제했습니다". The reader's quote highlights
  and `QuoteCache` refresh by themselves: `ReaderActivity.onResume` → `reloadAnnotations` (> 1 s since the last load).
- Book header tap (by-book orders): filter to that book (a chip appears).

### 8.3 Multi-select

Enter it from a row menu's "선택" or the overflow's "여러 개 선택". Tapping a row toggles it; long press toggles too.
Back / ✕ leaves selection mode.

```
│ ✕   12개 선택                 ⤴   ⇪   🗑   ⋮ │ 56 dp: close 48, title 18 sp bold, 공유, 내보내기 (ic_upload), 삭제, ⋮
```
- Rows show `ic_check_box` / `ic_check_box_outline_blank` (24 dp) in a 40 dp column before the content. Entering and
  leaving re-binds visible rows only.
- ⋮: 모두 선택 (`Notes.refs(q)` on IO: every row under the query, not just the loaded ones) · 색 변경 (applies to the
  selected quotes; toast "인용문 5개의 색을 바꿨습니다") · 선택 해제.
- Selection = a `LongHashSet` of `NoteRef.packed()`. It survives paging and tab switches, and is cleared by a query
  change that hides rows ("필터를 바꾸면 선택이 풀립니다" is not needed: the filter chips are disabled in selection mode).
- 삭제: `confirm("노트 삭제", "선택한 N개를 삭제할까요?${if (reviews > 0) " 리뷰는 책에서 지워집니다." else ""}", "삭제")`.

### 8.4 Overflow (⋮, normal mode)

정렬… (chooser of `NotesOrder.label`) · 책 선택… · 여러 개 선택 · 내보내기… (the current list) · 공유 (the current list,
TXT) · ✓ 같은 단어 한 번만 (단어 tab only; raw pref `notes.wordsOnce`) · ✓ 찾아본 단어 기록 (`AppSettings.recordLookups`).

### 8.5 Book chooser

`fullScreenDialog`: a toolbar "책 선택", a filter `EditText` (hint "책 제목"), and a list: "모든 책 · 212" first, then
`NoteBook` rows ("《제목》" 16 sp + "12" grey right, with author 13 sp grey under the title). The order is natural title
order, "(휴지통)" suffix for trashed books. It is InkPager-paged. Data comes from `Notes.books(q with bookId = null)`.

### 8.6 Empty states (centre, `emptyMessage` style: 17 sp grey, line spacing 1.3, 32 dp padding)

| Tab | Text |
|---|---|
| 전체 | 아직 모은 노트가 없습니다\n\n책을 읽다가 글자를 길게 눌러 '인용'이나 '메모'를 누르면\n모든 책의 인용문·메모·북마크·리뷰가 여기에 모입니다 |
| 인용문 | 인용문이 없습니다\n\n본문을 길게 눌러 문장을 선택한 뒤 '인용'을 누르세요 |
| 메모 | 메모가 없습니다\n\n문장을 선택하고 '메모'를 누르거나\n인용문·북마크의 메뉴에서 메모를 남기세요 |
| 북마크 | 북마크가 없습니다\n\n읽는 중에 메뉴의 북마크 버튼을 누르세요 (+ "\n화면 오른쪽 위 모서리를 눌러도 됩니다" when `bookmarkByTouch`) |
| 리뷰 | 리뷰가 없습니다\n\n책 메뉴의 '리뷰 쓰기'나\n책을 다 읽은 뒤 나오는 화면에서 남길 수 있습니다 |
| 단어 | 찾아본 단어가 없습니다\n\n글자를 길게 눌러 '사전·번역'이나 '웹 검색'을 누르면\n찾아본 단어와 그 문장이 여기에 기록됩니다 |
| 단어, recording off | 단어 기록이 꺼져 있습니다 + button [기록 켜기] (outlineButton) |
| any, search / filter | ‘검색어’와 일치하는 노트가 없습니다 · 이 책에는 노트가 없습니다 |

### 8.7 State

Raw prefs (`Settings.raw()`, like the library's `PREF_SHELF`): `notes.tab`, `notes.order`, `notes.wordsOnce`. On
`onSaveInstanceState`: the tab, order, book filter, search text, first visible position and selection. The intent
extras win over the remembered tab. On `onResume`: if `Library.notesGen` changed since the last load → drop the page
cache, reload counts, keep the first visible position (clamped) → one redraw. Otherwise nothing happens: no idle
redraw.

### 8.8 Entry points (each an owner's small change)

- LibraryActivity drawer (LIBRARY): after the shelves, a divider, then `drawerRow(ic_format_quote, "독서 노트")` (count
  = drawerCounts[0]) and `drawerRow(ic_translate, "단어장")` (count = drawerCounts[1], opens `WORDS`), next to R2's
  "읽기 기록" row. Counts come from `ensureCounts`' IO job (lazy, drawer open only).
- Library book menu (LIBRARY): "독서 노트" (`ic_format_quote`) after "책 정보" → `NotesActivity.open(ctx, ALL, book.id)`.
- Reader overflow (READER_A, `ReaderMenus`): "독서 노트" → `open(ctx, ALL, currentBookId)`.
- ContentsDialog (EXTRAS_NAV): on the 북마크 / 인용문 tabs, a toolbar `ic_open_in_new` "모든 책의 노트" →
  `open(ctx, BOOKMARKS / QUOTES)`.
- Settings LookupPage (SETTINGS): a switch "찾아본 단어 기록" ("선택한 글자를 사전·번역·웹 검색으로 찾으면 단어장에 문장과
  함께 남깁니다. 기기 안에만 저장됩니다"), row "단어장 열기", row "단어장 비우기" (confirm "찾아본 단어 N개를 모두 지울까요?").

### 8.9 E-ink rules applied

No animations, ripples or fades (`pressableBackground`, `noAnimation()` dialogs). The list is `einkListView()` +
`inkPaging(pageBar)`: a drag becomes one page jump, the page keys and volume keys page, and **no platform fast
scroller** (§14). Tab and chip changes are one redraw each. Loading text is static. There are no timers ("오늘/어제"
is computed at bind time only). Optional polish: hold the first draw of `NotesActivity` up to 250 ms until the first
page arrives (as `LibraryActivity.startOpenLast` does), so opening the hub is one e-ink update. If `InkPager` hasn't
landed by build time, build it first per ARCHITECTURE "UI kit (R2)" (EXTRAS_NAV API). The hub must not ship with fling
scrolling.

---

## 9. The 단어 tab (Dictionary section) in detail

- Rows: word (18 sp bold, 1 line, the selection's first line; long selections read as "문장 앞부분…"), "N회" badge
  when `wordCount > 1`, the sentence with the word in bold (`SpannableString`, `StyleSpan(BOLD)` on the first
  case-insensitive hit), "뜻" note, and meta "단어 · 《책》 · place · app · time".
- [다시 찾기] (`ic_translate`, 48 dp, `contentDescription` "다시 찾기") → `TextActions.lookUp(this, word)` (chooser of
  dictionary apps + 웹 검색). It records nothing new.
- Menu 웹 검색 → `TextActions.webSearch(this, word)`. 문맥 보기 = tap: opens the book with `ReaderJump(section, start,
  end, frac, sig, anchor = word)`, and the word is marked on the page.
- 같은 단어 한 번만 (L1 arm): one row per `word_key` with its latest lookup. The menu then offers "모든 기록 보기", which
  sets the search text to the word and turns the grouping off.
- Export (§10) has a "단어" section per book.
- Privacy: rows exist only in `library.db` and in backups, never on the network. The app's only network use stays the
  existing web search intent the user starts.

---

## 10. Export and share (pure `data/NotesExport.kt`, streaming to a `Writer`)

Flow: 내보내기 → chooser "내보내기 형식": "Markdown (.md) · 메모 앱·옵시디언" / "텍스트 (.txt)" → `ACTION_CREATE_DOCUMENT`
(`CATEGORY_OPENABLE`; type `text/markdown`, falling back to `text/plain` if it can't be resolved; `EXTRA_TITLE`
`독서노트-20260930.md`, or `독서노트-<제목 40자>-20260930.txt` when filtered to one book; the file name strips
`/\:*?"<>|`). `onActivityResult` → IO: `contentResolver.openOutputStream(uri, "wt")` → `BufferedWriter(UTF-8, no BOM,
"\n")` → `Notes.export(refs = selection or null, q, format, w, now)`. Toast "노트 N개를 내보냈습니다" or, through
`ui/kit/Errors`, "내보내지 못했습니다: …".

Content rules: books in the hub's book order (natural title order for date orders). Within a book: review first, then
quotes (memo quotes included, with their note), then bookmarks, then words, **each in reading order** (section,
offset). Empty sections are left out. Full texts are read book by book (one book's rows in memory at a time). The
header counts cover what was written.

### 10.1 Markdown

```markdown
# 독서 노트

- 내보낸 날짜: 2026-09-30 15:42
- 범위: 모든 책 · 전체
- 책 2권 · 인용문 5 · 메모 2 · 북마크 3 · 리뷰 1 · 단어 4

## 절대회귀 1-896 (완)

작가 미상 · TXT · `절대회귀 1-896 (완).txt`

### 리뷰

> 끝까지 읽었다. 중반부가 가장 좋았다.
> 둘째 줄

*2026-09-12 21:04*

### 인용문 (3)

> 인용문 본문 첫 줄
> 둘째 줄

— 12화 과거로 · 37% · 2026-09-12 21:04  
**메모:** 이 장면 다시 읽기

> 두 번째 인용문

— 13화 · 38% · 2026-09-12 21:30

### 북마크 (2)

- 12화 과거로 · 37% · 2026-09-12 21:04 — “새벽 공기는 생각보다 차가웠고…”  
  **메모:** 여기서부터 다시

### 단어 (4)

- **비명** — “…타인의 **비명**이 퍼졌다…” — 12화 · 37% · 파파고 · 2026-09-12 21:04  
  **뜻:** 외마디 소리
```

- "범위" is one of: "모든 책 · 전체", "《제목》 · 인용문", "선택한 노트 12개", plus " · 검색: 단어" when searching.
- Escaping (`NotesExport.md(text)`): backslash before `` \ ` * _ [ ] < > # | ``. At a line start, also before `-`,
  `+` and `N.` ("1\."). Every body line gets the prefix `> `, and empty lines inside a body become `>`. The
  meta/memo line ends in two spaces (a Markdown line break). The file name in backticks has backticks removed.

### 10.2 TXT

```
독서 노트
내보낸 날짜: 2026-09-30 15:42
범위: 모든 책 · 전체
책 2권 · 인용문 5 · 메모 2 · 북마크 3 · 리뷰 1 · 단어 4

========================================
《절대회귀 1-896 (완)》
작가 미상 · TXT · 절대회귀 1-896 (완).txt
========================================

[리뷰]
끝까지 읽었다. 중반부가 가장 좋았다.
(2026-09-12 21:04)

[인용문 3]
“인용문 본문 첫 줄
둘째 줄”
  — 12화 과거로 · 37% · 2026-09-12 21:04
  메모: 이 장면 다시 읽기

“두 번째 인용문”
  — 13화 · 38% · 2026-09-12 21:30

[북마크 2]
• 12화 과거로 · 37% · 2026-09-12 21:04
  “새벽 공기는 생각보다 차가웠고…”
  메모: 여기서부터 다시

[단어 4]
• 비명 — “…타인의 비명이 퍼졌다…”
  12화 · 37% · 파파고 · 2026-09-12 21:04
  뜻: 외마디 소리
```
Books are separated by one empty line before the `====` block. Nothing is wrapped. Items are separated by one empty
line (quotes) or none (list sections).

### 10.3 Share

공유 (the current list or a selection) sends the TXT format through `ACTION_SEND` (`TextActions.share`). It is capped at
200,000 chars (the binder limit, as `shareAllQuotes` does), cut at an item boundary with "\n…(나머지 N개는 '내보내기'로
저장하세요)" and a toast "노트가 많아 앞부분만 공유합니다".

---

## 11. Bookmarks: note or title?

They already have `note` (schema v1; "메모 편집" in the TOC dialog), so nothing is added to the schema. Presentation:
the note's first line is the bookmark's title (bold) in the hub, with the rest as the note. Without a note the chapter
is the title, and the snippet is always the second line. Optional (READER_A, later): long-pressing the chrome's
bookmark button → "메모와 함께 북마크" prompt. The corner tap stays instant (no prompt).

---

## 12. Keeping notes when a book goes away (DATA + LIBRARY)

- `LibrarySql.SELECT_IDS_WITH_NOTES` = `SELECT book_id FROM quotes UNION SELECT book_id FROM bookmarks UNION SELECT
  id FROM books WHERE review <> '' UNION SELECT book_id FROM lookups`.
- `FileScanner.plan`: a vanished, unmoved entry whose id is in that set is **trashed** (`SET_TRASHED 1`) instead of going
  to `gone`. Its notes stay in the hub, marked "(휴지통)", and tapping them says the file is missing. Entries without
  notes are dropped as today. `SyncPlan` gains `trash: List<Long>` (pure, tested in `ScanPlanTest`).
- LibraryDialogs "영구 삭제" and "휴지통 비우기" first read `Notes.countForBooks(ids)` on IO. When N > 0 the message adds
  "\n\n이 책의 인용문·메모·북마크·리뷰·단어 N개도 함께 지워집니다. 먼저 독서 노트에서 내보낼 수 있습니다." and a neutral
  button [독서 노트] → `NotesActivity.open(ctx, ALL, bookId)` (single book).
- "읽은 기록 초기화" keeps notes (unchanged).

---

## 13. Backup v3 fields (DATA; `BackupJson` stays tolerant, `VERSION` stays 1)

- quote: `style`, `chapter`, `frac`, `sig`; bookmark: `chapter`, `frac`, `sig`; book: `reviewAt`.
- book: `lookups: [{word, section, start, end, context, chapter, frac, sig, via, app, note, createdAt}]`, capped with
  `DataLimits`.
- Restore: quotes and bookmarks are matched as today (`section:start:end` / `section,offset`). An existing row gets
  `style` only when it is 0 and `chapter/frac/sig` only when `frac < 0`. Lookups dedupe on `(word_key, section,
  start_offset, created_at)`. `review_at = MAX(current, backup)` when the review was restored. Old backups restore
  unchanged. `notesGen++` after the import.
- Auto backup (scroll SPEC part C) carries all of it automatically, because it is the same export.

---

## 14. Library ⋮ bug: "⋮를 누르려 하면 스크롤이 잡혀 목록이 오르내림"

### 14.1 Root cause (confirmed in code and in the screenshot `ui_ref/ours/01_library.png`)

- `LibraryActivity.buildUi` enables the platform fast scroller, always visible, on both the list and the grid
  (`:428-430`, `:453-454`). Under `Theme.Material.Light` it has a track drawable. `FastScroller.isPointInside` is then
  true for **any y** when `x ≥ thumbRight − 48 dp` (the platform's minimum touch target,
  `fast_scroller_minimum_touch_target`). On a 360 dp screen that is x ≥ 312 dp.
- `AbsListView.onInterceptTouchEvent` asks the scroller first. On ACTION_DOWN inside that zone it intercepts
  (`beginDrag`), so the card never sees the touch. Every ACTION_MOVE, including the few pixels of jitter in any real
  tap, then calls `scrollTo(pos from y)`, and the list **jumps to the proportional position of the finger's height**.
  A tap low on the screen goes toward the end, a tap high toward the start. That is exactly "자꾸 화면이 내려가거나
  올라가".
- The card's ⋮ (`LibraryViews.kt:201`, the last of 5 equal 48 dp-high buttons) spans ≈ 301–348 dp (centre 325 dp
  in the screenshot, x = 651 px at 2×). Most of it is inside the zone, as are the "34%" label and the right end of
  the title. The grid's right column has the same problem.

### 14.2 Fix (LIBRARY, new `ui/kit/FastScrollGuard.kt`; no frozen file)

Keep drag-to-seek, but give the scroller only the touches that start **on its own strip**:

```kotlin
/** Pure: the x the platform may see for an ACTION_DOWN at [x] in a list [width] px wide (scroller on the right).
 *  Inside the platform's 48 dp grab zone but left of the GRAB_DP strip → moved just left of the zone. */
internal object FastScrollGuard {
    const val GRAB_DP = 14f     // the 8 dp track + 6 dp slack (the cards end 8 dp before the edge)
    const val ZONE_DP = 56f     // the platform's 48 dp target + 8 dp margin
    fun shieldedX(x: Float, width: Int, density: Float): Float {
        val grabLeft = width - GRAB_DP * density
        val zoneLeft = width - ZONE_DP * density
        return if (x >= grabLeft || x < zoneLeft) x else zoneLeft - 1f
    }
}

/** ListView / GridView whose always-visible fast scroller only takes touches starting on its strip. */
class GuardedListView(context: Context) : ListView(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = guarded(ev) { super.onInterceptTouchEvent(it) }
    override fun onTouchEvent(ev: MotionEvent): Boolean = guarded(ev) { super.onTouchEvent(it) }
}
class GuardedGridView(context: Context) : GridView(context) { /* same two overrides */ }

private inline fun View.guarded(ev: MotionEvent, sup: (MotionEvent) -> Boolean): Boolean {
    if (ev.actionMasked != MotionEvent.ACTION_DOWN || layoutDirection == View.LAYOUT_DIRECTION_RTL) return sup(ev)
    val x = FastScrollGuard.shieldedX(ev.x, width, resources.displayMetrics.density)
    if (x == ev.x) return sup(ev)
    val copy = MotionEvent.obtain(ev).apply { setLocation(x, ev.y) }
    try { return sup(copy) } finally { copy.recycle() }
}
```

Why it works: only `onInterceptTouchEvent` / `onTouchEvent` see the shifted copy. `ViewGroup.dispatchTouchEvent`
still hands the **original** event to the children, so the ⋮ gets its tap. The list records the DOWN at the same row
(rows span the full width), so a drag that starts on a card still scrolls the list normally. The FastScroller decides
at DOWN, and later events only matter if it started a drag, so it never starts one outside its strip. The visible track
(`x ≥ width − 14 dp`) keeps working exactly as today.

LibraryActivity: `listView = GuardedListView(this).apply { einkDefaults() }` (the `einkListView()` settings copied or
a new `ListView.einkDefaults()` in the same kit file) and `gridView = GuardedGridView(this)`. Nothing else changes.

Alternative considered: drop the fast scroller and page the library with `inkPaging` (plus the "3 / 27" numpad jump).
It is more e-ink-like, but it takes away drag-to-seek the library chose on purpose. That is a product decision, so it's
left for later. The hub (§8.9) uses paging and has no fast scroller at all.

Tests: `FastScrollGuardTest` (pure: left of the zone / in the zone / on the strip / width 0). CI screenshot step: on the
all-books shelf, tap the 3rd card's ⋮ at (325 dp, card centre + 44 dp). Assert the book menu shows and
`firstVisiblePosition` hasn't changed (screenshot `41_library_more.png`).

---

## 15. The other Premium items (recommendation, one line each)

| Item | Verdict |
|---|---|
| Synchronization (Google Drive) | **No** (no network, no accounts). The auto backup to a user-chosen SAF folder (scroll SPEC part C) carries notes and words (§13). A Drive-synced folder works through the file provider. |
| Background play | Already T1-11 (`TtsService`). |
| 섹션: 인용문 | **This spec** (독서 노트). |
| Section: Dictionary | **This spec** (단어 tab + 단어장 drawer row). |
| 인용문 색 표시 | Yes, via the quote-colour spec. Here only `quotes.style` ids, swatches, filter and change (§3.6). |
| 라이브러리 뷰 (전체·요약·썸네일·그리드) | 전체 = LIST, 요약 = COMPACT (T1-13), 그리드 = GRID. **썸네일** (covers only, 4 columns, no text) is cheap: one `LibraryListMode.COVERS("썸네일")` entry (frozen settings) + a text-less grid cell. Later. |
| 페이지 썸네일 | **Not now.** Each thumbnail is a full page layout+draw (≈ 15–30 ms on the A53); a screen of 12 is ≈ 0.3 s, and text-only web-novel pages at 1/4 scale carry no information. Cheaper alternative with the new `frac` column: "노트 지도", ticks for quotes/bookmarks on the reader's seek bar (drawn only while the chrome shows). |
| 내 글자체 | Exists. |

---

## 16. Contract requests (frozen files), exact

1. `data/LibrarySchema.kt`: `DB_VERSION = 3` (or 2, §3.1); `CREATE_QUOTES`, `CREATE_BOOKMARKS`, `CREATE_BOOKS`
   (`review_at`), new `CREATE_LOOKUPS`, the seven `AddedColumn`s (version 3), the five indexes, `CREATE_ALL` order,
   KDoc "v3: notes hub (lookups, note places, review_at)".
2. `data/Models.kt`: §3.4 (Quote/Bookmark fields, `NotePlace`, `NoteKind`, `NotesTab`, `NotesOrder`, `NotesQuery`,
   `NoteRef`, `NoteRow`, `NoteBook`, `NotesCounts`, `Lookup`).
3. `AndroidManifest.xml`: the `NotesActivity` element (§8).
4. `settings/ReaderSettings.kt`: `AppSettings.recordLookups: Boolean = true`. `settings/Settings.kt`: key
   `a.recordLookups`. `data/SettingsJson.kt`: mapped.
5. `reader/extras/ReaderPanels.kt`: `interface NotePlaceHost` (§6.1).
6. `docs/`: an ARCHITECTURE "Contract revision R3 → notes hub" section (the schema, the jump contract, the peek rule,
   "no index on an added column"), R3_INTERFACES entries for `Notes`, `Lookups`, `ReaderJump`, `NotePlaceHost`,
   `TextActions.lookUp(…, onPicked)`.

---

## 17. Owner work list

| Owner | Work |
|---|---|
| Contract | §16. |
| DATA | `Notes.kt`, `NotesSql.kt`, `NotesExport.kt`, `Lookups.kt` (+ `LookupWords`), `Library` additions (§5), `notesGen`, `BookRows` mappings, `LibrarySql` constants, `deleteBookRows` + lookups, scanner trash-instead-of-drop (§12), backup v3 (§13). |
| READER_A | `ReaderJump.kt`, `open(…, jump)`, `startOpen`/`onNewIntent` (§7), `OWNER_JUMP`, peek, anchor check, `NotePlaceHost`, bookmark place, backfill after `reloadAnnotations`, reader overflow entry. |
| EXTRAS_TOOLS | `LookupContext.kt`, `SelectionController` (quote place, lookup snapshot + record), `TextActions.lookUp/webSearch` callbacks. |
| EXTRAS_NAV | ContentsDialog "모든 책의 노트" link; `InkPager` / `InkNumPad` if still missing. |
| NOTES_UI (new; or LIBRARY) | `ui/notes/*` (§8–10). |
| LIBRARY | Drawer rows + counts, book menu entry, delete warnings, `FastScrollGuard` (§14). |
| SETTINGS | LookupPage switch / open / clear. |
| RENDER / colours | `QuoteStyles` (§3.6) and the page look of styles (their spec). |

Order: contract → DATA (with tests) → READER_A jump → NOTES_UI → EXTRAS_TOOLS recording → LIBRARY/SETTINGS entries. The
⋮ fix (§14) has no dependency and can ship first.

---

## 18. Tests (JVM unless stated)

| Test | What |
|---|---|
| `data/LibrarySchemaV3Test` | fresh v3 has every column; `upgradeStatements(1)` and `(2)` list the seven ALTERs once, `(3)` none; the columns-exist guard; **no `CREATE_INDEXES` entry names an `ADDED_COLUMNS` column**; `CREATE_LOOKUPS` precedes its indexes. |
| `data/SqlDumpTest` (+) / `check_sql` | every new statement prepares on SQLite ≥ 3.18 against (a) a fresh v3, (b) a v1 file upgraded with data, (c) v3 → v2 build → v3 (no duplicate ALTER). `EXPLAIN QUERY PLAN`: single-arm date pages use `*_created` without "TEMP B-TREE FOR ORDER BY"; by-book pages use `*_book`; details use the PK. |
| `data/NotesSqlTest` | arms per tab (MEMOS = QM + MM only; WORDS L vs L1); filters on every arm with correct aliases; search tokens bound in placeholder order and escaped (`%`, `_`, `\`); OLDEST reverses; LIMIT 51 / OFFSET 50p − 1 for p > 0; the counts statement re-labels memos as 5. |
| `data/NotesPagingTest` | `BookSpans.prefix/locate` (row → book, inner offset, boundaries, empty books); window page math; prefetch pages for a visible range; placeholder when a page is short. |
| `data/NotesExportTest` | golden Markdown and TXT for a fixture (2 books, every kind, multi-line quote, memo, trashed book, empty sections omitted, one-book scope, selection scope); Markdown escaping table; header counts equal the rows written; share cap cut at an item boundary with the suffix. |
| `data/LookupWordsTest` | NFC, punctuation/quotes stripped, ASCII lowercase, whitespace, ≤ 100 chars, Korean untouched. |
| `data/BackupJsonTest` (+) | v3 fields round trip (style, place, reviewAt, lookups); an old backup parses with defaults; caps applied. |
| `data/ScanPlanTest` (+) | a vanished entry with notes goes to `trash`, one without goes to `gone`, a moved one is re-pointed (unchanged). |
| `reader/extras/LookupContextTest` | sentence bounds at `. ! ? … 。` + closing quotes and `\n`; 150-char cut with "…"; OBJECT_CHAR removed; selection spanning two sentences; start/end clamped. |
| `reader/ReaderJumpTest` | `from`/`put` round trip via a fake extras map (the pure `sanitize` core); negative / NaN rejected; `resolveJump` table (§7.3); `JumpAnchor.matches` ignores whitespace and OBJECT_CHAR, fails on changed text, empty anchor = match. |
| `ui/notes/NotesTextTest` | `place`, `time`, `dayHeader` (today, yesterday, this year, other year; weekday in Korean), book header, `"N회"`, file-name sanitising. |
| `ui/kit/FastScrollGuardTest` | §14. |
| CI screenshots (device) | `60_notes_hub.png`: select text → 인용 → drawer → 독서 노트; `61_notes_words.png`: 사전·번역 → cancel (no row) / pick web (row); `41_library_more.png` (§14). |
| Perf (device, `RANotes`) | §4.7 gates with a seeded DB of 10,000 notes (debug-only seeder behind `adb shell am broadcast`, never in release). The book open with a jump equals a normal open in `RAPerf` ("first page N ms"). |

---

## 19. What this costs where it matters

- **Opening a book**: 6 `getExtra` calls and one branch. The jump **replaces** the saved position (same single layout).
  The mark is a map put before the first draw. The anchor check and backfill run after the first page. Peek is one
  boolean per save.
- **Page turn**: `ownerHighlights.remove(OWNER_JUMP)` next to the existing `OWNER_SEARCH` removal. Nothing else.
- **Library cold start**: nothing. `NotesActivity` is a separate class. The drawer's count is one query inside the
  existing lazy job, cached by `notesGen`.
- **Selection actions**: one IO insert after a dictionary / web pick (never on main, never blocking the chooser).
- **Upgrade**: seven `ALTER … ADD COLUMN` (constant time in SQLite) + five index builds (≈ 20 ms per 10,000 rows,
  once).
- **APK**: ≈ 60–80 KB of code, no new resources (every icon exists: `ic_format_quote`, `ic_translate`,
  `ic_rate_review`, `ic_bookmark`, `ic_sticky_note_2`, `ic_ink_highlighter`, `ic_upload`, `ic_check_box*`,
  `ic_open_in_new`, `ic_filter_list`, `ic_sort`).

---

## 20. Korean strings (all new UI text)

| Key / place | Text |
|---|---|
| drawer, toolbar | 독서 노트 · 단어장 |
| tabs | 전체 · 인용문 · 메모 · 북마크 · 리뷰 · 단어 |
| orders | 최신순 · 오래된 순 · 책별 · 최근 읽은 책 먼저 · 책별 · 제목순 |
| chips | 모든 책 ▾ · 《제목》 ✕ · 모든 색 ▾ |
| search hint | 인용문·메모·단어·책 제목 검색 |
| page bar | {p} / {n} 쪽 · {count}개 |
| row labels | 메모 · 뜻 · {n}회 · (휴지통) · 리뷰 |
| menus | 책에서 보기 · 전체 보기 · 복사 · 공유 · 메모 편집 · 색 변경 · 선택 · 삭제 · 리뷰 편집 · 책 열기 · 리뷰 지우기 · 다시 찾기 · 문맥 보기 · 웹 검색 · 뜻 메모 · 모든 기록 보기 |
| overflow | 정렬… · 책 선택… · 여러 개 선택 · 내보내기… · 공유 · 같은 단어 한 번만 · 찾아본 단어 기록 |
| selection | {n}개 선택 · 모두 선택 · 선택 해제 · 공유 · 내보내기 · 삭제 |
| dialogs | 인용문 삭제 / 이 인용문을 삭제할까요? · 북마크 삭제 / 이 북마크를 삭제할까요? · 리뷰 지우기 / ‘{제목}’의 리뷰를 지울까요? · 단어 기록 삭제 / ‘{단어}’ 기록을 삭제할까요? · 노트 삭제 / 선택한 {n}개를 삭제할까요? (+ 리뷰는 책에서 지워집니다.) · 인용문 메모 · 북마크 메모 · 뜻 메모 · 리뷰 · 색 · 책 선택 · 내보내기 형식 |
| export chooser | Markdown (.md) · 메모 앱·옵시디언 · 텍스트 (.txt) |
| toasts | 삭제했습니다 · {n}개를 삭제했습니다 · 인용문 {n}개의 색을 바꿨습니다 · 노트 {n}개를 내보냈습니다 · 내보내지 못했습니다 · 노트가 많아 앞부분만 공유합니다 · 책 파일을 찾을 수 없습니다 (노트는 남아 있습니다) · 노트 위치를 다시 찾았습니다 · 노트가 있던 곳을 찾지 못해 가까운 위치를 열었습니다 · 노트가 있던 곳을 찾지 못했습니다 · 복사했습니다 (API < 33) |
| settings (LookupPage) | 찾아본 단어 기록 · 선택한 글자를 사전·번역·웹 검색으로 찾으면 단어장에 문장과 함께 남깁니다. 기기 안에만 저장됩니다 · 단어장 열기 · 단어장 비우기 · 찾아본 단어 {n}개를 모두 지울까요? |
| delete warning (library) | 이 책의 인용문·메모·북마크·리뷰·단어 {n}개도 함께 지워집니다. 먼저 독서 노트에서 내보낼 수 있습니다. · [독서 노트] |
| empty states | §8.6 |
| export headers | 독서 노트 · 내보낸 날짜 · 범위 · 모든 책 · 선택한 노트 {n}개 · 검색 · 책 {n}권 · 인용문 · 메모 · 북마크 · 리뷰 · 단어 · 작가 미상 |

---

## 21. Open questions for the lead

1. The v2 fold vs v3 bump (§3.1): depends on whether any v2 build reached the device before this lands.
2. The owner of `ui/notes/` (a new NOTES_UI, or LIBRARY).
3. Anchor re-location writing the found position back into the note (§7.5): off in the first version (it rewrites
   user data).
4. The scanner trashing vanished books with notes (§12) instead of dropping them: a behaviour change the user may
   notice in 휴지통. The alternative is a silent loss of notes.
5. Replacing the library's fast scroller with InkPager paging (§14.2 alternative): a product call. The guard fix
   works with either.
