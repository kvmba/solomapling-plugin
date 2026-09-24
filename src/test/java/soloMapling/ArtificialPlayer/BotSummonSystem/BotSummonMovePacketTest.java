package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@code MOVE_SUMMON} frame the follower broadcasts for a bot's summon.
 *
 * <p>The frame is the one the host itself relays for a real player ({@code PacketCreator.moveSummon}
 * echoes the client's move path verbatim), and a client animates only the local player's own summon -
 * every other summon, a bot's included, is rendered purely from these frames. If the layout drifts,
 * observers see a frozen summon (no frame accepted) or a mis-parsed move; a headless bot cannot
 * "just let the client do it", which is why this is asserted byte-for-byte.</p>
 *
 * <p>Shape: {@code [SHORT opcode LE][int cid][int oid][short startX][short startY][byte count=1]
 * [byte type=0][short x][short y][short vx][short vy][short fh][byte action][short duration]} -
 * the fragment is the host's own {@code AbsoluteLifeMovement} serialisation, the same one the pet
 * system already sends in-game via {@code PacketCreator.movePet}.</p>
 */
class BotSummonMovePacketTest {

    private static final int MOVE_SUMMON = 0xB1; // SendOpcode.MOVE_SUMMON

    @Test
    void frameMatchesTheHostEchoLayout() {
        OutPacket p = BotSummonBroadcast.summonMovePacket(
                20001, 424242, new Point(100, 200), new Point(155, 188),
                new Point(183, -40), 0, 2, 300);
        assertArrayEquals(expected(20001, 424242, new Point(100, 200), new Point(155, 188),
                        new Point(183, -40), 0, 2, 300),
                p.getBytes(), "MOVE_SUMMON must match [opcode][cid][oid][start][1][type0 frag...]");
    }

    @Test
    void groundedSummonCarriesItsFoothold() {
        OutPacket p = BotSummonBroadcast.summonMovePacket(
                7, 99, new Point(1, 2), new Point(3, 4), new Point(-5, 6), 1234, 1, 300);
        assertArrayEquals(expected(7, 99, new Point(1, 2), new Point(3, 4), new Point(-5, 6), 1234, 1, 300),
                p.getBytes(), "a grounded summon's frame must carry the real foothold id");
    }

    private static byte[] expected(int cid, int oid, Point start, Point dest, Point vel,
                                   int fh, int action, int duration) {
        return new byte[]{
                (byte) (MOVE_SUMMON & 0xFF), (byte) ((MOVE_SUMMON >> 8) & 0xFF),
                (byte) (cid & 0xFF), (byte) ((cid >> 8) & 0xFF), (byte) ((cid >> 16) & 0xFF), (byte) ((cid >> 24) & 0xFF),
                (byte) (oid & 0xFF), (byte) ((oid >> 8) & 0xFF), (byte) ((oid >> 16) & 0xFF), (byte) ((oid >> 24) & 0xFF),
                (byte) (start.x & 0xFF), (byte) ((start.x >> 8) & 0xFF),
                (byte) (start.y & 0xFF), (byte) ((start.y >> 8) & 0xFF),
                (byte) 1,                 // one fragment
                (byte) 0,                 // command 0 = normal / absolute move
                (byte) (dest.x & 0xFF), (byte) ((dest.x >> 8) & 0xFF),
                (byte) (dest.y & 0xFF), (byte) ((dest.y >> 8) & 0xFF),
                (byte) (vel.x & 0xFF), (byte) ((vel.x >> 8) & 0xFF),
                (byte) (vel.y & 0xFF), (byte) ((vel.y >> 8) & 0xFF),
                (byte) (fh & 0xFF), (byte) ((fh >> 8) & 0xFF),
                (byte) action,
                (byte) (duration & 0xFF), (byte) ((duration >> 8) & 0xFF)
        };
    }

    @Test
    void fragmentCountAndTypeAreFixed() {
        byte[] bytes = BotSummonBroadcast.summonMovePacket(
                1, 2, new Point(0, 0), new Point(0, 0), new Point(0, 0), 0, 2, 100).getBytes();
        assertEquals(1, bytes[14] & 0xFF, "exactly one movement fragment");
        assertEquals(0, bytes[15] & 0xFF, "the fragment must be an absolute (normal) move");
    }
}
