package soloMapling.companion.execution;

import org.gms.client.Character;

/**
 * Optional high-level training capability for a persistent companion BotSM.
 *
 * <p>The controller remains owned by the companion implementation; execution
 * must not replace that BotSM with an ambient TrainingBot.</p>
 */
public interface CompanionTrainingController {

    ActionExecutionResult beginTrainingWith(Character target);

    void stopTraining();

    /**
     * Stops every high-level activity that would resume movement after REST or
     * GOODBYE. Implementations with no additional activity can use the default.
     */
    default void stopForRest() {
        stopTraining();
    }
}
