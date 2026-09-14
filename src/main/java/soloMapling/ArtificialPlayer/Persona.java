package soloMapling.ArtificialPlayer;

/**
 * A town bot's chat personality. Weighted toward the server-culture "trash-talker": most bots are
 * cheeky, a minority are chill or friendly, so a crowd reads like real players rather than a chorus.
 *
 * <p>Chosen once from the character id, so the SAME bot always talks the same way - a real person
 * does not switch from polite to toxic between two lines, and a stable personality is most of what
 * makes the banter read as a person rather than as random flavour text.
 */
public enum Persona {

    /** Cheeky, quick to jab - the default server-culture mouth. */
    TEASE("嘴欠、爱调侃，损人不带恶意，只损游戏里的事（等级/装备/手速/宠物/公会），不扯现实"),
    /** Deadpan and needling; answers with a rhetorical question. */
    SARCASTIC("阴阳怪气、爱反问、棒读式敷衍"),
    /** Loud and meme-heavy. */
    HYPE("网感重、梗多、情绪高（就这/破防/蚌埠住了/退退退/家人们谁懂啊）"),
    /** Laid-back, sometimes cheeky but never heavy. */
    CASUAL("随意、口语化、偶尔嘴欠但不过分"),
    /** Unbothered; lets most pokes slide. */
    CHILL("佛系、慢悠悠、懒得争"),
    /** The rare nice one - a real crowd has a few. */
    FRIENDLY("温和友善、有礼貌");

    private final String style;

    Persona(String style) {
        this.style = style;
    }

    /** One-line style hint, injected into the LLM system prompt. */
    public String style() {
        return style;
    }

    // Server-culture spread: cheeky dominates, friendly is the exception (12 slots, TEASE=42%).
    private static final Persona[] WEIGHTED = {
            TEASE, TEASE, TEASE, TEASE, TEASE,
            CASUAL, CASUAL,
            HYPE, HYPE,
            SARCASTIC,
            CHILL,
            FRIENDLY
    };

    // "chill" style: same spread, no heavy toxins - friendly/casual/laid-back dominate.
    private static final Persona[] CHILL_WEIGHTED = {
            FRIENDLY, FRIENDLY, FRIENDLY,
            CASUAL, CASUAL, CASUAL,
            CHILL, CHILL,
            HYPE,
            TEASE,
            SARCASTIC
    };

    /** Stable persona for a character id (default server-culture spread). */
    public static Persona of(int characterId) {
        return WEIGHTED[Math.floorMod(characterId, WEIGHTED.length)];
    }

    /** Stable persona for a character id, softened (the {@code chill} spread). */
    public static Persona ofChill(int characterId) {
        return CHILL_WEIGHTED[Math.floorMod(characterId, CHILL_WEIGHTED.length)];
    }

    /** True for personas that volunteer a jab in normal chat (not just when provoked). */
    public boolean cheeky() {
        return this == TEASE || this == SARCASTIC || this == HYPE || this == CASUAL;
    }

    /** True for personas whose cheek can escalate to the harsh pool. */
    public boolean harsh() {
        return this == TEASE || this == SARCASTIC;
    }
}
