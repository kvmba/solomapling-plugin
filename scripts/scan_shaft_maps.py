#!/usr/bin/env python3
"""Find "shaft" maps: a narrow, tall map whose only way up is one very long rope/ladder.

These are the maps a community bot (roaming TownWandererBot / stationed SocialBot)
must never pick as a destination - a bot that enters reads as a stuck statue (climbs,
falls, repeats) and arrivals pile up under the exit. The two known instances:

    222000001  童话村[井口]           Korean Folk Town / Pond
    221000001  地球防御本部[通道]      Omega Sector / Tunnel

The decisive question is not the geometry alone but whether the map is *selectable*:
the community destination pickers only consider a map that is one walkable portal hop
from a town that is actually configured in EnvironmentPopulation.yaml (the wanderer's
1-hop map family; the stationed bot's cross-map stroll likewise). A shaft no town can
reach is never chosen, so it is not a live concern.

Usage:
  python3 scan_shaft_maps.py [--wz <Map.wz/Map dir>] [--plugin <plugin repo root>]

Defaults: --wz /workspace/GMS083/gms-server/wz/Map.wz/Map
          --plugin <this repo> (auto: two dirs up from scripts/)
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys

NO_TARGET = 999999999

FLOOR_RE = re.compile(
    r'<int name="x1" value="(-?\d+)"/>\s*'
    r'<int name="y1" value="(-?\d+)"/>\s*'
    r'<int name="x2" value="(-?\d+)"/>\s*'
    r'<int name="y2" value="(-?\d+)"/>')
ROPE_RE = re.compile(
    r'<int name="x" value="(-?\d+)"/>\s*'
    r'<int name="y1" value="(-?\d+)"/>\s*'
    r'<int name="y2" value="(-?\d+)"/>')
PORTAL_ENTRY_RE = re.compile(r'<imgdir name="\d+">(.*?)</imgdir>', re.S)

# --- classification thresholds (a "shaft": narrow + one connector spanning most of its height) ---
MIN_ROPE_LEN = 500      # the vertical connector must be at least this long
MIN_ROPE_RATIO = 0.40   # ...and span at least this fraction of the map's own height
MAX_FLOOR_WIDTH = 900   # and the map's floor extent must be at most this wide


def load(path: str) -> str:
    return open(path, encoding='utf-8', errors='replace').read()


def int_attr(block: str, key: str):
    m = re.search(r'name="%s" value="(-?\d+)"' % key, block)
    return int(m.group(1)) if m else None


def parse(mid: int, path: str) -> dict:
    d = load(path)
    # info: the town/swim/link flags live under info. Some maps nest an empty <info> early in the
    # file (the obj/tile sections), so search the whole document for the specific keys instead of
    # slicing a fixed window around the first <info>.
    link = int_attr(d, 'link')
    town = re.search(r'name="town" value="1"', d) is not None
    swim = re.search(r'name="swim" value="1"', d) is not None

    life = d[d.find('<imgdir name="life">'):]
    rj = life.find('<imgdir name="reactor">')
    life_blk = life[:rj] if rj > 0 else life[:20000]
    mobs = len(re.findall(r'<string name="type" value="m"', life_blk))

    fi = d.find('<imgdir name="foothold">')
    li = d.find('<imgdir name="ladderRope">')
    mi = d.find('<imgdir name="miniMap">')
    floors = []
    if fi >= 0:
        floors_blk = d[fi:li] if li > fi else d[fi:]
        floors = [tuple(int(x) for x in m.groups()) for m in FLOOR_RE.finditer(floors_blk)]
    horiz = [f for f in floors if f[0] != f[2]]
    floor_w = (max(f[2] for f in horiz) - min(f[0] for f in horiz)) if horiz else 0

    ropes = []
    if li >= 0:
        rb = d[li:mi] if mi > li else d[li:li + 5000]
        for m in ROPE_RE.finditer(rb):
            ropes.append(abs(int(m.group(3)) - int(m.group(2))))

    ys = [y for f in floors for y in (f[1], f[3])]
    return dict(mid=mid, link=link, town=town, swim=swim, mobs=mobs,
                floor_w=floor_w, ys=ys, max_rope=max(ropes, default=0), ropes=len(ropes),
                portals=extract_portals(d))


def extract_portals(d: str) -> list[tuple[int, int, str]]:
    """(targetMapId, pt, script) for each portal entry."""
    pi = d.find('<imgdir name="portal">')
    out = []
    if pi < 0:
        return out
    for pm in PORTAL_ENTRY_RE.finditer(d[pi:pi + 16000]):
        b = pm.group(1)
        tm = int_attr(b, 'tm')
        if tm is None:
            continue
        sc = re.search(r'name="script" value="([^"]*)"', b)
        out.append((tm, int_attr(b, 'pt') or 0, sc.group(1) if sc else ''))
    return out


def portal_targets(d: str) -> set[int]:
    """Walkable, unscripted portal destinations (matches GCWorldGraph.readMap)."""
    tgts = set()
    for tm, pt, script in extract_portals(d):
        if tm == NO_TARGET or pt == 6 or script:
            continue
        tgts.add(tm)
    return tgts


def read_town_maps(plugin_root: str) -> set[int]:
    """Map ids configured under waves.town_presence.towns in EnvironmentPopulation.yaml."""
    path = os.path.join(plugin_root,
                        'src/main/java/soloMapling/Environment/EnvironmentPopulation.yaml')
    if not os.path.exists(path):
        return set()
    text = load(path)
    i = text.find('town_presence:')
    if i < 0:
        return set()
    seg = text[i:]
    ids: set[int] = set()
    for blk in re.finditer(r'-\s*name:\s*(.+?)\n(.*?)(?=\n\s*-\s*name:|\Z)', seg, re.S):
        ids |= {int(m) for m in re.findall(r'\{map:\s*(\d+)', blk.group(2))}
    return ids


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--wz', default='/workspace/GMS083/gms-server/wz/Map.wz/Map')
    ap.add_argument('--plugin', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()

    files: dict[int, str] = {}
    for d in sorted(glob.glob(os.path.join(args.wz, 'Map*'))):
        if not os.path.isdir(d):
            continue
        for f in glob.glob(os.path.join(d, '*.img.xml')):
            base = os.path.basename(f)[:-8]
            if base.isdigit():
                files[int(base)] = f

    parsed = {}
    targets: dict[int, set[int]] = {}
    for mid, f in files.items():
        try:
            d = load(f)
            parsed[mid] = parse(mid, f)
            targets[mid] = portal_targets(d)
        except Exception:
            continue

    town_maps = read_town_maps(args.plugin)

    cands = []
    for mid, m in parsed.items():
        if not m['ys']:
            continue
        H = max(m['ys']) - min(m['ys'])
        if H <= 0:
            continue
        if (m['max_rope'] >= MIN_ROPE_LEN
                and m['max_rope'] >= MIN_ROPE_RATIO * H
                and m['floor_w'] <= MAX_FLOOR_WIDTH):
            m['H'] = H
            # The community pickers only ever see a map that is 1 walkable hop from a configured
            # town map. "reachable from any parsed map" is reported for context, but the SELECTABLE
            # column (adjacent to a town map) is what decides whether this is a live concern.
            m['selectable_from'] = sorted(t for t in town_maps if mid in targets.get(t, set()))
            cands.append(m)

    cands.sort(key=lambda m: (not bool(m['selectable_from']), -(m['max_rope'] / m['H'])))
    print(f"{'mapId':>10} {'W':>5} {'H':>6} {'rope':>5} {'r/H':>5} {'mobs':>5} "
          f"{'town':>5} {'swim':>5}  {'selectable by a town bot?':<26} name")
    for m in cands:
        name = zh_name(args.wz, m["mid"])
        sel = ('YES from ' + ','.join(map(str, m['selectable_from']))) if m['selectable_from'] else '-'
        print(f"{m['mid']:>10} {m['floor_w']:>5} {m['H']:>6} {m['max_rope']:>5} "
              f"{m['max_rope']/m['H']:>5.2f} {m['mobs']:>5} {str(m['town']):>5} "
              f"{str(m['swim']):>5}  {sel:<26} {name}")
    live = [m for m in cands if m['selectable_from']]
    print(f"\ntotal shaft-shaped maps: {len(cands)}   (of {len(parsed)} parsed)")
    print(f"actually selectable by a town bot: {len(live)} -> "
          f"{[m['mid'] for m in live]}")
    return 0


def zh_name(wz_dir: str, mid: int) -> str:
    """Chinese map name, read from the wz-zh-CN String.wz beside the Map.wz tree we scanned."""
    # wz_dir is …/wz/Map.wz/Map; the zh tree is a sibling of Map.wz's parent (…/wz-zh-CN/String.wz).
    wz_root = os.path.dirname(os.path.dirname(os.path.abspath(wz_dir)))   # …/wz
    p = os.path.join(os.path.dirname(wz_root), 'wz-zh-CN', 'String.wz', 'Map.img.xml')
    if not os.path.exists(p):
        return ''
    m = re.search(r'<imgdir name="%d">(.*?)</imgdir>' % mid, load(p), re.S)
    if not m:
        return ''
    d = dict(re.findall(r'<string name="(\w+)" value="([^"]*)"', m.group(1)))
    return f"{d.get('streetName', '?')}/{d.get('mapName', '?')}"


if __name__ == '__main__':
    sys.exit(main())
