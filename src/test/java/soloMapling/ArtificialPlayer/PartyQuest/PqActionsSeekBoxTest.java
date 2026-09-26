package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the PQ seek box against real quest-room geometry that once read as "quiet".
 *
 * <p>LPQ stage 1 (922010100) is a ~3000px tower: the entry floor sits at y=+130 and the first
 * Ratz ledge at y=-450 - a 580px vertical gap over one-way ledges (the nav graph climbs it via
 * ropes, but only AFTER a target is selected). With the old 400px box the nearest mob was always
 * out of the seek box, so seekAndAttack never fired a chase and the bots stood at the entrance
 * forever; the mobs are mobTime=-1 and never close the gap themselves. The box must admit that
 * whole gap (and the tower's full height - a bot standing anywhere in it should see the hunt).
 */
class PqActionsSeekBoxTest {

    /** Entry floor y=130 to the first stage-1 mob ledge y=-450, read off the map img. */
    private static final int LPQ_STAGE1_VERTICAL_GAP = 580;

    /** The full tower span the box claims to cover (comment in PqActions). */
    private static final int LPQ_TOWER_HEIGHT = 3_000;

    @Test
    void seekBoxAdmitsTheLpqStage1VerticalGap() {
        assertTrue(PqActions.SEEK_STACK_RANGE_Y >= LPQ_STAGE1_VERTICAL_GAP,
                "seek box " + PqActions.SEEK_STACK_RANGE_Y + "px is narrower than the 580px "
                        + "entry-to-first-mob gap on LPQ stage 1 - bots will stand at the entrance again");
    }

    @Test
    void seekBoxCoversTheWholeLpqTower() {
        assertTrue(PqActions.SEEK_STACK_RANGE_Y >= LPQ_TOWER_HEIGHT,
                "seek box " + PqActions.SEEK_STACK_RANGE_Y + "px does not span the tower "
                        + "- a bot on the wrong floor would read the room as quiet");
    }
}
