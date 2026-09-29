package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.DocMeta
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.format.ParseOptions
import java.io.File

/** EPUB entry points (see docs/ARCHITECTURE.md "format/epub"). Blocking: call off the main thread. */
object EpubDocuments {
    /**
     * Opens an EPUB (container + OPF only; TOC, sections and cover load lazily).
     * @throws DocumentException when the file is not a readable EPUB.
     */
    fun open(file: File, options: ParseOptions): BookDocument = EpubBook.open(file, options)

    /**
     * Library metadata from container.xml + OPF. A zip without a package document yields the file name as title.
     * @throws DocumentException when the file is not a readable zip.
     */
    fun readMeta(file: File): DocMeta {
        EpubZip.open(file).use { zip ->
            val pkg = try {
                loadPackage(zip)
            } catch (_: Exception) {
                null
            } catch (_: StackOverflowError) {
                null
            }
            return if (pkg == null) DocMeta(titleFromFile(file)) else metaOf(pkg, file)
        }
    }

    internal fun metaOf(pkg: EpubPackage, file: File): DocMeta = DocMeta(
        title = pkg.title?.takeIf { it.isNotBlank() } ?: titleFromFile(file),
        authors = pkg.authors,
        series = pkg.series,
        seriesIndex = pkg.seriesIndex,
        language = pkg.language,
        publisher = pkg.publisher,
        description = pkg.description,
    )

    private fun titleFromFile(file: File): String = file.nameWithoutExtension.ifEmpty { file.name }

    /** Locates and parses the OPF (container.xml rootfile, else the shallowest *.opf entry). Null if none. */
    internal fun loadPackage(zip: EpubZip): EpubPackage? {
        var opfPath: String? = null
        zip.read(CONTAINER)?.let { bytes ->
            OpfParser.rootfile(EpubText.decode(bytes))?.let { rf ->
                opfPath = zip.find(EpubPaths.resolve("", rf)) ?: zip.find(EpubPaths.resolve("", rf, decode = false))
            }
        }
        if (opfPath == null) {
            opfPath = zip.names.filter { EpubPaths.extension(it) == "opf" }
                .minWithOrNull(compareBy<String>({ it.count { c -> c == '/' } }, { it }))
        }
        val path = opfPath ?: return null
        val bytes = zip.read(path) ?: return null
        return OpfParser.parse(EpubText.decode(bytes), path) { zip.find(it) }
    }

    /** Package synthesised from the zip listing when there is no OPF: every HTML entry in natural order. */
    internal fun fallbackPackage(zip: EpubZip): EpubPackage {
        val html = htmlEntries(zip)
        if (html.isEmpty()) throw DocumentException("EPUB 패키지(OPF)를 찾을 수 없습니다")
        val manifest = html.mapIndexed { i, p -> ManifestItem("item$i", p, "application/xhtml+xml", "") } +
            zip.names.filter { EpubPaths.isImagePath(it) }.sorted()
                .mapIndexed { i, p -> ManifestItem("img$i", p, "", "") }
        return EpubPackage(
            opfPath = "",
            title = null,
            authors = emptyList(),
            language = null,
            publisher = null,
            description = null,
            series = null,
            seriesIndex = null,
            manifest = manifest,
            spine = manifest.filter { it.isHtml },
            spineTocId = null,
            coverMeta = null,
            guideCover = null,
        )
    }

    /** HTML entries outside META-INF sorted naturally (numbers compared by value). */
    internal fun htmlEntries(zip: EpubZip): List<String> =
        zip.names.filter { EpubPaths.isHtmlPath(it) && !it.startsWith("META-INF/") && EpubPaths.extension(it) != "xml" }
            .sortedWith(NaturalOrder)

    private const val CONTAINER = "META-INF/container.xml"
    private const val ENCRYPTION = "META-INF/encryption.xml"

    /** Font obfuscation algorithms: harmless (only embedded fonts are scrambled; we never use them). */
    private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#rc")

    /**
     * Throws a [DocumentException] with a clear message when content documents are encrypted (Adobe ADEPT,
     * Readium LCP, …) instead of letting the converter show ciphertext.
     */
    internal fun checkDrm(zip: EpubZip) {
        val bytes = zip.read(ENCRYPTION, 4 * 1024 * 1024) ?: return
        val r = MarkupReader(EpubText.decode(bytes))
        var algorithm = ""
        while (true) {
            when (r.next()) {
                MarkupReader.EOF -> return
                MarkupReader.START -> when (r.name) {
                    "encrypteddata" -> algorithm = ""
                    "encryptionmethod" -> algorithm = r.attr("Algorithm")?.trim()?.lowercase() ?: ""
                    "cipherreference" -> {
                        val uri = r.attr("URI")?.trim() ?: continue
                        if (algorithm in FONT_OBFUSCATION) continue
                        val path = EpubPaths.resolve("", uri)
                        if (EpubPaths.isHtmlPath(path) || EpubPaths.extension(path) == "opf") {
                            throw DocumentException("DRM으로 보호된 EPUB은 열 수 없습니다.")
                        }
                    }
                }
            }
        }
    }

    /** "ch2" < "ch10". */
    internal object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ei = i
                    while (ei < a.length && a[ei].isDigit()) ei++
                    var ej = j
                    while (ej < b.length && b[ej].isDigit()) ej++
                    val na = a.substring(i, ei).trimStart('0')
                    val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    i = ei
                    j = ej
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
