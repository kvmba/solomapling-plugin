package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the field-key arithmetic: the one place where two unrelated fields could silently share
 * bookkeeping.
 *
 * <p>A key is built from four things - world, channel, edition, field - and every one of them has a
 * concrete way of going wrong if dropped:
 *
 * <ul>
 *   <li><b>channel</b>: each channel has its own instance of every field, so channel 1 and channel
 *       3 would suppress each other;</li>
 *   <li><b>world</b>: channel ids restart at 1 in every world, so the same collision happens across
 *       worlds;</li>
 *   <li><b>edition</b>: CPQ1's field 0 and CPQ2's field 0 are different maps;</li>
 *   <li><b>field</b>: the obvious one.</li>
 * </ul>
 *
 * <p>None of these produce an exception - the symptom is a field that quietly never opens - which is
 * why the arithmetic itself is pinned here.
 */
class CarnivalFieldKeyTest {

    /** The production key formula, restated so the test does not depend on reflection for it. */
    private static int key(int world, int channel, boolean cpq2, int field) {
        return world * 1_000_000 + channel * 1_000 + (cpq2 ? 100 : 0) + field;
    }

    @Test
    void everyDimensionChangesTheKey() {
        int base = key(0, 1, false, 0);
        assertNotEquals(base, key(1, 1, false, 0), "world must distinguish");
        assertNotEquals(base, key(0, 2, false, 0), "channel must distinguish");
        assertNotEquals(base, key(0, 1, true, 0), "edition must distinguish");
        assertNotEquals(base, key(0, 1, false, 1), "field must distinguish");
    }

    @Test
    void noTwoDistinctFieldsEverCollide() {
        // Exhaustive over everything the poll can actually visit: every channel of a world, both
        // editions, every field each edition runs. A collision here is a field that never opens.
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int world = 0; world <= 2; world++) {
            for (int channel = 1; channel <= 20; channel++) {
                for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
                    assertTrue(seen.add(key(world, channel, false, field)),
                            "CPQ1 w" + world + " ch" + channel + " f" + field + " collided");
                }
                for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
                    assertTrue(seen.add(key(world, channel, true, field)),
                            "CPQ2 w" + world + " ch" + channel + " f" + field + " collided");
                }
            }
        }
        // 3 worlds x 20 channels x (6 + 3) fields
        assertEquals(3 * 20 * 9, seen.size());
    }

    @Test
    void theFormulaInProductionMatchesThisOne() throws Exception {
        // The test above is only meaningful if it describes the real key. This reads the production
        // helper and checks it agrees on a sample - the method is private, so reflection is the
        // only way to state the contract without widening the class's API for a test.
        Class<?> arena = CarnivalArena.class;
        Method helper = null;
        for (Method m : arena.getDeclaredMethods()) {
            if (m.getName().equals("fieldKey") && m.getParameterCount() == 4) {
                helper = m;
                break;
            }
        }
        assertTrue(helper != null, "the four-argument fieldKey helper must exist");
        assertTrue(Modifier.isPrivate(helper.getModifiers()));
        helper.setAccessible(true);

        for (int world = 0; world <= 1; world++) {
            for (int channel = 1; channel <= 3; channel++) {
                for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
                    assertEquals(key(world, channel, false, field),
                            helper.invoke(null, world, channel, false, field),
                            "CPQ1 w" + world + " ch" + channel + " f" + field);
                }
                for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
                    assertEquals(key(world, channel, true, field),
                            helper.invoke(null, world, channel, true, field),
                            "CPQ2 w" + world + " ch" + channel + " f" + field);
                }
            }
        }
    }
}
