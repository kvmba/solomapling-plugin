package soloMapling.ArtificialPlayer.BotTypes.Henesys;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;

/**
 * Plays Henesys PQ ("Primrose Hill") with a real player leading.
 *
 * <p>The quest is one map and two halves, so this bot is correspondingly small: plant
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

    private static final long SETTLE_MS = 1_200;
    private static final int MAX_PLANT_PASSES = 40;

    /** Whether the planting half is done and the cakes are being made. */
    private boolean guarding;

    public HenesysPQBot(Character character) {
        super(character);
        dialoguePath = "HenesysPQBotDialogue.yaml";
        botType = "HenesysPQBot";
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
        return mapId == HenesysPqData.ENTRY_MAP;
    }

    @Override
    protected int lobbyMapId() {
        return HenesysPqData.LOBBY_MAP;
    }

    @Override
    protected boolean workStage() {
        if (!guarding && HenesysStages.flowersBloomed(getChr()) < HenesysPqData.FLOWERS_TO_BLOOM) {
            plantUntilBloomed();
            // The moon message the sixth flower raises is what flips the quest to its second
            // half; read it rather than assuming, in case a drop was refused.
            guarding = HenesysStages.flowersBloomed(getChr()) >= HenesysPqData.FLOWERS_TO_BLOOM;
            return false;
        }
        guarding = true;
        return HenesysStages.guardMoonBunny(getChr());
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
