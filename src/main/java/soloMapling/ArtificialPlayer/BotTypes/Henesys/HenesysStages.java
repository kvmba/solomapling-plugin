package soloMapling.ArtificialPlayer.BotTypes.Henesys;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.List;

/**
 * What a bot does in each half of Henesys PQ.
 *
 * <p>First half: take a seed off a leaf and plant it on the flower that accepts it. The
 * pairing is fixed in the reactor data, so the bot looks it up instead of testing it, and
 * the quest's own {@code stage} counter - which the flowers bump when one takes - says
 * whether it worked. Nothing is guessed.
 *
 * <p>Second half: keep the monsters off Moon Bunny and carry the rice cakes to Growlie. The
 * cakes are ordinary party drops, so whoever gathers them can hand them over; the bot only
 * has to keep the mobs down.
 */
public final class HenesysStages {

    private HenesysStages() {
    }

    // =========================================================================
    // First half - plant the six primroses
    // =========================================================================

    /**
     * Take the next seed the bot is missing and plant it.
     *
     * <p>One seed per pass, because carrying all six would need the bot to know which flower
     * is still empty - and it can find that out more cheaply by reading the flowers. The
     * quest's own reaction is the confirmation: a flower only bumps {@code stage} if it was
     * the right seed at the right footing.
     *
     * @return true once all six are up, which is the quest's own condition
     */
    public static boolean plantOneFlower(Character bot) {
        if (flowersBloomed(bot) >= HenesysPqData.FLOWERS_TO_BLOOM) {
            return true;
        }

        int wanted = firstEmptyFlower(bot);
        if (wanted < 0) {
            return false;
        }
        int seed = HenesysPqData.seedForFlower(wanted);

        if (PqActions.countItem(bot, seed) == 0) {
            takeSeed(bot, seed);
            if (PqActions.countItem(bot, seed) == 0) {
                return false; // none on the ground yet; the caller will come back
            }
        }

        plantSeedAt(bot, wanted, seed);
        return flowersBloomed(bot) >= HenesysPqData.FLOWERS_TO_BLOOM;
    }

    private static void plantSeedAt(Character bot, int flowerIndex, int seed) {
        Point spot = HenesysPqData.FLOWER_SPOTS.get(flowerIndex);
        PqActions.walkTo(bot, spot);
        // The flower takes the seed as a drop, and it wants exactly one, so throw exactly
        // one - the engine matches the stack's quantity against the WZ condition.
        PqActions.dropStack(bot, seed, 1, spot);
        PqActions.holdArea(bot, spot, 1_200); // stay put while the engine resolves the drop
    }

    /** Break leaves until one drops the seed this bot is after. */
    private static void takeSeed(Character bot, int seed) {
        for (int leafId = HenesysPqData.LEAF_FIRST; leafId <= HenesysPqData.LEAF_LAST; leafId++) {
            int oid = PqActions.findReactorOid(bot, leafId);
            if (oid < 0) {
                continue;
            }
            PqActions.hitReactor(bot, oid);
            PqActions.loot(bot, bot.getPosition(), 4_000, new int[]{seed});
            if (PqActions.countItem(bot, seed) > 0) {
                return;
            }
        }
    }

    /** How many flowers have taken a seed, from the quest's own {@code stage} counter. */
    public static int flowersBloomed(Character bot) {
        return PqActions.readEimInt(bot, "stage", 0);
    }

    /**
     * The first flower still waiting for its seed, by reading the flowers themselves
     * ({@code state > 0} means it has taken one). This is also what makes the loop
     * self-correcting: a flower that rejected its seed keeps state 0 and gets tried again.
     */
    private static int firstEmptyFlower(Character bot) {
        List<org.gms.server.maps.Reactor> flowers = bot.getMap().getAllReactors().stream()
                .filter(r -> r.getId() >= HenesysPqData.FLOWER_FIRST
                        && r.getId() <= HenesysPqData.FLOWER_LAST)
                .toList();
        for (int index = 0; index < HenesysPqData.SEED_TYPES; index++) {
            int dataId = HenesysPqData.FLOWER_FIRST + index;
            boolean hasTaken = flowers.stream()
                    .filter(r -> r.getId() == dataId)
                    .anyMatch(r -> r.getState() > 0);
            if (!hasTaken) {
                return index;
            }
        }
        return -1;
    }

    // =========================================================================
    // Second half - protect Moon Bunny, gather the rice cakes
    // =========================================================================

    /**
     * Keep the monsters off Moon Bunny, and pick up rice cakes as they appear.
     *
     * <p>The quest counts cakes in its own property and stops the run on its own clock, so
     * there is nothing to finish here beyond thinning the mobs; Growlie's turn-in needs the
     * player, because {@code cm.haveItem(4001101, 10)} reads the inventory of whoever talks
     * to him.
     *
     * <p>Returns true once the run has been cleared for the exit.
     */
    public static boolean guardMoonBunny(Character bot) {
        PqActions.attack(bot);
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{HenesysPqData.RICE_CAKE});
        return PqActions.readEimString(bot, "1stageclear") != null
                || PqActions.readEimInt(bot, "stage", 0) > HenesysPqData.FLOWERS_TO_BLOOM;
    }

    /** How many cakes the run has produced so far, from the quest's counter. */
    public static int cakesMade(Character bot) {
        return PqActions.readEimInt(bot, "bunnyCake", 0);
    }
}
