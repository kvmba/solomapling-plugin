# FMNameDesc-zh-CN

Simplified Chinese word lists for free-market shop names / descriptions, resolved by
`FMShopDescGen` via `LocalizedResources` (`solomapling.language: zh-CN`).

Only files that need translating live here; anything missing falls back to the English
`FMNameDesc/` list. Deliberately not translated:

| File | Why |
|------|-----|
| `emojiFaces.txt` | Language-neutral kaomoji |

## `randomRealMaplestoryIGNs.txt`

Bot character names, localized. This file used to be listed as "deliberately not translated —
v83 character names are latin-only"; that restriction does not hold for this server. The code
path already accepts CJK:

- `CompanionProvisioningInput` validates names against `[a-zA-Z0-9\u4e00-\u9fa5]{2,12}`, and
  `CompanionProvisioningInputTest` asserts CJK names such as `北斗伙伴` pass.
- `FMShopDescGen.displayWidth()` already counts CJK as two cells, so CJK shop titles are laid
  out correctly.
- `PluginResources.openReader()` decodes as UTF-8.

Entries are Chinese MapleStory-style names: 怀旧 two-word names, in-game terms, food and drink,
self-deprecating player humour, and CN/EN mixes. Roughly 10% carry a symbol or kaomoji
(`♪ ★ ♡`, `(￣▽￣)`, `^_^`) because that is how real players decorate names — the symbols are
limited to ones the v83 client font actually covers, so nothing renders as a tofu box.

**Constraints** — `FMShopDescGen.loadAndShuffleNames()` silently drops any line longer than 12
characters, and shop titles are laid out by display width:

| Rule | Value |
|------|-------|
| Display width (CJK = 2 cells, ASCII = 1) | 8–12 |
| Java `String.length()` | ≤ 12, else the name never gets drawn |
| No spaces or tabs | names are matched as typed |

**Characters** — a name is drawn with the game's own font, so anything that font lacks renders as
`?` or a tofu box. `BotIgnWordListTest` enforces a whitelist; keep to it:

- CJK ideographs, ASCII letters and digits — always fine.
- CJK/kana decoration players used in 非主流 names: `丶 灬 丨 の ゛ ゜ 〜 ～ °`
- The classic note/star/heart/flower set: `♪ ★ ☆ ♡ ♥ ✿ ❀ ❁ ✾`
- Plain geometric fills: `◆ ◇ ○ ●`

Nothing else. In particular **no ASCII punctuation** — that rules out the ASCII faces (`^_^`,
`T_T`, `-_-`, `=.=`) as *names*, since they are built from `. ^ * = _ -`. And no exotic symbols:
`ღ ✨ ❥ ❦ ❧ ❖ ❤ ☀ ☁ ☂ ☃ ◈ ◉ ❃ ❄ ❋ ❦ ☘ ☙ ☾` were all tried and removed — they came out as
`?` in game. When adding a symbol, check it against the fonts an old client uses (宋体 / MS
Gothic), not against a modern system font. An unusual glyph renders as garbage; a plain name is
always fine.

Keep entries short (roughly 4–10 characters). Two entries can be concatenated into one shop
title, and `FMClans.txt` names additionally get `[X]` emblem + ASCII borders, which throws
above 17 characters.
