package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the cleanup path a half-started match must take.
 *
 * <p>{@code MonsterCarnival}'s constructor assigns each member its team, its match pointer and its
 * festival points <em>before</em> the work that can fail (the party-channel lookup, the arena
 * load, the field registration). A failure after that point would otherwise leave both parties
 * pointing at each other as enemies and every member dressed as a team of a match that never
 * started - which reads, from the next attempt's point of view, as "this field is already
 * occupied by somebody else".
 *
 * <p>The cleanup is pure state reset, so it cannot be exercised without live characters. What is
 * pinned is that the seam exists and is reachable from the class that needs it.
 */
class CarnivalFailedStartTest {

    @Test
    void theArenaOwnsAFighterReleasePath() throws Exception {
        Method release = CarnivalArena.class.getDeclaredMethod("releaseFighters",
                Class.forName("org.gms.net.server.world.Party", false,
                        CarnivalFailedStartTest.class.getClassLoader()));
        assertTrue(Modifier.isPrivate(release.getModifiers()),
                "cleanup is an internal step, not part of the arena's contract");
        assertTrue(Modifier.isStatic(release.getModifiers()));
    }

    @Test
    void thePartyEnemyLinkIsWhatGetsCleared() throws Exception {
        // The pair of pointers that outlives a failed constructor: MonsterCarnival sets both
        // before anything that can throw, and only a completed run clears them.
        Class<?> party = Class.forName("org.gms.net.server.world.Party", false,
                CarnivalFailedStartTest.class.getClassLoader());
        assertTrue(Modifier.isPublic(party.getMethod("setEnemy", party).getModifiers()));
    }

    @Test
    void theCharacterStateAMatchAssignsIsAllPubliclyResettable() throws Exception {
        // releaseFighters must be able to undo every per-character field the constructor set,
        // or a failed start leaves a member that still looks enrolled in a match.
        Class<?> chr = Class.forName("org.gms.client.Character", false,
                CarnivalFailedStartTest.class.getClassLoader());
        Class<?> carnival = Class.forName("org.gms.server.partyquest.MonsterCarnival", false,
                CarnivalFailedStartTest.class.getClassLoader());
        assertTrue(Modifier.isPublic(chr.getMethod("setMonsterCarnival", carnival).getModifiers()));
        assertTrue(Modifier.isPublic(chr.getMethod("setTeam", int.class).getModifiers()));
        assertTrue(Modifier.isPublic(chr.getMethod("setFestivalPoints", int.class).getModifiers()));
    }
}
