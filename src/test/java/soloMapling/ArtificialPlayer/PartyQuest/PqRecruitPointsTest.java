package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the recruiting table, which decides whether a quest bot can be found at all.
 *
 * <p>A quest's eligibility check keeps only the members standing in its recruit map and inside
 * its level band, and it does so from a snapshot taken when the leader starts the run. So a bot
 * placed on the wrong map, or levelled outside the band, is not "less effective" - it is
 * invisible, and the run it was meant to join starts without it.
 *
 * <p>Every row here was read out of a quest's event script, which is also where the eligibility
 * check reads it from, so the two cannot drift without a test noticing.
 */
class PqRecruitPointsTest {

    @Test
    void everyQuestWithABotHasARecruitPoint() {
        for (String name : new String[]{
                "HenesysPQ", "KerningPQ", "LudiPQ", "PiratePQ", "AmoriaPQ",
                "EllinPQ", "MagatiaPQ", "ZakumPQ", "HorntailPQ", "BossRushPQ", "OrbisPQ"}) {
            assertNotNull(PqRecruitPoints.byName(name), name + " has no recruit point");
        }
    }

    @Test
    void theRecruitMapsAreTheOnesTheQuestsName() {
        // Straight from each event script's recruitMap.
        assertEquals(100000200, PqRecruitPoints.byName("HenesysPQ").recruitMap());
        assertEquals(103000000, PqRecruitPoints.byName("KerningPQ").recruitMap());
        assertEquals(221024500, PqRecruitPoints.byName("LudiPQ").recruitMap());
        assertEquals(251010404, PqRecruitPoints.byName("PiratePQ").recruitMap());
        assertEquals(670010100, PqRecruitPoints.byName("AmoriaPQ").recruitMap());
        assertEquals(300030100, PqRecruitPoints.byName("EllinPQ").recruitMap());
        assertEquals(261000021, PqRecruitPoints.byName("MagatiaPQ").recruitMap());
        assertEquals(211042300, PqRecruitPoints.byName("ZakumPQ").recruitMap());
        assertEquals(240050000, PqRecruitPoints.byName("HorntailPQ").recruitMap());
        assertEquals(970030000, PqRecruitPoints.byName("BossRushPQ").recruitMap());
        assertEquals(200080101, PqRecruitPoints.byName("OrbisPQ").recruitMap());
    }

    @Test
    void theLevelBandsMatchTheQuestsOwnNumbers() {
        assertEquals(10, PqRecruitPoints.byName("HenesysPQ").minLevel());
        assertEquals(21, PqRecruitPoints.byName("KerningPQ").minLevel());
        assertEquals(30, PqRecruitPoints.byName("KerningPQ").maxLevel());
        assertEquals(35, PqRecruitPoints.byName("LudiPQ").minLevel());
        assertEquals(50, PqRecruitPoints.byName("LudiPQ").maxLevel());
        assertEquals(71, PqRecruitPoints.byName("MagatiaPQ").minLevel());
        assertEquals(85, PqRecruitPoints.byName("MagatiaPQ").maxLevel());
        assertEquals(120, PqRecruitPoints.byName("HorntailPQ").minLevel());
        assertEquals(51, PqRecruitPoints.byName("OrbisPQ").minLevel());
        assertEquals(70, PqRecruitPoints.byName("OrbisPQ").maxLevel());
    }

    @Test
    void everyBandContainsAtLeastOneLevel() {
        // A band whose floor exceeded its ceiling would make every bot spawned for it
        // ineligible, and the spawn would look successful while producing nothing usable.
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            assertTrue(point.minLevel() <= point.maxLevel(),
                    point.name() + " has an empty level band");
            assertTrue(point.minLevel() >= 1, point.name() + " cannot start below level 1");
        }
    }

    @Test
    void everyQuestWantsAtLeastOneMemberAndTakesWhatItAsksFor() {
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            assertTrue(point.minPlayers() >= 1, point.name() + " wants no members");
            assertTrue(point.minPlayers() <= point.maxPlayers(),
                    point.name() + " wants more members than it accepts");
        }
    }

    @Test
    void theBotsOfferedReachThePartyTheQuestWants() {
        // The case worth covering is a lone player: botsToOffer(1) must bring a party of one
        // up to what the quest's eligibility check demands.
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            int withBots = 1 + point.botsToOffer(1);
            assertTrue(withBots >= point.minPlayers(),
                    point.name() + " would be left short of its own minimum");
            assertTrue(withBots <= point.maxPlayers(),
                    point.name() + " would be handed more members than it accepts");
        }
    }

    @Test
    void aPlayerHeavyPartyGetsNoExtraBots() {
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            assertEquals(0, point.botsToOffer(point.maxPlayers()));
            assertEquals(0, point.botsToOffer(point.maxPlayers() + 3));
        }
    }

    @Test
    void theLevelTestMatchesTheQuestsOwnComparison() {
        // getEligibleParty keeps levels where minLevel <= level <= maxLevel, inclusive on both
        // ends - so the floors and ceilings themselves must pass.
        PqRecruitPoints.Point kerning = PqRecruitPoints.byName("KerningPQ");
        assertTrue(kerning.levelEligible(21));
        assertTrue(kerning.levelEligible(30));
        assertFalse(kerning.levelEligible(20));
        assertFalse(kerning.levelEligible(31));
    }

    @Test
    void anUnknownQuestIsNotSilentlyTreatedAsRecruitable() {
        assertNull(PqRecruitPoints.byName("NotAQuest"));
    }

    @Test
    void aMapWithNothingRecruitingThereReportsNoBand() {
        assertEquals(-1, PqRecruitPoints.sharedLevelBandLow(999999999));
        assertEquals(-1, PqRecruitPoints.sharedLevelBandHigh(999999999));
        assertTrue(PqRecruitPoints.sharingMap(999999999).isEmpty());
    }

    @Test
    void aMapsQuestsAreAllFoundByTheSharedLookup() {
        // Used to place bots for several quests per town; missing one would leave that quest's
        // lobby empty while the log claimed success.
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            assertTrue(PqRecruitPoints.sharingMap(point.recruitMap()).contains(point),
                    point.name() + " is not found by its own map");
        }
        assertTrue(PqRecruitPoints.sharingMap(PqRecruitPoints.ORBIS.recruitMap())
                .contains(PqRecruitPoints.ORBIS), "Orbis is not found by its own map");
    }
}
