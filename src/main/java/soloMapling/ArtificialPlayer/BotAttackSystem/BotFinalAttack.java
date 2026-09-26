package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.inventory.WeaponType;
import org.gms.constants.skills.Crossbowman;
import org.gms.constants.skills.Fighter;
import org.gms.constants.skills.Hunter;
import org.gms.constants.skills.Page;
import org.gms.constants.skills.Spearman;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/*
 * Original. The 终极攻击 family (Final Attack: 终极剑/斧/棍/枪/矛/弓/弩) for warrior and bowman bots -
 * the passive that occasionally lands an extra blow right after a swing.
 *
 * The host's final attack is triggered by the real client: the player's attack lands, the client
 * rolls the skill's prop itself and sends a SECOND attack packet tagged with the final-attack skill
 * id; the server only re-renders it (AbstractDealDamageHandler) and its per-attack damage override
 * never applies to it (DawnWarrior.FINAL_ATTACK is the known host exception). A bot has no client to
 * roll anything, so the passive never fires on its own - this class rolls the chance server-side
 * and renders the same thing: an immediate second swing under the final-attack skill id, one line
 * per swing, no skill damage override (the host applies none to triggered final attacks either).
 *
 * Kept deliberately minimal (bots are decorative): no separate reach re-scan, no extra crit roll -
 * the follow-up re-strikes the swing's nearest target with one line off the same damage band, which
 * is exactly what the visual reads as.
 */
public final class BotFinalAttack {

    // botId -> epoch-ms before which this bot's final attack cannot trigger again. The follow-up
    // rides the same cadence as a real client's re-swing (~1s); without it a maxed prop would chain
    // visually on top of every fast grinder swing. Released on despawn.
    private static final long TRIGGER_COOLDOWN_MS = 1_000L;
    private static final Map<Integer, Long> nextTriggerByBot = new ConcurrentHashMap<>();

    /** The host's trigger chance at max level: bows/crossbows 50%, weapons 40% (Skill.wz prop). */
    private static final double PROP_MAX = 0.40;
    private static final double PROP_BOW_MAX = 0.50;

    private BotFinalAttack() {
    }

    /*
     * Roll the passive right after a landed swing. On a hit, broadcasts the final-attack swing (the
     * caller's own body action - the client plays the follow-up on the same attack animation) under
     * the weapon's final-attack skill id, one damage line on the swing's nearest still-living target,
     * and lands the real damage through the ordinary bot kill/EXP/loot path.
     *
     * {@code hits} is the swing's damage map (mob -> lines) as the attack layer built it, nearest
     * mob first; the follow-up takes the first entry it can still hit.
     */
    public static void maybeTrigger(Character bot, WeaponType weapon, int bodyActionId, int facingMask,
                                    Map<Monster, List<Integer>> hits) {
        if (bot == null || bot.getMap() == null || weapon == null || hits == null || hits.isEmpty()) {
            return;
        }
        Skill skill = skillFor(bot, weapon);
        if (skill == null) {
            return; // not a final-attack weapon (sword<->axe variants resolve below; blunt falls through)
        }
        int jobId = bot.getJob() == null ? 0 : bot.getJob().getId();
        if (jobId / 100 != 1 && jobId / 100 != 3) {
            return; // warrior (1xx) and bowman (3xx) branches only
        }
        long now = System.currentTimeMillis();
        if (now < nextTriggerByBot.getOrDefault(bot.getId(), 0L)) {
            return;
        }
        nextTriggerByBot.put(bot.getId(), now + TRIGGER_COOLDOWN_MS);

        double prop = weapon == WeaponType.BOW || weapon == WeaponType.CROSSBOW ? PROP_BOW_MAX : PROP_MAX;
        if (ThreadLocalRandom.current().nextDouble() >= prop) {
            return;
        }

        // The main swing's hitDelay may not have landed its damage yet, so the first listed mob can
        // still be alive here; the first ALIVE one is the honest follow-up target.
        Monster target = null;
        for (Monster mob : hits.keySet()) {
            if (mob.isAlive()) {
                target = mob;
                break;
            }
        }
        if (target == null) {
            return;
        }

        int skillId = skill.getId();
        int skillLevel = resolveSkillLevel(bot, skill);
        int damage = BotDamageModel.rollLine(bot.getJob().getJobTier(), bot.getLevel(), 1);

        // The follow-up swing renders immediately (a real client's final attack needs no travel time
        // for melee and the projectile is cosmetic for bowmen), one line on one mob.
        Map<Integer, List<Integer>> targets = new HashMap<>();
        targets.put(target.getObjectId(), List.of(damage));
        bot.getMap().broadcastMessage(bot, PacketCreator.closeRangeAttack(bot, skillId, skillLevel,
                facingMask, (1 << 4) | 1, targets, BotAttackData.DEFAULT_ATTACK_SPEED, bodyActionId, 0),
                false);
        BotAttackEffects.applyExternalHit(bot, target, damage);

        // The host's orb-gain branch runs on the final-attack packet too (its own
        // numAttacked > 0, only Shout is excluded) - a comboed Hero's follow-up gains an orb
        // exactly like every other swing.
        BotComboOrb.onAttackLanded(bot, skillId);
    }

    /** Release a despawned bot's trigger cooldown (mirrors the other per-bot clearBot hooks). */
    public static void clearBot(int botId) {
        nextTriggerByBot.remove(botId);
    }

    /*
     * The final-attack skill for this job + weapon: v83 splits the passive by WEAPON, so the pairs
     * resolve per swing - Hero/Paladin swords vs axes (the axe/blunt weapon types are the shared
     * GENERAL* ids, so the JOB branch tells 终极斧 from 终极棍), Dark Knight spear/pole-arm, Hunter
     * bow, Crossbowman crossbow. Null when this weapon/job has no final attack (knuckle/gun/claw/
     * dagger/staff/wand, and every non-warrior job the branch gate already excluded).
     */
    private static Skill skillFor(Character bot, WeaponType weapon) {
        Job job = bot.getJob();
        boolean warrior = job.isA(Job.WARRIOR);
        int skillId;
        if (weapon == WeaponType.BOW) {
            skillId = Hunter.FINAL_ATTACK;
        } else if (weapon == WeaponType.CROSSBOW) {
            skillId = Crossbowman.FINAL_ATTACK;
        } else if (warrior && weapon == WeaponType.SWORD1H || weapon == WeaponType.SWORD2H) {
            skillId = Fighter.FINAL_ATTACK_SWORD;
        } else if (warrior && (weapon == WeaponType.GENERAL1H_SWING || weapon == WeaponType.GENERAL1H_STAB
                || weapon == WeaponType.GENERAL2H_SWING || weapon == WeaponType.GENERAL2H_STAB)) {
            // an axe for the fighter line, a blunt weapon for the page line
            skillId = job.isA(Job.FIGHTER) ? Fighter.FINAL_ATTACK_AXE : Page.FINAL_ATTACK_BW;
        } else if (warrior && (weapon == WeaponType.SPEAR_STAB || weapon == WeaponType.SPEAR_SWING
                || weapon == WeaponType.POLE_ARM_SWING || weapon == WeaponType.POLE_ARM_STAB)) {
            skillId = Spearman.FINAL_ATTACK_POLEARM;
        } else {
            return null;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        return skill != null && skill.getMaxLevel() > 0 ? skill : null;
    }

    /** Max level - bots do not learn passives, so the follow-up renders at the skill's ceiling. */
    private static int resolveSkillLevel(Character bot, Skill skill) {
        int learned = bot.getSkillLevel(skill);
        return learned > 0 ? learned : skill.getMaxLevel();
    }
}
