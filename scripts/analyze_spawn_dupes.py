#!/usr/bin/env python3
"""按会话统计 packets.log 中重复下发的地图对象 spawn 包。

对同一个 objectId 重复发 SPAWN_NPC / SPAWN_PLAYER / REACTOR_SPAWN，客户端会重复
建对象或在已存在的 oid 上出错。这里按「进图 → 断开」切分会话，统计每个 oid 被
下发的次数，用来判断重复是否与掉线相关。

用法：python3 analyze_spawn_dupes.py <packets.log> [out.log]
"""
import collections
import re
import sys

LINE_RE = re.compile(
    r"^(?P<ts>\S+)\s+(?:<UnknownPacket>\s+)?"
    r"(?P<dir>ServerSend|ClientSend):(?P<name>\S*)\s+\[(?P<op>[0-9A-F]+)\]\s+"
    r"\((?P<len>\d+)\)\s+<HEX>\s+(?P<hex>[0-9A-Fa-f ]*?)(?:\s+<TEXT>|$)"
)

# oid 在各包体中的字节偏移（含 2 字节 opcode）
OID_AT = {
    "SPAWN_NPC": 2,
    "SPAWN_NPC_REQUEST_CONTROLLER": 3,   # 前面多一个 byte 标志
    "REACTOR_SPAWN": 2,
    "SPAWN_PLAYER": 2,                   # 这里其实是 characterId
}

SESSION_START = "SET_FIELD"


def oid_of(name, data):
    off = OID_AT[name]
    if len(data) < off + 4:
        return None
    return int.from_bytes(data[off:off + 4], "little")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)

    sessions = []           # [{start, counts: {(name,oid): [ts,...]}}]
    cur = None

    with open(sys.argv[1], errors="replace") as fh:
        for line in fh:
            m = LINE_RE.match(line)
            if not m or m.group("dir") != "ServerSend":
                continue
            name, ts = m.group("name"), m.group("ts")

            if name == SESSION_START:
                cur = {"start": ts, "counts": collections.defaultdict(list)}
                sessions.append(cur)
                continue

            if cur is None or name not in OID_AT:
                continue
            try:
                data = bytes.fromhex(m.group("hex").strip())
            except ValueError:
                continue
            oid = oid_of(name, data)
            if oid is not None:
                cur["counts"][(name, oid)].append(ts)

    for s in sessions:
        dupes = {k: v for k, v in s["counts"].items() if len(v) > 1}
        total = len(s["counts"])
        print(f"=== 会话 SET_FIELD @ {s['start']} ===")
        print(f"  下发对象种类 {total}，其中重复下发 {len(dupes)}")
        by_kind = collections.Counter(k[0] for k in dupes)
        for kind, n in by_kind.most_common():
            print(f"    {kind:<30} 重复 {n} 个 oid")
        # 展示重复次数最多的几个
        worst = sorted(dupes.items(), key=lambda kv: -len(kv[1]))[:5]
        for (kind, oid), tss in worst:
            print(f"    例：{kind} oid={oid} 共 {len(tss)} 次 @ {', '.join(tss[:6])}")
        print()


if __name__ == "__main__":
    main()
