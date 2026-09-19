# Release notes — SoloMapling Plugin (SPI)

## 0.4.0-SNAPSHOT

### Fixes

- **Pirate (base class 5) is now a first-class bot job.** Ambient / training bots previously never rolled a pirate (`rollBaseClass` excluded class 5), and a pirate's `getJobStyle()` resolves to `BRAWLER`/`GUNSLINGER` (never `PIRATE`), so any style-keyed lookup silently degraded it to "no job" (reqJob 0). Fixed end to end:
  - `rollBaseClass` now yields pirates at ~6% (rarest, after thief > mage > warrior > bowman); `selectJobForClass` covers all four tiers (500 / 510,520 / 511,521 / 512,522).
  - `getReqJobViaJobStyle` maps `BRAWLER`/`GUNSLINGER` → `PIRATE` (reqJob 16), so a pirate can wear its knuckle/gun and pirate armour.
  - Gear selection equips knuckle (brawler) or gun (gunslinger), branched on the job id (the decorator runs before STR/DEX are aligned).
  - Bot combat registers both pirate lines (Double Uppercut / Energy Blast / Barrage; Invisible Shot / Burst Fire / Rapid Fire / Battleship Cannon) plus a 1st-job weapon seed (knuckle melee / gun ranged, like the 1st-job rogue).
  - Bot buffs cover the pirate boosters (2nd), Transformation / Octopus (3rd) and Maple Warrior / Speed Infusion (4th).
  - `!bot spawn ... pirate` and `!bot trainhere <brawler|...|corsair>` are accepted.
  - Free-Market pirate shops now stock pirate gear instead of falling back to classless common items.
  - Note: pirate attack skills render their weapon's default swing/shot rather than a bespoke pose — the client action ids for pirate skills are not in the action enum the plugin draws its overrides from.
- **Bot fame was the bot's internal character id.** `BotGeneration` set every artificial player's 人气度 to its own cid (`setFame(botId)`, a debug leftover from the upstream port), so all bots showed a five-digit reputation starting at 20000. Fame is now rolled from level and tier instead (see `BotFame`):
  - low-level bots land around **-10..30** (beginners sit in the low single digits)
  - the ceiling grows with level but never exceeds **300**
  - **~15% of the population carries a negative reputation**
- Fame is generated after the bot's level/tier are final, and re-rolled where a level is overridden post-decoration (OPQ lobby bots, `spawnAttackTestBots`), so reputation always matches the level shown.
- `getConsoleBot` bypasses `BotDecorate`, so it now rolls its own fame instead of inheriting the template character's value.
- New GM commands: `!bot fame <cid>` (inspect), `!bot rerollfame <cid>`, `!bot setfame <cid> <-50..300>`.

### Features

- **Bot titles (称号/medals):** decorated bots roll, level-scaled, for a 称号 and wear it at spawn — no title below level 10, rising to a **50%** ceiling at level 130, with higher-level bots biased toward the fancier (reqLevel > 0) titles. A medal is the v83 `Me`-slot equip (`114xxxx`, slot `-49`), so wearing one is pure inventory state — the host's `addCharInfo` carries it in the look packet and the medal's own stat bonuses apply through the normal equip-stats recalculation. Bot play does not read those stats, so the effect is cosmetic.
  - The legal pool is scanned once from the client `Character.wz` (the host `EquipType` has no MEDAL value, so `EquipMetadataCache` skips medals) and keeps only real, localized (han-character) titles, excluding 人气 / 宠物-intimacy titles.
  - A bot only ever wears a medal it can legally wear (`reqLevel` / `reqJob` / `reqSTR…` / `reqPOP` all satisfied), and the title is re-rolled wherever the level or job is overridden (OPQ lobby, attack-test spawn, `!bot setlevel` / `setjob`).
  - New GM commands: `!bot medal <cid>` (inspect), `!bot givemedal <cid> [itemid]`, `!bot removemedal <cid>`, `!bot rerollmedal <cid>`.
- **SocialBot Hybrid LLM chat:** optional DeepSeek integration for free-form player dialogue during active SocialBot sessions (`solomapling.llm.*`). Menu options, party recruit, and goodbye remain YAML/rule-driven.
- Uses [simple-openai](https://github.com/sashirestela/simple-openai) (`SimpleOpenAIDeepseek`); client + OkHttp/Jackson shaded into the plugin jar.
- `DialogueContextResolver.buildSnapshot()` exports live game context into LLM system prompts.

### Config (`application.yml`)

```yaml
solomapling:
  llm:
    enabled: false
    api-key: ${LLM_API_KEY:}
    base-url: https://api.deepseek.com   # any OpenAI-compatible endpoint
    model: deepseek-v4-flash
    max-tokens: 80
    timeout-ms: 10000
    history-turns: 8
    fallback-to-yaml: true
```

### Localization

`solomapling.language: zh-CN` previously only switched the bot dialogue packs, so free-market bots kept shouting English lines that were hardcoded in Java or read from English-only word lists. Those three paths now follow the language setting:

- Merchant bot shouts moved from Java string arrays into `MerchantBotDialogue.yaml` (`SellAdvertise`, `BuyAdvertise` with `%ITEM%` / `%PRICE%` placeholders, plus `NXAdvertise` and the NX messenger hand-off nodes).
- Shop names / descriptions resolve through the new `FMNameDesc-zh-CN/` word lists, with the English lists as fallback. Bot IGNs and kaomoji stay ASCII on purpose.
- Remaining hardcoded strings moved into YAML / word lists: the AFK price-update whisper and offer hint (`ShopOfferDialogue.yaml`), the shop greeting (`welcomeDesc.txt`), the offerable tag (`offerableDesc.txt`), and the RWT currency tags (`rwtCurrencyDesc.txt`).

Directory-level language resolution is now shared by `LocalizedResources` instead of living in `DialoguePackPaths`, so any resource pack can gain a `-<tag>` sibling. Shop-title padding also measures rendered width rather than `String.length()`, so double-width names aren't padded as if they were ASCII.

## 0.3.1-SNAPSHOT

### Fixes

- Load YAML / text / dialogue resources via `PluginResources` (classpath + optional `data/solomapling/override/`), so jar deployments no longer depend on `src/main/java/...` relative paths.
- Drop bot hair / equip IDs whose client `Character.wz` entries have broken UOLs or are info-only (can crash / disconnect the v83 client when rendered):
  - Hair: `30580`, `30720`, `30870`, `31570`, `31580`, `31590`, `31600`, `34110`
  - Caps: `1002186`, `1002695`, `1002839`
  - Overalls: disable generic pool (`1050018` / `1050100` / `1050127` / `1051017` / `1051098` / `1051140`)
  - Gloves / weapon omit: `1082065`–`1082067`, `1332037`; weapon `1402013` removed from generic pool
- Add WZ / packet audit helpers under `scripts/` (`validate_bot_config_ids.py`, `check_client_wz.ps1`, `wzaudit/WzDeepAudit.java`, …).

## 0.3.0-SNAPSHOT (SPI packaging)

### Fork point

- **Upstream:** [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling) (author: MadaraGameDev / Richy), AGPL-3.0.
- **What this is:** an architectural refactor of that tree into a **host SPI plugin jar**, not a competing “official” SoloMapling distribution.
- **Base idea:** keep the SoloMapling bot framework; stop shipping it as a Cosmic fork.

### Changes vs upstream SoloMapling

| Kept | Changed |
|------|---------|
| `soloMapling/**` framework (bots, dialogue, FM, env waves, …) | Packaged as `ServerExtension` jar for `plugins/` |
| SoloMapling brand in package/artifact ids | Title/docs marked **Plugin / SPI** to avoid “official replacement” confusion |
| AGPL-3.0 + attribution ([NOTICE](NOTICE)) | Host boundary: chat / map / trade / party hooks on the host; plugin bridges subscribe |

### Host wiring (BeiDou)

- Chat: host `CHAT_GENERAL` → plugin `PlayerChatBridge` → primary message queue.
- Party invite: host `PartyInviteEvent` → plugin `BotPartyInviteBridge` → `BotPartyQueue`.
- Trade / artificial-character skips remain on the host (`BotTradeQueue`, `BotHelpers`).

### Credits

Please credit **MadaraGameDev / Richy** and link [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling) in demos, videos, and write-ups. Prefer upstream showcase media when describing SoloMapling’s capabilities.
