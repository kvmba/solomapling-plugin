package soloMapling.ArtificialPlayer.BotHealthSystem;

import org.gms.client.Character;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.BotClientBinding;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.companion.CompanionRoster;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a template-cloned bot drinking a potion: while its HP is below full, it
 * recovers a chunk of its max HP at a random interval.
 *
 * <p>Why it exists: {@code BotContactDamage} drains real HP from every artificial
 * player, but only persistent companions have a survival loop
 * ({@code CompanionSurvivalController} - potions, shop runs). Template-cloned bots
 * (TrainingBot, FollowerBot, …) are ephemeral decoration with no inventory and no
 * restocking, so their HP only ever goes down and pins at the 1-HP floor forever.
 *
 * <p>Deliberately not a real potion: these bots carry no USE items, are wiped on
 * restart, and must not touch the economy. This reproduces the player-visible
 * effect (HP ticks back up in steps, on the item-effect packet) without any item.
 *
 * <p>Companions are excluded - they already run the real survival controller, and
 * healing them twice would fight over the same HP.
 */
public final class BotPotionSim {

    /** Fraction of max HP restored per sip (tops back up to full over a few sips). */
    private static final double HEAL_RATIO = 0.30;
    // Sip cadence scales with how hurt the bot is. A badly hurt bot drinks at the
    // urgent pace; a bot that has only lost a sliver slows right down, so the recovery
    // reads as "drinks when it needs to" rather than a metronome ticking to full HP.
    /** At or below this HP fraction the bot drinks at the urgent pace. */
    private static final double URGENT_HP_RATIO = 0.30;
    private static final long URGENT_MIN_GAP_MS = 3_000L;
    private static final long URGENT_MAX_GAP_MS = 8_000L;
    /** Approaching full HP the bot slows to this pace. */
    private static final long CALM_MIN_GAP_MS = 12_000L;
    private static final long CALM_MAX_GAP_MS = 25_000L;
    /** Item-effect packet payload for the "drank a potion" animation. Must be an HP
     *  potion so the effect matches the rising HP bar: 2000000 is the Red Potion
     *  (Item.wz spec: hp +50). The MP potions (2000003 Blue Potion / 2000006 Mana
     *  Elixir) restore MP and would look wrong over a healing HP bar. */
    private static final int POTION_EFFECT_ITEM = 2000000; // Red Potion

    /** Base max HP at level 1 (matches the host's Character.getDefault). */
    private static final int BASE_MAX_HP = 50;
    /** Max HP gained per level. Sits inside the host's levelUp() roll band
     *  (~10-14 mage, ~20-28 warrior/bowman/thief, ~12-16 beginner). */
    private static final int MAX_HP_PER_LEVEL = 22;
    /** The host clamps maxHp to 30000 in reapplyLocalStats - don't exceed it. */
    private static final int MAX_HP_CAP = 30_000;

    private long nextSipAtMs = 0L;
    private int lastMaxHpForLevel = -1;    // level whose max-HP target we already applied

    /** Call once per macro tick. Throttled internally; cheap when healthy. */
    public void tick(Character bot) {
        if (!appliesTo(bot)) {
            return;
        }
        ensureMaxHp(bot);
        int maxHp = bot.getCurrentMaxHp();
        if (maxHp <= 0 || bot.getHp() >= maxHp) {
            return; // already full - nothing to do
        }
        long now = System.currentTimeMillis();
        if (now < nextSipAtMs) {
            return;
        }
        // Re-arm first: a bot that stays hurt keeps sipping at a fresh random cadence.
        nextSipAtMs = now + sipGapMs(bot.getHp(), maxHp);

        int healed = Math.min(maxHp, bot.getHp() + (int) (maxHp * HEAL_RATIO));
        BotClientBinding.runWithBoundPlayer(bot, () -> bot.updateHp(healed));
        // No updatePartyMemberHP() here: updateHp goes through setHp -> dispatchHpChanged ->
        // hpChangeAction, which already registers updatePartyMemberHP() on the map's
        // stat-update queue. Calling it again sends a second, out-of-order party HP packet.
        //
        // The host only renders the item effect on a real item use, so send it here to make
        // the recovery read as a potion rather than an invisible HP tick. Gated on the map
        // being observed: MapleMap.broadcastMessage skips artificial characters itself, but
        // early-returning avoids building the packet at all when nobody can see it.
        if (GCMovement.isMapObserved(bot.getMapId())) {
            bot.getMap().broadcastMessage(bot,
                    PacketCreator.itemEffect(bot.getId(), POTION_EFFECT_ITEM), false);
        }
    }

    /**
     * The random gap before the next sip, interpolated between the urgent and calm
     * paces by how hurt the bot is: at or below {@link #URGENT_HP_RATIO} it drinks at
     * the urgent pace, and the closer it gets to full the slower it goes. Keeps the
     * cadence honest at the bottom end (a near-dead bot should not slow down) and
     * unhurried at the top (topping off the last sliver shouldn't look mechanical).
     */
    private static long sipGapMs(int hp, int maxHp) {
        double filled = maxHp > 0 ? (double) Math.max(0, hp) / maxHp : 1.0;
        double span = 1.0 - URGENT_HP_RATIO;
        // 0 at/below the urgent threshold, 1 at full HP.
        double t = span > 0 ? Math.max(0.0, Math.min(1.0, (filled - URGENT_HP_RATIO) / span)) : 1.0;
        long minGap = Math.round(URGENT_MIN_GAP_MS + (CALM_MIN_GAP_MS - URGENT_MIN_GAP_MS) * t);
        long maxGap = Math.round(URGENT_MAX_GAP_MS + (CALM_MAX_GAP_MS - URGENT_MAX_GAP_MS) * t);
        if (maxGap <= minGap) {
            return minGap;
        }
        return minGap + ThreadLocalRandom.current().nextLong(maxGap - minGap);
    }

    /**
     * Raises max HP to match the bot's level. Template clones are levelled by assigning
     * {@code Character.setLevel} directly (TrainingBot.accrueAbstractExp), which bypasses
     * the host's {@code levelUp()} and so never runs {@code addMaxMPMaxHP} - a bot that
     * ground from 1 to 70 still carries the template's level-1 pool and gets floored by a
     * single contact hit.
     *
     * <p>Grows only: a real pool is also raised by gear and Hyper Body, and clamping it
     * back down would undo those. Memoized on the level, so an already-correct bot costs
     * one int compare per tick instead of re-locking the character every tick.
     */
    private void ensureMaxHp(Character bot) {
        int level = bot.getLevel();
        if (level == lastMaxHpForLevel) {
            return;
        }
        lastMaxHpForLevel = level;
        int target = Math.min(MAX_HP_CAP,
                BASE_MAX_HP + Math.max(0, level - 1) * MAX_HP_PER_LEVEL);
        if (target <= bot.getCurrentMaxHp()) {
            return;
        }
        BotClientBinding.runWithBoundPlayer(bot, () -> bot.updateMaxHp(target));
    }

    /** True for artificial players that have no survival loop of their own. */
    private static boolean appliesTo(Character bot) {
        return bot != null && bot.getMap() != null && bot.isAlive()
                && BotHelpers.isBot(bot)
                && !CompanionRoster.isCompanion(bot.getId());
    }
}
