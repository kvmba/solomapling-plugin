package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;

/*
 * The movement-skill abilities a bot's class grants, as far as the dead-pit verdict needs them: a
 * 2nd-job+ magician can blink (Teleport) out of a pocket, a Hermit/Night Lord can dash (Flash Jump).
 * The verdict is keyed on this value, so a warrior can never be handed a mage's "livable" answer —
 * "a real player can leave" must mean "THIS bot's player can leave".
 *
 * The job tables mirror FlashJumpTiers (dash tiers) and reuse the movement profile's own teleport
 * predicate — one table, because the graph bakes TELEPORT edges from that same predicate.
 */
record BotMovementCapabilities(boolean teleport, float flashJumpScale) {

    static final BotMovementCapabilities NONE = new BotMovementCapabilities(false, 0f);

    // Hermit / Night Lord ids and the dash tiers (see FlashJumpTiers).
    private static final int HERMIT = 411;
    private static final int NIGHT_LORD = 412;
    private static final int HERMIT_MAXED_LEVEL = 100;
    private static final float HERMIT_DEVELOPING_SCALE = 0.75f;

    static BotMovementCapabilities of(Character bot) {
        if (bot == null || bot.getJob() == null) {
            return NONE;
        }
        int jobId = bot.getJob().getId();
        return new BotMovementCapabilities(isTeleportMage(jobId), flashJumpScale(jobId, bot.getLevel()));
    }

    /**
     * Magician 2nd job and above (base Magician 200 has not learned the skill): 210-212 F/P,
     * 220-222 I/L, 230-232 Cleric.
     *
     * <p>Delegates to the movement profile's table so the graph (which bakes TELEPORT edges) and
     * this verdict (which decides whether a pocket is escapable) can never disagree.
     */
    private static boolean isTeleportMage(int jobId) {
        return BotMovementProfile.hasTeleportSkill(jobId);
    }

    private static float flashJumpScale(int jobId, int level) {
        if (jobId == NIGHT_LORD) {
            return 1f;
        }
        if (jobId == HERMIT) {
            return level >= HERMIT_MAXED_LEVEL ? 1f : HERMIT_DEVELOPING_SCALE;
        }
        return 0f;
    }
}
