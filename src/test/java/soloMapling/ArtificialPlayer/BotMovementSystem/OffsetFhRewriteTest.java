package soloMapling.ArtificialPlayer.BotMovementSystem;

import org.gms.constants.game.CharacterStance;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.SingleMoveCommand;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementEnums.MovementPacketValues.NORMAL;

/**
 * Guards the fh rule of {@link MovementPacketConstructor#modifyMovementPacketWithOffset} — the offset
 * rewriter the recorded-path engine uses to re-home a recording onto the bot's current position
 * (the spawn drop-down and the turnaround replays).
 *
 * <p>The bug this locks down: the rewriter stamped the bot's CURRENT ground foothold id onto EVERY
 * fragment, including airborne cmd-0 frames. The spawn drop recording (portalenterdrop) carries jump
 * (stance 6) frames with fh 0; overwriting that with a ground id makes the observing client snap the
 * sprite onto that foothold's footing and take its render layer — the recorded-engine twin of the
 * dynamic engine's "jumps down and suddenly renders on the stairs' last layer" report.
 *
 * <p>Rule: a LAND fragment takes the current foothold id; a non-land fragment (jump/fall, swim, rope)
 * KEEPS the fh the recording carried.
 */
class OffsetFhRewriteTest {

    private static final int CURRENT_FH = 49;

    private static SingleMoveCommand frag(int x, int y, int fh, int stance) {
        return new SingleMoveCommand(NORMAL, (short) x, (short) y, (short) 0, (short) 0, (short) fh, (byte) stance, (short) 0);
    }

    private static List<SingleMoveCommand> run(List<SingleMoveCommand> list) {
        // currentXPos = first fragment's x so the coordinate offset is a no-op (offset 0):
        // keeps this test about the fh rule alone.
        return MovementPacketConstructor.modifyMovementPacketWithOffset(list, list.get(0).getXpos(), list.get(0).getYpos(), CURRENT_FH);
    }

    @Test
    void airborneFragmentKeepsItsZeroFh() {
        // The regression: a jump (stance 6) frame must not be rewritten to the ground id below it.
        List<SingleMoveCommand> out = run(List.of(frag(790, 33, 0, CharacterStance.JUMP_RIGHT_STANCE)));
        assertEquals(0, out.get(0).getFh(), "mid-air frame must keep fh 0, not the current ground id");
    }

    @Test
    void landingFragmentTakesTheCurrentFootholdId() {
        List<SingleMoveCommand> out = run(List.of(frag(790, 34, 0, CharacterStance.STAND_RIGHT_STANCE)));
        assertEquals(CURRENT_FH, out.get(0).getFh(), "a stand frame takes the bot's current ground id");
    }

    @Test
    void swimmingAndClimbingFragmentsKeepTheirRecordedFh() {
        List<SingleMoveCommand> out = run(List.of(
                frag(0, 0, 0, CharacterStance.SWIM_RIGHT_STANCE),
                frag(0, 0, (short) 0xFFFF, CharacterStance.ROPE_RIGHT_STANCE)));
        assertEquals(0, out.get(0).getFh(), "swim frame keeps its recorded fh (0)");
        assertEquals((short) 0xFFFF, out.get(1).getFh(), "rope frame keeps its recorded negative index");
    }

    @Test
    void theSpawnDropRecordingSequenceRewritesOnlyTheLandFrame() {
        // Exactly the portalenterdrop drop: two jump frames (fh 0) then a stand frame.
        List<SingleMoveCommand> out = run(List.of(
                frag(790, 33, 0, CharacterStance.JUMP_RIGHT_STANCE),
                frag(790, 34, 0, CharacterStance.JUMP_RIGHT_STANCE),
                frag(790, 34, 0, CharacterStance.STAND_RIGHT_STANCE)));
        assertEquals(0, out.get(0).getFh());
        assertEquals(0, out.get(1).getFh());
        assertEquals(CURRENT_FH, out.get(2).getFh());
    }
}
