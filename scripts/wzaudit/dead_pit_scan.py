#!/usr/bin/env python3
"""Scan MapleStory map XML exports for dead-bottom pits.

A "dead pit" is a platform group a player can reach but can never leave upward:
  - no portal in the pit
  - no ladder/rope bottom within the pit (rope escape)
  - no climb path (graph search): jumps up to JUMP_REACH, walk-off / lateral
    jump onto lower platforms, stair slopes both directions.

Depth = pit floor y - nearest higher platform level with horizontal overlap.
"""
import re
import sys
from pathlib import Path

WZ_MAP_ROOT = Path("/workspace/GMS083/gms-server/wz/Map.wz/Map")

WALK_GAP = 12            # pieces closer than this merge into one floor run
JUMP_REACH = 100         # max rise a jump can climb (client jump apex ~77px + margin)
JUMP_ACROSS = 140        # max horizontal edge gap for a lateral jump / walk-off
ROPE_GRAB_SLACK = 40     # rope bottom may hang this far above the floor
ROPE_X_SLACK = 60        # rope this far horizontally from the pit can still be entered
PORTAL_X_SLACK = 30
PORTAL_Y_ABOVE = 80
PORTAL_Y_BELOW = 30
SLOPE_ENDPOINT_TOL = 25  # slope endpoints attach to platforms within this y
STEP_UP_TOL = 40         # rises up to this are trivial steps (snap/jump), not pits
OVERLAP_MIN = 5          # min horizontal overlap to jump straight up
MAX_PIT_WIDTH = 600      # wider basins are open floors, not pits

INT = re.compile(r'<int name="(\w+)" value="(-?\d+)"/>')
STR = re.compile(r'<string name="(\w+)" value="([^"]*)"/>')
FH = re.compile(
    r'<int name="x1" value="(-?\d+)"/>\s*'
    r'<int name="y1" value="(-?\d+)"/>\s*'
    r'<int name="x2" value="(-?\d+)"/>\s*'
    r'<int name="y2" value="(-?\d+)"/>')


def section(text, name):
    i = text.find('<imgdir name="%s">' % name)
    if i < 0:
        return ""
    j = text.find("\n  </imgdir>", i)
    return text[i:j if j > 0 else len(text)]


def parse_attrs(body):
    d = {}
    for m in INT.finditer(body):
        d[m.group(1)] = int(m.group(2))
    for m in STR.finditer(body):
        d[m.group(1)] = m.group(2)
    return d


def parse_map(path):
    text = path.read_text()
    fhs = [tuple(map(int, m)) for m in FH.findall(text)]
    if len(fhs) < 2:
        return None

    info = parse_attrs(section(text, "info"))
    if info.get("swim") or info.get("fly"):
        return None

    portals = []
    for m in re.finditer(r'<imgdir name="(\d+)">(.*?)(?=<imgdir name="\d+">|\Z)',
                         section(text, "portal"), re.S):
        a = parse_attrs(m.group(2))
        if "x" in a and "y" in a:
            portals.append((a["x"], a["y"], a.get("pn", "")))

    ropes = []
    for m in re.finditer(r'<imgdir name="(\d+)">(.*?)(?=<imgdir name="\d+">|\Z)',
                         section(text, "ladderRope"), re.S):
        a = parse_attrs(m.group(2))
        if "x" in a and "y1" in a and "y2" in a:
            ropes.append((a["x"], min(a["y1"], a["y2"]), max(a["y1"], a["y2"])))

    plats, slopes = [], []
    for x1, y1, x2, y2 in fhs:
        if y1 == y2:
            plats.append([min(x1, x2), y1, max(x1, x2)])
        elif x1 != x2:
            slopes.append((min(x1, x2), max(x1, x2), min(y1, y2), max(y1, y2)))
    if not plats:
        return None

    return plats, slopes, portals, ropes


def merge_groups(plats):
    plats = sorted(plats, key=lambda p: (p[1], p[0]))
    n = len(plats)
    parent = list(range(n))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(n):
        for j in range(i + 1, n):
            a, b = plats[i], plats[j]
            if a[1] != b[1]:
                break
            if b[0] - a[2] <= WALK_GAP:
                ri, rj = find(i), find(j)
                if ri != rj:
                    parent[rj] = ri

    groups = {}
    for i, p in enumerate(plats):
        groups.setdefault(find(i), []).append(p)
    out = []
    for members in groups.values():
        out.append((min(p[0] for p in members),
                    members[0][1],
                    max(p[2] for p in members),
                    len(members)))
    return sorted(out, key=lambda g: g[1])


def overlap(a1, a2, b1, b2):
    return min(a2, b2) - max(a1, b1)


def h_gap(a1, a2, b1, b2):
    if overlap(a1, a2, b1, b2) > 0:
        return 0
    return max(b1 - a2, a1 - b2)


def analyze(path):
    parsed = parse_map(path)
    if parsed is None:
        return None
    plats, slopes, portals, ropes = parsed
    groups = merge_groups(plats)
    if len(groups) < 2:
        return None

    def slope_attached(s, g):
        sx1, sx2, stop, sbot = s
        gx1, gy, gx2, _ = g
        if sx1 - 25 > gx2 or sx2 + 25 < gx1:
            return None
        for end_y in (stop, sbot):
            if abs(end_y - gy) <= SLOPE_ENDPOINT_TOL:
                return end_y
        return None

    # slope clusters: consecutive slope segments (stairs) chain into one walkable
    # unit via shared endpoints; a cluster attaches to platform groups at any of
    # its endpoints, so chains spanning several segments still connect levels.
    slope_parent = list(range(len(slopes)))

    def sfind(i):
        while slope_parent[i] != i:
            slope_parent[i] = slope_parent[slope_parent[i]]
            i = slope_parent[i]
        return i

    def slope_ends(s):
        sx1, sx2, stop, sbot = s
        return ((sx1, stop), (sx2, sbot))

    for i in range(len(slopes)):
        for j in range(i + 1, len(slopes)):
            ei = slope_ends(slopes[i])
            ej = slope_ends(slopes[j])
            for a in ei:
                for b in ej:
                    if abs(a[0] - b[0]) <= 6 and abs(a[1] - b[1]) <= 6:
                        ri, rj = sfind(i), sfind(j)
                        if ri != rj:
                            slope_parent[rj] = ri
    slope_members = {}
    for i in range(len(slopes)):
        slope_members.setdefault(sfind(i), []).append(slopes[i])

    def cluster_endpoints(cluster):
        pts = []
        for s in cluster:
            pts.extend(slope_ends(s))
        return set(pts)

    def cluster_attached_y(cluster, g):
        """best (lowest) y at which this cluster can be mounted from group g,
        counting both walk-on endpoints and jump-on endpoints (dy <= JUMP_REACH)."""
        gx1, gy, gx2, _ = g
        best = None
        for (px, py) in cluster_endpoints(cluster):
            if h_gap(px, px, gx1, gx2) > JUMP_ACROSS:
                continue
            rise = gy - py
            if 0 <= rise <= JUMP_REACH:
                if best is None or py < best:
                    best = py
        return best

    # reachability: up[a] = groups reachable from a going strictly higher
    n = len(groups)
    up = [set() for _ in range(n)]

    # direct jump-up edges + slope-cluster edges (walkable stairs)
    for i, a in enumerate(groups):
        for j, b in enumerate(groups):
            if i == j:
                continue
            if b[1] < a[1] and a[1] - b[1] <= JUMP_REACH \
                    and h_gap(a[0], a[2], b[0], b[2]) <= JUMP_ACROSS:
                up[i].add(j)
        for cluster in slope_members.values():
            ea = cluster_attached_y(cluster, a)
            if ea is None:
                continue
            for j, b in enumerate(groups):
                if i == j:
                    continue
                eb = cluster_attached_y(cluster, b)
                if eb is None or eb >= ea:
                    continue
                up[i].add(j)  # eb < ea: mount higher from the same slope system

    # lateral escape edges: from a you can drop to any lower b whose horizontal
    # edge gap is jumpable (walk off the rim and land on b, whatever its depth)
    for i, a in enumerate(groups):
        for j, b in enumerate(groups):
            if i == j or b[1] <= a[1]:
                continue
            if h_gap(a[0], a[2], b[0], b[2]) <= JUMP_ACROSS:
                pass  # b is a DOWNWARD move; does not help escaping upward

    def rope_ok(g):
        gx1, gy, gx2, _ = g
        for rx, ry1, ry2 in ropes:
            if ry2 < gy - JUMP_REACH - ROPE_GRAB_SLACK:
                continue
            if ry1 > gy:
                continue
            if gx1 - ROPE_X_SLACK <= rx <= gx2 + ROPE_X_SLACK:
                return True
        return False

    def portal_ok(g):
        gx1, gy, gx2, _ = g
        for px, py, _pn in portals:
            if gy - PORTAL_Y_ABOVE <= py <= gy + PORTAL_Y_BELOW and \
                    gx1 - PORTAL_X_SLACK <= px <= gx2 + PORTAL_X_SLACK:
                return True
        return False

    pits = []
    for i, g in enumerate(groups):
        if rope_ok(g) or portal_ok(g):
            continue
        # BFS over upward moves (jump-up edges + slope-up edges)
        seen = set()
        stack = [i]
        escaped = False
        while stack:
            cur = stack.pop()
            if cur in seen:
                continue
            seen.add(cur)
            for nxt in up[cur]:
                if nxt not in seen:
                    stack.append(nxt)
            # lateral walk-off: any group strictly higher reachable by dropping
            # off the rim is NOT an escape; escape means ending ABOVE start y
            if groups[cur][1] < g[1]:
                escaped = True
                break
        if escaped:
            continue
        # rim = nearest higher platform with overlap (defines depth)
        gx1, gy, gx2, _ = g
        rim = None
        for j, h in enumerate(groups):
            if h[1] >= gy or overlap(gx1, gx2, h[0], h[2]) < OVERLAP_MIN:
                continue
            if rim is None or h[1] > rim[1]:
                rim = h
        if rim is None:
            continue
        if gy - rim[1] <= STEP_UP_TOL:
            continue
        if gx2 - gx1 > MAX_PIT_WIDTH:
            continue
        pits.append({
            "map": path.stem.replace(".img", ""),
            "pit_y": gy, "pit_x1": gx1, "pit_x2": gx2,
            "rim_y": rim[1], "depth": gy - rim[1],
            "pieces": g[3],
        })
    return pits


def main():
    files = sorted(WZ_MAP_ROOT.rglob("*.img.xml"))
    all_pits = []
    for f in files:
        result = analyze(f)
        if result:
            all_pits.extend(result)

    maps_with = {p["map"] for p in all_pits}
    all_pits.sort(key=lambda p: p["depth"])
    print("maps scanned: %d, maps with dead pits: %d, dead pits found: %d"
          % (len(files), len(maps_with), len(all_pits)))
    if not all_pits:
        print("no dead pits found")
        return 0

    first = all_pits[0]
    print("\nminimum dead-pit depth: %d px (map %s, pit y=%d x[%d..%d], rim y=%d)"
          % (first["depth"], first["map"], first["pit_y"],
             first["pit_x1"], first["pit_x2"], first["rim_y"]))
    print("depth range: %d..%d px\n"
          % (all_pits[0]["depth"], all_pits[-1]["depth"]))

    print("%-11s %6s %8s %8s %s" % ("map", "depth", "pit_y", "rim_y", "pit x-range"))
    for p in all_pits:
        print("%-11s %6d %8d %8d   [%d..%d]"
              % (p["map"], p["depth"], p["pit_y"], p["rim_y"], p["pit_x1"], p["pit_x2"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
