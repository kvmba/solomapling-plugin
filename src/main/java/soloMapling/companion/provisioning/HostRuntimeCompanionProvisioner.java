package soloMapling.companion.provisioning;

import org.gms.extension.api.HostCharacterProvisionRequest;
import org.gms.extension.api.HostCharacterProvisionResult;
import org.gms.extension.api.HostCharacterProvisioner;
import org.gms.extension.api.HostRuntime;
import soloMapling.companion.progression.CompanionCareerBuild;
import soloMapling.companion.routine.CompanionRoutineGenerator;

import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapts SoloMapling metadata to the host-neutral atomic provisioning API.
 *
 * <p>The metadata callback runs inside the host's provisioning transaction, so
 * everything it writes is committed — or rolled back — together with the native
 * account and character. That is what makes it the right place for two things a
 * freshly created companion must have:</p>
 * <ul>
 *   <li>a spawn map, because the host picks it from a server setting
 *       ({@code use_beidou_beginner_map}) whose non-default branch is a map with
 *       no way out, and</li>
 *   <li>a routine, because a blank one parses to an all-offline schedule and a
 *       companion whose schedule says offline is never spawned.</li>
 * </ul>
 */
public final class HostRuntimeCompanionProvisioner implements CompanionHostProvisioner {

    /**
     * Mushroom Town: the beginner island's own spawn, and the start of the walk
     * to Southperry. The host's alternative (map 4) is a dead end — no exit
     * portal, no NPC — so a companion left there would never reach the boat.
     */
    private static final int SPAWN_MAP_ID = 10_000;

    private static final String UNSUPPORTED_HOST = "host is not BeiDou";
    private static final String MISSING_CAPABILITY =
            "host API missing: atomic native character provisioning";

    private final HostCharacterProvisioner provisioner;
    private final String unavailableReason;

    public HostRuntimeCompanionProvisioner(HostRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        Optional<HostCharacterProvisioner> capability = runtime.characterProvisioner();
        if (!"beidou".equalsIgnoreCase(runtime.hostId())) {
            provisioner = null;
            unavailableReason = UNSUPPORTED_HOST;
        } else if (capability.isEmpty()) {
            provisioner = null;
            unavailableReason = MISSING_CAPABILITY;
        } else {
            provisioner = capability.get();
            unavailableReason = "";
        }
    }

    @Override
    public boolean isAvailable() {
        return provisioner != null;
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public CompanionProvisionResult provision(
            CompanionProvisionRequest request,
            String accountName,
            char[] credential
    ) throws Exception {
        if (provisioner == null) {
            throw new IllegalStateException(unavailableReason);
        }

        HostCharacterProvisionResult result = provisioner.provision(
                new HostCharacterProvisionRequest(
                        accountName, credential, request.characterName(), request.worldId()),
                (connection, metadata) -> {
                    String sql = """
                            INSERT INTO bot_profiles
                                (character_id, account_id, display_name, persona_seed, career_build,
                                 routine_timezone, routine_profile)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """;
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, metadata.characterId());
                        statement.setInt(2, metadata.accountId());
                        statement.setString(3, metadata.characterName());
                        statement.setLong(4, request.personaSeed());
                        statement.setString(
                                5, CompanionCareerBuild.fromSeed(request.personaSeed()).id());
                        statement.setString(6, request.timezone());
                        statement.setString(
                                7, CompanionRoutineGenerator.generate(request.personaSeed()));
                        if (statement.executeUpdate() != 1) {
                            throw new IllegalStateException("bot profile insertion failed");
                        }
                    }
                    // Force the spawn map. Shares the host's connection, so a
                    // failure here rolls the whole provisioning back rather than
                    // leaving a companion that exists but can never be placed.
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE characters SET map = ?, spawnpoint = 0 WHERE id = ?")) {
                        statement.setInt(1, SPAWN_MAP_ID);
                        statement.setInt(2, metadata.characterId());
                        if (statement.executeUpdate() != 1) {
                            throw new IllegalStateException("companion spawn map update failed");
                        }
                    }
                });
        return new CompanionProvisionResult(
                result.characterId(), result.accountId(), result.characterName());
    }
}
