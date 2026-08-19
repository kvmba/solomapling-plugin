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
- **Build:** compiles against BeiDou `extension-api` + `gms-server` (`provided`); does **not** ship Cosmic or BeiDou server sources.

## Integration model

| Piece | Where |
|-------|--------|
| Framework (`soloMapling/**`) | this repo → `plugins/solomapling-plugin-*.jar` |
| Thin SPI (`extension-api`) | host — includes `ArtificialCharacters` / `TradeParticipantHook` |
| Engine types (`org.gms.*`) | host `gms-server` (provided at compile time) |
| Host gameplay events | host publishes map/chat/party/trade invite events; bridges forward into SoloMapling queues / `EventBus` |
| Artificial-character checks | plugin registers classifier in `onLoad`; host uses `HostHooks.isArtificial` (no `soloMapling` imports) |
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
```

## SPI entry

`META-INF/services/org.gms.extension.api.ServerExtension` → `soloMapling.plugin.SoloMaplingExtension`

## Related

- Upstream framework: [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling)
- BeiDou host PR: see BeiDou-Server `feat/solomapling-plugin-host`
- Release notes: [RELEASE_NOTES.md](RELEASE_NOTES.md)

## Version

`0.3.0-SNAPSHOT` — framework lineage aligned with SoloMapling v0.3; packaging is SPI/plugin-only.
