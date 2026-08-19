#!/usr/bin/env python3
"""从 packets.log 的 SPAWN_PLAYER 包中精确解析每个 bot 的外观装备，并核对
服务端 wz/Character.wz 里是否存在对应 img。

SPAWN_PLAYER 前段含 writeForeignBuffs（长度可变），所以不按固定偏移读，而是
滑动窗口寻找 addCharLook 的起点，并用严格约束自我校验：

    gender(1) skin(1) face(int) flag(1) hair(int)
    然后 [slot(1) itemId(int)]* 0xFF   ← 可见装备
    然后 [slot(1) itemId(int)]* 0xFF   ← 被遮挡装备
    然后 cashWeapon(int) + pet(int)*3

只有整段都满足约束才接受，避免暴力扫字节带来的误报。

用法：python3 extract_spawn_equips.py <packets.log> <wz/Character.wz 路径>
"""
import collections
import os
import re
import sys

LINE = re.compile(
    r"^(?P<ts>\S+)\s+ServerSend:SPAWN_PLAYER\s+\[A0\]\s+\((?P<len>\d+)\)"
    r"\s+<HEX>\s+(?P<hex>[0-9A-Fa-f ]*?)(?:\s+<TEXT>|$)"
)

FACE_RANGE = (20000, 29999)
HAIR_RANGE = (30000, 49999)
EQUIP_RANGE = (1000000, 1999999)
MAX_SLOT = 60


def u32(d, i):
    return int.from_bytes(d[i:i + 4], "little")


def try_parse_look(d, off):
    """从 off 处尝试解析 addCharLook，成功返回 (equips, masked, cash, end)。"""
    if off + 11 > len(d):
        return None
    gender, skin = d[off], d[off + 1]
    if gender > 1 or skin > 11:
        return None
    face = u32(d, off + 2)
    if not (FACE_RANGE[0] <= face <= FACE_RANGE[1]):
        return None
    if d[off + 6] > 1:
        return None
    hair = u32(d, off + 7)
    if not (HAIR_RANGE[0] <= hair <= HAIR_RANGE[1]):
        return None

    i = off + 11

    def read_list():
        nonlocal i
        out = {}
        while True:
            if i >= len(d):
                return None
            if d[i] == 0xFF:
                i += 1
                return out
            slot = d[i]
            if slot == 0 or slot > MAX_SLOT or i + 5 > len(d):
                return None
            item = u32(d, i + 1)
            if not (EQUIP_RANGE[0] <= item <= EQUIP_RANGE[1]):
                return None
            out[slot] = item
            i += 5

    visible = read_list()
    if visible is None:
        return None
    masked = read_list()
    if masked is None:
        return None
    if i + 4 > len(d):
        return None
    cash = u32(d, i)
    if cash != 0 and not (EQUIP_RANGE[0] <= cash <= EQUIP_RANGE[1]):
        return None
    return visible, masked, cash, face, hair, i + 4


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    log_path, wz_root = sys.argv[1], sys.argv[2]

    have = set()
    for dirpath, _dirnames, filenames in os.walk(wz_root):
        for fn in filenames:
            m = re.fullmatch(r"0*(\d+)\.img\.xml", fn)
            if m:
                have.add(int(m.group(1)))
    print(f"wz/Character.wz 中 img 数量：{len(have)}")

    parsed = failed = 0
    item_use = collections.Counter()
    face_use = collections.Counter()
    hair_use = collections.Counter()
    first_seen = {}

    with open(log_path, errors="replace") as fh:
        for line in fh:
            m = LINE.match(line)
            if not m:
                continue
            d = bytes.fromhex(m.group("hex").strip())
            res = None
            # 名称之后才可能是 look，最早从偏移 7 起试
            for off in range(7, min(len(d) - 11, 200)):
                res = try_parse_look(d, off)
                if res:
                    break
            if not res:
                failed += 1
                continue
            parsed += 1
            visible, masked, cash, face, hair, _end = res
            face_use[face] += 1
            hair_use[hair] += 1
            for it in list(visible.values()) + list(masked.values()) + ([cash] if cash else []):
                item_use[it] += 1
                first_seen.setdefault(it, m.group("ts"))

    print(f"SPAWN_PLAYER 解析成功 {parsed}，失败 {failed}")
    print(f"去重装备 {len(item_use)}，脸 {len(face_use)}，发型 {len(hair_use)}")
    print()

    for label, ctr in (("装备", item_use), ("脸", face_use), ("发型", hair_use)):
        missing = sorted(i for i in ctr if i not in have)
        print(f"=== {label}：wz/Character.wz 中缺失 {len(missing)} / {len(ctr)} ===")
        for i in missing:
            print(f"  {i}  出现 {ctr[i]} 次，首次 {first_seen.get(i, '-')}")
        print()


if __name__ == "__main__":
    main()
