"""Lists every t('...') text in lib/ that has no Khmer in km_mobile.dart or km_web.g.dart.
Run from mobile/: python3 tool/check_km.py  (exits 1 when something is missing)"""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent / "lib"
# A single- or double-quoted Dart string; the text is in group 1 or 2.
lit = r"""(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")"""


def unescape(s: str) -> str:
    return re.sub(r"\\(.)", r"\1", s)


known = set()
for name in ("core/km_mobile.dart", "core/km_web.g.dart"):
    known |= {unescape(a or b) for a, b in re.findall(rf"^\s*{lit}:", (root / name).read_text(), re.M)}

used = {}
for f in root.rglob("*.dart"):
    if f.name.startswith("km_"):
        continue
    for m in re.finditer(rf"\bt\(\s*{lit}", f.read_text()):
        used.setdefault(unescape(m.group(1) or m.group(2)), f.relative_to(root))

missing = sorted(k for k in used if k not in known)
for k in missing:
    print(f"{used[k]}: {k}")
print(f"{len(used)} texts, {len(missing)} without Khmer", file=sys.stderr)
sys.exit(1 if missing else 0)
