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

Entries are Chinese MapleStory-style names only — no symbols or kaomoji (an earlier pass carried
`♪ ★ ♡`, `(￣▽￣)`, `^_^`; those are gone, see **Characters** below). The list is ~7,900 names:
the pool's own two shapes crossed, plus curated standalone names.

- **`状态/动作` × `职业/怪物`** — `摸鱼企鹅王`, `熬夜战神`, and the reverse `企鹅王摸鱼`, `战神熬夜`.
  States come from the vocabulary players actually use (摸鱼 摆烂 躺平 养老 挂机 搬砖 打宝 熬夜 失眠
  回坑 带队 捡漏 …); roles are the MS classes and monsters the pool already used (企鹅王 冰龙王 大主教
  圣骑士 恶魔猎 魔法师 弓箭手 机械师 绿水灵 木妖精 橡皮怪 战神 龙神 …) plus the meme identities
  (摸鱼王 摆烂王 肝帝 卷王 打工人 老司机 …).
- **`形容词` × `职业/怪物`** — `冷酷法师`, `迷糊小雪人`, `倔强狂徒` style personality front-ends.
- **独立名** — 怀旧 (`点卡时代`, `网吧通宵`, `十年老兵`), 热梗 (`芜湖起飞`, `绝绝子`, `格局打开`),
  食物 (`螺蛳粉`, `珍珠奶茶`, `杨枝甘露`), 萌宠 (`橘猫`, `柯基`, `柴犬`) and 文艺 (`深夜食堂`,
  `岁月静好`, `云淡风轻`).

The `AFK`-prefixed family (`AFK一路向北` …) was removed: the prefix reads as a status, not as part
of a name, and it made ~1% of the pool look like a bot farm. Width distribution lands on 8 and 10
(CJK counts as two cells), with a thinner tail at 9/11/12; nothing exceeds 12.

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
punctuation, symbols (`♪ ★ ♡ 〜 °`) and every invisible or control character (zero-width
space, BOM, `0x00`-`0x1F`, `0x7F`, spaces) all fail on the same rule. **No decoration symbols
and no kaomoji are allowed at all** — only `A-Z a-z 0-9` and simplified Chinese.

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
