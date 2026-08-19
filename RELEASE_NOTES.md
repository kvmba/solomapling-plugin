# Release notes — SoloMapling Plugin (SPI)

## 0.4.0-SNAPSHOT

### Features

- **SocialBot Hybrid LLM chat:** optional DeepSeek integration for free-form player dialogue during active SocialBot sessions (`solomapling.llm.*`). Menu options, party recruit, and goodbye remain YAML/rule-driven.
- Uses [simple-openai](https://github.com/sashirestela/simple-openai) (`SimpleOpenAIDeepseek`); client + OkHttp/Jackson shaded into the plugin jar.
- `DialogueContextResolver.buildSnapshot()` exports live game context into LLM system prompts.

### Config (`application.yml`)

```yaml
solomapling:
  llm:
    enabled: false
    api-key: ${DEEPSEEK_API_KEY:}
    model: deepseek-v4-flash
    max-tokens: 80
    timeout-ms: 10000
    history-turns: 8
    fallback-to-yaml: true
```

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
