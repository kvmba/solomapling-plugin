package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@code SUMMON_ATTACK} action byte the follower broadcasts for a bot's summon.
 *
 * <p><b>Why this byte is not a plain 0/1 facing flag.</b> The v83 client (BeiDou.exe
 * {@code sub_7A6882}, verified in IDA) parses the relayed frame as
 * {@code [oid][level][action][count][oid,hitByte,damage]...} and decodes the action byte as
 * {@code facing = byte & 0x80}, {@code node = table[(byte & 0x7F) - 4]} over a 15-slot summon
 * action-name table (base 0xBEC3BC, decrypted from the client's string pool):
 * {@code stand, move, fly, summoned, attack1, attack2, skill1..skill6, hit, die, say}. The node
 * name drives the {@code Skill/<job>/<skill>/summon/<name>} lookup whose cached info carries the
 * per-level {@code ball} node - the projectile sprite observers draw. An action of 4 indexes slot 0
 * ({@code stand}): damage still lands, but no attack pose and NO bullet render - the exact
 * "hit without bullet" defect this byte once caused. ATTACK1 is slot 4, so the byte encodes action
 * 8: {@code 0x08} facing right, {@code 0x88} facing left; this test pins that.</p>
 *
 * <p>Shape: {@code [SHORT opcode LE][int cid][int oid][byte level=0][byte (left<<7)|8]
 * [byte count=1][int mobOid][byte 6][int damage]}.</p>
 */
class BotSummonAttackPacketTest {

    private static final int SUMMON_ATTACK = 0xB2; // SendOpcode.SUMMON_ATTACK

    @Test
    void actionByteCarriesAttack1ActionNotABareFacingFlag() {
        assertEquals(0x08, actionByte((byte) 0), "facing right must be 0x08 = attack1");
        assertEquals(0x88, actionByte((byte) 1), "facing left must be 0x88 = (left<<7)|attack1");
        // The client-side decode: node index = (byte & 0x7F) - 4 must hit "attack1" (slot 4),
        // never slot 0 ("stand" - plays no attack pose and renders no ball).
        assertEquals(4, (actionByte((byte) 0) & 0x7F) - 4);
        assertEquals(4, (actionByte((byte) 1) & 0x7F) - 4);
    }

    @Test
    void frameMatchesTheClientLayout() {
        OutPacket p = BotSummonBroadcast.summonAttackPacket(24110, 1000000011, (byte) 1,
                List.of(new BotSummonBroadcast.Strike(1000000007, 19762)));
        byte[] expected = {
                (byte) (SUMMON_ATTACK & 0xFF), (byte) ((SUMMON_ATTACK >> 8) & 0xFF),
                0x2E, 0x5E, 0x00, 0x00,                 // cid = 24110
                0x0B, (byte) 0xCA, (byte) 0x9A, 0x3B,   // summonOid = 1000000011
                0x00,                                   // nCharLevel
                (byte) 0x88,                            // (left<<7)|attack1
                0x01,                                   // nMobCount
                0x07, (byte) 0xCA, (byte) 0x9A, 0x3B,   // mobOid = 1000000007
                0x06,                                   // nHitAction ("who knows")
                0x32, 0x4D, 0x00, 0x00,                 // damage = 19762
        };
        assertPacketBytes(expected, p);
    }

    /**
     * A multi-mob summon (Bahamut carries a WZ mobCount of 3..6) writes one ATTACKINFO per target
     * under a single count byte - not one frame per mob. The host's PacketCreator.summonAttack does
     * the same and the client loops nMobCount entries, so a second target must not restart the frame.
     */
    @Test
    void multiMobStrikeAppendsOneAttackInfoPerTarget() {
        OutPacket p = BotSummonBroadcast.summonAttackPacket(7, 8, (byte) 0,
                List.of(new BotSummonBroadcast.Strike(100, 500),
                        new BotSummonBroadcast.Strike(101, 501),
                        new BotSummonBroadcast.Strike(102, 502)));
        byte[] expected = {
                (byte) (SUMMON_ATTACK & 0xFF), (byte) ((SUMMON_ATTACK >> 8) & 0xFF),
                0x07, 0x00, 0x00, 0x00,                     // cid = 7
                0x08, 0x00, 0x00, 0x00,                     // summonOid = 8
                0x00,                                       // nCharLevel
                0x08,                                       // (right<<7)|attack1
                0x03,                                       // nMobCount = 3
                0x64, 0x00, 0x00, 0x00, 0x06, (byte) 0xF4, 0x01, 0x00, 0x00, // oid 100, dmg 500
                0x65, 0x00, 0x00, 0x00, 0x06, (byte) 0xF5, 0x01, 0x00, 0x00, // oid 101, dmg 501
                0x66, 0x00, 0x00, 0x00, 0x06, (byte) 0xF6, 0x01, 0x00, 0x00, // oid 102, dmg 502
        };
        assertPacketBytes(expected, p);
    }

    private static void assertPacketBytes(byte[] expected, OutPacket actual) {
        assertEquals(expected.length, actual.getBytes().length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual.getBytes()[i], "byte " + i);
        }
    }

    private static int actionByte(byte direction) {
        OutPacket p = BotSummonBroadcast.summonAttackPacket(1, 2, direction,
                List.of(new BotSummonBroadcast.Strike(3, 4)));
        return p.getBytes()[11] & 0xFF; // opcode(2)+cid(4)+oid(4)+level(1) = offset 11
    }
}
