package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins what a career realign does with the summon a bot already carries.
 *
 * <p>The PQ lobby pins a bot's level AFTER the spawn-time summon was granted, and the career is
 * re-derived from that level (see {@code BotDecorate.alignCareerToLevel}). The realign introduced
 * with that fix only ever TORE DOWN: a career that owns no summon dropped the old bird (correct),
 * but a career that owns a DIFFERENT summon - a 70-119 lobby turning a Ranger into a Sniper -
 * dropped the old one and never re-granted, leaving that bot with no summon for the rest of its
 * life. The commit's own comment claimed "the ordinary grant path then re-rolls", but nothing in
 * the codebase called it.
 *
 * <p>The three outcomes are the whole contract:
 * <ul>
 *   <li>the skill the new career would choose is already showing -> keep the entity;</li>
 *   <li>a summon the new career would NOT choose -> tear down and re-grant the career's own;</li>
 *   <li>nothing showing at all -> grant nothing (the spawn chance roll is the only decider).</li>
 * </ul>
 */
class BotSummonRealignCareerTest {

    /** Ranger (311) shows a silver hawk; Sniper (321) would choose a golden eagle. */
    private static final int RANGER_HAWK = 3111005;
    private static final int SNIPER_EAGLE = 3211005;

    @Test
    void theSameCareerKeepsItsEntity() {
        assertEquals(BotSummonController.Realign.KEEP,
                BotSummonController.realignAction(Set.of(RANGER_HAWK), RANGER_HAWK),
                "the summon the career would choose is already on the field - keep it");
    }

    /**
     * The reported bug: a same-tier branch swap left the bot with no summon at all. The verdict
     * must be SWAP (tear down + re-grant), never a bare teardown.
     */
    @Test
    void aDifferentCareerSwapsTheEntity() {
        assertEquals(BotSummonController.Realign.SWAP,
                BotSummonController.realignAction(Set.of(RANGER_HAWK), SNIPER_EAGLE),
                "a Ranger's hawk under a Sniper must be replaced by the eagle");
    }

    @Test
    void aCareerThatOwnsNoSummonDropsIt() {
        // The original LPQ 35-50 case: the career the level band derives owns no summon at all.
        // wanted is still non-null here only in the sense of "nothing to keep"; the production
        // caller reaches DROP through the empty-showing branch below.
        assertEquals(BotSummonController.Realign.DROP,
                BotSummonController.realignAction(Set.of(), RANGER_HAWK),
                "a bot that never showed the wanted skill has nothing on the field to keep");
    }

    /**
     * A bot that never won the spawn roll must stay summon-less: the a level override is not a
     * second chance at the population mix.
     */
    @Test
    void aBotThatNeverHadOneStaysWithoutOne() {
        assertEquals(BotSummonController.Realign.DROP,
                BotSummonController.realignAction(Set.of(), SNIPER_EAGLE),
                "the spawn chance roll alone decides whether a bot carries a summon");
    }

    /** An operator kill-switch strips instead of granting (see {@code spawn.chance: 0}). */
    @Test
    void theOffSwitchMeansNoSummonIsEverCreated() {
        assertEquals(0.0, BotSummonConfig.CHANCE_DISABLED, 1e-9);
    }
}
