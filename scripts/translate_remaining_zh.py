#!/usr/bin/env python3
"""Structure-preserving EN->zh-CN for remaining BotDialoguePack files.
Protects {TOKEN}, {item}, %PRICE% etc. Pre-glosses MapleStory terms, then Google Translate.
"""
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
CACHE = ROOT / "scripts/.zh_translate_cache_v2.json"

FILES = [
    "ScrollingBotDialogue.yaml",
    "MerchantBotDialogue.yaml",
    "FMBotDialogue.yaml",
    "ShopOfferDialogue.yaml",
    "GameZoneHostBotDialogue.yaml",
    "HenesysBotDialogue.yaml",
]

# Protect placeholders: {TOKEN}, {item}, %PRICE%, %NX_CODE% etc.
PLACEHOLDER_RE = re.compile(r"(\{[A-Za-z][A-Za-z0-9_]*\}|%[A-Z][A-Z0-9_]*%|@@+)")
DASH_QUOTE = re.compile(r'^(\s+-\s+)"((?:\\.|[^"\\])*)"(\s*)$')
LINE_EMOTE = re.compile(
    r'^(\s+-\s+\{line:\s*)"((?:\\.|[^"\\])*)"((?:,\s*emote:\s*(?:\d+|\[(?:\d+,\s*)*\d+\])\}\s*|\}\s*))$'
)

KEEP_AS_IS = {
    "...", "zzz", "zzzz", "o/", ":)", ":(", ":P", ";)", ":D", "xD", "XD", "^^", "~",
    "@@@", "*******", "*yawn*", "wb", "sup", "yo", "hm?", "hmm", "nah", "lol",
    ":)", ":(", "xD", "^^", ">_<", "o_o", "T_T", "=D", "<3", "n_n", "-_-", "^o^",
    "OwO", "XP", ";w;", "lmao", ":3", "////", "gg", "e_e", "\\o/", "('w')", "orz",
    "B)", "qq", "hm~", "o_o;", ":D", "ayy", "^_~", ":P", "o.o", ":O", ";)", "!!!",
    "d(^_^)b", ";_;", "~_~", "^-^", ":>", "@_@", "..!?", "mhm", "(o_o)", ">:)", "o7",
    "uwu", "(^_^)", "(*^_^*)", ">.<", "o3o", "8)", "c:", "^~^", "m(_ _)m", "(owo)",
    ":-)", "xdd", "8D", "=w=", "(^v^)", ">w<", ":33", ";;;", "rip", "oof", "<333",
    "0_0", "*applause*", ";-;", "*slow clap*", ":/", ":')", ";D", "F", "L", "RIP",
    "pfft", "hehe", "kekeke", "w00t", "psh", "tehe", "hahaha", "nice", "bruh",
    "yeet", "welp", "woah", "heh", "lul", "huehue", "eek", "wow", "wew", "rofl",
    "lulz", "lololol", "lololololol", "oof", "ooof", "waaaa", "omggg", "yaaas",
    "okayyyy", "cmon", "nyahahaha", "tehe~", "...ok then", "huhh?", "lmao ok",
    "ok ok", "hah", "hm hm", "heyyy", "do do dooo~", "la la la", "hmmm", "~",
}

# Apply BEFORE translate so Google doesn't mangle game terms
PRE_GLOSS = [
    (r"(?i)\bdrop games?\b", "掉落游戏"),
    (r"(?i)\bwhite scrolls?\b", "白卷"),
    (r"(?i)\bdark scrolls?\b", "黑暗卷轴"),
    (r"(?i)\bcash shop\b", "点券商城"),
    (r"(?i)\bmaple island\b", "枫叶岛"),
    (r"(?i)\blith harbor\b", "明珠港"),
    (r"(?i)\bmushroom kingdom\b", "蘑菇王国"),
    (r"(?i)\bhunting grounds?\b", "狩猎场"),
    (r"(?i)\bgame zone\b", "游戏区"),
    (r"(?i)\bstore permit\b", "开店许可证"),
    (r"(?i)\bjob advance(?:ment)?\b", "转职"),
    (r"(?i)\bdouble exp\b", "双倍经验"),
    (r"(?i)\bzakum helm\b", "扎昆头盔"),
    (r"(?i)\bwork gloves?\b", "工作手套"),
    (r"(?i)\bhenesys\b", "射手村"),
    (r"(?i)\borbis\b", "天空之城"),
    (r"(?i)\bellinia\b", "魔法密林"),
    (r"(?i)\bkerning\b", "废弃都市"),
    (r"(?i)\bperion\b", "勇士部落"),
    (r"(?i)\bludibrium\b", "玩具城"),
    (r"(?i)\bzakum\b", "扎昆"),
    (r"\bFM\b", "自由市场"),
    (r"(?i)\bmesos?\b", "金币"),
    (r"(?i)\bscrollings?\b", "砸卷"),
    (r"(?i)\bscrolled\b", "砸过的"),
    (r"(?i)\bscrolls?\b", "卷轴"),
    (r"(?i)\bequips?\b", "装备"),
    (r"(?i)\bpots?\b", "药水"),
    (r"(?i)\bgrinding\b", "刷怪"),
    (r"(?i)\bgrind\b", "刷怪"),
    (r"(?i)\btraining\b", "练级"),
    (r"(?i)\btrain\b", "练级"),
    (r"(?i)\brng\b", "人品"),
    (r"(?i)\bpq\b", "组队任务"),
    (r"\bNX\b", "点券"),
    (r"(?i)\bafk\b", "挂机"),
    (r"(?i)\bbrb\b", "马上回"),
    (r"(?i)\btyvm\b", "太感谢了"),
    (r"(?i)\bsmh\b", "无语"),
    (r"(?i)\btbh\b", "说实话"),
    (r"(?i)\bngl\b", "不骗你"),
    (r"(?i)\blmao\b", "笑死"),
    (r"(?i)\blol\b", "哈哈"),
    (r"(?i)\bomg\b", "我去"),
    (r"(?i)\bwtf\b", "搞什么"),
    (r"(?i)\bfame\b", "人气"),
    (r"(?i)\bdefame[ds]?\b", "踩人气"),
    (r"(?i)\bmule\b", "小号"),
    (r"(?i)\bstorage\b", "仓库"),
    (r"(?i)\bchannel\b", "频道"),
    (r"(?i)\bclean\b", "未砸"),
    (r"(?i)\bboomed\b", "炸了"),
    (r"(?i)\bboom\b", "炸"),
    (r"(?i)\bKSed\b", "被抢怪"),
    (r"(?i)\bKS\b", "抢怪"),
    (r"(?i)\bwts\b", "出"),
    (r"(?i)\bwtb\b", "收"),
    (r"\bS>\b", "出>"),
    (r"\bLF>\b", "收>"),
    (r"(?i)\bhmu\b", "私我"),
    (r"(?i)\bobo\b", "可刀"),
    (r"(?i)\bexp\b", "经验"),
    (r"(?i)\bpet\b", "宠物"),
    (r"(?i)\bguild\b", "公会"),
    (r"(?i)\bquest\b", "任务"),
    (r"(?i)\bnpc\b", "NPC"),
    (r"(?i)\bbgm\b", "BGM"),
    (r"(?i)\bzhelm\b", "扎昆头盔"),
    (r"(?i)\blowball\b", "砍价"),
]

POST_GLOSS = [
    (r"(?i)\bmesos?\b", "金币"),
    (r"\bFM\b", "自由市场"),
    (r"(?i)\bscrolls?\b", "卷轴"),
    (r"(?i)\bequips?\b", "装备"),
]

translator = GoogleTranslator(source="en", target="zh-CN")


def load_cache() -> dict:
    if CACHE.exists():
        return json.loads(CACHE.read_text(encoding="utf-8"))
    return {}


def save_cache(cache: dict) -> None:
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")


def protect(text: str) -> tuple[str, list[str]]:
    toks: list[str] = []

    def repl(m: re.Match) -> str:
        toks.append(m.group(0))
        return f"XTOK{len(toks)-1}X"

    return PLACEHOLDER_RE.sub(repl, text), toks


def restore(text: str, toks: list[str]) -> str:
    for i, tok in enumerate(toks):
        for variant in (
            f"XTOK{i}X",
            f"xtok{i}x",
            f"Xtok{i}X",
            f"XTOK{i}x",
            f"xtok{i}X",
            f"TOK{i}",
            f"tok{i}",
        ):
            if variant in text:
                text = text.replace(variant, tok)
                break
        else:
            text = re.sub(rf"[Xx]?TOK{i}[Xx]?", tok, text, count=1, flags=re.I)
    # Ensure all tokens present
    for tok in toks:
        if tok not in text:
            # try insert at end if lost - better than dropping
            text = text + tok
    return text


def pre_gloss(text: str) -> str:
    for pat, rep in PRE_GLOSS:
        text = re.sub(pat, rep, text)
    return text


def translate_one(en: str, cache: dict) -> str:
    if en in cache:
        return cache[en]
    if en.strip() in KEEP_AS_IS or not en.strip():
        cache[en] = en
        return en
    if re.fullmatch(r"[\W\d_]+", en, flags=re.UNICODE):
        cache[en] = en
        return en

    protected, toks = protect(en)
    glossed = pre_gloss(protected)
    try:
        zh = translator.translate(glossed)
        time.sleep(0.025)
    except Exception as e:
        print(f"  warn: {e!s:.70} | {en[:50]!r}", file=sys.stderr)
        zh = glossed

    zh = restore(zh or glossed, toks)
    for pat, rep in POST_GLOSS:
        zh = re.sub(pat, rep, zh)
    zh = zh.replace("您", "你").strip()
    cache[en] = zh
    return zh


def translate_file(name: str, cache: dict) -> None:
    src = SRC / name
    out = []
    for line in src.read_text(encoding="utf-8").splitlines(keepends=True):
        raw = line.rstrip("\n")
        nl = "\n" if line.endswith("\n") else ""
        m = DASH_QUOTE.match(raw)
        if m:
            zh = translate_one(m.group(2), cache)
            zh_esc = zh.replace("\\", "\\\\").replace('"', '\\"')
            out.append(f'{m.group(1)}"{zh_esc}"{m.group(3)}{nl}')
            continue
        m = LINE_EMOTE.match(raw)
        if m:
            zh = translate_one(m.group(2), cache)
            zh_esc = zh.replace("\\", "\\\\").replace('"', '\\"')
            out.append(f'{m.group(1)}"{zh_esc}"{m.group(3)}{nl}')
            continue
        out.append(line)
    DST.mkdir(parents=True, exist_ok=True)
    (DST / name).write_text("".join(out), encoding="utf-8")
    print(f"Wrote {name}")


def main() -> None:
    cache = load_cache()
    # collect unique
    strings = []
    seen = set()
    for name in FILES:
        for line in (SRC / name).read_text(encoding="utf-8").splitlines():
            m = DASH_QUOTE.match(line) or LINE_EMOTE.match(line)
            if m and m.group(2) not in seen:
                seen.add(m.group(2))
                strings.append(m.group(2))
    todo = [s for s in strings if s not in cache]
    print(f"unique={len(strings)} cached={len(strings)-len(todo)} todo={len(todo)}")
    for i, s in enumerate(todo):
        translate_one(s, cache)
        if (i + 1) % 40 == 0:
            print(f"  {i+1}/{len(todo)}")
            save_cache(cache)
    save_cache(cache)
    for name in FILES:
        translate_file(name, cache)
    print("done")


if __name__ == "__main__":
    main()
