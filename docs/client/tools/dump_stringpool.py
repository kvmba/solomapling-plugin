#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Dump & decrypt the MapleStory v83 client StringPool (``Angel.exe`` / BeiDou build).

Run inside IDA Pro 9.x against ``BeiDou.i64``::

    idat.exe -A -S"dump_stringpool.py" BeiDou.i64
    # or: File > Script file... in the GUI

Outputs to ``../data/`` (i.e. ``docs/client/data/``)::

    stringpool.json   structured dump (index / enum name / VA / key / cipher / text)
    stringpool.tsv    one line per string: "<index>\\t<enum>\\t<text>"

---------------------------------------------------------------------------
Reverse-engineered anchors (this build, image base 0x400000)
---------------------------------------------------------------------------
    StringPool::GetString          0x406455   public wrapper
        -> real impl               0x79E993
            -> sub_79ECDE          0x79ECDE   decrypt one entry
                -> sub_79E780      0x79E780   copy entry bytes, rotate key table
                    -> sub_79EBF3  0x79EBF3   byte-rotate + bit-rotate
                -> sub_79E7E8      0x79E7E8   keystream[i % len]
    StringPool::ms_apString        0xBDC9D4   5658 entry pointers (off_BDC9D4)
    g_keyTable                     0xB001EC   16-byte global XOR key
    g_keyTableLen                  0xB001FC   = 0x10

Entry layout (at off_BDC9D4[i]):
    +0  BYTE  per-string key (also used as the rotate count)
    +1  ...   C-string ciphertext (NUL terminated)

Algorithm:
    T = rot16(g_keyTable, key)          # see rot16()
    for i, c in enumerate(cipher):
        p = c ^ T[i % 16]
        plain[i] = T[i % 16] if p == 0 else p    # decoder never emits NUL

rot16(t, k):
    if k >= 8:  t = t[(k >> 3) % 16:] + t[:(k >> 3) % 16]   # byte rotate left
    if k & 7:   t = big-endian 128-bit rotate-left by (k & 7)  # bit rotate
"""

import json
import os
import struct

import ida_bytes
import idautils
import idc

IMAGE_BASE = 0x400000
TABLE_VA = 0xBDC9D4
ENTRY_COUNT = 5658  # enum StringPoolStrings = SP_0 .. SP_5657 (+ "DUMMY" = -2)
KEY_VA = 0xB001EC
KEY_LEN_VA = 0xB001FC
GETSTRING_VA = 0x79E993

HERE = os.path.dirname(os.path.abspath(__file__)) if "__file__" in globals() else os.getcwd()
OUT_DIR = os.path.normpath(os.path.join(HERE, "..", "data"))


def rd(va, size):
    if va < IMAGE_BASE:
        return b""
    data = ida_bytes.get_bytes(va, size)
    return data if data else b""


def key_table():
    n = struct.unpack("<I", rd(KEY_LEN_VA, 4))[0] if rd(KEY_LEN_VA, 4) else 16
    return bytearray(rd(KEY_VA, n))


def rot16(table, key):
    """Byte-rotate then bit-rotate the key table, exactly like sub_79EBF3."""
    t = bytearray(table)
    n = len(t)
    if n == 0:
        return t
    if key >= 8:
        shift = (key >> 3) % n
        if shift:
            t = t[shift:] + t[:shift]
    bits = key & 7
    if bits:
        v = int.from_bytes(t, "big")
        v = ((v << bits) | (v >> (8 * n - bits))) & ((1 << (8 * n)) - 1)
        t = bytearray(v.to_bytes(n, "big"))
    return t


def decrypt(entry_va, table):
    blob = rd(entry_va, 0x8000)  # longest entry (SP_3041) is 1963 bytes, stop at NUL
    if not blob:
        raise ValueError("unreadable entry at 0x%X" % entry_va)
    end = blob.find(b"\x00", 1)  # the key byte itself may be 0x00
    if end < 0:
        end = len(blob)
    key = blob[0]
    cipher = blob[1:end]
    t = rot16(table, key)
    plain = bytes(t[i % len(t)] if (c ^ t[i % len(t)]) == 0 else c ^ t[i % len(t)]
                  for i, c in enumerate(cipher))
    return key, cipher, plain


def enum_names(count):
    """value -> StringPoolStrings member name (IDA enum), empty dict if missing."""
    out = {}
    try:
        eid = idc.get_enum("StringPoolStrings")
        if eid >= 0:
            for v in range(count):
                cid = idc.get_enum_member(eid, v, 0, -1)
                if cid != -1:
                    name = idc.get_enum_member_name(cid)
                    if name:
                        out[v] = name
    except Exception:
        pass
    return out


def decode_text(raw):
    for enc in ("ascii", "cp949", "utf-8"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("cp1252", "replace")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    table = key_table()
    if len(table) != 16:
        raise RuntimeError("unexpected key table length %d" % len(table))
    names = enum_names(ENTRY_COUNT)
    entries = []
    lines = []
    failures = []

    for idx in range(ENTRY_COUNT):
        ptr_raw = rd(TABLE_VA + 4 * idx, 4)
        if not ptr_raw:
            failures.append((idx, "unreadable pointer"))
            continue
        entry_va = struct.unpack("<I", ptr_raw)[0]
        try:
            key, cipher, plain = decrypt(entry_va, table)
        except Exception as exc:  # noqa: BLE001 - report and keep going
            failures.append((idx, str(exc)))
            continue

        text = decode_text(plain)
        name = names.get(idx) or ""
        entries.append({
            "index": idx,
            "name": name,
            "va": "0x%08X" % entry_va,
            "key": "0x%02X" % key,
            "cipher": cipher.hex(),
            "text": text,
        })
        one_line = text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
        lines.append("%d\t%s\t%s" % (idx, name, one_line))

    meta = {
        "module": idc.get_root_filename() or "BeiDou.i64",
        "image_base": "0x%08X" % IMAGE_BASE,
        "table_va": "0x%08X" % TABLE_VA,
        "entry_count": ENTRY_COUNT,
        "key_table_va": "0x%08X" % KEY_VA,
        "key_table_len_va": "0x%08X" % KEY_LEN_VA,
        "key_table": bytes(table).hex(),
        "get_string_va": "0x%08X" % GETSTRING_VA,
        "decrypted": len(entries),
        "failed": len(failures),
    }

    json_path = os.path.join(OUT_DIR, "stringpool.json")
    tsv_path = os.path.join(OUT_DIR, "stringpool.tsv")
    with open(json_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump({"meta": meta, "strings": entries}, fh, ensure_ascii=False, indent=1)
    with open(tsv_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("index\tname\ttext\n")
        fh.write("\n".join(lines) + "\n")

    print("key table: %s" % meta["key_table"])
    print("decrypted: %d / %d, failed: %d" % (len(entries), ENTRY_COUNT, len(failures)))
    if failures:
        print("failures: %r" % (failures[:10],))
    print("wrote %s" % json_path)
    print("wrote %s" % tsv_path)


if __name__ == "__main__":
    main()
