#!/usr/bin/env python3
"""Generates original sample books for CI screenshots and performance checks.
All text here is written for this project (no third-party content)."""
import os, random, struct, sys, zlib, zipfile

OUT = sys.argv[1] if len(sys.argv) > 1 else "samples"
os.makedirs(OUT, exist_ok=True)
rnd = random.Random(7)

SENTENCES = [
    "새벽 공기는 생각보다 차가웠고, 창문 너머로 희미한 빛이 스며들었다.",
    "그는 오래된 지도를 펼쳐 놓고 한참 동안 말없이 들여다보았다.",
    "\"정말 이 길이 맞는 거야?\" 하고 동생이 조심스럽게 물었다.",
    "마을 끝의 작은 서점에는 언제나 종이 냄새와 커피 향이 섞여 있었다.",
    "바람이 불 때마다 처마 끝의 풍경이 맑은 소리를 냈다.",
    "그녀는 대답 대신 가방에서 낡은 공책 한 권을 꺼내 건넸다.",
    "첫 장에는 서툰 글씨로 날짜와 이름이 적혀 있었다.",
    "시간이 멈춘 것 같은 오후였다. 아무도 서두르지 않았다.",
    "\"내일 아침까지는 돌아와야 해.\" 그가 짧게 덧붙였다.",
    "길가의 은행나무가 노랗게 물들어 가고 있었다.",
    "그 순간, 멀리서 기차가 지나가는 소리가 들려왔다.",
    "똠방각하라는 별명은 어릴 적 친구들이 붙여 준 것이었다.",
    "햇살이 책상 위에 비스듬히 내려앉아 먼지를 반짝이게 했다.",
    "누구에게나 말하지 못한 이야기 하나쯤은 있기 마련이다.",
    "그는 문을 닫고 천천히 계단을 내려갔다.",
    "호수 위로 물안개가 피어오르자 세상이 조용해졌다.",
    "Reader+ 테스트 문장입니다: English words, numbers 12,345 and 3.14 mixed in.",
    "…그리고 그날 이후로, 아무것도 예전과 같지 않았다.",
]

def paragraph():
    return " ".join(rnd.choice(SENTENCES) for _ in range(rnd.randint(1, 4)))

def chapter_text(n, paras):
    title = f"제{n}화 {rnd.choice(['시작', '편지', '비밀', '여행', '약속', '귀환', '겨울', '등불'])}"
    lines = [title, ""]
    for i in range(paras):
        lines.append(paragraph())
        lines.append("")  # blank line between paragraphs, typical of Korean web-novel TXT
        if i == paras // 2:
            lines += ["", "* * *", "", ""]
    return lines

def write_txt(name, chapters, paras, encoding):
    lines = ["리더플러스 샘플 소설", "", "프롤로그", ""]
    lines += [paragraph() + "\n" for _ in range(3)]
    for n in range(1, chapters + 1):
        lines += chapter_text(n, paras)
    lines += ["에필로그", "", paragraph(), "", "작가의 말", "", "읽어 주셔서 감사합니다."]
    data = "\r\n".join(lines).encode(encoding)
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(data)
    return len(data)

def png(w, h, fn):
    rows = b"".join(b"\x00" + bytes(fn(x, y) for x in range(w)) for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")

def write_epub(name):
    cover = png(300, 450, lambda x, y: 0 if (x // 30 + y // 30) % 2 == 0 and 40 < y < 410 else 255)
    figure = png(400, 200, lambda x, y: (x * 255 // 400) if y % 20 < 14 else 255)
    chapters = []
    for n in range(1, 6):
        body = [f'<h1 id="c{n}">제{n}장 샘플 챕터</h1>']
        for i in range(30):
            p = paragraph()
            if i == 1:
                p = f"<b>굵은 글씨</b>와 <i>기울인 글씨</i>, 그리고 <sup>위첨자</sup>가 있는 문단입니다. " + p
            if i == 3:
                p += ' <a href="notes.xhtml#n1">[주석]</a>'
            body.append(f"<p>{p}</p>")
            if i == 5:
                body.append('<p class="center">가운데 정렬된 문단</p>')
                body.append("<p>시의 첫 줄<br/>시의 둘째 줄<br/>시의 셋째 줄</p>")
            if i == 8 and n == 1:
                body.append('<div class="fig"><img src="../Images/figure.png" alt="그림"/></div>')
                body.append("<ul><li>첫째 항목</li><li>둘째 항목</li></ul>")
                body.append("<blockquote><p>인용문 블록입니다. " + paragraph() + "</p></blockquote>")
                body.append("<hr/>")
        chapters.append("\n".join(body))
    css = "p { text-indent: 1em; margin: 0; } .center { text-align: center; text-indent: 0; } h1 { text-align: center; }"
    def xhtml(title, body):
        return f'''<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="ko">
<head><title>{title}</title><link rel="stylesheet" type="text/css" href="../Styles/style.css"/></head>
<body>{body}</body></html>'''
    manifest = ['<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>',
                '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
                '<item id="css" href="Styles/style.css" media-type="text/css"/>',
                '<item id="cover" href="Images/cover.png" media-type="image/png" properties="cover-image"/>',
                '<item id="fig" href="Images/figure.png" media-type="image/png"/>',
                '<item id="notes" href="Text/notes.xhtml" media-type="application/xhtml+xml"/>']
    spine = []
    navli = []
    ncx = []
    for n in range(1, 6):
        manifest.append(f'<item id="ch{n}" href="Text/ch{n}.xhtml" media-type="application/xhtml+xml"/>')
        spine.append(f'<itemref idref="ch{n}"/>')
        navli.append(f'<li><a href="Text/ch{n}.xhtml#c{n}">제{n}장 샘플 챕터</a></li>')
        ncx.append(f'<navPoint id="np{n}" playOrder="{n}"><navLabel><text>제{n}장 샘플 챕터</text></navLabel><content src="Text/ch{n}.xhtml#c{n}"/></navPoint>')
    spine.append('<itemref idref="notes"/>')
    opf = f'''<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">readeraplus-sample</dc:identifier><dc:title>리더플러스 샘플 EPUB</dc:title>
<dc:creator>테스트 작가</dc:creator><dc:language>ko</dc:language>
<meta name="calibre:series" content="샘플 시리즈"/><meta name="calibre:series_index" content="1"/>
</metadata>
<manifest>{"".join(manifest)}</manifest>
<spine toc="ncx">{"".join(spine)}</spine>
</package>'''
    nav = xhtml("목차", '<nav epub:type="toc"><h1>목차</h1><ol>' + "".join(navli) + "</ol></nav>").replace("../Styles", "Styles")
    ncxdoc = f'''<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>샘플</text></docTitle>
<navMap>{"".join(ncx)}</navMap></ncx>'''
    with zipfile.ZipFile(os.path.join(OUT, name), "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr("OEBPS/content.opf", opf)
        z.writestr("OEBPS/nav.xhtml", nav)
        z.writestr("OEBPS/toc.ncx", ncxdoc)
        z.writestr("OEBPS/Styles/style.css", css)
        z.writestr("OEBPS/Images/cover.png", cover)
        z.writestr("OEBPS/Images/figure.png", figure)
        z.writestr("OEBPS/Text/notes.xhtml", xhtml("주석", '<h2>주석</h2><p id="n1">[주석] 이것은 샘플 주석입니다.</p>'))
        for n, body in enumerate(chapters, 1):
            z.writestr(f"OEBPS/Text/ch{n}.xhtml", xhtml(f"제{n}장", body))

# CI 99 (2026-10-05, 한자 빈칸: 나눔명조 OTF drew 聖 and 俗 as blanks). Each glyph sample starts with paragraphs made
# only of characters some font maps to blank glyphs, so the top rows of its first page hold ink only if they are drawn;
# the reported line follows for the eye. HANJA: KS X 1001 Hanja (blank in the old 나눔명조 OTF, left to the system font
# by the TTF); RARE_HANGUL: syllables outside KS X 1001 (blank in 학교안심 바른바탕, repaired at run time).
HANJA = "聖俗善惡科學形而上人間社會歷史文化自由平等罪罰良心理性信仰"
RARE_HANGUL = "똠됬햏뷁펲믜쨰쌰얬갅"
GLYPH_TAIL = ["선과 악, 성(聖)과 속(俗), 과학과 형이상학의", "漢字 𠀀"]


def glyph_paragraphs(chars, count=4, length=40):
    """count paragraphs of length characters cycling through chars (no spaces: nothing but those glyphs)."""
    return ["".join(chars[(7 * i + k) % len(chars)] for k in range(length)) for i in range(count)]


def write_glyph_txt(name, chars):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="") as f:
        f.write("\r\n\r\n".join(glyph_paragraphs(chars) + GLYPH_TAIL) + "\r\n")


def write_glyph_epub(name, chars):
    body = "".join(f"<p>{p}</p>" for p in glyph_paragraphs(chars) + GLYPH_TAIL)
    page = f'''<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="ko">
<head><title>漢字</title></head><body>{body}</body></html>'''
    nav = '''<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="ko">
<head><title>목차</title></head><body><nav epub:type="toc"><ol><li><a href="Text/ch1.xhtml">漢字</a></li></ol></nav></body></html>'''
    opf = '''<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">readeraplus-glyphs</dc:identifier><dc:title>한자 샘플 EPUB</dc:title><dc:language>ko</dc:language>
</metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="ch1"/></spine>
</package>'''
    with zipfile.ZipFile(os.path.join(OUT, name), "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr("OEBPS/content.opf", opf)
        z.writestr("OEBPS/nav.xhtml", nav)
        z.writestr("OEBPS/Text/ch1.xhtml", page)


# CI 100 (2026-10-05, 마루뷰어만큼 선명하게: body text hinted on whole pixels). Short one-line paragraphs of one pattern:
# hinted text draws its copies at a whole-px period, pixel for pixel, and every line the same (raw_equal.py crisp). "가o"
# is 1548/1024 em, a fractional advance at the CI's sizes (60.47 px at 40 px, 51.4 at 34): the linear paint of before
# drew the copies at other quarter-pixel phases. Six copies: four periods for the check, one line at any CI size.
CRISP_PATTERN = "가o" * 6
CRISP_LINES = 12


def write_crisp_txt(name):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="") as f:
        f.write("\r\n\r\n".join([CRISP_PATTERN] * CRISP_LINES) + "\r\n")


print("sample-cp949.txt", write_txt("sample-cp949.txt", 12, 40, "cp949"))
print("sample-utf8.txt", write_txt("sample-utf8.txt", 3, 20, "utf-8"))
print("big-cp949.txt", write_txt("big-cp949.txt", 900, 110, "cp949"))
write_epub("sample.epub")
print("sample.epub", os.path.getsize(os.path.join(OUT, "sample.epub")))
write_glyph_txt("glyphs-hanja.txt", HANJA)
write_glyph_epub("glyphs-hanja.epub", HANJA)
write_glyph_txt("glyphs-hangul.txt", RARE_HANGUL)
print("glyphs-hanja.txt glyphs-hanja.epub glyphs-hangul.txt")
write_crisp_txt("crisp.txt")
print("crisp.txt")


def restore_backup(epub_size):
    """The crafted auto backup of S §3.9 (CI shots 95–98): one book (sample.epub) with a position and a bookmark, and
    settings saved by an R2 build: readMode PAGED and the legacy side margins 18/16 without the r.marginBase marker.
    Its top/bottom 16/16 without r.marginBaseV are R2's untouched default: 40/40 from the edge, which the restore counts
    from the default status bands since 2026-10-05 (18/22), so CI 97 checks that conversion with 상하 여백 "0"."""
    created = 1790684040000  # 2026-09-29 21:14 KST, the time in the file name
    return {
        "format": "readeraplus-backup",
        "version": 1,
        "createdAt": created,
        "settings": {
            "reader": {"r.marginLeftDp": 18, "r.marginRightDp": 18, "r.marginTopDp": 16, "r.marginBottomDp": 16},
            "app": {"a.readMode": "PAGED"},
            "other": {},
            "otherTypes": {},
        },
        "collections": [],
        "books": [{
            "path": "/sdcard/Download/sample.epub",
            "fileName": "sample.epub",
            "size": epub_size,
            "format": "EPUB",
            "title": "리더플러스 샘플 EPUB",
            "posSection": 2,
            "posOffset": 0,
            "progress": 0.3,
            "lastReadAt": created - 60_000,
            "addedAt": created - 86_400_000,
            "readingSeconds": 600,
            "bookmarks": [{"section": 2, "offset": 0, "snippet": "제2장 샘플 챕터", "note": "", "createdAt": created - 120_000}],
        }],
    }


RESTORE_NAME = "readeraplus-auto-0badc0de-20260929-2114.json"
if __name__ == "__main__":
    import json
    with open(os.path.join(OUT, RESTORE_NAME), "w", encoding="utf-8") as f:
        json.dump(restore_backup(os.path.getsize(os.path.join(OUT, "sample.epub"))), f, ensure_ascii=False)
    print(RESTORE_NAME)
