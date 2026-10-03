#!/usr/bin/env python3
"""Off-device check of the library database SQL (N §16), plain python3 + its sqlite3 module.

SQLite can't run in the JVM unit tests, so this script reads the SQL straight from the Kotlin sources
(`data/LibrarySchema.kt`, `data/LibrarySql.kt`: string constants, `listOf(...)` and `AddedColumn(...)` values) and:

  1. creates a fresh v3 file and prepares every LibrarySql statement against it;
  2. builds a v1 file with data and upgrades it to v3 exactly like `LibraryDb.onUpgrade` (CREATE_ALL, the guarded
     ALTERs from PRAGMA table_info, then UPGRADE_SWEEP below v3), in one transaction;
  3. the same from v2, seeded with 10,000 quotes and 3,000 bookmarks, timing the upgrade (budget 50 ms);
  4. v3 -> "v2 build" -> v3: the downgraded build leaves orphan lookups behind and stamps the file v2; the re-upgrade
     must add no column (no duplicate ALTER) and the sweep must remove exactly the orphans;
  5. asserts EXPLAIN QUERY PLAN for the data core's statements and the hub's statement shapes (N §5.3 / §16):
     unfiltered single-arm date pages `USING INDEX *_created` without "TEMP B-TREE FOR ORDER BY", the 메모 tab on
     `quotes_memo` / `bookmarks_memo`, book filters on `*_book`, details by primary key, counts without a temp b-tree.

Optional: `--dump FILE` also prepares every statement of the JSON written by SqlDumpTest
(`DATA_SQL_DUMP=FILE tools/unittest.sh ...`), including any notes statements other lanes add to it.

Exit status 0 when every check passes; prints one line per check.
"""
import json
import os
import re
import sqlite3
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "app/src/main/java/com/ggumtak/readeraplus/data")
UPGRADE_BUDGET_MS = float(os.environ.get("CHECK_SQL_BUDGET_MS", "50"))

FAILURES = []


def check(cond, what):
    print(("ok   " if cond else "FAIL ") + what)
    if not cond:
        FAILURES.append(what)


# ---------------------------------------------------------------------------------------------------------------
# A tiny reader of the Kotlin constant expressions used in the two files.
# ---------------------------------------------------------------------------------------------------------------

class Call:
    def __init__(self, name, args):
        self.name, self.args = name, args


class KotlinConsts:
    """Lazily evaluates `[private ]const val X = ...` and the named list values of a set of Kotlin files."""

    LISTS = ("CREATE_INDEXES", "UPGRADE_SWEEP", "ADDED_COLUMNS", "CREATE_ALL")

    def __init__(self, paths):
        self.src = {}
        self.order = []
        for p in paths:
            text = open(p, encoding="utf-8").read()
            pat = r"^\s*(?:private\s+|internal\s+)?(const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*[\w<>]+)?\s*="
            for m in re.finditer(pat, text, re.M):
                is_const, name = m.group(1), m.group(2)
                if not is_const and name not in self.LISTS:
                    continue
                self.src[name] = (text, m.end())
                self.order.append(name)
        self.values = {}

    def get(self, name):
        if name not in self.values:
            if name not in self.src:
                raise KeyError(name)
            text, pos = self.src[name]
            self.values[name] = None  # cycle guard
            value, _ = self._expr(text, pos)
            self.values[name] = value
        return self.values[name]

    def strings(self):
        out = {}
        for n in self.order:
            try:
                v = self.get(n)
            except (KeyError, ValueError):
                continue
            if isinstance(v, str):
                out[n] = v
        return out

    # -- parsing --

    @staticmethod
    def _skip(text, i):
        while i < len(text):
            if text[i].isspace():
                i += 1
            elif text.startswith("//", i):
                j = text.find("\n", i)
                i = len(text) if j < 0 else j + 1
            elif text.startswith("/*", i):
                i = text.index("*/", i) + 2
            else:
                break
        return i

    def _expr(self, text, i):
        value, i = self._term(text, i)
        while True:
            j = self._skip(text, i)
            if j < len(text) and text[j] == "+" and text[j + 1:j + 2] != "=":
                rhs, i = self._term(text, j + 1)
                value = value + rhs
            else:
                return value, i

    def _term(self, text, i):
        i = self._skip(text, i)
        c = text[i]
        if c == '"':
            return self._string(text, i)
        m = re.compile(r"-?\d+(\.\d+)?").match(text, i)
        if m:
            return (float(m.group()) if m.group(1) else int(m.group())), m.end()
        m = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*").match(text, i)
        if not m:
            raise ValueError("unexpected %r at %d" % (text[i:i + 20], i))
        name, i = m.group(), m.end()
        j = self._skip(text, i)
        if j < len(text) and text[j] == "(":
            args, i = self._args(text, j + 1)
            if name in ("listOf", "arrayOf"):
                return list(args), i
            return Call(name, args), i
        return self.get(name.split(".")[-1]), i

    def _args(self, text, i):
        args = []
        while True:
            i = self._skip(text, i)
            if text[i] == ")":
                return args, i + 1
            v, i = self._expr(text, i)
            args.append(v)
            i = self._skip(text, i)
            if text[i] == ",":
                i += 1

    def _string(self, text, i):
        assert text[i] == '"'
        if text.startswith('"""', i):
            end = text.index('"""', i + 3)
            return self._templates(text[i + 3:end]), end + 3
        i += 1
        out = []
        while text[i] != '"':
            c = text[i]
            if c == "\\":
                n = text[i + 1]
                out.append({"n": "\n", "t": "\t", "\\": "\\", '"': '"', "'": "'", "$": "\x00DOLLAR\x00"}.get(n, n))
                i += 2
            else:
                out.append(c)
                i += 1
        return self._templates("".join(out)), i + 1

    def _templates(self, s):
        def sub(m):
            return str(self.get(m.group(1) or m.group(2)))
        s = re.sub(r"\$\{([A-Z][A-Z0-9_]*)\}|\$([A-Z][A-Z0-9_]*)", sub, s)
        return s.replace("\x00DOLLAR\x00", "$")


K = KotlinConsts([os.path.join(DATA, "LibrarySchema.kt"), os.path.join(DATA, "LibrarySql.kt")])
CREATE_ALL = K.get("CREATE_ALL")
CREATE_INDEXES = K.get("CREATE_INDEXES")
UPGRADE_SWEEP = K.get("UPGRADE_SWEEP")
ADDED = [(c.args[0], c.args[1], c.args[2], c.args[3]) for c in K.get("ADDED_COLUMNS")]
DB_VERSION = K.get("DB_VERSION")
LIB_RETURN_ALTER = K.get("ADD_RETURN_MARK")
# LibraryDb.NOTES_SWEEP (N §4.1 defence sweep of quotes / bookmarks, run after UPGRADE_SWEEP below v3).
NOTES_SWEEP = ["DELETE FROM %s WHERE book_id NOT IN (SELECT id FROM books)" % t for t in ("quotes", "bookmarks")]
_DB_SRC = open(os.path.join(DATA, "LibraryDb.kt"), encoding="utf-8").read()
assert all(s in _DB_SRC for s in NOTES_SWEEP), "LibraryDb.NOTES_SWEEP changed: update check_sql.py"
SQL = {n: v for n, v in K.strings().items()
       if v.lstrip().upper().startswith(("SELECT", "INSERT", "UPDATE", "DELETE", "PRAGMA"))}

# Tables / indexes per version (v1 = R1, v2 = R2 adds reading_log and book_prefs, v3 = R3 adds lookups and the
# hub's indexes). Columns per version come from ADDED_COLUMNS.
TABLE_VERSION = {"reading_log": 2, "book_prefs": 2, "lookups": 3}
INDEX_VERSION = {"reading_log_book": 2, "quotes_created": 3, "bookmarks_created": 3, "lookups_created": 3,
                 "lookups_book": 3, "lookups_word": 3, "quotes_memo": 3, "bookmarks_memo": 3}


def table_of(create):
    return re.match(r"CREATE TABLE IF NOT EXISTS (\w+)\(", create).group(1)


def index_of(create):
    m = re.match(r"CREATE INDEX IF NOT EXISTS (\w+) ON (\w+)", create)
    return m.group(1), m.group(2)


def strip_columns(create, table, version):
    """[create] without the columns ADDED_COLUMNS lists for versions above [version]."""
    head, body = create.split("(", 1)
    body = body[:body.rindex(")")]
    parts, depth, cur = [], 0, ""
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    drop = {c for (t, c, v, _) in ADDED if t == table and v > version}
    kept = [p for p in parts if p.strip().split(" ")[0] not in drop]
    return head + "(" + ",".join(kept) + ")"


def schema_of(version):
    out = []
    for s in CREATE_ALL:
        if s.startswith("CREATE TABLE"):
            t = table_of(s)
            if TABLE_VERSION.get(t, 1) <= version:
                out.append(strip_columns(s, t, version))
        else:
            name, _ = index_of(s)
            if INDEX_VERSION.get(name, 1) <= version:
                out.append(s)
    return out


def columns(db, table):
    return {r[1] for r in db.execute("PRAGMA table_info(%s)" % table)}


def upgrade_statements(db, old):
    """LibrarySchema.upgradeStatements with PRAGMA table_info as the column source."""
    out = []
    for (t, c, v, sql) in ADDED:
        if old >= v:
            continue
        if any(x.lower() == c.lower() for x in columns(db, t)):
            continue
        out.append(sql)
    return out


def on_upgrade(db, old):
    """LibraryDb.onUpgrade, inside one transaction like SQLiteOpenHelper. Returns (ALTERs run, millis)."""
    t0 = time.perf_counter()
    db.execute("BEGIN")
    for s in CREATE_ALL:
        db.execute(s)
    alters = upgrade_statements(db, old)
    for s in alters:
        db.execute(s)
    if old < 3:
        for s in UPGRADE_SWEEP + NOTES_SWEEP:
            db.execute(s)
    db.execute("PRAGMA user_version = %d" % DB_VERSION)
    db.execute("COMMIT")
    return alters, (time.perf_counter() - t0) * 1000


def open_db():
    db = sqlite3.connect(":memory:", isolation_level=None)
    return db


def create(version):
    db = open_db()
    for s in schema_of(version):
        db.execute(s)
    db.execute("PRAGMA user_version = %d" % version)
    return db


def prepare(db, name, sql, args=None):
    try:
        n = sql.count("?")
        db.execute("EXPLAIN " + sql, list(args) if args is not None else [None] * n)
        return True
    except sqlite3.Error as e:
        print("     %s: %s\n     %s" % (name, e, sql))
        return False


def seed_books(db, n, first=1):
    for i in range(first, first + n):
        db.execute("INSERT INTO books(id, path, file_name, title) VALUES (?, ?, ?, ?)",
                   (i, "/b/%d.txt" % i, "%d.txt" % i, "책 %d" % i))


# ---------------------------------------------------------------------------------------------------------------

def check_sources():
    old_syntax = [n for n, v in SQL.items() if re.search(r"ON CONFLICT|RETURNING|\bOVER \(|NULLS (FIRST|LAST)|IIF\(|UPSERT", v.upper())]
    check(not old_syntax, "every statement is SQLite 3.18 syntax" + (" " + str(old_syntax) if old_syntax else ""))
    check(DB_VERSION == 3, "DB_VERSION is 3")
    v3_added = [a for a in ADDED if a[2] == 3]
    check(len(v3_added) == 9, "nine v3 ADDED_COLUMNS (%d)" % len(v3_added))
    added_names = {(t, c) for (t, c, _, _) in ADDED}
    bad = []
    for s in CREATE_INDEXES:
        _, table = index_of(s)
        cols_part = s.split(" ON ", 1)[1]
        for (t, c) in added_names:
            if t == table and re.search(r"\b%s\b" % c, cols_part):
                bad.append(s)
    check(not bad, "no index names an ADDED_COLUMNS column " + (str(bad) if bad else ""))
    check(len(SQL) >= 60, "read %d LibrarySql statements from the Kotlin sources" % len(SQL))
    check(SQL["INSERT_QUOTE"].count("?") == 11 and SQL["INSERT_BOOKMARK"].count("?") == 9,
          "INSERT_QUOTE 11 / INSERT_BOOKMARK 9 placeholders")


def check_fresh():
    db = create(DB_VERSION)
    failed = [n for n, s in SQL.items() if not prepare(db, n, s)]
    check(not failed, "fresh v3: every LibrarySql statement prepares (%d)" % len(SQL) + (" " + str(failed) if failed else ""))
    check("return_mark" in columns(db, "book_prefs"), "fresh v3: book_prefs.return_mark")
    for t, c in (("quotes", "sig"), ("bookmarks", "frac"), ("books", "missing_at"), ("books", "review_at")):
        check(c in columns(db, t), "fresh v3: %s.%s" % (t, c))
    return db


def check_v1_upgrade():
    db = create(1)
    check("style" not in columns(db, "quotes"), "v1 file: quotes has no style column")
    seed_books(db, 3)
    db.execute("INSERT INTO quotes(book_id, quote_text, note, created_at) VALUES (1, 'q', 'm', 5)")
    db.execute("INSERT INTO bookmarks(book_id, snippet, created_at) VALUES (2, 's', 6)")
    alters, ms = on_upgrade(db, 1)
    # book_prefs is new to a v1 file: CREATE_ALL makes it with return_mark, so that ALTER is skipped by the guard.
    check(len(alters) == 9 and LIB_RETURN_ALTER not in alters, "v1 -> v3: nine ALTERs (style + eight v3 columns)")
    row = db.execute("SELECT style, chapter, frac, sig FROM quotes").fetchone()
    check(row == (0, "", -1.0, ""), "v1 -> v3: old quote reads the defaults %r" % (row,))
    check(db.execute("SELECT COUNT(*) FROM bookmarks").fetchone()[0] == 1, "v1 -> v3: data kept")
    failed = [n for n, s in SQL.items() if not prepare(db, n, s)]
    check(not failed, "v1 -> v3: every statement prepares" + (" " + str(failed) if failed else ""))
    again, _ = on_upgrade(db, 1)
    check(again == [], "v1 -> v3 run twice: the column guard adds nothing")


def check_v2_upgrade_quiet():
    """The seeded v2 -> v3 file of [check_v2_upgrade] without its checks (for plan assertions)."""
    global check
    saved = check
    check = lambda cond, what: None
    try:
        db = check_v2_upgrade()
    finally:
        check = saved
    db.execute("ANALYZE")
    return db


def check_v2_upgrade():
    db = create(2)
    seed_books(db, 500)
    db.execute("BEGIN")
    db.executemany("INSERT INTO quotes(book_id, section, start_offset, end_offset, quote_text, note, created_at, style) "
                   "VALUES (?, 0, ?, ?, ?, ?, ?, 0)",
                   [(1 + i % 500, i, i + 10, "인용문 %d " % i * 8, "메모" if i % 7 == 0 else "", 1_700_000_000_000 + i)
                    for i in range(10_000)])
    db.executemany("INSERT INTO bookmarks(book_id, section, char_offset, snippet, note, created_at) VALUES (?, 0, ?, ?, ?, ?)",
                   [(1 + i % 500, i, "북마크 %d" % i, "n" if i % 5 == 0 else "", 1_700_000_000_000 + i) for i in range(3_000)])
    db.execute("INSERT INTO book_prefs(book_id, finished_at) VALUES (1, 5)")
    db.execute("COMMIT")
    alters, ms = on_upgrade(db, 2)
    check(len(alters) == 9, "v2 -> v3: nine ALTERs")
    check(ms <= UPGRADE_BUDGET_MS, "v2 -> v3 on 10k quotes + 3k bookmarks: %.1f ms (budget %.0f ms)" % (ms, UPGRADE_BUDGET_MS))
    check(db.execute("SELECT COUNT(*) FROM quotes WHERE frac = -1").fetchone()[0] == 10_000, "v2 -> v3: old rows frac = -1")
    check(db.execute("SELECT return_mark FROM book_prefs WHERE book_id = 1").fetchone() == (None,),
          "v2 -> v3: book_prefs row kept, return_mark NULL")
    names = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'index'")}
    check(set(INDEX_VERSION) <= names, "v2 -> v3: the v3 indexes exist")
    return db


def check_downgrade_roundtrip():
    db = create(3)
    seed_books(db, 3)
    for b in (1, 2, 3):
        db.execute("INSERT INTO lookups(book_id, word, word_key, created_at) VALUES (?, 'w', 'w', 1)", (b,))
        db.execute("INSERT INTO quotes(book_id, quote_text, created_at, chapter, frac, sig) VALUES (?, 'q', 1, '1장', 0.5, 'e:1')", (b,))
    db.execute("INSERT INTO book_prefs(book_id, return_mark) VALUES (1, 'mark')")
    # The "v2 build": onDowngrade keeps the data and stamps v2; its deleteBookRows doesn't know lookups.
    db.execute("PRAGMA user_version = 2")
    for t in ("bookmarks", "quotes", "book_collections", "page_counts", "reading_log", "book_prefs"):
        db.execute("DELETE FROM %s WHERE book_id = 2" % t)
    db.execute("DELETE FROM books WHERE id = 2")
    db.execute("INSERT INTO bookmarks(book_id, snippet, created_at) VALUES (2, 'orphan', 1)")
    check(db.execute("SELECT COUNT(*) FROM lookups WHERE book_id = 2").fetchone()[0] == 1, "v2 build leaves an orphan lookup")
    alters, _ = on_upgrade(db, 2)
    check(alters == [], "v3 -> v2 build -> v3: no duplicate ALTER")
    check(db.execute("SELECT book_id FROM lookups ORDER BY book_id").fetchall() == [(1,), (3,)],
          "re-upgrade sweep removes exactly the orphan lookups")
    check(db.execute("SELECT COUNT(*) FROM bookmarks WHERE book_id = 2").fetchone()[0] == 0,
          "re-upgrade sweep removes an orphan bookmark")
    check(db.execute("SELECT return_mark FROM book_prefs WHERE book_id = 1").fetchone() == ("mark",),
          "re-upgrade keeps the return mark")
    check(db.execute("SELECT COUNT(*) FROM quotes WHERE frac = 0.5").fetchone()[0] == 2, "re-upgrade keeps note places")


def plan(db, sql, args=None):
    n = sql.count("?")
    rows = db.execute("EXPLAIN QUERY PLAN " + sql, list(args) if args is not None else [1] * n).fetchall()
    return " | ".join(r[-1] for r in rows)


def expect_plan(db, label, sql, must=(), must_not=("TEMP B-TREE",)):
    p = plan(db, sql)
    ok = all(m in p for m in must) and not any(m in p for m in must_not)
    check(ok, "plan %s: %s" % (label, p))


def check_plans(db):
    db.execute("ANALYZE")
    # Data core statements.
    expect_plan(db, "SELECT_QUOTES", SQL["SELECT_QUOTES"], must=("quotes_book",), must_not=())
    expect_plan(db, "SELECT_BOOKMARKS", SQL["SELECT_BOOKMARKS"], must=("bookmarks_book",), must_not=())
    expect_plan(db, "DELETE_LOOKUPS_OF_BOOK", "SELECT id FROM lookups WHERE book_id = ?", must=("lookups_book",))
    for n in ("UPDATE_QUOTE_STYLE", "UPDATE_QUOTE_PLACE", "UPDATE_BOOKMARK_PLACE", "SET_MISSING", "CLEAR_MISSING",
              "SET_REVIEW", "CLEAR_REVIEW", "UNTRASH"):
        expect_plan(db, n, SQL[n], must=("INTEGER PRIMARY KEY",))
    expect_plan(db, "SELECT_RETURN_MARK", SQL["SELECT_RETURN_MARK"], must=("INTEGER PRIMARY KEY",))
    expect_plan(db, "SELECT_IDS_WITH_NOTES", SQL["SELECT_IDS_WITH_NOTES"],
                must=("quotes_book", "bookmarks_book", "lookups_book"), must_not=())
    # Hub statement shapes (N §5.3): unfiltered single-arm date pages walk *_created with no sort.
    for t, a in (("quotes", "q"), ("bookmarks", "m"), ("lookups", "l")):
        for order in ("DESC", "ASC"):
            expect_plan(db, "%s page %s" % (t, order),
                        "SELECT %s.id FROM %s %s ORDER BY %s.created_at %s, %s.id %s LIMIT 51 OFFSET 49"
                        % (a, t, a, a, order, a, order), must=("%s_created" % t,))
    for t in ("quotes", "bookmarks"):
        expect_plan(db, "%s memo page" % t,
                    "SELECT id FROM %s WHERE note <> '' ORDER BY created_at DESC, id DESC LIMIT 51" % t,
                    must=("%s_memo" % t,))
        expect_plan(db, "%s memo count" % t, "SELECT COUNT(*) FROM %s WHERE note <> ''" % t, must=("%s_memo" % t,))
        expect_plan(db, "%s count" % t, "SELECT COUNT(*) FROM %s" % t)
        expect_plan(db, "%s book filter" % t,
                    "SELECT id FROM %s WHERE book_id = ? ORDER BY created_at DESC, id DESC LIMIT 51" % t,
                    must=("%s_book" % t,), must_not=())
        expect_plan(db, "%s details by id" % t,
                    "SELECT id, substr(note, 1, 401) FROM %s WHERE id IN (?, ?, ?, ?)" % t,
                    must=("INTEGER PRIMARY KEY",))
    expect_plan(db, "lookups word group", "SELECT COUNT(*) FROM lookups WHERE word_key = ?", must=("lookups_word",))


def check_dump(path):
    root = json.load(open(path, encoding="utf-8"))
    db = create(DB_VERSION)
    failed, n = [], 0

    def walk(node, where):
        nonlocal n
        if isinstance(node, dict):
            if isinstance(node.get("sql"), str):
                n += 1
                if not prepare(db, where, node["sql"], node.get("args")):
                    failed.append(where)
                return
            for k, v in node.items():
                if k in ("schema", "upgrade"):
                    continue
                walk(v, where + "." + k)
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, "%s[%d]" % (where, i))
        elif isinstance(node, str) and node.lstrip().upper().startswith(("SELECT", "INSERT", "UPDATE", "DELETE")):
            n += 1
            if not prepare(db, where, node):
                failed.append(where)

    walk(root, "dump")
    check(not failed, "dump %s: %d statements prepare" % (os.path.basename(path), n) + (" " + str(failed[:10]) if failed else ""))
    # The hub's statements (dump "notes", from DA-N's NotesSql): each entry may carry "expect" (plan substrings that
    # must appear) and "forbid" (that must not; default "TEMP B-TREE FOR ORDER BY" for unfiltered date pages).
    # Their plans are asserted on the seeded, upgraded file.
    notes = root.get("notes")
    if notes:
        pdb = check_v2_upgrade_quiet()
        entries = []

        def collect(node, where):
            if isinstance(node, dict):
                if isinstance(node.get("sql"), str):
                    entries.append((where, node))
                    return
                for k, v in node.items():
                    collect(v, where + "." + k)
            elif isinstance(node, list):
                for i, v in enumerate(node):
                    collect(v, "%s[%d]" % (where, i))

        collect(notes, "notes")
        bad = []
        for where, e in entries:
            if "expect" not in e and "forbid" not in e:
                continue
            p = plan(pdb, e["sql"], e.get("args"))
            if not all(m in p for m in e.get("expect", [])) or any(m in p for m in e.get("forbid", [])):
                bad.append("%s: %s" % (where, p))
        check(not bad, "dump notes: %d statement plans as expected" % len(entries) + (" " + str(bad[:5]) if bad else ""))
    up = root.get("upgrade", {})
    if up:
        sweep = UPGRADE_SWEEP + NOTES_SWEEP
        check(up.get("v2", [])[-len(sweep):] == sweep and up.get("v3") == [],
              "dump: LibraryDb.upgradeTail matches (sweep last below v3, nothing at v3)")


def main(argv):
    check_sources()
    check_fresh()
    check_v1_upgrade()
    db = check_v2_upgrade()
    check_downgrade_roundtrip()
    check_plans(db)
    if "--dump" in argv:
        check_dump(argv[argv.index("--dump") + 1])
    print("sqlite %s: %s" % (sqlite3.sqlite_version, "%d FAILED" % len(FAILURES) if FAILURES else "all checks passed"))
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
