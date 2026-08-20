package soloMapling.companion.provisioning;

/**
 * Safe fallback used when a command is constructed without a host runtime.
 *
 * <p>The minimum missing host capability is one transactional API that:
 * creates a dedicated non-interactive account using AccountService password
 * encoding and a random unrecoverable credential; creates a character through
 * the native CharacterFactory/insertNewChar path including all sub-tables; and
 * inserts bot_profiles before committing.</p>
 */
public final class UnavailableCompanionHostProvisioner implements CompanionHostProvisioner {

    public static final String REASON =
            "host API missing: atomic non-login account + native character + bot profile provisioning";

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return REASON;
    }

    @Override
    public CompanionProvisionResult provision(
            CompanionProvisionRequest request,
            String accountName,
            char[] credential
    ) {
        throw new IllegalStateException(REASON);
    }
}
