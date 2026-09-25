package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@code SUMMON_ATTACK} action byte the follower broadcasts for a bot's summon.
 *
 * <p><b>Why this byte is not a plain 0/1 facing flag.</b> The v83 client (BeiDou.exe
 * {@code sub_7A6882}, verified in IDA) decodes the 4th payload byte of SUMMON_ATTACK as
 * {@code facing = byte & 0x80}, {@code action = (byte & 0x7F) - 4}, and indexes its summon
 * action-name table (global array at 0xBEC3CC, decrypted string pool: index 0="attack1",
 * 1="attack2", 2="skill1", ...) with that action to find the {@code Summon/<job>.img/<skill>/}
 * attack node. A raw 0/1 byte yields index -4/-3 which lands on "stand"/"move" - node names no
 * summon skill carries - so the Skill.wz lookup returns a null attack template and the observing
 * client crashes ("data error") when it dereferences it. A real client always encodes
 * {@code (facing << 7) | action} with action >= 4 ("attack1" = 4 is every summon's basic strike);
 * this test pins that so a future edit cannot reintroduce the crash.</p>
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
        // The client-side decode: action index = (byte & 0x7F) - 4 must hit "attack1" (index 0),
        // never a negative index (0x00/0x01 -> -4/-3 -> "stand"/"move" -> client crash).
        assertEquals(0, (actionByte((byte) 0) & 0x7F) - 4);
        assertEquals(0, (actionByte((byte) 1) & 0x7F) - 4);
    }

    @Test
    void frameMatchesTheClientLayout() {
        OutPacket p = BotSummonBroadcast.summonAttackPacket(24110, 1000000011, (byte) 1,
                1000000007, 19762);
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
        assertEquals(expected.length, p.getBytes().length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], p.getBytes()[i], "byte " + i);
        }
    }

    private static int actionByte(byte direction) {
        OutPacket p = BotSummonBroadcast.summonAttackPacket(1, 2, direction, 3, 4);
        return p.getBytes()[11] & 0xFF; // opcode(2)+cid(4)+oid(4)+level(1) = offset 11
    }
}
