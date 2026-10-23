package soloMapling.ArtificialPlayer.BotTypes.Dojo;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The room model {@code DojoPQBot} works from, pinned against the host's own script arithmetic.
 *
 * <p>The bug this exists for: the bot's only progress test was "no monsters in the map". Every
 * dojo boss revives into {@code 9300216} (a 2.09B-HP marker flagged {@code invincible}, a field
 * this host never loads), so the map is never empty - the bot fought an invisible wall forever,
 * the door was never tried, and the room was never "cleared". The signals below are what
 * {@code dojang_1st.js} and {@code dojang_up.js} actually read.
 *
 * <p>All values come from {@code scripts/map/onFirstUserEnter/dojang_1st.js}:
 * <pre>
 *   var stage = Math.floor(mapId / 100) % 100;
 *   if (stage % 6 == 0) { ...no boss, it is a resting floor... }
 *   var realstage = stage - ((stage / 6) | 0);
 *   var mob = ms.getMonsterLifeFactory(9300183 + realstage);
 * </pre>
 * Note the offset: stage 1 resolves to {@code 9300184}, because {@code realstage} never reaches the
 * {@code 9300183} placeholder the script would use for stage 0 (a room that is never fought).
 */
class DojoRoomModelTest {

    @Test
    void aRestFloorIsClearAndHasNoBoss() {
        for (int stage = 6; stage <= 36; stage += 6) {
            int mapId = 925030000 + stage * 100;
            assertTrue(DojoPqData.isRestingFloor(mapId), "stage " + stage + " is a resting floor");
            assertEquals(-1, DojoPqData.bossIdFor(mapId), "a resting floor raises nothing");
        }
    }

    @Test
    void aFightingFloorRaisesTheScriptsOwnBoss() {
        assertEquals(9300184, DojoPqData.bossIdFor(925030100), "stage 1: realstage 1");
        assertEquals(9300185, DojoPqData.bossIdFor(925030200), "stage 2: realstage 2");
        assertEquals(9300188, DojoPqData.bossIdFor(925030500), "stage 5: realstage 5");
    }

    /** The cycle skips the rest floor: stage 7 continues at realstage 6, not 7. */
    @Test
    void theBossTableSkipsRestFloors() {
        assertEquals(9300189, DojoPqData.bossIdFor(925030700), "stage 7: realstage 6");
        assertEquals(9300194, DojoPqData.bossIdFor(925031300), "stage 13: realstage 11");
        assertEquals(9300214, DojoPqData.bossIdFor(925033700), "stage 37: realstage 31");
        assertEquals(9300215, DojoPqData.bossIdFor(925033800), "stage 38: realstage 32");
    }

    /**
     * The two tables line up exactly: 38 stages hold 6 resting floors, so 32 fighting floors must
     * cover the 32 boss rows {@code 9300184..9300215} once each. A drift in either the stage or the
     * realstage arithmetic breaks this bijection immediately.
     */
    @Test
    void theFightingFloorsCoverTheBossRowsExactlyOnce() {
        Set<Integer> bosses = new LinkedHashSet<>();
        int fightingFloors = 0;
        for (int stage = 1; stage <= DojoPqData.LAST_STAGE; stage++) {
            int boss = DojoPqData.bossIdFor(925030000 + stage * 100);
            if (DojoPqData.isRestingFloor(925030000 + stage * 100)) {
                assertEquals(-1, boss, "stage " + stage);
                continue;
            }
            fightingFloors++;
            assertTrue(bosses.add(boss), "stage " + stage + " reuses boss " + boss);
        }
        assertEquals(32, fightingFloors, "38 stages minus 6 resting floors");
        assertEquals(32, bosses.size(), "one boss per fighting floor");
        assertEquals(9300184, bosses.iterator().next(), "the weakest room's boss");
        assertEquals(9300215, bosses.stream().reduce((a, b) -> b).orElse(-1), "the top room's boss");
    }

    @Test
    void idsOutsideTheTowerHaveNoBoss() {
        assertEquals(-1, DojoPqData.bossIdFor(925020001), "the hall itself");
        assertEquals(-1, DojoPqData.bossIdFor(925030000), "stage 0");
        assertEquals(-1, DojoPqData.bossIdFor(925033900), "past the tower");
    }

    /** The marker id is the host's own constant, not a magic number in the plugin. */
    @Test
    void theClearMarkerIsTheHostsConstant() {
        assertEquals(org.gms.constants.id.MobId.TRANSPARENT_ITEM, DojoPqData.CLEAR_MARKER);
        assertEquals(9300216, DojoPqData.CLEAR_MARKER);
    }

    /**
     * The marker is NOT a friendly mob in WZ, so a friendliness-only filter keeps hitting it. This
     * is the assertion the previous fix would have failed.
     */
    @Test
    void theMarkerIsExcludedByTargetRulesNotByFriendliness() {
        assertFalse(soloMapling.ArtificialPlayer.BotAttackSystem.BotTargetRules
                        .isCombatTarget(null, DojoPqData.CLEAR_MARKER),
                "the marker must never be a combat target");
        assertTrue(soloMapling.ArtificialPlayer.BotAttackSystem.BotTargetRules
                        .isCombatTarget(null, DojoPqData.bossIdFor(925030100)),
                "the room's real boss must stay attackable");
    }

    /** {@code dojang_next} reads this reactor by name; the name is data, so it is pinned. */
    @Test
    void theDoorReactorIsTheScriptsName() {
        assertEquals("door", DojoPqData.DOOR_REACTOR);
        assertEquals("out001", DojoPqData.SERVER_DOOR);
        assertEquals("dojang_next", DojoPqData.SERVER_DOOR_SCRIPT);
    }

    // The resting-floor geometry (no door reactor, no out00) is pinned in GCMoveSystem's
    // DojoDoorReachabilityTest, which can read the package-private geometry loader; the stage/model
    // arithmetic above is what this test owns.
}
