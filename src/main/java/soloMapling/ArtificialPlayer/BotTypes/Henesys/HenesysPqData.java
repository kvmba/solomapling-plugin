package soloMapling.ArtificialPlayer.BotTypes.Henesys;

import java.awt.Point;
import java.util.List;

/**
 * Henesys PQ ("Primrose Hill") as the quest itself defines it.
 *
 * <p>The shape is two stages. First the party takes primrose seeds off the leaves along the
 * bottom of the map and plants each kind at the footing that suits it - the quest keeps a
 * running count in the {@code stage} property and only opens the second half once six
 * flowers are up. Then Moon Bunny pounds rice cakes while the party keeps the monsters off
 * it, and the cakes go to Growlie.
 *
 * <p>The seed-to-footing pairing is fixed in the reactor data rather than randomised: each
 * moonflower names the seed it accepts, so a bot can read the pairing instead of testing it,
 * which is exactly the trial and error the quest asks the player to do.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/HenesysPQ.js} - {@code minPlayers 3}, levels 10+, the
 *       {@code stage} / {@code bunnyCake} / {@code bunnyDamaged} properties</li>
 *   <li>{@code scripts/reactor/9108000..9108005.js} - the six flowers, which bump
 *       {@code stage} and advance the "fullmoon" reactor</li>
 *   <li>{@code scripts/reactor/9102002..9102007.js} - the "nut" leaves that drop the seeds</li>
 *   <li>{@code scripts/npc/1012114.js} - Growlie, who takes 10 rice cakes and ends the run</li>
 *   <li>{@code wz/Reactor.wz/9108xxx.img.xml} - each flower's required seed</li>
 * </ul>
 */
public final class HenesysPqData {

    private HenesysPqData() {
    }

    // =========================================================================
    // Maps
    // =========================================================================

    public static final int LOBBY_MAP = 100000200; // Henesys, where Tory recruits
    public static final int ENTRY_MAP = 910010000; // the one map the whole quest plays in
    public static final int EXIT_MAP  = 100000200;

    /** The quest's own floor for a party: {@code minPlayers = 3}, levels 10 and up. */
    public static final int MIN_PLAYERS = 3;
    public static final int MIN_LEVEL = 10;

    // =========================================================================
    // NPCs
    // =========================================================================

    public static final int TORY = 1012112;     // the recruiter
    public static final int GROWLIE = 1012114;  // takes the rice cakes

    // =========================================================================
    // Items
    // =========================================================================

    public static final int RICE_CAKE = 4001101;
    public static final int RICE_CAKE_COUNT = 10;

    /** The six primrose seeds, in the order the flowers are numbered. */
    public static final int SEED_FIRST = 4001095;
    public static final int SEED_LAST = 4001100;
    public static final int SEED_TYPES = 6;

    // =========================================================================
    // Reactors
    // =========================================================================

    /** The six flowers, flower N accepting seed SEED_FIRST + N - 1. */
    public static final int FLOWER_FIRST = 9108000;
    public static final int FLOWER_LAST = 9108005;

    /** The moon dial the flowers advance; six advances is the second half of the quest. */
    public static final int FULL_MOON = 9101000;
    public static final int FLOWERS_TO_BLOOM = 6;

    /**
     * The leaves that carry the seeds, one reactor kind per seed. Hitting one drops its seed
     * ({@code reactordrops}: 9102002 -> 4001095 ... 9102007 -> 4001100).
     */
    public static final int LEAF_FIRST = 9102002;
    public static final int LEAF_LAST = 9102007;

    // =========================================================================
    // Geometry
    // =========================================================================

    /**
     * Where each flower grows, indexed by seed (0 = seed 4001095). The plants are along the
     * top half of the map and the leaves along the bottom, so a bot's loop is: take a seed
     * from the bottom, carry it to the matching plant up top.
     *
     * <p>These are the flowers' own positions from the map data, which is where their trigger
     * area is centred.
     */
    public static final List<Point> FLOWER_SPOTS = List.of(
            new Point(4, -690),      // moonflower1 <- 4001095
            new Point(182, -452),    // moonflower2 <- 4001096
            new Point(0, -210),      // moonflower3 <- 4001097
            new Point(-358, -210),   // moonflower4 <- 4001098
            new Point(-535, -449),   // moonflower5 <- 4001099
            new Point(-359, -692)    // moonflower6 <- 4001100
    );

    /** The seed the flower at {@code index} accepts. */
    public static int seedForFlower(int index) {
        return SEED_FIRST + index;
    }

    /** The flower index a seed belongs to, or -1 when it is not one of the six. */
    public static int flowerForSeed(int seedItemId) {
        int index = seedItemId - SEED_FIRST;
        return (index >= 0 && index < SEED_TYPES) ? index : -1;
    }
}
