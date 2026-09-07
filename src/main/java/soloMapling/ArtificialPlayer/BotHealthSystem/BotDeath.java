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
    private static final long DOWN_MIN_MS = 30_000L;
    private static final long DOWN_MAX_MS = 120_000L;

    // Grumble cadence while down.
    private static final long GRUMBLE_MIN_MS = 8_000L;
    private static final long GRUMBLE_MAX_MS = 15_000L;

    // Fail-safe: nothing should keep a bot dead this long. If it does something is wedged, and
    // standing up is better than a permanent corpse.
    private static final long DOWN_MAX_HARD_MS = 5 * 60_000L;

    private final Character chr;
    private boolean down;
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
     * Marks the bot dead, starting the down timer immediately.
     *
     * <p>The world node for the grumbling is sampled now rather than on each line, because the
     * "kind" of death is about where it happened: the same map is not reclassified later.
     */
    void markDown() {
        long now = System.currentTimeMillis();
        if (down) {
            return; // already down — a second killing blow must not restart the wait
        }
        down = true;
        passOutAtMs = now;
        standUpAtMs = now + DOWN_MIN_MS + ThreadLocalRandom.current().nextLong(DOWN_MAX_MS - DOWN_MIN_MS);
        nextGrumbleAtMs = now; // let it complain right away
        stopCrumbling();
    }

    /** Stand up, reset everything, and hand control back to this bot's own behaviour. */
    private void clearDown() {
        down = false;
        passOutAtMs = 0L;
        standUpAtMs = 0L;
        nextGrumbleAtMs = 0L;
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

    /** The sanctuary this map points at, or 0 when it has none. */
    static int sanctuaryOf(Character chr) {
        int homeId = chr.getMap().getReturnMapId();
        return homeId != MapId.NONE ? homeId : 0;
    }

    /** Stop anything that keeps changing the corpse while it lies there. */
    private void stopCrumbling() {
        Character chr = this.chr;
        if (chr == null || chr.getMap() == null) {
            return;
        }
        try {
            botCancelChair(chr); // a seated bot keeps healing — see Character.startChairTask
            GCMovement.stop(chr);
        } catch (RuntimeException ignored) {
            // one overdue clean-up must not cost us the death handling
        }
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

    /**
     * The lethal-blow entry point: called by the damage layer when a hit is more than the bot
     * could drink through. Nothing else may set {@link #down}.
     */
    public void kill() {
        Character chr = this.chr;
        if (chr == null || !BotHelpers.isBot(chr)) {
            return; // only artificial characters have this lifecycle
        }
        BotClientBinding.runWithBoundPlayer(chr, () -> chr.updateHp(0));
        chr.updatePartyMemberHP(); // headless bots never receive their own stat packet
        markDown();
    }
}
