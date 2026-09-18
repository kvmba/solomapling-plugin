package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.net.opcodes.SendOpcode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the pet-effect packet layout — the two effects a following pet produces but the plugin
 * previously never sent: TELEPORT (the warp puff when a pet left behind re-homes onto the owner)
 * and HANG_ON_BACK (hopping onto the owner's back when the owner grabs a rope / ladder).
 *
 * <p>The packet is the same {@code UserEffect "Pet"} shape the host already emits for the pet
 * level-up effect ({@code PacketCreator.showPetLevelUp}), with the {@code PetEffectType} sub-type
 * (GMS: LevelUp=0, Teleport=1, HangOnBack=2, Evolution=3) no longer hard-coded to 0:
 * {@code [SHOW_FOREIGN_EFFECT][int cid][byte 4][byte subType][byte petIndex]}. The leading
 * {@code 4} is the UserEffect type byte that marks a PET effect; a wrong byte here would make the
 * client play an unrelated effect (or crash the decode), so it is asserted, not assumed.</p>
 *
 * <p>The byte-building is a static seam, so no live {@code Character}/{@code MapleMap} is needed —
 * same reason the follower's other packet/decision seams exist ({@code shouldResolveFoothold},
 * {@code ownerSteppedOffRopeTop}).</p>
 */
class BotPetEffectPacketTest {

    private static final int EFFECT_TYPE_PET = 4;
    private static final int SHOW_FOREIGN_EFFECT = SendOpcode.SHOW_FOREIGN_EFFECT.getValue();

    @Test
    void teleportEffectCarriesSubTypeOne() {
        byte[] packet = BotPetFollower.petEffectPacket(12345, 0, 1).getBytes();
        assertArrayEquals(frame(12345, 1, 0), packet,
                "Teleport effect must be [SHOW_FOREIGN_EFFECT][cid][4][1][petIndex]");
    }

    @Test
    void hangOnBackEffectCarriesSubTypeTwo() {
        byte[] packet = BotPetFollower.petEffectPacket(777, 2, 2).getBytes();
        assertArrayEquals(frame(777, 2, 2), packet,
                "HangOnBack effect must be [SHOW_FOREIGN_EFFECT][cid][4][2][petIndex]");
    }

    @Test
    void theEffectTypeByteMarksAPetEffect() {
        // Guards against a copy-paste that drops or changes the UserEffect Pet marker (byte 4):
        // any other value makes the client resolve a different effect from the same opcode.
        byte[] packet = BotPetFollower.petEffectPacket(1, 0, 1).getBytes();
        assertEquals(EFFECT_TYPE_PET, packet[6] & 0xFF,
                "byte 6 (after the 2-byte opcode and the 4-byte cid) is the UserEffect type = Pet");
    }

    /** The expected bytes: LE opcode, LE int cid, then the three little bytes. */
    private static byte[] frame(int cid, int subType, int petIndex) {
        return new byte[]{
                (byte) (SHOW_FOREIGN_EFFECT & 0xFF), (byte) ((SHOW_FOREIGN_EFFECT >> 8) & 0xFF),
                (byte) (cid & 0xFF), (byte) ((cid >> 8) & 0xFF), (byte) ((cid >> 16) & 0xFF), (byte) ((cid >> 24) & 0xFF),
                (byte) EFFECT_TYPE_PET,
                (byte) subType,
                (byte) petIndex
        };
    }
}
