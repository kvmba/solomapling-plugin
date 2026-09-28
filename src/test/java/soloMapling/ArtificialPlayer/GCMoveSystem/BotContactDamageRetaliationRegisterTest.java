package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the retaliate-when-hit register's contract: a landed mob hit is remembered per bot inside the
 * memory window, a repeat hit refreshes it in place (never grows the map), and despawn cleanup drops
 * it. The registration itself is fed by {@code BotContactDamage.applyMobHit}; the grind brain's
 * retaliate beat reads it through {@code GCMovement.lastAttackerOid}.
 */
class BotContactDamageRetaliationRegisterTest {

    private static final int BOT_ID = 99_001;
    private static final int MOB_OID = 555;

    @AfterEach
    void cleanup() {
        BotContactDamage.clearBot(BOT_ID);
    }

    @Test
    void aRegisteredAttackerIsRememberedInsideTheWindow() {
        BotContactDamage.registerRetaliation(BOT_ID, MOB_OID);
        BotContactDamage.Attacker a = BotContactDamage.lastAttacker(BOT_ID);
        org.junit.jupiter.api.Assertions.assertNotNull(a, "a registered hit must be readable back");
        assertEquals(MOB_OID, a.mobOid());
    }

    @Test
    void aRepeatRegistrationReplacesInPlace() {
        BotContactDamage.registerRetaliation(BOT_ID, MOB_OID);
        BotContactDamage.registerRetaliation(BOT_ID, 777);
        BotContactDamage.Attacker a = BotContactDamage.lastAttacker(BOT_ID);
        org.junit.jupiter.api.Assertions.assertNotNull(a);
        assertEquals(777, a.mobOid(), "the latest attacker wins");
    }

    @Test
    void despawnCleanupDropsTheRegister() {
        BotContactDamage.registerRetaliation(BOT_ID, MOB_OID);
        BotContactDamage.clearBot(BOT_ID);
        assertNull(BotContactDamage.lastAttacker(BOT_ID), "a despawned bot must leave no entry behind");
    }
}
