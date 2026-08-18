# solomapling-plugin

SoloMapling artificial-player framework packaged as a **BeiDou** `ServerExtension` jar.

This repository contains **only** the SoloMapling framework (plus SPI entry). It does **not** include Cosmic or BeiDou server sources.

## Integration model (option 2)

| Piece | Where |
|-------|--------|
| Framework (`soloMapling/**`) | this repo → `plugins/solomapling-plugin-*.jar` |
| Thin SPI (`extension-api`) | BeiDou-Server |
| Engine types (`org.gms.*`) | BeiDou `gms-server` (provided at compile time) |
| Host bridges (`BotHelpers`, `EventBus`, …) | BeiDou `gms-server` (engine hooks call these) |

Plugins compile against BeiDou types directly. SPI only covers lifecycle, config, events, and command registration.

## Build

Prerequisites: JDK 21, Maven, and a local install of BeiDou `extension-api` + `gms-server` (`1.0-SNAPSHOT`) that includes the SoloMapling host hooks.

```bash
# In BeiDou-Server (feature branch with extension runtime)
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

Config (`application.yml`):

```yaml
solomapling:
  plugins-enabled: true
  plugins-dir: plugins
  spawn-bots-on-startup: true
```

## SPI entry

`META-INF/services/org.gms.extension.api.ServerExtension` → `soloMapling.plugin.SoloMaplingExtension`

## Version

`0.3.0-SNAPSHOT` — aligned with SoloMapling v0.3 bot framework.
