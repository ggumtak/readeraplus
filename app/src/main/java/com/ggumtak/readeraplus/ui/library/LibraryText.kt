package com.ggumtak.readeraplus.ui.library

import android.content.Intent
import android.view.KeyEvent
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.data.ShelfGroup
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.reader.extras.Josa
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.ui.settings.SettingsFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone

/**
 * Pure (JVM-testable) helpers of the library screen: display strings, path conversion for SAF picks,
 * file naming for imports, key mapping and back-navigation order. Wording follows the glossary (책, never 문서;
 * shelf names only through `Shelf.X.label`).
 */
internal object LibraryText {

    /** Shelves that first list [ShelfGroup]s and only show books after a group is picked. */
    private val GROUPED = setOf(Shelf.AUTHORS, Shelf.SERIES, Shelf.COLLECTIONS, Shelf.FORMATS, Shelf.FOLDERS)

    fun isGrouped(shelf: Shelf): Boolean = shelf in GROUPED

    /**
     * The drawer's shelves in their groups, top to bottom (NOTES_SPEC §10.1): the reading shelves, the grouped ones,
     * the trash. 독서 노트 · 단어장 follow the first group and 설정 · 읽기 기록 the last; a line parts the groups. The
     * stored shelf names (the enum) keep their order.
     */
    val DRAWER_SHELVES: List<List<Shelf>> = listOf(
        listOf(Shelf.READING_NOW, Shelf.ALL, Shelf.FAVORITES, Shelf.TO_READ, Shelf.HAVE_READ),
        listOf(Shelf.COLLECTIONS, Shelf.AUTHORS, Shelf.SERIES, Shelf.FOLDERS, Shelf.DOWNLOADS, Shelf.FORMATS),
        listOf(Shelf.TRASH),
    )

    private val UNITS = arrayOf("KB", "MB", "GB", "TB")

    /**
     * Compact size like ReadEra's card: "820KB", "3.4MB", "15MB" (binary units, one decimal below 10,
     * no trailing ".0").
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)}B"
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < UNITS.lastIndex) {
            v /= 1024
            i++
        }
        if (v < 10) {
            val r = Math.round(v * 10) / 10.0
            if (r < 10) {
                val whole = r.toLong()
                return if (r == whole.toDouble()) "$whole${UNITS[i]}" else String.format(Locale.US, "%.1f%s", r, UNITS[i])
            }
        }
        val rounded = Math.round(v)
        if (rounded >= 1024 && i < UNITS.lastIndex) return "1${UNITS[i + 1]}"
        return "$rounded${UNITS[i]}"
    }

    /** "TXT 3.4MB". */
    fun metaLine(formatLabel: String, sizeBytes: Long): String = "$formatLabel ${formatSize(sizeBytes)}"

    /**
     * The 자세히 card's meta line (NOTES_SPEC §10.2, library.md §2.2), the useful part first: "3일 전 · TXT 3.4MB",
     * "시리즈명 3 · TXT 3.4MB" for a book in a series, "TXT 3.4MB" for one never opened, and "파일 없음 · TXT 3.4MB" for
     * a trashed book whose file is gone. Built on IO in `reload()`.
     */
    fun metaLine(formatLabel: String, sizeBytes: Long, series: String?, seriesIndex: Float?, lastRead: String, missing: Boolean): String {
        val base = metaLine(formatLabel, sizeBytes)
        val first = when {
            missing -> "파일 없음"
            !series.isNullOrBlank() -> seriesLabel(series, seriesIndex)
            else -> lastRead
        }
        return if (first.isEmpty()) base else "$first · $base"
    }

    /** "시리즈명 3" ("시리즈명 2.5" for a half step, the bare name without an index). */
    fun seriesLabel(series: String, index: Float?): String {
        val name = series.trim()
        if (index == null || index <= 0f) return name
        val whole = index.toLong()
        val num = if (index == whole.toFloat()) whole.toString() else String.format(Locale.US, "%.1f", index)
        return "$name $num"
    }

    /** When a book was last read ([ago]), or "" for one never opened. */
    fun lastRead(now: Long, lastReadAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (lastReadAt <= 0L) "" else ago(lastReadAt, now, zone)

    /**
     * The 간단히 row's second line: "★ 작가 · 3일 전". A leading "★ " for a favourite, then the author (left out when
     * blank) and one state: "파일 없음", "다 읽음", "새 책" (never opened), "읽을 책", else when it was last read
     * ([lastRead]). No format or size: the row is for finding a book, not for its file.
     */
    fun compactMeta(
        author: String, favorite: Boolean, opened: Boolean, haveRead: Boolean, toRead: Boolean, missing: Boolean,
        lastRead: String,
    ): String {
        val sb = StringBuilder(32)
        if (favorite) sb.append("★ ")
        val a = author.trim()
        val state = when {
            missing -> "파일 없음"
            haveRead -> "다 읽음"
            !opened -> "새 책"
            toRead -> "읽을 책"
            else -> lastRead
        }
        sb.append(a)
        if (a.isNotEmpty() && state.isNotEmpty()) sb.append(" · ")
        sb.append(state)
        return sb.toString()
    }

    /** Integer percent label ("34%"), or "" for a book that was never opened. Floors so 99.7% isn't "100%". */
    fun percent(progress: Float, opened: Boolean): String {
        if (!opened) return ""
        val p = Math.floor(progress.toDouble() * 100.0 + 1e-3).toInt().coerceIn(0, 100)
        return "$p%"
    }

    /**
     * The word that stands in for a progress bar or percent: "다 읽음" for a book marked read, "새 책" for one never
     * opened, else null (show the progress). Cards and cells use it only for unopened books (their flag icons show
     * 다 읽음).
     */
    fun statusTag(opened: Boolean, haveRead: Boolean): String? = when {
        haveRead -> "다 읽음"
        !opened -> "새 책"
        else -> null
    }

    /**
     * When a book was last read, counted in calendar days of [zone] (not 24-hour spans, so last night reads 어제):
     * "오늘", "어제", "3일 전" (up to 29), "5개월 전" (30-day months), "2년 전". A time in the future reads 오늘.
     */
    fun ago(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
        )
        return when {
            days <= 0L -> "오늘"
            days == 1L -> "어제"
            days < 30L -> "${days}일 전"
            days < 365L -> "${days / 30}개월 전"
            else -> "${days / 365}년 전"
        }
    }

    /** The "보기" chooser of the library and of 설정 (NOTES_SPEC §10.2): "큰 표지 (3열)". */
    fun modeChoice(mode: LibraryListMode): String = when (mode) {
        LibraryListMode.LIST -> mode.label
        LibraryListMode.COMPACT -> "${mode.label} (한 줄)"
        LibraryListMode.GRID -> "${mode.label} (3열)"
        LibraryListMode.COVERS -> "${mode.label} (4열)"
    }

    /** The toolbar view toggle's cycle: 자세히 → 간단히 → 큰 표지 → 작은 표지 → 자세히 (the enum's order). */
    fun nextListMode(mode: LibraryListMode): LibraryListMode {
        val all = LibraryListMode.entries
        return all[(mode.ordinal + 1) % all.size]
    }

    /** Book-menu item for a shelf flag: "읽을 책에 추가" / "읽을 책에서 빼기" (the reader's toasts use the same verbs). */
    fun flagMenuLabel(shelf: Shelf, on: Boolean): String = if (on) "${shelf.label}에서 빼기" else "${shelf.label}에 추가"

    // ---- multi-select (T1-13)

    /** Title of the selection toolbar: "3권 선택", or a prompt while nothing is checked. */
    fun selectionTitle(count: Int): String = if (count <= 0) "책을 고르세요" else "${count}권 선택"

    /** Result of [다 읽음] / [읽을 책]: "다 읽은 책에 3권을 추가했습니다". */
    fun addedToShelf(shelf: Shelf, count: Int): String = "${shelf.label}에 ${count}권을 추가했습니다"

    /** Result of [컬렉션]: "‘무협’에 3권을 추가했습니다". */
    fun addedToCollection(name: String, count: Int): String = "‘$name’에 ${count}권을 추가했습니다"

    /** Result of moving books to the trash (one book: the book menu's wording). */
    fun trashedMessage(count: Int): String = if (count == 1) "휴지통으로 옮겼습니다" else "${count}권을 휴지통으로 옮겼습니다"

    /** Question before a batch move to the trash (the trash has no batch restore, so several books ask first). */
    fun trashQuestion(count: Int): String = "고른 책 ${count}권을 휴지통으로 옮길까요? 휴지통에서는 한 권씩 복원할 수 있습니다."

    // ---- deleting books that have notes (NOTES_SPEC §10.1)

    /** Appended to a delete / empty-trash question when the [books] carry [notes] notes (nothing when 0). */
    fun notesWarning(notes: Int, books: Int = 1): String = when {
        notes <= 0 -> ""
        books > 1 -> "\n\n이 책들의 노트 ${notes}개도 함께 지워집니다."
        else -> "\n\n이 책의 노트 ${notes}개도 함께 지워집니다."
    }

    /** "영구 삭제" question for one book. */
    fun deleteMessage(title: String, notes: Int): String =
        "‘$title’${Josa.eulReul(title)} 서재에서 삭제할까요?" + notesWarning(notes)

    /** "휴지통 비우기" question; [books] = the books in the trash. */
    fun emptyTrashMessage(notes: Int, books: Int): String =
        "휴지통을 비울까요? 휴지통의 책이 모두 서재에서 삭제됩니다." + notesWarning(notes, books)

    // ---- auto backup and the restore offer (scroll SPEC §3.2, §3.4)

    /**
     * The offer's message under its question title: when, what and where. [location] names where the file is
     * ("다운로드/ReaderaPlus/backup", "다운로드" or "문서"); [lateAnswer]: this install already has reading history of its
     * own (the offer is answered late).
     */
    fun restoreOfferMessage(
        createdAt: Long, books: Int, bookmarks: Int, quotes: Int, location: String, lateAnswer: Boolean,
        tz: TimeZone = TimeZone.getDefault(), now: Long = System.currentTimeMillis(),
    ): String {
        val sb = StringBuilder(160)
        sb.append(SettingsFormat.dateTime(createdAt, tz, now)).append(" 백업\n")
        sb.append("책 ").append(books).append("권 · 북마크 ").append(bookmarks).append("개 · 인용문 ").append(quotes).append("개\n")
        sb.append("위치: ").append(location).append("\n\n")
        sb.append("책 파일은 지금 있는 곳에서 다시 찾습니다.")
        if (lateAnswer) sb.append('\n').append(RESTORE_LATE_LINE)
        return sb.toString()
    }

    const val RESTORE_LATE_LINE = "지금 설정은 백업의 설정으로 바뀌고, 책마다 더 최근에 읽은 위치가 남습니다."

    /** Where a backup file lies, for the offer: the auto-backup folder, else the top-level folder it was found in. */
    fun backupLocation(auto: Boolean, parentName: String?, autoLabel: String): String = when {
        auto -> autoLabel
        parentName.equals("Documents", ignoreCase = true) -> "문서"
        else -> "다운로드"
    }

    /** Toast after a restore. */
    fun restoredMessage(books: Int): String = "책 ${books}권의 기록을 복원했습니다"

    /** The one-time status line after this install's first auto backup (the place is on the 백업·복원 page). */
    const val AUTO_BACKUP_NOTICE = "자동 백업을 저장했습니다 · 설정의 ‘백업·복원’에서 끌 수 있습니다"

    // ---- import / scan results

    /** "책 3권을 추가했습니다" after a multi-file pick. */
    fun importedMessage(count: Int): String = if (count <= 0) "추가한 책이 없습니다" else "책 ${count}권을 추가했습니다"

    /** "책 12권을 가져왔습니다" after a folder import. */
    fun treeImportedMessage(count: Int): String = if (count <= 0) "가져온 책이 없습니다" else "책 ${count}권을 가져왔습니다"

    /** "스캔 완료: 책 120권". */
    fun scanDoneMessage(total: Int): String = "스캔 완료: 책 ${total}권"

    /**
     * The one toast after a folder was picked for the scan: "‘Books’ 폴더를 스캔에 추가했습니다", or that the scan
     * covers it already; " · 스캔 중" when a scan was running already (the new folder waits for the next one).
     */
    fun scanFolderMessage(path: String, added: Boolean, scanning: Boolean): String =
        (if (added) "‘${folderName(path)}’ 폴더를 스캔에 추가했습니다" else "이미 스캔 범위에 있는 폴더입니다") +
            if (scanning) " · 스캔 중" else ""

    /** Last path segment of a folder path ("/storage/emulated/0/Books" → "Books"). */
    fun folderName(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return "/"
        return trimmed.substringAfterLast('/').ifEmpty { trimmed }
    }

    /**
     * Title shown for a group: folders use their last path segment (whether the data layer labels them with
     * the full path or already with a short name), everything else its label.
     */
    fun groupTitle(shelf: Shelf, group: ShelfGroup): String {
        if (shelf != Shelf.FOLDERS) return group.label
        val l = group.label
        return if (l.isEmpty() || l.contains('/')) folderName(l.ifEmpty { group.key }) else l
    }

    /** Second line of a group row: the full folder path for FOLDERS (when it adds information), else null. */
    fun groupSubtitle(shelf: Shelf, group: ShelfGroup): String? {
        if (shelf != Shelf.FOLDERS) return null
        val path = when {
            group.key.contains('/') -> group.key
            group.label.contains('/') -> group.label
            else -> group.key.ifEmpty { group.label }
        }
        return path.takeIf { it.isNotEmpty() && it != groupTitle(shelf, group) }
    }

    /** Groups whose label (or folder path) contains [query] (case-insensitive); all when blank. */
    fun filterGroups(groups: List<ShelfGroup>, query: String): List<ShelfGroup> {
        val q = query.trim()
        if (q.isEmpty()) return groups
        return groups.filter { it.label.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true) }
    }

    /** True for names the app can open (.epub / .txt, any case). */
    fun isBookName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".epub") || n.endsWith(".txt")
    }

    /**
     * Converts a DocumentsProvider document/tree id to a filesystem path when the provider is the platform's
     * external storage (`primary:Books/a.txt`, `1234-ABCD:Novels`, `home:x`) or a downloads `raw:` id.
     * [authority] null = assume external storage. Returns null when no path can be derived.
     */
    fun docIdToPath(authority: String?, docId: String, primaryRoot: String): String? {
        if (docId.startsWith("raw:")) return docId.removePrefix("raw:").takeIf { it.startsWith("/") }
        if (authority != null && authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1).trim('/')
        val base = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot.trimEnd('/')
            volume.equals("home", ignoreCase = true) -> primaryRoot.trimEnd('/') + "/Documents"
            VOLUME_ID.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private val VOLUME_ID = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f]{8,16}")

    private val BAD_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /**
     * File name to store an imported document under: the display name with unsafe characters replaced, and an
     * extension derived from [mime] when the name has none we support. Null when the type is unsupported.
     */
    fun importFileName(displayName: String?, mime: String?): String? {
        val clean = (displayName ?: "").replace(BAD_CHARS, "_").trim().trimStart('.')
        if (clean.isNotEmpty() && isBookName(clean)) return clean
        val ext = when (mime?.lowercase(Locale.ROOT)) {
            "application/epub+zip" -> ".epub"
            "text/plain" -> ".txt"
            else -> return null
        }
        val base = clean.ifEmpty { "가져온 책" }
        return base + ext
    }

    /** "a.txt" → "a (1).txt", "a (2).txt", … until [exists] is false. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    /** Grid columns for a width: as many [cellDp]-wide cells as fit, at least [min]. */
    fun gridColumns(widthPx: Int, density: Float, cellDp: Int = 110, min: Int = 2): Int {
        if (density <= 0f || widthPx <= 0) return min
        return ((widthPx / density) / cellDp).toInt().coerceAtLeast(min)
    }

    /**
     * Library list paging by hardware keys: +1 = scroll a page down, -1 = up, 0 = not ours.
     * Volume keys only when [volumeKeysTurn] (swapped by [invertVolume]); page keys always; learned keys too.
     */
    fun keyDirection(
        keyCode: Int,
        volumeKeysTurn: Boolean,
        invertVolume: Boolean,
        nextKeys: Set<Int>,
        prevKeys: Set<Int>,
    ): Int = when {
        // A learned key must never swallow navigation keys (the user could not leave the library).
        keyCode in RESERVED_KEYS -> 0
        keyCode in nextKeys -> 1
        keyCode in prevKeys -> -1
        keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> 1
        keyCode == KeyEvent.KEYCODE_PAGE_UP -> -1
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && volumeKeysTurn -> if (invertVolume) -1 else 1
        keyCode == KeyEvent.KEYCODE_VOLUME_UP && volumeKeysTurn -> if (invertVolume) 1 else -1
        else -> 0
    }

    /**
     * [keyDirection] with the reader's key bindings first (T1-4, same resolution order as the reader's KeyMap): a key
     * bound to a next/previous action pages, one bound to anything else (including "없음(시스템에 맡김)") is left to the
     * system, an unbound key falls back to [keyDirection].
     */
    fun pageDirection(keyCode: Int, app: AppSettings): Int {
        if (keyCode in RESERVED_KEYS) return 0
        if (app.keyBindings.isNotEmpty()) app.keyBindings[keyCode]?.let { return boundDirection(it) }
        return keyDirection(keyCode, app.volumeKeysTurn, app.invertVolumeKeys, app.nextPageKeys, app.prevPageKeys)
    }

    /** Library paging meaning of a bound reader action: +1 next, -1 previous, 0 not a paging action. */
    fun boundDirection(action: TapAction): Int = when (action) {
        TapAction.NEXT, TapAction.NEXT_CHAPTER -> 1
        TapAction.PREV, TapAction.PREV_CHAPTER -> -1
        else -> 0
    }

    private val RESERVED_KEYS = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_APP_SWITCH,
    )

    enum class BackStep { CLOSE_DRAWER, END_SELECTION, CLOSE_SEARCH, LEAVE_GROUP, FINISH }

    /** Back closes the drawer, then leaves multi-select, then closes the search row, then the open group, then the app. */
    fun backStep(drawerOpen: Boolean, searchOpen: Boolean, inGroup: Boolean, selecting: Boolean = false): BackStep = when {
        drawerOpen -> BackStep.CLOSE_DRAWER
        selecting -> BackStep.END_SELECTION
        searchOpen -> BackStep.CLOSE_SEARCH
        inGroup -> BackStep.LEAVE_GROUP
        else -> BackStep.FINISH
    }

    /**
     * Open the last book on start ([AppSettings.openLastOnStart]): go straight to the last-read book only on a fresh
     * launcher start. A task restarted from recents has lost its activities; [startMode] restores an open reader.
     */
    fun shouldOpenLast(enabled: Boolean, restored: Boolean, action: String?, flags: Int): Boolean =
        enabled && !restored && action == Intent.ACTION_MAIN &&
            flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

    enum class StartMode { LIBRARY, OPEN_LAST, RESUME }

    /** Resume an unclosed reader only at the first activity of a new process, with a bounded crash streak. */
    fun startMode(
        openLastOnStart: Boolean, restored: Boolean, action: String?, flags: Int,
        firstActivity: Boolean, resumeBookId: Long, resumeTries: Int, maxTries: Int = 2,
    ): StartMode = when {
        firstActivity && resumeBookId > 0 && resumeTries < maxTries && (restored || action == Intent.ACTION_MAIN) ->
            StartMode.RESUME
        shouldOpenLast(openLastOnStart, restored, action, flags) -> StartMode.OPEN_LAST
        else -> StartMode.LIBRARY
    }

    /** True when a background rescan is due ([lastScanAt] 0 = never scanned). */
    fun rescanDue(lastScanAt: Long, now: Long, maxAgeMs: Long = 30L * 60 * 1000): Boolean =
        lastScanAt <= 0L || now - lastScanAt > maxAgeMs || now < lastScanAt

    /** Status row text while scanning / importing, or null when idle. */
    fun statusText(scanning: Boolean, scanFound: Int, importing: Boolean, imported: Int, importTotal: Int): String? {
        val parts = ArrayList<String>(2)
        if (scanning) parts += if (scanFound > 0) "책 스캔 중… ${scanFound}권" else "책 스캔 중…"
        if (importing) parts += if (importTotal > 0) "가져오는 중… $imported/$importTotal" else "가져오는 중… $imported"
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /**
     * Empty-state message for a shelf. [flagButtons]: the list shows cards with flag buttons (목록); in the other
     * views the hint points to multi-select or the book menu instead.
     */
    fun emptyMessage(shelf: Shelf, query: String, inGroup: Boolean, flagButtons: Boolean = true): String {
        if (query.isNotBlank()) return "‘${query.trim()}’ 검색 결과가 없습니다."
        if (inGroup) return "이 항목에 책이 없습니다."
        return when (shelf) {
            Shelf.READING_NOW -> "${Shelf.READING_NOW.label}이 없습니다.\n책을 열면 여기에 표시됩니다."
            // The [지금 스캔] and [파일 열기] buttons under it say the rest.
            Shelf.ALL -> "책이 없습니다."
            Shelf.FAVORITES -> "즐겨찾기한 책이 없습니다.\n" +
                if (flagButtons) "카드의 별 버튼으로 추가하세요." else "책 메뉴에서 ‘즐겨찾기에 추가’를 고르세요."
            Shelf.TO_READ -> "${Shelf.TO_READ.label}이 없습니다.\n" +
                if (flagButtons) "카드의 시계 버튼으로 추가하세요." else "책을 길게 눌러 고른 뒤 ‘읽을 책’을 누르세요."
            Shelf.HAVE_READ -> "${Shelf.HAVE_READ.label}이 없습니다.\n" +
                if (flagButtons) "카드의 체크 버튼으로 표시하세요." else "책을 길게 눌러 고른 뒤 ‘다 읽음’을 누르세요."
            Shelf.AUTHORS -> "작가 정보가 있는 책이 없습니다."
            Shelf.SERIES -> "시리즈 정보가 있는 책이 없습니다."
            Shelf.COLLECTIONS -> "컬렉션이 없습니다."
            Shelf.FORMATS -> "책이 없습니다."
            Shelf.FOLDERS -> "책이 있는 폴더가 없습니다."
            Shelf.DOWNLOADS -> "다운로드 폴더에 TXT·EPUB 파일이 없습니다."
            Shelf.TRASH -> "휴지통이 비어 있습니다."
        }
    }

    /** Mime type for sharing a book file. */
    fun mimeFor(fileName: String): String {
        val n = fileName.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".epub") -> "application/epub+zip"
            n.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * Adds [path] to the configured scan folders. An empty [existing] set means "scan [defaultRoots]" (the
     * whole storage), so a folder inside one of them changes nothing, and a folder outside all of them (e.g. a
     * USB drive) is added *together with* the default roots — otherwise the non-empty set would narrow the scan
     * to that one folder. Returns null when nothing changes (already covered, or no roots known). Folders
     * inside the new one are dropped (the new root covers them).
     */
    fun addScanFolder(existing: Set<String>, path: String, defaultRoots: List<String> = emptyList()): Set<String>? {
        val p = path.trimEnd('/').ifEmpty { "/" }
        val base: Collection<String> = if (existing.isNotEmpty()) {
            existing
        } else {
            defaultRoots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
        }
        if (base.isEmpty()) return null
        if (base.any { isSameOrInside(p, it) }) return null
        val kept = base.filterNot { isSameOrInside(it, p) }
        return LinkedHashSet(kept).apply { add(p) }
    }

    /** True when [path] equals [folder] or lies below it (path-segment aware). */
    fun isSameOrInside(path: String, folder: String): Boolean {
        val f = folder.trimEnd('/')
        val p = path.trimEnd('/')
        if (f.isEmpty()) return true
        return p == f || p.startsWith("$f/")
    }

    /** Human label for a TXT encoding choice ("" = auto); the same wording as the reader's. */
    fun encodingLabel(charset: String): String = ReaderFormat.encodingLabel(charset)

    const val ADB_HINT = "adb shell appops set com.ggumtak.readeraplus MANAGE_EXTERNAL_STORAGE allow"
}
