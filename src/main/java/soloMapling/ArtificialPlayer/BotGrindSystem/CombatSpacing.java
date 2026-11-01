package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;

/** Shared approach distance and ranged kite pacing for grinding and party quests. */
public final class CombatSpacing {
    private static final double APPROACH_REACH_FRAC = 0.80;
    private static final int KITE_MIN_PX = 150;
    private static final int KITE_STEP_PX = 90;
    private static final long KITE_COOLDOWN_MS = 400;
    private static final int KITE_RETRIGGER_MARGIN_PX = 30;

    private long nextKiteMs;
    private int kiteReenterAtDx = KITE_MIN_PX;

    public static int approachX(Character bot) {
        return Math.max(GrindBrain.APPROACH_X, (int) (BotAttackDriver.attackReachX(bot) * APPROACH_REACH_FRAC));
    }

    public static int approachY(Character bot) {
        return Math.max(GrindBrain.APPROACH_Y, (int) (BotAttackDriver.attackReachY(bot) * APPROACH_REACH_FRAC));
    }

    public void reset() {
        nextKiteMs = 0L;
        kiteReenterAtDx = KITE_MIN_PX;
    }

    /** Zero when comfortable/cooling down; otherwise the same 90px retreat in either mode. */
    public int retreatStep(int dx, long nowMs) {
        return nowMs >= nextKiteMs && dx < KITE_MIN_PX && dx <= kiteReenterAtDx ? KITE_STEP_PX : 0;
    }

    public void retreated(long nowMs) {
        nextKiteMs = nowMs + KITE_COOLDOWN_MS;
        kiteReenterAtDx = KITE_MIN_PX - KITE_RETRIGGER_MARGIN_PX;
    }
}
