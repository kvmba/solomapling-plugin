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

**Characters** — a name may contain only:

- `A-Z`, `a-z`, `0-9`
- simplified-Chinese ideographs (`U+4E00`-`U+9FA5`)

Nothing else. Every other character is rejected, because a name is drawn with the game's own
font and anything that font lacks renders as `?` or a tofu box. The rule is a whitelist, so it
rejects by default rather than by enumeration — kana (`の パ`), full-width forms (`２`), CJK
punctuation, symbols (`♪ ★ ♡ 〜 ° 丶 灬 丨`) and every invisible or control character (zero-width
space, BOM, `0x00`-`0x1F`, `0x7F`, spaces) all fail on the same rule.

Two things are easy to get wrong and are worth naming:

- **Stroke characters are not words.** `丶 丨 灬 丿 乀 亅 彡 乂` sit *inside* the CJK block, so a
  plain "is it a Chinese character?" test lets them through. Players use them as name decoration,
  and they render inconsistently, so they are subtracted explicitly in
  `CompanionProvisioningInput.STROKE_DECORATION` and mirrored in `BotIgnWordListTest`.
- **`灬` is `U+706C`, not `U+7070`.** `U+7070` is `灰`, an ordinary character that appears in
  legitimate names such as `骨灰捡漏`. Confusing the two silently deletes good names.

Both lists must stay in step: if the pool allows a character the provisioner rejects, a generated
name can fail validation at runtime.

Keep entries short (roughly 4–10 characters). Two entries can be concatenated into one shop
title, and `FMClans.txt` names additionally get `[X]` emblem + ASCII borders, which throws
above 17 characters.
