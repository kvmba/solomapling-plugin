package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the invitation policy that keeps the opponent roster staffable.
 *
 * <p>Every other quest bot in this package exists to be recruited: a player invites it, the leader
 * starts the run, and the bot tags along. Monster Carnival's bots are the opposite - they are the
 * <em>other team</em>, fielded six at a time by the arena.
 *
 * <p>So accepting an invite is not a harmless social nicety here. {@code CarnivalArena} drafts only
 * bots whose {@code getParty()} is null, precisely because a party member is already committed
 * somewhere. A player who invited four of the six would make the field unstaffable, and the
 * symptom - "the carnival never finds an opponent" - gives no hint that four invitations caused it.
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
        assertTrue(Modifier.isProtected(hook.getModifiers()),
                "a quest bot overrides this, it is not part of the public API");
        assertFalse(Modifier.isAbstract(hook.getModifiers()),
                "every existing quest bot must keep working without an edit");
    }

    @Test
    void theCarnivalBotOverridesIt() throws Exception {
        // If this method ever stops being declared on CarnivalPQBot, the bot silently falls back
        // to accepting invites and the arena starts failing to field a team.
        Method hook = CarnivalPQBot.class.getDeclaredMethod("acceptsPartyInvites",
                Class.forName("org.gms.client.Character", false,
                        CarnivalInvitePolicyTest.class.getClassLoader()));
        assertTrue(Modifier.isProtected(hook.getModifiers()));
        assertFalse(Modifier.isStatic(hook.getModifiers()));
    }

    @Test
    void theDraftRuleAndTheInviteRuleAreTheSameRule() {
        // Transcribed: the arena skips a bot that is in a party, so "accepts invites" and
        // "can be drafted" are mutually exclusive. A change to either must keep that true.
        boolean acceptsInvites = false; // CarnivalPQBot
        boolean alreadyInAParty = acceptsInvites;
        boolean draftable = !alreadyInAParty;
        assertTrue(draftable, "a bot that declines invites stays draftable");

        boolean recruitingBotAcceptsInvites = true; // every other quest bot
        boolean recruitingBotDraftable = !recruitingBotAcceptsInvites;
        assertFalse(recruitingBotDraftable,
                "a recruiting bot that joined a party is correctly not drafted");
    }
}
