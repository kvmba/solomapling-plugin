#!/usr/bin/env python3
"""Build BotDialoguePack-zh-CN with Google Translate, preserving {TOKEN}s and YAML structure."""
from __future__ import annotations

import re
import sys
import time
from pathlib import Path

import yaml
from deep_translator import GoogleTranslator

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack"
DST = ROOT / "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack-zh-CN"

TOKEN_RE = re.compile(r"\{[A-Z0-9_]+\}")
translator = GoogleTranslator(source="en", target="zh-CN")
_cache: dict[str, str] = {}


def protect_tokens(text: str) -> tuple[str, list[str]]:
    tokens: list[str] = []

    def repl(m: re.Match[str]) -> str:
        tokens.append(m.group(0))
        return f"__TOK{len(tokens)-1}__"

    return TOKEN_RE.sub(repl, text), tokens


def restore_tokens(text: str, tokens: list[str]) -> str:
    for i, tok in enumerate(tokens):
        text = text.replace(f"__TOK{i}__", tok)
    return text


def translate_text(en: str) -> str:
    if not en or not en.strip():
        return en
    if en in _cache:
        return _cache[en]
    if re.fullmatch(r"[\W\d_]+", en):
        _cache[en] = en
        return en
    protected, tokens = protect_tokens(en)
    try:
        zh = translator.translate(protected)
        time.sleep(0.05)
    except Exception as e:
        print(f"  warn: translate failed ({e!s:.60}): {en[:50]!r}", file=sys.stderr)
        zh = protected
    result = restore_tokens(zh, tokens)
    _cache[en] = result
    return result


def translate_value(value):
    if isinstance(value, str):
        return translate_text(value)
    if isinstance(value, list):
        return [translate_value(v) for v in value]
    if isinstance(value, dict):
        return {k: (translate_text(v) if k in {"line", "text"} and isinstance(v, str) else translate_value(v))
                for k, v in value.items()}
    return value


def process_file(src_file: Path, dst_file: Path) -> None:
    print(f"Translating {src_file.name}...")
    with src_file.open(encoding="utf-8") as f:
        data = yaml.safe_load(f)
    translated = translate_value(data or {})
    dst_file.parent.mkdir(parents=True, exist_ok=True)
    with dst_file.open("w", encoding="utf-8") as f:
        yaml.dump(translated, f, allow_unicode=True, default_flow_style=False, sort_keys=False, width=120)


def main() -> None:
    files = sorted(p for p in SRC.glob("*.yaml") if p.name != "DropGameLootPool.yaml")
    for src in files:
        process_file(src, DST / src.name)
    print(f"Done: {len(files)} dialogue files -> {DST}")


if __name__ == "__main__":
    main()
