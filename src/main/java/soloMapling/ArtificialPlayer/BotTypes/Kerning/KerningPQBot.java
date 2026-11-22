package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.ArtificialPlayer.PartyQuest.PqStageChat;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Plays Kerning PQ ("First Time Together") with a real player leading.
 *
 * <p>Five stages, and which one is in play is read from the quest's own per-stage flags
 * rather than from the map: the four stage rooms are separate maps, but the flag is what says
 * whether a stage is behind the party or still ahead, and the quest sets it itself.
 *
 * <p>Stages 2 to 4 are where this bot earns its keep. The party is supposed to find the right
 * arrangement of bodies by trying and being told; the quest stores the answer in an instance
 * property before anyone tries, so the bot reads it and goes straight to its spot. It also
 * announces the spots that still need someone, because the real player is one of the bodies
 * the quest counts - a bot that quietly claimed a spot would break the count for everybody.
 *
 * <p>In stage 1 the bot collects its own coupons and posts the resulting pass at Cloto.
 * The final turn-in stays with the leader.
 */
public class KerningPQBot extends PartyQuestBot {

    private final ReentrantLock couponWork = new ReentrantLock();
    private volatile KerningStages.CouponRun couponRun;

    public KerningPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
        botType = "KerningPQBot";
        questName = "KerningPQ";
        // The stage scripts resolve their speaker through client.getPlayer() when they run,
        // so this bot needs a client of its own rather than the shared per-channel one.
        BotGeneration.adoptPrivateClient(character);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        tickPartyQuest();
    }

    @Override
    protected boolean isInsideQuest(int mapId) {
        return mapId >= KerningPqData.STAGE_1 && mapId <= KerningPqData.BONUS_MAP
                || mapId == KerningPqData.EXIT_MAP;
    }

    @Override
    protected int lobbyMapId() {
        return KerningPqData.RECRUIT_MAP;
    }

    @Override
    protected String questChatPool(Beat beat) {
        int stage = stageOf(getChr().getMapId());
        if (beat == Beat.START && stage != 1) return null;
        if (beat != Beat.WORKING) return questName;
        if (stage < 0 || KerningStages.stageCleared(getChr(), stage)) return null;
        return switch (stage) {
            case 1 -> questName;
            case 2, 3, 4 -> questName + ".puzzle";
            case 5 -> questName + ".boss";
            default -> null;
        };
    }

    @Override
    public void grindTick() {
        // Cloto's exchange and the combat sweep both change the coupon count. The shared
        // combat ticker must skip a beat rather than wait for this bot's NPC script.
        if (!couponWork.tryLock()) {
            return;
        }
        try {
            super.grindTick();
        } finally {
            couponWork.unlock();
        }
    }

    @Override
    protected void combatSweep(Character bot) {
        int mapId = bot.getMapId();
        boolean coupons = mapId == KerningPqData.STAGE_1;
        if (!coupons && mapId != KerningPqData.STAGE_5) {
            super.combatSweep(bot);
            return;
        }
        if (getState() == BotState.PAUSE || !(coupons ? KerningStages.needsCoupons(bot, couponRun)
                : !KerningStages.stageCleared(bot, 5) && KerningStages.needsBossPasses(bot))) {
            PqActions.stopPartyLoot(bot);
            PqActions.stopHunting(bot);
            return;
        }
        int[] lootIds = coupons ? KerningStages.COUPON_LOOT_IDS : KerningStages.PASS_LOOT_IDS;
        switch (PqActions.collectPartyMonsterLoot(bot, lootIds, KerningPQBot::eligibleLootPeer)) {
            case NONE -> PqActions.seekAndAttack(bot);
            case COLLECTING -> {
                if (!coupons) PqActions.attack(bot);
            }
            case PICKED_UP -> {
                if (coupons) {
                    EventInstanceManager instance = bot.getEventInstance();
                    if (instance != null && bot.getMapId() == mapId) couponRun(instance).pickedUp(bot);
                }
            }
        }
    }

    private static boolean eligibleLootPeer(BotSM other) {
        if (!(other instanceof KerningPQBot peer) || !peer.getRunning() || peer.getState() == BotState.PAUSE
                || peer.death().isCorpse() || peer.status().isFrozen()) return false;
        Character bot = peer.getChr();
        return bot.getMapId() != KerningPqData.STAGE_1 || KerningStages.needsCoupons(bot, peer.couponRun);
    }

    @Override
    protected boolean fightsOnSweep() {
        return switch (getChr().getMapId()) {
            case KerningPqData.BONUS_MAP -> hasPlayerInRunRoom(KerningPqData.BONUS_MAP);
            case KerningPqData.EXIT_MAP -> false;
            default -> true;
        };
    }

    private KerningStages.CouponRun couponRun(EventInstanceManager instance) {
        if (couponRun == null || couponRun.instance != instance) {
            couponRun = new KerningStages.CouponRun(instance);
        }
        return couponRun;
    }

    @Override
    protected boolean workStage() {
        int mapId = getChr().getMapId();
        if (mapId == KerningPqData.BONUS_MAP) {
            return !hasPlayerInRunRoom(mapId); // Only the combat sweep works this room.
        }
        if (mapId == KerningPqData.EXIT_MAP) {
            settleRewardRoom(KerningPqData.NPC_NELLA);
            return false;
        }
        int stage = stageOf(mapId);
        if (stage < 0) {
            return false;
        }

        switch (stage) {
            case 1 -> {
                if (KerningStages.stageCleared(getChr(), 1)) {
                    sayStageClearOnce(stage);
                }
                couponWork.lock();
                try {
                    Character bot = getChr();
                    EventInstanceManager instance = bot.getEventInstance();
                    if (instance != null) {
                        couponRun(instance).work(bot, this::requestTickWithin);
                    }
                } finally {
                    couponWork.unlock();
                }
            }
            case 2, 3, 4 -> {
                if (KerningStages.stageCleared(getChr(), stage)) {
                    sayStageClearOnce(stage);
                    PqActions.waitNearPuzzleNpc(getChr(), KerningPqData.NPC_CLOTO);
                    return false;
                }
                var plan = KerningStages.takeMyPlace(getChr(), stage, PositionCombination.botIndex(getChr()));
                PqStageChat.announcePositionPlan(this, stage, plan, KerningPqData.areasFor(stage));
            }
            case 5 -> {
                if (KerningStages.stageCleared(getChr(), 5)) {
                    // No WZ link to the bonus. Cloto rewards each member separately and
                    // sends that member to the bonus on the confirmation click.
                    PqActions.stopHunting(getChr());
                    if (hasPlayerInRunRoom(KerningPqData.BONUS_MAP)) {
                        PqActions.talkTo(getChr(), KerningPqData.NPC_CLOTO, 0);
                    } else {
                        PqActions.waitNearStageNpc(getChr(), KerningPqData.NPC_CLOTO);
                    }
                    return false;
                }
                if (!KerningStages.needsBossPasses(getChr())) {
                    PqActions.deliverAndWait(getChr(), KerningPqData.PASS, KerningPqData.NPC_CLOTO);
                }
            }
            default -> {
                return false;
            }
        }
        return false;
    }

    /** Which stage a room is, by its position in the quest's map run. */
    private static int stageOf(int mapId) {
        if (mapId < KerningPqData.STAGE_1 || mapId > KerningPqData.STAGE_5) {
            return -1;
        }
        return mapId - KerningPqData.STAGE_1 + 1;
    }

    @Override
    protected void releaseRoomState() {
        super.releaseRoomState();
        couponWork.lock();
        try {
            couponRun = null;
        } finally {
            couponWork.unlock();
        }
    }
}
