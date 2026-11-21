package soloMapling.ArtificialPlayer.BotTypes.Henesys;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;

/**
 * Plays Henesys PQ ("Primrose Hill") with a real player leading.
 *
 * <p>The main room has two halves: plant
 * primroses until the moon dial is full, then guard Moon Bunny while the rice cakes come
 * out. Which half it is in is decided by the quest's own {@code stage} counter rather than
 * by a timer, because the second half starts exactly when the sixth flower takes.
 *
 * <p>Choosing this quest first was deliberate: it is the one a low-level player can reach
 * (level 10, party of three) and it exercises the whole capability layer - reactors that
 * drop, reactors that take a drop, a published counter to read, and monsters to fight -
 * with none of Orbis's room routing. If something in {@code PqActions} is broken, this is
 * where it shows up soonest.
 */
public class HenesysPQBot extends PartyQuestBot {

    private static final int[] CAKE_LOOT = {HenesysPqData.RICE_CAKE};

    public HenesysPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
        botType = "HenesysPQBot";
        questName = "HenesysPQ";
        // The quest's reactors resolve their player through client.getPlayer() when they
        // script, so this bot needs a client of its own for the same reason the Orbis one
        // does - the shared per-channel client would answer with whichever bot bound last.
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
        return switch (mapId) {
            case HenesysPqData.ENTRY_MAP, HenesysPqData.REWARD_MAP,
                    HenesysPqData.BONUS_MAP, HenesysPqData.FAILED_MAP, HenesysPqData.BONUS_EXIT_MAP -> true;
            default -> false;
        };
    }

    @Override
    protected int lobbyMapId() {
        return HenesysPqData.LOBBY_MAP;
    }

    @Override
    protected boolean fightsOnSweep() {
        return switch (getChr().getMapId()) {
            case HenesysPqData.ENTRY_MAP -> plantingDone() && !HenesysStages.stageCleared(getChr())
                    && !HenesysStages.deliversCakes(getChr());
            case HenesysPqData.BONUS_MAP -> hasPlayerInRunRoom(HenesysPqData.BONUS_MAP);
            default -> false;
        };
    }

    @Override
    protected int[] steppedOnLootIds() {
        return getChr().getMapId() == HenesysPqData.ENTRY_MAP
                && plantingDone() && HenesysStages.needsCakes(getChr())
                ? CAKE_LOOT : super.steppedOnLootIds();
    }

    private boolean plantingDone() {
        return HenesysStages.flowersBloomed(getChr()) >= HenesysPqData.FLOWERS_TO_BLOOM;
    }

    @Override
    protected void releaseRoomState() {
        super.releaseRoomState();
        HenesysStages.releaseFlowerClaim(getChr());
    }

    @Override
    protected boolean workStage() {
        switch (getChr().getMapId()) {
            case HenesysPqData.REWARD_MAP, HenesysPqData.BONUS_EXIT_MAP -> {
                settleRewardRoom(HenesysPqData.TORY);
                return false;
            }
            case HenesysPqData.FAILED_MAP -> {
                settleRewardRoom(HenesysPqData.TOMMY);
                return false;
            }
            case HenesysPqData.BONUS_MAP -> {
                return shouldLeaveBonusRoom(hasPlayerInRunRoom(HenesysPqData.BONUS_MAP));
            }
            default -> { }
        }
        if (!plantingDone()) {
            HenesysStages.plantOneFlower(getChr());
        } else {
            sayStageClearOnce(HenesysPqData.ENTRY_MAP);
            HenesysStages.guardMoonBunny(getChr());
        }
        return false;
    }
}
