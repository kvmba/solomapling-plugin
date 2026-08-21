package soloMapling.companion.provisioning;

/**
 * Host-owned atomic boundary. Implementations must use the host password
 * encoder and native character creation path, and commit account, character,
 * character sub-tables, and bot_profiles as one operation. The credential is
 * ephemeral and will be zeroed as soon as the synchronous call returns.
 */
public interface CompanionHostProvisioner {

    boolean isAvailable();

    String unavailableReason();

    CompanionProvisionResult provision(
            CompanionProvisionRequest request,
            String accountName,
            char[] credential
    ) throws Exception;
}
