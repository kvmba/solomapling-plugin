package soloMapling.companion.provisioning;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Generates unrecoverable account material for a future atomic host API.
 * Callers must never log or return the generated credential.
 */
public final class SecureCompanionIdentityGenerator {

    private static final char[] ACCOUNT_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final char[] CREDENTIAL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int ACCOUNT_SUFFIX_LENGTH = 9;
    private static final int CREDENTIAL_LENGTH = 48;

    private final SecureRandom random;

    public SecureCompanionIdentityGenerator() {
        this(new SecureRandom());
    }

    SecureCompanionIdentityGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public String nextAccountName() {
        return "cmp_" + randomChars(ACCOUNT_ALPHABET, ACCOUNT_SUFFIX_LENGTH);
    }

    public char[] nextCredential() {
        return randomChars(CREDENTIAL_ALPHABET, CREDENTIAL_LENGTH).toCharArray();
    }

    public long nextPersonaSeed() {
        return random.nextLong();
    }

    private String randomChars(char[] alphabet, int length) {
        char[] value = new char[length];
        for (int i = 0; i < value.length; i++) {
            value[i] = alphabet[random.nextInt(alphabet.length)];
        }
        return new String(value);
    }
}
