#!/usr/bin/env python3
"""Writes a small original test PDF (3 pages of text, Helvetica) to argv[1]. No dependencies."""
import sys

pages = [
    ["ReaderaPlus PDF test page one", "The quick brown fox jumps over the lazy dog.", "Circle this word: dictionary"],
    ["Page two", "Search target: lighthouse", "Another line of plain sample text."],
    ["Page three", "lighthouse appears here again", "End of the sample document."],
]
objs = []
def add(body):
    objs.append(body)
    return len(objs)
font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
kids_ref = []
page_ids = []
pages_id = len(objs) + 1 + 2 * len(pages)  # placeholder index computed after
content_ids = []
for n, lines in enumerate(pages):
    stream = "BT /F1 18 Tf 60 760 Td 24 TL " + " ".join("(%s) '" % l.replace("(", "\\(").replace(")", "\\)") for l in lines) + " ET"
    if n == 0:
        stream += " BT /F1 24 Tf 200 421 Td (dictionary) Tj ET"  # mid-page, for the emulator's lasso loop
    data = stream.encode("latin-1")
    cid = add(b"<< /Length %d >>\nstream\n" % len(data) + data + b"\nendstream")
    content_ids.append(cid)
pages_obj_index = len(objs) + 1 + len(pages)
for cid in content_ids:
    pid = add(b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 %d 0 R >> >> /Contents %d 0 R >>"
              % (pages_obj_index, font, cid))
    page_ids.append(pid)
pages_id = add(b"<< /Type /Pages /Kids [" + b" ".join(b"%d 0 R" % p for p in page_ids) + b"] /Count %d >>" % len(page_ids))
assert pages_id == pages_obj_index
catalog = add(b"<< /Type /Catalog /Pages %d 0 R >>" % pages_id)
out = bytearray(b"%PDF-1.4\n")
offsets = []
for i, body in enumerate(objs, 1):
    offsets.append(len(out))
    out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
xref = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
for off in offsets:
    out += b"%010d 00000 n \n" % off
out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, catalog, xref)
open(sys.argv[1], "wb").write(out)
