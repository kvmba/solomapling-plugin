package soloMapling;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Free-Market merchant must not shout while it is walking.
 *
 * <p>The mobile merchants (selling / buying / NX) shuffle between the FM entrance platforms, and a
 * shuffle can cross a ladder/stairs. The macro tick used to shout on the same tick it started the
 * shuffle — and a shuffle from an earlier tick could still be in flight — so a merchant was seen
 * shouting from halfway up the stairs. The shout is now gated on {@code GCMovement.isMoving}; the
 * movement itself is deliberately left free (no ladder avoidance), only the line waits for the bot
 * to stand still.
 *
 * <p>These bots need a live Character, so they cannot be constructed here. Like
 * {@link MerchantAdvertiseTemplateTest} this pins the contract from the source instead.
 */
class MerchantShoutGroundedTest {

    private static final Path BOT_TYPES = Paths.get("src/main/java/soloMapling/ArtificialPlayer/BotTypes");

    private static final String[] MOBILE_MERCHANTS = {
            "SellingMerchantBot.java",
            "BuyingMerchantBot.java",
            "NXMerchantBot.java",
    };

    @Test
    void merchantAdvertiseIsGatedOnStandingStill() throws IOException {
        for (String file : MOBILE_MERCHANTS) {
            String src = read(BOT_TYPES.resolve(file));
            assertTrue(src.contains("import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;"),
                    file + " must import GCMovement to test for movement before shouting");
            assertTrue(src.contains("GCMovement.isMoving("),
                    file + " must gate its shout on GCMovement.isMoving so it never shouts mid-walk");
        }
    }

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "expected source file at " + p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
