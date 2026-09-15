package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

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
 * <p>Stage 1 is the one this bot only half does: it gathers coupons, but the questions come
 * from Cloto and only the leader may spend the passes, so the turn-in stays with the player.
 */
public class KerningPQBot extends PartyQuestBot {

    private static final long SETTLE_MS = 1_200;

    /**
     * Which body this bot is, for splitting the wanted spots.
     *
     * <p>Counted from character ids among the bots in the same party: a stable order that
     * needs no setup and no knowledge of the other bots, so two Kerning bots on one stage
     * always take different spots while a lone bot always takes the same one.
     */
    private int botIndex() {
        var party = getChr().getParty();
        if (party == null) {
            return 0;
        }
        int mine = getChr().getId();
        int ahead = 0;
        for (var member : party.getMembers()) {
            int id = member.getId();
            if (id != mine && id < mine
                    && CharacterStorage.getBotById(id) != null) {
                ahead++;
            }
        }
        return ahead;
    }

    /** The stage whose plan was last announced, so the party is not spammed with it. */
    private int announcedStage = -1;

    public KerningPQBot(Character character) {
        super(character);
        dialoguePath = "KerningPQBotDialogue.yaml";
        botType = "KerningPQBot";
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
        return mapId >= KerningPqData.STAGE_1 && mapId <= KerningPqData.STAGE_5;
    }

    @Override
    protected int lobbyMapId() {
        return KerningPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        int stage = stageOf(getChr().getMapId());
        if (stage < 0) {
            return false;
        }

        switch (stage) {
            case 1 -> {
                if (KerningStages.stageCleared(getChr(), 1)) {
                    return false; // the portal to stage 2 is open; the leader walks it
                }
                KerningStages.workCoupons(getChr());
            }
            case 2, 3, 4 -> {
                if (KerningStages.stageCleared(getChr(), stage)) {
                    return false;
                }
                String plan = KerningStages.takeMyPlace(getChr(), stage, botIndex());
                announcePlanOnce(stage, plan);
            }
            case 5 -> {
                if (KerningStages.stageCleared(getChr(), 5)) {
                    return true; // all five done: the quest is over
                }
                KerningStages.fightBoss(getChr());
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

    /**
     * Tell the party the spots that still need a body, once per stage.
     *
     * <p>The quest counts everyone in the instance, the player included, so the spots this bot
     * did not take are the player's to fill. Saying so once is help; saying it every tick is
     * noise.
     */
    private void announcePlanOnce(int stage, String plan) {
        if (plan == null || announcedStage == stage) {
            return;
        }
        announcedStage = stage;
        PqActions.say(getChr(), "Stage " + stage + ": " + plan);
    }
}
