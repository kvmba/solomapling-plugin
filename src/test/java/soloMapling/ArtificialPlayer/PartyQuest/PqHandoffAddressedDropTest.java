package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the addressed-hand-off contract behind the "pass ping-pong / lost piles" audit finding.
 *
 * <p>The hand-off drop path ({@code PqActions.giveItemTo} / {@code giveItemToQuiet}) must spawn
 * through the host's own owner-locked {@code MapleMap.spawnOwnerOnlyItemDrop} - the plain
 * {@code spawnItemDrop} leaves {@code permanentOwner} false, which silently disabled three
 * guards that key on it: {@code botCanLoot}'s addressed-pile refusal, {@code isFloorLoot}'s
 * qty-1 pass filter, and {@code recoverUngatheredHandoffs}' 30s reclaim (which never matched
 * a real pile and could never fire).</p>
 *
 * <p>Source-level pin: the call graph runs on a live Character/MapleMap, which unit tests
 * cannot instantiate; the wire bytes come from the host unchanged, so the shape of the call
 * is the contract.</p>
 */
class PqHandoffAddressedDropTest {

    private static final String DROP_COMMANDS =
            "src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommands.java";

    @Test
    void handOffQtySpawnsThroughTheOwnersOwnLockedDrop() throws Exception {
        String java = javaSource(DROP_COMMANDS);
        // botThrowToOwnerItemQtyAt is the ONLY spawn seam both hand-off paths use
        // (giveItemAt + giveItemToQuiet delegate to it). It must go through the host's
        // addressed drop; the no-spot overload is a thin forwarder onto the same seam.
        int qty = java.indexOf("public static void botThrowToOwnerItemQtyAt");
        assertTrue(qty >= 0, "botThrowToOwnerItemQtyAt must exist (the hand-off seam)");
        String body = java.substring(qty);
        body = body.substring(0, body.indexOf("public static", 10) > 0
                ? body.indexOf("public static", 10) : body.length());
        assertTrue(body.contains("spawnOwnerOnlyItemDrop"),
                "the hand-off must use the host's owner-locked spawnOwnerOnlyItemDrop, got: "
                        + body.replaceAll("\\s+", " "));
        assertFalse(body.contains("spawnItemDrop("),
                "the plain spawnItemDrop leaves permanentOwner false - the addressed guards never engage");
    }

    @Test
    void reclaimAndLootGuardsStillKeyOnPermanentOwner() throws Exception {
        // The guards the fix empowers must keep keying on the permanent-owner read - now
        // through the locked seam (DropCommands.isPermanentOwnerLocked) so the read honours the
        // host's item-lock contract like the write does - and the reclaim must additionally take
        // only THIS bot's own piles (the dropper identity). Flip either side and the contract
        // silently breaks again.
        String pq = javaSource("src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PqActions.java");
        assertTrue(pq.contains("!DropCommands.isPermanentOwnerLocked(drop)"),
                "isFloorLoot must refuse addressed piles");
        int reclaim = pq.indexOf("public static int recoverUngatheredHandoffs(");
        assertTrue(reclaim >= 0, "recoverUngatheredHandoffs must exist");
        String body = pq.substring(reclaim);
        body = body.substring(0, body.indexOf("public static", 10) > 0
                ? body.indexOf("public static", 10) : body.length());
        assertTrue(body.contains("drop.getDropper() != bot"),
                "the reclaim must only take piles THIS bot dropped for the leader");
        assertTrue(body.contains("!DropCommands.isPermanentOwnerLocked(drop)"),
                "recoverUngatheredHandoffs must require an addressed pile");
        String drops = javaSource(DROP_COMMANDS);
        assertTrue(drops.contains("if (isPermanentOwnerLocked(mapItem))"),
                "botCanLoot must refuse addressed piles");
    }

    private static String javaSource(String path) throws Exception {
        Path p = Paths.get(path);
        assertTrue(Files.isRegularFile(p), "source file missing: " + path);
        return Files.readString(p);
    }
}
