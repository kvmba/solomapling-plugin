package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.skills.Buccaneer;
import org.gms.constants.skills.Corsair;
import org.gms.constants.skills.ThunderBreaker;
import org.gms.net.server.Server;
import org.gms.net.server.world.Party;
import org.gms.server.StatEffect;
import soloMapling.ArtificialPlayer.BotHelpers;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/*
 * Buff-visual packet ids here are vanilla Cosmic (org.gms.server.StatEffect), not GreenCatMS-sourced.
 * The "show a buff" broadcast for bots. Vanilla StatEffect.applyTo does far more than the
 * visual (HP/MP, consumption, morphs, effect registration) and bails out on many branches,
 * so for cosmetic bots we fire just the two broadcasts that make a buff look like it happened:
 * the cast animation (showBuffEffect) and the persistent aura (giveForeignBuff). No stats are
 * changed and no effect is registered.
 */
public final class BotBuffEffects {

    // effectId 1 + direction 3 = the standard self-cast buff effect (matches the
    // primary-cast broadcast in StatEffect).
    private static final int CAST_EFFECT_ID = 1;

    private BotBuffEffects() {}

    /*
     * Broadcast the buff visual for skillId on the bot to everyone on its
     * map. Returns the buff's WZ duration in ms (for recast cadence), or 0 if the
     * skill/effect can't be resolved. Does NOT apply any stat.
     */
    public static int showBuff(Character bot, int skillId) {
        return showBuff(bot, skillId, false);
    }

    /*
     * As {@link #showBuff(Character, int)}, but when silent is true the map-wide cast animation
     * (showBuffEffect) is skipped and only the persistent aura (giveForeignBuff) is broadcast.
     * Used for the on-arrival re-show: a bot that meets a player should look as if it was ALREADY
     * buffed (the aura is simply there), not be caught mid-cast playing a buff animation for the
     * new arrival. The periodic re-buff keeps the default (animated) path - a grinder topping
     * itself up mid-session is normal and reads as organic.
     */
    public static int showBuff(Character bot, int skillId, boolean silent) {
        if (bot == null || bot.getMap() == null) return 0;

        // Casting a skill is never done from the saddle - drop the mount first so the
        // riding model doesn't fight the cast pose. Cheap no-op for an unmounted bot.
        soloMapling.ArtificialPlayer.BotMountSystem.BotMount.cancelForAction(bot);

        // Gate on the skill existing in Skill.wz BEFORE broadcasting: the client looks the id up
        // to play the cast animation, and an id that isn't there is a client crash for everyone
        // watching. Ids reach here straight from !bot castbuff/givebuff, so they are not trusted.
        //
        // This is only an existence check - the cast animation is still fired for a skill with no
        // resolvable StatEffect, so a legitimate buff's visual is never skipped.
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return 0;

        // Fire the cast animation for every resolvable skill, BEFORE the effect lookup: an id that
        // exists in Skill.wz but has no StatEffect at max level should still play its cast (the aura
        // below is the only part that needs the stat list). Skipped only for the silent on-arrival show.
        if (!silent) {
            bot.getMap().broadcastMessage(bot,
                    PacketCreator.showBuffEffect(bot.getId(), skillId, CAST_EFFECT_ID), false);
        }

        // The persistent aura needs the buff's stat list (cheap memoized lookup,
        // no applyTo). Skip silently if the skill has no stat ups.
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return 0;

        broadcastAura(bot, skillId, effect);
        // A 疾驰 / 伪装 aura is state-bound: the movement tick retires it once the bot stops walking or
        // attacks. Let it know the aura is now up (no-op for every other buff).
        BotAuraState.onAuraShown(bot, skillId);

        return effect.getDuration();
    }

    /**
     * Re-show ONLY the persistent aura for {@code skillId} - no cast animation, and no re-registration.
     * Used when the movement tick re-arms a state-bound aura (疾驰 on the walk's rising edge). Returns
     * the aura's WZ duration in ms (0 if the skill/effect can't be resolved).
     */
    public static int showAura(Character bot, int skillId) {
        if (bot == null || bot.getMap() == null) return 0;
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return 0;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return 0;
        broadcastAura(bot, skillId, effect);
        return effect.getDuration();
    }

    /**
     * Broadcast the bot's persistent aura for {@code skillId} with the layout the v83 client
     * actually parses for that buff.
     *
     * <p><b>Why one layout is not enough.</b> The host's own {@code StatEffect.applyTo} does NOT
     * send every buff through the generic {@code giveForeignBuff}: three skill families carry
     * extended foreign frames that the client decodes with extra fields, and using the short
     * generic frame for them makes the client read past the end of the packet (the crashed
     * "数据过短" symptom). The host switches on exactly these three:</p>
     * <ul>
     *   <li>{@code isDash()} — pirate 疾驰 (5001005 / 15001003 / 1014 / 1001015) →
     *       {@code giveForeignPirateBuff} (per-stat int + skill id + skip + duration),</li>
     *   <li>{@code isInfusion()} — 极速领域 (5121009 / 15111005, and 5221010 which the host
     *       lists under the misleading constant name {@code Corsair.HEROS_WILL}) →
     *       {@code giveForeignPirateBuff},</li>
     *   <li>{@code isWkCharge()} — the {@code WK_CHARGE} 元素剑 family (烈焰/寒冰/雷电/圣灵之剑)
     *       → {@code giveForeignWKChargeEffect}.</li>
     * </ul>
     *
     * <p>Mirroring the host's dispatch here keeps a bot's buff visually identical to a player's.
     * Everything else keeps the generic frame (the shape the host sends for Maple Warrior,
     * Stance, Sharp Eyes, ...).</p>
     */
    private static void broadcastAura(Character bot, int skillId, StatEffect effect) {
        broadcastAura(bot, skillId, effect, effect.getDuration());
    }

    /**
     * As {@link #broadcastAura(Character, int, StatEffect)} but with the length the caller is
     * actually applying (a GM-granted buff may be longer than the WZ duration). Only the pirate
     * frame carries a duration; it is in seconds, as the host's own {@code applyTo} writes it.
     */
    private static void broadcastAura(Character bot, int skillId, StatEffect effect, int durationMs) {
        List<Pair<BuffStat, Integer>> statups = effect.getStatups();
        if (statups.isEmpty()) {
            return;
        }
        if (isDash(skillId) || isInfusion(skillId)) {
            int seconds = Math.max(1, durationMs / 1000); // pirate frames carry seconds
            bot.getMap().broadcastMessage(bot,
                    PacketCreator.giveForeignPirateBuff(bot.getId(), skillId, seconds, statups), false);
            return;
        }
        if (isWkCharge(statups)) {
            bot.getMap().broadcastMessage(bot,
                    PacketCreator.giveForeignWKChargeEffect(bot.getId(), skillId, statups), false);
            return;
        }
        if (isDarkSight(skillId)) {
            // 隐身术 uses the generic frame too, but the host's own isDs() branch normalises the
            // DARKSIGHT statup to value 0 (StatEffect.applyTo) - mirror that so a bot's hide looks
            // exactly like a real player's (the semi-transparent shade observers render).
            bot.getMap().broadcastMessage(bot,
                    PacketCreator.giveForeignBuff(bot.getId(),
                            Collections.singletonList(new Pair<>(BuffStat.DARKSIGHT, 0))), false);
            return;
        }
        bot.getMap().broadcastMessage(bot,
                PacketCreator.giveForeignBuff(bot.getId(), statups), false);
    }

    /** The host's own {@code isDash}: the 疾驰 speed/jump burst (single source: {@link BotAuraState}). */
    private static boolean isDash(int skillId) {
        return BotAuraState.isDash(skillId);
    }

    /** The host's own {@code isDs}: the 隐身术 hide (single source: {@link BotAuraState}). */
    private static boolean isDarkSight(int skillId) {
        return BotAuraState.isDarkSight(skillId);
    }

    /**
     * The host's own {@code isInfusion}: 极速领域 (5121009 / 15111005 / 5221010). The host
     * constant for 5221010 is named {@code Corsair.HEROS_WILL} but the skill itself is 极速领域 -
     * the id, not the name, is what the client decodes.
     */
    private static boolean isInfusion(int skillId) {
        return skillId == Buccaneer.SPEED_INFUSION || skillId == ThunderBreaker.SPEED_INFUSION
                || skillId == Corsair.HEROS_WILL;
    }

    /** The host's own {@code isWkCharge}: any buff whose stat list carries {@code WK_CHARGE}. */
    private static boolean isWkCharge(List<Pair<BuffStat, Integer>> statups) {
        for (Pair<BuffStat, Integer> statup : statups) {
            if (statup.getLeft() == BuffStat.WK_CHARGE) {
                return true;
            }
        }
        return false;
    }

    /* The buff's WZ duration (ms) at max level, or 0 if unresolvable. Cheap memoized lookup. */
    public static int durationOf(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return 0;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        return effect != null ? effect.getDuration() : 0;
    }

    // Range a party buff reaches around the casting bot (split x/y, since MapleStory
    // maps are wide and short). Members further than this aren't buffed.
    private static final int PARTY_BUFF_RANGE_X = 700;
    private static final int PARTY_BUFF_RANGE_Y = 350;

    /* A sensible "extended" buff length for bots: 10 minutes. */
    public static final int EXTENDED_DURATION_MS = 600_000;

    /*
     * Full buff cast for a bot. Always shows the cast animation on the bot; and if
     * the skill is a PARTY buff (has an area-of-effect box), also grants it to the
     * bot's nearby party members - real players get the working buff, party-member
     * bots get the cosmetic aura. Self-only buffs stay on the bot. Returns the
     * buff's WZ duration (ms) for recast cadence.
     */
    public static int castBuff(Character bot, int skillId) {
        return castBuff(bot, skillId, false);
    }

    /* As {@link #castBuff(Character, int)} but with the silent on-arrival re-show semantics of
     * {@link #showBuff(Character, int, boolean)}: no cast animation, only the aura (+ party spread). */
    public static int castBuff(Character bot, int skillId, boolean silent) {
        showBuff(bot, skillId, silent);

        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return 0;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return 0;

        if (effect.isPartyBuff()) {
            applyToTargets(bot, effect, skillId, nearbyPartyMembers(bot));
        }
        return effect.getDuration();
    }

    /*
     * A bot grants a buff to an explicit target list: shows the cast animation on
     * the bot, then applies the REAL buff to each (real players get the actual
     * working effect with no MP cost / no cast pose; bots get the cosmetic aura).
     * Used by the !bot givebuff command.
     */
    public static void givePartyBuff(Character bot, int skillId, Collection<Character> targets) {
        showBuff(bot, skillId);
        if (targets == null || targets.isEmpty()) return;

        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return;

        applyToTargets(bot, effect, skillId, targets);
    }

    private static void applyToTargets(Character bot, StatEffect effect, int skillId, Collection<Character> targets) {
        for (Character target : targets) {
            if (target == null || target == bot) continue;
            if (BotHelpers.isBot(target)) {
                showBuff(target, skillId);            // party-member bots stay cosmetic
            } else {
                effect.applyToTarget(bot, target);    // real player gets the working buff
                showReceivedBuff(target, skillId);    // + the "received a party buff" particle
            }
        }
    }

    /*
     * Show the "received a party buff" particle on target. applyToTarget
     * uses the non-primary apply path, which skips the cast effect, so we fire the exact
     * two packets the engine sends for each affected party member (StatEffect.java:1172-1173):
     * showOwnBuffEffect to the target's own client (they see it on themselves) and a
     * showBuffEffect broadcast to the rest of the map (others see it on them). effectId
     * 2 = the party-receive variant. Works regardless of party membership - it's just packets.
     */
    private static void showReceivedBuff(Character target, int skillId) {
        target.sendPacket(PacketCreator.showOwnBuffEffect(skillId, 2));
        target.getMap().broadcastMessage(target, PacketCreator.showBuffEffect(target.getId(), skillId, 2), false);
    }

    /*
     * Like .givePartyBuff but forces a custom duration (e.g. 10 min) on the
     * real buff given to player targets, instead of the WZ duration. Bots stay
     * cosmetic. Useful for extending key party buffs (Holy Symbol, Maple Warrior, ...).
     */
    public static void giveExtendedBuff(Character bot, int skillId, Collection<Character> targets, int durationMs) {
        showBuff(bot, skillId);
        if (targets == null || targets.isEmpty()) return;

        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return;

        for (Character target : targets) {
            if (target == null || target == bot) continue;
            if (BotHelpers.isBot(target)) {
                showBuff(target, skillId);                       // party-member bots stay cosmetic
            } else {
                applyBuffWithDuration(target, effect, skillId, durationMs);
            }
        }
    }

    /*
     * Apply effect to target with a custom duration - the lean core
     * of StatEffect.applyTo's buff path with our own length: the giveBuff
     * packet (client countdown), registerEffect with an explicit expiry
     * (server stat + expiry; confirmed via Character.registerEffect ->
     * addItemEffectHolder(expirationtime) + updateLocalStats), the foreign-buff aura,
     * and the receive particle. No MP cost, no cast pose on the target.
     */
    private static void applyBuffWithDuration(Character target, StatEffect effect, int skillId, int durationMs) {
        long start = Server.getInstance().getCurrentTime();
        target.sendPacket(PacketCreator.giveBuff(effect.getBuffSourceId(), durationMs, effect.getStatups()));
        target.registerEffect(effect, start, start + durationMs, false);
        // Same layout dispatch as a bot's own aura: a GM may hand over any skill id
        // (incl. a dash / infusion / charge), and its observers must get the extended frame.
        broadcastAura(target, skillId, effect, durationMs);
        showReceivedBuff(target, skillId);
    }

    /* The bot's party members on the same map within party-buff range (excludes the bot). */
    private static List<Character> nearbyPartyMembers(Character bot) {
        Party party = bot.getParty();
        if (party == null || bot.getMap() == null) return Collections.emptyList();

        Point botPos = bot.getPosition();
        List<Character> result = new ArrayList<>();
        for (Character chr : bot.getMap().getCharacters()) {
            if (chr == bot) continue;
            Party other = chr.getParty();
            if (other == null || other.getId() != party.getId()) continue;

            Point p = chr.getPosition();
            if (botPos != null && p != null) {
                if (Math.abs(p.x - botPos.x) > PARTY_BUFF_RANGE_X) continue;
                if (Math.abs(p.y - botPos.y) > PARTY_BUFF_RANGE_Y) continue;
            }
            result.add(chr);
        }
        return result;
    }
}
