package soloMapling.ArtificialPlayer.BotTownSystem;

import java.util.Set;

// Maps that pass the town test (WZ `town=1`, no mobs, one portal hop from a real town) but are actually
// vertical shafts a community bot must never make its destination: the whole map is one rope/ladder the
// full height of the shaft, with the only floor at the bottom - so a bot that enters spends its life
// climbing and falling and reads as a stuck statue. The town consumers that pick a cross-map destination
// by "mob-free neighbour" (TownWandererBot's map family, SocialBot's cross-map stroll) consult this list
// and treat these as non-destinations, exactly as they already skip a mob-bearing neighbour.
//
// Grinders are unaffected by construction: TrainingMapFinder only ever targets maps that HAVE mobs, and
// these have none. GCTravel routing is likewise untouched - these maps stay valid intermediate hops for a
// trip that genuinely has to pass through; only a roam/station DESTINATION is forbidden.
//
// The list is data the owner curates (add an id + a why-comment). Modelled on TownOverrides.isBanned.
//
// Re-derive the candidates any time with scripts/scan_shaft_maps.py: it scans Map.wz for narrow, tall
// maps whose one connector spans most of the shaft, and reports which of them a town bot can actually
// select (one walkable portal hop from a configured town map). The last run found 11 shaft-shaped maps,
// of which exactly these two were selectable; the rest sit in instance/maze sub-maps no town reaches.
public final class TownRoamMaps {

    private TownRoamMaps() {
    }

    // 222000001 童话村[井口]: a well whose only floor is 977px below the exit portal; the single rope
    // (x=-51, span [-499,474]) is the only way up.
    // 221000001 地球防御本部[通道]: the connector shaft whose single ladder (x=76, span [-957,76]) climbs
    // ~1033px between the town and the 本部.
    private static final Set<Integer> BANNED = Set.of(
            222000001,
            221000001
    );

    /** True when mapId is a shaft-shaped town map a roaming/stationed bot must not pick as a destination. */
    public static boolean isBanned(int mapId) {
        return BANNED.contains(mapId);
    }
}
