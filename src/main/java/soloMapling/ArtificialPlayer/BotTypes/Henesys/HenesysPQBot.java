package soloMapling.ArtificialPlayer.BotTypes.Henesys;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

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
    private static final long SETTLE_MS = 1_200;
    private static final int MAX_PLANT_PASSES = 40;

    /** Whether the planting half is done and the cakes are being made. */
    private boolean guarding;

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
            case HenesysPqData.ENTRY_MAP -> !HenesysStages.stageCleared(getChr())
                    && !HenesysStages.deliversCakes(getChr());
            case HenesysPqData.BONUS_MAP -> hasPlayerInRunRoom(HenesysPqData.BONUS_MAP);
            default -> false;
        };
    }

    @Override
    protected int[] steppedOnLootIds() {
        return getChr().getMapId() == HenesysPqData.ENTRY_MAP && HenesysStages.needsCakes(getChr())
                ? CAKE_LOOT : super.steppedOnLootIds();
    }

    /**
     * Forget which half of the quest the last visit was in.
     *
     * <p>{@code guarding} is a per-run latch, and the bot object outlives the run: the same
     * instance is recruited for the next party, so without this reset the second visit starts
     * already believing the flowers are planted and skips straight to guarding Moon Bunny - which
     * never spawns until six seeds are planted, leaving the bot standing in an empty field for the
     * whole run. The base class calls this on every run end (and on stop/retype), which is the
     * same seam Ludi's announcement flags reset at.
     */
    @Override
    protected void releaseRoomState() {
        super.releaseRoomState();
        guarding = false;
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
        if (!guarding && HenesysStages.flowersBloomed(getChr()) < HenesysPqData.FLOWERS_TO_BLOOM) {
            plantUntilBloomed();
            // The moon message the sixth flower raises is what flips the quest to its second
            // half; read it rather than assuming, in case a drop was refused.
            guarding = HenesysStages.flowersBloomed(getChr()) >= HenesysPqData.FLOWERS_TO_BLOOM;
            if (guarding) {
                // The planting half is done: the moon is full and Moon Bunny is cooking.
                sayStageClearOnce(HenesysPqData.ENTRY_MAP);
            }
            return false;
        }
        guarding = true;
        HenesysStages.guardMoonBunny(getChr());
        return false;
    }

    /**
     * Keep planting until the dial is full, within a pass budget.
     *
     * <p>A pass that plants nothing leaves the bot where it can try again next tick, which
     * matters when the leaves have not regrown yet - the quest's own pacing, not a failure.
     */
    private void plantUntilBloomed() {
        for (int pass = 0; pass < MAX_PLANT_PASSES; pass++) {
            if (HenesysStages.flowersBloomed(getChr()) >= HenesysPqData.FLOWERS_TO_BLOOM) {
                return;
            }
            if (!HenesysStages.plantOneFlower(getChr())) {
                // Nothing to plant this pass; give the map a moment and try the next leaf.
                sleep(SETTLE_MS);
                return;
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
