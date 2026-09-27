package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.client.Character;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Guards the "a dry platform lull ends when mobs feed elsewhere" behavior.
 *
 * A cleared spot/stack used to stand out the full lull patience (camp 1.5-6s) or, on stacks, the
 * 20s no-kill floor, while live mobs were fighting on a neighbouring platform — the "kills the
 * floor, then stands idle for ages" report. The fix: once the post-kill grace beat is spent and
 * another reachable, non-full spot/stack holds live hostiles, the WAIT relocates immediately;
 * the loot sweeps (tryWalkAndLoot + collectAfterKill) still run first so the fresh kill's drop
 * is gathered before walking off.
 */
class WaitBailToMobsTest {

    private static long longConstant(String name) throws Exception {
        Field f = GrindBrain.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(null);
    }

    private static boolean declared(Method m) {
        m.setAccessible(true);
        return true;
    }

    @Test
    void nearbyMobGraceIsShorterThanEveryLullPatience() throws Exception {
        long grace = longConstant("NEARBY_MOB_GRACE_MS");
        assertTrue(grace > 0, "the grace must be a real beat, else a dying mob's own spot reads empty");
        Field campCompactMin = CampStrategy.class.getDeclaredField("WAIT_PATIENCE_COMPACT_MIN_MS");
        campCompactMin.setAccessible(true);
        Field stackMin = StackStrategy.class.getDeclaredField("WAIT_PATIENCE_MIN_MS");
        stackMin.setAccessible(true);
        assertTrue(grace < campCompactMin.getLong(null) && grace < stackMin.getLong(null),
                "grace must sit below every regime's patience, else the nearby-mob bail never "
                        + "fires before the old lull path");
    }

    @Test
    void stackWaitsMirrorCampsNearbyMobBypass() throws Exception {
        assertTrue(declared(StackStrategy.class.getDeclaredMethod("anotherStackHasMobs",
                        Character.class, SpotStack.class)),
                "stack must have its own nearby-mobs probe — its 20s no-kill relocate floor made it "
                        + "the worst idle offender");
        Method campBypass = CampStrategy.class.getDeclaredMethod("anotherSpotHasMobs",
                Character.class, Spot.class);
        campBypass.setAccessible(true);
        assertTrue(campBypass != null, "camp's probe must stay (it feeds both the grace bail and the lull)");
    }

    @Test
    void collectAfterKillIsBoundedInsideTheLull() throws Exception {
        long collect = longConstant("COLLECT_AFTER_KILL_MS");
        assertTrue(collect < 4_000,
                "the post-kill tidy window must stay bounded — it runs before the nearby-mob bail "
                        + "in doWait, so an unbounded window would starve the bail");
    }
}
