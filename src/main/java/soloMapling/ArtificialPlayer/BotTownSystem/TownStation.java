package soloMapling.ArtificialPlayer.BotTownSystem;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotSpotClaims;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


// Positional life for OLD-engine stationed town bots (SocialBot) without a GC retrofit: hold a claimed
// ledge in town and occasionally drift to a fresh anchor-weighted spot, so a town crowd looks alive rather
// than pinned to its spawn pixel.
//
// Engine-agnostic on the claim side (pure BotSpotClaims registry keyed by (mapId, ledgeId), read via the
// terrain-only GCMovement.regionIdAt that never GC-drives the bot), so it shares the SAME registry and keys
// as TownLoiter (which returning training bots use). That is what makes cross-cohort coordination fall out:
// a stationed SocialBot and a returning TrainingBot now see each other and won't stack past CAPACITY on one
// ledge.
//
// The drift walk itself no longer lives here. It used to be relocate() -> pathFinderAware (old-engine
// pathfind over recorded movement packets), which silently no-opped on every town in
// EnvironmentPopulation.yaml - those maps have no recordings - so stationed bots never moved at all.
// SocialBot now drifts with GCMovement.move (GC engine, WZ terrain, no recordings).
//
// Our own creation (not a GreenCat extraction).
public final class TownStation {

    private TownStation() {
    }

    // How many bots may claim one ledge before we prefer another. MUST match TownLoiter.CAPACITY_PER_LEDGE
    // so the shared BotSpotClaims cap is enforced consistently across both town consumers (Gap #3).
    private static final int CAPACITY = 3;

    // botId -> the ledge claim it holds, so releaseSpot() frees exactly what claimSpot() took.
    private record Claim(int mapId, int ledgeId) {
    }

    private static final Map<Integer, Claim> ACTIVE = new ConcurrentHashMap<>();

    // Guards release-prior + claim + record so it is atomic against a concurrent releaseSpot() from teardown
    // on another thread (same discipline as TownLoiter's CLAIM_LOCK).
    private static final Object CLAIM_LOCK = new Object();

    // Claim the ledge the bot currently stands on (terrain-only resolve; no movement). Idempotent: any prior
    // claim is released first. No-op if the bot is on no baked ledge (nothing to arbitrate). Returns true if
    // a claim is now held.
    public static boolean claimSpot(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return false;
        }
        MapleMap map = bot.getMap();
        int mapId = bot.getMapId();
        int botId = bot.getId();
        Point p = bot.getPosition();
        int ledgeId = GCMovement.regionIdAt(map, p.x, p.y); // terrain-only, safe on an old-engine bot

        synchronized (CLAIM_LOCK) {
            releaseLocked(botId); // idempotent re-claim: free the previous ledge first
            if (ledgeId < 0) {
                return false; // no ledge under the bot - accept uncontended, nothing to claim
            }
            if (BotSpotClaims.claim(mapId, ledgeId, CAPACITY, botId) >= 0) {
                ACTIVE.put(botId, new Claim(mapId, ledgeId));
                return true;
            }
        }
        return false; // ledge already at capacity - stay put uncontended, no relocate here (best-effort)
    }

    // Release the bot's ledge claim. Idempotent - safe when not claimed. Call on teardown / convert / before
    // a relocation walk.
    public static void releaseSpot(Character bot) {
        if (bot == null) {
            return;
        }
        synchronized (CLAIM_LOCK) {
            releaseLocked(bot.getId());
        }
    }

    public static boolean isStationed(Character bot) {
        return bot != null && ACTIVE.containsKey(bot.getId());
    }

    // Release the bot's ledge claim. Caller must hold CLAIM_LOCK.
    private static void releaseLocked(int botId) {
        Claim c = ACTIVE.remove(botId);
        if (c != null) {
            BotSpotClaims.release(c.mapId(), c.ledgeId(), botId);
        }
    }
}
