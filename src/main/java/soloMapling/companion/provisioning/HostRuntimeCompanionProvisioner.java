package soloMapling.companion.provisioning;

import org.gms.extension.api.HostCharacterProvisionRequest;
import org.gms.extension.api.HostCharacterProvisionResult;
import org.gms.extension.api.HostCharacterProvisioner;
import org.gms.extension.api.HostRuntime;

import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapts SoloMapling metadata to the host-neutral atomic provisioning API.
 */
public final class HostRuntimeCompanionProvisioner implements CompanionHostProvisioner {

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
                                (character_id, account_id, display_name, persona_seed)
                            VALUES (?, ?, ?, ?)
                            """;
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, metadata.characterId());
                        statement.setInt(2, metadata.accountId());
                        statement.setString(3, metadata.characterName());
                        statement.setLong(4, request.personaSeed());
                        if (statement.executeUpdate() != 1) {
                            throw new IllegalStateException("bot profile insertion failed");
                        }
                    }
                });
        return new CompanionProvisionResult(
                result.characterId(), result.accountId(), result.characterName());
    }
}
