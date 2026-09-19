package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import java.awt.Point;
import java.util.List;

/**
 * Orbis PQ ("Tower of Goddess") as the engine lays it out, stage by stage.
 *
 * <p>Every value here was read off the quest's own scripts, its WZ map data and its reactor
 * definitions rather than guessed, because the assumptions that look obvious are wrong in
 * the places that matter - most expensively, where a stage is triggered by an item landing
 * inside a rectangle rather than by a player standing in one.
 *
 * <p>The tower at {@link #TOWER_MAP} is the hub: every stage room is reached from there by
 * walking to a portal, clearing the room's puzzle, and coming back. That is why the bot
 * routes through the tower instead of trying to walk from one room to the next.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/OrbisPQ.js} - stage flags {@code statusStg0..8}, the weekday
 *       the music box wants, {@code minLevel}</li>
 *   <li>{@code scripts/npc/2013001.js} - Chamberlain Eak, who clears every stage and holds
 *       every turn-in check</li>
 *   <li>{@code wz/Map.wz/Map/Map9/9200101xx.img.xml} - rooms, mobs, portals, trigger areas</li>
 *   <li>{@code wz/Reactor.wz/*.img.xml} - reactor hit counts, item conditions, trigger boxes</li>
 *   <li>{@code db/.../drop_data_insert_old_data.sql} - which mob drops which statue piece</li>
 * </ul>
 */
public final class OrbisPqData {

    private OrbisPqData() {
    }

    // =========================================================================
    // Maps
    // =========================================================================

    public static final int LOBBY_MAP         = 200080101;
    public static final int STAGE_CLOUDS      = 920010000; // statusStg0 - 20 clouds onto the altar
    public static final int TOWER_MAP         = 920010100; // the hub every room is reached from
    public static final int STAGE_WALKWAY     = 920010200; // statusStg1 - 30 Statue Pieces
    public static final int STAGE_STORAGE     = 920010300; // statusStg2 - clear the room, then Eak
    public static final int STAGE_MUSIC       = 920010400; // statusStg3 - today's record
    public static final int STAGE_SEALED      = 920010500; // statusStg4 - three platforms
    public static final int STAGE_LOUNGE      = 920010600; // statusStg5 - 40 Statue Pieces
    public static final int STAGE_UP          = 920010700; // statusStg6 - two of five levers
    public static final int STAGE_PAPA        = 920010800; // statusStg7 - scar1..6
    public static final int STAGE_JAIL        = 920010900;
    public static final int STAGE_PRIZE       = 920011000;
    public static final int EXIT_LOBBY        = 920011200;

    /** The four sub-rooms 920010600 opens onto; 604 holds the ten 4001052 reactors. */
    public static final List<Integer> LOUNGE_ROOMS =
            List.of(920010601, 920010602, 920010603, 920010604);

    /** The lounge sub-rooms' doors, in the order their {@code in0N} portals list them. */
    public static final String[] LOUNGE_ENTRY_PORTALS = {"in00", "in01", "in02", "in03"};

    /** The door a lounge sub-room walks back out of. */
    public static final String LOUNGE_EXIT_PORTAL = "out00";

    /** The sub-room door for a rotation index (wraps). */
    public static String loungeEntryPortal(int index) {
        return LOUNGE_ENTRY_PORTALS[Math.floorMod(index, LOUNGE_ENTRY_PORTALS.length)];
    }

    // =========================================================================
    // NPCs
    // =========================================================================

    /** Chamberlain Eak at 2013001: spawns from the altar, clears every stage. */
    public static final int EAK = 2013001;

    // =========================================================================
    // Items
    // =========================================================================

    public static final int CLOUD_PIECE        = 4001063; // x20 onto the altar (statusStg0)
    public static final int STATUE_PIECE_1     = 4001050; // x30, statusStg1
    public static final int STATUE_PIECE_2     = 4001045; // Eak hands it over, statusStg2
    public static final int STATUE_PIECE_5     = 4001052; // x40, statusStg5
    public static final int STATUE_PIECE_8     = 4001055; // x1 onto the statue base, statusStg8
    public static final int RECORD_LP_FIRST    = 4001056; // + weekday, statusStg3

    // =========================================================================
    // Stage 0 - clouds
    // =========================================================================

    /**
     * Where a cloud stack has to land. The altar reactor sits at (377,66) and its box spans
     * x in [277,477); the drop re-seats itself onto the floor 85px below the throw
     * (MapleMap#calcDropPos), so this is the landing point and not the throw point. Aiming
     * at the reactor's own cell lands here too, but naming the landing keeps the arithmetic
     * visible - the old constant was (497,143), twenty pixels outside the box to the right.
     */
    public static final Point ALTAR_DROP = new Point(377, 99);

    /** The altar reactor's data id, and the number of clouds its condition asks for. */
    public static final int ALTAR_REACTOR = 2006000;
    public static final int CLOUD_COUNT = 20;

    // =========================================================================
    // Stage 1 - walkway (30 x 4001050 from 9300045/9300046/9300047)
    // =========================================================================

    public static final int WALKWAY_MOB_FIRST = 9300045;
    public static final int WALKWAY_MOB_LAST  = 9300047;
    public static final int WALKWAY_COUNT     = 30;
    public static final Point WALKWAY_SPAWN   = new Point(0, -1000);

    // =========================================================================
    // Stage 2 - storage (clear the room; Eak hands over 4001045)
    // =========================================================================

    public static final int STORAGE_MOB = 9300040;

    // =========================================================================
    // Stage 3 - music box
    // =========================================================================

    /**
     * Where a record has to land. The box sits at (-1706,-240) in a box spanning
     * x in [-1758,-1666); the landing below it is y=-172. The old constant was (-1588,-127),
     * seventy-eight pixels outside on x.
     */
    public static final Point MUSIC_DROP = new Point(-1706, -172);
    public static final int MUSIC_REACTOR = 2008006;

    /** The seven boxes, in data-id order; the record they drop is RECORD_LP_FIRST + offset. */
    public static final int MUSIC_BOX_FIRST = 2002004;
    public static final int MUSIC_BOX_LAST  = 2002010;

    // =========================================================================
    // Stage 4 - sealed room (three platforms, targets published as stage4_0..2)
    // =========================================================================

    /** The three trigger areas, as (x1,y1,x2,y2) - the centres are what a bot stands on. */
    public static final Point[] SEALED_AREAS = {
            new Point(-162, -808),   // area 1: x in [-205,-119), y in [-850,-767)
            new Point(-40,  -919),   // area 2: x in [-71,-10),   y in [-963,-876)
            new Point(78,   -813),   // area 3: x in [40,116),    y in [-850,-776)
    };
    public static final int SEALED_PLAYERS = 3;
    public static final int SEALED_STONE_REACTOR = 2002012; // "stone4" - the prize box

    // =========================================================================
    // Stage 5 - lounge (40 x 4001052)
    // =========================================================================

    public static final int LOUNGE_MOB_FIRST = 9300041;
    public static final int LOUNGE_MOB_LAST  = 9300043;
    public static final int LOUNGE_REACTOR   = 2002002; // ten of these in room 920010604
    public static final int LOUNGE_COUNT     = 40;

    // =========================================================================
    // Stage 6 - levers (exactly two of five)
    // =========================================================================

    /**
     * The five lever reactors, named "1".."5" in the map. The quest publishes which two it
     * wants as a five-character string in {@code stage6_c}, e.g. "01010" - the bot reads it
     * rather than trying combinations.
     *
     * <p>Turned on means "state > 0", so one hit each is enough; the positions are the map
     * data's, indexed the way the quest names them (1-based).
     */
    public static final Point[] LEVER_SPOTS = {
            new Point(-283, -4980),  // "1"
            new Point(207, -4983),   // "2"
            new Point(-49, -4980),   // "3"
            new Point(77, -5053),    // "4"
            new Point(-164, -5051),  // "5"
    };

    /** Where the stone6 prize box sits, for the walk after the levers are right. */
    public static final Point LEVER_PRIZE_SPOT = new Point(-43, -1710);

    /** Where the stone4 prize box sits. */
    public static final Point SEALED_PRIZE_SPOT = new Point(-47, -1417);

    /** The six scar reactors' positions in the tower, for the walk between them. */
    public static final Point[] SCAR_SPOTS = {
            new Point(-164, -1064),  // scar1
            new Point(99, -1048),    // scar2 - listed by name order, not data id
            new Point(74, -843),     // scar3
            new Point(-145, -830),   // scar4
            new Point(-3, -1006),    // scar5
            new Point(-134, -937),   // scar6
    };

    /** Minerva's statue base in the tower, where the last piece goes. */
    public static final Point STATUE_BASE_SPOT = new Point(-48, -916);
    public static final int LEVER_COUNT = 5;
    /** All five levers share this data id; they are told apart by their map names "1".."5". */
    public static final int LEVER_REACTOR = 2008007;
    public static final int LEVER_STONE_REACTOR = 2002013; // "stone6" - the prize box

    // =========================================================================
    // Stage 7 - Papa Pixie (statusStg7 is set by the spring, not by the scars)
    // =========================================================================

    /**
     * The Papa Pixie chain, straight off the map's reactors and their scripts.
     *
     * <p>The pots ({@code 2001000}/{@code 2001001}) are item-triggered on {@code 4001053} - feed a
     * pot its medal and the room's mobs ({@code 9300048}, sometimes the darker {@code 9300049})
     * appear, and those mobs drop the very medal the pots take. The "trap" reactors
     * ({@code 2001016}) are item-triggered on {@code 4001074}, which the darker mob drops; feeding
     * one a {@code 4001074} kills the room and makes Papa Pixie ({@code 9300039}) appear. Papa
     * drops the Root of Life ({@code 4001054}), and the spring ({@code 2002003}) takes that and is
     * what actually sets {@code statusStg7} (and lets Minerva's final piece fall).
     *
     * <p>Reaching that spring drop is the stage. Breaking the six scar reactors is a different,
     * earlier thing (it lives in the tower and is what makes Eak send the party here at all).
     */
    public static final int PAPA_SPRING_REACTOR = 2002003;
    public static final Point PAPA_SPRING_SPOT = new Point(-755, 19);
    /** The Root of Life Papa Pixie drops and the spring wants. */
    public static final int PAPA_SEED = 4001054;
    /** The pots that spawn the room's mobs, on {@code 4001053}. */
    public static final int PAPA_POT = 2001000;
    public static final int PAPA_POT_ALT = 2001001;
    /** The medal the summoned mobs drop and the pots take. */
    public static final int PAPA_MEDAL = 4001053;
    /** The "trap" reactors that end the room and reveal Papa, on {@code 4001074}. */
    public static final int PAPA_TRAP = 2001016;
    /** The item the darker mob drops and a trap takes. */
    public static final int PAPA_TRAP_ITEM = 4001074;
    /**
     * Every monster this room can hold, as one id range: Papa Pixie ({@code 9300039}) and the two
     * it summons ({@code 9300048}/{@code 9300049}) are contiguous, so a fight-and-loot loop can
     * cover the room with a single range test.
     */
    public static final int PAPA_MOB_LOW = 9300039;
    public static final int PAPA_MOB_HIGH = 9300049;

    /**
     * The six scar reactors, which live <em>in the tower</em> rather than in a room of their
     * own: the map data names them scar1..scar6 at 2008000..2008005, and Eak only opens the
     * next stage once every one of them has been hit ({@code isStatueComplete} checks
     * {@code getState() >= 1} on each).
     *
     * <p>None of them has a reactor script - the whole mechanic is the state change - so
     * hitting them through the engine's state walk is all that is required.
     */
    public static final int SCAR_FIRST = 2008000;
    public static final int SCAR_LAST  = 2008005;
    public static final int SCAR_COUNT = 6;

    // =========================================================================
    // Stage 8 - statue base
    // =========================================================================

    /** The statue base the final piece goes onto. */
    public static final int STATUE_BASE_REACTOR = 2006001;

    // =========================================================================
    // Tower portals: which portal walks to which room
    // =========================================================================

    /**
     * Portal ids in the tower that lead to each room. A portal's id is its index in the
     * map's WZ portal list ({@code PortalFactory} does {@code setId(Integer.parseInt(name))}),
     * so these are stable values from the map data rather than a running count.
     *
     * <p>The tower is the hub: every room is entered from here and returns here, which is why
     * routing goes through it rather than room to room.
     */
    public static int towerPortalFor(int roomMap) {
        return switch (roomMap) {
            case STAGE_WALKWAY -> 4;    // in00 -> party3_room1
            case STAGE_MUSIC   -> 5;    // in02 -> party3_room3
            case STAGE_STORAGE -> 12;   // in01 -> party3_room2
            case STAGE_SEALED  -> 13;   // in03 -> party3_room4
            case STAGE_UP      -> 14;   // in05 -> party3_room6
            case STAGE_LOUNGE  -> 15;   // in04 -> party3_room5
            case STAGE_PAPA    -> 16;   // in06 -> party3_room8
            default -> -1;
        };
    }

    /**
     * The portal name the tower enters a room by, read off the map data (portal id {@code 4} is
     * {@code in00}, and so on). Named rather than numeric because the tower's room portals are
     * script portals whose names the scripts key on.
     */
    public static String towerPortalName(int portalId) {
        return switch (portalId) {
            case 4 -> "in00";
            case 5 -> "in02";
            case 12 -> "in01";
            case 13 -> "in03";
            case 14 -> "in05";
            case 15 -> "in04";
            case 16 -> "in06";
            default -> null;
        };
    }

    /** Where to stand in the tower to reach a room. */
    public static Point towerSpotFor(int roomMap) {
        return switch (roomMap) {
            case STAGE_WALKWAY -> new Point(-250, -1631);
            case STAGE_MUSIC   -> new Point(155, -31);
            case STAGE_STORAGE -> new Point(157, -1385);
            case STAGE_SEALED  -> new Point(-259, -1267);
            case STAGE_UP      -> new Point(-42, -1905);
            case STAGE_LOUNGE  -> new Point(-38, -1454);
            case STAGE_PAPA    -> new Point(180, -316);
            default -> null;
        };
    }

    /** Where the tower's spawn point puts an arriving player, for returning from a room. */
    public static final Point TOWER_SPAWN = new Point(-168, -85);

    /**
     * Whether a map id belongs to this quest's rooms. Used to recognise where a bot has
     * ended up: the tower and every room hanging off it, including the lounge's four
     * sub-rooms and the jail and prize areas, so one of them is a stage it can work rather
     * than somewhere unexpected.
     */
    public static boolean isOrbisRoom(int mapId) {
        return mapId == STAGE_CLOUDS || mapId == TOWER_MAP || mapId == STAGE_WALKWAY
                || mapId == STAGE_STORAGE || mapId == STAGE_MUSIC || mapId == STAGE_SEALED
                || mapId == STAGE_LOUNGE || mapId == STAGE_UP || mapId == STAGE_PAPA
                || mapId == STAGE_JAIL || mapId == STAGE_PRIZE
                || LOUNGE_ROOMS.contains(mapId);
    }

    /**
     * The name of the portal a room walks out to the tower by.
     *
     * <p>Most rooms publish it as {@code st00} (entered by {@code party3_roomout}, whose script
     * switches on the map id); the lounge's sub-rooms, the jail and the prize room use
     * {@code out00}. Papa Pixie's room is leader-only ({@code party3_gardenin}) and has no
     * walk-out, which is why the bot leaves it by following the leader. Found by name rather than
     * by id because the ids differ per room.
     */
    public static final String ROOM_EXIT_PORTAL_NAME = "st00";
}
