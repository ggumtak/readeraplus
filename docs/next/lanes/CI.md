# Lane CI (emulator checks) — read BRIEF.md first

Files: tools/ci/screenshots.sh, tools/ci/raw_equal.py (maint), tools/ci/same_page.py (maint), tools/ci/find_node.py
(maint), tools/ci/make_samples.py (only if extra samples are needed), new tools/ci/perf_log.py, new
tools/ci/test_ci_tools.py (python unittest for perf_log.py and any other pure helper), and the screenshots job in
.github/workflows/*.yml only if a step needs a new input (keep the build job untouched).
No Kotlin changes. Checks: `bash -n tools/ci/screenshots.sh`, `python3 -m py_compile tools/ci/*.py`,
`python3 -m unittest tools/ci/test_ci_tools.py`, shellcheck if available. (You may skip the Kotlin toolchain setup
and the Kotlin full-tree checks; still never edit Kotlin files.)
Status note: docs/next/CI_STATUS.md · result: docs/next/lanes/CI.result.json

TASKS (PLAN §4 "CI" and §5.3 — the whole table in run order with the exact names; sources: U §8.2
(docs/next/ui/UI_SPEC.md), S §1.15 + §3.9 (docs/next/scroll/SPEC.md), N §17 (docs/next/notes/NOTES_SPEC.md), A §6.6
(docs/next/wave2/anchor.md), R §7 (already present as 70–78; keep it))
- Read the existing tools/ci/screenshots.sh fully first (it already has H1's 70–78 block, H2's 41/41b, H3's 14d, H4's
  57 with rawshot/perf_mark and the `RAPerf` setprop) and extend it to the §5.3 order: 01, 41(+41b), 02, 10, 11, 12
  (+12b), 13 (+rawshot), 13b–13h, 14, 14b, 14c, 10b (+rawshot, no_relayout), 14d, 15, 16, 17, 20–22, 60–69, 69b,
  30–32 (perf marks, first_is 31 32), 40, 42(+42b), 43, 44, 45, 46(+46), 50, 51, 80–90, 52–57, (92–93 W2: add the
  steps guarded so a missing menu item logs and continues), 70–78, 95–98 LAST (pm clear).
- Helpers: rawshot (H4), top_is, same, overview_back (H1), perf_mark (H4), new tools/ci/perf_log.py
  (`first_is A B`: the page start `o:` of the first `RAPerf show` line after mark B equals the anchor `a:` at mark A;
  `no_relayout A B`: no `RELAYOUT` show line between the marks; parse the H4 line format
  `RAPerf show <OPEN|TURN|JUMP|RELAYOUT> s:<section> o:<page start> a:<anchor> g:<gen> <ms>ms`).
- Every expectation becomes a `CHECK <n> PASS|FAIL <reason>` line in steps.txt; nothing fails the job except the
  existing crash rule. Label changes for the list modes (C29: 전체/요약/썸네일/그리드). Tap targets by text through
  find_node.py where possible (Korean labels exactly as PLAN §5.3 shows), coordinates only as a fallback.
- The emulator is 720×1440 at density 2. Content rows `pv + 80 … pv + 1360` only at 상하 여백 "0": every
  margin-changing step restores "0" before the next raw_equal (K8).
Accept: bash -n and the python unit tests green; every §5.3 name appears in the script; the run order matches §5.3.
