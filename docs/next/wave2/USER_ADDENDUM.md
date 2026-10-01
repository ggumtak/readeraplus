# Latest user requests (after the three specs were written) — Korean originals paraphrased

U1. RECENTS BUG: "When I go to the recent-apps (multitasking) screen and come back, the book I was reading in ReaderaPlus is
    closed and I land on the library (book list) screen." Must be fixed: returning from recents must show the same book at the
    same page. Investigate: manifest launchMode/taskAffinity/noHistory/excludeFromRecents/documentLaunchMode, finish() calls in
    onPause/onStop/onUserLeaveHint/onTrimMemory, process death + ReaderActivity recreation (savedInstanceState / intent extras
    → does startOpen fail and finish()?), LibraryActivity singleTask/clearTask flags, FLAG_ACTIVITY_NO_ANIMATION usage, the
    vendor (Samsung One UI in the screenshot) recents behaviour, low-memory kills while backgrounded. Also the library must
    reopen the last book if the reader was killed (option?).
U2. FOOTER INDEPENDENT: "Even when I turn the bottom info display on, the text must not get pushed; the footer just exists
    separately, fixed — no effect on the text." => turning header/footer/progress line on/off must NOT change the text box
    (no re-pagination). The bands live inside the page margins.
U3. TOP/BOTTOM MARGINS default 40 (like the side margins: 40 dp shown as 0).
U4. PAGE-BREAK MODE: "I'm not sure whether page breaks by paragraph or by character are better, so let me choose." =>
    a setting: 문단 단위 (a paragraph that fits on one page is never split across pages; the page ends early instead) vs
    줄 단위 (fill every page line by line; paragraphs split; today's widow/orphan behaviour) — decide labels/default.
U5. VOLUME KEYS: allow inverting the direction of volume up/down page turning.
U6. POSITION STABILITY: "Changing margins, the top chapter title etc. should not move me away from where I am reading —
    ideally the exact same position, no text shifting. Especially: opening/showing anything must never push the text.
    Margins/indent/paragraph spacing will reflow of course, but the place I'm reading should stay almost fixed."
    => (a) nothing that is shown/hidden (chrome, panels, return strip, footer/header, progress line, TTS bar…) may change the
    text box; (b) on any relayout the first visible character stays the first visible character of the new current page
    (anchored pagination: e.g. a forced page start at the anchor's line, with the page before it allowed to be shorter —
    design the exact rule, the engine change, cache/count implications, and how later relayouts clean it up).
