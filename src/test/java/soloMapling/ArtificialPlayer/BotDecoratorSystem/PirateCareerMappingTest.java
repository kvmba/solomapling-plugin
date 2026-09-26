package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pirate (base class 5) rolls that let ambient/training bots come up as pirates.
 *
 * <p>Only the pure-integer paths are exercised: {@code Job} cannot be initialized off-host
 * (its enum constructor reads the host i18n bundle), and the reqJob mapping that keys off
 * {@code Job} is a three-line branch covered by reading. The class-id roll and the per-tier
 * job selection are where the real logic lives.
 */
class PirateCareerMappingTest {

    @Test
    void selectJobForClassRollsOnlyPirateJobs() {
        Set<Integer> first = Set.of(500);
        Set<Integer> second = Set.of(510, 520);
        Set<Integer> third = Set.of(511, 521);
        Set<Integer> fourth = Set.of(512, 522);

        for (int i = 0; i < 300; i++) {
            assertTrue(first.contains(BotDecorate.selectJobForClass(5, 10)), "1st job @10");
            assertTrue(first.contains(BotDecorate.selectJobForClass(5, 29)), "1st job @29");
            assertTrue(second.contains(BotDecorate.selectJobForClass(5, 30)), "2nd job @30");
            assertTrue(second.contains(BotDecorate.selectJobForClass(5, 69)), "2nd job @69");
            assertTrue(third.contains(BotDecorate.selectJobForClass(5, 70)), "3rd job @70");
            assertTrue(third.contains(BotDecorate.selectJobForClass(5, 119)), "3rd job @119");
            assertTrue(fourth.contains(BotDecorate.selectJobForClass(5, 120)), "4th job @120");
            assertTrue(fourth.contains(BotDecorate.selectJobForClass(5, 200)), "4th job @200");
        }
    }

    @Test
    void selectJobForClassStillRollsTheOtherClasses() {
        // Regression guard: the added case 5 must not disturb the existing four classes.
        assertTrue(Set.of(100).contains(BotDecorate.selectJobForClass(1, 15)));
        assertTrue(Set.of(200).contains(BotDecorate.selectJobForClass(2, 15)));
        assertTrue(Set.of(300).contains(BotDecorate.selectJobForClass(3, 15)));
        assertTrue(Set.of(400).contains(BotDecorate.selectJobForClass(4, 15)));
        assertTrue(BotDecorate.selectJobForClass(5, 5) == 0, "sub-level-10 stays a beginner");
    }

    @Test
    void rollBaseClassEventuallyYieldsPirates() {
        // Pirates are an 18% roll; over enough draws they must appear, and never as an
        // out-of-range class id.
        boolean sawPirate = false;
        for (int i = 0; i < 5000 && !sawPirate; i++) {
            int c = BotDecorate.rollBaseClass();
            assertTrue(c >= 1 && c <= 5, "class id in 1..5, got " + c);
            if (c == 5) {
                sawPirate = true;
            }
        }
        assertTrue(sawPirate, "rollBaseClass should be able to produce a pirate");
    }
}
