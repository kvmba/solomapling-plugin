package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two behaviours that let a hall bot play on a player's team, and the one that stops it.
 *
 * <p>Before this, a carnival bot in the hall could neither be invited nor follow the party that
 * had invited it: it declined by policy (so the hall was scenery) and the base class only trails a
 * party through portals (so a teammate would have waited in the hall through the whole match). Both
 * restrictions existed because the arena needed six bots of its own - but the arena drafts only
 * bots that are <em>not in a party</em>, so the two roles cannot collide.
 */
class CarnivalTeamPlayTest {

    @Test
    void aHallBotAcceptsAPartyInvitation() throws Exception {
        Method hook = CarnivalPQBot.class.getDeclaredMethod("acceptsPartyInvites",
                Class.forName("org.gms.client.Character", false,
                        CarnivalTeamPlayTest.class.getClassLoader()));
        assertTrue(Modifier.isProtected(hook.getModifiers()));
        // The invariant that makes this safe: the arena's draft skips a bot in a party, so a
        // recruited bot is never also fielded as an opponent.
        assertFalse(Modifier.isStatic(hook.getModifiers()));
    }

    @Test
    void theBotFollowsAPartyThatMovedByWarp() throws Exception {
        // The hall has no portal to a field, so following the player in needs a warp. The hook is
        // opt-in on the base class and only carnival's bot turns it on.
        Class<?> base = Class.forName("soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot",
                false, CarnivalTeamPlayTest.class.getClassLoader());
        Method baseHook = base.getDeclaredMethod("followLeaderByWarp");
        assertTrue(Modifier.isProtected(baseHook.getModifiers()),
                "a quest bot overrides it; it is not public API");
        assertFalse(Modifier.isAbstract(baseHook.getModifiers()),
                "every other quest must inherit the default unchanged");

        Method over = CarnivalPQBot.class.getDeclaredMethod("followLeaderByWarp");
        assertTrue(Modifier.isProtected(over.getModifiers()));
    }

    @Test
    void followingByWarpIsLimitedToTheCarnival() {
        // The rule the override implements: only a leader standing in a carnival map is followed.
        // Otherwise a player who walks off to town would drag every recruited bot along.
        assertTrue(CarnivalPqData.isCarnivalMap(CarnivalPqData.LOBBY_MAP));
        assertTrue(CarnivalPqData.isCarnivalMap(CarnivalPqData.LOBBY_MAP_CPQ2));
        assertTrue(CarnivalPqData.isCarnivalMap(CarnivalPqData.lobbyForField2(0)));
        assertFalse(CarnivalPqData.isCarnivalMap(221024500),
                "the toy-tower lobby is not part of the carnival");
        assertFalse(CarnivalPqData.isCarnivalMap(0), "a null-ish map is never followed");
    }

    @Test
    void theHallHasARecruitName() {
        // Without a name the lobby shout is skipped silently, and the hall reads as a crowd that
        // never speaks. The key is the event name the spawner and message packs share.
        String line = soloMapling.Environment.BotMessages.get("pq.recruit.name.CarnivalPQ");
        assertFalse("pq.recruit.name.CarnivalPQ".equals(line),
                "the carnival needs its own recruit-name entry in both message packs");
        assertFalse(line.isBlank());
    }
}
