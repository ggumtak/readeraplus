package com.ggumtak.readeraplus.reader

/**
 * What one BACK press closes in the reader. The topmost thing on screen goes first, so BACK never stops TTS or drops
 * the search-results bar while the bars are still covering the page; the book is left only when nothing is open.
 */
internal object BackOrder {

    /** The one thing a BACK press closes, in the order [next] checks them. */
    enum class Step { END_PANEL, SELECTION, CHROME, TTS, SEARCH_BAR, EXIT }

    /**
     * The step for a BACK press: the end-of-book panel, then a text selection, then the top and bottom bars
     * ([chrome]), then speaking TTS, then the search-results bar; [Step.EXIT] when none of them is up.
     */
    fun next(endPanel: Boolean, selection: Boolean, chrome: Boolean, tts: Boolean, searchBar: Boolean): Step = when {
        endPanel -> Step.END_PANEL
        selection -> Step.SELECTION
        chrome -> Step.CHROME
        tts -> Step.TTS
        searchBar -> Step.SEARCH_BAR
        else -> Step.EXIT
    }

    /**
     * Whether the BACK press that does [step] also clears a search highlight left behind: 검색 결과 목록 hides the
     * results bar but keeps its highlight, so with the bar gone ([searchBar] false) any step that stays in the book takes
     * it. While the bar shows, its highlight stays with it (the bar's own step clears both); leaving clears everything.
     */
    fun dropsSearchHighlight(step: Step, searchBar: Boolean): Boolean = !searchBar && step != Step.EXIT
}
