# SoloMapling Plugin (SPI)

**Based on [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling)** by MadaraGameDev / Richy — AGPL-3.0.

This repository is a **plugin / SPI packaging** of SoloMapling for host servers such as [BeiDou](https://github.com/BeiDouMS/BeiDou-Server). It is **not** an official SoloMapling release and is **not** a drop-in replacement for the upstream Cosmic-based tree.

| | |
|---|---|
| Upstream | [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling) |
| License | [AGPL-3.0](LICENSE) — see [NOTICE](NOTICE) |
| Artifact | `solo-mapling:solomapling-plugin` (keeps the SoloMapling brand; title marks it as a plugin) |

## Commit history boundary

- **`port: … (packaging only)`** — first commit: upstream [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling) packaged as an SPI plugin. Intentional **no** gameplay/bot-logic changes.
- **All later commits** — may include behavior/logic changes (host bridges, classifiers, trade hooks, docs, etc.).

## Attribution

SoloMapling is an artificial-player framework for MapleStory v83 originally built **on Cosmic**. This port keeps that brand and framework code under AGPL-3.0 and credits the original authors. Please prefer the [upstream showcase / demos](https://github.com/MadaraGameDev/SoloMapling#showcase) when describing the project.

## Architectural change

**Upstream:** SoloMapling ships as a Cosmic fork — framework sources live inside the emulator tree and talk to engine types directly.

**This repo:** the same bot framework is packaged as a **ServerExtension** jar that loads through a thin host SPI (`extension-api` / maple-style `HostRuntime`). BeiDou (and potentially other hosts) implement the runtime; this jar provides only the SoloMapling framework.

```
MadaraGameDev/SoloMapling          this repo (solomapling-plugin)
─────────────────────────         ────────────────────────────────
Cosmic fork + soloMapling/**  →   soloMapling/** as SPI plugin jar
engine + bots in one tree     →   host (BeiDou) + plugins/*.jar
```

## What we kept

- The SoloMapling artificial-player framework (`soloMapling/**`): bot types, dialogue, movement, FM/shops, party/recruit, environment waves, etc.
- Package / artifact naming under the SoloMapling brand (`soloMapling`, `solomapling-plugin`).
- AGPL-3.0 licensing and attribution to MadaraGameDev / Richy.

## What we changed

- **Packaging:** Cosmic-embedded sources → shaded `ServerExtension` plugin jar for `gms-server/plugins/`.
- **Host boundary:** the host exposes artificial-character checks and gameplay events; this plugin registers a classifier and bridges host events into its internal EventBus (see [docs/HOST_BOUNDARY.md](docs/HOST_BOUNDARY.md)).
- **Persistence boundary:** the plugin applies and versions its own `bot_*`
  Flyway migrations; the host only supplies generic native-character
  provisioning and its transaction callback.
- **Build:** compiles against BeiDou `extension-api` + `gms-server` (`provided`); does **not** ship Cosmic or BeiDou server sources.

## Integration model

| Piece | Where |
|-------|--------|
| Framework (`soloMapling/**`) | this repo → `plugins/solomapling-plugin-*.jar` |
| Thin SPI (`extension-api`) | host — includes `ArtificialCharacters` / `TradeParticipantHook` |
| Engine types (`org.gms.*`) | host `gms-server` (provided at compile time) |
| Host gameplay events | host publishes map/chat/party/trade invite events; bridges forward into SoloMapling queues / `EventBus` |
| Artificial-character checks | plugin registers classifier in `onLoad`; host uses `HostHooks.isArtificial` (no `soloMapling` imports) |
| Companion persistence | plugin owns `bot_*` tables and `flyway_solomapling_schema_history`; host remains unaware of Companion schema |
| Trade | plugin registers `TradeParticipantHook` + `BotTradeInviteBridge` → `BotTradeQueue` (see [docs/TRADE_DESIGN.md](docs/TRADE_DESIGN.md)) |

## Build

Prerequisites: JDK 21, Maven, and a local install of the host `extension-api` + `gms-server` that includes the extension runtime and simulation APIs (`BotClient`, `BotTier`, `TradeParticipantHook`, …).

```bash
# In BeiDou-Server
mvn -pl extension-api,gms-server -am install -DskipTests

# In this repo
mvn -DskipTests package
cp target/solomapling-plugin-*-SNAPSHOT.jar /path/to/BeiDou-Server/gms-server/plugins/
```

## Run (BeiDou)

Working directory must be `gms-server`:

```bash
cd /path/to/BeiDou-Server/gms-server
java -Xmx4g \
  -Dspring.config.location=src/main/resources/application.yml \
  -jar target/BeiDou-boot.jar
```

```yaml
solomapling:
  plugins-enabled: true
  plugins-dir: plugins
  spawn-bots-on-startup: true
  language: zh-CN   # optional; defaults to gms.service.language (en-US | zh-CN)
  llm:
    enabled: false
    api-key: ${DEEPSEEK_API_KEY:}   # or set env DEEPSEEK_API_KEY
    model: deepseek-v4-flash
    max-tokens: 80
    timeout-ms: 10000
    history-turns: 8
    fallback-to-yaml: true          # on LLM failure, speak a YAML line instead
```

### SocialBot LLM chat (Hybrid)

When `solomapling.llm.enabled: true` and an API key is set, **SocialBot** uses DeepSeek for **free-form player chat** during an active conversation (player must @ the bot by name first). Structured intents still use YAML:

| Player intent | Handler |
|---------------|---------|
| Menu `1`–`3` / keywords (what's up, rumors, …) | YAML dialogue |
| `4` / team / party | Party recruit flow |
| `5` / goodbye / bye | YAML farewell |
| Anything else | LLM (`deepseek-v4-flash` by default) |

LLM calls run on virtual threads and never block the chat packet thread. Replies are capped (~120 chars) for map chat. Session history is in-memory per bot↔player and cleared when the conversation ends.

Client library: [simple-openai](https://github.com/sashirestela/simple-openai) (`SimpleOpenAIDeepseek`), shaded into the plugin jar.

## SPI entry

`META-INF/services/org.gms.extension.api.ServerExtension` → `soloMapling.plugin.SoloMaplingExtension`

## Related

- Upstream framework: [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling)
- BeiDou host PR: see BeiDou-Server `feat/solomapling-plugin-host`
- Release notes: [RELEASE_NOTES.md](RELEASE_NOTES.md)

## Version

`0.4.0-SNAPSHOT` — SocialBot Hybrid LLM chat (DeepSeek via simple-openai) and zh-CN free-market localization; packaging is SPI/plugin-only.
