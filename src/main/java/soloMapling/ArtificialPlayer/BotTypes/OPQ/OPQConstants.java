package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import java.awt.*;
import java.util.List;

/**
 * Map / item / platform constants for the Orbis Party Quest rush bot system.
 *
 * NOTE: Many of these IDs are placeholders taken from the OPQ spec document and
 * the common GMS v83 data set. They MUST be verified against this server's WZ
 * export before relying on them in live runs.
 * that are most likely to drift.
 */
public final class OPQConstants {

    private OPQConstants() {}

    // ---- Map IDs ------------------------------------------------------------
    public static final int OPQ_LOBBY      = 200080101; // recruitment lobby (Orbis Ticketing Booth area)
    public static final int OPQ_STAGE_1    = 920010000; // Stage 1 Cloud Collection Entrance - GOOD
    public static final int OPQ_TOWER      = 920010100; // intermediate tower hub
    public static final int OPQ_STAGE_2    = 920010400; // music record stage
    public static final int OPQ_EXIT_LOBBY = 920011200; // Exit Lobby On the Way Out - GOOD

    // Convenience grouping
    public static final List<Integer> OPQ_INSIDE_MAPS = List.of(
            OPQ_STAGE_1, OPQ_TOWER, OPQ_STAGE_2, OPQ_EXIT_LOBBY
    );

    // ---- NPC IDs -----------------------------------------------------------
    public static final int CHAMBERLAIN_EAK = 2013001; // Stage 1 Cloud NPC - Click on him to teleport to tower

    // ---- Item IDs -----------------------------------------------------------
    public static final int CLOUD_PIECE    = 4001063; // Stage 1 Cloud Pieces - GOOD

    // Stage 2 records. Seven boxes drop seven different ones, in data-id order:
    // 2002004 -> 4001056 ... 2002010 -> 4001062 (reactordrops). The music box accepts only
    // the one matching today's weekday, which OrbisPQ.js picks by setting the reactor's
    // event state to d.getDay() - see OPQOrchestrator.getTodayRecordItemId().
    public static final int RECORD_LP_FIRST = 4001056;

    // ---- Stage 1 reactor data ids -------------------------------------------
    // Reactor data (template) ids on map 920010000, sourced from
    // wz/Map.wz/Map/Map9/920010000.img.xml:
    //   2006000 -> "eak" altar reactor where cloud pieces are dropped (skip).
    //   2002001 -> the 20 breakable cloud reactors (cloud1..cloud20).
    // Note: the 1000000003 / 1000000004-23 values seen in the !reactor list
    // command are runtime object ids (oid), which are reassigned per map
    // instance — never use those for filtering. Filter by data id instead.
    public static final int STAGE_1_ALTAR_REACTOR_ID = 2006000;
    public static final int STAGE_1_CLOUD_REACTOR_ID = 2002001;

    // ---- Stage 2 reactor data ids -------------------------------------------
    // 7 breakable box reactors on map 920010400 (one per platform m3–m9).
    // Data IDs confirmed from runtime dump: 2002004 through 2002010.
    public static final int STAGE_2_BOX_REACTOR_FIRST = 2002004;
    public static final int STAGE_2_BOX_REACTOR_LAST  = 2002010;
    // Central music box reactor (dataId=2008006, pos ~x=-1706,y=-240).
    public static final int STAGE_2_MUSIC_BOX_REACTOR_ID = 2008006;

    // Ordinal labels for chat: index 0 = reactor 1000000005, index 6 = reactor 1000000011
    public static final String[] STAGE_2_BOX_ORDINALS = {
            "1st", "2nd", "3rd", "4th", "5th", "6th", "7th"
    };

    // ---- Stage 1 top-platform (post-handin) ---------------------------------
    // After the leader hands all cloud pieces to the NPC, they get teleported
    // to a small platform near the top of the map. Bots watch the leader's
    // position and treat stage 1 as complete the moment they observe the
    // leader inside the tolerance box around this anchor.
    //
    // MapleStory uses screen-style coords (down is +y) so the top of a map
    // lives at very negative y values — the sign on Y here is correct.
    public static final Point STAGE_1_ENTRY_TP = new Point(266, 143);
    public static final Point STAGE_1_COMPLETE_TP = new Point(165, -1270);
    public static final int STAGE_1_TOP_PLATFORM_X        =   165;
    public static final int STAGE_1_TOP_PLATFORM_Y        = -1270;
    public static final int STAGE_1_TOP_PLATFORM_TOL_X_PX =   300;
    public static final int STAGE_1_TOP_PLATFORM_TOL_Y_PX =   150;

    // ---- Stage 1 loot scan tuning -------------------------------------------
    // After breaking a cloud reactor, the dropped piece can land at the
    // reactor's anchor or fall through several footholds below it. The bot
    // first probes its own feet (where the just-broken reactor was) and, if
    // nothing's there, walks straight down the reactor's x in fixed steps.
    public static final double STAGE_1_LOOT_SCAN_RANGE_PX    = 8000;
    public static final int    STAGE_1_LOOT_FALLBACK_STEPS   =  20;
    public static final int    STAGE_1_LOOT_FALLBACK_STEP_PX = 2500;

    // ---- Stage 1 drop / return zone -----------------------------------------
    // The altar reactor ("eak") at (377,66) is what spawns Chamberlain Eak, and it fires
    // only when a single stack of exactly 20 cloud pieces lands inside its box
    // x∈[277,477) y∈[-34,166) (Reactor.wz/2006000.img.xml event/0: type=100, 0=4001063,
    // 1=20, lt(-100,-100)/rb(100,100) relative to the reactor).
    //
    // Aim at (377,99), not at the reactor: MapleMap#calcDropPos re-seats a throw onto the
    // ground 85px below it, and 99 is the first foothold at y>=58 under x=377. Throwing at
    // the reactor's own (377,66) lands in the same place. The old (497,143) was 20px
    // outside the box to the right and never triggered anything.
    public static final Point STAGE_1_DROP_POS = new Point(377, 99);

    // Exactly this many, in ONE stack - the trigger compares item.getQuantity() against
    // this value, so twenty loose single pieces will not fire it.
    public static final int CLOUD_REQUIRED = 20;

    // ---- Stage 2 boxes are on platforms m3–m9 (m1/m2 are base floor and entry).
    public static final List<String> STAGE_2_BOX_PLATFORMS = List.of(
            "m3", "m4", "m5", "m6", "m7", "m8", "m9"
    );

    // Music box reactor ("music") at (-1706,-240) on 920010400, trigger box
    // x∈[-1758,-1666) y∈[-304,-161). Same landing rule: throw at (-1706,-172), the first
    // foothold at y>=-325 under x=-1706 - the old (-1588,-127) was 78px outside on x.
    public static final Point STAGE_2_DROP_POS = new Point(-1706, -172);

    // ---- Tuning -------------------------------------------------------------
    public static final long STAGE_WAIT_TIMEOUT_MS        = 120_000; // 2 min per stage wait
    public static final long RECRUIT_MESSAGE_INTERVAL_MS  =  12_000; // chat every ~12s in lobby
    public static final long NAVIGATE_SETTLE_MS           =   1_500; // pause after arriving at platform
    public static final long ASSIGNMENT_POLL_MS           =     500; // how often the bot re-checks a null assignment
    public static final int  MAX_REACTOR_HITS             =       4; // OPQ cloud reactors break after 4 hits
    public static final long SWING_INTERVAL_MS            =     700; // delay between bot swings (~typical 1H attack speed)
    public static final int  REACTOR_HIT_RANGE_PX         =      200; // bot must be within this many px of reactor.x to hit
}
