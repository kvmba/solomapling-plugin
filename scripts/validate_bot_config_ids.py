#!/usr/bin/env python3
"""Validate hardcoded / YAML bot-build item IDs against the BeiDou client.

Checks:
  1. Face / Hair / Equip IDs exist as Data/Character/**/{id:08d}.img
  2. Hair base IDs expand +0..+7 color variants; faces expand +0,+100..+800
  3. Chair / use-item IDs exist under Data/Item (best-effort folder guess)
  4. Character equips that exist but are info-only on the server XML are flagged
     (no action trees — bad for worn gear)

Sources covered:
  - BotCosmeticPool.java
  - BeginnerEquip.java
  - BotCustomization.java (chairs / store permits)
  - GenericEquipPool.yaml
  - NXItemPool.yaml
  - EquipOmitList.yaml (blocklist — still checked for stale IDs)

Usage:
  python3 validate_bot_config_ids.py \\
    --client /Users/.../BeiDou-Client \\
    --server-wz /Users/.../gms-server/wz/Character.wz \\
    --plugin /Users/.../solomapling-plugin
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path
from xml.etree import ElementTree as ET

INT_RE = re.compile(r"(?<![.\w])(\d{5,7})(?![.\w])")
YAML_ID_RE = re.compile(
    r"(?m)^[ \t]*-[ \t]*(?:\{[ \t]*id:[ \t]*)?(\d{5,7})\b"
)


def index_imgs(root: Path, suffix: str = ".img") -> dict[int, Path]:
    out: dict[int, Path] = {}
    if not root.is_dir():
        return out
    for p in root.rglob(f"*{suffix}"):
        name = p.name
        if not name.endswith(suffix):
            continue
        base = name[: -len(suffix)].lstrip("0") or "0"
        if base.isdigit():
            out[int(base)] = p
    return out


def extract_java_array_ids(text: str, markers: list[str]) -> set[int]:
    """Pull ints from array/list initializers near known field markers."""
    ids: set[int] = set()
    for marker in markers:
        for m in re.finditer(re.escape(marker), text):
            # take a window after the marker until semicolon or next static/field
            window = text[m.start() : m.start() + 8000]
            end = re.search(r";\s*(?://|/\*|private|public|static|protected|\n\s*\n)", window)
            chunk = window[: end.start()] if end else window[:4000]
            ids.update(int(x) for x in INT_RE.findall(chunk))
    return ids


def extract_yaml_ids(path: Path) -> set[int]:
    text = path.read_text(errors="replace")
    # strip full-line comments
    lines = []
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        lines.append(line)
    body = "\n".join(lines)
    return {int(x) for x in YAML_ID_RE.findall(body)}


def expand_hair(base: int) -> list[int]:
    return [base + i for i in range(8)]


def expand_face(base: int) -> list[int]:
    return [base + i * 100 for i in range(9)]


def is_info_only(xml_path: Path) -> bool:
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError:
        return False
    actions = [
        c.get("name")
        for c in root
        if c.tag == "imgdir" and c.get("name") not in (None, "info")
    ]
    return len(actions) == 0


def classify(item_id: int) -> str:
    if 20000 <= item_id <= 29999:
        return "face"
    if 30000 <= item_id <= 49999:
        return "hair"
    if 1000000 <= item_id <= 1999999:
        return "equip"
    if 2000000 <= item_id <= 5999999:
        return "item"
    return "other"


def find_item_on_client(item_id: int, item_index: dict[int, Path]) -> Path | None:
    return item_index.get(item_id)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--client", required=True, type=Path)
    ap.add_argument("--server-wz", required=True, type=Path)
    ap.add_argument("--plugin", required=True, type=Path)
    args = ap.parse_args()

    plugin = args.plugin / "src/main/java/soloMapling"
    client_char = args.client / "Data" / "Character"
    client_item = args.client / "Data" / "Item"

    char_index = index_imgs(client_char, ".img")
    item_index = index_imgs(client_item, ".img")
    server_index = index_imgs(args.server_wz, ".img.xml")

    print(f"Client Character imgs: {len(char_index)}")
    print(f"Client Item imgs:      {len(item_index)}")
    print(f"Server Character xml:  {len(server_index)}")
    print()

    sources: dict[str, set[int]] = {}

    # --- BotCosmeticPool ---
    cos = (plugin / "ArtificialPlayer/BotDecoratorSystem/BotCosmeticPool.java").read_text()
    hair_bases = extract_java_array_ids(
        cos,
        [
            "WILD_MALE_HAIR",
            "WILD_FEMALE_HAIR",
            "MALE_HAIR.put",
            "FEMALE_HAIR.put",
        ],
    )
    # filter to hair range only (weights etc. may sneak in)
    hair_bases = {i for i in hair_bases if 30000 <= i <= 49999}
    face_bases = extract_java_array_ids(
        cos,
        [
            "WILD_MALE_EYES",
            "WILD_FEMALE_EYES",
            "MALE_EYES.put",
            "FEMALE_EYES.put",
        ],
    )
    face_bases = {i for i in face_bases if 20000 <= i <= 29999}
    sources["BotCosmeticPool.hair_base"] = hair_bases
    sources["BotCosmeticPool.face_base"] = face_bases
    sources["BotCosmeticPool.hair_expanded"] = {v for b in hair_bases for v in expand_hair(b)}
    sources["BotCosmeticPool.face_expanded"] = {v for b in face_bases for v in expand_face(b)}

    # --- BeginnerEquip ---
    beg = (plugin / "ArtificialPlayer/BotDecoratorSystem/BeginnerEquip.java").read_text()
    sources["BeginnerEquip"] = {
        i for i in extract_java_array_ids(beg, ["SHOES", "MALE_TOPS", "FEMALE_TOPS", "CAP_", "pool.add", "equipWeapon", "equipPants"])
        if 1000000 <= i <= 1999999
    }
    # also catch simple assignments like private static final int SHOES = 1072005
    sources["BeginnerEquip"] |= {int(x) for x in INT_RE.findall(beg) if 1000000 <= int(x) <= 1999999}

    # --- BotCustomization chairs / permits ---
    cust = (plugin / "ArtificialPlayer/BotCustomization.java").read_text()
    chairs = set()
    for m in re.finditer(r"v83_chair_ids\s*=\s*List\.of\((.*?)\);", cust, re.S):
        chairs.update(int(x) for x in INT_RE.findall(m.group(1)))
    permits = set()
    for m in re.finditer(r"v83_store_permit_ids\s*=\s*\{(.*?)\};", cust, re.S):
        permits.update(int(x) for x in INT_RE.findall(m.group(1)))
    sources["BotCustomization.chairs"] = chairs
    sources["BotCustomization.store_permits"] = permits

    # --- YAML pools ---
    sources["GenericEquipPool.yaml"] = extract_yaml_ids(
        plugin / "ArtificialPlayer/BotDecoratorSystem/GenericEquipPool.yaml"
    )
    sources["NXItemPool.yaml"] = extract_yaml_ids(
        plugin / "ArtificialPlayer/BotDecoratorSystem/NXItemPool.yaml"
    )
    sources["EquipOmitList.yaml"] = extract_yaml_ids(plugin / "itemPool/EquipOmitList.yaml")

    # Report per source
    missing_by_source: dict[str, list[int]] = {}
    info_only: list[tuple[str, int, str]] = []
    all_char_ids: set[int] = set()
    all_item_ids: set[int] = set()

    for name, ids in sources.items():
        miss = []
        for iid in sorted(ids):
            kind = classify(iid)
            if kind in ("face", "hair", "equip"):
                all_char_ids.add(iid)
                if iid not in char_index:
                    miss.append(iid)
                else:
                    # info-only check via server XML (same ID set as client Character)
                    sx = server_index.get(iid)
                    if sx and kind == "equip" and is_info_only(sx):
                        # rings/earrings/medals can legitimately be info-only
                        prefix = iid // 10000
                        if prefix not in (111, 112, 113, 114, 115, 116, 118, 119):  # accessory-ish
                            # also allow 103 earrings? 103 is Accessory
                            if prefix not in (103,):
                                info_only.append((name, iid, str(sx.relative_to(args.server_wz))))
            elif kind == "item":
                all_item_ids.add(iid)
                if iid not in item_index:
                    miss.append(iid)
            else:
                # skin / misc — skip
                pass
        if miss:
            missing_by_source[name] = miss

    print("=== Per-source ID counts ===")
    for name, ids in sources.items():
        print(f"  {name:<40} {len(ids):>5}")
    print()

    print("=== Missing on client ===")
    if not missing_by_source:
        print("  none")
    else:
        for name, miss in missing_by_source.items():
            print(f"  {name}: {len(miss)} missing")
            for iid in miss[:40]:
                print(f"    {iid}  ({classify(iid)})")
            if len(miss) > 40:
                print(f"    ... +{len(miss) - 40} more")
    print()

    print("=== Equips present but info-only (no action tree) ===")
    # filter accessory slots that are expected info-only
    actionable = []
    for src, iid, rel in info_only:
        prefix = iid // 10000
        # Cap/Coat/Weapon/Glove/etc should have actions
        if prefix in (100, 101, 102, 104, 105, 106, 107, 108, 109, 110, 130, 131, 132, 133, 134,
                      137, 138, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 170):
            actionable.append((src, iid, rel, prefix))
    if not actionable:
        print("  none among wearable body/weapon slots")
    else:
        for src, iid, rel, prefix in actionable:
            print(f"  {iid}  prefix={prefix}  source={src}  xml={rel}")
    print()

    # Summary for Character curated IDs only (existence)
    char_checked = sorted(i for i in all_char_ids if classify(i) in ("face", "hair", "equip"))
    char_missing = [i for i in char_checked if i not in char_index]
    print(f"Character IDs checked: {len(char_checked)}, missing: {len(char_missing)}")
    print(f"Item IDs checked: {len(all_item_ids)}, missing: {len([i for i in all_item_ids if i not in item_index])}")

    # Write ID list for optional WzDeepAudit follow-up
    out_ids = Path(__file__).with_name("bot_config_character_ids.txt")
    out_ids.write_text("\n".join(str(i) for i in char_checked if i in char_index) + "\n")
    print(f"Wrote existing Character IDs for deep audit: {out_ids}")

    return 1 if missing_by_source or actionable else 0


if __name__ == "__main__":
    sys.exit(main())
