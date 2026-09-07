package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotHealthFloor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotContactDamageTest {

    @Test
    void trainingBotTakesRealHpDamage() {
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(40, 12, 100);

        assertEquals(12, damage.broadcastDamage());
        assertEquals(12, damage.hpDamage());
        assertFalse(damage.lethal());
    }

    @Test
    void persistentCompanionTakesRealHpDamage() {
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(40, 12, 100);

        assertEquals(12, damage.broadcastDamage());
        assertEquals(12, damage.hpDamage());
    }

    @Test
    void damageStopsAtTheFivePercentFloorNotOneHp() {
        // 1000 HP pool -> floor of 50. A bot sitting at 80 takes 30, not 79: the old clamp
        // would have taken it to 1 and shown an empty bar on a bot that is still alive.
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(80, 40, 1000);

        assertEquals(40, damage.broadcastDamage());
        assertEquals(30, damage.hpDamage()); // 80 - floor(1000)=50
        assertFalse(damage.lethal());
    }

    @Test
    void botAtTheFloorEitherDiesOrLosesNoMoreHp() {
        // Pinned at the floor, a further hit has nothing left to take: it either finishes the
        // bot off (the "no time to drink" case) or leaves it exactly where it is. What must
        // never happen is the old behaviour of grinding it down towards 1 HP.
        int floor = BotHealthFloor.floorFor(1000); // 50
        for (int i = 0; i < 200; i++) {
            BotContactDamage.MobHitDamage damage =
                    BotContactDamage.resolveMobHitDamage(floor, 12, 1000);
            assertEquals(12, damage.broadcastDamage());
            if (damage.lethal()) {
                assertEquals(12, damage.hpDamage(), "a lethal hit is not clamped");
            } else {
                assertEquals(0, damage.hpDamage(), "a survived hit cannot go below the floor");
            }
        }
    }

    @Test
    void missNeverChangesHp() {
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(40, 0, 1000);

        assertEquals(0, damage.broadcastDamage());
        assertEquals(0, damage.hpDamage());
        assertFalse(damage.lethal());
    }

    // ── Lethal hits ──────────────────────────────────────────────────────────

    @Test
    void hitBiggerThanTheWholePoolIsAlwaysLethal() {
        // "One-shot": more damage than the bot has HP, so no amount of drinking saves it.
        assertTrue(BotContactDamage.lethal(1568, 2000, 1568));
        assertTrue(BotContactDamage.lethal(50, 50, 50));
    }

    @Test
    void ordinaryHitOnAHealthyBotIsNeverLethal() {
        assertFalse(BotContactDamage.lethal(1568, 180, 1568));
        assertFalse(BotContactDamage.lethal(500, 90, 1000));
    }

    @Test
    void zeroDamageIsNeverLethal() {
        assertFalse(BotContactDamage.lethal(1, 0, 1000));
    }

    @Test
    void floorHitsAreLethalOnlySometimes() {
        // A bot pinned at the floor that keeps getting hit must not die every single time —
        // but it has to be possible, otherwise the death state can never be reached in play
        // (contact damage is a few hundred at most against a four-figure pool).
        int floor = BotHealthFloor.floorFor(1568);
        int lethal = 0;
        int survived = 0;
        for (int i = 0; i < 400; i++) {
            if (BotContactDamage.lethal(floor, 180, 1568)) {
                lethal++;
            } else {
                survived++;
            }
        }
        assertTrue(lethal > 0, "a floor hit must sometimes finish the bot off");
        assertTrue(survived > 0, "a floor hit must not always finish the bot off");
    }

    @Test
    void lethalHitPassesTheFullDamageThrough() {
        int maxHp = 1568;
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(maxHp, maxHp + 500, maxHp);

        assertTrue(damage.lethal());
        assertEquals(maxHp + 500, damage.broadcastDamage());
        assertEquals(maxHp + 500, damage.hpDamage()); // not clamped to the floor
    }
}
