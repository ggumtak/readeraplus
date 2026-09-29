#!/usr/bin/env python3
"""Fetch Material Symbols (Apache-2.0, google/material-design-icons) Android vector drawables
and normalise them to plain black 24dp icons without theme attributes."""
import re, sys, urllib.request, os, concurrent.futures as cf
BASE = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android"
OUT = sys.argv[1]
NAMES = sys.argv[2:]
def fetch(spec):
    # spec: name or name:fill  -> file ic_<name>[_fill].xml
    name, _, variant = spec.partition(':')
    fname = f"{name}_fill1_24px.xml" if variant == 'fill' else f"{name}_24px.xml"
    url = f"{BASE}/{name}/materialsymbolsoutlined/{fname}"
    try:
        data = urllib.request.urlopen(url, timeout=30).read().decode()
    except Exception as e:
        return spec, f"FAIL {e}"
    data = re.sub(r'\s*android:tint="[^"]*"', '', data)
    data = re.sub(r'android:fillColor="[^"]*"', 'android:fillColor="#FF000000"', data)
    out = os.path.join(OUT, f"ic_{name}{'_fill' if variant=='fill' else ''}.xml")
    open(out, 'w').write(data)
    return spec, "ok"
with cf.ThreadPoolExecutor(8) as ex:
    for spec, res in ex.map(fetch, NAMES):
        if res != "ok": print(spec, res)
print("done")
