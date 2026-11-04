package soloMapling.ArtificialPlayer.BotCommandsPack;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.constants.id.MapId;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementRecording;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.HashMap;
import java.util.Map;

import static soloMapling.ArtificialPlayer.BotClientHandler.getBotClient;
import static soloMapling.ArtificialPlayer.BotMovementSystem.InPacketReader.getMovementRecording;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.BotMoveStreamOffset;
import static soloMapling.DebugUtilities.debugprint;

public class WarpCommands {

    /**
     * The map a bot should warp to: resolved on the bot's OWN channel.
     *
     * <p>A map is a per-channel object. Taking it from the channel-1 client (the old behaviour)
     * would move a bot living on another channel into a foreign channel's map instance, where
     * its broadcasts go to the wrong players and the players who should see it see nothing.
     */
    public static MapleMap mapForBot(Character bot, int mapId) {
        // A bot always carries its channel's client, but fall back to the shared channel-1
        // client rather than NPE if one is ever seen without it.
        Client client = (bot != null && bot.getClient() != null) ? bot.getClient() : getBotClient();
        return client.getChannelServer().getMapFactory().getMap(mapId);
    }

    public static Map<Integer, Integer> FMRoomWarpPortalId = new HashMap<>();

    static {
        FMRoomWarpPortalId.put(1, 11); // henesysDoorPortalId 1
        for (int i = 2; i <= 6; i++) {
            FMRoomWarpPortalId.put(i, 10); // henesysDoorPortalId 2-6
        }
        for (int i = 7; i <= 12; i++) {
            FMRoomWarpPortalId.put(i, 10); // Ludibrium
        }
        for (int i = 13; i <= 17; i++) {
            FMRoomWarpPortalId.put(i, 10); // Perion
        }
        for (int i = 18; i <= 22; i++) {
            FMRoomWarpPortalId.put(i, 9); // El Nath
        }

        // Fixing the bug for FM room 10
        FMRoomWarpPortalId.put(10, 11);
    }

    /*
    Bot Enters the portal they're standing on. Does not require Map id's, portal id's, or coordinates.
     */
    public static void botWarpMapOnPortal(Character fakechar) {
        if (fakechar == null || fakechar.getMap() == null) {
            return;
        }
        Portal portal = fakechar.getMap().findClosestPortal(fakechar.getPosition());
        if (portal == null) {
            return;
        }
        // Mirror the host's own portal rule (GenericPortal.enterPortal): a portal whose tm is
        // MapId.NONE (spawn points, exit/one-way pads) does not move the character at all. Without
        // this the bot asked the map factory for map 999999999, which has no WZ image -
        // MapFactory.loadMapFromWz dereferences the missing Data and throws NPE straight out of the
        // bot's tick. A freshly spawned bot's nearest portal is usually such an "sp".
        //
        // A scripted portal is left to the host as well: running it here would need the bot bound to
        // its client (see BotClientBinding), which is a different call path entirely.
        if (portal.getScriptName() != null || portal.getTargetMapId() == MapId.NONE) {
            return;
        }
        try {
            MapleMap to = fakechar.getEventInstance() == null
                    ? mapForBot(fakechar, portal.getTargetMapId())
                    : fakechar.getEventInstance().getMapInstance(portal.getTargetMapId());
            if (to == null) {
                return; // the destination is not loadable on this bot's channel - leave it where it is
            }
            Portal pto = to.getPortal(portal.getTarget());
            if (pto == null) {// fallback for missing portals - no real life case anymore - interesting for not implemented areas
                pto = to.getPortal(0);
            }
            fakechar.changeMap(to, pto);
            botEnterPortalDropDown(fakechar);
        } catch (Exception e) {
            debugprint("botWarpMapOnPortal failed for " + fakechar.getName() + ": " + e);
        }
    }

    public static void botWarpMapOnPortal(Character fakechar, MapleMap warpMap, int portalId) {
        if (fakechar == null || warpMap == null) {
            return;
        }
        fakechar.changeMap(warpMap, portalId);
        botEnterPortalDropDown(fakechar);
    }

    public static void botEnterFMRoom(Character fakechar, int roomNumber) {
        int freeMarketRoom = 910000000 + roomNumber;
        MapleMap warpMap = mapForBot(fakechar, freeMarketRoom);
        botWarpMapOnPortal(fakechar, warpMap, FMRoomWarpPortalId.get(roomNumber));
    }

    public static void botExitFMRoom(Character fakechar, int doorNumber) {
        int freeMarketEntrance = 910000000;
        MapleMap warpMap = mapForBot(fakechar, freeMarketEntrance);
        botWarpMapOnPortal(fakechar, warpMap, getFMEntrancePortal(doorNumber).getId());
    }

    public static Portal getFMEntrancePortal(int doorNumber) {
        int freeMarketEntrance = 910000000;
        String portalName = String.format("in%02d", doorNumber);
        MapleMap warpMap = getBotClient().getChannelServer().getMapFactory().getMap(freeMarketEntrance);
        return warpMap.getPortal(portalName);
    }

    public static Portal getFMEUpArrows(int upArrow) {
        // upArrow 0 = row 1 -> 2
        // upArrow 1 = row 2 -> 3
        // upArrow 2 = row 3 -> 4
        int freeMarketEntrance = 910000000;
        String arrowName = String.format("up%02d", upArrow);
        MapleMap warpMap = getBotClient().getChannelServer().getMapFactory().getMap(freeMarketEntrance);
        return warpMap.getPortal(arrowName);
    }

    // Deliberate synchronous choreography: fake portal lag, then a blocking
    // recording replay. Part of the spawn/warp arrival scripts that hold their thread.
    public static void botEnterPortalDropDown(Character fakechar, int variablePortalLag) {
        // A bot under dynamic (GCMovement) control owns its own arrival: its driver plays the
        // map-entry float -> drop from the real terrain the moment the map changes, so replaying
        // the recorded drop on top of it would send a second, conflicting movement packet. The
        // recorded drop is only for the recorded-path bots, which have no driver of their own.
        if (GCMovement.isEnabled(fakechar)) {
            return;
        }
        BotHelpers.blockingSleep(variablePortalLag);
        String recName = "portalenterdrop";
//        List<MovementPacket> mvp = readPacketsFromFile(0, recName);
        MovementRecording mvr = getMovementRecording(0, recName);
        BotMoveStreamOffset(mvr, fakechar);
    }

    public static void botEnterPortalDropDown(Character fakechar) {
        botEnterPortalDropDown(fakechar, 1500);
    }

    //test only
    public static void botMoveMap(Character fakechar, int mapId) {
        MapleMap warpMap = mapForBot(fakechar, mapId);
        fakechar.changeMap(warpMap, warpMap.getPortal(11));

////        MapleMap target = fakechar.getClient().getChannelServer().getMapFactory().getMap(gotomaps.get(params[0]));
//
//        Portal targetPortal = target.findMarketPortal();
////        Portal targetPortal = target.getRandomPlayerSpawnpoint();
//        fakechar.saveLocationOnWarp();
//        fakechar.changeMap(target, targetPortal);
//        // broadcast that player enter
//        // in the air packet, then dropping them
    }
}
