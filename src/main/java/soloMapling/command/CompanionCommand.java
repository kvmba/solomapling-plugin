package soloMapling.command;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.extension.api.HostRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.CompanionBot;
import soloMapling.companion.agent.CompanionBrain;
import soloMapling.companion.lifecycle.CompanionLifecycleAccess;
import soloMapling.companion.lifecycle.CompanionLifecycleCoordinator;
import soloMapling.companion.lifecycle.CompanionLifecycleStatus;
import soloMapling.companion.memory.MemoryRecord;
import soloMapling.companion.persistence.CompanionMemoryRepository;
import soloMapling.companion.persistence.JdbcCompanionMemoryRepository;
import soloMapling.companion.provisioning.CompanionAdminRepository;
import soloMapling.companion.provisioning.CompanionAdminView;
import soloMapling.companion.provisioning.CompanionProvisionResult;
import soloMapling.companion.provisioning.CompanionProvisioningInput;
import soloMapling.companion.provisioning.CompanionProvisioningService;
import soloMapling.companion.provisioning.JdbcCompanionAdminRepository;
import soloMapling.companion.provisioning.HostRuntimeCompanionProvisioner;
import soloMapling.companion.provisioning.SecureCompanionIdentityGenerator;
import soloMapling.companion.provisioning.UnavailableCompanionHostProvisioner;
import soloMapling.companion.routine.RoutineBlock;
import soloMapling.companion.routine.RoutineSchedule;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class CompanionCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(CompanionCommand.class);

    static final int LIST_LIMIT = 50;
    static final int DEFAULT_MEMORY_LIMIT = 10;
    static final int MAX_MEMORY_LIMIT = 25;
    static final int MEMORY_SUMMARY_LIMIT = 120;

    private final CompanionAdminRepository repository;
    private final CompanionProvisioningService provisioningService;
    private final CompanionMemoryRepository memories;
    private final CompanionLifecycleAccess lifecycleAccess;

    {
        setDescription("Persistent companion provisioning and diagnostics.");
    }

    public CompanionCommand() {
        this(
                new JdbcCompanionAdminRepository(),
                new CompanionProvisioningService(
                        new UnavailableCompanionHostProvisioner(),
                        new SecureCompanionIdentityGenerator()),
                new JdbcCompanionMemoryRepository(),
                new CompanionLifecycleAccess()
        );
    }

    public CompanionCommand(HostRuntime runtime) {
        this(runtime, new CompanionLifecycleAccess());
    }

    public CompanionCommand(HostRuntime runtime, CompanionLifecycleAccess lifecycleAccess) {
        this(
                new JdbcCompanionAdminRepository(),
                new CompanionProvisioningService(
                        new HostRuntimeCompanionProvisioner(runtime),
                        new SecureCompanionIdentityGenerator()),
                new JdbcCompanionMemoryRepository(),
                lifecycleAccess
        );
    }

    CompanionCommand(
            CompanionAdminRepository repository,
            CompanionProvisioningService provisioningService,
            CompanionMemoryRepository memories,
            CompanionLifecycleAccess lifecycleAccess
    ) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.provisioningService =
                java.util.Objects.requireNonNull(provisioningService, "provisioningService");
        this.memories = java.util.Objects.requireNonNull(memories, "memories");
        this.lifecycleAccess = java.util.Objects.requireNonNull(lifecycleAccess, "lifecycleAccess");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character player = client.getPlayer();
        if (params.length == 0) {
            help(player);
            return;
        }

        String operation = params[0].toLowerCase(Locale.ROOT);
        try {
            switch (operation) {
                case "list" -> list(player, params);
                case "inspect" -> inspect(player, params);
                case "provision" -> provision(player, params);
                case "spawn" -> spawn(player, params);
                case "despawn" -> despawn(player, params);
                case "status" -> status(player, params);
                case "schedule" -> schedule(player, params);
                case "save" -> save(player, params);
                case "think" -> think(player, params);
                case "memories" -> memories(player, params);
                default -> help(player);
            }
        } catch (IllegalArgumentException e) {
            player.dropMessage(6, "Companion " + operation + " failed: " + e.getMessage());
        } catch (IllegalStateException e) {
            player.dropMessage(6, "Companion " + operation + " failed: " + e.getMessage());
        } catch (SQLException e) {
            log.error("Companion {} database operation failed for player {}",
                    operation, player == null ? "unknown" : player.getName(), e);
            player.dropMessage(6, "Companion " + operation
                    + " failed: database operation failed; no success was reported.");
        } catch (CompanionProvisioningService.ProvisioningUnavailableException e) {
            player.dropMessage(6, "Companion provision failed: " + e.getMessage()
                    + "; no account, character, or profile was created.");
        } catch (Exception e) {
            log.error("Companion {} operation failed for player {}",
                    operation, player == null ? "unknown" : player.getName(), e);
            player.dropMessage(6, "Companion " + operation
                    + " failed: no success was reported. Check server diagnostics.");
        }
    }

    private void list(Character player, String[] params) throws SQLException {
        requireArity(params, 1, "!companion list");
        List<CompanionAdminView> companions = repository.findAll(LIST_LIMIT);
        player.dropMessage(6, "Companion list succeeded: showing " + companions.size()
                + " (limit " + LIST_LIMIT + ").");
        for (CompanionAdminView companion : companions) {
            player.dropMessage(6, String.format(
                    "cid=%d aid=%d name=%s status=%s enabled=%s native=%s",
                    companion.characterId(),
                    companion.accountId(),
                    companion.displayName(),
                    companion.status(),
                    companion.enabled(),
                    nativeState(companion)));
        }
    }

    private void inspect(Character player, String[] params) throws SQLException {
        requireArity(params, 2, "!companion inspect <cid>");
        int characterId = CompanionProvisioningInput.parseCharacterId(params[1]);
        Optional<CompanionAdminView> found = repository.findByCharacterId(characterId);
        if (found.isEmpty()) {
            player.dropMessage(6, "Companion inspect failed: no profile found for cid="
                    + characterId + ".");
            return;
        }

        CompanionAdminView companion = found.get();
        player.dropMessage(6, String.format(
                "Companion inspect succeeded: cid=%d aid=%d name=%s status=%s enabled=%s",
                companion.characterId(), companion.accountId(), companion.displayName(),
                companion.status(), companion.enabled()));
        player.dropMessage(6, String.format(
                "personaSeed=%d growth=%s mode=%s native=%s",
                companion.personaSeed(), companion.growthStage(),
                companion.currentMode(), nativeState(companion)));
        player.dropMessage(6, "created=" + companion.createdAt() + " updated=" + companion.updatedAt());
    }

    private void provision(Character player, String[] params) throws Exception {
        if (params.length < 2 || params.length > 3) {
            throw new IllegalArgumentException(
                    "usage: !companion provision <characterName> [personaSeed]");
        }
        CompanionProvisionResult result =
                provisioningService.provision(
                        params[1],
                        params.length == 3 ? params[2] : null,
                        player.getWorld());
        player.dropMessage(6, String.format(
                "Companion provisioned: cid=%d aid=%d name=%s.",
                result.characterId(), result.accountId(), result.displayName()));
    }

    private void spawn(Character player, String[] params) throws SQLException {
        requireArity(params, 2, "!companion spawn <cid>");
        int characterId = parseCharacterId(params[1]);
        CompanionLifecycleStatus result = lifecycle().spawnNow(characterId);
        player.dropMessage(6, "Companion spawn succeeded: " + formatStatus(result));
    }

    private void despawn(Character player, String[] params) {
        requireArity(params, 2, "!companion despawn <cid>");
        int characterId = parseCharacterId(params[1]);
        CompanionLifecycleStatus result = lifecycle().despawnNow(characterId);
        player.dropMessage(6, "Companion despawn succeeded: " + formatStatus(result));
    }

    private void status(Character player, String[] params) {
        if (params.length > 2) {
            throw new IllegalArgumentException("usage: !companion status [cid]");
        }
        CompanionLifecycleCoordinator coordinator = lifecycle();
        if (params.length == 2) {
            int characterId = parseCharacterId(params[1]);
            Optional<CompanionLifecycleStatus> result = coordinator.status(characterId);
            if (result.isEmpty()) {
                player.dropMessage(6, "Companion status failed: no lifecycle status for cid="
                        + characterId + ".");
                return;
            }
            player.dropMessage(6, "Companion status succeeded: " + formatStatus(result.get()));
            return;
        }

        List<CompanionLifecycleStatus> statuses = coordinator.status().values().stream()
                .sorted(Comparator.comparingInt(CompanionLifecycleStatus::characterId))
                .limit(LIST_LIMIT)
                .toList();
        player.dropMessage(6, "Companion status succeeded: showing " + statuses.size()
                + " (limit " + LIST_LIMIT + ").");
        for (CompanionLifecycleStatus value : statuses) {
            player.dropMessage(6, formatStatus(value));
        }
    }

    private void schedule(Character player, String[] params) throws SQLException {
        requireArity(params, 2, "!companion schedule <cid>");
        int characterId = parseCharacterId(params[1]);
        RoutineSchedule schedule = lifecycle().schedule(characterId);
        player.dropMessage(6, "Companion schedule succeeded: cid=" + characterId
                + " zone=" + schedule.zoneId() + " blocks=" + schedule.blocks().size() + ".");
        for (RoutineBlock block : schedule.blocks()) {
            player.dropMessage(6, block.start() + "-" + block.end() + "=" + block.activity());
        }
    }

    private void save(Character player, String[] params) {
        requireArity(params, 2, "!companion save <cid>");
        int characterId = parseCharacterId(params[1]);
        lifecycle().checkpointNow(characterId);
        player.dropMessage(6, "Companion save succeeded: checkpoint saved for cid="
                + characterId + ".");
    }

    private void think(Character player, String[] params) {
        if (params.length < 3) {
            throw new IllegalArgumentException("usage: !companion think <cid> <message>");
        }
        int characterId = parseCharacterId(params[1]);
        String message = String.join(" ", java.util.Arrays.copyOfRange(params, 2, params.length))
                .trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (message.length() > CompanionBrain.MAX_PLAYER_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message must not exceed "
                    + CompanionBrain.MAX_PLAYER_MESSAGE_LENGTH + " characters");
        }

        CompanionLifecycleStatus lifecycleStatus = lifecycle().status(characterId)
                .filter(status -> status.state() == CompanionLifecycleStatus.State.ONLINE
                        && status.loaded())
                .orElseThrow(() -> new IllegalStateException(
                        "companion cid=" + characterId + " is not online"));
        BotSM stored = CharacterStorage.getBotById(characterId);
        if (!(stored instanceof CompanionBot companion)) {
            throw new IllegalStateException(
                    "CharacterStorage does not contain an online CompanionBot for cid="
                            + characterId);
        }
        Character companionCharacter = companion.getChr();
        if (player.getMap() == null || companionCharacter == null
                || companionCharacter.getMap() == null
                || player.getMapId() != companionCharacter.getMapId()) {
            throw new IllegalStateException(
                    "GM and companion must be on the same map; cross-map think is rejected");
        }
        if (!companion.enqueuePlayerMessage(player, message)) {
            throw new IllegalStateException(
                    "companion queue rejected the message (busy or invalid)");
        }
        player.dropMessage(6, "Companion think succeeded: queued for cid="
                + lifecycleStatus.characterId() + "; LLM work will run asynchronously.");
    }

    private void memories(Character player, String[] params) throws SQLException {
        if (params.length < 2 || params.length > 3) {
            throw new IllegalArgumentException("usage: !companion memories <cid> [limit]");
        }
        int characterId = parseCharacterId(params[1]);
        int limit = params.length == 3
                ? parseLimit(params[2], MAX_MEMORY_LIMIT)
                : DEFAULT_MEMORY_LIMIT;
        List<MemoryRecord> found = memories.findCandidates(
                characterId, null, null, null, false, null, null, limit);
        player.dropMessage(6, "Companion memories succeeded: cid=" + characterId
                + " showing=" + found.size() + " limit=" + limit + ".");
        for (MemoryRecord memory : found) {
            player.dropMessage(6, String.format(Locale.ROOT,
                    "id=%s type=%s time=%s strength=%.2f summary=%s",
                    memory.id(), memory.type(), memory.occurredAt(), memory.strength(),
                    memorySummary(memory.content())));
        }
    }

    private CompanionLifecycleCoordinator lifecycle() {
        return lifecycleAccess.current().orElseThrow(() -> new IllegalStateException(
                "lifecycle is not ready (wait for server ready and enable companions)"));
    }

    private static String nativeState(CompanionAdminView companion) {
        if (!companion.accountPresent()) {
            return "missing-account";
        }
        if (!companion.characterPresent()) {
            return "missing-character";
        }
        return companion.ownershipMatches() ? "ok" : "account-mismatch";
    }

    private static void requireArity(String[] params, int expected, String usage) {
        if (params.length != expected) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    static int parseLimit(String raw, int maximum) {
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0 || value > maximum) {
                throw new IllegalArgumentException(
                        "limit must be between 1 and " + maximum);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be an integer", exception);
        }
    }

    static String memorySummary(String content) {
        String normalized = content == null ? "" : content
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("system:") || lower.startsWith("prompt:")
                || lower.contains("system prompt")) {
            return "[sensitive prompt redacted]";
        }
        if (lower.contains("player said:") || lower.startsWith("user:")) {
            return "[private conversation summary redacted]";
        }
        if (normalized.length() <= MEMORY_SUMMARY_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, MEMORY_SUMMARY_LIMIT - 1) + "…";
    }

    private static int parseCharacterId(String raw) {
        return CompanionProvisioningInput.parseCharacterId(raw);
    }

    private static String formatStatus(CompanionLifecycleStatus status) {
        return String.format(
                "cid=%d state=%s desiredOnline=%s loaded=%s code=%s observed=%s",
                status.characterId(), status.state(), status.desiredOnline(), status.loaded(),
                status.code(), status.observedAt());
    }

    private void help(Character player) {
        player.dropMessage(6, "!companion list");
        player.dropMessage(6, "!companion inspect <cid>");
        player.dropMessage(6, "!companion provision <characterName> [personaSeed]");
        player.dropMessage(6, "!companion spawn <cid> | despawn <cid> | status [cid]");
        player.dropMessage(6, "!companion schedule <cid> | save <cid>");
        player.dropMessage(6, "!companion think <cid> <message>");
        player.dropMessage(6, "!companion memories <cid> [limit]");
        player.dropMessage(6, "Provisioning: " + provisioningService.availabilityDescription());
    }
}
