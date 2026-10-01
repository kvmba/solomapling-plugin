package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the post-work delivery contract behind the "bots throw passes as soon as the leader
 * is nearby" report: the mid-fight hand-off is gone from the Ludi stage work, and the only
 * delivery path left ({@code handItemsToLeaderAfterStage}) drops at the stage-NPC post once
 * the stage's work is over - the leader has to come there for the turn-in anyway.
 *
 * <p>The Ludi stage methods must not call the old immediate hand-off any more; the helper
 * itself must refuse to deliver until it has walked the bot to the NPC post and seen the
 * leader in range there.
 */
class PqPassHandoffGateTest {

    /** The immediate hand-off: allowed only inside the post-stage helper and handItemsToLeader itself. */
    private static final String[] LUDI_STAGE_SOURCES = {
            "soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiStages.java",
    };

    @Test
    void ludiStageWorkNeverDropsPassesMidFight() throws Exception {
        for (String source : LUDI_STAGE_SOURCES) {
            String java = javaSource(source);
            // The old mid-fight pattern: leaderNear gate + immediate handItemsToLeader,
            // with recoverUngatheredHandoffs called right before it (the old beat's shape).
            assertFalse(java.matches("(?s).*leaderNear\\([^)]*\\)\\s*\\)\\s*\\{\\s*"
                            + "PqActions\\.handItemsToLeader.*"),
                    source + " still delivers the moment the leader passes by");
            // The post-work helper is the only delivery path the stage work uses.
            assertTrue(java.contains("handItemsToLeaderAfterStage"),
                    source + " must deliver through the post-work helper");
        }
    }

    @Test
    void helperIsTheOnlyNewDeliveryEntryPoint() throws Exception {
        Method m = PqActions.class.getDeclaredMethod("handItemsToLeaderAfterStage",
                org.gms.client.Character.class, int.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()),
                "the post-work delivery is a PqActions action like its siblings");
    }

    private static String javaSource(String path) throws Exception {
        try (var in = PqPassHandoffGateTest.class.getClassLoader().getResourceAsStream(path);
             var raw = in != null ? in : new java.io.FileInputStream("src/main/java/" + path)) {
            return new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
