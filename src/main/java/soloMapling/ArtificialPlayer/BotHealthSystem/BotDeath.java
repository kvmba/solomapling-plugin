package soloMapling.ArtificialPlayer.BotHealthSystem;

import org.gms.client.Character;
import org.gms.constants.id.MapId;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotClientBinding;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.companion.CompanionRoster;

import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botCancelChair;

/**
 * The death state every artificial player obeys: lie down, complain, then go home.
 *
 * <p>Why it exists: contact damage drains real HP, so a bot really can reach 0. What happens
 * next used to be nobody's job — the bot kept evaluating its own FSM while dead, walking
 * around at null HP. This owns the whole episode.
 *
 * <p>Design boundaries, deliberately narrow:
 * <ul>
 *   <li><b>Injury is not our business.</b> {@code BotContactDamage} decides when a hit is
 *       lethal and calls {@link #kill}. We never look at HP ourselves to discover death, so
 *       there is exactly one way to die: taking a hit you cannot survive.</li>
 *   <li><b>"Alright again" has exactly one meaning.</b> A bot that reached a different map is
 *       alive and well — the transport put it somewhere and topped it up. We do not inspect
 *       the destination's monster list and re-kill it: it is standing there with full HP, and
 *       if something there kills it, that is again {@code BotContactDamage}'s call.</li>
 *   <li><b>We never navigate.</b> Nothing here plans a route, walks, or rides. Returning home
 *       is one map change.</li>
 * </ul>
 *
 * <p>The grumbling is throttled and only rendered when a real player shares the map; the timer
 * itself advances whether or not anyone is watching, so a death in an empty field still ends.
 */
public final class BotDeath {

    private static final String DIALOGUE_PATH = "BotDeathDialogue.yaml";
    private static final String FIELD_NODE = "FieldDeath";
    private static final String TOWN_NODE = "TownDeath";

    // Lie here before being carried home: long enough to read as a corpse rather than a
    // stumble, varied so a field of bodies does not all stand up on the same tick.
    static final long DOWN_MIN_MS = 30_000L;
    static final long DOWN_MAX_MS = 120_000L;

    // Grumble cadence while down.
    static final long GRUMBLE_MIN_MS = 8_000L;
    static final long GRUMBLE_MAX_MS = 15_000L;

    // Fail-safe: nothing should keep a bot dead this long. If it does something is wedged, and
    // standing up is better than a permanent corpse.
    static final long DOWN_MAX_HARD_MS = 5 * 60_000L;

    private final Character chr;
    // Volatile, and always written LAST (see markDown): the movement thread ends the bot while
    // the macro thread runs the episode, so this is the hand-off between them — reading it true
    // must also mean the timers below are already visible.
    private volatile boolean down;
    private long passOutAtMs;      // start of the whole episode (fail-safe clock)
    private long standUpAtMs;
    private long nextGrumbleAtMs;

    public BotDeath(Character chr) {
        this.chr = chr;
    }

    /** True while this bot is dead: it must not act, move, or be steered normally. */
    public boolean isDead() {
        return down;
    }

    /**
     * Marks this bot as having been killed. Called only from the damage layer.
     *
     * <p>Stopping the movement is done here rather than left to {@code GCMovementDriver}
     * because a driver only exists for a bot that has movement enabled, and the driver's job is
     * to move — not to notice it should not.
     */
    public void kill() {
        Character chr = this.chr;
        if (chr == null || !BotHelpers.isBot(chr)) {
            return; // only artificial characters have this lifecycle
        }
        // Persistent companions are excluded: they are real characters with a real inventory,
        // saved progress, and their own survival loop (potions, supply runs, a controller that
        // already stops when isAlive() is false). Knocking one over here would cost its owner
        // experience and leave it lying where a player might be relying on it. They keep the
        // 5% floor and simply never die from contact.
        if (CompanionRoster.isCompanion(chr.getId())) {
            return;
        }
        if (down) {
            return; // already dead — a second killing blow must not restart the wait
        }
        // Zero first, so the world sees a corpse on the tick this happens. Everything below is
        // inert by comparison and can afford to fail.
        BotClientBinding.runWithBoundPlayer(chr, () -> chr.updateHp(0));
        chr.updatePartyMemberHP(); // headless bots never receive their own stat packet
        markDown();
    }

    private void markDown() {
        long now = System.currentTimeMillis();
        // Every timestamp first, then the flag: 'down' is what the macro thread reads, and it
        // must never observe a death whose timers are still the previous episode's.
        passOutAtMs = now;
        standUpAtMs = now + DOWN_MIN_MS + ThreadLocalRandom.current().nextLong(DOWN_MAX_MS - DOWN_MIN_MS);
        nextGrumbleAtMs = now; // let it complain right away
        down = true;
        stillTheCorpse(chr);
    }

    /** Stand up, reset everything, and hand control back to this bot's own behaviour. */
    private void clearDown() {
        passOutAtMs = 0L;
        standUpAtMs = 0L;
        nextGrumbleAtMs = 0L;
        down = false; // last, for the same reason as above
    }

    /**
     * Advances the death one tick. Called from the bot's own FSM tick; while it returns true
     * every other behaviour is skipped.
     *
     * @return true while still dead (the caller must run nothing else this tick)
     */
    public boolean tick() {
        if (!down) {
            return false;
        }
        Character chr = this.chr;
        if (chr == null || chr.getMap() == null) {
            clearDown();
            return false;
        }

        // Fail-safe: however the timer got missed, do not leave a permanent corpse.
        if (System.currentTimeMillis() - passOutAtMs > DOWN_MAX_HARD_MS) {
            carryHome();
            return false;
        }

        grumble(chr);

        if (System.currentTimeMillis() < standUpAtMs) {
            return true; // still lying down
        }
        carryHome();
        return false;
    }

    /** Say one line if it is time and somebody can hear it. */
    private void grumble(Character chr) {
        long now = System.currentTimeMillis();
        if (now < nextGrumbleAtMs) {
            return;
        }
        nextGrumbleAtMs = now + GRUMBLE_MIN_MS
                + ThreadLocalRandom.current().nextLong(GRUMBLE_MAX_MS - GRUMBLE_MIN_MS);
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            return; // nobody watching — advance the clock without paying for a line
        }
        String line = BotDialogueHandler.getRandomResolvedLine(DIALOGUE_PATH, "BotDeath", node(), chr, null);
        if (line == null || line.isBlank()) {
            return;
        }
        SocialCommands.BotSpeak(chr, line);
    }

    /**
     * Which pool the grumbling comes from: cursing the wildlife, or cursing whoever let the
     * wildlife in. Decided from static spawn data, so a town is "a map that is not supposed to
     * have monsters" rather than a guess based on who happens to be standing there.
     */
    private String node() {
        boolean wasSupposedToHaveMonsters = chr.getMap() != null
                && MapMobIndex.level(chr.getMapId()) >= 0;
        return wasSupposedToHaveMonsters ? FIELD_NODE : TOWN_NODE;
    }

    /**
     * Patches the bot up and sets it down in its sanctuary — always in that order, so it never
     * lands somewhere hostile with an empty HP bar.
     *
     * <p>A sanctuary that is the very map it died on (or no sanctuary at all) simply leaves the
     * bot standing where it fell.
     */
    private void carryHome() {
        Character chr = this.chr;
        if (chr == null || chr.getMap() == null) {
            clearDown();
            return;
        }
        // Standing it up first means the change-of-map below moves a living character, so no
        // client is ever told that somebody arrived at the sanctuary dead.
        int full = Math.max(1, chr.getCurrentMaxHp());
        BotClientBinding.runWithBoundPlayer(chr, () -> chr.updateHp(full));
        chr.updatePartyMemberHP();
        int homeId = sanctuaryOf(chr);
        if (homeId > 0 && homeId != chr.getMapId()) {
            BotClientBinding.runWithBoundPlayer(chr, () -> chr.changeMap(homeId));
        }
        clearDown();
    }

    /**
     * How the bot's own tick should be paced while it is dead.
     *
     * <p>Why this is not simply left alone: a grinder's unobserved cadence is 4-8 minutes, and
     * the whole point of that stretch is that nothing time-critical happens while nobody is
     * watching. A death is nothing but a timer, so at the stretched cadence a bot in an empty
     * field would lie there for a quarter of an hour instead of the intended half-minute to two
     * minutes. Living bots keep their cadence; only the corpse is paced by its own clock.
     */
    public long tickDelayMs() {
        return delayForRemaining(standUpAtMs - System.currentTimeMillis());
    }

    /**
     * Pure form of {@link #tickDelayMs}, so the pacing can be tested without waiting.
     *
     * <p>Floored at a second so a bot standing up does not spin, and never longer than the time
     * actually left — a delay past that would add a whole extra wait to every death.
     */
    static long delayForRemaining(long remainingMs) {
        if (remainingMs < TICK_FLOOR_MS) {
            return TICK_FLOOR_MS;
        }
        return Math.min(remainingMs, TICK_CEILING_MS);
    }

    static final long TICK_FLOOR_MS = 1_000L;
    // Wake a few times while lying down: often enough that the wait lands near the intended
    // moment rather than up to a whole extra interval late.
    static final long TICK_CEILING_MS = 15_000L;

    /** The sanctuary this map points at, or 0 when it has none. */
    static int sanctuaryOf(Character chr) {
        int homeId = chr.getMap().getReturnMapId();
        return homeId != MapId.NONE ? homeId : 0;
    }

    /**
     * Stop everything that would keep moving a corpse.
     *
     * <p>The chair matters most: a seated bot is healed by a host timer that knows nothing
     * about death, so a bot killed mid-sit would stand itself back up out of the chair's own
     * recovery task. Cancelling the movement cancels the wander with it.
     */
    private void stillTheCorpse(Character chr) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        try {
            if (chr.getChair() > 0) {
                botCancelChair(chr);
            }
            // A rope rest hold would outlive the death: it is only cleared when a break ends,
            // and a break does not end — the bot died hanging there. Left set, the movement
            // driver freezes the bot on the rope forever once it is on its feet again.
            GCMovement.setRestHold(chr, false);
            GCMovement.stop(chr);
        } catch (RuntimeException ignored) {
            // one overdue clean-up must not cost us the death handling
        }
    }

    /**
     * Puts this bot back on its feet at once, skipping the rest of the episode.
     *
     * <p>Used when the bot is being handed to a different behaviour (a retype). The outgoing
     * {@code BotDeath} is discarded with its bot — a fresh {@code BotSM} builds a fresh one — so
     * the character must not be left at zero HP with nobody left to stand it up. Always call
     * this before the old behaviour is thrown away.
     */
    public void abandon() {
        if (!down) {
            return;
        }
        Character chr = this.chr;
        if (chr != null && chr.getMap() != null) {
            int full = Math.max(1, chr.getCurrentMaxHp());
            try {
                BotClientBinding.runWithBoundPlayer(chr, () -> chr.updateHp(full));
                chr.updatePartyMemberHP();
            } catch (RuntimeException ignored) {
                // the character is going away anyway — just do not leave it at zero
            }
        }
        clearDown();
    }

    /**
     * The death episode belonging to this character's running bot behaviour, or null when it has
     * none (a bare artificial character, or one mid-retype). Lets the damage layer end a bot
     * without knowing which of the twenty-odd bot types it is.
     */
    public static BotDeath of(Character chr) {
        if (chr == null) {
            return null;
        }
        BotSM owner = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage
                .getBotById(chr.getId());
        return owner == null ? null : owner.death();
    }

}
