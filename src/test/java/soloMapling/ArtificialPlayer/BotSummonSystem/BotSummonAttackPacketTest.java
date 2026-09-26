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
 * {@code [oid][level][action][count][oid,hitByte,damage]...}; the action byte encodes
 * {@code facing = byte & 0x80} plus an action id. In-game verified TWICE, both directions: the
 * base-4 byte ({@code 0x04/0x84}) renders every summon's attack pose - birds, dragon, bahamut,
 * AND the octopus turret. 8 ({@code 0x08/0x88}) killed the birds' poses and the turret's pose
 * alike, so the string-pool action-name table does not govern this byte the way a static decrypt
 * suggested. 4 is the empirically correct attack encoding; this test pins it so a future edit
 * cannot regress the poses again. (The octopus's missing projectile is a separate, open defect -
 * its WZ carries summon/attack1/info/ball + bulletSpeed, so the gate is elsewhere.)</p>
 *
 * <p>Shape: {@code [SHORT opcode LE][int cid][int oid][byte level=0][byte (left<<7)|4]
 * [byte count=1][int mobOid][byte 6][int damage]}.</p>
 */
class BotSummonAttackPacketTest {

    private static final int SUMMON_ATTACK = 0xB2; // SendOpcode.SUMMON_ATTACK

    @Test
    void actionByteCarriesAttack1ActionNotABareFacingFlag() {
        assertEquals(0x04, actionByte((byte) 0), "facing right must be 0x04 = attack1");
        assertEquals(0x84, actionByte((byte) 1), "facing left must be 0x84 = (left<<7)|attack1");
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
                (byte) 0x84,                            // (left<<7)|attack1
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
                0x04,                                       // (right<<7)|attack1
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
