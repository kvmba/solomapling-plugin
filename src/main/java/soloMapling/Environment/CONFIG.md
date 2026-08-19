# SoloMapling population configuration

One file drives world population:

**`EnvironmentPopulation.yaml`**

| Section | Role |
|---------|------|
| `scale` | Multiplies Henesys / FM / merchant / training counts |
| `waves.*` | Wave toggles and batch sizes |
| `waves.training.cohorts` | TrainingBot spawn hubs + level bands |
| `waves.town_presence.towns` | Ambient SocialBot / wanderer counts (was `TownPresence.yaml`) |

`TownPresence.yaml` is deprecated and no longer read (stub only).

## Where to put the file at runtime (BeiDou)

**Do not put it in `BeiDou-boot.jar`.** The host fat jar does not need this file.
`EnvironmentPopulationConfig` resolves it in this order:

1. Optional override: `application.yml` → `solomapling.population-config: <path>`
2. Optional hot-edit overlay: `data/solomapling/override/Environment/EnvironmentPopulation.yaml`
3. Legacy FS (dev only): `src/main/java/soloMapling/Environment/EnvironmentPopulation.yaml`
4. Classpath inside **`plugins/solomapling-plugin-*.jar`**:  
   `soloMapling/Environment/EnvironmentPopulation.yaml`

| Location | Role |
|----------|------|
| `solomapling-plugin/.../Environment/EnvironmentPopulation.yaml` | Source of truth; packed into the **plugin** jar at build |
| `data/solomapling/override/...` | Optional runtime hot-edits (no plugin rebuild) |
| `BeiDou-boot.jar` | Not used for this config |

## Packaged resources vs runtime data

Static SoloMapling assets (YAML, movement packs, IGN pools, dialogue, …) live **inside the plugin jar** on the classpath under `soloMapling/`. Loaders use `PluginResources` (override → legacy FS → classpath).

| Path | Role |
|------|------|
| classpath `soloMapling/<rel>` | Packaged static resources (production) |
| `data/solomapling/override/<rel>` | Optional hot-edit overlay for any packaged file |
| `logs/` | Runtime logs (`BotLog.txt`, graph dump, …) |
| `data/solomapling/` | Writable state (`TownPins.txt`, `recordings/map<id>/…`) |
| `cache/bot-nav/` | Nav cache (existing) |

Do **not** write recordings or machine state into `src/main/java`. Recording output goes to `data/solomapling/recordings/`.

## Dialogue language

Bot dialogue YAML lives under `soloMapling/ArtificialPlayer/`:

| Directory | Language |
|-----------|----------|
| `BotDialoguePack/` | English (default) |
| `BotDialoguePack-zh-CN/` | Simplified Chinese |

Set in `application.yml`:

```yaml
solomapling:
  language: zh-CN   # optional; falls back to gms.service.language, then en-US
```

Resolution matches BeiDou scripts: localized pack first, then English fallback. At runtime the plugin reads via `PluginResources` (override / legacy FS / plugin jar classpath).

## Live commands (GM ≥ 4)

```
!env population show|reload   # whole file (waves + towns)
!env townpresence reload      # same file; refreshes town plan + social map scope
```

`reload` re-reads YAML but does not despawn bots already online — restart (or a full environment load) to apply new counts.

## Verify

```bash
cd /path/to/BeiDou-Server
mvn -pl solomapling-plugin -am test -Dtest=EnvironmentPopulationConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```
