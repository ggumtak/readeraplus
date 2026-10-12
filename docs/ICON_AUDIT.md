# PureReader icon review — 2026-10-12

## Scope and decisions

Reviewed all 99 icon resources present at the start, their Kotlin use sites and state pairs. The result has 110 resources: 22 existing icons redrawn and 11 purpose-specific icons added. Familiar navigation, media, selection and favourite/read-later symbols remain where they already communicate the action clearly. This is a source and raster-artwork review, not an Android device screenshot review.

| Problem | Result |
| --- | --- |
| Page jump used a document/magnifier | Document with an internal navigation arrow; searching keeps the magnifier |
| Reading-in-progress reused the reset arrows | Open book with a reading ribbon |
| Series used a shopping price tag | Numbered books linked in sequence; same symbol in drawer and group list |
| File formats reused generic layers | Stacked file sheets, distinct from book collections |
| Wi-Fi receive looked like an ordinary download | Radio waves and an incoming arrow; download shelf keeps its download glyph |
| File-open arrow pointed outwards, exports looked like uploads | Incoming arrow for open/import, outgoing file arrow for export |
| Completed/uncompleted glyphs were byte-for-byte identical | Outlined check circle versus filled circle with transparent check cut-out |
| Active collection/bookmark could switch to an unrelated silhouette | Related book/ribbon contours in outlined and filled states |
| Bookmark removal still showed a plus | Minus icon on the removal action; plus on addition |
| Notes symbol resembled a face / quotes only | Notebook with pencil, shared between library and reader notes |
| Single T and tiny rotation/lock detail | Aa for reading appearance; phone and turn arrows, larger lock |
| AI settings used overflow dots; web search looked like local search | Matching gears for both settings entry points; globe for web search |
| PDF pen-entry showed a navigation hand; redo mirrored its whole view | Related outline/filled pen mode glyphs and a dedicated redo vector |

## Additional surfaces inspected

| Surface | Decision |
| --- | --- |
| PDF Canvas pen/highlighter/eraser/lasso/drag grip (`reader/pdf/PdfToolIcon.kt`) | Retain: pen point colour and width have meaning; highlighter tip, dashed lasso, eraser and active tiles are already distinct |
| PDF stroke/pressure samples (`reader/pdf/PenPanel.kt`, `InkShape.kt`) | Retain: actual line previews communicate ink colour, width and pressure response |
| Reader battery/charging marks (`render/PageRenderer.kt`) | Retain: charge indication and e-ink/phone palette are meaningful and already covered by geometry tests |
| Quote colour/style samples, thumbnail bookmark ribbons, toggles | Retain: these show content state/style directly rather than acting as general navigation icons |
| Selection action text (`reader/extras/SelectionActions.kt`) | Retain clear named actions; do not replace space-efficient labels with guessed pictograms |
| AI inline symbols | Inspect menu, new conversation, settings, jump, web search, attachment, send/stop, drawer close/new; change settings/search only. Retain provider welcome mark and labelled CSS speech bubbles / delete controls |
| Ten empty-state illustrations and launcher layers | Retain the existing original PureReader artwork; their geometry, occlusion fills and mask treatment remain appropriate |

## Complete drawable inventory

The following table lists every resulting `ic_*.xml` resource. Legacy resources that no longer have a call site remain available; they are not silently substituted for current actions. Existing resource names such as `ic_ridi_*` and `ic_done_all*` are compatibility names, not descriptions of the new artwork.

| Resource | Decision | Uses |
| --- | --- | --- |
| `ic_add` | Retained | `ui/library/LibraryActivity.kt`; `ui/settings/FontsPage.kt`; `ui/settings/PageTurningPage.kt` |
| `ic_add_circle` | Retained | `reader/extras/ReadingSettingsPopup.kt`; `ui/kit/Ui.kt` |
| `ic_apps` | Retained | `ui/library/LibraryActivity.kt` |
| `ic_arrow_back` | Retained | `reader/extras/ContentsDialog.kt`; `reader/extras/RulesDialog.kt`; `reader/extras/SearchPanel.kt`; `reader/extras/WordSearchPanel.kt`; `reader/pdf/PdfChrome.kt`; `ui/library/LibraryActivity.kt`; `ui/notes/NotesActivity.kt`; `ui/notes/NotesMenus.kt`; `ui/settings/AboutPage.kt`; `ui/settings/EinkTest.kt`; `ui/settings/SettingsActivity.kt` |
| `ic_arrow_drop_down` | Retained | `reader/extras/ReadingSettingsPopup.kt`; `ui/settings/SettingsPage.kt` |
| `ic_article` | Retained | `ui/library/LibraryActivity.kt`; `ui/notes/NotesMenus.kt` |
| `ic_auto_stories` | Retained | `reader/ReaderMenus.kt` |
| `ic_autorenew` | Retained | `ui/library/LibraryDialogs.kt` |
| `ic_bookmark` | Redrawn original vector | `reader/extras/ContentsDialog.kt`; `reader/pdf/PdfChrome.kt`; `reader/pdf/PdfSidePanel.kt`; `ui/notes/NotesAdapter.kt` |
| `ic_bookmark_add` | Redrawn original vector | `reader/ReaderMenus.kt` |
| `ic_bookmark_fill` | Redrawn original vector | `reader/pdf/PdfChrome.kt`; `reader/pdf/PdfSidePanel.kt` |
| `ic_bookmark_remove` | Added original vector | `reader/ReaderMenus.kt` |
| `ic_check` | Retained | `ui/settings/FontsPage.kt` |
| `ic_check_box` | Retained | `reader/EndPanel.kt`; `reader/extras/RulesDialog.kt`; `ui/library/LibraryViews.kt`; `ui/notes/NotesAdapter.kt`; `ui/notes/NotesMenus.kt` |
| `ic_check_box_outline_blank` | Retained | `reader/EndPanel.kt`; `reader/extras/RulesDialog.kt`; `ui/library/LibraryViews.kt`; `ui/notes/NotesAdapter.kt`; `ui/notes/NotesMenus.kt` |
| `ic_chevron_left` | Retained | `reader/ReturnNav.kt`; `reader/extras/SearchPanel.kt`; `reader/extras/TtsController.kt` |
| `ic_chevron_right` | Retained | `reader/ReaderChrome.kt`; `reader/ReturnNav.kt`; `reader/extras/ReadingSettingsPopup.kt`; `reader/extras/SearchPanel.kt`; `reader/extras/TtsController.kt`; `ui/settings/SettingsPage.kt` |
| `ic_close` | Retained | `reader/ReturnNav.kt`; `reader/extras/ReadingSettingsPopup.kt`; `reader/extras/SearchPanel.kt`; `reader/extras/TtsController.kt`; `reader/extras/TtsService.kt`; `reader/extras/WordSearchPanel.kt`; `reader/pdf/PdfSidePanel.kt`; `ui/library/LibraryActivity.kt`; `ui/notes/NotesActivity.kt`; `ui/settings/ScanPage.kt` |
| `ic_content_copy` | Redrawn original vector | `reader/extras/ContentsDialog.kt`; `ui/notes/NotesMenus.kt` |
| `ic_create_new_folder` | Redrawn original vector | `ui/library/LibraryActivity.kt` |
| `ic_delete` | Retained | `reader/extras/ContentsDialog.kt`; `reader/pdf/PdfSidePanel.kt`; `ui/library/LibraryActivity.kt`; `ui/library/LibraryDialogs.kt`; `ui/notes/NotesActivity.kt`; `ui/notes/NotesMenus.kt`; `ui/settings/FontsPage.kt` |
| `ic_delete_forever` | Retained | `ui/library/LibraryActivity.kt`; `ui/library/LibraryDialogs.kt` |
| `ic_do_not_disturb_on` | Retained | `reader/extras/ReadingSettingsPopup.kt`; `ui/kit/Ui.kt` |
| `ic_done_all` | Redrawn original vector | `reader/ReaderMenus.kt`; `ui/library/LibraryActivity.kt`; `ui/library/LibraryDialogs.kt`; `ui/library/LibraryViews.kt` |
| `ic_done_all_fill` | Redrawn original vector | `reader/ReaderMenus.kt`; `ui/library/LibraryDialogs.kt`; `ui/library/LibraryViews.kt` |
| `ic_download` | Retained | `ui/library/LibraryActivity.kt` |
| `ic_edit` | Redrawn original vector | `reader/extras/ContentsDialog.kt`; `reader/pdf/PdfSidePanel.kt`; `ui/library/LibraryDialogs.kt`; `ui/notes/NotesMenus.kt` |
| `ic_expand_less` | Retained | `reader/pdf/PdfChrome.kt`; `ui/settings/AboutPage.kt`; `ui/settings/EinkPage.kt`; `ui/settings/StatsPage.kt` |
| `ic_expand_more` | Retained | `reader/pdf/PdfChrome.kt`; `ui/settings/AboutPage.kt`; `ui/settings/EinkPage.kt`; `ui/settings/StatsPage.kt` |
| `ic_export_file` | Added original vector | `ui/notes/NotesActivity.kt`; `ui/notes/NotesMenus.kt` |
| `ic_file_formats` | Added original vector | `ui/library/LibraryActivity.kt`; `ui/library/LibraryViews.kt` |
| `ic_file_open` | Redrawn original vector | `ui/library/LibraryActivity.kt` |
| `ic_find_in_page` | Retained | Legacy resource; no current call site |
| `ic_folder` | Retained | `ui/library/LibraryActivity.kt`; `ui/library/LibraryViews.kt` |
| `ic_format_quote` | Redrawn original vector | `ui/library/LibraryActivity.kt`; `ui/library/LibraryDialogs.kt` |
| `ic_grid_view` | Retained | `reader/ReaderMenus.kt`; `reader/pdf/PdfChrome.kt`; `reader/pdf/PdfSidePanel.kt`; `ui/library/LibraryActivity.kt` |
| `ic_headphones` | Retained | `reader/extras/TtsService.kt` |
| `ic_history` | Retained | `ui/library/LibraryActivity.kt`; `ui/notes/NotesMenus.kt` |
| `ic_info` | Retained | `reader/ReaderMenus.kt`; `ui/library/LibraryDialogs.kt` |
| `ic_ink_highlighter` | Retained | `reader/extras/ContentsDialog.kt`; `ui/notes/NotesActivity.kt`; `ui/notes/NotesMenus.kt` |
| `ic_launcher_background` | Retained | `res/mipmap-anydpi-v26/ic_launcher{,_round}.xml` |
| `ic_launcher_foreground` | Retained | `res/mipmap-anydpi-v26/ic_launcher{,_round}.xml` |
| `ic_launcher_monochrome` | Retained | `res/mipmap-anydpi-v26/ic_launcher{,_round}.xml` |
| `ic_layers` | Retained | Legacy resource; no current call site |
| `ic_library_books` | Redrawn original vector | `reader/ReaderMenus.kt`; `ui/library/LibraryActivity.kt`; `ui/library/LibraryDialogs.kt`; `ui/library/LibraryViews.kt` |
| `ic_library_books_fill` | Redrawn original vector | `ui/library/LibraryViews.kt` |
| `ic_menu` | Retained | `ui/library/LibraryActivity.kt` |
| `ic_menu_book` | Retained | `ui/library/LibraryActivity.kt` |
| `ic_more_vert` | Retained | `ui/library/LibraryActivity.kt`; `ui/library/LibraryViews.kt`; `ui/notes/NotesActivity.kt`; `ui/notes/NotesAdapter.kt` |
| `ic_open_in_new` | Retained | `reader/extras/ContentsDialog.kt`; `ui/notes/NotesMenus.kt` |
| `ic_page_jump` | Added original vector | `reader/ReaderMenus.kt` |
| `ic_pause` | Retained | `reader/extras/TtsController.kt`; `reader/extras/TtsService.kt` |
| `ic_pen_mode` | Added original vector | `reader/pdf/PdfChrome.kt` |
| `ic_pen_mode_fill` | Added original vector | `reader/pdf/PdfChrome.kt` |
| `ic_person` | Retained | `ui/library/LibraryActivity.kt`; `ui/library/LibraryViews.kt` |
| `ic_play_arrow` | Retained | `reader/extras/TtsController.kt`; `reader/extras/TtsService.kt` |
| `ic_playlist_add` | Retained | `reader/ReaderMenus.kt` |
| `ic_purereader` | Retained | `ui/library/LibraryActivity.kt`; `ui/settings/AboutPage.kt` |
| `ic_push_pin` | Retained | `reader/pdf/PdfChrome.kt` |
| `ic_push_pin_fill` | Retained | `reader/pdf/PdfChrome.kt` |
| `ic_radio_button_checked` | Retained | `reader/extras/CompactUi.kt`; `reader/extras/FontChooser.kt`; `ui/kit/Ui.kt`; `ui/settings/SettingsPage.kt` |
| `ic_radio_button_unchecked` | Retained | `reader/extras/CompactUi.kt`; `reader/extras/FontChooser.kt`; `ui/kit/Ui.kt`; `ui/settings/SettingsPage.kt` |
| `ic_rate_review` | Retained | `reader/ReaderMenus.kt` |
| `ic_reading_now` | Added original vector | `ui/library/LibraryActivity.kt` |
| `ic_redo` | Added original vector | `reader/pdf/PdfChrome.kt` |
| `ic_refresh` | Retained | `reader/ReaderMenus.kt`; `ui/library/LibraryActivity.kt`; `ui/settings/FontsPage.kt` |
| `ic_restore_from_trash` | Retained | `ui/library/LibraryDialogs.kt` |
| `ic_ridi_back` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_bookmark` | Redrawn original vector | `reader/ReaderChrome.kt` |
| `ic_ridi_bookmark_fill` | Redrawn original vector | `reader/ReaderChrome.kt` |
| `ic_ridi_expand_less` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_expand_more` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_gear` | Redrawn original vector | `reader/ReaderChrome.kt` |
| `ic_ridi_more` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_next` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_note` | Redrawn original vector | `reader/ReaderChrome.kt` |
| `ic_ridi_pin` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_pin_fill` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_prev` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_rotate` | Redrawn original vector | `reader/ReaderChrome.kt` |
| `ic_ridi_rotate_lock` | Redrawn original vector | `reader/ReaderChrome.kt` |
| `ic_ridi_search` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_sun` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_sun_auto` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_text` | Redrawn original vector | `reader/ReaderChrome.kt` |
| `ic_ridi_toc` | Retained | `reader/ReaderChrome.kt` |
| `ic_ridi_tts` | Retained | `reader/ReaderChrome.kt` |
| `ic_schedule` | Retained | `reader/ReaderMenus.kt`; `ui/library/LibraryActivity.kt`; `ui/library/LibraryDialogs.kt`; `ui/library/LibraryViews.kt` |
| `ic_schedule_fill` | Retained | `reader/ReaderMenus.kt`; `ui/library/LibraryDialogs.kt`; `ui/library/LibraryViews.kt` |
| `ic_search` | Retained | `reader/extras/SearchPanel.kt`; `reader/pdf/PdfChrome.kt`; `reader/pdf/PdfSidePanel.kt`; `ui/library/LibraryActivity.kt`; `ui/notes/NotesActivity.kt` |
| `ic_select_all` | Retained | `ui/notes/NotesMenus.kt` |
| `ic_sell` | Retained | Legacy resource; no current call site |
| `ic_series` | Added original vector | `ui/library/LibraryActivity.kt`; `ui/library/LibraryViews.kt` |
| `ic_settings` | Redrawn original vector | `reader/extras/TtsController.kt`; `reader/pdf/PdfChrome.kt`; `ui/library/LibraryActivity.kt` |
| `ic_share` | Redrawn original vector | `reader/extras/ContentsDialog.kt`; `ui/library/LibraryDialogs.kt`; `ui/notes/NotesActivity.kt`; `ui/notes/NotesMenus.kt` |
| `ic_skip_next` | Retained | `reader/extras/TtsService.kt` |
| `ic_skip_previous` | Retained | `reader/extras/TtsService.kt` |
| `ic_sort` | Retained | `ui/library/LibraryActivity.kt` |
| `ic_star` | Retained | `reader/ReaderMenus.kt`; `ui/library/LibraryActivity.kt`; `ui/library/LibraryDialogs.kt`; `ui/library/LibraryViews.kt` |
| `ic_star_fill` | Retained | `reader/ReaderMenus.kt`; `ui/library/LibraryDialogs.kt`; `ui/library/LibraryViews.kt` |
| `ic_sticky_note_2` | Retained | `ui/notes/NotesMenus.kt` |
| `ic_text_fields` | Retained | `ui/library/LibraryDialogs.kt` |
| `ic_timer` | Retained | `reader/ReaderMenus.kt` |
| `ic_touch_app` | Retained | Legacy resource; no current call site |
| `ic_translate` | Retained | `ui/library/LibraryActivity.kt`; `ui/notes/NotesAdapter.kt`; `ui/notes/NotesMenus.kt` |
| `ic_undo` | Redrawn original vector | `reader/pdf/PdfChrome.kt` |
| `ic_upload` | Retained | Legacy resource; no current call site |
| `ic_view_list` | Retained | `reader/ReaderMenus.kt`; `reader/extras/SearchPanel.kt`; `ui/library/LibraryActivity.kt` |
| `ic_web_search` | Added original vector | `ui/notes/NotesMenus.kt` |
| `ic_wifi_receive` | Added original vector | `ui/library/LibraryActivity.kt` |

## Verification

- SVG/XML geometry pairing, XML resource parsing and reference resolution.
- Raster inspection of the full original inventory, then changed icons at 16, 24 and 52 px; active-state pairs on light and dark backgrounds.
- AI inline SVG source review and its existing 35 client tests. Local HTML screenshot rendering could not run because the Playwright browser binary is absent.
- GitHub CI runs the complete JVM unit-test suite and release APK assembly.
- Real Comet/phone Android screen inspection remains to be done on the device.
