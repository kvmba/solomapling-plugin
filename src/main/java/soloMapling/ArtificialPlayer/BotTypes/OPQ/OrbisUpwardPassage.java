package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.Arrays;

/** Stage 6: learn the stair portals from their landings, then set the summit's levers. */
final class OrbisUpwardPassage {
    private static final int ROWS = 16;
    private static final int COLUMNS = 4;
    private static final int ROWS_PER_CHECKPOINT = 4;
    private static final int FIRST_STAIR_PORTAL = 24; // rp011, then four consecutive ids per row
    private static final int FIRST_CHECKPOINT = 1;   // pt00..pt03, after rows 4, 8, 12 and 16
    private static final int FALLBACK_PORTAL = 5;     // r6fail, below the first row
    private static final int ROW_TOLERANCE_PX = 48;   // rows are at least 115px apart
    private static final long RETRY_MS = 5_000;

    private final long[] retryAt = new long[ROWS * COLUMNS];
    private StairWalk walk;
    private Point leverFloor;

    private static final class StairWalk {
        final Portal portal;
        final Point approach;
        final int row;
        final int column;
        volatile boolean abandoned;

        StairWalk(Portal portal, Point approach, int row, int column) {
            this.portal = portal;
            this.approach = approach;
            this.row = row;
            this.column = column;
        }
    }

    /** Run-scoped observations; do not consult the script's hidden stair combination. */
    static final class Puzzle {
        private final int[] choices = new int[ROWS + 1];

        Puzzle() {
            Arrays.fill(choices, (1 << COLUMNS) - 1);
        }

        synchronized int choices(int row) {
            return choices[row];
        }

        synchronized boolean enter(Character bot, MapleMap room, StairWalk attempt) {
            int row = attempt.row;
            int bit = 1 << attempt.column;
            if (bot.getMap() != room || (choices[row] & bit) == 0) return true;

            PqActions.enterPortal(bot, attempt.portal);
            if (bot.getMap() != room) return true;

            // party3_r6pt: advance one row in the same column, or land at a checkpoint.
            int success = row % ROWS_PER_CHECKPOINT == 0 ? FIRST_CHECKPOINT + row / ROWS_PER_CHECKPOINT - 1
                    : attempt.portal.getId() + COLUMNS;
            // A miss on row 5 drops to r6fail too; later misses return to the last checkpoint.
            int failure = row <= 5 ? FALLBACK_PORTAL : FIRST_CHECKPOINT + (row - 1) / ROWS_PER_CHECKPOINT - 1;
            Point arrival = bot.getPosition();
            if (atPortal(room, arrival, success)) {
                choices[row] = bit;
                return true;
            }
            if (atPortal(room, arrival, failure)) {
                choices[row] &= ~bit;
                return true;
            }
            return false; // a refused or interrupted entry does not count as a wrong guess
        }

        private static boolean atPortal(MapleMap room, Point position, int id) {
            Portal portal = room.getPortal(id);
            return portal != null && GCMovement.withinArrivalBox(position, portal.getPosition());
        }

        /** Levers toggle in both directions. Recheck under the same lock for all bots in this run. */
        synchronized boolean pull(Character bot, MapleMap room, Lever lever, Point floor) {
            return bot.getMap() == room && (lever.reactor().getState() > 0) != lever.on()
                    && PqActions.hitReactorIfInReach(bot, lever.reactor().getObjectId(), floor);
        }
    }

    private record Lever(Reactor reactor, boolean on) {
    }

    void reset(Character bot) {
        stopWalking(bot);
        Arrays.fill(retryAt, 0);
    }

    void stopWalking(Character bot) {
        if (bot.getMapId() == OrbisPqData.STAGE_UP) {
            if (walk != null) GCMovement.stopIfTarget(bot, walk.approach);
            if (leverFloor != null) GCMovement.stopIfTarget(bot, leverFloor);
        }
        walk = null;
        leverFloor = null;
    }

    /** True after a portal attempt or lever strike, so the FSM can take its next beat promptly. */
    boolean step(Character bot, Puzzle puzzle) {
        MapleMap room = bot.getMap();
        int wanted = leverMask(PqActions.readEimString(bot, "stage6_c"));
        Lever lever = wanted < 0 ? null : nextLever(room, wanted);
        if (lever == null) {
            // Eak initializes the lever combination when the leader first talks to him.
            // Once the levers match, the leader can return to Eak to claim the piece.
            stopWalking(bot);
            PqActions.waitNearStageNpc(bot, OrbisPqData.EAK);
            return false;
        }

        if (walk != null && walk.abandoned) {
            defer(walk);
            walk = null;
        }
        boolean settled = GCMovement.isSettled(bot);
        int row = settled || walk == null ? nextRow(room, bot.getPosition().y) : walk.row;
        if (walk != null && (walk.row != row || !walk.portal.getPortalStatus()
                || (puzzle.choices(walk.row) & (1 << walk.column)) == 0)) {
            // Finish a rope dismount before canceling its destination.
            if (!settled && GCMovement.hasMoveTarget(bot)) return false;
            stopWalking(bot);
        }
        if (walk == null) {
            if (!settled && !GCMovement.isClimbing(bot)) return false;
            if (row == 0) return false;
            if (row > ROWS) return workLever(bot, room, puzzle, lever);
            if (leverFloor != null) stopWalking(bot);
            walk = chooseStair(bot, room, row, puzzle.choices(row));
            if (walk == null) return false;
        }

        StairWalk attempt = walk;
        if (!PqActions.walkToPortal(bot, attempt.portal, () -> attempt.abandoned = true)) return false;
        boolean advanced = puzzle.enter(bot, room, attempt);
        if (!advanced) defer(attempt);
        walk = null;
        return advanced;
    }

    /** Walk up to the first row, or the next row above the checkpoint we landed at. */
    private static int nextRow(MapleMap room, int y) {
        for (int row = 1; row <= ROWS; row++) {
            Portal reference = room.getPortal(stairId(row, 0));
            if (reference == null) return 0;
            if (y >= reference.getPosition().y - ROW_TOLERANCE_PX) return row;
        }
        return ROWS + 1;
    }

    private StairWalk chooseStair(Character bot, MapleMap room, int row, int choices) {
        long now = GCMovement.movementNowMs();
        for (int remaining = choices; remaining != 0; remaining &= remaining - 1) {
            int column = Integer.numberOfTrailingZeros(remaining);
            int id = stairId(row, column);
            if (retryAt[id - FIRST_STAIR_PORTAL] > now) continue;
            Portal portal = room.getPortal(id);
            if (portal == null || !portal.getPortalStatus()) continue;
            Point approach = GCMovement.portalApproachPoint(room, portal);
            if (GCMovement.canPathTo(bot, approach.x, approach.y)) {
                return new StairWalk(portal, approach, row, column);
            }
        }
        return null;
    }

    private static int stairId(int row, int column) {
        return FIRST_STAIR_PORTAL + (row - 1) * COLUMNS + column;
    }

    private void defer(StairWalk attempt) {
        retryAt[attempt.portal.getId() - FIRST_STAIR_PORTAL] = GCMovement.movementNowMs() + RETRY_MS;
    }

    private boolean workLever(Character bot, MapleMap room, Puzzle puzzle, Lever lever) {
        Point at = lever.reactor().getPosition();
        Point floor = GCMovement.groundPointBelow(room, at.x, at.y);
        if (floor == null) return false;
        if (puzzle.pull(bot, room, lever, floor)) {
            stopWalking(bot);
            return true;
        }
        if (bot.getMap() != room || (lever.reactor().getState() > 0) == lever.on()) return true;
        leverFloor = floor;
        PqActions.walkTo(bot, floor);
        return false;
    }

    private static int leverMask(String combination) {
        if (combination == null || combination.length() != OrbisPqData.LEVER_COUNT) return -1;
        int mask = 0;
        for (int i = 0; i < combination.length(); i++) {
            char state = combination.charAt(i);
            if (state == '1') mask |= 1 << i;
            else if (state != '0') return -1;
        }
        return Integer.bitCount(mask) == 2 ? mask : -1;
    }

    private static Lever nextLever(MapleMap room, int wanted) {
        for (int i = 0; i < OrbisPqData.LEVER_COUNT; i++) {
            Reactor lever = room.getReactorByName(Integer.toString(i + 1));
            if (lever == null || lever.getId() != OrbisPqData.LEVER_REACTOR) return null;
            boolean on = (wanted & (1 << i)) != 0;
            if ((lever.getState() > 0) != on) return new Lever(lever, on);
        }
        return null;
    }
}
