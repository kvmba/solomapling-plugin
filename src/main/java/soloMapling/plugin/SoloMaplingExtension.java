package soloMapling.plugin;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.extension.api.ArtificialCharacters;
import org.gms.extension.api.CharacterClassifier;
import org.gms.extension.api.HostItemActions;
import org.gms.extension.api.HostMonsterDrops;
import org.gms.extension.api.HostRuntime;
import org.gms.extension.api.ServerExtension;
import org.gms.extension.api.event.ServerReadyEvent;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;
import org.gms.server.life.LifeFactory;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.maps.MapFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotClientHandler;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.PlayerChatBridge;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyInviteBridge;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInviteBridge;
import soloMapling.ArtificialPlayer.BotTradeSystem.SoloMaplingTradeParticipantHook;
import soloMapling.ArtificialPlayer.LlmSystem.SocialLlmService;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.Environment.EnvironmentPopulationConfig;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.command.ArtificialPlayerCommand;
import soloMapling.command.BotMoveCommand;
import soloMapling.command.CompanionCommand;
import soloMapling.command.EnvironmentCommand;
import soloMapling.command.FMBotCommand;
import soloMapling.command.GCMoveCommand;
import soloMapling.companion.CompanionRoster;
import soloMapling.companion.lifecycle.CompanionLifecycleAccess;
import soloMapling.companion.lifecycle.CompanionLifecycleCoordinator;
import soloMapling.companion.lifecycle.HostCompanionRuntimeAdapter;
import soloMapling.companion.persistence.CompanionSchemaMigrator;
import soloMapling.companion.persistence.JdbcCompanionProfileRepository;
import soloMapling.companion.routine.OfflineProgressionPolicy;
import soloMapling.companion.execution.CompanionRuntimeCapabilities;
import soloMapling.companion.gear.GearDropSourceProvider;
import soloMapling.companion.intake.CompanionIntakeService;
import soloMapling.companion.provisioning.CompanionProvisionRequest;
import soloMapling.companion.provisioning.CompanionProvisioningInput;
import soloMapling.companion.provisioning.CompanionProvisioningService;
import soloMapling.companion.provisioning.HostRuntimeCompanionProvisioner;
import soloMapling.companion.provisioning.SecureCompanionIdentityGenerator;
import soloMapling.FreeMarket.FMShopDescGen;
import soloMapling.itemPool.DesirableEquipList;
import soloMapling.itemPool.EquipMetadataCache;
import soloMapling.server.MethodScheduler;

import java.time.Clock;

/**
 * SPI entry for SoloMapling. Discovered via
 * {@code META-INF/services/org.gms.extension.api.ServerExtension}.
 */
public final class SoloMaplingExtension implements ServerExtension {

    private static final Logger log = LoggerFactory.getLogger(SoloMaplingExtension.class);

    /**
     * Persistent companions keep their native auto-increment character IDs.
     * The historical range remains classified for ephemeral template clones.
     *
     * <p>{@code CharacterStorage.botLoggedIn} is consulted first because it is populated the
     * instant a Character is wrapped in a BotSM, so it is the only source that is already
     * correct for a character that has become a bot but has not yet reached the roster
     * (e.g. a companion being attached). Without it such a character is briefly classified as
     * a real player and the host runs player-facing map scripts against it, which then fail
     * with "Cannot read property 'getMapId' of null" because headless bot clients have no
     * Character. The id-range and roster checks remain as belt-and-braces for characters
     * that are bots by construction but not yet attached (e.g. mid-spawn).
     */
    private static final CharacterClassifier BOT_IDS =
            id -> CharacterStorage.botLoggedIn(id)
                    || CompanionRoster.isCompanion(id)
                    || id > 20000
                    || id == 999;

    private final CompanionLifecycleAccess companionLifecycleAccess =
            new CompanionLifecycleAccess();
    private HostRuntime runtime;
    private CompanionLifecycleCoordinator companionLifecycle;
    private CompanionIntakeService companionIntake;

    @Override
    public String id() {
        return "solomapling";
    }

    @Override
    public String version() {
        return "0.4.0-SNAPSHOT";
    }

    @Override
    public void onLoad(HostRuntime runtime) {
        this.runtime = runtime;
        int migrationsExecuted = CompanionSchemaMigrator.migrate();
        log.info("SoloMapling plugin onLoad hostId={} spawnBotsOnStartup={}",
                runtime.hostId(),
                runtime.config().getBool("solomapling.spawn-bots-on-startup", false));
        log.info("SoloMapling schema ready migrationsExecuted={}", migrationsExecuted);

        BotGeneration.ensureTemplateCharacter(runtime);

        ArtificialCharacters.register(BOT_IDS);
        SoloMaplingTradeParticipantHook.register();
        log.info("SoloMapling registered ArtificialCharacters classifier + TradeParticipantHook");

        SoloMaplingLanguageConfig.configure(runtime.config());
        log.info("SoloMapling language={} dialoguePack={}",
                SoloMaplingLanguageConfig.languageTag(),
                SoloMaplingLanguageConfig.dialoguePackDirectoryName());

        SocialLlmService.configure(runtime.config());
        installCompanionRuntimeCapabilities(runtime);

        String populationPath = runtime.config().getString("solomapling.population-config", "");
        if (populationPath != null && !populationPath.isBlank()) {
            EnvironmentPopulationConfig.setConfigPath(populationPath);
            log.info("SoloMapling population-config override={}", populationPath);
        }
        var plan = EnvironmentPopulationConfig.plan();
        log.info("SoloMapling population plan source={} scale={} trainingScaledTotal={}",
                plan.loadedFrom(), plan.scale(), plan.trainingCohortTotal());

        registerCommands(runtime);

        // Host->bot input bridges. Must be live before any player can chat or invite.
        HostGameplayEventBridge.register(runtime);
        PlayerChatBridge.register();
        BotPartyInviteBridge.register(runtime);
        BotTradeInviteBridge.register(runtime);

        runtime.events().subscribe(ServerReadyEvent.class, this::onHostServerReady);

        try {
            EquipMetadataCache.initialize();
            DesirableEquipList.load();
            log.info("SoloMapling EquipMetadataCache + DesirableEquipList loaded");
        } catch (Throwable t) {
            log.warn("SoloMapling equip metadata prefetch failed (bots may still start lazily): {}", t.toString());
        }
    }

    private void registerCommands(HostRuntime runtime) {
        bindCommand(runtime, "smping", 4, "SoloMapling plugin ping",
                (characterId, args) -> log.info("SoloMapling !smping from characterId={} args={}",
                        characterId, String.join(" ", args == null ? new String[0] : args)));
        bindCommand(runtime, "env", 4, "SoloMapling environment commands", new EnvironmentCommand());
        bindCommand(runtime, "bot", 4, "SoloMapling artificial player commands", new ArtificialPlayerCommand());
        bindCommand(runtime, "move", 4, "SoloMapling bot move commands", new BotMoveCommand());
        bindCommand(runtime, "fmbot", 4, "SoloMapling FM bot commands", new FMBotCommand());
        bindCommand(runtime, "gcmove", 4, "SoloMapling GCMove commands", new GCMoveCommand());
        bindCommand(runtime, "companion", 4,
                "Persistent companion provisioning and diagnostics",
                new CompanionCommand(runtime, companionLifecycleAccess));
    }

    private static void installCompanionRuntimeCapabilities(HostRuntime runtime) {
        HostItemActions itemActions = runtime.itemActions().orElse(null);
        HostMonsterDrops monsterDrops = runtime.monsterDrops().orElse(null);
        CompanionRuntimeCapabilities.install(
                (sourceId, targetId, inventoryType, slot, quantity) -> {
                    if (itemActions == null) {
                        return new CompanionRuntimeCapabilities.GiftResult(
                                false, "HOST_ITEM_ACTIONS_UNAVAILABLE");
                    }
                    HostItemActions.DropResult result = itemActions.dropToCharacter(
                            sourceId, targetId, inventoryType, slot, quantity);
                    return new CompanionRuntimeCapabilities.GiftResult(
                            result.success(), result.code());
                },
                itemId -> {
                    if (monsterDrops == null) {
                        return java.util.List.of();
                    }
                    return monsterDrops.findSources(itemId, 8).stream()
                            .map(SoloMaplingExtension::toGearDropSource)
                            .toList();
                });
        log.info("SoloMapling companion host capabilities itemActions={} monsterDrops={}",
                itemActions != null, monsterDrops != null);
    }

    private static GearDropSourceProvider.DropSource toGearDropSource(
            HostMonsterDrops.DropSource source) {
        MonsterInformationProvider monsters = MonsterInformationProvider.getInstance();
        String mobName = monsters.getMobNameFromId(source.dropperId());
        int mapId = parseMapId(MapFactory.getMapIdByLifeId(source.dropperId()));
        boolean boss = false;
        try {
            Monster monster = LifeFactory.getMonster(source.dropperId());
            boss = monster != null && monster.isBoss();
        } catch (RuntimeException ignored) {
            // Name/drop facts remain useful even when a mob template cannot be loaded.
        }
        return new GearDropSourceProvider.DropSource(
                source.dropperId(),
                mobName == null ? "monster-" + source.dropperId() : mobName,
                mapId,
                mapId < 0 ? "" : "map-" + mapId,
                source.chance() / 1_000_000.0,
                boss);
    }

    private static int parseMapId(String raw) {
        if (raw == null) return -1;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d{9})(?:\\.img)?$").matcher(raw);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void bindCommand(HostRuntime runtime, String syntax, int level, String description, Command command) {
        runtime.commands().register(syntax, level, description, (characterId, args) -> {
            Character chr = findOnlineCharacter(characterId);
            if (chr == null) {
                log.warn("SoloMapling command !{}: characterId={} not online", syntax, characterId);
                return;
            }
            Client client = chr.getClient();
            if (client == null) {
                log.warn("SoloMapling command !{}: characterId={} has no client", syntax, characterId);
                return;
            }
            command.execute(client, args == null ? new String[0] : args);
        });
    }

    private void bindCommand(HostRuntime runtime, String syntax, int level, String description,
                             org.gms.extension.api.HostCommandHandler handler) {
        runtime.commands().register(syntax, level, description, handler);
    }

    /*
     * Newcomers, at a trickle. interval-seconds is the switch: above zero a
     * companion is provisioned every that-many seconds, zero or absent means the
     * world keeps only the companions it already has.
     *
     * Read once, at startup — there is no hot reload, so the value is decided
     * here and never revisited.
     */
    private void startCompanionIntake() {
        int intervalSeconds = runtime.config()
                .getInt("solomapling.companion-intake.interval-seconds", 0);
        if (intervalSeconds <= 0) {
            log.info("SoloMapling companion intake disabled (interval-seconds={})",
                    intervalSeconds);
            return;
        }
        // Guarded, not fatal: a bad intake setting must not take the rest of
        // onServerReady down with it — the ambient population spawns after this.
        int maxTotal = runtime.config()
                .getInt("solomapling.companion-intake.max-total", 20);
        if (maxTotal <= 0) {
            log.warn("SoloMapling companion intake disabled: max-total must be positive, got {}",
                    maxTotal);
            return;
        }
        int worldId = runtime.config()
                .getInt("solomapling.companion-intake.world-id", 0);
        if (worldId < 0) {
            log.warn("SoloMapling companion intake disabled: world-id must not be negative, got {}",
                    worldId);
            return;
        }
        final String timezone;
        try {
            timezone = CompanionProvisioningInput.validateTimezone(
                    runtime.config().getString(
                            "solomapling.companion-intake.timezone",
                            CompanionProvisionRequest.DEFAULT_TIMEZONE));
        } catch (IllegalArgumentException e) {
            log.warn("SoloMapling companion intake disabled: {}", e.getMessage());
            return;
        }
        try {
            CompanionIntakeService intake = new CompanionIntakeService(
                    new CompanionProvisioningService(
                            new HostRuntimeCompanionProvisioner(runtime),
                            new SecureCompanionIdentityGenerator()),
                    companionLifecycleAccess,
                    // The ambient bots' own name pool: the same list a player
                    // sees walking around, so a newcomer does not stand out.
                    FMShopDescGen::getRandomCharacterIGN,
                    intervalSeconds * 1000L,
                    maxTotal,
                    worldId,
                    timezone);
            intake.start();
            companionIntake = intake;
        } catch (RuntimeException e) {
            log.warn("SoloMapling companion intake failed to start: {}", e.toString());
        }
    }

    private static Character findOnlineCharacter(int characterId) {
        for (World world : Server.getInstance().getWorlds()) {
            Character chr = world.getPlayerStorage().getCharacterById(characterId);
            if (chr != null) {
                return chr;
            }
        }
        return null;
    }

    private void onHostServerReady(ServerReadyEvent event) {
        log.info("SoloMapling received ServerReadyEvent at {}", event.readyAtEpochMs());
    }

    @Override
    public void onServerReady() {
        boolean spawn = runtime != null
                && runtime.config().getBool("solomapling.spawn-bots-on-startup", false);
        boolean companionsEnabled = runtime != null
                && runtime.config().getBool("solomapling.companions.enabled", false);
        log.info("SoloMapling onServerReady spawnBotsOnStartup={} companionsEnabled={}",
                spawn, companionsEnabled);
        try {
            BotClientHandler.initHeadlessBotClient();
            log.info("SoloMapling BotClientHandler headless client initialized");
        } catch (Throwable t) {
            log.error("SoloMapling failed to init headless BotClient", t);
            return;
        }
        try {
            CompanionRoster.refreshFromDatabase();
        } catch (Throwable t) {
            // Keep legacy ambient bots available when a developer runs the
            // plugin before applying the companion schema migration.
            log.warn("SoloMapling companion roster unavailable; persistent companions disabled: {}",
                    t.toString());
        }
        if (companionsEnabled) {
            CompanionLifecycleCoordinator lifecycle = new CompanionLifecycleCoordinator(
                    new JdbcCompanionProfileRepository(),
                    new HostCompanionRuntimeAdapter(),
                    OfflineProgressionPolicy.conservativeDefaults(),
                    Clock.systemUTC());
            lifecycle.start();
            companionLifecycle = lifecycle;
            companionLifecycleAccess.register(lifecycle);
            log.info("SoloMapling persistent companion lifecycle started");
            startCompanionIntake();
        }
        if (spawn) {
            MethodScheduler.runAfterDelay(() -> {
                try {
                    EnvironmentManager.environmentLoadStartup();
                } catch (Throwable t) {
                    log.error("SoloMapling environmentLoadStartup failed", t);
                }
            }, 1000);
            log.info("SoloMapling scheduled environmentLoadStartup in 1s");
        }
    }

    @Override
    public void onUnload() {
        log.info("SoloMapling plugin onUnload");
        CompanionIntakeService intake = companionIntake;
        companionIntake = null;
        if (intake != null) {
            intake.stop();
        }
        CompanionLifecycleCoordinator lifecycle = companionLifecycle;
        companionLifecycleAccess.clear(lifecycle);
        companionLifecycle = null;
        if (lifecycle != null) {
            lifecycle.stop();
        }
        SoloMaplingTradeParticipantHook.unregister();
        CompanionRuntimeCapabilities.clear();
        ArtificialCharacters.unregister(BOT_IDS);
        CompanionRoster.clear();
        runtime = null;
    }
}
