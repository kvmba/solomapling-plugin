package soloMapling.companion.routine;

/**
 * Bounded persona influence on ordinary offline work. 10,000 basis points is neutral.
 */
public record PersonaProgressionProfile(int diligenceBasisPoints, int thriftBasisPoints) {
    public static final PersonaProgressionProfile NEUTRAL =
            new PersonaProgressionProfile(10_000, 10_000);

    public PersonaProgressionProfile {
        validate(diligenceBasisPoints, "diligenceBasisPoints");
        validate(thriftBasisPoints, "thriftBasisPoints");
    }

    private static void validate(int value, String name) {
        if (value < 5_000 || value > 10_000) {
            throw new IllegalArgumentException(name + " must be between 5000 and 10000");
        }
    }
}
