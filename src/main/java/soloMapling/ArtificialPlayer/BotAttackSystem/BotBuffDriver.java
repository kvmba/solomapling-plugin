package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.constants.skills.Crusader;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.MethodScheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Bot buff maintenance. For each of the bot's class buffs whose recast timer has elapsed,
 * broadcasts the buff visual via BotBuffEffects - no stats, MP, or registered effect, just
 * the packets that make the buff look like it happened. Recast cadence is 90% of the buff's
 * WZ duration; timer-gated, so most ticks cast nothing. Call clearBot when a bot despawns.
 */
// Bot-buff maintenance loop; cosmetic reimplementation inspired by GreenCatMS. Credit: NutNNut for the idea.
public final class BotBuffDriver {

    // botId -> (skillId -> recast epoch-ms; MAX = pending cast, MIN = pending silent aura)
    private static final Map<Integer, Map<Integer, Long>> nextCastByBot = new ConcurrentHashMap<>();

    // Recast cadence for a buff whose WZ duration is missing / non-positive.
    private static final long FALLBACK_RECAST_MS = 60_000L;

    // Gap between consecutive buffs so a multi-buff bot casts them one at a time
    // (in order) rather than all at once.
    private static final long STAGGER_MS = GCMovement.ATTACK_ACTION_LOCK_MS;
    private static final long RETRY_MS = 250L;

    private BotBuffDriver() {}

    /*
     * Maintain the bot's class buffs (the botBuff() entry point). Timer-gated
     * and cheap; safe to call every tick for every bot.
     */
    public static void botBuff(Character bot) {
        castDueBuffs(bot, false, false);
    }

    /*
     * Force every configured class buff to show immediately, ignoring timers.
     * Backs the !bot buff <id> GM test command. Returns the number of
     * buffs broadcast (0 means this bot's job has no configured buffs).
     */
    public static int forceBuff(Character bot) {
        return castDueBuffs(bot, true, false);
    }

    /*
     * As {@link #forceBuff(Character)} but silent: only the persistent aura is shown, never the
     * cast animation. Used for a bot's on-arrival re-show, so a player walking in sees bots that
     * already look buffed instead of every bot on the map casting at them at once.
     */
    public static int forceBuff(Character bot, boolean silent) {
        return castDueBuffs(bot, true, silent);
    }

    /* Release a despawned bot's recast timers so the map doesn't grow unbounded. */
    public static void clearBot(int botId) {
        nextCastByBot.remove(botId);
    }

    static boolean hasPendingCast(int botId) {
        Map<Integer, Long> timers = nextCastByBot.get(botId);
        return timers != null && timers.containsValue(Long.MAX_VALUE);
    }

    /*
     * Show one specific buff skill on the bot now, bypassing the per-job config.
     * Backs !bot castbuff <id> <skillId> - fire e.g. 4101004
     * (Assassin Haste) on any bot to confirm the visual pipe independent of the
     * job lookup. Returns true if the visual was broadcast.
     */
    public static boolean castSkill(Character bot, int skillId) {
        if (bot == null || bot.getMap() == null) return false;
        return BotBuffEffects.showBuff(bot, skillId) >= 0;
    }

    private static int castDueBuffs(Character bot, boolean force, boolean silent) {
        if (bot == null || bot.getMap() == null) return 0;

        List<Integer> buffIds = BotBuffConfig.buffsForJob(bot.getJob());
        if (buffIds.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        MapleMap map = bot.getMap();
        Map<Integer, Long> timers = nextCastByBot.computeIfAbsent(bot.getId(), k -> new ConcurrentHashMap<>());
        long pending = silent ? Long.MIN_VALUE : Long.MAX_VALUE;

        int cast = 0;
        for (int skillId : buffIds) {
            if (skillId == Crusader.COMBO) {
                // The combo ring is BotComboOrb's exclusive wire format - its broadcasts carry the
                // LIVE orb count, while this sweep's generic aura frame would carry the WZ statup,
                // which the host pins at 1 (StatEffect's COMBO case). Re-showing 斗气集中 here would
                // slam an observer's ring back to 1 every recast, so the sweep must never touch it.
                continue;
            }
            if (BotAuraState.isDash(skillId) || BotAuraState.isHide(skillId)) {
                // 疾驰 is state-bound (only valid while WALKING) and the movement tick shows/cancels it
                // for the bot, so the periodic sweep must never fire it - a cast from a stand would
                // otherwise linger until the next movement tick retired it.
                //
                // The hide family (橡木伪装 / 隐身术) is state-bound the same way and is owned by
                // BotAuraState: the aura only belongs on the bot while the bot is NOT acting. This
                // sweep fires while the bot is mid-combat, so a barrel / Dark Sight re-shown here is
                // immediately torn off by the next swing's cancelHidesForAction - the bot visibly
                // fights under the disguise. An observed-map arrival also runs this sweep silently
                // (the "already buffed" re-show), painting the disguise onto a bot that is about to
                // attack. State-bound auras are never re-cast from here; GM casts via castSkill
                // still reach them.
                continue;
            }
            // The attack enablers (变身 morphs / 海盗船) ride the normal cadence below: every
            // actual show re-arms their BotAuraState expiry clock via onAuraShown - the same
            // "top up the transformation" beat a real player does - and the sweep also serves the
            // silent on-arrival re-show for them.
            Long previous = timers.get(skillId);
            if (previous != null && (previous == Long.MAX_VALUE || previous == Long.MIN_VALUE
                    || (!force && now < previous))) {
                continue; // not due, or this cast is already pending (even for forced sweeps)
            }
            if (previous == null ? timers.putIfAbsent(skillId, pending) != null
                    : !timers.replace(skillId, previous, pending)) continue;
            final int sid = skillId;
            try {
                MethodScheduler.runAfterDelay(() -> castPending(bot, map, timers, sid, silent), cast * STAGGER_MS);
            } catch (RuntimeException | Error e) {
                timers.remove(skillId, pending);
                throw e;
            }
            cast++;
        }
        return cast;
    }

    private static void castPending(Character bot, MapleMap map, Map<Integer, Long> timers,
                                    int skillId, boolean silent) {
        if (nextCastByBot.get(bot.getId()) != timers) return; // removed/replaced bot episode
        long pending = silent ? Long.MIN_VALUE : Long.MAX_VALUE;
        if (bot.getMap() != map || bot.getHp() <= 0) {
            timers.remove(skillId, pending);
            return;
        }
        try {
            int durationMs = BotBuffEffects.castBuff(bot, skillId, silent);
            if (durationMs < 0) {
                MethodScheduler.runAfterDelay(() -> castPending(bot, map, timers, skillId, silent), RETRY_MS);
                return;
            }
            // Stamp only a completed cast. Keep the ship's level-scaled recast, not its ~24-day WZ ride.
            durationMs = BotBuffEffects.durationOf(skillId, bot);
            long delay = durationMs > 0 ? (long) (durationMs * 0.9) : FALLBACK_RECAST_MS;
            timers.replace(skillId, pending, System.currentTimeMillis() + delay);
        } catch (RuntimeException | Error e) {
            timers.remove(skillId, pending);
            throw e; // scheduler logs the failure; a future sweep may retry it
        }
    }
}
