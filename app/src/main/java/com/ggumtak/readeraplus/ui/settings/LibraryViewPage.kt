package com.ggumtak.readeraplus.ui.settings

import android.view.View
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.LibrarySort
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.chooser

/**
 * "서재 보기": how the library lists its books (정렬, 보기, 목록 넘기기) and what the app opens with (시작할 때 읽던 책
 * 열기). They were rows of the main list itself, between rows that open pages (the user, 2026-11-20: the options felt
 * scattered); the main list now only moves on. 정렬 and 보기 are also the first two rows of the library's ⋮ menu, which
 * edit the same [com.ggumtak.readeraplus.settings.AppSettings] fields.
 */
internal class LibraryViewPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_LIBRARY_VIEW, TITLE) {
    override fun build(): View {
        val app = Settings.app
        val body = ctx.pageBody()

        body.section("목록")
        var sortRow: View? = null
        sortRow = ctx.valueRow("정렬", app.librarySort.label) {
            val all = LibrarySort.entries
            ctx.chooser("정렬", all.map { it.label }, all.indexOf(Settings.app.librarySort)) { i ->
                editApp { it.copy(librarySort = all[i]) }
                sortRow?.setSummary(all[i].label)
            }
        }.also(body::addView)
        var listModeRow: View? = null
        listModeRow = ctx.valueRow("보기", app.libraryListMode.label) {
            val all = LibraryListMode.entries
            ctx.chooser("보기", all.map { R3Rows.libraryViewChoice(it) }, all.indexOf(Settings.app.libraryListMode)) { i ->
                editApp { it.copy(libraryListMode = all[i]) }
                listModeRow?.setSummary(all[i].label)
            }
        }.also(body::addView)
        var listPagingRow: View? = null
        listPagingRow = ctx.valueRow("목록 넘기기", R3Rows.listPaging(app.listPaging)) {
            val opts = R3Rows.LIST_PAGINGS
            ctx.chooser("목록 넘기기", opts.map { R3Rows.listPaging(it) }, R3Rows.listPagingIndex(Settings.app.listPaging)) { i ->
                editApp { it.copy(listPaging = opts[i]) }
                listPagingRow?.setSummary(R3Rows.listPaging(opts[i]))
            }
        }.also(body::addView)

        body.section("시작")
        // Off, the app still comes back to a book the system closed while it was open (LibraryText.startMode: RESUME).
        body.addView(ctx.toggleRow("시작할 때 읽던 책 열기", null, app.openLastOnStart) { v ->
            editApp { it.copy(openLastOnStart = v) }
        })
        return ctx.pageScroll(body)
    }

    companion object {
        const val TITLE = "서재 보기"
    }
}
