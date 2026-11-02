#!/usr/bin/env python3
"""Prüft, welche REL::IDs aus CommonLibF4 (include/RE/IDs.h) in einer Address-Library-Datei
(version-1-11-240-0.bin, Format V0: u64 Anzahl, dann Paare u64 id / u64 offset) fehlen.
Aufruf: python3 -I tools/check_addrlib.py <version-...bin> [Namensfilter ...]"""
import re, struct, sys, pathlib

ids_h = pathlib.Path(__file__).resolve().parent.parent / "third_party/commonlibf4/include/RE/IDs.h"
binf = pathlib.Path(sys.argv[1])
filters = sys.argv[2:]

data = binf.read_bytes()
n = struct.unpack_from("<Q", data, 0)[0]
have = {}
for i in range(n):
    k, off = struct.unpack_from("<QQ", data, 8 + i * 16)
    have[k] = off
print(f"{binf.name}: {n} Einträge, IDs {min(have)}..{max(have)}")

stack, names = [], {}
for line in ids_h.read_text().splitlines():
    m = re.match(r"\s*namespace (\w+)", line)
    if m: stack.append(m.group(1)); continue
    m = re.match(r"\s*inline constexpr REL::ID (\w+)\{\s*(\d+)\s*\}", line)
    if m: names["::".join(stack[1:] + [m.group(1)])] = int(m.group(2)); continue
    if line.strip() == "}" and stack: stack.pop()

sel = {k: v for k, v in names.items() if not filters or any(f in k for f in filters)}
missing = {k: v for k, v in sel.items() if v not in have}
print(f"geprüft: {len(sel)}, fehlend: {len(missing)}")
for k, v in sorted(missing.items()):
    print(f"  FEHLT {k} = {v}")
