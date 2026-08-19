#!/usr/bin/env python3
"""Structure-preserving EN->zh-CN translation for selected BotDialoguePack YAML files."""
from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

from deep_translator import GoogleTranslator

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack"
DST = ROOT / "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack-zh-CN"
CACHE = ROOT / "scripts/.zh_translate_cache.json"

FILES = [
    "ScrollingBotDialogue.yaml",
    "MerchantBotDialogue.yaml",
    "FMBotDialogue.yaml",
    "ShopOfferDialogue.yaml",
    "DropGameBotDialogue.yaml",
    "DropGameSpectatorDialogue.yaml",
    "GameZoneHostBotDialogue.yaml",
    "HenesysBotDialogue.yaml",
]

TOKEN_RE = re.compile(r"\{[A-Z][A-Z0-9_]*\}")
DASH_QUOTE = re.compile(r'^(\s+-\s+)"((?:\\.|[^"\\])*)"(\s*)$')
LINE_EMOTE = re.compile(r'^(\s+-\s+\{line:\s*)"((?:\\.|[^"\\])*)"((?:,\s*emote:\s*\d+\}\s*|\}\s*))$')

# Keep these as-is (chat particles / symbols)
KEEP_AS_IS = {
    "...", "zzz", "o/", ":)", ":(", ":P", ";)", ":D", "xD", "XD", "^^", "~",
    "@@@", "*******", "*yawn*", "wb", "sup", "yo", "hm?", "hmm", "nah",
}

# Applied AFTER machine translation to fix leftover English game jargon.
GLOSSARY = [
    (r"(?i)\bmesos?\b", "金币"),
    (r"\bFM\b", "自由市场"),
    (r"(?i)\bhenesys\b", "射手村"),
    (r"(?i)\borbis\b", "天空之城"),
    (r"(?i)\bellinia\b", "魔法密林"),
    (r"(?i)\bkerning\b", "废弃都市"),
    (r"(?i)\bperion\b", "勇士部落"),
    (r"(?i)\blith harbor\b", "明珠港"),
    (r"(?i)\bmaple island\b", "枫叶岛"),
    (r"(?i)\bzakum\b", "扎昆"),
    (r"\bNX\b", "点券"),
    (r"(?i)\bcash shop\b", "点券商城"),
    (r"(?i)\bwhite scrolls?\b", "白卷"),
    (r"(?i)\bdark scrolls?\b", "黑暗卷轴"),
    (r"(?i)\bscrolls?\b", "卷轴"),
    (r"(?i)\bscrolling\b", "砸卷"),
    (r"(?i)\bequips?\b", "装备"),
    (r"(?i)\bgear\b", "装备"),
    (r"(?i)\bpots?\b", "药水"),
    (r"(?i)\bgrinding\b", "刷怪"),
    (r"(?i)\bgrind\b", "刷怪"),
    (r"(?i)\btraining\b", "练级"),
    (r"(?i)\bdrop games?\b", "掉落游戏"),
    (r"(?i)\brng\b", "人品"),
    (r"(?i)\bpq\b", "组队任务"),
    (r"(?i)\bbrb\b", "马上回"),
    (r"(?i)\bafk\b", "挂机"),
    (r"(?i)\btyvm\b", "太感谢了"),
    (r"(?i)\bsmh\b", "无语"),
    (r"(?i)\btbh\b", "说实话"),
    (r"(?i)\bngl\b", "不骗你"),
    (r"(?i)\blmao\b", "笑死"),
    (r"(?i)\blol\b", "哈哈"),
    (r"(?i)\bomg\b", "我去"),
    (r"(?i)\bwtf\b", "搞什么"),
    (r"\bEXP\b", "经验"),
    (r"(?i)\bmule\b", "小号"),
    (r"(?i)\bfame\b", "人气"),
    (r"(?i)\bdefame[d]?\b", "踩人气"),
    (r"(?i)\bwhisper\b", "密聊"),
    (r"(?i)\bpm me\b", "密我"),
    (r"(?i)\btrade me\b", "跟我交易"),
]

translator = GoogleTranslator(source="en", target="zh-CN")


def load_cache() -> dict:
    if CACHE.exists():
        return json.loads(CACHE.read_text(encoding="utf-8"))
    return {}


def save_cache(cache: dict) -> None:
    CACHE.write_text(json.dumps(cache, ensure_ascii=False, indent=0), encoding="utf-8")


def protect_tokens(text: str) -> tuple[str, list[str]]:
    tokens: list[str] = []

    def repl(m: re.Match) -> str:
        tokens.append(m.group(0))
        return f"⟦TOK{len(tokens)-1}⟧"

    return TOKEN_RE.sub(repl, text), tokens


def restore_tokens(text: str, tokens: list[str]) -> str:
    for i, tok in enumerate(tokens):
        # Google may mangle brackets
        for variant in (f"⟦TOK{i}⟧", f"[TOK{i}]", f"（TOK{i}）", f"(TOK{i})", f"TOK{i}"):
            if variant in text:
                text = text.replace(variant, tok)
                break
        else:
            # try loose match
            text = re.sub(rf"[\[【⟦(（]?TOK{i}[\]】⟧)）]?", tok, text, count=1)
    return text


def apply_glossary(text: str) -> str:
    for pat, rep in GLOSSARY:
        text = re.sub(pat, rep, text)
    return text


def postprocess_zh(zh: str) -> str:
    # Light cleanup for chat feel
    zh = zh.replace("您", "你")
    zh = re.sub(r"。{2,}", "…", zh)
    zh = zh.strip()
    return zh


def translate_one(en: str, cache: dict) -> str:
    if en in cache:
        return cache[en]
    if en.strip() in KEEP_AS_IS or not en.strip():
        cache[en] = en
        return en
    # Pure symbols / numbers
    if re.fullmatch(r"[\W\d_]+", en, flags=re.UNICODE):
        cache[en] = en
        return en

    protected, tokens = protect_tokens(en)
    try:
        zh = translator.translate(protected)
        time.sleep(0.03)
    except Exception as e:
        print(f"  warn: {e!s:.80} | {en[:60]!r}", file=sys.stderr)
        zh = protected

    zh = restore_tokens(zh or protected, tokens)
    zh = apply_glossary(zh)
    zh = postprocess_zh(zh)
    cache[en] = zh
    return zh


def collect_strings(files: list[str]) -> list[str]:
    seen = set()
    ordered = []
    for name in files:
        for line in (SRC / name).read_text(encoding="utf-8").splitlines():
            m = DASH_QUOTE.match(line) or LINE_EMOTE.match(line)
            if not m:
                continue
            s = m.group(2)
            if s not in seen:
                seen.add(s)
                ordered.append(s)
    return ordered


def translate_file(name: str, cache: dict) -> None:
    src = SRC / name
    dst = DST / name
    out_lines = []
    for line in src.read_text(encoding="utf-8").splitlines(keepends=True):
        raw = line.rstrip("\n")
        ended = line.endswith("\n")
        m = DASH_QUOTE.match(raw)
        if m:
            zh = translate_one(m.group(2), cache)
            # Escape quotes in Chinese rarely needed but handle \"
            zh_esc = zh.replace("\\", "\\\\").replace('"', '\\"')
            new = f'{m.group(1)}"{zh_esc}"{m.group(3)}'
            out_lines.append(new + ("\n" if ended else ""))
            continue
        m = LINE_EMOTE.match(raw)
        if m:
            zh = translate_one(m.group(2), cache)
            zh_esc = zh.replace("\\", "\\\\").replace('"', '\\"')
            new = f'{m.group(1)}"{zh_esc}"{m.group(3)}'
            out_lines.append(new + ("\n" if ended else ""))
            continue
        out_lines.append(line)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text("".join(out_lines), encoding="utf-8")
    print(f"Wrote {dst.relative_to(ROOT)}")


def main() -> None:
    DST.mkdir(parents=True, exist_ok=True)
    cache = load_cache()
    strings = collect_strings(FILES)
    todo = [s for s in strings if s not in cache]
    print(f"Unique strings: {len(strings)}; cached: {len(strings)-len(todo)}; todo: {len(todo)}")

    # Prefill translations in batches via translate_batch when possible
    BATCH = 40
    for i in range(0, len(todo), BATCH):
        chunk = todo[i : i + BATCH]
        # translate individually for token safety
        for j, s in enumerate(chunk):
            translate_one(s, cache)
            if (i + j + 1) % 50 == 0:
                print(f"  progress {i+j+1}/{len(todo)}")
                save_cache(cache)
        save_cache(cache)
        print(f"  batch done {min(i+BATCH, len(todo))}/{len(todo)}")

    save_cache(cache)
    for name in FILES:
        translate_file(name, cache)
    print("All done.")


if __name__ == "__main__":
    main()
