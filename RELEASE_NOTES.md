# Release notes — SoloMapling Plugin (SPI)

## 0.4.0-SNAPSHOT

### Fixes

- **Training bots no longer abandon a continent that still has mobs for them.** Continental migration (a bot that "outgrows" its landmass moving to one whose mobs still bite) tested only whether a harder continent's level bar was cleared, and treated any YES as "this one is finished". That is a different question, and the wrong answer drained the low-bar continents: 地球防御本部 (EDF, map `221000000`, inside Ludibrium — bar 30) holds fields for its 40-54 cohort, but a 45-level bot there cleared every harder bar, so it was ordered off on its first town visit. Since a migration only ever relocates to another continent's *town* and EDF is not one, the bot could never return — EDF bled out, with no refill (bots are spawned once, at startup; the real curve depends on how fast the crossings resolve). The climb is now content-aware: `TrainingMapChooser.hasInBandTarget` asks whether a level-appropriate hunting ground is still reachable (the same two-sided band and level-scaled radius DECIDE uses, plus the deep-hub downward floor, and it band-checks the result because the finder's non-empty fallback would otherwise always answer "yes"), and `forcedCrossing` — one shared statement of the rule, used by both the training bot and the companion — forces a move only for a genuine outgrowing or the beginner island's one-way boat. A free mover's move stays optional (the existing 25% dice). Above the free-move level, or when nothing is left to fight, migration behaves as before; the island's `level 8` boat is deliberately never gated (it is an exit, not a continent to outgrow, and the island holds level-appropriate mobs).
- **Bot names no longer contradict their job.** A bot's IGN and its class were two independent rolls, so a third of the zh-CN names — which lean on role words like 圣骑士 / 大主教 / 魔法师 / 弓箭手 — landed on a bot of a different class (`圣骑士肝帝` walking around as a thief). Names are now drawn to match the job they will get:
  - `BotNamePool` classifies each name by the v83 job category its role word asserts (warrior … pirate), with the flavour words that are not v83 classes (龙神 / 恶魔 / 天使) kept neutral and the town name 勇士部落 subtracted from the warrior word 勇士.
  - Both pools are covered. The English list is the default language; its words match on ASCII token boundaries (`sin`⊄`Since2005`, `mage`⊄`image`), the Chinese list on substrings (no word boundaries in CJK), so the default English world filters names too.
  - `FMShopDescGen.getRandomCharacterIGN(int category)` returns the first name that is neutral or asserts the bot's own category; the plain `getRandomCharacterIGN()` still works for an unknown class.
  - The category is resolved once in `BotGeneration.createBotOn` (from the forced job, else the rolled/known base class) and handed to both the name draw and the decoration, so the name is fixed before the job is rolled and the two can never disagree. The no-class spawn path previously let `BotDecorate` roll its own base class; it now reuses that single roll (same default 10..80 band).
  - Companions are named the same way: intake draws the persona seed first, derives the class from it, and passes the same seed to provisioning, so a companion's IGN matches the career it trains as.
- **Pirate (base class 5) is now a first-class bot job.** Ambient / training bots previously never rolled a pirate (`rollBaseClass` excluded class 5), and a pirate's `getJobStyle()` resolves to `BRAWLER`/`GUNSLINGER` (never `PIRATE`), so any style-keyed lookup silently degraded it to "no job" (reqJob 0). Fixed end to end:
  - `rollBaseClass` now yields pirates at ~10% (after thief > mage > warrior > bowman); `selectJobForClass` covers all four tiers (500 / 510,520 / 511,521 / 512,522).
  - `getReqJobViaJobStyle` maps `BRAWLER`/`GUNSLINGER` → `PIRATE` (reqJob 16), so a pirate can wear its knuckle/gun and pirate armour.
  - Gear selection equips knuckle (brawler) or gun (gunslinger), branched on the job id (the decorator runs before STR/DEX are aligned).
  - Bot combat registers both pirate lines (Double Uppercut / Energy Blast / Barrage; Invisible Shot / Burst Fire / Rapid Fire / Battleship Cannon) plus a 1st-job weapon seed (knuckle melee / gun ranged, like the 1st-job rogue).
  - Bot buffs cover the pirate boosters (2nd), Transformation / Octopus (3rd) and Maple Warrior / Speed Infusion (4th).
  - `!bot spawn ... pirate` and `!bot trainhere <brawler|...|corsair>` are accepted.
  - Free-Market pirate shops now stock pirate gear instead of falling back to classless common items.
  - Pirate attack skills carry their real body-action poses (straight / somersault / doubleupper / eburster / triplefire / cannon / ...), read from the v83 client's own action table.
- **Bot fame was the bot's internal character id.** `BotGeneration` set every artificial player's 人气度 to its own cid (`setFame(botId)`, a debug leftover from the upstream port), so all bots showed a five-digit reputation starting at 20000. Fame is now rolled from level and tier instead (see `BotFame`):
  - low-level bots land around **-10..30** (beginners sit in the low single digits)
  - the ceiling grows with level but never exceeds **300**
  - **~15% of the population carries a negative reputation**
- Fame is generated after the bot's level/tier are final, and re-rolled where a level is overridden post-decoration (OPQ lobby bots, `spawnAttackTestBots`), so reputation always matches the level shown.
- `getConsoleBot` bypasses `BotDecorate`, so it now rolls its own fame instead of inheriting the template character's value.
- New GM commands: `!bot fame <cid>` (inspect), `!bot rerollfame <cid>`, `!bot setfame <cid> <-50..300>`.

### Features

- **Bot detail window (角色详情窗口) is populated.** Opening a bot's character-info window (CUIUserInfo) used to show whatever the `fmbot` template character held — the same empty/duplicated monster book, medal book and wishlist for every bot, because bots inherit the template's `Character` state. The window's packet (`charInfo`) carries exactly three data sets, and all three are now preset per bot at spawn (`soloMapling.ArtificialPlayer.BotDetailSystem`):
  - **怪物卡 (monster book):** book level + normal/special/total counts, scaled by level and replayed through the host's own `calculateLevel()` curve. Only the three private counters are written (reflection; `addCard` would broadcast and dereference a null headless player); the per-card map is not touched (the window never reads it) and the cover stays 0 (an unbacked cover id would NPE `getCardMobId` for every viewer).
  - **勋章收藏 (medal collection):** a deterministic, level-scaled subset of the 28 medal-granting `29xxx` quests marked completed. Eligibility reuses `BotMedalPool.eligibleFor`, so a job-exclusive (29300–29304, reqLevel 180) or too-high-level medal quest can never appear; the worn medal's own quest is always included. Written via `getQuestNAdd(...).setStatus(COMPLETED)` — not `updateQuestStatus`, which would also award fame and emit packets.
  - **心愿单 (wishlist / 想要购买的道具):** a level-scaled subset of the host's on-sale, non-package cash items (≤ the client's 10-entry cap), from a pool cached against the live catalog's identity so a spawn never re-scans ~8k items.
  - All values are derived deterministically from the character id, so a persistent companion shows the same content across restarts; ambient bots are never persisted, so this is pure in-memory state for them. Injected at the same point as `BotMedal` (`BotDecorate.setBotVariables` ×3 paths, `loadPersistentBot`) plus the 5 level/job overrides. The monster-book aggregate is written through a new host API, `MonsterBook.setCardCounts` (the only GMS083 change).
  - New GM commands: `!bot detail <cid>` (inspect) and `!bot rerolldetail <cid>`.
- **Bot titles (称号/medals):** decorated bots roll, level-scaled, for a 称号 and wear it at spawn — no title below level 10, rising to a **50%** ceiling at level 130, with higher-level bots biased toward the fancier (reqLevel > 0) titles. A medal is the v83 `Me`-slot equip (`114xxxx`, slot `-49`), so wearing one is pure inventory state — the host's `addCharInfo` carries it in the look packet and the medal's own stat bonuses apply through the normal equip-stats recalculation. Bot play does not read those stats, so the effect is cosmetic.
  - The legal pool is scanned once from the client `Character.wz` (the host `EquipType` has no MEDAL value, so `EquipMetadataCache` skips medals) and keeps only real, localized (han-character) titles, excluding 人气 / 宠物-intimacy titles.
  - A bot only ever wears a medal it can legally wear (`reqLevel` / `reqJob` / `reqSTR…` / `reqPOP` all satisfied), and the title is re-rolled wherever the level or job is overridden (OPQ lobby, attack-test spawn, `!bot setlevel` / `setjob`).
  - New GM commands: `!bot medal <cid>` (inspect), `!bot givemedal <cid> [itemid]`, `!bot removemedal <cid>`, `!bot rerollmedal <cid>`.
- **SocialBot Hybrid LLM chat:** optional DeepSeek integration for free-form player dialogue during active SocialBot sessions (`solomapling.llm.*`). Menu options, party recruit, and goodbye remain YAML/rule-driven.
- Uses [simple-openai](https://github.com/sashirestela/simple-openai) (`SimpleOpenAIDeepseek`); client + OkHttp/Jackson shaded into the plugin jar.
- `DialogueContextResolver.buildSnapshot()` exports live game context into LLM system prompts.
- **Brawler pirate bots now run 能量获得 (Energy Charge, 5110001) for real.** The skill is entirely client-driven in the host — the bar is charged by the CLOSE_RANGE_ATTACK handler and the retaliation arrives as a `TOUCH_MONSTER_ATTACK` — so a headless bot could never trigger either half. Both are now synthesized in the plugin, reusing the host's own values and packets so onlookers cannot tell (`soloMapling.ArtificialPlayer.BotAttackSystem.BotEnergyCharge`):
  - Each landed swing adds 102 per mob hit, exactly as `Character.handleEnergyChargeGain` does; the bar arms at 10000 and flips to the host's 15000 full value in the same step, so a bot never rests in an armed-but-idle state.
  - Reaching full broadcasts the skill's own charge flash plus the foreign `ENERGY_CHARGE` stat (the bar value, clamped to the client's 10000 gauge ceiling, is what draws the gauge over the bot) and arms a self-expiry at the skill's WZ duration, after which the bar empties and the gauge is cancelled from every viewer — the host's own timer does the same two things.
  - While full, a mob touching the bot takes a real hit (rolled from the bot's tier/level). The touch is its own attack in the v83 protocol — the client sends it as `TOUCH_MONSTER_ATTACK` (`0x2F`, "能量攻击") and the server answers with `ENERGY_ATTACK` (`0xBD`) — so the strike is broadcast on that opcode with the brawler's own punch pose and its damage line, then applied through the same kill/EXP/loot path as every other bot hit. A viewer plays the real strike instead of only watching the mob's HP drop. Cadence is the charged skill's own ~1s attack interval, independent of the bot's hurt i-frames, exactly like a real client.
  - Only the brawler line holds a charge (Marauder 511 / Buccaneer 512 — the jobs the host's own gate covers); the gun line and Thunder Breakers are untouched.
  - Runs entirely inside the existing LOD gates: the charge rides the attack path, and the retaliation reuses the contact tick's own nearby-mob scan (an extra scan only when a charged brawler is actually due to retaliate).
  - New GM commands: `!bot energy <cid>` (inspect the bar), `!bot energycharge <cid>` (fill it now), `!bot energyreset <cid>` (empty it).
  - One new host API, `PacketCreator.energyAttack` (the only GMS083 change, `33ad70ea0`): the `ENERGY_ATTACK` opcode was declared but never built anywhere in the engine, so no touch-triggered attack could be echoed back as a real strike — it had to fall back to a bare damage number. It is the same `addAttackBody` the other three attacks use, behind that header (matching the reference v83 server, where all four share one body). Requires a host build at or after that commit.
  - The charged value lives on the host's own `Character.energyBar`, so the char-info packet renders the charged look with no extra work. The skill is granted before the bar can reach full, at the level the bot's own character level would plausibly hold — 1 on the day of the level-70 third job, filling in at the server's own SP rate (3 a level, 1 a skill level) so a 511 bot reads as maxed from ~83 up. This is not cosmetic-only: Energy Charge's charged state lasts 31s at level 1 and 50s at level 40, so a fixed max grant would have given freshly-advanced bots a duration they never earned. Level below 70 grants nothing and charges nothing. The grant itself is required: the host's `reapplyLocalStats` reads this skill's effect whenever the bar is 15000 and would index `effects[-1]` for an unlearned one, so charging is skipped outright if the skill cannot be resolved.

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
