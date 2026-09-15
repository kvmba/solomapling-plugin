package soloMapling.ArtificialPlayer.BotTypes.Dojo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Mu Lung Dojo's entry rule, which is the part of that quest that can go wrong
 * invisibly.
 *
 * <p>The dojo does not have a level floor and ceiling like the other quests. It requires every
 * member to be within thirty levels of every other, so a well-levelled bot can still be the
 * reason a party is refused - and the refusal message blames the party's levels, not the bot.
 * Getting this backwards would produce a bot that looks correct on paper and quietly prevents
 * the run it was added to.
 */
class DojoLevelSpreadTest {

    @Test
    void aTightlyLevelledpartyIsWithinTheSpread() {
        assertTrue(DojoPqData.levelsWithinSpread(List.of(60, 70, 80)));
        assertTrue(DojoPqData.levelsWithinSpread(List.of(60)));
    }

    @Test
    void exactlyThirtyApartIsAllowed() {
        // The rule is "within 30 levels", so the boundary itself passes.
        assertTrue(DojoPqData.levelsWithinSpread(List.of(60, 90)));
    }

    @Test
    void moreThanThirtyApartIsRefused() {
        assertFalse(DojoPqData.levelsWithinSpread(List.of(60, 91)));
    }

    @Test
    void theSpreadIsMeasuredAcrossTheWholePartyNotNeighbours() {
        // 40, 70, 100: each step is 30, but the ends are 60 apart, so the party is refused.
        // A pairwise check would wrongly accept it.
        assertFalse(DojoPqData.levelsWithinSpread(List.of(40, 70, 100)));
    }

    @Test
    void aBotOutsideTheBandIsNamedAsTheBlocker() {
        // The check worth running before adding a bot, not after a refused entry.
        assertTrue(DojoPqData.wouldBlockParty(120, List.of(60, 70)));
        assertFalse(DojoPqData.wouldBlockParty(75, List.of(60, 70)));
    }

    @Test
    void anEmptyPartyIsNotEnterable() {
        assertFalse(DojoPqData.levelsWithinSpread(List.of()));
        assertFalse(DojoPqData.levelsWithinSpread(null));
    }

    @Test
    void theSlotsMatchWhatTheChannelHandsOut() {
        // Five party slots and fifteen solo ones, as Channel.ingressDojo allocates. A bot party
        // takes one of the five, which is why this bot does not enter on its own.
        org.junit.jupiter.api.Assertions.assertEquals(5, DojoPqData.PARTY_SLOTS);
        org.junit.jupiter.api.Assertions.assertEquals(15, DojoPqData.SOLO_SLOTS);
    }
}
