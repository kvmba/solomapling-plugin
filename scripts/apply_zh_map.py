#!/usr/bin/env python3
"""Apply en->zh map to YAML files preserving exact structure."""
from __future__ import annotations
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack"
DST = ROOT / "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack-zh-CN"

DASH = re.compile(r'^(\s+-\s+)"((?:\\.|[^"\\])*)"(\s*)$')
LINE = re.compile(r'^(\s+-\s+\{line:\s*)"((?:\\.|[^"\\])*)"((?:,\s*emote:\s*(?:\d+|\[(?:\d+,\s*)*\d+\])\}\s*|\}\s*))$')

def unescape(s: str) -> str:
    return s.replace('\\"', '"').replace('\\\\', '\\')

def escape(s: str) -> str:
    return s.replace('\\', '\\\\').replace('"', '\\"')

def apply_file(name: str, mapping: dict) -> tuple[int,int]:
    missing = 0
    hit = 0
    out = []
    for line in (SRC/name).read_text(encoding='utf-8').splitlines(keepends=True):
        raw = line.rstrip('\n'); nl = '\n' if line.endswith('\n') else ''
        m = DASH.match(raw) or LINE.match(raw)
        if not m:
            out.append(line); continue
        en = unescape(m.group(2))
        zh = mapping.get(en)
        if zh is None:
            missing += 1
            zh = en  # leave English if missing (will report)
        else:
            hit += 1
        if DASH.match(raw):
            out.append(f'{m.group(1)}"{escape(zh)}"{m.group(3)}{nl}')
        else:
            out.append(f'{m.group(1)}"{escape(zh)}"{m.group(3)}{nl}')
    DST.mkdir(parents=True, exist_ok=True)
    (DST/name).write_text(''.join(out), encoding='utf-8')
    return hit, missing

def main():
    map_path = Path(sys.argv[1])
    files = sys.argv[2:]
    mapping = json.loads(map_path.read_text(encoding='utf-8'))
    for f in files:
        hit, miss = apply_file(f, mapping)
        print(f'{f}: hit={hit} missing={miss}')

if __name__ == '__main__':
    main()
