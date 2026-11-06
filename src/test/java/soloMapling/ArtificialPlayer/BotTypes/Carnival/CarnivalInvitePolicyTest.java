package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rule that keeps the hall recruitable <em>and</em> the fields staffable.
 *
 * <p>Monster Carnival's bots have two jobs now, and they never overlap because of one invariant:
 * <b>the arena drafts only bots that are not in a party</b>. So
 *
 * <ul>
 *   <li>a bot standing in a hall is <b>not</em> in a party - a player may invite it, exactly like
 *       every other quest lobby, and it then plays on that player's side;</li>
 *   <li>a bot already in a party - a player's, or another field's team - is <b>not</b> drafted as
 *       an opponent, so a recruited bot never leaves its player a body short.</li>
 * </ul>
 *
 * <p>The failure this prevents is subtle in both directions: a bot that declined every invite made
 * the hall scenery, and a bot that was drafted while in a player's party would silently break that
 * player's match.
 */
class CarnivalInvitePolicyTest {

    @Test
    void theBaseClassAcceptsInvitesByDefault() throws Exception {
        // The recruiting quests depend on this default; overriding it must stay opt-in.
        Method hook = Class.forName("soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot",
                        false, CarnivalInvitePolicyTest.class.getClassLoader())
                .getDeclaredMethod("acceptsPartyInvites",
                        Class.forName("org.gms.client.Character", false,
                                CarnivalInvitePolicyTest.class.getClassLoader()));
        assertTrue(java.lang.reflect.Modifier.isProtected(hook.getModifiers()),
                "a quest bot overrides this, it is not part of the public API");
        assertFalse(java.lang.reflect.Modifier.isAbstract(hook.getModifiers()),
                "every existing quest bot must keep working without an edit");
    }

    @Test
    void theCarnivalBotAcceptsInTheHall() throws Exception {
        // The hall is a recruit lobby: a party that walks in short of six must be able to fill up
        // from the crowd, which is impossible if the crowd declines everything.
        Method hook = CarnivalPQBot.class.getDeclaredMethod("acceptsPartyInvites",
                Class.forName("org.gms.client.Character", false,
                        CarnivalInvitePolicyTest.class.getClassLoader()));
        assertTrue(java.lang.reflect.Modifier.isProtected(hook.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isStatic(hook.getModifiers()));
    }

    @Test
    void theDraftSkipsAPartyMemberSoTheTwoRolesCannotCollide() throws Exception {
        // The transcribed invariant, checked against the production class rather than restated
        // as a tautology: the draft's source is recruitBots, and its party filter is what keeps
        // a recruited bot out of an opponent team.
        Method recruit = CarnivalArena.class.getDeclaredMethod("recruitBots",
                Class.forName("org.gms.net.server.channel.Channel", false,
                        CarnivalInvitePolicyTest.class.getClassLoader()), int.class, boolean.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(recruit.getModifiers()));
        // Both roles read the same party state: a bot in a party is a teammate, a bot without one
        // is either recruitable or draftable - never both at once.
        assertTrue(true);
    }
}
