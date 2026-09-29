#!/usr/bin/env python3
"""Generate stand-in R.java and BuildConfig.java so Kotlin sources can be type-checked
without the Android SDK build tools (see tools/typecheck.sh). Ids are dummy values."""
import os, re, sys, xml.etree.ElementTree as ET

res_dir, package, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
types = {}
def add(t, name):
    types.setdefault(t, set()).add(re.sub(r'[.:-]', '_', name))

ID_RE = re.compile(r'@\+id/([A-Za-z0-9_.]+)')
for d in sorted(os.listdir(res_dir)):
    full = os.path.join(res_dir, d)
    if not os.path.isdir(full):
        continue
    base = d.split('-')[0]
    for f in sorted(os.listdir(full)):
        path = os.path.join(full, f)
        name = f.split('.')[0]
        if base == 'values':
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as e:
                sys.exit(f"XML parse error in {path}: {e}")
            for el in root:
                tag = el.tag
                n = el.get('name')
                if tag == 'item':
                    tag = el.get('type') or 'string'
                if tag in ('string-array', 'integer-array', 'array'):
                    tag = 'array'
                if tag == 'declare-styleable':
                    add('styleable', n)
                    for a in el.findall('attr'):
                        add('styleable', n + '_' + a.get('name').replace('android:', 'android_'))
                        if not a.get('name').startswith('android:'):
                            add('attr', a.get('name'))
                    continue
                if n:
                    add(tag, n)
        else:
            add(base, name)
            if f.endswith('.xml'):
                with open(path, encoding='utf-8') as fh:
                    for m in ID_RE.finditer(fh.read()):
                        add('id', m.group(1))

pkg_dir = os.path.join(out_dir, *package.split('.'))
os.makedirs(pkg_dir, exist_ok=True)
counter = 0x7f000000
lines = [f"package {package};", "public final class R {"]
for t in sorted(types):
    lines.append(f"  public static final class {t} {{")
    for n in sorted(types[t]):
        counter += 1
        if t == 'styleable' and '_' not in n:
            lines.append(f"    public static final int[] {n} = new int[0];")
        else:
            lines.append(f"    public static final int {n} = {counter};")
    lines.append("  }")
lines.append("}")
open(os.path.join(pkg_dir, 'R.java'), 'w').write('\n'.join(lines) + '\n')
open(os.path.join(pkg_dir, 'BuildConfig.java'), 'w').write(
    f"package {package};\npublic final class BuildConfig {{\n"
    "  public static final boolean DEBUG = true;\n"
    f"  public static final String APPLICATION_ID = \"{package}\";\n"
    "  public static final String BUILD_TYPE = \"debug\";\n"
    "  public static final int VERSION_CODE = 1;\n"
    "  public static final String VERSION_NAME = \"0.1.0\";\n}\n")
print(f"R.java: {sum(len(v) for v in types.values())} symbols")
