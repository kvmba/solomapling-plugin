package soloMapling.ArtificialPlayer.BotTypes.Zakum;

/**
 * Zakum PQ (the "trials" before Zakum) as its own scripts define it.
 *
 * <p>A mine full of crates. Every room is reactors and nothing else - no monsters at all -
 * and what the party is after is the Fire Ore they yield, which Aura forges into the pendant
 * that lets the party through to Zakum. The exit portal states the bar itself: it refuses
 * while the instance is not cleared, and the instance is cleared when the ore is handed over.
 *
 * <p>That makes this the reactor quest with the least reading to do: break the crates,
 * collect what falls out, and hand it over - and the hand-over, as everywhere else, wants the
 * item in the inventory of whoever talks to the NPC.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/ZakumPQ.js} - {@code minPlayers 1}, {@code maxPlayers 6},
 *       levels 50+, maps 280010000..280011006</li>
 *   <li>{@code scripts/npc/2030008.js} - the Fire Ore turn-in and the pendant forge</li>
 *   <li>{@code scripts/portal/Zakum03.js} - the exit, which names the requirement</li>
 *   <li>{@code wz/Map.wz/Map/Map9/2800100xx.img.xml} - the crate reactors, which carry no
 *       scripts: the mechanic is the state change</li>
 * </ul>
 */
public final class ZakumPqData {

    private ZakumPqData() {
    }

    public static final int RECRUIT_MAP = 211042300;
    public static final int ENTRY_MAP   = 280010000;
    public static final int LAST_MAP    = 280011006;

    public static final int MIN_PLAYERS = 1;
    public static final int MAX_PLAYERS = 6;
    public static final int MIN_LEVEL = 50;

    /**
     * The crates that stand in the mine's rooms, including the one that matters: the Fire
     * Ore (4001018) the turn-in wants drops only from reactor 2112014, which stands in the
     * last mine room ({@code 280011005}) - the rest of the crates carry the documents side
     * quest or nothing but potion drops (2118000/2118001 are "no function" crates linked to
     * the potion box, 2112012/2112013 carry paper documents).
     *
     * <p>Sources: {@code reactordrops} migration V1.0.58 (reactor 2112014 → item 4001018)
     * and the {@code Map2/2800100xx}/280011005 map files listing which crates stand where.
     */
    public static final int[] CRATES = {2112014, 2118000, 2118001, 2112012, 2112013};

    /**
     * The ore the turn-in wants, and the piece Aura forges for each member after it.
     *
     * <p>4001018 ("Fire Ore") is what Aura checks on the leader; 4031061/4031062 are the
     * refined pieces he hands OUT afterwards - they never drop, and chasing them instead of
     * the ore is what left the bot breaking crates forever while the exit stayed shut.
     */
    public static final int FIRE_ORE = 4001018;
    public static final int FIRE_ORE_REFINED = 4031061;
    public static final int PENDANT = 4001017;

    public static boolean isQuestRoom(int mapId) {
        return mapId >= ENTRY_MAP && mapId <= LAST_MAP;
    }
}
