package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Monster Carnival against a real party - the only bot here that is an opponent rather
 * than a teammate.
 *
 * <p>CPQ is two parties fighting over the same arena and the same clock. A bot on the
 * opposing side is not helping anyone; it is standing in for the team the player could not
 * find, which is a different job from every other bot in this package. What it should do is
 * therefore not "help" but "play": kill what it can reach, spend its Carnival Points on
 * monsters when it has them, and lose gracefully when it is outmatched.
 *
 * <p>It stays deliberately unambitious about tactics. It cycles through the arena's own
 * summon list rather than picking a "best" monster, because the choice that would matter -
 * how hard to push against this particular party - is not something it can read off the
 * instance, and a bot that guesses wrong is worse than one that plays steadily.
 *
 * <p>The engine does the work the bot would otherwise have to fake: CP is awarded on kills by
 * {@code Character.gainCP}, the arena adds the summoned monster itself once the request is
 * accepted, and the win is decided by the counters. The bot's whole part is to keep fighting
 * and to ask for summons it can afford.
 */
public class CarnivalPQBot extends PartyQuestBot {

    private static final int FIGHT_PASSES = 20;

    /** Which entry of the arena's summon list to try next, cycled. */
    private int nextSummon;

    public CarnivalPQBot(Character character) {
        super(character);
        dialoguePath = "CarnivalPQBotDialogue.yaml";
        botType = "CarnivalPQBot";
        // The arena resolves its player through client.getPlayer() when awarding CP and when
        // adding a summoned monster, so this bot needs a client of its own.
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
        return CarnivalPqData.isArena(getChr().getMap())
                || CarnivalPqData.isLobby(getChr().getMap());
    }

    @Override
    protected int lobbyMapId() {
        return CarnivalPqData.lobbyForLevel(getChr().getLevel());
    }

    @Override
    protected boolean workStage() {
        var map = getChr().getMap();
        if (!CarnivalPqData.isArena(map)) {
            // In the lobby: nothing to do until the match starts. The run itself puts both
            // sides into the arena with forceChangeMap.
            return false;
        }

        trySummon();

        // Then fight. CP comes from kills, which is what the match is scored on, so this is
        // the bot's actual contribution rather than a filler step.
        if (!map.getMonsters().isEmpty()) {
            for (int pass = 0; pass < FIGHT_PASSES && !map.getMonsters().isEmpty(); pass++) {
                PqActions.seekAndAttack(getChr());
            }
        }
        return false;
    }

    /**
     * Ask the arena for the next monster this side can afford.
     *
     * <p>Reads the arena's own list of summonable monsters and their costs - the same list the
     * client's request indexes into - rather than carrying ids, and checks the same two
     * conditions the server does. A request that fails both checks would simply be refused, so
     * this avoids making it.
     */
    private void trySummon() {
        var options = CarnivalPqData.summonableOn(getChr().getMap());
        if (options.isEmpty() || !sideHasSummonsLeft()) {
            return;
        }
        // Try each entry once, starting from where the last call left off, so the bot cycles
        // through what the arena offers instead of hammering one it cannot afford.
        for (int attempt = 0; attempt < options.size(); attempt++) {
            int index = nextSummon % options.size();
            nextSummon++;
            if (CarnivalSummons.request(getChr(), index)) {
                return;
            }
        }
    }

    /**
     * Whether this side still has summons left.
     *
     * <p>The allowance is the arena's to track and it decrements as monsters are called, so the
     * bot does not keep its own count - it reads the run's.
     */
    private boolean sideHasSummonsLeft() {
        var carnival = getChr().getMonsterCarnival();
        if (carnival == null) {
            return false;
        }
        return getChr().getTeam() == 0 ? carnival.canSummonR() : carnival.canSummonB();
    }
}
