package soloMapling.ArtificialPlayer.BotTypes.Magatia;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.util.Arrays;

/** A lane is reserved to one player. Keep the observed choices when the script sends us back. */
final class MagatiaLane {
    private static final int ROWS = 10;
    private static final int COLUMNS = 4;
    private static final long WALK_RETRY_MS = 5_000;
    private final int[] possible = new int[ROWS];
    private final long[] retryAt = new long[ROWS * COLUMNS];
    private MapleMap lane;
    private Walk walk;

    private static final class Walk {
        final Portal portal;
        final int row;
        final int column;
        volatile boolean abandoned;

        Walk(Portal portal, int row, int column) {
            this.portal = portal;
            this.row = row;
            this.column = column;
        }
    }

    void reset() {
        lane = null;
        walk = null;
        Arrays.fill(possible, (1 << COLUMNS) - 1);
        Arrays.fill(retryAt, 0);
    }

    /** Returns true when the next step can run without waiting a full stage tick. */
    boolean climb(Character bot) {
        MapleMap map = bot.getMap();
        if (lane != map) {
            reset();
            lane = map;
        }
        Portal success = map.getPortal("success");
        if (bot.getPosition().y <= success.getPosition().y + 32) {
            PqActions.walkThroughPortal(bot, map.getPortal("out00"));
            return bot.getMap() != map;
        }
        if (walk != null && (walk.abandoned || !walk.portal.getPortalStatus())) {
            if (walk.abandoned) defer(walk);
            walk = null;
        }
        if (walk == null) {
            if (!GCMovement.isSettled(bot)) return false;
            walk = chooseDoor(bot, map);
            if (walk == null) return false;
        }
        Walk attempt = walk;
        if (!PqActions.walkToPortal(bot, attempt.portal, () -> attempt.abandoned = true)) return false;
        PqActions.enterPortal(bot, attempt.portal);
        if (bot.getMap() != map) return true;

        Portal next = attempt.row == ROWS - 1 ? success : map.getPortal(attempt.portal.getId() + COLUMNS);
        var position = bot.getPosition();
        int bit = 1 << attempt.column;
        if (GCMovement.withinArrivalBox(position, map.getPortal("fail").getPosition())) {
            possible[attempt.row] &= ~bit;
        } else if (GCMovement.withinArrivalBox(position, next.getPosition())) {
            possible[attempt.row] = bit;
        } else {
            defer(attempt);
        }
        walk = null;
        return true;
    }

    /** A missed approach or warp is transient, even when this is the only proven correct door. */
    private void defer(Walk attempt) {
        retryAt[attempt.row * COLUMNS + attempt.column] = GCMovement.movementNowMs() + WALK_RETRY_MS;
    }

    private Walk chooseDoor(Character bot, MapleMap map) {
        int y = bot.getPosition().y;
        int row = 0;
        int nearest = Integer.MAX_VALUE;
        for (int i = 0; i < ROWS; i++) {
            int distance = Math.abs(map.getPortal("pt" + i + '0').getPosition().y - y);
            if (distance < nearest) {
                row = i;
                nearest = distance;
            }
        }
        long now = GCMovement.movementNowMs();
        for (int candidates = possible[row]; candidates != 0; candidates &= candidates - 1) {
            int column = Integer.numberOfTrailingZeros(candidates);
            if (now < retryAt[row * COLUMNS + column]) continue;
            Portal portal = map.getPortal("pt" + row + column);
            var approach = GCMovement.portalApproachPoint(map, portal);
            if (portal.getPortalStatus() && GCMovement.canPathTo(bot, approach.x, approach.y)) {
                return new Walk(portal, row, column);
            }
        }
        return null;
    }
}
