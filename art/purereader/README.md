# PureReader artwork

Original vector artwork created for this app. The open book carries a cut-out P on the left page and three reading lines on the right. The launcher, drawer header and About page share that mark.

These SVGs are editable source assets. Their Android VectorDrawable counterparts are in `app/src/main/res/drawable/` with matching filenames. Keep each pair in sync when editing geometry. Launcher layers use a 108-unit viewport and keep the mark inside the adaptive icon safe area; `ic_launcher_monochrome` uses transparent cut-outs so Android can tint it correctly.

| Assets | Use |
| --- | --- |
| `ic_purereader`, `ic_launcher_*` | Brand mark, drawer, About page, standard/round/themed launcher |
| `ic_library_books`, `ic_menu_book`, `ic_bookmark`, `ic_folder` | Library and reading navigation |
| `ic_format_quote`, `ic_translate`, `ic_file_open`, `ic_ink_highlighter` | Notes, wordbook, import, highlighting |
| `ill_empty_library`, `ill_empty_reading`, `ill_empty_collection` | Empty shelves |
| `ill_empty_search`, `ill_empty_trash`, `ill_no_access` | No matches, empty trash, missing storage permission |
| `ill_empty_notes`, `ill_empty_bookmarks`, `ill_empty_highlights`, `ill_empty_words` | Empty notes tabs and reader bookmark/highlight tabs |

Navigation icons use 24-unit viewports and 1.8-unit rounded strokes. Illustrations use 96-unit viewports and 2–2.6-unit strokes, with white occlusion fills where objects overlap. Do not apply a single color filter to the illustrations: that would fill their white counters. All graphics are static black/white vectors; no network, bitmap decoding or animation is added. Loading and error messages keep their existing text-only presentation.

The product name is PureReader. `com.ggumtak.readeraplus`, the repository name, the backup format marker and the APK/update transport filenames remain stable to retain compatibility with existing installs and backups. New reader screenshots are saved under `Pictures/PureReader`; existing pictures are left where they were saved.

Other icons in the app retain their existing attribution (Material Symbols, Apache 2.0).
