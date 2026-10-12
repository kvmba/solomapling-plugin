package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;

/*
 * The movement-skill abilities a bot's class grants, as far as the dead-pit verdict needs them: a
 * 2nd-job+ magician can blink (Teleport) out of a pocket, a Hermit/Night Lord can dash (Flash Jump).
 * The verdict is keyed on this value, so a warrior can never be handed a mage's "livable" answer —
 * "a real player can leave" must mean "THIS bot's player can leave".
 *
 * The job tables mirror MovementStylePolicy (teleport mages) and FlashJumpTiers (dash tiers);
 * BotMovementCapabilitiesTest pins the two against drift.
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

    // Magician 2nd job and above (base Magician 200 has no Teleport): 210-212 F/P, 220-222 I/L, 230-232 Cleric.
    private static boolean isTeleportMage(int jobId) {
        return (jobId >= 210 && jobId <= 212) || (jobId >= 220 && jobId <= 222) || (jobId >= 230 && jobId <= 232);
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
