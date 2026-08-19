#!/usr/bin/env python3
"""校验 packets.log 里服务端发出的封包结构是否自洽。

针对 MOVE_PLAYER 做逐命令字节核算：每条命令消耗的字节数取自服务端权威解析器
AbstractMovementPacketHandler.updatePositionBot。若某个包声明的 numCommands /
command 与实际字节数不符，客户端就会读错位，进而解密流错乱、静默断开——这正是
我们要找的东西。其余 opcode 做长度分布统计，用来发现离群包。

用法：python3 validate_packets.py <packets.log>
"""
import collections
import re
import sys

# command -> 载荷字节数（不含 command 自身），取自 updatePositionBot
CMD_SIZE = {}
for c in (0, 5, 17):
    CMD_SIZE[c] = 13          # short+short+skip6+byte+short
for c in (1, 2, 6, 12, 13, 16, 18, 19, 20, 22):
    CMD_SIZE[c] = 7           # skip4+byte+short
for c in (3, 4, 7, 8, 9, 11):
    CMD_SIZE[c] = 9           # skip8+byte
CMD_SIZE[14] = 9              # skip9
CMD_SIZE[10] = 1              # byte
CMD_SIZE[15] = 15             # skip12+byte+short
CMD_SIZE[21] = 3              # skip3

MOVE_PLAYER_HEADER = 10       # opcode(2) + cid(4) + int0(4)

LINE_RE = re.compile(
    r"^(?P<ts>\S+)\s+(?:<UnknownPacket>\s+)?"
    r"(?P<dir>ServerSend|ClientSend):(?P<name>\S*)\s+\[(?P<op>[0-9A-F]+)\]\s+"
    r"\((?P<len>\d+)\)\s+<HEX>\s+(?P<hex>[0-9A-Fa-f ]*?)(?:\s+<TEXT>|$)"
)


def check_move_player(data):
    """返回 None 表示结构自洽，否则返回错误描述。"""
    if len(data) < MOVE_PLAYER_HEADER + 1:
        return f"包太短：{len(data)} 字节"
    mv = data[MOVE_PLAYER_HEADER:]
    num = mv[0]
    if num < 1:
        return f"numCommands={num}（引擎要求 >=1，会抛 EmptyMovementException）"
    pos = 1
    for i in range(num):
        if pos >= len(mv):
            return f"第 {i + 1}/{num} 条命令：读 command 时越界（offset {pos}/{len(mv)}）"
        cmd = mv[pos]
        pos += 1
        size = CMD_SIZE.get(cmd)
        if size is None:
            return f"第 {i + 1}/{num} 条命令：未知 command={cmd}"
        if pos + size > len(mv):
            return (f"第 {i + 1}/{num} 条命令 cmd={cmd} 需 {size} 字节，"
                    f"仅剩 {len(mv) - pos}")
        pos += size
    if pos != len(mv):
        return f"解析完 {num} 条命令后剩余 {len(mv) - pos} 字节未被消耗"
    return None


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    path = sys.argv[1]

    lengths = collections.defaultdict(collections.Counter)
    cmd_hist = collections.Counter()
    bad = []
    total = parsed = move_total = 0

    with open(path, "r", errors="replace") as fh:
        for lineno, line in enumerate(fh, 1):
            total += 1
            m = LINE_RE.match(line)
            if not m:
                continue
            parsed += 1
            hexstr = m.group("hex").strip()
            try:
                data = bytes.fromhex(hexstr)
            except ValueError:
                bad.append((lineno, m.group("ts"), m.group("name"), "hex 无法解析"))
                continue

            declared = int(m.group("len"))
            key = f'{m.group("dir")}:{m.group("name")}[{m.group("op")}]'
            lengths[key][len(data)] += 1

            if declared != len(data):
                bad.append((lineno, m.group("ts"), key,
                            f"声明长度 {declared} != 实际 {len(data)}"))

            if m.group("dir") == "ServerSend" and m.group("name") == "MOVE_PLAYER":
                move_total += 1
                mv = data[MOVE_PLAYER_HEADER:]
                if len(mv) >= 2:
                    cmd_hist[mv[1]] += 1
                err = check_move_player(data)
                if err:
                    bad.append((lineno, m.group("ts"), key, err))

    print(f"总行数 {total}，成功解析 {parsed}，其中 MOVE_PLAYER {move_total}")
    print()
    print("=== MOVE_PLAYER 首条 command 分布 ===")
    for cmd, n in sorted(cmd_hist.items()):
        print(f"  command {cmd:>3} : {n:>6}  (载荷 {CMD_SIZE.get(cmd, '?')} 字节)")
    print()
    print("=== 各 opcode 长度分布（多长度的优先） ===")
    for key, ctr in sorted(lengths.items(), key=lambda kv: -len(kv[1])):
        shape = ", ".join(f"{ln}B×{n}" for ln, n in sorted(ctr.items()))
        print(f"  {key:<42} {shape}")
    print()
    if bad:
        print(f"!!! 发现 {len(bad)} 处结构异常：")
        for lineno, ts, key, err in bad[:60]:
            print(f"  行{lineno} {ts} {key}: {err}")
        if len(bad) > 60:
            print(f"  ... 另有 {len(bad) - 60} 处")
    else:
        print("所有封包结构自洽，未发现长度/命令数不符。")


if __name__ == "__main__":
    main()
