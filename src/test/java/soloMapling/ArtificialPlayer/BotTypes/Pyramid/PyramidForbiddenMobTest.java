package soloMapling.ArtificialPlayer.BotTypes.Pyramid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Pyramid "forbidden monster" ids.
 *
 * <p>Duarte warns the party off the Pharaoh Jr. Yeti that carries the "Missed Mark". The trap
 * this guards against is a silent one: the briefing prints the mark as an item icon
 * ({@code #v04032424#}), so it is easy to compare a monster's id against that ITEM id - a check
 * that can never be equal, which left the guard doing nothing at all. These cases pin the real
 * MONSTER ids the spawner uses.
 */
class PyramidForbiddenMobTest {

    @Test
    void theForbiddenMonstersAreTheOnesTheSpawnerActuallyUses() {
        // killing_BonusSetting.js spawns 9700019 in the ordinary bonus rooms and 9700029 in the
        // two maps it special-cases (926010013 / 926010070).
        assertTrue(PyramidPqData.isForbidden(9700019), "the decoy in the bonus rooms");
        assertTrue(PyramidPqData.isForbidden(9700029), "the decoy variant two maps use");
    }

    @Test
    void theMarksItemIdIsNotAMonster() {
        // 4032424 is "Missed Mark", the item shown in Duarte's briefing. Treating it as a mob id
        // is exactly the bug this replaces.
        assertFalse(PyramidPqData.isForbidden(4032424), "the mark is an item, not a monster");
    }

    @Test
    void ordinaryPyramidMobsAreNotForbidden() {
        // The mobs the party is supposed to kill must stay attackable, or the bot would never fight.
        for (int mob : new int[]{9700004, 9700005, 9700006, 9700007, 9700008, 9700024}) {
            assertFalse(PyramidPqData.isForbidden(mob), "mob " + mob + " is an ordinary kill");
        }
    }
}
