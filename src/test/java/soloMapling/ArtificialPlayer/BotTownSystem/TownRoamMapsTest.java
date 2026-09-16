package soloMapling.ArtificialPlayer.BotTownSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shaft-shaped town-map blacklist that keeps roaming/stationed bots out of maps that pass the
 * town test (WZ {@code town=1}, no mobs, one portal hop from a town) but are actually a single
 * full-height rope/ladder with the only floor at the bottom.
 *
 * <p>The two consumers that pick a cross-map DESTINATION by "mob-free neighbour" - TownWandererBot's map
 * family and SocialBot's cross-map stroll - consult {@link TownRoamMaps}. A bot that enters such a shaft
 * reads as a stuck statue (climbs, falls, repeats), and arrivals pile up under the exit.
 *
 * <p>The load-bearing guarantees: both shafts are banned, and the ordinary town rooms a roam legitimately
 * uses (the town itself, its sub-rooms) stay allowed - so a stray entry here would either drop a shaft
 * back into the roam pool or, worse, wall off a real town map. Grinders never consult this list at all
 * (TrainingMapFinder only targets maps that HAVE mobs), so it can't affect them.
 */
class TownRoamMapsTest {

    @Test
    void bansTheTwoShaftMaps() {
        assertTrue(TownRoamMaps.isBanned(222000001), "童话村[井口] (the well shaft)");
        assertTrue(TownRoamMaps.isBanned(221000001), "地球防御本部[通道] (the connector shaft)");
    }

    @Test
    void leavesOrdinaryTownMapsAllowed() {
        assertFalse(TownRoamMaps.isBanned(222000000), "童话村 itself - a roam destination");
        assertFalse(TownRoamMaps.isBanned(221000000), "地球防御本部 town");
        assertFalse(TownRoamMaps.isBanned(221000100), "地球防御本部 本部");
        assertFalse(TownRoamMaps.isBanned(100000000), "Henesys");
        assertFalse(TownRoamMaps.isBanned(0), "unset id");
    }
}
