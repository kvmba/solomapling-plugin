package soloMapling.ArtificialPlayer.BotPetSystem;

import java.util.List;
import java.util.Random;

/**
 * Pure picker over {@link PetInteractionTable.Interact} entries: choose the
 * behaviour a pet performs this time. Kept free of host/WZ types so the
 * selection rule (level band + probability weighting) is unit-testable.
 */
public final class PetCommandInterpreter {

    private PetCommandInterpreter() {
    }

    /**
     * Pick one interaction the pet may perform at {@code petLevel}, weighted by
     * each entry's {@code prob} (entries outside the pet's level band are
     * skipped). Returns {@code null} when none apply.
     */
    public static PetInteractionTable.Interact pick(
            List<PetInteractionTable.Interact> interactions, int petLevel, Random rng) {
        if (interactions == null || interactions.isEmpty()) {
            return null;
        }
        int total = 0;
        for (PetInteractionTable.Interact it : interactions) {
            if (it.allowsLevel(petLevel) && it.prob() > 0) {
                total += it.prob();
            }
        }
        if (total <= 0) {
            return null;
        }
        int roll = rng.nextInt(total);
        for (PetInteractionTable.Interact it : interactions) {
            if (!it.allowsLevel(petLevel) || it.prob() <= 0) {
                continue;
            }
            roll -= it.prob();
            if (roll < 0) {
                return it;
            }
        }
        return null; // unreachable given the total above
    }
}
