package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;

import java.util.List;
import java.util.Map;

/*
 * The Fire/Ice Demon rider (火凤球 2121003 / 冰凤球 2221003): the two 4th-job mage attacks'
 * monster statuses, applied the way the host's damage handler applies them. A real client's
 * swing lands through AbstractDealDamageHandler, whose generic branch rolls the attack
 * effect's own monster statuses per hit mob - attackEffect.makeChanceResult(), then
 * monster.applyStatus(attackEffect.getMonsterStati(), ..., attackEffect.isPoison(),
 * attackEffect.getDuration()). loadFromData maps FIRE_DEMON / ICE_DEMON to POISON + FREEZE
 * (the demon cases in the monsterStatus switch); demons are NOT in StatEffect.isPoison()'s
 * whitelist, so both land as plain timed statuses - the client renders the poison/freeze
 * state, the server registers the expiry, and no drain tick runs (a player's demon hit does
 * exactly the same on this host). Bosses are refused by applyStatus's own gate. A bot's
 * swing never enters that handler (no client packet), so the driver calls this right after
 * its strike broadcast - the same beat, the same per-mob chance roll.
 *
 * The rider reads the bot's OWN skill level (SkillFactory.getSkill + getSkillLevel): the WZ
 * duration at the held level is the status's lifetime, exactly as a player's would be. Only
 * the two demon skills carry statuses here; every other swing is a no-op that costs one
 * string compare.
 */
public final class BotDemonRider {

    private BotDemonRider() {}

    /*
     * The demon rider: after a landed swing with one of the demon skills, roll the effect's
     * chance per hit mob and apply the effect's own statuses (POISON + FREEZE, plain timed
     * statuses at the held level's duration - demons are not poison-sourced, so no drain
     * tick). A dead mob is skipped; a boss is refused by applyStatus itself.
     */
    public static void apply(Character bot, int skillId, Map<Monster, List<Integer>> hits) {
        if (bot == null || hits == null || hits.isEmpty()) {
            return;
        }
        if (skillId != org.gms.constants.skills.FPArchMage.FIRE_DEMON
                && skillId != org.gms.constants.skills.ILArchMage.ICE_DEMON) {
            return; // the fast gate every non-demon swing exits through
        }
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return;
        }
        int level = bot.getSkillLevel(skill);
        if (level < 1) {
            return; // never apply a rider the bot has not learned
        }
        StatEffect effect = skill.getEffect(level);
        if (effect == null || effect.getMonsterStati().isEmpty()) {
            return;
        }
        for (Monster mob : hits.keySet()) {
            if (!mob.isAlive() || !effect.makeChanceResult()) {
                continue;
            }
            mob.applyStatus(bot, new org.gms.client.status.MonsterStatusEffect(
                    effect.getMonsterStati(), skill, null, false),
                    effect.isPoison(), effect.getDuration());
        }
    }
}
