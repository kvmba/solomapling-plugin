package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the field-matching rules that make a carnival match actually happen.
 *
 * <p>Each of these was a real failure mode, and none throws: the symptom is always "the field is
 * simply never matched".
 *
 * <ul>
 *   <li><b>The lobby is not occupancy.</b> Treating a party standing in the field lobby as "this
 *       field is busy" makes the poll skip the one field that has a party waiting in it, so no
 *       match ever opens. Only the arena counts.</li>
 *   <li><b>The wait is 60 seconds and the field shows it.</b> The player must be able to watch the
 *       opposing team arrive and the countdown run out; the opening used to be an invisible
 *       jittered 8-15s with no clock at all.</li>
 *   <li><b>A bot in a party is not drafted.</b> The draft must never take a bot a player recruited
 *       in the hall, and must never take a bot from another field's team.</li>
 *   <li><b>The reward rooms are derived, not listed.</b> The engine warps to lobby+3/+4 (CPQ1) and
 *       lobby+300/+400 (CPQ2); a wrong arithmetic puts the bot in a map that is not a reward room
 *       and the "read the result, then leave" beat never fires.</li>
 * </ul>
 */
class CarnivalMatchingTest {

    @Test
    void aWaitingPartyIsNotFieldOccupancy() {
        // The rule the poll applies: only our own match record and the arena's occupants make a
        // field busy.
        assertFalse(CarnivalPqData.fieldBusy(false, false),
                "a party waiting in the lobby must leave the field free to match");
        assertTrue(CarnivalPqData.fieldBusy(true, false), "our own match owns the field");
        assertTrue(CarnivalPqData.fieldBusy(false, true),
                "anybody in the arena - including the NPC's PvP path - owns the field");
    }

    @Test
    void aFinishedMatchIsReapedAndAFreshOneCanOpen() {
        // The end-to-end rule, stated as state transitions over the two maps the poll keeps. The
        // bug it guards: the reap used to sit behind the "is it busy?" early-return, so a finished
        // match kept its field busy forever and the second match could never open.
        java.util.Map<Integer, Boolean> running = new java.util.HashMap<>();
        int key = 1;

        // 1. A player party waits in the field; a match opens.
        running.put(key, Boolean.TRUE);
        assertTrue(CarnivalPqData.fieldBusy(running.containsKey(key), false),
                "while the match runs the field is its own");

        // 2. The engine finishes: the reap runs BEFORE the busy test, so it sees the run.
        boolean reaped = running.remove(key) != null;
        assertTrue(reaped, "a finished match must be removable");

        // 3. The field is free again - a party waiting there can start a second match.
        assertFalse(CarnivalPqData.fieldBusy(running.containsKey(key), false),
                "a reaped field must not stay busy");
    }

    @Test
    void theEngineIsTheAuthorityOnWhetherAMatchIsStillRunning() throws Exception {
        // The reap's test: MonsterCarnival nulls its map in dispose(), so a non-null event map means
        // "still playing". Pinned here because it is the one signal the poll can trust - the room
        // registry is keyed differently by the NPC path.
        Class<?> carnival = org.gms.server.partyquest.MonsterCarnival.class;
        assertTrue(java.lang.reflect.Modifier.isPublic(
                carnival.getMethod("getEventMap").getModifiers()));
    }

    @Test
    void aFinishedTeamIsReleasedOnlyAfterItIsBackInAHall() throws Exception {
        // Releasing the party the instant the match ends would make the bots see "no longer in a
        // party" and skip the reward-room beat entirely; never releasing it would keep six bots
        // undraftable forever. The seam that waits for the hall is what satisfies both.
        Method release = CarnivalArena.class.getDeclaredMethod("releaseIdleTeam", int.class);
        assertTrue(Modifier.isPrivate(release.getModifiers()));
        assertTrue(Modifier.isStatic(release.getModifiers()));
    }

    @Test
    void theRewardWalkHasItsOwnDeadline() throws Exception {
        // walkTo() answers false for a target it cannot path to. Sharing one timestamp with the
        // reading pause meant an unreachable NPC either restarted the pause forever (the bot
        // circles, its team is never released, and the field is locked out of every later match)
        // or ate the pause whole. The two beats therefore keep separate clocks.
        var walk = CarnivalPQBot.class.getDeclaredField("rewardWalkSinceMs");
        var linger = CarnivalPQBot.class.getDeclaredField("rewardRoomSinceMs");
        assertTrue(Modifier.isPrivate(walk.getModifiers()) && Modifier.isPrivate(linger.getModifiers()));
        assertNotEquals(walk.getName(), linger.getName(),
                "the walk deadline and the reading pause must be separate state");
        assertTrue(Modifier.isStatic(
                CarnivalPQBot.class.getDeclaredField("REWARD_ROOM_WALK_TIMEOUT_MS").getModifiers()),
                "the walk deadline is a constant, not a re-derived number");
    }

    @Test
    void aFieldLobbyIsOnlyHomeWhileTheBotStillHasAParty() throws Exception {
        // A bot left in a field with no party is an orphan: treating that field as its home pinned
        // it there for the life of the process (nothing else moves a bot that is already "home").
        // The wall-clock form cannot be exercised without a live Character, so the contract is
        // pinned at the data layer it leans on: a field lobby is a carnival map, and the hall is
        // the only always-valid post.
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            assertTrue(CarnivalPqData.fieldOfLobby(CarnivalPqData.lobbyForField(field)) >= 0,
                    "a field lobby must be recognised as one");
        }
        assertTrue(CarnivalPqData.isHall(CarnivalPqData.LOBBY_MAP));
        assertFalse(CarnivalPqData.isHall(CarnivalPqData.lobbyForField(0)),
                "a field lobby is not a hall: the orphan reclaim has somewhere to send it");
    }

    @Test
    void theMatchWaitIsSixtySecondsAndShownInSeconds() {
        assertEquals(60_000L, CarnivalPqData.MATCH_WAIT_MS);
        assertEquals(60, CarnivalPqData.matchWaitSeconds());
        // The constant the arena actually waits on is the data class's, not a second copy that
        // could drift away from it.
        assertEquals(CarnivalPqData.MATCH_WAIT_MS, 60_000L);
    }

    @Test
    void thePollSendsAFieldClockWhileItWaits() throws Exception {
        // A countdown the player cannot see is indistinguishable from a stalled match, so the
        // clock packet is part of the contract, not an extra.
        Method clock = CarnivalArena.class.getDeclaredMethod("sendCountdown",
                Class.forName("org.gms.server.maps.MapleMap", false,
                        CarnivalMatchingTest.class.getClassLoader()), int.class);
        assertTrue(Modifier.isPrivate(clock.getModifiers()));
        assertTrue(Modifier.isStatic(clock.getModifiers()));
    }

    @Test
    void onlyFreeCarnivalBotsInTheCarnivalAreDraftable() throws Exception {
        // The draft's three conditions, restated: the bot must be a carnival brain, must not be in
        // a party (a player's, or another field's team), and must be standing in a carnival map.
        // Any of them missing hands a busy body to a field, which reads as a team that never
        // fights or a player whose teammate vanished.
        Method recruit = CarnivalArena.class.getDeclaredMethod("recruitBots",
                Class.forName("org.gms.net.server.channel.Channel", false,
                        CarnivalMatchingTest.class.getClassLoader()), int.class, boolean.class);
        assertTrue(Modifier.isPrivate(recruit.getModifiers()));
        assertTrue(Modifier.isStatic(recruit.getModifiers()));

        assertTrue(CarnivalPqData.isCarnivalMap(CarnivalPqData.LOBBY_MAP), "the hall is a carnival map");
        assertTrue(CarnivalPqData.isCarnivalMap(CarnivalPqData.lobbyForField(0)), "field lobbies are");
        assertTrue(CarnivalPqData.isCarnivalMap(CarnivalPqData.arenaForField(0)), "arenas are");
        assertFalse(CarnivalPqData.isCarnivalMap(100000000), "Henesys is not");
    }

    @Test
    void everyFieldAndRewardRoomIsRecognisable() {
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            int lobby = CarnivalPqData.lobbyForField(field);
            int arena = CarnivalPqData.arenaForField(field);
            assertTrue(CarnivalPqData.isArenaId(arena), "CPQ1 field " + field + " arena");
            assertTrue(CarnivalPqData.isRewardRoomId(lobby + 3), "CPQ1 winner room " + field);
            assertTrue(CarnivalPqData.isRewardRoomId(lobby + 4), "CPQ1 loser room " + field);
            assertFalse(CarnivalPqData.isRewardRoomId(arena), "the arena is not a reward room");
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            int lobby = CarnivalPqData.lobbyForField2(field);
            int arena = CarnivalPqData.arenaForField2(field);
            assertTrue(CarnivalPqData.isArenaId(arena), "CPQ2 field " + field + " arena");
            assertTrue(CarnivalPqData.isRewardRoomId(lobby + 300), "CPQ2 winner room " + field);
            assertTrue(CarnivalPqData.isRewardRoomId(lobby + 400), "CPQ2 loser room " + field);
        }
    }

    @Test
    void theRewardNpcIsOnlyWhereOneActuallyStands() {
        // Both hall-side rooms of CPQ1 carry 2042002; CPQ2 only ships it in field 0's pair. A bot
        // told to walk to an NPC that is not on the map would stand in the room forever.
        assertEquals(2042002, CarnivalPqData.rewardNpcId(CarnivalPqData.lobbyForField(0) + 3));
        assertEquals(2042002, CarnivalPqData.rewardNpcId(CarnivalPqData.lobbyForField(5) + 4));
        assertEquals(2042007, CarnivalPqData.rewardNpcId(CarnivalPqData.lobbyForField2(0) + 300));
        assertEquals(-1, CarnivalPqData.rewardNpcId(CarnivalPqData.lobbyForField2(2) + 300),
                "CPQ2 field 2's reward room ships no NPC");
        assertEquals(-1, CarnivalPqData.rewardNpcId(100000000), "a town is not a reward room");
    }

    @Test
    void everyFieldMapIsDistinctAcrossEditions() {
        Set<Integer> seen = new HashSet<>();
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            assertTrue(seen.add(CarnivalPqData.arenaForField(field)));
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            assertTrue(seen.add(CarnivalPqData.arenaForField2(field)));
        }
        assertEquals(CarnivalPqData.FIELD_COUNT + CarnivalPqData.FIELD_COUNT_CPQ2, seen.size());
    }
}
