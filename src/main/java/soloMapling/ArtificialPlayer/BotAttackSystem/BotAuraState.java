package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.constants.game.CharacterStance;
import org.gms.constants.skills.Beginner;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.Buccaneer;
import org.gms.constants.skills.Corsair;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.NightWalker;
import org.gms.constants.skills.Noblesse;
import org.gms.constants.skills.Pirate;
import org.gms.constants.skills.Rogue;
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
 *   <li><b>Pirate 疾驰 (DASH, e.g. 5001005).</b> The speed/jump burst a real pirate double-taps
 *       into on a committed run. The burst lasts its WZ duration (20s at max level) and rides
 *       through jumps and ropes, but is released the moment the bot STOPS moving on the ground —
 *       the same tick, so {@link BotDashBurst} is the single lifetime authority. The aura here is
 *       its visible side: the movement tick shows the aura while a burst is live and cancels it
 *       the moment the burst is gone (a mount or a live 变身/海盗船 pose still owns the body and
 *       suppresses the display). The bot's kit is resolved from its job's buff registry
 *       ({@link BotBuffConfig}) once and cached, so a bot with a 疾驰 is recognised even before it
 *       ever bursts, and {@link BotBuffDriver} skips 疾驰 in its periodic sweep so the aura never
 *       lingers from a macro cast (a GM cast via {@link BotBuffEffects} reaches
 *       {@link BotDashBurst#startBurst} and runs the real burst).</li>
 *   <li><b>Pirate 橡木伪装 (OAK_BARREL, 5101007).</b> A hide morph. In the official client the attack
 *       key's handler cancels it before doing anything else ({@code if (IsHideMorphed())
 *       SendSkillCancelRequest(BRAWLER_OAK_BARREL)}), and taking a 骑宠 mount clears it. A bot has no
 *       client to run either rule, so {@link #cancelHidesForAction} runs at every swing site and
 *       the movement tick retires it while a mount is up.</li>
 *   <li><b>Pirate 变身 morphs (TRANSFORMATION 5111005 / SUPER_TRANSFORMATION 5121003 / the Cygnus
 *       TRANSFORMATION).</b> Attackable attack-enabler morphs: while the morph is up the player may
 *       use the skills that REQUIRE it (Shockwave / Demolition / Dragon Strike), and the morph's own
 *       pose forbids everything else that owns the body — a 骑宠 mount and 疾驰. Each actual show
 *       (the buff sweep's re-cast, a GM cast) arms the expiry clock via {@link #onAuraShown}, and
 *       {@link #tickMovement} enforces the exclusions while it holds. On expiry the MORPH aura's
 *       cancel goes out the way the host does it and the mount / 疾驰 are free to return; the attack
 *       driver gates the morph-gated skills on {@link #isMorphedAs} meanwhile.</li>
 *   <li><b>Corsair 海盗船 (BATTLE_SHIP, 5221006).</b> The gunner's attack-enabler: Battleship
 *       Cannon / Torpedo are illegal off the ship, and the ship's pose forbids the 骑宠 mount and
 *       疾驰 exactly like a morph. The host registers it as a MONSTER_RIDING buff whose riding item
 *       is forced to the Battleship (1932000), but the plugin only broadcasts the observer frame
 *       ({@code showMonsterRiding}) - the buff itself is never registered, so the 骑宠 mount system
 *       cannot see it and the bookkeeping here ({@link #isMorphedAs}) is the sole source of truth.
 *       Expiry sends the MONSTER_RIDING-bit foreign-buff cancel so every observer's ship model is
 *       cleared with the body swap; the 骑宠 mount and 疾驰 are free to return.</li>
 *   <li><b>Thief 隐身术 (DARK_SIGHT, 4001003 / 14001003).</b> The rogue hide: the official client
 *       renders it as a semi-transparent shade that players STILL SEE (unlike GM hide, the sprite
 *       stays on the map), and while it holds the character cannot be attacked by monsters at all.
 *       The same two cancel rules apply — an attack drops it (the host's own damage handlers cancel
 *       the DARKSIGHT statup on any swing) and a mount clears it — so it shares the 橡木伪装 rules
 *       and the two together form the hide family this class tracks. While a hide aura is up, the
 *       contact-damage layer consults {@link #isMonsterImmune} and skips the bot entirely: no touch
 *       hit, no mob debuff, no fall damage, no touch retaliation.</li>
 * </ul>
 *
 * <p>The 变身 (TRANSFORMATION / SUPER_TRANSFORMATION) morphs are ATTACKABLE, so - unlike the hides -
 * an attack must NOT cancel them. Tracking is therefore keyed on which MORPH skill is currently
 * shown: only when that is 橡木伪装 does {@link #cancelHidesForAction} fire.</p>
 *
 * <p>A bot's auras are broadcast only - no host effect is registered - so the cancel is the matching
 * foreign-buff cancel frame, and "is it up" lives here. Keyed by character id; released on despawn via
 * {@link #clearBot}. Fields are concurrent because the aura broadcast runs on the bot's macro thread
 * while the movement tick runs on the movement thread.</p>
 */
public final class BotAuraState {

    /** The wire statups of a 疾驰 aura, in the host's own DASH2+DASH order (the cancel mask needs both). */
    private static final List<BuffStat> DASH_STATS = List.of(BuffStat.DASH2, BuffStat.DASH);
    /** The wire statup of any skill morph (MORPH). */
    private static final List<BuffStat> MORPH_STATS = List.of(BuffStat.MORPH);
    /** The wire statup of a 隐身术 aura's CANCEL frame (the show frame carries the same DARKSIGHT bit). */
    private static final List<BuffStat> DARK_SIGHT_STATS = List.of(BuffStat.DARKSIGHT);
    /** The cancel mask of a 海盗船 aura (its observer frame is the MONSTER_RIDING mount frame). */
    private static final List<BuffStat> RIDING_STATS = List.of(BuffStat.MONSTER_RIDING);

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
    /** botIds whose 隐身术 aura is intended to be up (a cancel is only sent on the drop edge). */
    private static final Set<Integer> DARK_SIGHT_UP = ConcurrentHashMap.newKeySet();

    /** Expiry fallback for an attack-enabler aura whose WZ duration is missing (the WZ minimum is ~30s). */
    private static final long ENABLER_FALLBACK_MS = 80_000L;

    /**
     * botId -> epoch-ms at which the bot's attack-enabler aura (变身 morph / 海盗船) expires. These
     * are the auras whose presence GATES attacks (Shockwave / Demolition / the Battleship guns) and
     * whose pose excludes the 骑宠 mount and 疾驰; see {@link #isMorphed}.
     */
    private static final Map<Integer, Long> ATTACK_ENABLER_UNTIL = new ConcurrentHashMap<>();

    private BotAuraState() {}

    /**
     * The enabler aura {@code bot} needs for {@code attackSkillId}, or 0 if that skill has no
     * enabler. A bot's kit can only carry the enablers of its own lineage, so if the mapped skill
     * is not in the bot's buff registry the skill is simply not fireable for this bot.
     */
    public static int enablerSkillFor(Character bot, int attackSkillId) {
        int wanted = enablerFor(attackSkillId);
        if (wanted == 0 || bot == null) {
            return 0;
        }
        return BotBuffConfig.buffsForJob(bot.getJob()).contains(wanted) ? wanted : 0;
    }

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
     * 隐身术 (4001003 / 14001003) - the rogue-line hide. Mirrors the host's own {@code isDs()} id
     * set: while it holds the character is semi-transparent to players and unattackable by monsters.
     */
    static boolean isDarkSight(int skillId) {
        return skillId == Rogue.DARK_SIGHT || skillId == NightWalker.DARK_SIGHT;
    }

    /**
     * The hide family - 橡木伪装 plus 隐身术. These are the two auras an attack and a mount both
     * cancel, and the two that make the bot unattackable while shown ({@link #isMonsterImmune}).
     */
    static boolean isHide(int skillId) {
        return isDisguise(skillId) || isDarkSight(skillId);
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
     * The walk-stance rule, as a pure seam: the WALK stance is exactly the grounded-and-moving
     * pose, so standing (STAND), a jump (JUMP), a swim (SWIM) or a rope/ladder (ROPE/LADDER) all
     * render a different stance. Kept as the classification of "is this stance a walk" — the
     * 疾驰 aura itself now keys on the BotDashBurst buff window, not the stance.
     */
    static boolean dashHolds(int stance) {
        return CharacterStance.isWalking(stance);
    }

    /** The attack-enabler family: the skills whose pose excludes a 骑宠 mount and 疾驰. */
    public static boolean isAttackEnabler(int skillId) {
        return isTransformMorph(skillId) || skillId == Corsair.BATTLE_SHIP;
    }

    /** The attack skills that REQUIRE the attack-enabler aura (the guns, Shockwave, Demolition, ...). */
    public static boolean isAttackEnablerSkill(int skillId) {
        return enablerFor(skillId) != 0;
    }

    /**
     * The attack-enabler aura that gates {@code attackSkillId}, or 0 if it is aura-free. The WZ
     * skill texts name these outright: Shockwave (碎石乱击) "Requires Transformation or Super
     * Transformation"; Demolition (金手指) / Snatch "Can only be used during Super Transformation";
     * the ship guns (急速射 5221007 / 重量炮击 5221008) "Can only be used aboard Battleship";
     * Dragon Strike's (潜龙出渊) dragon-rise pose only exists on the transformed body. Barrage
     * (光速拳, the single slot) is deliberately NOT gated: its desc carries no transform clause,
     * the normal body 00002000 ships the {@code fist} keyframe so the swing renders untransformed,
     * and Super Transformation's 120 s duration / 430 s cooldown would otherwise leave a 4th-job
     * brawler without a single usable attack for ~72% of the time. One place, so a driver fallback
     * and any show always agree on which aura a skill belongs to.
     */
    public static int enablerFor(int attackSkillId) {
        return switch (attackSkillId) {
            case Marauder.SHOCKWAVE -> Marauder.TRANSFORMATION;
            case ThunderBreaker.SHOCK_WAVE -> ThunderBreaker.TRANSFORMATION;
            case Buccaneer.DEMOLITION, Buccaneer.DRAGON_STRIKE -> Buccaneer.SUPER_TRANSFORMATION;
            case Corsair.BATTLESHIP_CANNON, Corsair.BATTLESHIP_TORPEDO -> Corsair.BATTLE_SHIP;
            default -> 0;
        };
    }

    /**
     * True while the bot's attack-enabler aura (变身 morph, or the gunner's 海盗船) is shown. While
     * it holds, {@link BotMount} refuses to mount the bot and the movement tick keeps 疾驰 down;
     * the attack driver only fires the skills that require it once this is true.
     */
    public static boolean isMorphed(Character bot) {
        if (bot == null) {
            return false;
        }
        Long until = ATTACK_ENABLER_UNTIL.get(bot.getId());
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * True while {@code bot} is morphed AS {@code enablerSkillId} specifically (变身 5111005 vs
     * 超级变身 5121003 vs 海盗船 5221006 gate different skills). The attack gate: a Demolition is
     * not covered by a plain 变身 any more than an untransformed swing is.
     */
    public static boolean isMorphedAs(Character bot, int enablerSkillId) {
        if (bot == null || enablerSkillId == 0) {
            return false;
        }
        Long until = ATTACK_ENABLER_UNTIL.get(bot.getId());
        return until != null && System.currentTimeMillis() < until
                && MORPH_SKILL.get(bot.getId()) == enablerSkillId;
    }

    /**
     * Record that {@code bot}'s aura for {@code skillId} was just broadcast (a macro cast or a GM /
     * party-buff show). 疾驰 is included so a stray cast from a stand is retired on the next movement
     * tick; the walking display itself is governed by {@link #tickMovement}. The hide auras are noted
     * so an attack / mount can cancel them (a transform supersedes an earlier disguise).
     */
    public static void onAuraShown(Character bot, int skillId) {
        if (bot == null) {
            return;
        }
        int id = bot.getId();
        if (isDash(skillId)) {
            DASH_SKILL.putIfAbsent(id, skillId);
            DASH_UP.add(id);
            // A GM / party-buff show carries the real burst too, from the caller's thread.
            BotDashBurst.startBurst(bot, skillId);
        } else if (isAttackEnabler(skillId)) {
            // An attack-enabler morph: note which MORPH visual is up (so an expiry swap can name the
            // exact cancel frame) and when it expires. A later enabler overwrites the earlier one -
            // the swap below broadcasts that cancellation - exactly how the client's single MORPH
            // stat slot behaves.
            MORPH_SKILL.put(id, skillId);
            int durationMs = BotBuffEffects.durationOf(skillId);
            long life = durationMs > 0 ? (long) (durationMs * 0.9) : ENABLER_FALLBACK_MS;
            ATTACK_ENABLER_UNTIL.put(id, System.currentTimeMillis() + life);
        } else if (isDisguise(skillId)) {
            MORPH_SKILL.put(id, skillId);
        } else if (isDarkSight(skillId)) {
            DARK_SIGHT_UP.add(id);
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
        boolean hasHide = MORPH_SKILL.containsKey(id) || DARK_SIGHT_UP.contains(id);
        if (dashSkill == 0 && !hasHide) {
            return; // no state-bound aura for this bot (the overwhelming majority)
        }
        boolean observed = GCMovement.isMapObserved(bot.getMapId());
        boolean mounted = bot.getBuffedValue(BuffStat.MONSTER_RIDING) != null;

        // 变身 / 海盗船: the attack-enabler pose owns the body and its expiry is enforced here.
        // On expiry the visuals swap off the way the host's own cancel does: a 变身 morph's foreign
        // representation is the MORPH statup, so its cancel is the MORPH-cancel frame; the 海盗船
        // was shown with the MONSTER_RIDING mount frame (showMonsterRiding), so its expiry needs
        // that bit cleared too or every observer keeps rendering the ship forever.
        Long enablerUntil = ATTACK_ENABLER_UNTIL.remove(id);
        if (enablerUntil != null) {
            if (System.currentTimeMillis() < enablerUntil) {
                ATTACK_ENABLER_UNTIL.put(id, enablerUntil); // still up - put it back for the next tick
            } else {
                Integer shown = MORPH_SKILL.get(id);
                MORPH_SKILL.remove(id);
                if (shown != null && shown == Corsair.BATTLE_SHIP) {
                    cancel(bot, RIDING_STATS);
                } else if (shown != null) {
                    cancel(bot, MORPH_STATS);
                }
            }
        }

        // 疾驰: the aura is the visible side of the BotDashBurst buff — it shows whenever the
        // burst is live and the bot is not mounted, and is cancelled the moment the burst is
        // gone (expiry, or the bot STOPPED moving — BotDashBurst releases it on the stop edge,
        // one tick before this reads it). While bursting it is (re)shown, throttled to the
        // aura's own refresh window; the refresh clock only advances on an actual show, so a
        // burst that begins while unobserved shows on the first observed tick. A live 变身 /
        // 海盗船 never reaches the show branch — its pose gate in BotDashBurst refuses new
        // rolls, and an enabler granted BEFORE a burst expires by its own clock below without
        // tearing the burst down (the host's buff slots are independent).
        if (dashSkill != 0) {
            boolean poseOwned = mounted || isMorphed(bot);
            if (!poseOwned && BotDashBurst.isActive(bot)) {
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

        // 伪装 / 隐身: a 骑宠 (mount) clears either hide, exactly as dismounting for an action does.
        // An attack-enabler morph / the Battleship is the same in reverse - the pose owns the body -
        // but it expires by its own clock above and the mount system never mounts a morphed bot
        // (BotMount.tick), so no teardown is needed here.
        if (mounted) {
            if (isDisguised(id)) {
                MORPH_SKILL.remove(id);
                cancel(bot, MORPH_STATS);
            }
            if (DARK_SIGHT_UP.remove(id)) {
                cancel(bot, DARK_SIGHT_STATS);
            }
        }
    }

    /**
     * Cancel every hide aura for an action that officially breaks it: any attack or attack skill (the
     * client drops 橡木伪装 the moment the attack key is pressed, and the host's damage handlers drop
     * 隐身术 on any swing). Called alongside the mount's own {@code cancelForAction} at every swing
     * site. No-op unless a hide is currently shown, so an attackable 变身 is left alone.
     */
    public static void cancelHidesForAction(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        int id = bot.getId();
        if (isDisguised(id)) {
            MORPH_SKILL.remove(id);
            cancel(bot, MORPH_STATS);
        }
        if (DARK_SIGHT_UP.remove(id)) {
            cancel(bot, DARK_SIGHT_STATS);
        }
    }

    /**
     * True while a hide aura (橡木伪装 / 隐身术) is shown on this bot: monsters cannot recognise it,
     * so it cannot be attacked. The contact-damage layer (BotContactDamage) consults this and skips
     * the bot entirely - no touch hit, no mob debuff, no fall damage, no touch retaliation. The
     * attackable 变身 morphs do NOT count: they never protect the bot.
     */
    public static boolean isMonsterImmune(Character bot) {
        if (bot == null) {
            return false;
        }
        int id = bot.getId();
        return DARK_SIGHT_UP.contains(id) || isDisguised(id);
    }

    /** This bot's 疾驰 skill id (0 = none), resolved once from its job's buff registry and cached. */
    static int dashSkillFor(Character bot) {
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
        DARK_SIGHT_UP.remove(botId);
        ATTACK_ENABLER_UNTIL.remove(botId);
        BotDashBurst.clearBot(botId);
    }
}
