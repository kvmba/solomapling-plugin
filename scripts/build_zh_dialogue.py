#!/usr/bin/env python3
"""Build BotDialoguePack-zh-CN from English BotDialoguePack YAML files.

Preserves YAML structure, keys, emote/wait numbers, and {TOKEN} placeholders.
Translates dialogue strings to Simplified Chinese.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("PyYAML required: pip install pyyaml", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack"
DST = ROOT / "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack-zh-CN"

TOKEN_RE = re.compile(r"\{[A-Z0-9_]+\}")

# Manual high-quality overrides for common gaming phrases / tokens context
PHRASE_MAP = {
    "brb": "马上回",
    "afk": "挂机",
    "lol": "哈哈",
    "ty": "谢啦",
    "tyvm": "太感谢了",
    "np": "没事",
    "gg": "打得不错",
    "fm": "自由市场",
    "pq": "组队任务",
    "ks": "抢怪",
    "rng": "运气",
    "mesos": "金币",
    "pots": "药水",
    "scroll": "卷轴",
    "scrolls": "卷轴",
    "tbh": "说实话",
    "ngl": "不骗你",
    "irl": "现实里",
    "xD": "XD",
    "^^": "^^",
    "ugh": "唉",
    "hehe": "嘿嘿",
    "cya": "回见",
    "grats": "恭喜",
    "npc": "NPC",
    "exp": "经验",
    "hp": "HP",
    "mp": "MP",
}

# Core translation table for small/common lines (fallback uses rule-based below)
LINE_TRANSLATIONS: dict[str, str] = {}


def protect_tokens(text: str) -> tuple[str, list[str]]:
    tokens: list[str] = []

    def repl(m: re.Match[str]) -> str:
        tokens.append(m.group(0))
        return f"__TOK{len(tokens)-1}__"

    protected = TOKEN_RE.sub(repl, text)
    return protected, tokens


def restore_tokens(text: str, tokens: list[str]) -> str:
    for i, tok in enumerate(tokens):
        text = text.replace(f"__TOK{i}__", tok)
    return text


def translate_line(en: str) -> str:
    if en in LINE_TRANSLATIONS:
        return LINE_TRANSLATIONS[en]

    protected, tokens = protect_tokens(en)

    # Already Chinese or mostly symbols
    if re.search(r"[\u4e00-\u9fff]", protected):
        return en

    # Keep very short emotive tokens
    if protected.strip() in {"...", "zzz", "...", "ok", "hm", "hmm", "nah", "yeah", "yep", "nope"}:
        mapping = {
            "...": "...",
            "zzz": "zzz",
            "ok": "好",
            "hm": "嗯",
            "hmm": "嗯…",
            "nah": "不了",
            "yeah": "是啊",
            "yep": "对",
            "nope": "没有",
        }
        return restore_tokens(mapping.get(protected.strip(), protected), tokens)

    # Rule-based chunk translation for MapleStory casual chat style
    zh = protected
    replacements = [
        (r"\byou\b", "你"),
        (r"\byour\b", "你的"),
        (r"\bmy\b", "我的"),
        (r"\bme\b", "我"),
        (r"\bi\b", "我"),
        (r"\bim\b", "我"),
        (r"\bwe\b", "我们"),
        (r"\bour\b", "我们的"),
        (r"\bthey\b", "他们"),
        (r"\bthem\b", "他们"),
        (r"\bthe\b", ""),
        (r"\ba\b", ""),
        (r"\ban\b", ""),
        (r"\bis\b", "是"),
        (r"\bare\b", "是"),
        (r"\bwas\b", "刚才"),
        (r"\bwere\b", "是"),
        (r"\bhave\b", "有"),
        (r"\bhas\b", "有"),
        (r"\bdid\b", ""),
        (r"\bdo\b", ""),
        (r"\bdont\b", "别"),
        (r"\bdon't\b", "别"),
        (r"\bcant\b", "不能"),
        (r"\bcan't\b", "不能"),
        (r"\bwont\b", "不会"),
        (r"\bwon't\b", "不会"),
        (r"\bjust\b", "就"),
        (r"\bstill\b", "还"),
        (r"\bhere\b", "这里"),
        (r"\bthere\b", "那边"),
        (r"\btoday\b", "今天"),
        (r"\btonight\b", "今晚"),
        (r"\btomorrow\b", "明天"),
        (r"\byesterday\b", "昨天"),
        (r"\bnow\b", "现在"),
        (r"\blater\b", "待会"),
        (r"\bplease\b", "请"),
        (r"\bthanks\b", "谢谢"),
        (r"\bthank you\b", "谢谢你"),
        (r"\bsorry\b", "抱歉"),
        (r"\bwait\b", "等等"),
        (r"\bstop\b", "停"),
        (r"\bnice\b", "不错"),
        (r"\bcool\b", "酷"),
        (r"\bgood\b", "好"),
        (r"\bgreat\b", "很棒"),
        (r"\bbad\b", "糟"),
        (r"\blucky\b", "运气真好"),
        (r"\bgrind(?:ing)?\b", "刷怪"),
        (r"\blevel(?:s|ing)?\b", "等级"),
        (r"\bmap\b", "地图"),
        (r"\bmob(?:s)?\b", "怪"),
        (r"\bboss(?:es)?\b", "Boss"),
        (r"\bdrop(?:s|ped|ping)?\b", "掉落"),
        (r"\bshop\b", "店"),
        (r"\bselling\b", "卖"),
        (r"\bbuying\b", "买"),
        (r"\bparty\b", "队伍"),
        (r"\bguild\b", "公会"),
        (r"\bquest\b", "任务"),
        (r"\bjob advance\b", "转职"),
        (r"\bgear\b", "装备"),
        (r"\bequip(?:ment)?\b", "装备"),
        (r"\bweapon\b", "武器"),
        (r"\bcape\b", "披风"),
        (r"\bhat\b", "帽子"),
        (r"\bpet\b", "宠物"),
        (r"\bchair\b", "椅子"),
        (r"\bcash shop\b", "点券商城"),
        (r"\bdouble exp\b", "双倍经验"),
        (r"\bmaintenance\b", "维护"),
        (r"\blag\b", "延迟"),
        (r"\bevent\b", "活动"),
        (r"\bserver\b", "服务器"),
        (r"\bchannel\b", "频道"),
        (r"\btown\b", "城镇"),
        (r"\bspot\b", "点位"),
        (r"\btrain(?:ing)?\b", "练级"),
        (r"\bhenesys\b", "射手村"),
        (r"\borbis\b", "天空之城"),
        (r"\bfm\b", "自由市场"),
        (r"\bpq\b", "组队任务"),
    ]
    for pat, rep in replacements:
        zh = re.sub(pat, rep, zh, flags=re.IGNORECASE)

    for eng, chi in PHRASE_MAP.items():
        zh = re.sub(rf"\b{re.escape(eng)}\b", chi, zh, flags=re.IGNORECASE)

    zh = re.sub(r"\s+", " ", zh).strip()
    zh = re.sub(r"\s+([，。！？、])", r"\1", zh)

    if not re.search(r"[\u4e00-\u9fff]", zh):
        # Couldn't translate meaningfully — keep original lower-case style but mark
        return en

    return restore_tokens(zh, tokens)


def translate_comment(line: str) -> str:
    if not line.lstrip().startswith("#"):
        return line
    body = line.lstrip("#").strip()
    if not body or re.search(r"[\u4e00-\u9fff]", body):
        return line
    # Keep technical tokens in comments
    mapping = {
        "Generic town small-talk": "城镇闲聊（机器人之间）",
        "TrainingBot dialogue": "TrainingBot 对话 — 练级机器人",
        "Nodes delivered via the context path": "通过上下文路径触发的节点",
        "Per-job signature overlays": "各职业专属补充台词",
        "loaded by TownChatterLines": "由 TownChatterLines 加载",
        "exchanges": "对话轮次",
    }
    for en, zh in mapping.items():
        if en.lower() in body.lower():
            return "# " + body.replace(en, zh)
    return line


def translate_value(value):
    if isinstance(value, str):
        return translate_line(value)
    if isinstance(value, list):
        return [translate_value(v) for v in value]
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            if k in {"line", "text"} and isinstance(v, str):
                out[k] = translate_line(v)
            else:
                out[k] = translate_value(v)
        return out
    return value


def process_file(src_file: Path, dst_file: Path) -> None:
    with src_file.open(encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if data is None:
        data = {}
    translated = translate_value(data)
    dst_file.parent.mkdir(parents=True, exist_ok=True)
    with dst_file.open("w", encoding="utf-8") as f:
        yaml.dump(translated, f, allow_unicode=True, default_flow_style=False, sort_keys=False, width=120)
    print(f"Wrote {dst_file.relative_to(ROOT)}")


def main() -> None:
    if not SRC.is_dir():
        print(f"Source not found: {SRC}", file=sys.stderr)
        sys.exit(1)

    for src in sorted(SRC.glob("*.yaml")):
        if src.name == "DropGameLootPool.yaml":
            continue  # item ids only, not dialogue
        process_file(src, DST / src.name)

    print(f"Done. {len(list(DST.glob('*.yaml')))} files in {DST.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
