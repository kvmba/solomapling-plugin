package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.constants.id.MobId;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterStats;
import soloMapling.ArtificialPlayer.BotTypes.Ariant.AriantPqData;

/**
 * The one rule for "may a bot swing at this mob".
 *
 * <p>Two kinds of monster are never a combat target, and both are invisible to a check that only
 * asks {@code isAlive()}:
 *
 * <ul>
 *   <li><b>Friendly mobs</b> (WZ {@code damagedByMob=1}): the engine paints them like any other
 *       monster and {@code Monster.damage} has no friendly clause, so damage lands. What makes
 *       them special is the quest scripts: HPQ's {@code friendlyKilled} ends the whole instance 5s
 *       after the Moon Bunny dies, Magatia sets {@code escortFail}, the mount quests kick the
 *       party. Killing one is the FAILURE path.</li>
 *   <li><b>The Dojo clear marker</b> ({@code 9300216}, "a transparent item for checking
 *       elimination"): every Dojo boss revives into it on death ({@code Mob.wz} {@code
 *       revive/0}), and it is what {@code dojang_up.js}/{@code dojang_1st.js} read as "this room
 *       is cleared". It carries 2.09B HP, is flagged {@code invincible} (a WZ field this host
 *       never loads) and is NOT friendly - so an unfiltered scan treats it as a boss-sized mob and
 *       the bot swings at an invisible wall forever.</li>
 * </ul>
 *
 * <p>Kept as one rule because the attack layer has several independent scans - the driver's seek,
 * the Pickpocket bomb, Shadow Meso, Shadow Web, Poison Mist and every summon's own strike - and a
 * rule that lives in only one of them reads as fixed while the others still kill the escort.
 * {@link BotAttackEffects#applyExternalHit} is deliberately NOT gated by this: the skill-show
 * dummy ({@code 9300093} Tylus) is a friendly WZ mob, and only the scans may decide.
 */
public final class BotTargetRules {

    private BotTargetRules() {
    }

    /** The primitive form: pure, so the rule is unit-testable without a WZ tree. */
    public static boolean isCombatTarget(MonsterStats stats, int mobId) {
        if (mobId == MobId.TRANSPARENT_ITEM) {
            return false; // the Dojo room-clear marker, not a monster to fight
        }
        return stats == null || !stats.isFriendly();
    }

    /** Whether this monster can be attacked (and chased) at all. Liveness is the caller's test. */
    public static boolean isCombatTarget(Monster monster) {
        return monster != null && isCombatTarget(monster.getStats(), monster.getId())
                && !(monster.getId() == AriantPqData.SCORPION
                && AriantPqData.catchableAt(monster.getHp(), monster.getMaxHp()));
    }

    /** Rechecked under the host monster lock when damage lands, including delayed/summon hits. */
    static int catchSafeDamage(Monster monster, int damage) {
        int catchHp = Math.max(1, (monster.getMaxHp() / 10) * 4 - 1);
        return Math.max(0, Math.min(damage, monster.getHp() - catchHp));
    }
}
