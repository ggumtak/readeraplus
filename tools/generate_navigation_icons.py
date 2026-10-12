#!/usr/bin/env python3
"""Generate original PureReader navigation vectors and their editable SVG sources."""
from pathlib import Path
import math

ROOT = Path(__file__).resolve().parents[1]
COLOR = "#151515"


def line(d, width=1.8):
    return {"d": d, "stroke": COLOR, "width": width}


def solid(d, holes=False):
    return {"d": d, "fill": COLOR, "holes": holes}


ICONS = {}


def add(names, *paths):
    for name in names.split():
        ICONS[name] = paths


# Same contour in normal/active states; holes are transparent, so Android tinting preserves them.
bookmark = "M6,3.5 H18 V21 L12,17 L6,21 Z"
add("ic_bookmark ic_ridi_bookmark", line(bookmark))
add("ic_bookmark_fill ic_ridi_bookmark_fill", solid(bookmark))
add("ic_bookmark_add", line("M14,3.5 H6 V21 L12,17 L18,21 V13"), line("M18,3 V9 M15,6 H21"))
add("ic_bookmark_remove", line("M14,3.5 H6 V21 L12,17 L18,21 V13"), line("M15,6 H21"))
books = "M3,4 H7 V21 H3 Z M10,3 H14 V21 H10 Z M16,6 L20,5 L23,20 L19,21 Z"
bands = "M4,8 H6 V10 H4 Z M11,7 H13 V9 H11 Z M4,17 H6 V19 H4 Z M11,17 H13 V19 H11 Z M17.5,10 L19.5,9.5 L20,11.5 L18,12 Z"
add("ic_library_books", line(books), line("M3,8 H7 M10,7 H14 M3,17 H7 M10,17 H14"))
add("ic_library_books_fill", solid(books + " " + bands, True))
circle = "M21.5,12 A9.5,9.5 0,1 1,2.5,12 A9.5,9.5 0,1 1,21.5,12 Z"
check = "M6,11.5 L10,15.5 L18,7.5"
check_hole = "M4.8,11.5 L6,10.3 L10,14.3 L16.8,7.5 L18,8.7 L10,16.7 Z"
add("ic_done_all", line(circle), line(check))
add("ic_done_all_fill", solid(circle + " " + check_hole, True))

# Action-specific pictograms: do not reuse a magnifier, price tag or refresh arrow for these.
add("ic_file_open", line("M4,3 H14 L19,8 V12 M14,3 V8 H19 M4,3 V21 H19"), line("M21,16 H10 M14,12 L10,16 L14,20"))
add("ic_export_file", line("M4,3 H14 L19,8 V12 M14,3 V8 H19 M4,3 V21 H12"), line("M11,17 H21 M17,13 L21,17 L17,21"))
# Page jump carries a page-location target, export carries an outgoing edge arrow.
add("ic_page_jump", line("M4,3 H14 L19,8 V21 H4 Z M14,3 V8 H19"), line("M8,11 H15 M8,15 H12"), line("M15,15 L18,18 L15,21 M12,18 H18"))
add("ic_wifi_receive", line("M3,6 C8,2 16,2 21,6 M6,9 C9,6.5 15,6.5 18,9 M9,12 C11,10.5 13,10.5 15,12"), line("M12,13 V21 M8.5,17.5 L12,21 L15.5,17.5"))
add("ic_reading_now", line("M3,5 C6,4 9,5 12,7 C15,5 18,4 21,5 V19 C18,18 15,19 12,21 C9,19 6,18 3,19 Z M12,7 V21"), line("M16,5 V13 L18,11.5 L20,13 V5"), line("M6,10 H9 M6,14 H9"))
add("ic_series", line("M2.5,3 H10.5 V16 H2.5 Z M13.5,7 H21.5 V20 H13.5 Z"), line("M5,8 L6.5,7 V12 M5,12 H8 M16,12 C19,10 20,13 17,15 L16,16 H19.5", 1.5), line("M4,20 H10 M8,18 L10,20 L8,22"))
add("ic_file_formats", line("M6,3 H16 L21,8 V19 H6 Z M16,3 V8 H21 M3,6 V22 H17"), line("M10,11 H17 M10,15 H14"))
add("ic_web_search", line("M20.5,10.5 A8.5,8.5 0,1 1,3.5,10.5 A8.5,8.5 0,1 1,20.5,10.5 Z M3.5,10.5 H20.5 M12,2 C7,6 7,15 12,19 C17,15 17,6 12,2"), line("M18.5,17 L22,21"))

# Reading notes are a notebook with a pencil, instead of a quote-only symbol or two "eyes".
notes = (line("M4,3 H15 V21 H4 Z M2.5,7 H5.5 M2.5,12 H5.5 M2.5,17 H5.5"),
         line("M8,7 H12 M8,11 H11"), line("M12,16 L18,6 L21,8 L15,18 L11,20 Z M17,8 L20,10"))
add("ic_format_quote ic_ridi_note", *notes)

# Matching folder / import / export shapes.
add("ic_create_new_folder", line("M3,6 V20 H21 V8 H11 L9,4 H3 Z M3,9 H21"), line("M9,14 H15 M12,11 V17"))
add("ic_content_copy", line("M8,3 H20 V18 H8 Z M4,7 V22 H16"))
add("ic_edit", line("M4,16 L16,4 L20,8 L8,20 L3,21 Z M13,7 L17,11 M4,16 L8,20"))
pen = "M4,16 L16,4 L20,8 L8,20 L3,21 Z"
add("ic_pen_mode", line(pen), line("M13,7 L17,11 M4,16 L8,20"))
add("ic_pen_mode_fill", solid(pen + " M14,6 L18,10 L17,11 L13,7 Z", True))
add("ic_undo", line("M9,6 L4,11 L9,16 M4,11 H15 C23,11 23,20 15,20 H12"))
add("ic_redo", line("M15,6 L20,11 L15,16 M20,11 H9 C1,11 1,20 9,20 H12"))
add("ic_share", line("M8,10 L16,6 M8,14 L16,18"), line("M8,12 A3,3 0,1 1,2,12 A3,3 0,1 1,8,12 Z M22,4.5 A3,3 0,1 1,16,4.5 A3,3 0,1 1,22,4.5 Z M22,19.5 A3,3 0,1 1,16,19.5 A3,3 0,1 1,22,19.5 Z"))

# A gear means settings everywhere, with an open centre that survives small sizes.
gear = []
for i in range(32):
    theta = 2 * math.pi * (i + .5) / 32
    radius = 9.6 if i % 4 in (0, 3) else 7.7
    gear.append(f"{12 + radius * math.cos(theta):.3f},{12 + radius * math.sin(theta):.3f}")
gear_d = "M" + " L".join(gear) + " Z"
add("ic_settings ic_ridi_gear", line(gear_d), line("M15.3,12 A3.3,3.3 0,1 1,8.7,12 A3.3,3.3 0,1 1,15.3,12 Z"))
add("ic_ridi_text", line("M3,19 L8,5 L13,19 M5,14 H11 M16,11 C22,8 22,18 18,19 C14,20 14,15 18,15 H21 M21,11 V19"))

# Phone + turning arrows; the locked state has a larger, recognisable lock.
rotate_phone = "M9,6 H15 V18 H9 Z M11,15 H13"
turn_top = "M3,9 C3,4 8,2 12,3 M9,1.8 L12,3 L10.5,6"
turn_bottom = "M21,15 C21,20 16,22 12,21 M15,22.2 L12,21 L13.5,18"
add("ic_ridi_rotate", line(rotate_phone), line(turn_top), line(turn_bottom))
add("ic_ridi_rotate_lock", line("M7,6 H13 V18 H7 Z M9,15 H11"), line(turn_top), line("M16,13 V11 C16,7 21,7 21,11 V13 M15,13 H22 V20 H15 Z M18.5,16 V17"))


def write_asset(name, paths):
    xml = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Original PureReader artwork. Regenerate with tools/generate_navigation_icons.py. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">']
    for p in paths:
        xa = f'android:pathData="{p["d"]}"'
        sa = f'd="{p["d"]}" fill="{p.get("fill", "none")}"'
        if "fill" in p:
            xa += f' android:fillColor="{p["fill"]}"'
        if "stroke" in p:
            xa += f' android:strokeColor="{p["stroke"]}" android:strokeWidth="{p["width"]}" android:strokeLineCap="round" android:strokeLineJoin="round"'
            sa += f' stroke="{p["stroke"]}" stroke-width="{p["width"]}" stroke-linecap="round" stroke-linejoin="round"'
        if p.get("holes"):
            xa += ' android:fillType="evenOdd"'
            sa += ' fill-rule="evenodd"'
        xml.append(f'    <path {xa} />')
        svg.append(f'    <path {sa} />')
    xml.append('</vector>')
    svg.append('</svg>')
    (ROOT / 'app/src/main/res/drawable' / f'{name}.xml').write_text('\n'.join(xml) + '\n')
    (ROOT / 'art/purereader' / f'{name}.svg').write_text('\n'.join(svg) + '\n')


if __name__ == '__main__':
    for name, paths in ICONS.items():
        write_asset(name, paths)
    print(f'Generated {len(ICONS)} original navigation icons and matching SVG sources.')
