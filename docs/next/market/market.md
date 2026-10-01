# Leading reader apps: feature inventory, user complaints and 56 candidate features for ReaderaPlus on the Comet

## 0. Method and confidence

**How the evidence was gathered.**
- **Web search** worked, but only as result snippets.
- **Direct page fetches were blocked** for every site I tried: f-droid.org, the-ebook-reader.com, technipages, brianlovin/HN.
- **GitHub worked.** I read the KOReader repository tree and plugin metadata (commit `7fedb85`), Librera's `AppState.java` settings fields, Legado's source tree (branch `pr5804`) and the lithiumpatch README. The earlier research notes in the scratchpad were used as well.

**Confidence tags used on every claim.**
- **[V]** verified this session in source code or a repository.
- **[S]** stated in a search-result snippet this session (sources at the end).
- **[M]** my own knowledge, medium confidence.
- **[M?]** my own knowledge, low confidence. Check before relying on it.

**What ReaderaPlus already has** (from `docs/ARCHITECTURE.md` and `settings/ReaderSettings.kt`). The candidate table in section 4 marks each feature against this list, so we don't re-propose these:
- **Typography:** font, size, weight from 100 to 900, line spacing, paragraph spacing, indent, letter spacing, alignment, word or character line breaking, margins, three fixed style presets.
- **Status bar:** a header plus a footer with toggles for page, chapter pages left, %, clock and battery.
- **Input:** tap-zone presets plus a custom 3×3 grid, learned page keys, volume keys, swipes, brightness swipe.
- **E-ink:** full refresh every N pages and on chapter start, and the Bigme e-ink mode.
- **Reading:** auto page turn, a one-level "돌아가기" (go back) chip, search, contents / bookmarks / quotes.
- **Selection and lookup:** selection actions (copy, quote, note, share, PROCESS_TEXT, web), and TTS with a sleep timer.
- **TXT processing:** encoding detection and override, chapter rules and regex, replacement rules, blank-line modes, the index cache.
- **Library:** shelves, collections, grid view, trash, manual JSON backup, invert mode.

**What ReaderaPlus does not have:**
- A statistics screen (only `reading_seconds` is stored).
- Settings per book (all settings are global).
- Presets saved by the user.
- Highlight styles, and selection across pages.
- Any sync, Wi-Fi transfer, offline dictionary or footnote popup.
- An end-of-book action.
- ZIP support, and paginated lists (lists scroll).

---

## 1. Executive summary

1. **What people pay for** is the same across the one-time "Pro/Premium" apps (Moon+, ReadEra, Librera, Lithium, FBReader):
   - no ads;
   - sync of position, notes and library across devices;
   - TTS in the background or with the screen locked;
   - reading statistics;
   - extra themes and highlight colours;
   - every note from every book in one place.

   The store-tied readers (Kindle, Kobo, Play Books, PocketBook, Onyx) sell an ecosystem: store, sync, subscriptions and hardware. Nobody pays for raw speed, but users leave apps that are slow on big files or on e-ink.
2. **Why users love the power apps.** KOReader and Moon+ are loved for depth: every typographic knob, status-bar items, gestures, statistics, exporting highlights. Both are hated for the same thing: menus that overwhelm people ([S] for KOReader). ReaderaPlus's "compact popup plus 더보기 (more)" design already avoids this. The lesson is to add depth behind progressive disclosure, not as more rows on the first screen.
3. **What the dedicated e-ink readers share** (Kindle, Kobo, PocketBook, Onyx, KOReader):
   - lists that page instead of scroll (KOReader "paginated menus" [S]);
   - a configurable full-refresh cadence;
   - control over font weight and sharpness (Kobo TypeGenius weight and sharpness [S]; Onyx embolden and contrast [S]);
   - time-left estimates (Kobo [S]; KOReader footer [V]);
   - sleep screen showing the current book's cover [M].

   Android apps only reached e-ink recently: Moon+ added an E-Ink mode in 8.4 [S], FBReader an EInk theme in 3.8 [S], and Readest has an e-ink mode [S]. ReaderaPlus is already ahead on refresh control. Its remaining gaps are scrolling lists and time-to-read.
4. **Top complaints, and what each means for us:**

   | Complaint | Example | What we do about it |
   |---|---|---|
   | Clutter and learning curve | KOReader [S], Moon+/Librera [M] | Add features behind 더보기 (more), not on the first screen |
   | Ads and upsell | Moon+ free [S], ReadEra background TTS paywall [V] | None needed; we have neither |
   | Sync failures | Moon+ Dropbox [S] | Keep sync optional and file-based |
   | Slow large files and whole-document layout | ReadEra, KOReader/crengine [V from earlier notes] | Already solved by the index cache and lazy layout |
   | Sideloaded books treated as second-class | Kobo stats disabled for sideloaded EPUB [S]; Kindle can't take EPUB over USB [S] | Everything is sideloaded for us, so every feature must work on local files |
   | Abandoned development | Librera frozen by the war [V README]; Lithium slow [M] | — |
   | Animations and ghosting on e-ink | Moon+ before 8.4 [S] | Already avoided |
5. **The web-novel gap nobody fills well.** Korean web-novel TXT comes in multi-part files (`1-100화.txt`, `101-200화.txt`), has 1000+ chapters and is often zipped. KOReader is closest with its "Open next file" end-of-document action [V]. Legado has a TOC search [S]. No app treats a folder of parts as one book. This is ReaderaPlus's clearest chance to be better than all of them.
6. **Twelve features I recommend** for the "sellable" jump, none of which touch the open path:
   1. Paginated lists, plus jumping to a chapter by typing its number.
   2. End-of-book → open the next part, and reading a multi-part series as one book.
   3. Reading statistics plus time-left in the footer.
   4. Style profiles saved by the user, plus an optional per-book override.
   5. Status bar with slots, plus a progress bar with chapter ticks.
   6. Footnote popups for EPUB.
   7. Highlight styles, selection across pages, all annotations in one place, and export to Markdown or TXT.
   8. TTS in the background with a media notification, plus Korean TTS replacement rules.
   9. Wi-Fi transfer that runs only while its screen is open.
   10. Opening TXT and EPUB inside a ZIP.
   11. Automatic backup.
   12. Mapping any key, or a long press, to any action; plus a UI-scale setting and a text-sharpness toggle.

   Details are in sections 4 and 5.

---

## 2. Per-app inventories

### 2.1 Moon+ Reader Pro (Android; closed source, one developer; roughly $5 one-time Pro)

**Formats:** EPUB, PDF, DJVU, AZW3, MOBI, FB2, PRC, CHM, CBZ/CBR, UMD, DOCX, ODT, RTF, TXT, HTML, MHT, MD, ZIP/RAR, OPDS [S].

**Library:** bookshelf with shelves, favourites, downloads, authors, tags and folders [M]; OPDS catalogues [S]; works with Calibre (local Calibre server / Calibre Companion) [S].

**Reading and typography:**
- line spacing, font scale, bold, italic, text shadow, justification, alpha colours, "fading edge" [S];
- 10+ themes with day/night switching [S];
- "intelligent paragraph, indent paragraph, trim unwanted blank spaces and lines" for TXT [S, earlier notes];
- custom fonts, hyphenation, dual page in landscape, several page-turn animations [M];
- RTL mode for PDF, DJVU and comics [S].

**Navigation and controls:**
- 24 customisable operations (screen taps, swipe gestures, hardware keys) mapped to 15 events such as search, bookmark, themes, navigation and font size [S];
- 5 auto-scroll modes (rolling blind, by pixel, by line, by page), with speed control while it runs [S].

**Annotations:** highlights, notes and bookmarks with sharing [S]; "Highlight all the same word" (mark learning, 8.4) [S].

**Dictionary and TTS:**
- Dictionary: third-party apps such as ColorDict and GoldenDict, plus online lookup [M].
- TTS through system engines, "shake to speak", Bluetooth headset controls (Pro) [S], and a sleep timer [M].

**Sync and backup:** backup and restore to Dropbox or WebDAV, plus position sync between devices [S].

**Statistics:** reading statistics (Pro), with reading progress added to the daily statistics [S].

**E-ink:** E-Ink display mode since 8.4. It removes every animation (page turns, menus, dialogs, shelves) and forces pure black on white [S].

**Other:** blue-light filter [S]; password protection (Pro) [S].

**Why people love or pay:** the most complete Android reader, with a one-time price. Pro removes ads and adds TTS extras, statistics and a password lock [S].

**Complaints:**
- full-screen ads in the free version [S];
- intermittent authentication and sync problems with Dropbox [S];
- merged EPUBs sometimes show only part of the content [S];
- settings overload and a dated UI [M];
- duplicate page turns on e-ink [S, earlier notes];
- a single closed-source developer [M].

### 2.2 KOReader (open source, AGPL; Kindle, Kobo and PocketBook firmware, Android, Linux)

**Library:**
- file browser, history and collections, with a mosaic or list cover browser (`coverbrowser` plugin) [V];
- OPDS, Calibre wireless connection and metadata search, Wallabag, a news downloader that turns RSS into EPUB, cloud storage (Dropbox, WebDAV, FTP) [V];
- "Move to archive" [V].

**Reading and typography:** crengine with arbitrary margins, line spacing, external fonts, style tweaks (CSS), widow and orphan control, hyphenation including user hyphenation (`readeruserhyph`), CJK typography tweaks, text contrast [S/V].

**Navigation:**
- TOC, plus a "handmade TOC" you build yourself and hidden flows (`readerhandmade`) [V];
- page browser and book map (`readerthumbnail`) [V];
- publisher reference page numbers (`readerpagemap`) [V];
- a location stack you can go back through (`readerback`) [V];
- go-to page or percent [V];
- a gesture manager, hotkeys, and book shortcuts bound to gestures [V].

**Status bar** (`readerfooter`) [V]:
- items: page progress, pages left in the book, pages left in the chapter, time, battery, %, time to read for the book and for the chapter, chapter progress, book title, chapter title, author, bookmark count, custom text, dynamic filler, frontlight, Wi-Fi, memory;
- progress bar: thick or thin, with chapter markers, an initial-position marker, or a chapter-only bar;
- layout: arrange the items, and choose their font and size.

**Annotations:**
- highlight styles Lighten / Underline / Invert, gray highlight opacity, highlight merging [V];
- a dog-ear corner for bookmarks [V];
- selecting text with the keyboard [V].

**Exporter** [V] writes to HTML, JSON, Markdown, text, Kindle "My Clippings", Joplin, Readwise, Nextcloud and XMNote.

**Dictionary and learning:** offline StarDict dictionaries, Wikipedia, Google Translate [S]; vocabulary builder with spaced repetition [V]; Japanese deinflection [V].

**TTS:** no built-in TTS on most devices [M].

**Sync:** `kosync` progress-sync server [V]; statistics sync [V].

**Statistics** [V]: current book, reading progress (last week), time range, calendar view with an hourly histogram, today's timeline, limits on how long a page may count, freezing statistics of finished books.

**E-ink** [V, earlier notes]:
- full refresh rate (never / every page / every N pages);
- flash on chapter boundaries, flash on image pages, "avoid black flashes in UI";
- paginated menus with no animation [S];
- page turns measured at under half the delay of the built-in reader on some devices [S].

**Other plugins** [V]:
- **Reading aids:** Profiles (switchable sets of settings), Read timer, Perception expander (speed-reading guide lines), Autoturn, Autodim, Autowarmth.
- **Behaviour and power:** "Tweak document settings" (rules applied before a document loads), Auto-suspend/standby, Keep-alive.
- **Covers and QR:** Cover image (save the book cover for use as a screensaver), QR code from the clipboard.
- **Tools:** SSH, terminal, text editor.

**End-of-document action** [V]: ask, show book status, go to beginning, open next file, file browser, mark as finished, delete file.

**Settings per book:** each document keeps its own settings in a sidecar, with global defaults [M].

**Why people love it:** it is free, extremely deep, fastest on e-ink, has the best statistics and export, and is the same app on every device.

**Complaints:**
- menus are unintuitive and the learning curve is steep ("the sheer number of features") [S];
- the Android version is officially "experimental" [S];
- crengine lays out the whole document, so large TXT files are slow [V earlier notes];
- no system-level library or scanning [M];
- no TTS [M];
- it looks non-native on Android [M].

### 2.3 ReadEra / ReadEra Premium (Android and iOS)

**Library** [V earlier notes]:
- automatic scan of all storage;
- shelves: Reading now, Books, Favourites, To read, Have read, Authors, Series, Collections, Formats, Folders, Downloads, Trash;
- duplicate detection by MD5, so a book keeps its position when the file moves.

**Reading:**
- colour modes, font boldness, hyphenation, floating punctuation, publisher styles on or off [V];
- tap zones, volume keys, going below the system minimum brightness [V];
- showing pages left in the chapter [V].

**Annotations:** quotes with colours, notes, reviews and ratings; a list of looked-up words [V].

**Premium** [S]:
- Google Drive sync of books, progress, bookmarks and quotes;
- one section holding all quotes, notes, bookmarks and reviews;
- TTS in the background and with the screen locked;
- "My fonts";
- full, brief, thumbnail and grid library views;
- more quote colours;
- a dictionary section with every looked-up word.

**Why people love or pay:** it is clean, has no ads even in the free version, and the auto-scan means zero setup. People pay for sync and background TTS.

**Complaints:**
- the auto-scan clutters the library with unwanted files [S];
- TTS sounds robotic, with long pauses and glitches [S];
- users want better organisation and more fonts [S];
- whole-document layout makes large TXT files slow, Korean TXT gets no TOC, blank lines are doubled, and there is no e-ink mode [V earlier notes].

### 2.4 Librera Reader / Librera PRO (open source, GPL; "PRO" means no ads)

**State:** development and support are frozen because of the war in Ukraine [V README].

**Formats:** PDF, EPUB, EPUB3, MOBI, DjVu, FB2, TXT, RTF, AZW/AZW3, HTML, CBZ/CBR, DOC/DOCX, OPDS [V].

**Features.** These are verified as setting fields in `AppState.java` [V]:
- **Page modes:** Musician's mode, i.e. hands-free auto-scroll (`prefMusicianMode`, `autoScrollSpeed`, `isScrollSpeedByVolumeKeys`), plus scroll, book and two-page modes.
- **Tap zones:** custom zones on the top, bottom, left and right (`tapZoneTop/Bottom/Left/Right`, `tapzoneSize`); volume keys and reversing them.
- **TTS:** reading by sentence, a configurable pause length (`ttsPauseDuration`), pronunciation replacements (`isEnalbeTTSReplacements`, `lineTTSReplacements`), converting TTS to MP3 (`isConvertToMp`), stopping reading when a call comes in, a quick bookmark while TTS is reading.
- **Text transforms:** text replacement (`isEnableTextReplacement`), a bionic reading mode (`isBionicMode`), a hyphenation language.
- **Progress display:** chapter marks on the progress bar (`isShowChaptersOnProgress`, `isShowSubChaptersOnProgress`), a red marker on the last page.
- **Library:** tags (`bookTags`), hiding books already read, skipping folders that contain `.nomedia`, reading metadata from Calibre OPF, the series number in the title, a home-screen widget, fast bookmarks.
- **Reading comfort:** a rest reminder (`remindRestTime`), a blue-light filter, background images, custom day and night colours.
- **Other:** an app password (`isAppPassword`), OPDS with a proxy, crop margins, RTL.
- **E-ink:** optimisation for e-ink [S].

**Why people love it:** a huge feature set for free, open source, strong PDF and comics support.

**Complaints:** development is frozen [V]; the UI is dense and cluttered with many modes [M]; ads in the free version [M]; bugs that won't be fixed [M].

### 2.5 Lithium: EPUB Reader (Android; free, with Pro)

**Features:** EPUB only [S]; Material design, no ads, automatic detection of EPUB files, highlights and notes, night and sepia themes, page or scroll mode [S].

**Pro:** custom themes with custom colours, more highlight colours, Google Drive sync of position, highlights, notes and bookmarks [S].

**What the community patch adds.** lithiumpatch [V] shows what the stock app lacks:
- custom fonts, an offline dictionary, hyphenation, finer font-size control;
- more information in the footer, series metadata and a series section;
- disabling page-turn animations for e-ink, inverting images or the whole page;
- a cover-only grid, a progress badge on books.

**Why people love it:** simplicity, speed and a beautiful UI.

**Complaints:** EPUB only [S]; books failing to open after an update [S]; slow development [M]; stock has no custom fonts, TTS or e-ink options [V via the patch list].

### 2.6 FBReader (Android, iOS, desktop; Premium is a one-time purchase)

**Sync:** the "FBReader book network", backed by Google Drive, syncs the library, reading positions and bookmarks [S].

**Premium:** opening PDFs and comics, built-in Google Translate, and an exclusive dark UI theme (Dark Blue, 3.8.6) [S].

**Themes:** version 3.8 added Blue, EInk and Classic [S].

**Encoding:** the option to change it in the book-info dialog was restored in 3.5.1 [S].

**From memory [M]:** OPDS network libraries, CSS on or off, tap zones, TTS through a plugin, ColorDict/GoldenDict integration, broad format support (EPUB, FB2, MOBI, RTF, TXT, HTML, DOC).

**Why people love it:** a long-trusted, light, cross-platform reader.

**Complaints [M]:**
- the UI looked dated for years (the new themes were the answer);
- the plugin split for PDF is confusing;
- basic handling of TXT paragraphs;
- Premium upsell.

### 2.7 Kindle (Amazon e-ink devices and apps)

**Reading and navigation:**
- Page Flip: skim pages or chapters without losing your place [S];
- Word Wise hints above hard words [S];
- X-Ray and Smart Lookup: a dictionary together with Wikipedia and characters [S];
- Vocabulary Builder with flashcards [S].

**Newer features:**
- AI **Recaps** for books in a series (2025) [S];
- **Story So Far** and **Ask This Book**, spoiler-safe AI answers (app in late 2025, devices early 2026) [S];
- double-tap the side or back of the device to turn the page forward [S].

**From memory [M]:**
- **Display:** Themes (saved sets of font, size and layout), a bold-level slider, a choice of reading-progress display (location, page, time left in chapter, time left in book), a "Page Refresh" toggle, dark mode.
- **Collections and annotations:** collections, the `My Clippings.txt` export of annotations, popular highlights.
- **Sync, sleep screen and ecosystem:** Whispersync, "Display Cover" on the lock screen for models without ads, Goodreads [S].
- **Accessibility:** the VoiceView screen reader, OpenDyslexic.

**Why people love or pay:** the store, Kindle Unlimited, Whispersync, a polished and simple UI, Word Wise and X-Ray.

**Complaints:**
- custom fonts revert to Bookerly or the publisher font [S];
- limited layout choices, with only three margin and line-spacing settings for years [S];
- no EPUB over USB, only through Send to Kindle [S];
- sideloaded collections and metadata can't be managed [S];
- lock-screen ads unless you pay [S];
- firmware updates that break the progress bar or font sizes [S].

### 2.8 Kobo (Rakuten Kobo e-ink devices)

**Statistics:** time to finish the book and the chapter based on your reading speed, book and chapter progress, average minutes per session, pages per minute, total hours, % of library read, and an indicator of the next chapter's length [S].

**Header and footer:** pages left in the chapter, % of the chapter, time left in the chapter; pages left in the book, % of the book, time left in the book [S].

**Typography:**
- 13 fonts and 40+ sizes;
- TypeGenius "Advanced" per font, with **Weight** and **Sharpness** sliders;
- sideloaded TTF/OTF fonts from a `fonts` folder;
- line spacing and margins;
- justification: full, left or off [S].

**Refresh:** a full refresh every few pages, configurable [S].

**From memory [M]:**
- **Store and services:** Kobo Plus subscription, OverDrive library loans, Dropbox and Google Drive integration.
- **Device and sleep screen:** sleep screen showing the current book cover, dark mode, physical page-turn buttons on the Libra and Sage.
- **Tap zones and notes:** a few fixed tap-zone layouts (not a free grid), "My Words", stylus notebooks on some models.

**Why people love it:** open to EPUB, typography better than Kindle, good statistics, library loans.

**Complaints:**
- statistics (minutes to go, chapters) are disabled for sideloaded EPUB and KEPUB [S];
- the best features need KEPUB conversion [M];
- limited tap-zone choices [M];
- sync hiccups [M].

### 2.9 Onyx Boox NeoReader (Onyx Android e-ink devices)

**Display:** refresh mode chosen per app, "one-tap display optimisation" [S]; embolden, contrast, image sharpness, watermark bleaching, dark-colour enhancement, image smoothing [S].

**Layout:** split screen, e.g. two documents, or a document next to a notepad [S].

**AI:** an AI assistant explains terms and difficult sentences [S].

**NeoReader 3.0:**
- reading statistics; scroll mode for PDF; the original EPUB layout, including vertical text [S];
- dictionary lookup of phrases [S];
- a highlight style that fully covers the text [S];
- export of annotations to third-party note apps [S];
- a floating toolbar, renamable bookmarks, sidenote mode [S].

**Transfer:** BooxDrop, which serves an HTTP page on the LAN (e.g. `http://192.168.0.13:8085`) for two-way transfer from a browser and appears to Calibre as a wireless device [S]; "Send to Boox" Chrome extension [S].

**From memory [M]:** handwritten annotation over EPUB and PDF, TTS, translation, two-page layout, auto page turn, configurable tap zones, Onyx Cloud.

**Why people love it:** deep integration with the hardware (refresh, contrast), stylus notes, strong PDF.

**Complaints:**
- a bug where the position resets to page 1 after changing font size (older) [S];
- EPUB typography weaker than KOReader and the app can feel sluggish [M];
- privacy concerns about the Onyx cloud [M];
- many people install KOReader or Moon+ on their Boox instead [M].

### 2.10 PocketBook Reader (Android and iOS app; PocketBook e-ink devices)

**Formats and layout:** PDF, EPUB, FB2, MOBI, RTF, DOCX, CBR/CBZ, CHM [S]; single-page, two-page or scroll view; two night modes [S].

**Tools:** crop margins, zoom, highlights, notes, translation, bookmarks, TTS [S].

**Sync:** PocketBook Cloud syncs books, audiobooks, positions, notes and bookmarks; a QR code pairs the app with a PocketBook e-reader [S]; Dropbox, Google Drive and Google Books appear as one library [S].

**On the e-ink devices:** actions assignable to 9 touch zones [S]; a "Full page update" setting [S]; statistics weak enough that third-party trackers (PB-Tracker) exist [S].

**From memory [M]:** Send-to-PocketBook by email, built-in dictionaries, audiobook player.

**Why people love it:** open formats, a full cloud ecosystem, 9-zone gestures on the devices.

**Complaints [M]:** a clunkier UI, slow cloud sync, a less polished Android app. The only evidence is old forum threads saying that full-page update was ignored for EPUB [S].

### 2.11 Google Play Books (Android, iOS, web)

**Features:** Bubble Zoom for comics [S]; "Read & Listen" for children [S]; smart notes that sync to Google Drive [S]; free auto-narrated audiobooks [S].

**Uploads:** up to 1000 DRM-free EPUB or PDF files, each at most 100 MB [S].

**From memory [M]:** original pages or flowing text, a handful of fonts, line height, justification, a page-turn animation setting, Read aloud with Google voices, Night light, dictionary / translate / search, Family Library.

**Why people love it:** it is free, syncs everywhere, and ties into the Play Store and Drive.

**Complaints:** historically, poor bookmarks and place-finding, and crashes [S]. From memory [M]: shallow typography with no font import, no TXT, heavy on e-ink with no e-ink mode, upload limits and the need for a Google account.

### 2.12 Also relevant to our user (brief)

- **Readest** (open source, cross-platform, a rewrite of Foliate) [S]:
  - e-ink mode;
  - sync with KOReader, so progress stays aligned with KOReader devices;
  - CJK typography;
  - custom CSS;
  - TTS, translation, grouped bookshelves.
  - It shows how fast a KOReader-sync-compatible Android reader can win e-ink users.
- **Legado (阅读)**, the Chinese web-novel reader [V tree, S]:
  - LAN web service (`web/HttpServer.kt`, `service/WebService.kt`);
  - WebDAV backup (`help/storage/Backup*`);
  - HTTP/online TTS (`HttpReadAloudService`);
  - auto-read and click-action config;
  - named read styles (`ReadStyleDialog`);
  - a header/footer "tip" config (`TipConfigDialog`);
  - replacement and "purification" rules;
  - a searchable TOC [S].

  A fork adds AI chapter summaries [S]. It is the closest analogue to Korean 텍본 (text-file novel) reading.
- **Korean apps:**
  - RIDI saves font, size, width and spacing per work, with 6-step controls [S, earlier notes].
  - Millie expanded its settings from 3 to 7 steps [S, earlier notes].
  - Crema exposes 두께 (weight), 여백 (margins) and 정렬 (alignment) [S, earlier notes].
  - 텍뷰 (TekView) offers cloud download, fonts, styles, night mode, scroll or page mode, auto-scroll and TTS [S].
  - 마루뷰어 (MaruViewer) is a comic, text, scan and novel viewer with 386K+ installs and a 4.96★ rating [S].

---

## 3. Cross-app patterns

**Features every "sellable" reader has, and our status:**

| Feature | ReaderaPlus |
|---|---|
| Statistics with time-left | Missing |
| Presets you save yourself | Missing (3 fixed presets only) |
| Configurable status bar | Partial (toggles only) |
| All annotations in one place, with export | Missing |
| Background TTS | Missing |
| Some form of sync or transfer | Missing |
| Footnote popups | Missing |
| Offline dictionary | Missing |

**E-ink essentials:**
- Paginated lists: we don't have them; lists scroll.
- Refresh policy: we have it.
- Weight or sharpness control: we have weight.
- Cover as sleep screen: we don't have it, and it depends on the device.
- No animation: we have it.

**Web-novel essentials:**
- TOC search / chapter jump: missing.
- Next file at the end: missing.
- ZIP: missing.
- Replacement rules: we have them.
- Auto page turn: we have it.

**Complaints we avoid by design:** ads, a paywalled TTS, auto-scan clutter (we have scan folders and exclusions), slow big TXT, and animations.

**Complaints to guard against as we add features:**
- menu overload, so new items go behind 더보기 (more) or into sub-pages;
- sync conflicts, so sync is optional and resolved by "furthest position wins, or ask";
- features that work only for some files (Kobo's sideload problem), so everything must work on local files.

---

## 4. Candidate features (56)

**Columns:**
- **Has it:** which apps have the feature.
- **RP now:** ReaderaPlus today — Has, Partial or No.
- **Comet fit / cost:** how well it suits the Comet and what it costs in speed.
- **Pri:** priority. P0 means do it now (high value, no cost to open speed). P1 means next. P2 means later or optional. Skip means not for the Comet.

### A. Navigation and e-ink behaviour

| # | Feature | One-line description | Has it | RP now | Comet fit / cost | Pri |
|---|---|---|---|---|---|---|
| 1 | Paginated lists | TOC, library, search results and bookmarks page with ◀ ▶ or keys instead of scrolling | KOReader [S], Kindle/Kobo/PocketBook native UIs [M] | No (ListView scroll) | Excellent: less ghosting and redraw; cheap | **P0** |
| 2 | TOC search / chapter-number jump | Type "137" or text to filter or jump among 1000+ chapters | Legado (TOC search) [S] | No | Key for web novels; filtering an in-memory list costs nothing | **P0** |
| 3 | End-of-book action | At the last page: open next file in the folder / mark finished / back to library / ask | KOReader [V], Kindle "Before You Go" [M] | No | Multi-part TXT; zero cost | **P0** |
| 4 | Multi-part series as one book (합본) | Treat `1-100화.txt`, `101-200화.txt`… as one virtual book, with a continuous TOC and progress | None fully (closest: KOReader "open next file" [V]) | No | Unique selling point; reuses the per-file TXT index | P1 |
| 5 | Location history with back and forward | A stack of jumps (TOC, search, link, slider) with back and forward | KOReader `readerback` [V], ReadEra arrows [V] | Partial (one-level chip) | Cheap | P1 |
| 6 | Next/previous chapter buttons in the chrome | Two buttons beside the slider, plus long-press on keys | Kindle, Kobo, Moon+ [M], Legado [V] | Partial (tap action only) | Cheap | P1 |
| 7 | Page Flip / peek | Browse pages or chapter starts in an overlay, then return | Kindle Page Flip [S], KOReader page browser and book map [V], NeoReader thumbnails [M] | No | Needs extra layout; do a text-only peek | P2 |
| 8 | Refresh after menus / dialogs / image pages | A full refresh when chrome or popups close and on pages with images | KOReader [V], NeoReader modes [S] | Partial (every N pages, chapter) | Removes ghosting after menus; cheap | P1 |
| 9 | Refresh mode per surface | HD/Regal for the page, fast mode for menus and lists | NeoReader per app [S], KOReader "avoid black flashes in UI" [V] | Partial (page only) | Bigme xrz call per view; cheap | P2 |
| 10 | Map any key to any action, including long press | Keys and long-presses → next chapter, TOC, refresh, TTS, bookmark, invert… | Moon+ 24 operations [S], KOReader hotkeys [V], Librera [V], Legado PageKey [V] | Partial (page turns only) | The Comet has few keys, so this is high value; cheap | P1 |
| 11 | Long-press or gesture actions per tap zone | Long-press left or right → chapter jump; two-finger swipe → brightness | Moon+ [S], KOReader gestures [V] | No | Cheap; keep off by default | P2 |
| 12 | Accidental-touch guard | Optional minimum interval between turns, and ignoring edge or palm touches | Moon+ complaint [S]; KOReader tap settings [M] | Partial (40 ms duplicate drop) | Cheap | P2 |

### B. Typography and display

| # | Feature | One-line description | Has it | RP now | Comet fit / cost | Pri |
|---|---|---|---|---|---|---|
| 13 | Style profiles you save and name | Save the current typography as a named preset; long-press to rename or delete | Kindle Themes [M], KOReader Profiles [V], Legado ReadStyle [V], Moon+ themes [S] | Partial (3 fixed) | Cheap | **P0** |
| 14 | Per-book override | "Apply to this book only", with a way back to global | KOReader per-doc [M], RIDI per work [S] | No | The layout key already hashes settings; cheap | P1 |
| 15 | Sharpness / contrast toggle | Hinting and antialias options (e.g. crisp mode for small sizes), text gamma | Kobo TypeGenius sharpness [S], NeoReader contrast [S], KOReader contrast [S] | Partial (weight only) | Paint flags; test on the device | P1 |
| 16 | Status bar slots | Header and footer each with left, centre and right slots; items include chapter %, time left, title, author, custom text | KOReader [V], Legado TipConfig [V], Kobo [S] | Partial (fixed layout with toggles) | Only updates on page turns; cheap | P1 |
| 17 | Progress bar with chapter ticks | A thin 1px bar with tick marks at chapter starts, optionally chapter-only | KOReader [V], Librera [V] | No | Cheap | P1 |
| 18 | Time left in chapter and in book | Estimate from this user's measured pages per minute | Kobo [S], KOReader [V], Kindle [M] | No | Needs #34; cheap | **P0** |
| 19 | Footnote popup | EPUB note references open a small popup instead of jumping | KOReader, Kindle, Kobo, Moon+, ReadEra engine [M] | No (jump plus chip) | Less refresh than jumping away | P1 |
| 20 | Image viewer | Tap an image → full screen, fit or zoom, one refresh | KOReader, Kindle, Kobo, Moon+, Play Books [M] | No | Only when used | P2 |
| 21 | Chapter title styling | Title alignment and size, space before, "start chapters on a new page", no blank page | Legado [earlier notes], KOReader [V] | Partial (emphasis toggle) | Cheap | P2 |
| 22 | Hyphenation for Latin text | Dictionary-based hyphenation for English EPUB | KOReader, Kindle, Kobo, Moon+, Librera, ReadEra, lithiumpatch [V/M] | No | Not needed for Korean; moderate work | P2 |
| 23 | Custom CSS / style tweaks | Toggles such as "ignore publisher line-height", "hide footnotes", plus a raw CSS field | KOReader [V], Moon+, Librera [M], Readest [S] | Partial (publisher styles on/off) | Only in the parser | P2 |
| 24 | Invert with image handling | When inverted, keep photos un-inverted, or invert them too | KOReader [M], lithiumpatch [V], Kindle dark mode [M] | Partial (invert) | Cheap | P2 |
| 25 | Scroll mode / two-page landscape | Continuous scroll; two columns in landscape | Almost all [S/M] | No | Poor on narrow e-ink | Skip |

### C. Annotations and knowledge

| # | Feature | One-line description | Has it | RP now | Comet fit / cost | Pri |
|---|---|---|---|---|---|---|
| 26 | E-ink highlight styles | Grey fill, underline, strikeout, invert, box, plus a note marker | KOReader Lighten/Underline/Invert [V], NeoReader cover-up [S] | Partial (one grey style) | Cheap | P1 |
| 27 | Selection across pages | Extend a selection or quote over a page boundary | Kindle, KOReader, Moon+ [M] | No (current page only) | Moderate | P1 |
| 28 | All annotations in one place | Every quote, note and bookmark across books, searchable, grouped by book | ReadEra Premium [S], Kindle Notebook [M] | No | One SQL query | P1 |
| 29 | Export annotations | Per book or all books to Markdown, TXT, HTML, JSON or "My Clippings", saved to a folder | KOReader exporter [V], Moon+ [S], Kindle [M], Play Books to Drive [S], NeoReader [S] | Partial (share all as text) | Cheap | P1 |
| 30 | Offline dictionary popup | A dictionary file the user supplies (StarDict or DSL), shown in a popup, loaded lazily | KOReader [S], Kindle, Kobo, PocketBook [M], NeoReader [S], lithiumpatch [V] | Partial (PROCESS_TEXT, web) | Indexes load only on first lookup | P2 |
| 31 | Vocabulary list | Looked-up words with the sentence they came from; review later | Kindle [S], KOReader SRS [V], Kobo My Words [M], ReadEra words [V] | No | Low value for a native reader | P2 |
| 32 | Rate / review at finish | End-of-book card with a star rating, review and "mark read" | Kindle [M], KOReader Book status [V] | Partial (review) | Pairs with #3 | P2 |
| 33 | Share quote as an image card | Render the quote with the title in an image | Kindle app, Play Books [M] | No | Low | Skip |

### D. Statistics and habits

| # | Feature | One-line description | Has it | RP now | Comet fit / cost | Pri |
|---|---|---|---|---|---|---|
| 34 | Reading statistics | Sessions stored per day and per book; today, week, calendar, per-book time, pages per minute | KOReader [V], Moon+ Pro [S], Kobo [S], NeoReader [S] | Partial (total seconds) | Batch in memory, one INSERT on pause; about zero cost | **P0** |
| 35 | Streaks, goals, yearly summary | Days in a row, a daily-minutes goal, books finished this year | Kindle app, Kobo awards [M] | No | Derived from #34 | P2 |
| 36 | Read timer / rest reminder | Show a note after N minutes of reading | KOReader Read timer [V], Librera rest reminder [V] | No | A single alarm; cheap | P2 |

### E. TTS

| # | Feature | One-line description | Has it | RP now | Comet fit / cost | Pri |
|---|---|---|---|---|---|---|
| 37 | TTS in the background | Foreground service with a media notification, lock screen and headset buttons | ReadEra Premium [S], Moon+ headset [S], Play Books, Librera, Legado [M/V] | Partial (in-activity) | Service runs only while speaking | P1 |
| 38 | TTS replacement and skip rules | Pronunciation fixes (e.g. "ㅋㅋㅋ" → skip, "……" → pause), per book or global | Librera [V], Legado [M] | No (text rules only) | Cheap | P1 |
| 39 | TTS pacing options | Pause between sentences, read chapter titles, stop at the end of a chapter | Librera pause [V], Legado paragraph interval [S] | Partial (sleep timer) | Cheap | P2 |
| 40 | TTS to an audio file | Export a chapter or range to WAV through `synthesizeToFile` | Librera MP3 [V] | No | Only when used | P2 |

### F. Library, files, transfer and sync

| # | Feature | One-line description | Has it | RP now | Comet fit / cost | Pri |
|---|---|---|---|---|---|---|
| 41 | Open files inside ZIP | Read TXT or EPUB inside a .zip, extracted once to a cache keyed by CRC | Moon+ ZIP/RAR [S], KOReader, Librera `supportZIP` [V] | No | Korean 텍본 archives; one-time cost | P1 |
| 42 | Wi-Fi transfer | While the screen is open, serve a LAN page to upload books from a phone or PC | BooxDrop [S], Legado WebService [V], KOReader Calibre wireless and SSH [V] | No | No cost when closed; no background service | P1 |
| 43 | Automatic backup | Backup to a chosen folder daily or on exit, keeping N copies | Moon+ Dropbox/WebDAV [S], Legado WebDAV [V], KOReader cloud [V] | Partial (manual) | Cheap, on IO in onPause | P1 |
| 44 | Progress sync | Optional KOReader-sync protocol or WebDAV to share position with other devices | KOReader kosync [V], Readest [S], Moon+ [S], ReadEra/Lithium Drive [S], FBReader [S], Kindle/Kobo/Play/PocketBook [M] | No | Network; off by default | P2 |
| 45 | Series from file names | Group `소설명 1-100화.txt`-style parts into a series with natural ordering | Librera series number in title [V], Kindle/Kobo metadata [M] | Partial (EPUB series; NaturalOrder exists) | Cheap; enables #4 | P1 |
| 46 | Hide finished / smart shelves / tags | Toggle to hide "have read"; tags that cut across collections | Librera `isHideReadBook`, tags [V] | Partial (shelves, collections) | Cheap | P2 |
| 47 | More formats | FB2, MOBI/AZW3 (DRM-free), HTML, MD, DOCX, CBZ | Moon+ [S], KOReader [S], Librera [V], ReadEra [S], PocketBook [S] | No (EPUB, TXT) | Mainly a selling point; each is a new parser | P2 |
| 48 | OPDS catalogue browser | Browse Calibre-Web, Komga or other OPDS servers and download | KOReader [V], Moon+ [S], Librera [V], FBReader [M] | No | Network; only when used | P2 |
| 49 | Home-screen shortcut / widget | Pin "continue reading" or a specific book to the launcher | Librera widget [V], KOReader book shortcuts [V], Moon+ [M] | Partial (open last on start) | `ShortcutManager`; cheap | P2 |
| 50 | Sleep screen shows current cover | Write the current cover to the device's sleep-image path, or expose it | Kindle Display Cover, Kobo, PocketBook, Boox [M], KOReader coverimage [V] | No | Depends on the Comet firmware; investigate | P2 |
| 51 | App lock / private books | PIN for the app or for a hidden shelf | Moon+ Pro password [S], Librera app password [V] | No | Cheap | P2 |

### G. Accessibility and aids

| # | Feature | One-line description | Has it | RP now | Comet fit / cost | Pri |
|---|---|---|---|---|---|---|
| 52 | UI scale | Menu and list text size independent of the book font (5.84" screen) | KOReader UI font / DPI [M], NeoReader DPI per app [M] | No | Ui.kt factor; cheap | P1 |
| 53 | Screen reader and key navigation | contentDescription on every icon; D-pad focus through menus | Kindle VoiceView, Play Books TalkBack [M], KOReader key selection [V] | Partial | Cheap | P2 |
| 54 | Reading aids | OpenDyslexic font slot, bionic bolding, perception-expander guide lines | ReadEra OpenDyslexic [S], Librera bionic [V], KOReader perception expander [V] | Partial (user fonts) | Low | P2 |

### H. Trend features (optional, never on the reading path)

| # | Feature | One-line description | Has it | RP now | Comet fit / cost | Pri |
|---|---|---|---|---|---|---|
| 55 | AI "story so far" / chapter recap | On request, summarise up to the current position through a user-supplied API key | Kindle Recaps, Story So Far / Ask This Book [S], NeoReader AI [S], Legado fork [S] | No | Network, explicit button only | P2 |
| 56 | Ask about a selection | Explain a term or who a character is, spoiler-safe (text so far only) | Kindle Ask This Book [S], NeoReader AI assistant [S] | No | Same as #55 | P2 |

---

## 5. Shortlist and performance guardrails for the Comet

**Recommended order:**
1. Paginated lists (#1) and TOC search / number jump (#2).
2. End-of-book → next part (#3), then series from file names and reading parts as one book (#45 → #4).
3. Reading statistics (#34), then time left and a status bar with slots and chapter ticks (#18, #16, #17).
4. Style profiles saved by the user (#13), then a per-book override (#14).
5. Footnote popup (#19); highlight styles, selection across pages, all annotations and export (#26–#29).
6. Background TTS and TTS rules (#37, #38).
7. ZIP (#41), Wi-Fi transfer (#42), automatic backup (#43).
8. Key or long-press mapping (#10), refresh after menus (#8), sharpness (#15), UI scale (#52).

**Guardrails that keep opening instant:**
- **Nothing new on the open path.**
  - Statistics count pages and seconds in memory on each turn and write one session row in `onPause`.
  - Time-left uses page counts the app already has, times the stored pages-per-minute.
  - Profiles and per-book settings are one small row read with the book row.
- **No background service except TTS while it is speaking.**
  - The Wi-Fi server lives only while its screen is visible.
  - Sync runs only when triggered, or on pause if the user enables it.
- **Lazy by default.**
  - Dictionaries, OPDS and AI load nothing until first used.
  - ZIP entries are extracted once and then use the existing TXT index cache.
- **E-ink first.**
  - New lists page instead of scrolling.
  - New popups use `noAnimation()`.
  - Status items update only on page turns.
  - An optional full refresh runs after dismissing menus (#8).
- **Keep the UI simple.** Any new setting goes behind the existing 더보기 (more) or into a settings sub-page. The first screen of the reading popup stays at 10 rows.

---

## Sources

**Web (search snippets this session):**
- Moon+ Reader:
  - [MobileRead wiki: Moon+ Reader](https://wiki.mobileread.com/wiki/Moon+_Reader)
  - [APKMirror Moon+ 8.4](https://www.apkmirror.com/?p=5319998)
  - [goodereader: Moon+ e-ink mode](https://goodereader.com/blog/?p=371493)
  - [Yahoo Moon+ review](https://www.yahoo.com/tech/moon-reader-review-220655508.html)
  - [libgenis Moon+ review](https://libgenis.it.com/?p=113)
  - [MobileRead Moon+ threads](https://www.mobileread.com/forums/showthread.php?p=4563361)
  - [MobileRead Moon+ / Calibre](https://www.mobileread.com/forums/showthread.php?p=3552816)
- KOReader:
  - [KOReader wiki](https://github.com/koreader/koreader/wiki/)
  - [koreader.rocks README](https://koreader.rocks/doc/topics/README.md.html)
  - [dsebastien on KOReader](https://www.dsebastien.net/koreader/)
  - [HN thread (via brianlovin)](https://brianlovin.com/hn/43539103)
  - [F-Droid KOReader](https://f-droid.org/en/packages/org.koreader.launcher.fdroid/)
- ReadEra:
  - [ReadEra Premium on Apptopia](https://apptopia.com/google-play/app/org.readera.premium/about)
  - [Kimola ReadEra Premium review insights](https://kimola.com/reports/unlock-readera-premium-review-insights-elevate-reading-google-play-tr-146861)
  - [AlternativeTo ReadEra](https://www.alternativeto.net/software/readera/about/)
- Librera: [Librera F-Droid](https://cloudflare.f-droid.org/zh_Hant/packages/com.foobnix.pro.pdf.reader/), [Librera PRO on BlueStacks](https://www.bluestacks.com/campaign/com.foobnix.pro.pdf.reader/de)
- Lithium: [APKMirror Lithium](https://apkmirror.com/apk/faultexception/lithium-epub-reader/lithium-epub-reader-0-24-0-release), [pdfgear EPUB readers](https://pdfgear.com/pdf-converter/epub-readers-for-android.htm), [Kimola Lithium](https://kimola.com/reports/unlock-insights-with-the-lithium-epub-reader-feedback-report-google-play-tr-146372)
- FBReader: [FBReader book network roadmap](https://books.fbreader.org/roadmap.html), [FBReader 3.7.7 news](https://fbreader.org/news/202410290000), [FBReader Android news](https://next.fbreader.org/news/category/android), [FBReader 3.5.1](https://fbreader.org/news/202310310000)
- Kindle:
  - [Kindle Recaps and double tap (Notebookcheck)](https://www.notebookcheck.net/New-Kindle-e-reader-update-brings-Recaps-and-Double-Tap-to-Page-Turn.992797.0.html)
  - [Android Authority: Ask This Book](https://www.androidauthority.com/amazon-kindle-story-so-far-ask-this-book-3603120)
  - [Kindle bugs list](https://blog.the-ebook-reader.com/2024/12/19/kindle-bugs-list-common-software-issues-and-how-to-fix-them)
  - [Kindle wish list](https://blog.the-ebook-reader.com/2020/08/06/new-kindle-software-features-wish-list-top-5/)
  - [iClarified Paperwhite](https://www.iclarified.com/33376/amazon-unveils-new-kindle-paperwhite-ereader)
- Kobo:
  - [Kobo software features list](https://blog.the-ebook-reader.com/2021/07/10/kobo-ereaders-software-features-list/)
  - [Kobo help: reading eBooks](https://help.kobo.com/hc/en-us/articles/360017640153-Kobo-eReader-basics-Reading-eBooks)
  - [MobileRead Kobo sideload stats](https://www.mobileread.com/forums/showthread.php?p=2688730)
  - [Kobo Clara BW tweaks](https://node2.medicloud.intersales.de/news/kobo-clara-bw-top-tweaks)
- Onyx Boox:
  - [goodereader NeoReader](https://goodereader.com/blog/?p=331520)
  - [NeoReader PDF features](https://blog.the-ebook-reader.com/2024/08/05/onyx-boox-neo-reader-pdf-features-review-video/)
  - [goodereader NeoReader 3.0](https://goodereader.com/blog/?p=230316)
  - [9 ways to transfer files to Boox](https://blog.the-ebook-reader.com/2023/02/21/9-ways-to-transfer-files-to-and-from-onyx-boox-devices/)
  - [goodereader BooxDrop](https://goodereader.com/blog/?p=247091)
  - [MobileRead NeoReader position bug](https://www.mobileread.com/forums/showthread.php?p=2931728)
- PocketBook:
  - [PocketBook Reader on Softpedia](https://mobile.softpedia.com/apk/pocketbook-reader/)
  - [AlternativeTo PocketBook](https://alternativeto.net/software/pocketbook-reader/about)
  - [PocketBook 2023 update](https://blog.the-ebook-reader.com/2023/02/11/major-software-update-released-for-pocketbook-ereaders/)
  - [PB-Tracker](https://github.com/mmmmiru/PB-Tracker)
  - [MobileRead full page update](https://www.mobileread.com/forums/showthread.php?p=1245016)
- Google Play Books:
  - [Play Books on the App Store](https://apps.apple.com/app/400989007)
  - [Google blog: 15 years of Play Books](https://blog.google/products/google-play/google-play-books-15-birthday/)
  - [tuttoandroid: 100 MB uploads](https://www.tuttoandroid.net/news/google-play-books-gli-utenti-possono-caricare-ora-file-fino-a-100-mb-178690/)
  - [phonearena Play Books features](https://www.phonearena.com/news/Google-Play-Books-finally-gets-features-to-compete-with-Kindle_id34831)
- Readest: [Readest on the App Store](https://apps.apple.com/cl/app/id6738622779), [readest.com llms.txt](https://readest.com/llms.txt)
- Legado: [Legado MD3 fork release notes](https://newreleases.io/project/github/HapeLee/legado-with-MD3/release/3.26.16-beta.10), [gitea legado](https://gitea.com/wys1310/legado)
- Korean viewers: [텍뷰](https://apps.apple.com/kr/app/id1535579674), [마루뷰어 (appgoblin)](https://appgoblin.info/apps/neo.maru)

**Source code and repositories read directly [V]:**
- `koreader/koreader` at commit `7fedb85`: `plugins/*/_meta.lua`, `plugins/statistics.koplugin/main.lua`, `plugins/exporter.koplugin/target/*`, `frontend/apps/reader/modules/*` (`readerhighlight`, `readerstatus`), and `readerfooter.lua`.
- `foobnix/LibreraReader`: `README.md` and `AppState.java` fields.
- `gedoor/legado` (`refs/pull/5804`): the `service/`, `web/`, `help/storage/` and `ui/book/read/config/` trees.
- `pgaskin/lithiumpatch`: `README.md`.

Local clones and notes are in `/tmp/claude-0/-home-user-readeraplus/3bb6d2e9-b507-53f5-9b88-fb1342d5f88b/scratchpad/`, in the folders `koreader/`, `librera/`, `legado/` and `lithiumpatch/`, plus the files `AppState.java`, `readerfooter.lua` and `research_*.md`. Project state was checked against `/home/user/readeraplus/docs/ARCHITECTURE.md` and `/home/user/readeraplus/app/src/main/java/com/ggumtak/readeraplus/settings/ReaderSettings.kt`.