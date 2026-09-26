package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.skills.Crusader;
import org.gms.constants.skills.DawnWarrior;
import org.gms.constants.skills.Hero;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import soloMapling.server.MethodScheduler;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Original. The 斗气集中 (Combo, 1111002) orb counter for Crusader-lineage bots (111/112 - the jobs
 * whose buff aura shows the combo) - the mechanic behind the growing orb ring every onlooker's
 * client draws over a comboed fighter.
 *
 * The host's combo lives in the player attack pipeline (CloseRangeDamageHandler reads
 * BuffStat.COMBO, advances it and broadcasts the new count); a bot never enters that pipeline
 * (BotAttackDriver builds and lands its own packets), so without this class the bot's combo buff is
 * a frozen aura. This mirrors the host's observable behaviour at the bot's own sites: each landed
 * swing gains ONE orb up to the WZ cap, the count lapses when the combo times out, and a finisher
 * (Panic / Coma) consumes the ring back to 1.
 *
 * The wire frame is the generic GIVE_FOREIGN_BUFF with BuffStat.COMBO carrying the count - the same
 * statups the host's orb-gain and handleOrbconsume broadcasts carry - so the ring other players see
 * updates exactly like a real player's. There is no self packet: bots have no client.
 */
public final class BotComboOrb {

    /*
     * The orb counter follows the host's wire domain: 1 on a fresh combo, +1 per swing, capped so
     * the last step is the effect's X + 1. The client draws comboBuff - 1 orbs, so a maxed combo
     * (X = 5) tops out at a 5-orb ring carried as a 6 - and a 4th-job hero's cap comes off the
     * ADVANCED combo instead (X = 10, an 11 on the wire). See capFor.
     */
    static final int MIN_ORBS = 1;

    // botId -> current orb count. Released on despawn (clearBot).
    private static final Map<Integer, Integer> orbsByBot = new ConcurrentHashMap<>();

    // botId -> the deadline of the lapse that is currently running. Doubles as the token that lets a
    // delayed task tell "my combo" from "a combo started after mine" (a reused bot id), so a stale
    // task can never lapse a combo it does not own. Released on despawn.
    private static final Map<Integer, Long> lapseDeadlineByBot = new ConcurrentHashMap<>();

    private BotComboOrb() {
    }

    /*
     * One landed swing. The mirror of the host's CloseRangeDamageHandler combo branch: a comboed
     * bot gains exactly ONE orb per swing (regardless of how many mobs the swing hit), until the
     * counter reaches the effect's cap (X + 1); a finisher instead consumes the ring
     * (handleOrbconsume resets to 1). Called from the attack layer after the swing landed; a no-op
     * for every class whose job does not carry the combo buff (the host's comboBuff != null gate)
     * - which here is the Crusader lineage, the jobs the buff layer shows the aura on.
     */
    public static boolean onAttackLanded(Character bot, int skillId) {
        if (bot == null || bot.getJob() == null || !bot.getJob().isA(Job.CRUSADER)) {
            return false;
        }
        Skill skill = comboSkillFor(bot);
        if (skill == null) {
            return false;
        }
        if (isFinisher(skillId)) {
            orbsByBot.put(bot.getId(), MIN_ORBS);
            broadcastOrbs(bot, skill);
            return true;
        }
        int cap = capFor(bot, skill);
        int orbs = orbsByBot.getOrDefault(bot.getId(), MIN_ORBS);
        if (orbs >= cap) {
            return false; // the ring is full - the host stops broadcasting here too
        }
        orbsByBot.put(bot.getId(), orbs + 1);
        scheduleLapse(bot, skill);
        broadcastOrbs(bot, skill);
        return false;
    }

    /** This bot's current orb count - the wire value the aura carries (MIN_ORBS when comboed). */
    static int orbsFor(Character bot) {
        return orbsByBot.getOrDefault(bot.getId(), MIN_ORBS);
    }

    /** Release a despawned bot's combo bookkeeping (mirrors the other per-bot clearBot hooks). */
    public static void clearBot(int botId) {
        orbsByBot.remove(botId);
        lapseDeadlineByBot.remove(botId);
    }

    /** One-line combo report, for the GM diagnostics behind !bot combo <cid>. */
    public static String describe(Character bot) {
        if (bot == null || bot.getJob() == null) {
            return "bot or job is null";
        }
        return bot.getName() + " (job " + bot.getJob().getId() + ") combo=" + orbsFor(bot)
                + (bot.getJob().isA(Job.CRUSADER) ? "" : " - this job has no combo");
    }

    /*
     * GM test hook: jump straight to a full ring, so the growth, the finisher reset and the lapse can
     * be watched without grinding out ten landed swings. Backs !bot combo <cid>.
     */
    public static void fillForTest(Character bot) {
        if (!wantsCombo(bot)) {
            return;
        }
        Skill skill = comboSkillFor(bot);
        if (skill == null) {
            return;
        }
        orbsByBot.put(bot.getId(), capFor(bot, skill));
        scheduleLapse(bot, skill);
        broadcastOrbs(bot, skill);
    }

    /*
     * GM test hook: drop the ring back to a single orb and pull the aura from every viewer. Backs
     * !bot comboreset <cid>, so a comboed bot can be taken back to the fresh look without waiting out
     * the lapse.
     */
    public static void resetForTest(Character bot) {
        if (bot == null) {
            return;
        }
        lapseDeadlineByBot.remove(bot.getId());
        orbsByBot.remove(bot.getId());
        broadcastCancel(bot);
    }

    /** Whether this character is a warrior-line job that can hold a combo at all. */
    private static boolean wantsCombo(Character bot) {
        return bot != null && bot.getJob() != null
                && bot.getJob().isA(Job.CRUSADER) && bot.getMap() != null;
    }

    /*
     * The wire cap for THIS bot: the WZ combo cap X (orbs drawn) plus the counter's own 1. The
     * host resolves the cap from the ADVANCED combo's effect when the 4th-job skill is learned
     * (CloseRangeDamageHandler: advComboSkillLevel > 0 branches first) - its X is 10 where plain
     * combo's is 5 - so the lineage check mirrors exactly that.
     */
    private static int capFor(Character bot, Skill plainCombo) {
        if (bot.getJob() != null && bot.getJob().isA(Job.HERO)) {
            Skill advanced = SkillFactory.getSkill(Hero.ADVANCED_COMBO);
            if (advanced != null && advanced.getMaxLevel() > 0) {
                return advanced.getEffect(advanced.getMaxLevel()).getX() + 1;
            }
        }
        return plainCombo.getEffect(plainCombo.getMaxLevel()).getX() + 1;
    }

    /*
     * This bot's combo skill - the skill id the WZ lapse duration reads from. No skill grant is
     * needed: bots never register buffs server-side, and the aura frame carries only the COMBO
     * statup (no skill id), so the orb ring renders from the stat value alone. Null when Skill.wz
     * cannot resolve it.
     */
    private static Skill comboSkillFor(Character bot) {
        return SkillFactory.getSkill(Crusader.COMBO);
    }

    /*
     * The combo lapses on its own: the host's combo buff expires at the skill's WZ duration and the
     * client pulls the orbs when it does. Ours re-arms on every landed swing (setCombo refreshes the
     * buff the same way), so the timer always describes the CURRENT swing's expiry.
     */
    private static void scheduleLapse(Character bot, Skill skill) {
        long duration = skill.getEffect(skill.getMaxLevel()).getDuration();
        if (duration <= 0) {
            duration = 90_000L; // an unreadable WZ duration keeps the lapse behaviour (and sane)
        }
        long deadline = System.currentTimeMillis() + duration;
        lapseDeadlineByBot.put(bot.getId(), deadline);
        MethodScheduler.runAfterDelay(() -> lapse(bot, deadline), duration);
    }

    private static void lapse(Character bot, long deadline) {
        Long current = lapseDeadlineByBot.get(bot.getId());
        if (current == null || current != deadline) {
            return; // a later swing re-armed the combo, or the bot despawned
        }
        lapseDeadlineByBot.remove(bot.getId());
        orbsByBot.remove(bot.getId());
        broadcastCancel(bot);
    }

    /*
     * Pull the orb ring from every viewer: the host's own buff-cancel broadcast
     * (cancelPlayerBuffs -> CANCEL_FOREIGN_BUFF with the stat list).
     */
    private static void broadcastCancel(Character bot) {
        MapleMap map = bot.getMap();
        if (map != null) {
            map.broadcastMessage(bot, PacketCreator.cancelForeignBuff(bot.getId(),
                    Collections.singletonList(BuffStat.COMBO)), false);
        }
    }

    /*
     * The host's combo-advance broadcast: the foreign COMBO stat carrying the new count - the count,
     * not a flag, is what draws the orb ring over the bot for onlookers. A real player's combo
     * advance sends only this aura refresh (setCombo -> giveForeignBuff), no per-orb effect flash,
     * so the mirror sends exactly that.
     */
    private static void broadcastOrbs(Character bot, Skill skill) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        map.broadcastMessage(bot, PacketCreator.giveForeignBuff(bot.getId(),
                Collections.singletonList(new Pair<>(BuffStat.COMBO, orbsFor(bot)))), false);
    }

    /*
     * Whether {@code skillId} is a combo-consuming finisher: the host's isFinisherSkill set is the
     * Panic / Coma pair (1111003-1111006) plus the Dawn Warrior's two (11111002/11111003).
     */
    static boolean isFinisher(int skillId) {
        return skillId >= Crusader.SWORD_PANIC && skillId <= Crusader.AXE_COMA
                || skillId == DawnWarrior.PANIC || skillId == DawnWarrior.COMA;
    }
}
