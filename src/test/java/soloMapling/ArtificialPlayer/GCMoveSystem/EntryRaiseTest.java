package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the map-entry raise a bot is shown at, against the host's own entering-field packet.
 *
 * <p>The room is told about a warping character by {@code PacketCreator.spawnPlayerMapObject(...,
 * enteringField = true)}, which writes {@code position.y - 42} and the JUMP stance. For a real
 * player that is the end of it: the same packet carries foothold 0, the client applies gravity and
 * the character falls from the raised point.
 *
 * <p>A bot lost that raise. The very same packet fills the foothold field with the bot's real
 * foothold id (so the sprite renders on the right layer), and the client SNAPS a sprite onto a
 * foothold id it is given - so every arriving bot was pulled back down to the floor while the player
 * beside it stayed up. The dynamic engine then floated the bot from a SECOND, unrelated number
 * ({@code PORTAL_FLOAT_HEIGHT_PX}, 60px above the FLOOR), which is the reported "it is not raised at
 * first, then gets pulled up later" - and the two frames disagreed by a different amount in every
 * room because one was portal-relative and the other floor-relative.
 *
 * <p>Two things are pinned here, because a real {@code MapleMap}/{@code Character} cannot be built
 * in a unit test (their constructors trip the Spring-backed {@code Server} static initializer; the
 * same constraint as {@code GroundSwayTest} / {@code SettledPointTest}):
 * <ul>
 *   <li>the raise rule - it is the host's 42, measured from the arrival portal, the same in every
 *       room;</li>
 *   <li>the pose the host reads to decide whether to emit a foothold id at all, which is what made
 *       the bot's first frame differ from a player's.</li>
 * </ul>
 */
class EntryRaiseTest {

    /** PacketCreator.spawnPlayerMapObject writes {@code spawnPos.y -= 42}. */
    private static final int HOST_RAISE_PX = 42;

    @Test
    void raisesTheArrivalPortalByTheHostsOwnEnteringFieldOffset() {
        // Toy Tower 101 stage 2: st00 sits at (-192, -2523), the floor under it is -2521.
        assertEquals(new Point(-192, -2523 - HOST_RAISE_PX),
                GCMovement.entrySpawnPoint(new Point(-192, -2523)),
                "the entry point must be the portal raised by the host's own 42px, so the frame the "
                        + "room already has is the frame the drop starts from");
        assertEquals(HOST_RAISE_PX, GCMovement.ENTRY_RAISE_PX,
                "this constant exists to mirror PacketCreator's entering-field raise; if the host "
                        + "changes it, the bot's first frame drifts away from a player's again");
    }

    @Test
    void theRaiseIsPortalRelativeNotFloorRelative() {
        // Every room gets the SAME raise, which is the property the old floor-based float lacked: it
        // produced ground.y - 60, i.e. ~54-59px in the flat rooms and 0 in 922010700 (whose st00 hangs
        // 74px above its floor), so the bot's two frames disagreed by a different amount per room.
        Point[] spawns = {
                new Point(-192, -2523), // 922010200, portal 2px above the floor
                new Point(188, -1510),  // 922010300
                new Point(-43, -783),   // 922010700, portal 74px above the floor
        };
        for (Point spawn : spawns) {
            assertEquals(spawn.y - HOST_RAISE_PX, GCMovement.entrySpawnPoint(spawn).y,
                    "the raise must not depend on the floor: that is what let the host's first frame "
                            + "and the driver's float disagree in the first place");
            assertEquals(spawn.x, GCMovement.entrySpawnPoint(spawn).x,
                    "the arrival portal's column is what the room was already shown");
        }
    }

    /**
     * The host only fills the foothold field for an artificial character it reads as STANDING:
     * {@code onFoot = !isClimbing(stance) && !isJumping(stance) && !isSwimming(stance)}. A bot that
     * stands still while its portal script runs therefore gets a ground foothold id on the spawn
     * packet and is snapped back down to the floor; a player (who never gets the field filled) keeps
     * the raise. Arming a jump pose before the warp is what makes the host write 0 and leave the bot
     * up there like everyone else.
     */
    @Test
    void theEntryPoseMakesTheHostOmitTheFoothold() {
        int stance = org.gms.constants.game.CharacterStance.JUMP_RIGHT_STANCE;
        assertTrue(org.gms.constants.game.CharacterStance.isJumping(stance),
                "a jump stance is what PacketCreator.spawnPlayerMapObject reads to send foothold 0");
        assertFalse(org.gms.constants.game.CharacterStance.isClimbing(stance));
        assertFalse(org.gms.constants.game.CharacterStance.isSwimming(stance));
        // And the other side of the contract: a standing bot still gets a foothold id, which is what
        // silently cancelled the host's raise before the pose was armed.
        assertFalse(org.gms.constants.game.CharacterStance.isJumping(
                        org.gms.constants.game.CharacterStance.STAND_RIGHT_STANCE),
                "the reported defect was exactly this: standing at the portal meant onFoot == true");
    }

    /** The raise must land on the same y the host's packet already broadcast for this arrival. */
    @Test
    void theDriverFloatStartsWhereTheHostsPacketLeftTheBot() {
        Point spawn = new Point(-208, -219);              // 922010500 st00
        Point hostFrame = new Point(spawn.x, spawn.y - HOST_RAISE_PX);
        assertEquals(hostFrame, GCMovement.entrySpawnPoint(spawn),
                "the driver must not float from a second, floor-derived height - the room has "
                        + "already been shown this one");
    }

    /**
     * The armed pose must not outlive the warp by a driver tick.
     *
     * <p>The host does not reset a stance on a map change ({@code changeMapInternal} only calls
     * {@code setPosition}), and an unobserved room's driver ticks once a second - four when the bot
     * has no job. A player who walks in during that window is told about every existing bot by
     * {@code Character.sendSpawnData}, whose {@code enteringField = false} frame writes
     * {@code chr.getStance()} verbatim: a leftover jump pose would render every arriving bot
     * hovering in mid-air. The pose therefore has to be handed back inside {@code enterPortal}
     * itself, on the map-change branch.
     *
     * <p>Structural because a real {@code MapleMap}/{@code Character} cannot be built in a unit test
     * (the Spring-backed {@code Server} static initializer; same constraint as above).
     */
    @Test
    void theEntryPoseIsHandedBackBeforeTheNextDriverTick() throws Exception {
        String java = source();
        int start = java.indexOf("public static void enterPortal(Character bot, Portal portal)");
        assertTrue(start > 0, "enterPortal must exist");
        String body = java.substring(start, java.indexOf("\n    }", start));

        int mapChangeBranch = body.indexOf("} else {");
        assertTrue(mapChangeBranch > 0,
                "the cross-map branch must hand the pose back itself");
        assertTrue(body.substring(mapChangeBranch).contains("bot.setStance(stanceBefore)"),
                "a cross-map arrival must restore the pose immediately: changeMapInternal does not "
                        + "reset it, and waiting for the driver leaves a jump pose on the character "
                        + "for up to 4s of unobserved cadence");
        assertTrue(body.contains("bot.setStance(stanceBefore)"),
                "the refusing-door branch must undo the pose too");
        assertEquals(2, body.split("bot\\.setStance\\(stanceBefore\\)", -1).length - 1,
                "exactly the two undo points: the refused door and the completed cross-map warp");
    }

    private static String source() throws Exception {
        try (var in = EntryRaiseTest.class.getClassLoader()
                .getResourceAsStream("soloMapling/ArtificialPlayer/GCMoveSystem/GCMovement.java");
             var raw = in != null ? in
                     : new java.io.FileInputStream(
                             "src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovement.java")) {
            return new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
