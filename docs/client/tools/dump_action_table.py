#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Dump the client's character-animation action table (raw action code -> action name).

Run inside IDA Pro 9.x against ``BeiDou.i64``::

    idat.exe -A -S"dump_action_table.py" BeiDou.i64

The table is ``CAvatar::s_aCharacterActionData`` (162 x 24-byte ACTIONDATA entries at
0xBEC620).  It is zero-filled in the file and populated by the dynamic initializer
``_dynamic_initializer_for__s_aCharacterActionData__`` (0x4A38E7), which does, per entry::

    push <StringPoolStrings index>
    call StringPool::GetInstance
    call StringPool::GetBSTR          ; 0x406292 -> decrypts the StringPool entry
    ...
    mov  ecx, offset s_aCharacterActionData[code]
    call ACTIONDATA::<assign>

``CAvatar::get_action_name_from_code`` (0x4A8CE6) is the runtime lookup::

    s_aCharacterActionData[code].name      ; 24 * code bytes

Outputs to ``../data/`` (i.e. ``docs/client/data/``)::

    actions.tsv    "<code>\\t<stringpool_idx>\\t<name>"
    actions.json
"""

import json
import os
import struct

import ida_bytes
import ida_funcs
import ida_ua
import idc

INIT_EA = 0x4A38E7            # _dynamic_initializer_for__s_aCharacterActionData__
ARRAY_EA = 0xBEC620           # CAvatar::s_aCharacterActionData
ENTRY_SIZE = 24               # ACTIONDATA
MAX_ENTRIES = 400
GETBSTR_EA = 0x406292         # StringPool::GetBSTR
GET_ACTION_NAME_EA = 0x4A8CE6 # CAvatar::get_action_name_from_code

SP_TABLE_VA = 0xBDC9D4
SP_KEY_VA = 0xB001EC
SP_KEY_LEN_VA = 0xB001FC

IMAGE_BASE = 0x400000
HERE = os.path.dirname(os.path.abspath(__file__)) if "__file__" in globals() else os.getcwd()
OUT_DIR = os.path.normpath(os.path.join(HERE, "..", "data"))


def scan_pairs():
    """(code, StringPool index) pairs, in the order the initializer writes them."""
    func = ida_funcs.get_func(INIT_EA)
    if func is None:
        raise RuntimeError("initializer not found at 0x%X" % INIT_EA)
    insn = ida_ua.insn_t()
    ea = func.start_ea
    last_push = None
    pending = None
    rows = []
    while ea < func.end_ea:
        size = ida_ua.decode_insn(insn, ea)
        if size <= 0:
            ea += 1
            continue
        mnem = insn.get_canon_mnem()
        ops = insn.ops
        if mnem == "push" and ops[0].type == ida_ua.o_imm:
            last_push = ops[0].value
        elif mnem == "call" and ops[0].type == ida_ua.o_near and ops[0].addr == GETBSTR_EA:
            pending = last_push
        elif (pending is not None and mnem == "mov"
              and ops[0].type == ida_ua.o_reg and ops[0].reg == 1
              and ops[1].type == ida_ua.o_imm):
            va = ops[1].value
            if ARRAY_EA <= va < ARRAY_EA + ENTRY_SIZE * MAX_ENTRIES:
                rows.append(((va - ARRAY_EA) // ENTRY_SIZE, pending))
                pending = None
        ea += size
    return rows


def read(va, size):
    data = ida_bytes.get_bytes(va, size)
    return data if data else b""


def key_table():
    raw = read(SP_KEY_LEN_VA, 4)
    n = struct.unpack("<I", raw)[0] if raw else 16
    return bytearray(read(SP_KEY_VA, n))


def rot16(table, key):
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


def stringpool_entry(index, table):
    raw_ptr = read(SP_TABLE_VA + 4 * index, 4)
    if not raw_ptr:
        raise ValueError("no StringPool pointer for index %d" % index)
    entry_va = struct.unpack("<I", raw_ptr)[0]
    blob = read(entry_va, 0x8000)
    end = blob.find(b"\x00", 1)  # the key byte itself may be 0x00
    if end < 0:
        end = len(blob)
    key = blob[0]
    cipher = blob[1:end]
    t = rot16(table, key)
    plain = bytes(t[i % len(t)] if (c ^ t[i % len(t)]) == 0 else c ^ t[i % len(t)]
                  for i, c in enumerate(cipher))
    try:
        return plain.decode("cp949")
    except UnicodeDecodeError:
        return plain.decode("cp1252", "replace")


def main():
    table = key_table()
    rows = scan_pairs()
    if not rows:
        raise RuntimeError("no (code, StringPool index) pairs found")

    entries = []
    lines = []
    for code, sp_idx in rows:
        name = stringpool_entry(sp_idx, table) if sp_idx is not None else ""
        entries.append({"code": code, "stringpool_index": sp_idx, "name": name})
        lines.append("%d\t%s\t%s" % (code, sp_idx, name))

    meta = {
        "module": idc.get_root_filename() or "BeiDou.i64",
        "table_va": "0x%08X" % ARRAY_EA,
        "entry_size": ENTRY_SIZE,
        "initializer_va": "0x%08X" % INIT_EA,
        "lookup_va": "0x%08X" % GET_ACTION_NAME_EA,
        "count": len(entries),
    }

    tsv_path = os.path.join(OUT_DIR, "actions.tsv")
    json_path = os.path.join(OUT_DIR, "actions.json")
    with open(tsv_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("code\tstringpool_index\tname\n")
        fh.write("\n".join(lines) + "\n")
    with open(json_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump({"meta": meta, "actions": entries}, fh, ensure_ascii=False, indent=1)

    print("action entries: %d" % len(entries))
    for e in entries:
        print("%3d\t%5s\t%s" % (e["code"], e["stringpool_index"], e["name"]))
    print("wrote %s" % tsv_path)
    print("wrote %s" % json_path)


if __name__ == "__main__":
    main()
