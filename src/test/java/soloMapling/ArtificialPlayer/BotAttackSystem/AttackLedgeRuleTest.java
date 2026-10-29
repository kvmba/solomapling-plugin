package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the route-dependent ledge rule in {@link BotAttackDriver}.
 *
 * <p>Ludi PQ's stage 7 is built around it: 2040042.js tells the party they need ranged personnel
 * to kill three Ratz standing on shelves that no foothold chain reaches. The attack driver used
 * to apply its melee ledge test to every route, so a ranged bot refused exactly those targets -
 * the stage could not be played as designed. The rule is now: a projectile crosses the gap, a
 * swing does not.
 */
class AttackLedgeRuleTest {

    private static final String DRIVER = "src/main/java/soloMapling/ArtificialPlayer/"
            + "BotAttackSystem/BotAttackDriver.java";

    private static String source() throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(DRIVER));
    }

    private static String methodBody(String java, String signature) {
        int start = java.indexOf(signature);
        assertTrue(start > 0, "missing: " + signature);
        int open = java.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < java.length(); i++) {
            char c = java.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return java.substring(open, i + 1);
        }
        fail("unbalanced braces after " + signature);
        return null;
    }

    @Test
    void rangedAndMagicSkipTheLedgeTest() throws Exception {
        String body = methodBody(source(), "private static boolean onAttackableSurface(");
        assertTrue(body.contains("BotAttackProfile.Route.RANGED"),
                "a thrown/launched projectile is not ledge-bound");
        assertTrue(body.contains("BotAttackProfile.Route.MAGIC"),
                "a spell is not ledge-bound either");
        int ranged = body.indexOf("Route.RANGED");
        int ledge = body.indexOf("onDifferentLedge");
        assertTrue(ranged > 0 && ledge > ranged,
                "the ranged exemption must come BEFORE the ledge rejection");
    }

    @Test
    void meleeStillRejectsAForeignLedge() throws Exception {
        String body = methodBody(source(), "private static boolean onAttackableSurface(");
        assertTrue(body.contains("onDifferentLedge"),
                "melee must still refuse a mob on another ledge (the overhead-platform report)");
        assertTrue(body.contains("m.isBoss()"),
                "bosses stay exempt: multi-bodied, parts on their own footholds");
    }

    @Test
    void theRouteIsThreadedFromTheSwingDecision() throws Exception {
        String body = methodBody(source(), "private static boolean inReach(");
        assertTrue(body.contains("profile.route"),
                "inReach must pass its own route in, or the exemption can never apply");
    }

    @Test
    void theFacingAnchorIsNotLedgeFiltered() throws Exception {
        // nearestMob only picks the mob to FACE (and feeds the miss diagnostic). Filtering it by
        // ledge left a ranged bot facing nothing on the one room it could shoot across.
        String body = methodBody(source(), "private static Monster nearestMob(");
        assertFalse(body.contains("onAttackableSurface"),
                "the facing anchor must not apply the melee ledge gate");
    }
}
