package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.constants.game.CharacterStance;
import org.gms.constants.skills.Beginner;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.Buccaneer;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.Noblesse;
import org.gms.constants.skills.Pirate;
import org.gms.constants.skills.ThunderBreaker;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifetime bookkeeping for the two bot cosmetic auras whose official behaviour is state-bound rather
 * than simply duration-bound, so a plain "show the aura" broadcast is not enough:
 *
 * <ul>
 *   <li><b>Pirate 疾驰 (DASH, e.g. 5001005).</b> A speed/jump burst the player holds only while
 *       WALKING - the double-tap direction key starts it and it drops the moment the player stops,
 *       jumps, swims or grabs a rope. The plugin's aura packet carries a fixed duration, so without
 *       this it lingered through every stand / jump / swim / climb. The movement tick therefore OWNS
 *       the 疾驰 display: it shows the aura while the bot's wire stance is a walk and cancels it the
 *       instant the stance stops being a walk (Stand / Jump / Swim / Rope / Ladder). The bot's kit is
 *       resolved from its job's buff registry ({@link BotBuffConfig}) once and cached, so a bot with a
 *       疾驰 is recognised even before it ever casts one, and {@link BotBuffDriver} skips 疾驰 in its
 *       periodic sweep so the aura never lingers from a macro cast.
 *       <br>Show/cancel packet note: the show frame carries the burst on the v83-decodable
 *       SPEED/JUMP mask positions (the host's DASH2/DASH bits are undecodable by a v83 client -
 *       see {@link BotBuffEffects#broadcastAura}), and this class's cancel mask mirrors that.</li>
 *   <li><b>Pirate 橡木伪装 (OAK_BARREL, 5101007).</b> A hide morph. In the official client the attack
 *       key's handler cancels it before doing anything else ({@code if (IsHideMorphed())
 *       SendSkillCancelRequest(BRAWLER_OAK_BARREL)}), and taking a 骑宠 mount clears it. A bot has no
 *       client to run either rule, so {@link #cancelDisguiseForAction} runs at every swing site and
 *       the movement tick retires it while a mount is up.</li>
 * </ul>
 *
 * <p>The 变身 (TRANSFORMATION / SUPER_TRANSFORMATION) morphs are ATTACKABLE, so - unlike 伪装 - an
 * attack must NOT cancel them. Tracking is therefore keyed on which MORPH skill is currently shown:
 * only when that is 橡木伪装 does {@link #cancelDisguiseForAction} fire.</p>
 *
 * <p>A bot's auras are broadcast only - no host effect is registered - so the cancel is the matching
 * foreign-buff cancel frame, and "is it up" lives here. Keyed by character id; released on despawn via
 * {@link #clearBot}. Fields are concurrent because the aura broadcast runs on the bot's macro thread
 * while the movement tick runs on the movement thread.</p>
 */
public final class BotAuraState {

    /**
     * The wire statups of a 疾驰 aura's CANCEL frame. This must list exactly the positions the SHOW
     * frame's mask set: {@link BotBuffEffects} remaps the 疾驰 burst onto the v83-decodable
     * SPEED/JUMP positions (the host's DASH2/DASH mask bits land on positions the v83 client has no
     * decode branch for), so the cancel mask has to use the same remapped stats or the client would
     * never clear the aura.
     */
    private static final List<BuffStat> DASH_STATS = List.of(BuffStat.SPEED, BuffStat.JUMP);
    /** The wire statup of any skill morph (MORPH). */
    private static final List<BuffStat> MORPH_STATS = List.of(BuffStat.MORPH);

    /** Fallback re-show cadence for a 疾驰 whose WZ duration is missing (matches BotBuffDriver). */
    private static final long DASH_FALLBACK_REFRESH_MS = 60_000L;

    /** botId -> the 疾驰 skill id this bot's kit carries (0 = none), resolved once and cached. */
    private static final Map<Integer, Integer> DASH_SKILL = new ConcurrentHashMap<>();
    /** botIds whose 疾驰 aura is intended to be up (walking); a cancel is only sent on the drop edge. */
    private static final Set<Integer> DASH_UP = ConcurrentHashMap.newKeySet();
    /** botId -> epoch-ms at which the 疾驰 aura must be re-shown (the WZ duration, minus a margin). */
    private static final Map<Integer, Long> DASH_RESHOW_AT = new ConcurrentHashMap<>();

    /** botId -> the MORPH skill id currently shown (a transformation, or the 伪装 hide morph). */
    private static final Map<Integer, Integer> MORPH_SKILL = new ConcurrentHashMap<>();

    private BotAuraState() {}

    /** The host's own {@code isDash} set: the 疾驰 speed/jump burst. */
    public static boolean isDash(int skillId) {
        return skillId == Pirate.DASH || skillId == ThunderBreaker.DASH
                || skillId == Beginner.SPACE_DASH || skillId == Noblesse.SPACE_DASH;
    }

    /** 橡木伪装 (5101007) - the HIDE morph the attack key cancels. */
    static boolean isDisguise(int skillId) {
        return skillId == Brawler.OAK_BARREL;
    }

    /**
     * The 变身 morph family - ATTACKABLE, so an attack must not cancel them. Mirrors the host's
     * {@code isSkillMorph()} (the explorers the bot registry grades into).
     */
    static boolean isTransformMorph(int skillId) {
        return skillId == Marauder.TRANSFORMATION || skillId == Buccaneer.SUPER_TRANSFORMATION
                || skillId == ThunderBreaker.TRANSFORMATION;
    }

    /**
     * The 疾驰 rule, as a pure seam: the aura is only valid while the bot is WALKING on the ground.
     * The walk stance is exactly the grounded-and-moving pose, so standing (STAND), a jump (JUMP), a
     * swim (SWIM) or a rope/ladder (ROPE/LADDER) all render a different stance and cancel it.
     */
    static boolean dashHolds(int stance) {
        return CharacterStance.isWalking(stance);
    }

    /**
     * Record that {@code bot}'s aura for {@code skillId} was just broadcast (a macro cast or a GM /
     * party-buff show). 疾驰 is included so a stray cast from a stand is retired on the next movement
     * tick; the walking display itself is governed by {@link #tickMovement}. The morph auras are noted
     * so an attack / mount can cancel the right one (a transform supersedes an earlier disguise).
     */
    public static void onAuraShown(Character bot, int skillId) {
        if (bot == null) {
            return;
        }
        int id = bot.getId();
        if (isDash(skillId)) {
            DASH_SKILL.putIfAbsent(id, skillId);
            DASH_UP.add(id);
        } else if (isDisguise(skillId) || isTransformMorph(skillId)) {
            MORPH_SKILL.put(id, skillId);
        }
    }

    /**
     * Retire / refresh the state-bound auras this tick, from the bot's settled wire stance and mount
     * state. Runs for every GC-controlled bot.
     *
     * <p>The SHOW follows the rest of the plugin's LOD policy (an aura is only worth broadcasting
     * where a real player can see it), but the CANCEL is always sent: a bot's aura has no server-side
     * holder, so once shown it keeps rendering on every client until it receives the cancel -
     * suppressing the cancel because the map just went dark (or the observer poll's cached set
     * flipped) would strand a stale aura. A cancel for an aura that was never shown is a harmless
     * no-op on the client, and the edge is rare, so this costs nothing.</p>
     */
    public static void tickMovement(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        int id = bot.getId();
        int dashSkill = dashSkillFor(bot); // cached (0 = no dash)
        boolean hasMorph = MORPH_SKILL.containsKey(id);
        if (dashSkill == 0 && !hasMorph) {
            return; // no state-bound aura for this bot (the overwhelming majority)
        }
        boolean observed = GCMovement.isMapObserved(bot.getMapId());

        // 疾驰: valid only while the wire stance is a walk and the bot is not astride a mount (the
        // ride owns the pose). Any other stance (stand / jump / swim / rope / ladder) cancels it;
        // while walking it is (re)shown, throttled to the aura's own refresh window. The refresh clock
        // only advances on an actual show, so a walk that begins while unobserved shows on the first
        // observed tick.
        if (dashSkill != 0) {
            boolean mounted = bot.getBuffedValue(BuffStat.MONSTER_RIDING) != null;
            if (!mounted && dashHolds(bot.getStance())) {
                boolean firstWalk = DASH_UP.add(id);
                if (observed && (firstWalk || System.currentTimeMillis() >= DASH_RESHOW_AT.getOrDefault(id, 0L))) {
                    int durationMs = BotBuffEffects.showAura(bot, dashSkill);
                    long window = durationMs > 0 ? (long) (durationMs * 0.9) : DASH_FALLBACK_REFRESH_MS;
                    DASH_RESHOW_AT.put(id, System.currentTimeMillis() + window);
                }
            } else if (DASH_UP.remove(id)) {
                DASH_RESHOW_AT.remove(id);
                cancel(bot, DASH_STATS); // always: a shown aura must be retirable whatever the LOD state
            }
        }

        // 伪装: a 骑宠 (mount) clears it, exactly as dismounting for an action does.
        if (isDisguised(id) && bot.getBuffedValue(BuffStat.MONSTER_RIDING) != null) {
            MORPH_SKILL.remove(id);
            cancel(bot, MORPH_STATS);
        }
    }

    /**
     * Cancel the 伪装 aura for an action that officially breaks it: any attack or attack skill (the
     * client cancels OAK_BARREL the moment the attack key is pressed). Called alongside the mount's own
     * {@code cancelForAction} at every swing site. No-op unless 橡木伪装 is the morph currently shown,
     * so an attackable 变身 is left alone.
     */
    public static void cancelDisguiseForAction(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        int id = bot.getId();
        if (isDisguised(id)) {
            MORPH_SKILL.remove(id);
            cancel(bot, MORPH_STATS);
        }
    }

    /** This bot's 疾驰 skill id (0 = none), resolved once from its job's buff registry and cached. */
    private static int dashSkillFor(Character bot) {
        Integer cached = DASH_SKILL.get(bot.getId());
        if (cached != null) {
            return cached;
        }
        int found = 0;
        for (int skillId : BotBuffConfig.buffsForJob(bot.getJob())) {
            if (isDash(skillId)) {
                found = skillId;
                break;
            }
        }
        DASH_SKILL.put(bot.getId(), found);
        return found;
    }

    private static boolean isDisguised(int botId) {
        Integer shown = MORPH_SKILL.get(botId);
        return shown != null && shown == Brawler.OAK_BARREL;
    }

    private static void cancel(Character bot, List<BuffStat> statups) {
        bot.getMap().broadcastMessage(bot, PacketCreator.cancelForeignBuff(bot.getId(), statups), false);
    }

    /** Release a despawned bot's aura bookkeeping so the maps don't grow unbounded. */
    public static void clearBot(int botId) {
        DASH_SKILL.remove(botId);
        DASH_UP.remove(botId);
        DASH_RESHOW_AT.remove(botId);
        MORPH_SKILL.remove(botId);
    }
}
