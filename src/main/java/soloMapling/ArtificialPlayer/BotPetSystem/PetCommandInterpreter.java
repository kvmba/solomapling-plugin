package soloMapling.ArtificialPlayer.BotPetSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure picker over {@link PetInteractionTable.Interact} entries: choose the
 * behaviour a pet attempts this time, and decide whether it obeys or refuses.
 * Kept free of host/WZ types so the rule is unit-testable.
 *
 * <p>Mirrors the WZ model: a pet has a set of level-appropriate commands, and
 * each command carries a {@code prob} which is the chance the pet OBEYS it
 * (the host rolls exactly this to pick the success vs fail branch). So we pick
 * uniformly among the pet's level-valid commands and then roll {@code prob} to
 * choose success (the {@code success} act/line) or refusal (the {@code fail}
 * act/line) — both branches use the pet's own speech corpus.</p>
 */
public final class PetCommandInterpreter {

    private PetCommandInterpreter() {
    }

    /**
     * Pick one interaction the pet may attempt at {@code petLevel}. Uniform among
     * entries whose level band contains the pet level and whose prob is positive.
     * Returns {@code null} when none apply.
     */
    public static PetInteractionTable.Interact pick(
            List<PetInteractionTable.Interact> interactions, int petLevel, Random rng) {
        if (interactions == null || interactions.isEmpty()) {
            return null;
        }
        List<PetInteractionTable.Interact> valid = new ArrayList<>();
        for (PetInteractionTable.Interact it : interactions) {
            if (it.allowsLevel(petLevel) && it.prob() > 0) {
                valid.add(it);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        return valid.get(rng.nextInt(valid.size()));
    }

    /**
     * Whether the pet obeys the chosen command: {@code prob} percent of the time.
     * {@code prob <= 0} never obeys; {@code prob >= 100} always does.
     */
    public static boolean succeeds(PetInteractionTable.Interact interact, Random rng) {
        if (interact == null) {
            return true;
        }
        return rng.nextInt(100) < interact.prob();
    }
}
