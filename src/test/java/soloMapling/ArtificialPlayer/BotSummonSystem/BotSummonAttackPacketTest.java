package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@code SUMMON_ATTACK} action byte the follower broadcasts for a bot's summon.
 *
 * <p><b>Why the byte is split by summon kind.</b> The v83 client (BeiDou.exe {@code sub_7A6882},
 * verified in IDA) parses the relayed frame as
 * {@code [oid][level][action][count][oid,hitByte,damage]...}. For STATIONARY turrets it decodes
 * {@code facing = byte & 0x80}, {@code node = table[(byte & 0x7F) - 4]} over a 15-slot summon
 * action-name table (base 0xBEC3BC, decrypted string pool): {@code stand, move, fly, summoned,
 * attack1, attack2, skill1..skill6, hit, die, say}; the node drives the
 * {@code Skill/<job>/<skill>/summon/<name>} lookup whose cached info carries the per-level
 * {@code ball} node - the projectile sprite. A base-4 byte indexes slot 0 ({@code stand}): damage
 * lands but no pose and no ball, the reported octopus defect. TURRET byte = 8 -> slot 4 = attack1.
 * <p>
 * The archer birds rendered correctly on the old base-4 byte and broke on 8 (in-game verified), so
 * their path does NOT go through that table decode: they keep the raw 4. The split is by the
 * summon's own move kind; this test pins both values so neither can regress.
 *
 * <p>Shape: {@code [SHORT opcode LE][int cid][int oid][byte level=0][byte (left<<7)|action]
 * [byte count=1][int mobOid][byte 6][int damage]}.</p>
 */
class BotSummonAttackPacketTest {

    private static final int SUMMON_ATTACK = 0xB2; // SendOpcode.SUMMON_ATTACK

    @Test
    void turretSendsTheTableDecodedAttack1Action() {
        // Octopus turret (STATIONARY): slot 4 = attack1 -> ball renders.
        assertEquals(0x08, actionByte((byte) 0, true), "facing right must be 0x08 = attack1");
        assertEquals(0x88, actionByte((byte) 1, true), "facing left must be 0x88 = (left<<7)|attack1");
        assertEquals(4, (actionByte((byte) 0, true) & 0x7F) - 4, "turret decode must hit attack1 (slot 4)");
        assertEquals(4, (actionByte((byte) 1, true) & 0x7F) - 4);
    }

    @Test
    void birdsKeepTheProvenBase4Action() {
        // Hawk/eagle/phoenix/frostprey (CIRCLE_FOLLOW): the raw 4 rendered their attack poses
        // for the whole lifetime of this feature - in-game verified on both values. Untouched.
        assertEquals(0x04, actionByte((byte) 0, false), "facing right must stay 0x04");
        assertEquals(0x84, actionByte((byte) 1, false), "facing left must stay 0x84");
    }

    @Test
    void turretFrameMatchesTheClientLayout() {
        OutPacket p = BotSummonBroadcast.summonAttackPacket(24110, 1000000011, (byte) 1,
                List.of(new BotSummonBroadcast.Strike(1000000007, 19762)), true);
        byte[] expected = {
                (byte) (SUMMON_ATTACK & 0xFF), (byte) ((SUMMON_ATTACK >> 8) & 0xFF),
                0x2E, 0x5E, 0x00, 0x00,                 // cid = 24110
                0x0B, (byte) 0xCA, (byte) 0x9A, 0x3B,   // summonOid = 1000000011
                0x00,                                   // nCharLevel
                (byte) 0x88,                            // (left<<7)|turret attack1
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
     * Bahamut is a FOLLOW flyer -> base-4 byte.
     */
    @Test
    void multiMobStrikeAppendsOneAttackInfoPerTarget() {
        OutPacket p = BotSummonBroadcast.summonAttackPacket(7, 8, (byte) 0,
                List.of(new BotSummonBroadcast.Strike(100, 500),
                        new BotSummonBroadcast.Strike(101, 501),
                        new BotSummonBroadcast.Strike(102, 502)), false);
        byte[] expected = {
                (byte) (SUMMON_ATTACK & 0xFF), (byte) ((SUMMON_ATTACK >> 8) & 0xFF),
                0x07, 0x00, 0x00, 0x00,                     // cid = 7
                0x08, 0x00, 0x00, 0x00,                     // summonOid = 8
                0x00,                                       // nCharLevel
                0x04,                                       // (right<<7)|bird action
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

    private static int actionByte(byte direction, boolean stationaryTurret) {
        OutPacket p = BotSummonBroadcast.summonAttackPacket(1, 2, direction,
                List.of(new BotSummonBroadcast.Strike(3, 4)), stationaryTurret);
        return p.getBytes()[11] & 0xFF; // opcode(2)+cid(4)+oid(4)+level(1) = offset 11
    }
}
