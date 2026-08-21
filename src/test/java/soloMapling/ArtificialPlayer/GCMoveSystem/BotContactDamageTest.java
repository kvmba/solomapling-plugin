package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotContactDamageTest {

    @Test
    void trainingBotTakesRealHpDamage() {
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(40, 12);

        assertEquals(12, damage.broadcastDamage());
        assertEquals(12, damage.hpDamage());
    }

    @Test
    void persistentCompanionTakesRealHpDamage() {
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(40, 12);

        assertEquals(12, damage.broadcastDamage());
        assertEquals(12, damage.hpDamage());
    }

    @Test
    void lethalCompanionHitUsesOneHpMvpFloorWithoutChangingHurtDisplay() {
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(5, 12);

        assertEquals(12, damage.broadcastDamage());
        assertEquals(4, damage.hpDamage());
    }

    @Test
    void botAtDeathFloorDoesNotLoseMoreHp() {
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(1, 12);

        assertEquals(12, damage.broadcastDamage());
        assertEquals(0, damage.hpDamage());
    }

    @Test
    void missNeverChangesHp() {
        BotContactDamage.MobHitDamage damage =
                BotContactDamage.resolveMobHitDamage(40, 0);

        assertEquals(0, damage.broadcastDamage());
        assertEquals(0, damage.hpDamage());
    }
}
