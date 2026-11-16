package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.BotSpotPicker;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;

/** Only sprawling town recruitment maps need a leash; dedicated PQ halls keep their usual behaviour. */
record PqRecruitArea(Point center, int radius) {

    static PqRecruitArea forMap(MapleMap map) {
        int npcId = switch (map.getId()) {
            case 100000200 -> 1012112; // Henesys Park: Tory
            case 103000000 -> 9020000; // Kerning City: Lakelis
            default -> 0;
        };
        if (npcId == 0) {
            return null;
        }
        var npc = map.getNPCById(npcId);
        if (npc == null) {
            throw new IllegalStateException("PQ recruit NPC " + npcId + " missing on map " + map.getId());
        }
        var point = PqRecruitPoints.byName(npcId == 1012112 ? "HenesysPQ" : "KerningPQ");
        // A 40px berth per expected recruit, plus 100px of NPC clearance across the diameter.
        // At normal population: 15 Henesys recruits -> 350px; 12 Kerning recruits -> 300px.
        int radius = Math.max(300, PqBotSpawner.recruitCount(point) * 20 + 50);
        return new PqRecruitArea(npc.getPosition(), radius);
    }

    boolean contains(Point position) {
        return center.distanceSq(position) <= (double) radius * radius;
    }

    List<Point> spots(MapleMap map, Point from, int count) {
        return BotSpotPicker.pickGroundSpotsInRadius(map, from.x, from.y, center, radius, count);
    }

    void stroll(Character bot) {
        if (GCMovement.isMoving(bot)) {
            return;
        }
        Point from = bot.getPosition();
        boolean returning = !contains(from);
        for (Point spot : spots(bot.getMap(), from, 8)) {
            if ((returning || from.distanceSq(spot) >= 60 * 60)
                    && GCMovement.canPathTo(bot, spot.x, spot.y)) {
                GCMovement.move(bot, spot.x, spot.y);
                return;
            }
        }
    }
}
