package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.server.life.MonsterStats;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one targeting rule every bot scan shares.
 *
 * <p>Two monsters must never be attacked, and neither is detectable with {@code isAlive()}
 * alone:
 *
 * <ul>
 *   <li>a <b>friendly</b> mob ({@code damagedByMob=1}) - the HPQ Moon Bunny, Magatia's escort, the
 *       mount-quest escorts. The engine lets damage land on them, and the quest scripts treat the
 *       death as the FAILURE path ({@code friendlyKilled} ends the instance);</li>
 *   <li>the <b>Dojo clear marker</b> {@code 9300216} - every dojo boss revives into it, it carries
 *       2.09B HP, and its {@code invincible} WZ flag is never read by this host. A scan that only
 *       asks {@code isAlive()} keeps a bot swinging at an invisible wall for the rest of the run,
 *       and the same scan is why {@code PqActions.seekAndAttack} would chase it.</li>
 * </ul>
 *
 * <p>The rule is primitive-shaped ({@link MonsterStats} + id) so it is testable without a WZ tree
 * or a Spring context - the constraint that keeps every attack-layer test in this package
 * source-level today.
 */
class BotTargetRulesTest {

    private static MonsterStats stats(boolean friendly) {
        MonsterStats s = new MonsterStats();
        s.setFriendly(friendly);
        return s;
    }

    @Test
    void aFriendlyMobIsNotATarget() {
        assertFalse(BotTargetRules.isCombatTarget(stats(true), 9300061), "the Moon Bunny");
        assertFalse(BotTargetRules.isCombatTarget(stats(true), 9300137), "Magatia's Juliet");
    }

    @Test
    void anOrdinaryMobIsATarget() {
        assertTrue(BotTargetRules.isCombatTarget(stats(false), 9300172));
        assertTrue(BotTargetRules.isCombatTarget(stats(false), 100100));
    }

    /** The marker is NOT friendly in WZ, so only the id clause can exclude it. */
    @Test
    void theDojoClearMarkerIsNeverATarget() {
        assertFalse(BotTargetRules.isCombatTarget(stats(false), 9300216),
                "the revive marker is a clear signal, not a monster - and it is invincible");
        assertFalse(BotTargetRules.isCombatTarget(stats(true), 9300216),
                "the id clause outranks the friendly clause too");
    }

    /** A stats-less monster (a partially loaded row) is still attackable: only the marker is special. */
    @Test
    void aMissingStatsRowDoesNotHideAnOrdinaryMob() {
        assertTrue(BotTargetRules.isCombatTarget(null, 9300172));
        assertFalse(BotTargetRules.isCombatTarget(null, 9300216));
    }

    @Test
    void theMonsterOverloadAnswersTheSameWay() {
        AtomicReference<Boolean> seen = new AtomicReference<>();
        // The overload only reads getStats()/getId(); a null monster is the boundary it reports on.
        assertFalse(BotTargetRules.isCombatTarget((org.gms.server.life.Monster) null));
        seen.set(BotTargetRules.isCombatTarget(null, 9300061));
        assertTrue(seen.get(), "no stats row, no friendly flag - an ordinary id stays targetable");
    }
}
