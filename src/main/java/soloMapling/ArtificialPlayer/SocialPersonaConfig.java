package soloMapling.ArtificialPlayer;

import org.gms.extension.api.HostConfig;

/**
 * Host-configurable knobs for the town-bot chat personality ({@code solomapling.social.*}).
 *
 * <p>Defaults lean "server-culture cheeky" - the goal is bots that answer players like a real,
 * slightly-toxic player would, not polite NPCs. Both keys are a safety valve: dial the cheek down
 * (or off) without touching code.
 */
public final class SocialPersonaConfig {

    private SocialPersonaConfig() {
    }

    public static final String PREFIX = "solomapling.social.";
    /** {@code cheeky} (default) | {@code chill} | {@code off}. */
    public static final String KEY_PERSONA = PREFIX + "persona";
    /** Percent of normal replies that carry a jab (0-100). */
    public static final String KEY_TEASE_CHANCE = PREFIX + "tease-chance";

    public static final String PERSONA_CHEEKY = "cheeky";
    public static final String PERSONA_CHILL = "chill";
    public static final String PERSONA_OFF = "off";

    // Cheeky default: ~2 in 3 replies carry a jab.
    private static final String DEFAULT_PERSONA = PERSONA_CHEEKY;
    private static final int DEFAULT_TEASE_PCT = 65;

    private static volatile String persona = DEFAULT_PERSONA;
    private static volatile int teasePct = DEFAULT_TEASE_PCT;

    public static void configure(HostConfig config) {
        if (config == null) {
            persona = DEFAULT_PERSONA;
            teasePct = DEFAULT_TEASE_PCT;
            return;
        }
        persona = normalize(config.getString(KEY_PERSONA, DEFAULT_PERSONA));
        teasePct = Math.max(0, Math.min(100, config.getInt(KEY_TEASE_CHANCE, DEFAULT_TEASE_PCT)));
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return DEFAULT_PERSONA;
        }
        String s = raw.trim().toLowerCase();
        return switch (s) {
            case PERSONA_CHEEKY, PERSONA_CHILL, PERSONA_OFF -> s;
            default -> DEFAULT_PERSONA;
        };
    }

    /** Chance a normal reply carries a jab (0.0 when off). */
    public static double teaseChance() {
        return PERSONA_OFF.equals(persona) ? 0.0 : teasePct / 100.0;
    }

    /** Whether social-intent recognition is on; a fully {@code off} persona disables it too. */
    public static boolean intentEnabled() {
        return !PERSONA_OFF.equals(persona);
    }

    /** The persona a character speaks with, honoring the configured spread. */
    public static Persona personaFor(int characterId) {
        if (PERSONA_OFF.equals(persona)) {
            return Persona.FRIENDLY;
        }
        if (PERSONA_CHILL.equals(persona)) {
            return Persona.ofChill(characterId);
        }
        return Persona.of(characterId);
    }
}
