package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.PopupWindow
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.reader.ReaderHost
import kotlinx.coroutines.Job
import java.lang.ref.WeakReference

/**
 * Reader panels (ReadEra-like): reading settings popup, contents (목차 · 북마크 · 인용문), in-book search,
 * review, document properties and go-to-page. Wired by ReaderActivity. All calls on the main thread.
 *
 * Lifecycle: every window a panel opens (full-screen dialogs, the settings popup and its menus, info and prompt
 * dialogs) is tracked per activity, so the reader can close them all with [dismissAll] when the book closes or the
 * activity is destroyed; BACK should first try [closeSearchBar].
 */
object ReaderPanels {
    /** ReadEra-style reading settings popup anchored under the toolbar gear. */
    fun showReadingSettings(host: ReaderHost, anchor: View) {
        ReadingSettingsPopup(host, anchor).show()
    }

    /** Full-screen TOC / bookmarks / quotes (tabs 목차 · 북마크 · 인용문). */
    fun showContents(host: ReaderHost, initialTab: Int = 0) {
        ContentsDialog(host, initialTab).show()
    }

    /** Full-screen in-book search with results list (snippet + page). */
    fun showSearch(host: ReaderHost, initialQuery: String = "") {
        SearchNavBar.remove()
        SearchPanel.show(host, initialQuery)
    }

    /** "내 리뷰" dialog. */
    fun showReview(host: ReaderHost) {
        InfoDialogs.review(host)
    }

    /** Document properties dialog (also used from the library with document == null). */
    fun showDocumentInfo(activity: Activity, book: Book, document: BookDocument?) {
        InfoDialogs.documentInfo(activity, book, document)
    }

    /**
     * Go-to-page dialog (page number or percent). When [host] also implements [PageJumpHost] the chosen page is
     * shown exactly and drawn once; otherwise the page is estimated first and corrected when its section is laid out.
     */
    fun showGoTo(host: ReaderHost) {
        InfoDialogs.goTo(host)
    }

    /**
     * Closes the search-results bar over [host]'s page (and clears its search highlight), if shown. Returns true
     * when a bar was closed, so the reader's BACK handling can close it first and only leave the book on the next
     * BACK. Safe to call at any time (does nothing and returns false when no bar is shown). Main thread only.
     */
    fun closeSearchBar(host: ReaderHost): Boolean {
        val shown = SearchNavBar.isShown(host)
        SearchPanel.clearHighlight(host)
        if (shown) SearchNavBar.remove()
        return shown
    }

    /**
     * Closes everything the panels opened for [host]: the 목차/검색 full-screen dialogs, the reading-settings popup
     * and its menus, the font chooser, info / review / go-to-page / memo / TTS-settings dialogs and the
     * search-results bar (with its highlight). Dismissing a dialog cancels its background work (TOC anchor resolution, search scan), pending
     * loads that would open a dialog are cancelled, and the remembered search results and cached section texts of
     * the old document are dropped. A pending reading-settings change is still applied (flushed) on dismiss.
     *
     * Call on the main thread when the book closes (first thing in closeCurrentBook(), before the session / book
     * are released) and at the start of onDestroy(). Idempotent; cheap when nothing is open. Not for a re-open with
     * new parse options (that is started from the settings popup, which must stay open): use [closeSearchBar] there.
     * Sub-dialogs opened through the ui.kit helpers (confirm / prompt / chooser) are not tracked; their callbacks
     * only save settings or act on data of the book they were opened for.
     */
    fun dismissAll(host: ReaderHost) {
        val activity = runCatching { host.activity }.getOrNull()
        if (activity != null) PanelRegistry.closeAll(activity)
        runCatching { closeSearchBar(host) }
        SearchPanel.forget()
        SearchPanel.dropTextCache()
    }
}

/**
 * Optional [ReaderHost] capability used by 페이지 이동 (checked with `host as? PageJumpHost`).
 * ReaderActivity implements it with its existing seek-bar jump (navigateTo(section, 0, pageIndex, JUMP)).
 */
interface PageJumpHost {
    /**
     * Shows page [pageIndex] (0-based, clamped to the section) of [section], laying the section out first when
     * needed, with a single page draw (no approximate page first). When [remember] the position before the jump
     * is pushed for the "돌아가기" chip. Main thread only.
     */
    fun goToPage(section: Int, pageIndex: Int, remember: Boolean)

    /**
     * Reading progress 0..1 exactly as the footer shows it (by pages once counted, else by characters), so the
     * go-to dialog's "현재 N%" and the footer agree.
     */
    fun progressFraction(): Float

    /**
     * Jumps to [fraction] (0..1) of the book using the same measure as [progressFraction]: typing N% lands on the
     * page whose footer reads N%. Remembers the old position for the "돌아가기" chip. Main thread only.
     */
    fun goToProgress(fraction: Float)
}

/**
 * Windows and loading jobs opened by the panels, per owning activity, so [ReaderPanels.dismissAll] can close them.
 * Weak references only: a showing dialog / popup is reachable from the window manager and a running job from its
 * dispatcher, so an entry never keeps anything alive by itself. Main thread only.
 */
internal object PanelRegistry {
    private class Entry(owner: Activity, target: Any) {
        val owner = WeakReference(owner)
        val target = WeakReference(target)
    }

    private val entries = ArrayList<Entry>()

    /** Tracks a dialog that is already showing. */
    fun <T : Dialog> dialog(owner: Context, d: T): T = d.also { add(owner, it) }

    /** Tracks a popup that is already showing. */
    fun popup(owner: Context, p: PopupWindow): PopupWindow = p.also { add(owner, it) }

    /** Tracks a job whose result would open a window (cancelled by closeAll). */
    fun job(owner: Context, j: Job): Job = j.also { add(owner, it) }

    fun closeAll(owner: Activity) {
        prune()
        val mine = entries.filter { it.owner.get() === owner }
        if (mine.isEmpty()) return
        entries.removeAll(mine.toSet())
        // Newest first: sub-dialogs (confirm, menus) go before the panel that opened them.
        for (e in mine.asReversed()) {
            when (val t = e.target.get()) {
                is Job -> t.cancel()
                is Dialog -> runCatching { t.dismiss() }
                is PopupWindow -> runCatching { t.dismiss() }
            }
        }
    }

    /** Number of live tracked entries for [owner] (tests / diagnostics). */
    fun openCount(owner: Activity): Int {
        prune()
        return entries.count { it.owner.get() === owner }
    }

    private fun add(owner: Context, target: Any) {
        val a = owner.findActivity() ?: return
        prune()
        entries.add(Entry(a, target))
    }

    private fun prune() {
        entries.removeAll { e ->
            val t = e.target.get()
            e.owner.get() == null || t == null ||
                (t is Job && !t.isActive) || (t is Dialog && !t.isShowing) || (t is PopupWindow && !t.isShowing)
        }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        var depth = 0
        while (c != null && depth++ < 16) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }
}
