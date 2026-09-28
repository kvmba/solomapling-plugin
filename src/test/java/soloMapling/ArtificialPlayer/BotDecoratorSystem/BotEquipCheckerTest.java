package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BotEquipChecker#isNakedWearing(int, boolean)} slot semantics.
 *
 * <p>Regression: the old check demanded BOTH the top slot (-5) and the pants
 * slot (-6). An overall lives only in -5 (host BodyPart.LONGCOAT shares the
 * top's slot value), so every overall wearer - all pirates, plus shop-pool
 * overall wearers - was flagged naked on every scan, and the repair could never
 * clear the flag because a pirate has no coat/pants in WZ, only overalls.</p>
 */
public class BotEquipCheckerTest {

    private static final int TOP = 1040002;     // male coat
    private static final int OVERALL = 1052095; // pirate overall (shop-stocked)

    @Test
    public void overallAloneCountsAsDressed() {
        assertFalse(BotEquipChecker.isNakedWearing(OVERALL, false));
    }

    @Test
    public void topWithoutPantsIsNaked() {
        assertTrue(BotEquipChecker.isNakedWearing(TOP, false));
    }

    @Test
    public void topWithPantsIsDressed() {
        assertFalse(BotEquipChecker.isNakedWearing(TOP, true));
    }

    @Test
    public void overallWithPantsIsDressed() {
        assertFalse(BotEquipChecker.isNakedWearing(OVERALL, true));
    }
}
