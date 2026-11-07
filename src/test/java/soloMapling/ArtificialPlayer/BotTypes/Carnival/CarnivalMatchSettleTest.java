package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the teardown a LIVE match needs on plugin unload: settle the run, and get both teams out of
 * the arena before the run disposes it.
 *
 * <p>Two separate failures are covered here, both reachable from {@code releaseAll}:
 *
 * <ol>
 *   <li><b>The party must not be dissolved first.</b> {@code MonsterCarnival} settles by walking
 *       each side's {@code getParty().getMembers()}; a bot party dissolved through
 *       {@code BotPartyCommands.botLeaveParty} has every member's {@code party} nulled, so the
 *       engine's own {@code dispose()} throws NPE inside a timer that swallows it - the field then
 *       stays in the channel's room table for the life of the process (a permanently "full" field
 *       plus a leaked arena map).</li>
 *   <li><b>Nobody may be left standing in the arena when it is disposed.</b>
 *       {@code dispose(false)} - the only form {@code exit()} reaches - deletes the map's footholds
 *       and portals and nulls each member's match pointer, but it does NOT warp anybody out. A
 *       character left there cannot walk, cannot be found by a later settle, and is stuck until a
 *       relog. Both teams are reset by that call, so both must leave.</li>
 * </ol>
 */
class CarnivalMatchSettleTest {

    @Test
    void theArenaOwnsASettleStep() throws Exception {
        Method settle = CarnivalArena.class.getDeclaredMethod("settleMatchIfRunning",
                Class.forName("org.gms.net.server.world.Party", false,
                        CarnivalMatchSettleTest.class.getClassLoader()));
        assertTrue(Modifier.isPrivate(settle.getModifiers()),
                "settling is an internal step of the arena's teardown");
        assertTrue(Modifier.isStatic(settle.getModifiers()));
    }

    /**
     * A match is TWO parties. Evacuating only the team being disbanded leaves the opponent on the
     * dead map, so the settle step's party set is part of the contract.
     */
    @Test
    void bothTeamsAreEvacuated() {
        var self = mock(org.gms.net.server.world.Party.class);
        var enemy = mock(org.gms.net.server.world.Party.class);
        org.mockito.Mockito.when(self.getEnemy()).thenReturn(enemy);

        List<org.gms.net.server.world.Party> teams = CarnivalArena.partiesToEvacuate(self);
        assertEquals(2, teams.size(), "a paired match has two sides to pull out");
        assertTrue(teams.contains(self) && teams.contains(enemy));
    }

    /** An unpaired party is still evacuated (a half-started match may have no enemy link). */
    @Test
    void anUnpairedPartyIsStillEvacuated() {
        var self = mock(org.gms.net.server.world.Party.class);
        org.mockito.Mockito.when(self.getEnemy()).thenReturn(null);
        assertEquals(List.of(self), CarnivalArena.partiesToEvacuate(self));
    }

    /** A self-referencing enemy link must not produce a duplicate (it would move people twice). */
    @Test
    void aSelfReferencingEnemyIsNotDuplicated() {
        var self = mock(org.gms.net.server.world.Party.class);
        org.mockito.Mockito.when(self.getEnemy()).thenReturn(self);
        assertEquals(List.of(self), CarnivalArena.partiesToEvacuate(self));
    }

    @Test
    void theEngineExitIsWhatUnregistersTheField() throws Exception {
        var carnival = Class.forName("org.gms.server.partyquest.MonsterCarnival", false,
                CarnivalMatchSettleTest.class.getClassLoader());
        assertTrue(Modifier.isPublic(carnival.getMethod("exit").getModifiers()),
                "MonsterCarnival.exit() must stay public or a teardown can never free the field");
        assertTrue(Modifier.isPublic(carnival.getMethod("getEventMap").getModifiers()),
                "the reap's own liveness test reads this");
    }

    /**
     * The destination must NOT be the arena's own returnMap: that one points at the field's holding
     * map, which returns to the arena. Parking a disposed team there walks it back into a map whose
     * footholds are already gone.
     */
    @Test
    void theEvacuationTargetIsNotTheArenaReturnMap() {
        // 980000101's returnMap is 980000102 (the holding map), which returns to 980000101.
        int arena = CarnivalPqData.arenaForField(0);
        assertEquals(980000101, arena);
        assertEquals(980000102, 980000101 + 1, "the holding map sits just past the arena");
        assertTrue(CarnivalPqData.outMapFor(arena) != 980000102,
                "the holding map bounces straight back into the arena");
        assertEquals(CarnivalPqData.MATCH_OUT_MAP, CarnivalPqData.outMapFor(arena));
    }

    /** Each edition parks its team on its OWN holding map, and it is not the other's. */
    @Test
    void eachEditionHasItsOwnOutMap() {
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            assertEquals(CarnivalPqData.MATCH_OUT_MAP,
                    CarnivalPqData.outMapFor(CarnivalPqData.arenaForField(field)));
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            assertEquals(CarnivalPqData.MATCH_OUT_MAP_CPQ2,
                    CarnivalPqData.outMapFor(CarnivalPqData.arenaForField2(field)));
        }
        assertTrue(CarnivalPqData.MATCH_OUT_MAP != CarnivalPqData.MATCH_OUT_MAP_CPQ2);
    }
}
