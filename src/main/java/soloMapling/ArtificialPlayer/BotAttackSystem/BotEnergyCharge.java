package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.skills.Marauder;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState;
import soloMapling.server.MethodScheduler;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 能量获得 (Energy Charge, 5110001) for brawler-line pirate bots - the touch mechanic, rebuilt inside
 * the plugin because the host only ever learns about it from a real client's packets. Energy is gained
 * per landed hit in CloseRangeDamageHandler, and the retaliation arrives as a TOUCH_MONSTER_ATTACK the
 * host accepts from a real client (TouchMonsterDamageHandler) - a headless bot does neither, so both
 * halves are synthesized here, reusing the host's own packets and values so onlookers cannot tell.
 *
 * Faithful to Character.handleEnergyChargeGain:
 *   - each landed swing adds 102 charge per mob hit; the bar arms at 10000 and flips to the host's
 *     full value 15000 in the same step (the host runs both blocks in one call),
 *   - reaching full broadcasts the charge (the skill's flash + the foreign ENERGY_CHARGE stat that
 *     draws the gauge over the bot) and arms a self-expiry at the skill's WZ duration,
 *   - while full, a mob touching the bot takes a real hit (BotAttackEffects.bodyStrike) - the
 *     synthetic counterpart of the touch packet, gated on the same 15000 the host tests.
 *
 * The bar itself lives on the Character (get/setEnergyBar), exactly where the host reads it, so the
 * char-info packet renders the charged look for free (PacketCreator.writeForeignBuffs).
 *
 * Invariant: the skill is granted before the bar can reach FULL_ENERGY. The host's own
 * reapplyLocalStats reads this skill's effect whenever the bar is 15000 and dereferences effects[-1]
 * for an unlearned skill (Character.java:7230) - that is a hard crash on any later equip change or
 * mount, so charging is skipped outright when Skill.wz cannot resolve the skill.
 *
 * Ours (SoloMapling).
 */
public final class BotEnergyCharge {

    /** Charge added per landed mob hit (the host's +102 in handleEnergyChargeGain). */
    static final int GAIN_PER_HIT = 102;

    /** Bar value at which the charge arms. The arm step itself already flips it to full. */
    static final int ARM_ENERGY = 10_000;

    /** The host's full/charged value - every charged-state check in the host tests exactly this. */
    static final int FULL_ENERGY = 15_000;

    /** Upper bound of the host's arm window (arms at 10000, full at 15000, never between). */
    private static final int ARM_WINDOW_TOP = 11_000;

    /** effectId 2 = the "gained the buff" flash the host broadcasts on every charge step. */
    private static final int CHARGE_EFFECT_ID = 2;

    /** Expiry length when the skill's WZ duration cannot be read (the WZ minimum is ~31s). */
    private static final long FALLBACK_FULL_MS = 30_000L;

    /*
     * Cadence of the touch retaliation. The real client sends a TOUCH_MONSTER_ATTACK on the charged
     * skill's own attack interval (~1s), independent of the bot's hurt i-frames - which is why the
     * contact tick keeps scanning while the bot is in its i-frame window when this is ready.
     */
    static final long RETALIATION_COOLDOWN_MS = 1_000L;

    // botId -> the deadline of the charge that is currently running. Doubles as the token that lets a
    // delayed expiry tell "my charge" from "a charge started after mine" (a reused bot id), so a stale
    // task can never empty a bar it does not own. Released on despawn.
    private static final Map<Integer, Long> fullResetDeadlineByBot = new ConcurrentHashMap<>();

    // botId -> next allowed touch retaliation (epoch ms). Released on despawn.
    private static final Map<Integer, Long> nextRetaliationByBot = new ConcurrentHashMap<>();

    private BotEnergyCharge() {
    }

    /*
     * One landed swing on `mobsHit` mobs. Adds the charge (and flips to the full state) for a
     * brawler-line pirate; a no-op for every other job. Called from the attack layer on every strike
     * that actually landed, mirroring the host's per-mob gain loop.
     */
    public static void onAttackLanded(Character bot, int mobsHit) {
        if (bot == null || mobsHit <= 0 || bot.getJob() == null) {
            return;
        }
        if (!isEnergyChargeJob(bot.getJob().getId())) {
            return;
        }

        // Grant first, always: the bar may not reach 15000 until this skill resolves (see the class note).
        Skill skill = chargeSkillFor(bot);
        if (skill == null) {
            return;
        }

        int energy = bot.getEnergyBar();
        if (energy == FULL_ENERGY) {
            return; // charged: the bar is the expiry timer's to hold, and a full bar gains nothing
        }

        Gain gain = gain(energy, mobsHit);
        bot.setEnergyBar(gain.energy());
        broadcastGain(bot, skill, gain.energy());
        if (gain.becameFull()) {
            scheduleFullExpiry(bot, skill);
        }
    }

    /*
     * Claim this bot's next touch-retaliation beat: charged, off the skill's attack interval, and not
     * pinned by a debuff. The interval is stamped here - on the ATTEMPT, not on a landed hit - exactly
     * like a real client, which fires on the charged skill's fixed cadence while it holds the touch.
     * That also bounds the work: the contact tick scans for a retaliation at most once per interval
     * instead of once per 50 ms tick.
     */
    public static boolean claimRetaliationBeat(Character bot) {
        if (bot == null || bot.getJob() == null) {
            return false;
        }
        if (!isEnergyChargeJob(bot.getJob().getId()) || !isCharged(bot.getEnergyBar())) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < nextRetaliationByBot.getOrDefault(bot.getId(), 0L)) {
            return false;
        }
        // The attack layer's own rule, applied here too: a bot pinned by STUN/SEDUCE or sealed cannot
        // strike back. The movement layer still lets a frozen bot be touched (and hurt), so this is the
        // only place that keeps a stunned brawler from retaliating.
        BotDebuffState status = BotDebuffState.of(bot);
        if (status != null && status.blocksAttack()) {
            return false;
        }
        nextRetaliationByBot.put(bot.getId(), now + RETALIATION_COOLDOWN_MS);
        return true;
    }

    /*
     * Land the claimed retaliation on one mob: a real hit that can kill it and drop its loot like any
     * other bot hit. Ungated - the caller has already claimed the beat via
     * {@link #claimRetaliationBeat(Character)}.
     */
    public static boolean strike(Character bot, Monster mob) {
        if (bot == null || bot.getMap() == null || mob == null) {
            return false;
        }
        int damage = BotDamageModel.rollLine(bot.getJob().getJobTier(), bot.getLevel(), 1);
        return BotAttackEffects.bodyStrike(bot, mob, damage);
    }

    /** Release a despawned bot's charge bookkeeping (mirrors the other per-bot clearBot hooks). */
    public static void clearBot(int botId) {
        fullResetDeadlineByBot.remove(botId);
        nextRetaliationByBot.remove(botId);
    }

    /** One-line charge report, for the GM diagnostics behind !bot energy <cid>. */
    public static String describe(Character bot) {
        if (bot == null || bot.getJob() == null) {
            return "bot or job is null";
        }
        int energy = bot.getEnergyBar();
        String state = isCharged(energy) ? "CHARGED" : (energy >= ARM_ENERGY ? "armed" : "charging");
        return bot.getName() + " (job " + bot.getJob().getId() + ") energy=" + energy + " " + state
                + (isEnergyChargeJob(bot.getJob().getId()) ? "" : " - this job has no Energy Charge");
    }

    /*
     * GM test hook: drive the bar to full the moment a bot has anything to charge. Backs
     * !bot energycharge <cid>, so the charged look and the touch retaliation can be watched without
     * waiting out the ~98 hits a real charge takes.
     */
    public static void fillForTest(Character bot) {
        if (!wantsCharge(bot)) {
            return;
        }
        Skill skill = chargeSkillFor(bot);
        if (skill == null) {
            return;
        }
        bot.setEnergyBar(FULL_ENERGY);
        broadcastGain(bot, skill, FULL_ENERGY);
        scheduleFullExpiry(bot, skill);
    }

    /*
     * GM test hook: empty the bar and pull the gauge from every viewer. Backs !bot energyreset <cid>,
     * so a charged bot can be taken back to the charging look without waiting out the charge.
     */
    public static void resetForTest(Character bot) {
        if (bot == null || bot.getJob() == null) {
            return;
        }
        fullResetDeadlineByBot.remove(bot.getId());
        bot.setEnergyBar(0);
        MapleMap map = bot.getMap();
        if (map != null) {
            map.broadcastMessage(bot, PacketCreator.cancelForeignFirstDebuff(bot.getId(),
                    BuffStat.ENERGY_CHARGE.getValue()), false);
        }
    }

    /** Whether this character is a brawler-line pirate that can hold a charge at all. */
    private static boolean wantsCharge(Character bot) {
        return bot != null && bot.getJob() != null
                && isEnergyChargeJob(bot.getJob().getId()) && bot.getMap() != null;
    }

    // ── charge model (pure, so the numbers are testable without WZ or a character) ──────────────

    /*
     * Add one swing's charge. A single hit both arms the bar and flips it to full - the host has no
     * "armed but not yet charged" resting state either (Character.handleEnergyChargeGain runs the
     * `+= 102` clamp and the `>= 10000` flip in the same call). A call on an already-full bar is a
     * no-op, so a charged bot's swings keep reporting becameFull=false.
     */
    static Gain gain(int currentEnergy, int mobsHit) {
        int energy = currentEnergy;
        boolean becameFull = false;
        for (int i = 0; i < mobsHit; i++) {
            if (energy < ARM_ENERGY) {
                energy += GAIN_PER_HIT;
                if (energy > ARM_ENERGY) {
                    energy = ARM_ENERGY;
                }
            }
            if (energy >= ARM_ENERGY && energy < ARM_WINDOW_TOP) {
                energy = FULL_ENERGY;
                becameFull = true;
            }
        }
        return new Gain(energy, becameFull);
    }

    /** Whether the bar is at the host's charged value - the gate every host check (and the body hit) uses. */
    static boolean isCharged(int energy) {
        return energy == FULL_ENERGY;
    }

    /*
     * Brawler-line pirates at 3rd job and beyond: Marauder 511 and Buccaneer 512, the jobs the host's
     * own gate covers (job.isA(Job.MARAUDER) is true for both). Thunder Breakers (1511/1512) charge
     * their own 15100004 through a Cygnus path this plugin does not model, and the gun line
     * (521/522) has no Energy Charge at all.
     */
    static boolean isEnergyChargeJob(int jobId) {
        return jobId / 10 == 51 && jobId >= 511;
    }

    record Gain(int energy, boolean becameFull) {
    }

    // ── packets ─────────────────────────────────────────────────────────────────────────────────

    /*
     * The two broadcasts the host makes on every charge step: the charge-up flash, and the foreign
     * ENERGY_CHARGE stat carrying the new bar value - the value, not a flag, is what draws the gauge
     * over the bot filling up for onlookers.
     *
     * The wire value is always the client's own ceiling (ARM_ENERGY), never the host's FULL_ENERGY
     * sentinel: the v83 client's gauge tops out at 10000, and the host itself only ever puts values
     * <= 10000 on the wire (its own arm step broadcasts the clamped 10000, not the 15000 it then
     * stores server-side). FULL_ENERGY is a server-side marker - the char-info packet reports it as a
     * 0/1 flag, which this bot gets for free from the bar field.
     */
    private static void broadcastGain(Character bot, Skill skill, int energy) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        StatEffect effect = chargeEffect(bot, skill);
        int seconds = effect != null && effect.getDuration() > 0 ? effect.getDuration() / 1000 : 1;
        map.broadcastMessage(bot,
                PacketCreator.showBuffEffect(bot.getId(), skill.getId(), CHARGE_EFFECT_ID), false);
        map.broadcastMessage(bot, PacketCreator.giveForeignPirateBuff(bot.getId(), skill.getId(), seconds,
                Collections.singletonList(new Pair<>(BuffStat.ENERGY_CHARGE, Math.min(energy, ARM_ENERGY)))), false);
    }

    /*
     * The charged state lapses on its own after the skill's WZ duration, emptying the bar and pulling
     * the gauge from every viewer - the host's own timer does the same two things
     * (Character.handleEnergyChargeGain).
     */
    private static void scheduleFullExpiry(Character bot, Skill skill) {
        StatEffect effect = chargeEffect(bot, skill);
        long durationMs = effect != null && effect.getDuration() > 0 ? effect.getDuration() : FALLBACK_FULL_MS;
        long deadline = System.currentTimeMillis() + durationMs;
        fullResetDeadlineByBot.put(bot.getId(), deadline);
        MethodScheduler.runAfterDelay(() -> expireFull(bot, deadline), durationMs);
    }

    private static void expireFull(Character bot, long deadline) {
        Long current = fullResetDeadlineByBot.get(bot.getId());
        if (current == null || current != deadline) {
            return; // superseded by a later charge, or released on despawn
        }
        fullResetDeadlineByBot.remove(bot.getId());
        bot.setEnergyBar(0);
        MapleMap map = bot.getMap();
        if (map != null) {
            map.broadcastMessage(bot, PacketCreator.cancelForeignFirstDebuff(bot.getId(),
                    BuffStat.ENERGY_CHARGE.getValue()), false);
        }
    }

    /*
     * The bot's Energy Charge skill, granting it first when the bot never learned one. Bots are
     * synthetic and spend no SP, so this mirrors BotMount.learnRiderSkill - but the granted LEVEL
     * follows the bot's own level rather than jumping to max (see skillLevelForBot), so a bot that
     * has just advanced reads like a player who has just advanced: the charged state lasts its
     * level-appropriate time and carries its level-appropriate bonus.
     *
     * Null when the skill cannot be resolved OR the grant did not stick (an unreadable Skill.wz, a
     * level clamped away): the caller must then leave the bar alone, because a bar at FULL with an
     * unlearned skill crashes the host's own stat recompute
     * (Character.reapplyLocalStats -> Skill.getEffect(0) -> effects[-1]).
     */
    private static Skill chargeSkillFor(Character bot) {
        Skill skill = SkillFactory.getSkill(Marauder.ENERGY_CHARGE);
        if (skill == null || skill.getMaxLevel() <= 0) {
            return null;
        }
        int wanted = skillLevelForBot(bot.getLevel(), skill.getMaxLevel());
        if (wanted < 1) {
            // Below the third job: nothing to grant, and the bar must not charge (see the note above).
            // A GM can force a Marauder job onto a low-level bot, so this is a real path, not a
            // theoretical one.
            return null;
        }
        if (bot.getSkillLevel(skill) < wanted) {
            bot.changeSkillLevel(skill, (byte) wanted, skill.getMaxLevel(), -1);
            if (bot.getSkillLevel(skill) < 1) {
                return null; // the grant did not take - never let the bar reach FULL
            }
        }
        return skill;
    }

    /** The 3rd job an Explorer Marauder/Buccaneer advances into Energy Charge at. */
    static final int THIRD_JOB_LEVEL = 70;

    /*
     * Skill points the host hands out per level after a job advance (level_up_sp_gain, 3 by default),
     * and what a single skill level costs. The bot's granted level follows them so its Energy Charge
     * fills in at the pace a player's would.
     */
    private static final int SP_PER_LEVEL = 3;

    /*
     * The skill level a bot of this character level would plausibly hold: 1 on the day of the third
     * job, filling in at the host's own SP rate from there. Energy Charge is the FIRST skill the
     * CompanionSkillBuilds brawler build spends on and goes straight to 40, so a bot that prioritised
     * it the same way tops out a little past level 83 - which is why most third- and fourth-job
     * brawlers read as maxed while a fresh Marauder reads as a level-1 charge.
     *
     * This is the ONE place the granted level is decided. Level changes only cosmetics and the
     * charged state's WZ duration / watk - the charge math itself (a flat +102 a hit) is level-free.
     */
    static int skillLevelForBot(int characterLevel, int maxLevel) {
        if (characterLevel < THIRD_JOB_LEVEL) {
            return 0; // no Energy Charge before the third job
        }
        int level = 1 + (characterLevel - THIRD_JOB_LEVEL) * SP_PER_LEVEL;
        return Math.max(1, Math.min(maxLevel, level));
    }

    private static StatEffect chargeEffect(Character bot, Skill skill) {
        return skill.getEffect(Math.max(1, bot.getSkillLevel(skill)));
    }
}
