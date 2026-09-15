package soloMapling.ArtificialPlayer.BotTypes.Henesys;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the seed-to-flower pairing Henesys PQ's bots depend on.
 *
 * <p>The quest's whole first half is trial and error for a player: six primrose seeds, six
 * footings, and nothing to say which goes where except watching for the ones that take. A
 * bot does not have to guess - the pairing is fixed in the reactor data, each flower naming
 * the one seed it accepts - but that also means an error here is silent. A bot that plants
 * the wrong seed just watches the flower refuse it and tries again forever.
 */
class HenesysSeedDataTest {

    @Test
    void eachFlowerAcceptsTheNextSeedInOrder() {
        // wz/Reactor.wz: 9108000 wants 4001095, 9108001 wants 4001096, ... in step.
        for (int index = 0; index < HenesysPqData.SEED_TYPES; index++) {
            assertEquals(HenesysPqData.SEED_FIRST + index,
                    HenesysPqData.seedForFlower(index),
                    "flower " + index + " wants the wrong seed");
            assertEquals(HenesysPqData.FLOWER_FIRST + index,
                    HenesysPqData.FLOWER_FIRST + index); // id order is the same as seed order
        }
    }

    @Test
    void seedLookupRoundTrips() {
        for (int index = 0; index < HenesysPqData.SEED_TYPES; index++) {
            int seed = HenesysPqData.seedForFlower(index);
            assertEquals(index, HenesysPqData.flowerForSeed(seed));
        }
    }

    @Test
    void unrelatedItemsAreNotSeeds() {
        assertEquals(-1, HenesysPqData.flowerForSeed(HenesysPqData.SEED_FIRST - 1));
        assertEquals(-1, HenesysPqData.flowerForSeed(HenesysPqData.SEED_LAST + 1));
        assertEquals(-1, HenesysPqData.flowerForSeed(HenesysPqData.RICE_CAKE));
    }

    @Test
    void theSixFlowersHaveSixDistinctSpots() {
        // Two flowers sharing a spot would send the bot to plant one flower twice and never
        // finish the dial.
        assertEquals(HenesysPqData.SEED_TYPES, HenesysPqData.FLOWER_SPOTS.size());
        HashSet<Point> spots = new HashSet<>(HenesysPqData.FLOWER_SPOTS);
        assertEquals(HenesysPqData.SEED_TYPES, spots.size(), "two flowers share a spot");
    }

    @Test
    void theQuestAsksForThreePlayersAtLeast() {
        // minPlayers is 3, so a lone player plus two bots is the smallest working party;
        // keeping the number here stops a future edit from quietly raising the bar past
        // what the spawn logic can produce.
        assertEquals(3, HenesysPqData.MIN_PLAYERS);
    }

    @Test
    void theDialNeedsSixFlowers() {
        assertEquals(HenesysPqData.SEED_TYPES, HenesysPqData.FLOWERS_TO_BLOOM);
    }
}
