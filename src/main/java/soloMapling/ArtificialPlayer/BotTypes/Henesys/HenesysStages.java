package soloMapling.ArtificialPlayer.BotTypes.Henesys;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What a bot does in each half of Henesys PQ.
 *
 * <p>First half: take a seed off a leaf and plant it on the flower that accepts it. The
 * pairing is fixed in the reactor data, so the bot looks it up instead of testing it, and
 * the quest's own {@code stage} counter - which the flowers bump when one takes - says
 * whether it worked. Nothing is guessed.
 *
 * <p>Second half: keep the monsters off Moon Bunny while the players collect the rice cakes.
 */
public final class HenesysStages {

    private HenesysStages() {
    }

    // =========================================================================
    // First half - plant the six primroses
    // =========================================================================

    // One claimant per seed color, scoped to the actual room rather than its shared map id.
    private static final Map<MapleMap, int[]> FLOWER_CLAIMS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Work on the claimed flower until its seed is accepted by the quest script. */
    static void plantOneFlower(Character bot) {
        // Let seed pickup or planting take the wheel even if a leaf is still active.
        PqActions.armReactorBeat(bot, -1);
        Reactor flower = claimFlower(bot);
        if (flower == null) return;
        int index = flower.getId() - HenesysPqData.FLOWER_FIRST;
        int seed = HenesysPqData.seedForFlower(index);
        int oid = flower.getObjectId();

        // An already planted seed lies on the ground until the delayed trigger runs.
        if (feedOrRelease(bot, oid, seed) || PqActions.countItem(bot, seed) > 0) return;
        switch (PqActions.collectPartyQuestLoot(bot, new int[]{seed})) {
            case PICKED_UP -> feedOrRelease(bot, oid, seed);
            case COLLECTING -> { }
            case NONE -> PqActions.workActiveReactors(bot, HenesysPqData.LEAF_FIRST + index);
        }
    }

    private static boolean feedOrRelease(Character bot, int oid, int seed) {
        var result = PqActions.feedReactorStep(bot, oid, seed);
        if (result == PqActions.FeedResult.STUCK) {
            PqActions.deferReactorApproach(bot, oid);
            releaseFlowerClaim(bot);
        }
        return result != PqActions.FeedResult.NO_ACTION;
    }

    private static Reactor claimFlower(Character bot) {
        MapleMap map = bot.getMap();
        var flowers = map.getReactorsByIdRange(HenesysPqData.FLOWER_FIRST, HenesysPqData.FLOWER_LAST);
        int[] claims = FLOWER_CLAIMS.computeIfAbsent(map, room -> new int[HenesysPqData.SEED_TYPES]);
        int botId = bot.getId();
        synchronized (claims) {
            Reactor free = null;
            for (Reactor flower : flowers) {
                int index = flower.getId() - HenesysPqData.FLOWER_FIRST;
                if (!flower.isActive() || flower.getState() != 0) {
                    claims[index] = 0;
                    continue;
                }
                int owner = claims[index];
                if (owner == botId) return flower;
                if ((free == null || flower.getId() < free.getId())
                        && (owner == 0 || !isPlantingInRoom(owner, map))
                        && !PqActions.reactorApproachDeferred(bot, flower.getObjectId())) {
                    free = flower;
                }
            }
            if (free != null) claims[free.getId() - HenesysPqData.FLOWER_FIRST] = botId;
            return free;
        }
    }

    private static boolean isPlantingInRoom(int botId, MapleMap room) {
        return CharacterStorage.getBotById(botId) instanceof HenesysPQBot planter
                && planter.getChr().getMap() == room && planter.getRunning()
                && planter.getState() != BotSM.BotState.PAUSE && !planter.death().isCorpse();
    }

    static void releaseFlowerClaim(Character bot) {
        int[] claims = FLOWER_CLAIMS.get(bot.getMap());
        if (claims == null) return;
        int botId = bot.getId();
        synchronized (claims) {
            for (int i = 0; i < claims.length; i++) {
                if (claims[i] == botId) {
                    claims[i] = 0;
                    return;
                }
            }
        }
    }

    /** How many flowers have taken a seed, from the quest's own {@code stage} counter. */
    public static int flowersBloomed(Character bot) {
        return PqActions.readEimInt(bot, "stage", 0);
    }

    // =========================================================================
    // Second half - protect Moon Bunny
    // =========================================================================

    /** Guard until the leader submits the cakes; leave the drops for players. */
    public static void guardMoonBunny(Character bot) {
        if (stageCleared(bot)) {
            PqActions.stopHunting(bot);
            PqActions.waitNearStageNpc(bot, HenesysPqData.GROWLIE);
        } else {
            PqActions.seekAndAttack(bot);
        }
    }

    static boolean stageCleared(Character bot) {
        return PqActions.readEimString(bot, "1stageclear") != null
                || PqActions.readEimInt(bot, "stage", 0) > HenesysPqData.FLOWERS_TO_BLOOM;
    }

    /** How many cakes the run has produced so far, from the quest's counter. */
    public static int cakesMade(Character bot) {
        return PqActions.readEimInt(bot, "bunnyCake", 0);
    }
}
