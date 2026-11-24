package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGrindSystem.MovementStylePolicy;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;

/** Stage 5 room assignments and the GMS083 two-row portal climb to rooms 302/303. */
final class OrbisLounge {
    private static final int BOX_ROOM = 3;
    private static final int ABOVE_301_ROOMS = 0b110;
    private static final String[][] GUESS_DOORS = {
            {"r4rp11", "r4rp12", "r4rp13"},
            {"r4rp21", "r4rp22", "r4rp23"}
    };
    private static final int ALL_CHOICES = 0b111;
    static final long ROOM_DROP_SETTLE_MS = 1_000;

    enum Beat { NO_WORK, WALKING, ADVANCED }

    private int finishedRooms;
    private int failedWalks;
    private boolean stalledClimb;
    private Walk walk;
    private MapleMap quietRoom;
    private long quietSince;

    private static final class Walk {
        final Portal portal;
        final Point approach;
        final int row;
        final int choice;
        int refusedEntries;
        volatile boolean abandoned;

        Walk(Portal portal, Point approach, int row, int choice) {
            this.portal = portal;
            this.approach = approach;
            this.row = row;
            this.choice = choice;
        }
    }

    /** Observations belong to the run, not an individual bot or the map ID. */
    static final class Puzzle {
        private final int[] choices = {ALL_CHOICES, ALL_CHOICES};

        synchronized int choices(int row) {
            return choices[row];
        }

        synchronized boolean enter(Character bot, Walk walk) {
            // A teammate may have ruled this door out while we were walking to it.
            int bit = 1 << walk.choice;
            if ((choices[walk.row] & bit) == 0) return true;

            MapleMap hall = bot.getMap();
            PqActions.enterPortal(bot, walk.portal);
            if (bot.getMap() != hall) return true;
            Point arrival = bot.getPosition();
            if (atPortal(hall, arrival, "fail")) {
                choices[walk.row] &= ~bit;
                return true;
            }
            String success = walk.row == 0 ? GUESS_DOORS[1][walk.choice] : "success";
            if (atPortal(hall, arrival, success)) {
                choices[walk.row] = bit;
                return true;
            }
            return false;
        }

        private static boolean atPortal(MapleMap hall, Point position, String name) {
            Portal portal = hall.getPortal(name);
            return portal != null && GCMovement.withinArrivalBox(position, portal.getPosition());
        }
    }

    void reset() {
        finishedRooms = 0;
        resetHallWalks();
    }

    void finishedRoom(int roomId) {
        finishedRooms |= 1 << OrbisPqData.LOUNGE_ROOMS.indexOf(roomId);
    }

    boolean roomClear(MapleMap room, boolean quiet) {
        if (!quiet) {
            quietRoom = null;
            return false;
        }
        // A last kill/terminal reactor state can be visible before the host creates its drops.
        long now = GCMovement.movementNowMs();
        if (quietRoom == room) return now - quietSince >= ROOM_DROP_SETTLE_MS;
        quietRoom = room;
        quietSince = now;
        return false;
    }

    void resetHallWalks() {
        walk = null;
        failedWalks = 0;
        stalledClimb = false;
        quietRoom = null;
    }

    void stopWalking(Character bot) {
        if (walk != null) {
            GCMovement.stopIfTarget(bot, walk.approach);
            walk = null;
        }
    }

    Beat step(Character bot, Puzzle puzzle) {
        MapleMap hall = bot.getMap();
        if (walk != null && walk.abandoned) {
            failedWalks |= 1 << walk.portal.getId();
            walk = null;
        }
        if (walk != null && (!walk.portal.getPortalStatus()
                || (walk.row >= 0 && (puzzle.choices(walk.row) & (1 << walk.choice)) == 0))) {
            // Don't clear a climber's goal mid-rope to switch to another door.
            if (!GCMovement.isSettled(bot) && GCMovement.hasMoveTarget(bot)) return Beat.WALKING;
            stopWalking(bot);
        }
        if (walk == null) {
            if (!GCMovement.isSettled(bot) && !GCMovement.isClimbing(bot)) return Beat.WALKING;
            walk = chooseDoor(bot, hall, puzzle);
            if (walk == null) return Beat.NO_WORK;
        }
        Walk attempt = walk;
        if (!PqActions.walkToPortal(bot, attempt.portal, () -> attempt.abandoned = true)) {
            return Beat.WALKING;
        }
        if (attempt.row >= 0 ? puzzle.enter(bot, attempt) : enterRoom(bot, hall, attempt.portal)) {
            walk = null;
            if (bot.getMap() != hall) resetHallWalks();
            return Beat.ADVANCED;
        }
        // A refused script/warp is not a wrong guess. Let one transient refusal retry locally.
        if (++attempt.refusedEntries < 2) return Beat.WALKING;
        failedWalks |= 1 << attempt.portal.getId();
        walk = null;
        return Beat.ADVANCED;
    }

    private Walk chooseDoor(Character bot, MapleMap hall, Puzzle puzzle) {
        boolean upper = worksUpperRooms(bot.getJob());
        int assignedRooms = upper ? (1 << OrbisPqData.LOUNGE_ROOMS.size()) - 1 : 1 << BOX_ROOM;
        if ((assignedRooms & ~finishedRooms) == 0) return null;
        // A warm graph for a different jump profile cannot decide which doors this bot can reach.
        if (!GCMovement.ensureGraph(bot)) return null;
        if (upper) {
            for (int room = 0; room < BOX_ROOM; room++) {
                Walk entry = roomDoor(bot, hall, room);
                if (entry != null) return entry;
            }
            if ((~finishedRooms & ABOVE_301_ROOMS) != 0) {
                Walk guess = guessDoor(bot, hall, puzzle);
                if (guess != null) return guess;
            }
        }
        return roomDoor(bot, hall, BOX_ROOM);
    }

    private Walk roomDoor(Character bot, MapleMap hall, int room) {
        return (finishedRooms & (1 << room)) != 0 ? null
                : reachableDoor(bot, hall.getPortal(OrbisPqData.loungeEntryPortal(room)), -1, -1);
    }

    private Walk guessDoor(Character bot, MapleMap hall, Puzzle puzzle) {
        if (stalledClimb) return null;
        Portal success = hall.getPortal("success");
        Portal secondRow = hall.getPortal("r4rp20");
        if (success == null || secondRow == null) return null;
        int secondChoices = puzzle.choices(1);
        if (secondChoices == 0) return null;
        int y = bot.getPosition().y;
        // The climb would only put us back here, with the same unreachable continuation.
        if (y <= success.getPosition().y + 12) {
            stalledClimb = true;
            return null;
        }
        int row = y <= secondRow.getPosition().y + 32 ? 1 : 0;
        int choices = row == 1 ? secondChoices : puzzle.choices(0);
        while (choices != 0) {
            int choice = Integer.numberOfTrailingZeros(choices);
            Walk entry = reachableDoor(bot, hall.getPortal(GUESS_DOORS[row][choice]), row, choice);
            if (entry != null) return entry;
            choices &= choices - 1;
        }
        // After falling back to Eak, do not walk back up just to retry a blocked second row.
        if (row == 1) stalledClimb = true;
        return null;
    }

    private Walk reachableDoor(Character bot, Portal portal, int row, int choice) {
        if (portal == null || !portal.getPortalStatus() || (failedWalks & (1 << portal.getId())) != 0) {
            return null;
        }
        Point approach = GCMovement.portalApproachPoint(bot.getMap(), portal);
        return GCMovement.canPathTo(bot, approach.x, approach.y) ? new Walk(portal, approach, row, choice) : null;
    }

    private static boolean worksUpperRooms(Job job) {
        return job != null && (MovementStylePolicy.hasTeleportSkill(job.getId())
                || job.isA(Job.ASSASSIN) || job.isA(Job.BANDIT));
    }

    private static boolean enterRoom(Character bot, MapleMap hall, Portal portal) {
        PqActions.enterPortal(bot, portal);
        return bot.getMap() != hall;
    }
}
