# Lane EX-N

LANE EX-N (stub owner tag in code: "EX-N") — navigation dialogs: quotes in the contents dialog, colour chips, hub link, TOC title
Main files (relative to app/src/main/java/com/ggumtak/readeraplus/): reader/extras/ContentsDialog.kt, reader/extras/SearchPanel.kt, reader/extras/InfoDialogs.kt, reader/extras/Episodes.kt, reader/extras/FontChooser.kt, reader/extras/RulesDialog.kt, reader/extras/RuleList.kt, reader/extras/TtsService.kt
Test files (relative to app/src/test/java/com/ggumtak/readeraplus/): reader/extras/TocTextTest.kt, reader/extras/EpisodesTest.kt, reader/extras/RuleListTest.kt, reader/extras/TextSearchTest.kt; new pure tests under reader/extras/ for any helper you extract
Module-mode flags <OWN>: --own reader/extras/ContentsDialog.kt --own reader/extras/SearchPanel.kt --own reader/extras/InfoDialogs.kt --own reader/extras/Episodes.kt --own reader/extras/FontChooser.kt --own reader/extras/RulesDialog.kt --own reader/extras/RuleList.kt --own reader/extras/TtsService.kt --own reader/extras/TocTextTest.kt --own reader/extras/EpisodesTest.kt --own reader/extras/RuleListTest.kt --own reader/extras/TextSearchTest.kt  (+ --own for every new file you add, main or test)
Status note: docs/next/EX_N_STATUS.md

TASKS
PLAN §4 "EX-N" W1 part only (W2 thumbnails — ThumbsTab, InkPager.PageTarget, InkNumPad — come later; do NOT do them now):
- N §7.2: in the contents dialog's 인용문/북마크 lists, the swatch column as a position-based row tap (no clickable child, so InkPager drag-to-page still works on quote rows), filter chips "[전체 N] [● n] …" per style, "색 바꾸기" action (Library.updateQuoteStyle / setQuoteStyles, DA-C's API), Highlight style passed through, the link "모든 책의 노트" → ui.notes.NotesActivity.open(ctx, …) (frozen companion), "공유" of all quotes capped at TextActions.SHARE_MAX_CHARS; the "· 위치 바뀜" suffix for a quote whose place no longer matches (K2: use the host's result when known, else the sig — find what the frozen ReaderPanels/NotePlaceHost contract gives you; if nothing fits, add a contractRequest and use the sig).
- U polish 10: TOC title 20 sp bold through the kit toolbar style.
Accept: InkPager drag-to-page still works on quote rows; CI 83 expectations (PLAN §5.3 row 32: swatch column, chips "[전체 2] [● 1] [● 1]", link "모든 책의 노트").
