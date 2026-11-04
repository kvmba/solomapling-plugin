package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.gms.client.Character;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.server.maps.MapleMap;
import org.gms.server.partyquest.MonsterCarnival;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.BotLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens Monster Carnival matches between a real party and a full team of bots.
 *
 * <p>Why this exists at all: the quest's own entry path cannot be driven by a bot. Its challenge
 * dialog ({@code cpqchallenge.js}) is answered by the player who receives it, and the accepted
 * branch reaches {@code ldr.getClient().getCM().startCPQ(...)} - a socket-level NPC conversation
 * the bot side does not have. Everything below therefore builds the same match out of the engine's
 * non-interactive primitives: two {@link Party} records, one {@link MonsterCarnival}, the arena's
 * own map instances. No NPC script, no dialog, no {@code cm.*} call.
 *
 * <p>The contract with the rest of the plugin:
 * <ul>
 *   <li>a bot team is always {@link CarnivalPqData#BOT_TEAM_SIZE} bots, and its leader is a bot;</li>
 *   <li>the player side is whatever party is standing in the same field lobby;</li>
 *   <li>the two sides do not have to be the same size - the engine enforces no such rule, and
 *       a bot team of six against a party of two is a legal match;</li>
 *   <li>the match is only opened while the player party's leader stands in the lobby, so a
 *       half-assembled party is never dropped into an arena alone.</li>
 * </ul>
 *
 * <h2>What makes it look like a player-initiated match</h2>
 * The three things a player can see are: the wait before the arena accepts (the other team
 * "deciding"), the notice both sides receive on acceptance, and the field's own occupied state.
 * This class reproduces all three - see {@link #CONFIRM_DELAY_MIN_MS} and
 * {@link #announceChallenge}.
 */
public final class CarnivalArena {

    private CarnivalArena() {
    }

    /**
     * The window between "the player party is standing in a field" and "the arena starts".
     *
     * <p>A real opponent takes a moment to accept the challenge - long enough to read the party
     * list in the dialog. Starting on the same tick the player walks in would be the one detail
     * that gives the match away as synthetic, so the opening is deliberately delayed and jittered
     * per field.
     */
    private static final long CONFIRM_DELAY_MIN_MS = 8_000L;
    private static final long CONFIRM_DELAY_MAX_MS = 15_000L;

    /** How often the fields are polled. Once a second is enough for a ten-minute match. */
    private static final long TICK_PERIOD_MS = 1_000L;

    /** How long a field must stay quiet before its next match may open. */
    private static final long FIELD_COOLDOWN_MS = 20_000L;

    /** One field's pending opening: the player party seen there and when the wait started. */
    private static final class PendingMatch {
        final Party players;
        final long dueAtMs;

        PendingMatch(Party players, long dueAtMs) {
            this.players = players;
            this.dueAtMs = dueAtMs;
        }
    }

    /** field key -> pending opening. */
    private static final Map<Integer, PendingMatch> PENDING = new ConcurrentHashMap<>();

    /** field key -> the live match on it, so a field is never opened twice. */
    private static final Map<Integer, MonsterCarnival> RUNNING = new ConcurrentHashMap<>();

    /** field key -> epoch ms before which the field must not be re-opened. */
    private static final Map<Integer, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();

    /** field key -> the bot party that fields a team there, so it is reused across matches. */
    private static final Map<Integer, Party> BOT_PARTIES = new ConcurrentHashMap<>();

    /**
     * field keys seen busy on the previous poll.
     *
     * <p>Needed because the channel's own field registry is the only record of "a match is
     * running", and it is cleared by whoever ends the match - so the first poll after any match
     * sees a free field with no local trace of what just happened. Remembering the busy fields
     * lets that transition be recognised as "a match just ended" and earn the cool-down instead
     * of being re-matched on the spot.
     */
    private static final Map<Integer, Boolean> WAS_BUSY = new ConcurrentHashMap<>();

    /**
     * field keys with a bot-spawn request in flight.
     *
     * <p>Spawn requests are asynchronous (see {@code requestTeamSpawn}), and the poll runs every
     * second while a spawn can take much longer. Without this gate each poll would start another
     * batch, and the field would be flooded the moment the first one lands.
     */
    private static final Map<Integer, Boolean> SPAWNING = new ConcurrentHashMap<>();

    /**
     * arena map id -> whether its {@code monsterCarnival} section loaded.
     *
     * <p>Cached because the probe reads WZ and the map data cannot change while the server is up;
     * re-reading it on every poll of every field would be pure waste. Also memoised because a
     * probe instance has to be disposed after use, and there is nothing to read on a disposed one.
     */
    private static final Map<Integer, Boolean> ARENA_HAS_DATA = new ConcurrentHashMap<>();

    /**
     * State key: world, channel, edition and field together.
     *
     * <p>Every field exists once per channel, and the poll walks every channel of every world, so
     * a key that omitted either would merge unrelated fields. Without the channel, channel 1's
     * field 0 and channel 3's field 0 would share one pending wait, one running match and one
     * cool-down - a field on one channel would never open because another channel was busy.
     * Without the world the same happens across worlds, because channel ids restart at 1 in each.
     *
     * <p>Worlds are packed at a coarse stride (a million) so the field part stays readable in a
     * log line; any real world index is far below that.
     */
    private static int fieldKey(int world, int channel, boolean cpq2, int field) {
        return world * 1_000_000 + channel * 1_000 + (cpq2 ? 100 : 0) + field;
    }

    /**
     * The key for a channel, reading its world off the channel rather than assuming one.
     *
     * <p>The plugin's own world is the only one with bots in it, but the poll still walks every
     * world, and a key collision there would make a real player's field look occupied by another
     * world's match.
     */
    private static int fieldKey(Channel channel, boolean cpq2, int field) {
        return fieldKey(channel.getWorld(), channel.getId(), cpq2, field);
    }

    /** Guards the one-time start of the poll. */
    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The poll thread; daemon so it never holds the JVM open.
     *
     * <p>Volatile and recreated by {@link #releaseAll()}: a scheduled executor cannot be restarted
     * once shut down, and the plugin can be unloaded and loaded again in one process. Holding this
     * in a final field would make the second load silently field no matches at all.
     */
    private static volatile java.util.concurrent.ScheduledExecutorService POOL =
            newPollThread();

    private static java.util.concurrent.ScheduledExecutorService newPollThread() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cpq-arena");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the field poll (idempotent).
     *
     * <p>Deliberately a poll rather than a hook: the arena's own entry point is an NPC dialog,
     * which is exactly what this class may not touch, so "is a player party waiting in a field"
     * has to be observed. One pass costs nine map lookups per channel on already-loaded maps.
     */
    public static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            POOL.scheduleAtFixedRate(CarnivalArena::safeTick, TICK_PERIOD_MS, TICK_PERIOD_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // A pool that refuses the task (shut down by an unload, or already terminated) leaves
            // STARTED set with nothing scheduled - every future field would then be invisible.
            STARTED.set(false);
            BotLogger.log("CPQ arena poll could not be scheduled: " + e);
        }
    }

    /**
     * One poll, with every failure contained.
     *
     * <p>A scheduled task that lets anything escape is cancelled for good - {@code
     * scheduleAtFixedRate} does not resubmit it. So this catches {@link Throwable}, not just
     * {@link RuntimeException}: a {@code NoClassDefFoundError} or {@code StackOverflowError} raised
     * once inside a map lookup would otherwise stop the carnival permanently, with no log line and
     * no way to tell it apart from "nobody is playing".
     */
    private static void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            BotLogger.log("CPQ arena tick failed: " + t);
        }
    }

    /**
     * One poll of every carnival field on every channel.
     *
     * <p>Cheap when no field has any player in it: each iteration asks the map factory for an
     * already-loaded map and scans a typically-empty player list.
     */
    private static void tick() {
        if (!enabled()) {
            return;
        }
        for (Channel channel : carnivals()) {
            if (channel == null) {
                continue;
            }
            for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
                tickFieldSafely(channel, field, false);
            }
            for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
                tickFieldSafely(channel, field, true);
            }
        }
    }

    /**
     * One field's tick, isolated.
     *
     * <p>One field's failure must not cost the others their poll: a bad map record, a party that
     * vanished mid-tick, a channel torn down under us - all of them are that field's problem, and
     * the remaining eight still need their matches reaped and their waits advanced.
     */
    private static void tickFieldSafely(Channel channel, int field, boolean cpq2) {
        try {
            tickField(channel, field, cpq2);
        } catch (Throwable t) {
            BotLogger.log("CPQ arena field " + field + " (cpq2=" + cpq2 + ") tick failed: " + t);
        }
    }

    /**
     * Every channel of the bot's own world.
     *
     * <p>A carnival field belongs to one channel, and the players who walk into one live on the
     * channel they are on, so the poll has to cover them all - a player on channel 3 must find a
     * waiting team just like one on channel 1.
     *
     * <p>Only the bot world is walked. Bots do not exist in any other world (the whole artificial
     * player subsystem is pinned to {@code WORLD_SCANIA}), so a field there could never be staffed:
     * polling it would try to spawn a team on a channel index that resolves back into world 0,
     * find no bots in the other world's storage, and repeat that futile spawn on every tick.
     */
    private static List<Channel> carnivals() {
        List<Channel> out = new ArrayList<>();
        var world = org.gms.net.server.Server.getInstance()
                .getWorld(soloMapling.server.SoloMaplingConstants.GameConstants.WORLD_SCANIA);
        if (world != null) {
            out.addAll(world.getChannels());
        }
        return out;
    }

    /** Whether the server has the carnival enabled at all (the NPC gates on the same flag). */
    private static boolean enabled() {
        return org.gms.config.GameConfig.getServerBoolean("use_cpq");
    }

    /**
     * Advance one field one tick: reap a finished match, open a due one, or arm a new wait.
     */
    private static void tickField(Channel channel, int field, boolean cpq2) {
        int key = fieldKey(channel, cpq2, field);
        int lobbyId = cpq2 ? CarnivalPqData.lobbyForField2(field) : CarnivalPqData.lobbyForField(field);

        // Is a match already running here?
        //
        // Deliberately NOT `canInitMonsterCarnival(field)`. That registry is keyed by a "room"
        // number, and the two entry paths disagree on it: the NPC derives the room from the arena
        // map id (`(mapId / 100) % 10` for CPQ1, `(mapId / 1000) % 10` for CPQ2), which is
        // `field + 1`, while `fieldTaken` probes the plain field index. So a match started through
        // the NPC marks room N+1 and a match started here marks room N - and neither check can see
        // the other. Probing both room numbers is not a fix either: it would make field N's match
        // look like field N+1's, blocking the neighbouring field for the whole run.
        //
        // What both paths DO agree on is the physical occupancy of the field: a running match has
        // its teams standing in the arena (or its holding maps), and a party waiting to be matched
        // stands in the field lobby. Reading that is authoritative regardless of who opened it.
        boolean runningHere = RUNNING.containsKey(key) || fieldOccupied(channel, field, cpq2);
        if (runningHere) {
            WAS_BUSY.put(key, Boolean.TRUE);
            if (RUNNING.get(key) == null) {
                // Somebody else's match on this field: leave it alone and make sure our own
                // pending attempt does not fight it.
                PENDING.remove(key);
            }
            return;
        }
        // A field that was busy last poll and is free now just finished a match - ours or
        // another source's. Applying the cool-down HERE (rather than only when our own match
        // object is reaped) is what stops the exact players who just played from being
        // immediately re-matched by the next poll.
        //
        // Both removals must run unconditionally. Written as
        // `RUNNING.remove(key) != null || WAS_BUSY.remove(key) != null` the short-circuit skips
        // the second call whenever our own match was the one just reaped, leaving a stale busy
        // flag that arms a second, bogus cool-down on the next poll.
        boolean ownMatchEnded = RUNNING.remove(key) != null;
        boolean foreignMatchEnded = WAS_BUSY.remove(key) != null;
        if (ownMatchEnded || foreignMatchEnded) {
            COOLDOWN_UNTIL.put(key, System.currentTimeMillis() + FIELD_COOLDOWN_MS);
            PENDING.remove(key);
            return;
        }

        Long coolUntil = COOLDOWN_UNTIL.get(key);
        if (coolUntil != null) {
            if (System.currentTimeMillis() < coolUntil) {
                return;
            }
            COOLDOWN_UNTIL.remove(key);
        }

        // Never load a field lobby just to look at it. `getMap` loads WZ on a miss, and the poll
        // runs for nine fields on every channel every second - on an idle server that would pull
        // every lobby into memory for nothing. A field nothing has touched (no player, no match)
        // is skipped outright; the one a player walks into is loaded by their own entry.
        if (!channel.getMapFactory().isMapLoaded(lobbyId)) {
            return;
        }
        MapleMap lobby = channel.getMapFactory().getMap(lobbyId);

        if (!hasHuman(lobby)) {
            PENDING.remove(key);
            return;
        }

        Party players = readyPlayerParty(lobby);
        if (players == null) {
            // A lone player, or a party with someone still walking to the field: wait, exactly as
            // the NPC does ("Make sure everyone in your party is among the correct levels / not
            // on the map"). No match is ever opened out of a half-assembled party.
            PENDING.remove(key);
            return;
        }

        PendingMatch waiting = PENDING.get(key);
        if (waiting == null || waiting.players != players) {
            PENDING.put(key, new PendingMatch(players,
                    System.currentTimeMillis() + confirmDelayMs()));
            return;
        }
        if (System.currentTimeMillis() < waiting.dueAtMs) {
            return;
        }
        PENDING.remove(key);
        openMatch(channel, players, field, cpq2, key);
    }

    /** A per-field jitter so several fields never open in the same instant. */
    private static long confirmDelayMs() {
        long span = CONFIRM_DELAY_MAX_MS - CONFIRM_DELAY_MIN_MS;
        return CONFIRM_DELAY_MIN_MS + (long) (Math.random() * span);
    }

    /**
     * Whether anybody is standing in this field's maps - the lobby or the arena.
     *
     * <p>This is what both entry paths agree on. A match in progress has its two teams in the
     * arena; a party waiting to be matched stands in the lobby. Checking the maps rather than the
     * "room" registry is deliberate - see {@link #tickField} for why that registry cannot be
     * trusted across the two entry paths.
     *
     * <p>The reward rooms are NOT part of this check: both sides are sent there when the clock
     * expires, and a field whose players are reading their result is finished, not occupied.
     *
     * <p>Uses {@code isMapLoaded} first so the poll never pulls a field's maps into memory just to
     * look at them.
     */
    /**
     * Whether a field is occupied right now - what the poll and the GM diagnostic both ask.
     *
     * <p>Public so {@code !bot cpqarena} can report the same thing the poll acts on; a diagnostic
     * that answers a different question than the scheduler is worse than none.
     */
    public static boolean fieldOccupied(Channel channel, int field, boolean cpq2) {
        int lobby = cpq2 ? CarnivalPqData.lobbyForField2(field) : CarnivalPqData.lobbyForField(field);
        int arena = cpq2 ? CarnivalPqData.arenaForField2(field) : CarnivalPqData.arenaForField(field);

        // The arena counts ANY occupant, bots included: a match in progress has both teams there,
        // and the bot side is bots. (A player-versus-player match started through the NPC also puts
        // its players there, so this catches that too - which the room registry cannot.)
        if (channel.getMapFactory().isMapLoaded(arena)) {
            MapleMap arenaMap = channel.getMapFactory().getMap(arena);
            if (arenaMap != null && !arenaMap.getAllPlayers().isEmpty()) {
                return true;
            }
        }
        // The lobby counts only real players: our own waiting bot team stands there too, and
        // treating it as occupancy would make the field permanently "busy" to itself.
        if (!channel.getMapFactory().isMapLoaded(lobby)) {
            return false;
        }
        MapleMap lobbyMap = channel.getMapFactory().getMap(lobby);
        return lobbyMap != null && hasHuman(lobbyMap);
    }

    /**
     * Whether any real player is standing in this lobby.
     *
     * <p>The poll's cheapest early-out, and the one that keeps it free when the server is idle:
     * one map lookup and a scan that stops at the first player. A lobby with only bots in it is
     * normal between matches and must not open one.
     */
    private static boolean hasHuman(MapleMap lobby) {
        for (Character chr : lobby.getAllPlayers()) {
            if (!soloMapling.ArtificialPlayer.BotHelpers.isBot(chr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The player party waiting in this field, or null when no party is ready here.
     *
     * <p>Several players may be standing in one lobby in different parties (or alone). The one
     * that may be matched is the party whose <em>whole</em> roster is here and level-eligible -
     * the same two conditions the quest's NPC applies before it lets a challenge through. Picking
     * "whoever the map listed first" instead would match a party whose other members are still in
     * town, which the engine would then warp into the arena without them.
     */
    private static Party readyPlayerParty(MapleMap lobby) {
        java.util.Set<Party> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Character chr : lobby.getAllPlayers()) {
            if (soloMapling.ArtificialPlayer.BotHelpers.isBot(chr)) {
                continue;
            }
            Party party = chr.getParty();
            if (party == null || !seen.add(party)) {
                continue;
            }
            // The challenger must be the party's own leader, exactly as the NPC insists
            // ("Your party leader must talk to me"): the engine's match record is keyed on him.
            PartyCharacter leader = party.getLeader();
            if (leader == null || leader.getPlayer() == null
                    || leader.getPlayer().getMap() != lobby) {
                continue;
            }
            if (!playersIneffective(party, lobby)) {
                return party;
            }
        }
        return null;
    }

    /**
     * Whether a player party is not actually ready to fight.
     *
     * <p>Two real refusals from the quest, restated as conditions: every member must be standing
     * in this very field (the NPC says "some of the party members are not on the map"), and every
     * member must be inside the edition's level band (its other message). Both are properties of
     * the party, not of the bot, so honouring them keeps the bot from starting something the game
     * itself would have refused.
     */
    private static boolean playersIneffective(Party party, MapleMap lobby) {
        List<PartyCharacter> members = party.getPartyMembers();
        if (members.isEmpty() || members.size() > CarnivalPqData.BOT_TEAM_SIZE) {
            return true;
        }
        for (PartyCharacter mpc : members) {
            Character mc = mpc.getPlayer();
            if (mc == null || mc.getMap() != lobby) {
                return true;
            }
            int level = mc.getLevel();
            if (lobby.isCPQLobby()) {
                if (level < CarnivalPqData.CPQ1_MIN_LEVEL || level > CarnivalPqData.CPQ1_MAX_LEVEL) {
                    return true;
                }
            } else {
                if (level < CarnivalPqData.CPQ2_MIN_LEVEL || level > CarnivalPqData.CPQ2_MAX_LEVEL) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Field a full bot team and start the match.
     *
     * <p>The bot team is created through the same seam a real party uses ({@code Party.createParty}
     * plus {@link Party#joinParty}), so every party-bound structure the arena reads - the member
     * list, the map's party registry, the leader - is populated exactly as it would be for six
     * players. Nothing here talks to an NPC.
     */
    private static void openMatch(Channel channel, Party players, int field, boolean cpq2, int key) {
        int arenaId = cpq2 ? CarnivalPqData.arenaForField2(field) : CarnivalPqData.arenaForField(field);

        // Validate on a DISPOSABLE copy, never on getMap(). Two reasons, both concrete:
        //   * getMap() publishes the map in MapManager's cache, and the match itself is played on
        //     a disposable instance (MonsterCarnival calls getDisposableMap) which is disposed at
        //     the end. Probing through getMap() would leave a permanent, never-disposed shell of
        //     every arena in the cache - nine full MapleMap objects with footholds and spawns, one
        //     per field per channel, for the life of the process.
        //   * The probe must describe the map the match will actually run on, and that is the
        //     disposable one.
        //
        // The probe costs one WZ read, so its verdict is cached per arena id: the map data cannot
        // change while the server is up, and re-reading it on every poll of every field would be
        // pure waste. Absent (or false) means "not yet known" and triggers the probe.
        //
        // Runs BEFORE the bot team is built: a field that cannot host a match must not leave six
        // bots bound to it in a party that will never fight.
        Boolean usable = ARENA_HAS_DATA.get(arenaId);
        if (usable == null) {
            MapleMap probe = channel.getMapFactory().getDisposableMap(arenaId);
            if (probe == null) {
                BotLogger.log("CPQ field " + field + ": arena " + arenaId + " does not exist");
                return;
            }
            int timeDefault = probe.getTimeDefault();
            int summonCount = probe.getMobsToSpawn().size();
            // The probe instance is ours alone and nobody will ever enter it.
            probe.dispose();
            // The arena's own clock is what ends the match, and MonsterCarnival reads it straight
            // from the map's monsterCarnival section. A map whose section did not load (an id
            // outside isCPQMap's table, a stripped WZ) reports zero, and a zero-length match would
            // finish the instant it started - so refuse to open one rather than warp two parties
            // into nothing.
            usable = timeDefault > 0 && summonCount > 0;
            if (!usable) {
                BotLogger.log("CPQ field " + field + ": arena " + arenaId
                        + " has no carnival data (timeDefault=" + timeDefault
                        + ", summons=" + summonCount + ")");
            }
            ARENA_HAS_DATA.put(arenaId, usable);
        }
        if (!usable) {
            COOLDOWN_UNTIL.put(key, System.currentTimeMillis() + FIELD_COOLDOWN_MS);
            return;
        }

        Party bots = botTeamFor(channel, field, cpq2);
        if (bots == null || bots.getMembers().size() < CarnivalPqData.BOT_TEAM_SIZE) {
            BotLogger.log("CPQ field " + field + ": could not field a full bot team, skipping");
            return;
        }

        // Move the bot team into the player's field before the match record is created, so the
        // team is briefly visible where an opponent would be waiting. MonsterCarnival's own
        // forceChangeMap then warps both sides into the arena.
        int lobbyId = cpq2 ? CarnivalPqData.lobbyForField2(field) : CarnivalPqData.lobbyForField(field);
        moveTeamTo(bots, channel, lobbyId);

        // The room number the engine registers. Kept as the plain field index, because that is
        // what `fieldTaken(field)` probes - and that is what must see OUR match, or a player could
        // queue into a field a bot match is already running in. (The NPC's own entry path passes a
        // value derived from the arena map id, i.e. field + 1, which its own `fieldTaken` then
        // fails to find - a host bug we neither inherit nor depend on: a field started by the NPC
        // is player-versus-player, where no bot is wanted.)
        int room = field;

        try {
            MonsterCarnival run = new MonsterCarnival(players, bots, arenaId, !cpq2, room);
            // The constructor swallows its own failures, so "did it start?" has to be read off the
            // channel: a completed start registers the field.
            if (!channel.canInitMonsterCarnival(!cpq2, room)) {
                // Clear the field lobby's three-minute walk-out timer only once the match really
                // started. The NPC's own entry path installs it (cpqLobby) and cancels it when a
                // challenge is accepted (cancelCPQLobby inside startCPQ); opening the match
                // directly skips that, and the players would be yanked out three minutes in.
                clearLobbyTimers(players);
                RUNNING.put(key, run);
                // Announced only now: telling the players "the opposing party accepted" before a
                // constructor that can silently fail would leave them waiting for a match that
                // was never going to start.
                announceChallenge(players, bots);
                BotLogger.log("CPQ field " + field + " opened: " + players.getMembers().size()
                        + " players vs " + bots.getMembers().size() + " bots");
            } else {
                BotLogger.log("CPQ field " + field + ": the engine refused to start the match");
                COOLDOWN_UNTIL.put(key, System.currentTimeMillis() + FIELD_COOLDOWN_MS);
                // The team was already walked into the field lobby. Leaving it there would strand
                // it: a field lobby is that bot's own lobbyMapId, so the base class's reclaim
                // never moves it out, and it would sit in a field nobody is fighting in for good.
                sendTeamHome(bots, channel, cpq2);
            }
        } catch (RuntimeException e) {
            // The constructor swallows most of its own failures, but the party lookup before it
            // can throw (a member whose channel/world no longer resolves), and by then the two
            // parties may already have been made each other's enemy. Undo that here, or the next
            // attempt starts with both sides pointing at a match that never existed.
            BotLogger.log("CPQ field " + field + ": match start failed (" + e + ")");
            COOLDOWN_UNTIL.put(key, System.currentTimeMillis() + FIELD_COOLDOWN_MS);
            players.setEnemy(null);
            bots.setEnemy(null);
            releaseFighters(players);
            releaseFighters(bots);
            sendTeamHome(bots, channel, cpq2);
            channel.finishMonsterCarnival(!cpq2, room);
        }
    }

    /**
     * Undo the per-character state a half-started match left behind.
     *
     * <p>{@code MonsterCarnival}'s constructor sets {@code monsterCarnival}, the team and the
     * festival points on each member before it does the work that can fail. A member left holding
     * a team outside a match would render in carnival colours in town and, worse, still route CP
     * awards into a run object nothing owns.
     */
    private static void releaseFighters(Party party) {
        for (PartyCharacter mpc : party.getPartyMembers()) {
            Character mc = mpc.getPlayer();
            if (mc != null) {
                mc.setMonsterCarnival(null);
                mc.setTeam(-1);
                mc.setFestivalPoints(0);
            }
        }
    }

    /**
     * Cancel the three-minute "walk out of the field lobby" timer the NPC's entry path installs.
     *
     * <p>{@code NPCConversationManager.cpqLobby} schedules {@code changeMap(hall)} on every member
     * so a party that is never challenged does not sit in the field forever. The normal entry path
     * cancels it when the challenge is accepted ({@code cancelCPQLobby} inside {@code startCPQ});
     * opening the match directly would skip that, and the players would be teleported out of a
     * live match at the three-minute mark.
     */
    private static void clearLobbyTimers(Party players) {
        for (PartyCharacter mpc : players.getPartyMembers()) {
            Character mc = mpc.getPlayer();
            if (mc != null) {
                mc.clearCpqTimer();
            }
        }
    }

    /**
     * Walk a whole bot team back to its edition's town hall.
     *
     * <p>Needed after a match fails to start: the team has already been moved into the field lobby,
     * and that lobby is the bot's own {@code lobbyMapId} - so the base class's "outside the quest
     * and outside the lobby" reclaim will never move it out. Without this the six bots sit in a
     * field nobody is fighting in, unusable by any other field because they still look committed.
     */
    private static void sendTeamHome(Party bots, Channel channel, boolean cpq2) {
        int hallId = cpq2 ? CarnivalPqData.LOBBY_MAP_CPQ2 : CarnivalPqData.LOBBY_MAP;
        moveTeamTo(bots, channel, hallId);
    }

    /**
     * Walk a whole bot team into a map.
     *
     * <p>Uses {@code changeMap} rather than the movement engine: the team is crossing maps, not
     * walking a room, and the arena's own entry does the same. A member whose map already matches
     * is left alone, so a team that was drafted in place does not blink.
     */
    private static void moveTeamTo(Party bots, Channel channel, int mapId) {
        MapleMap target = channel.getMapFactory().getMap(mapId);
        if (target == null) {
            return;
        }
        for (PartyCharacter mpc : bots.getPartyMembers()) {
            Character mc = mpc.getPlayer();
            if (mc != null && mc.getMapId() != mapId) {
                mc.changeMap(target, target.getPortal(0));
            }
        }
    }

    /**
     * Tell both sides the match was accepted.
     *
     * <p>A player who challenges another party sees a system notice on both clients; without one
     * the arena would appear out of nowhere.
     */
    private static void announceChallenge(Party players, Party bots) {
        for (PartyCharacter mpc : players.getPartyMembers()) {
            notifyAccepted(mpc.getPlayer());
        }
        for (PartyCharacter mpc : bots.getPartyMembers()) {
            notifyAccepted(mpc.getPlayer());
        }
    }

    private static void notifyAccepted(Character mc) {
        if (mc != null) {
            mc.dropMessage(6, "The opposing party accepted the challenge. The battle begins...");
        }
    }

    /**
     * The bot party for a field: reused while it exists and its members are still around, and
     * rebuilt (full size) when it is not.
     */
    private static Party botTeamFor(Channel channel, int field, boolean cpq2) {
        int key = fieldKey(channel, cpq2, field);
        Party existing = BOT_PARTIES.get(key);
        if (existing != null && liveTeamSize(existing) >= CarnivalPqData.BOT_TEAM_SIZE) {
            return existing;
        }
        if (existing != null) {
            disband(existing);
            BOT_PARTIES.remove(key);
        }

        List<Character> bots = recruitBots(channel, field, cpq2);
        if (bots.size() < CarnivalPqData.BOT_TEAM_SIZE) {
            return null;
        }
        Character leader = bots.get(0);
        if (!Party.createParty(leader, true)) {
            return null;
        }
        Party team = leader.getParty();
        if (team == null) {
            return null;
        }
        for (int i = 1; i < CarnivalPqData.BOT_TEAM_SIZE; i++) {
            if (!Party.joinParty(bots.get(i), team.getId(), true)) {
                // A join that fails leaves the team short; better to drop the whole attempt than
                // to field an undersized side.
                disband(team);
                return null;
            }
        }
        BOT_PARTIES.put(key, team);
        return team;
    }

    /** How many members of a party are still logged in, standing somewhere real and still in it. */
    private static int liveTeamSize(Party party) {
        int live = 0;
        for (PartyCharacter mpc : party.getPartyMembers()) {
            Character mc = mpc.getPlayer();
            // The Party record can outlive its members: a bot that was re-typed or despawned keeps
            // a stale PartyCharacter whose getPlayer() is null. Counting only live members is what
            // makes the "team is still field-able" test honest.
            if (mc != null && mc.getMap() != null && mc.getParty() == party) {
                live++;
            }
        }
        return live;
    }

    /**
     * Tear down a bot team.
     *
     * <p>Delegates the leader's leave to {@link BotPartyCommands#botLeaveParty} rather than
     * dropping the {@code Party} object: the party is also registered in the world's party table
     * and in each map's party list, and a later {@code joinParty} on those members would fail
     * while the stale records still say they are committed. A leader's leave disbands the whole
     * party in one operation, which is what clears all of them.
     *
     * <p>The per-member release runs even when that call throws: a party record the world no
     * longer knows about must not leave its members pointing at it, or every later match finds
     * them "already committed" and never drafts them again.
     */
    private static void disband(Party party) {
        party.setEnemy(null);
        try {
            PartyCharacter leader = party.getLeader();
            Character leaderChr = leader == null ? null : leader.getPlayer();
            if (leaderChr != null && leaderChr.getParty() == party) {
                BotPartyCommands.botLeaveParty(leaderChr);
            }
        } catch (RuntimeException e) {
            BotLogger.log("CPQ: could not leave party " + party.getId() + " (" + e + ")");
        } finally {
            // Any member the disband path could not reach (offline, already gone, or the throw
            // above) is still released here so it can be drafted by a later match.
            for (PartyCharacter mpc : new ArrayList<>(party.getPartyMembers())) {
                Character mc = mpc.getPlayer();
                if (mc != null) {
                    mc.setParty(null);
                }
            }
        }
    }

    /**
     * Pick the bots that will fight here: the carnival bots that already exist on this channel,
     * topped up by spawning more when there are not six.
     *
     * <p>Only {@code CARNIVAL_PQ_BOT} characters are drafted - a grind bot or a town wanderer is
     * not a carnival opponent, and mixing them in would show as a team that does not fight.
     */
    private static List<Character> recruitBots(Channel channel, int field, boolean cpq2) {
        List<Character> out = new ArrayList<>();
        for (Character chr : channel.getPlayerStorage().getAllCharacters()) {
            if (chr == null || !soloMapling.ArtificialPlayer.BotHelpers.isBot(chr)) {
                continue;
            }
            if (!isCarnivalBot(chr)) {
                continue;
            }
            if (chr.getParty() != null) {
                continue; // already committed to another field
            }
            out.add(chr);
        }
        out.sort(Comparator.comparingInt(Character::getId));

        if (out.size() < CarnivalPqData.BOT_TEAM_SIZE) {
            // Spawning is NOT done on this thread. PlatformPlacement.spawnBotsOnMap blocks on a
            // latch for up to two minutes and retries with sleeps, and this method runs on the
            // single poll thread - one slow spawn would freeze the reaping and opening of every
            // field on every channel for that whole time. The request is handed off and the field
            // simply opens on a later poll, once the new bots are in the channel's storage.
            requestTeamSpawn(channel, field, cpq2, CarnivalPqData.BOT_TEAM_SIZE - out.size());
        }
        return out.size() > CarnivalPqData.BOT_TEAM_SIZE
                ? out.subList(0, CarnivalPqData.BOT_TEAM_SIZE)
                : out;
    }

    /**
     * Ask for more carnival bots on a channel, off the poll thread.
     *
     * <p>One request per field is in flight at a time; a second poll that still finds the team
     * short does not stack another spawn on top of the first, which would double the population
     * the moment the first batch lands.
     */
    private static void requestTeamSpawn(Channel channel, int field, boolean cpq2, int want) {
        int key = fieldKey(channel, cpq2, field);
        if (SPAWNING.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        int lobbyId = cpq2
                ? CarnivalPqData.lobbyForField2(field)
                : CarnivalPqData.lobbyForField(field);
        int channelId = channel.getId();
        soloMapling.server.ExecutorServiceManager.runAsync(() -> {
            try {
                soloMapling.ArtificialPlayer.PartyQuest.PqBotSpawner
                        .spawnCarnivalTeam(want, lobbyId, cpq2, channelId);
            } catch (RuntimeException e) {
                BotLogger.log("CPQ field " + field + ": spawn failed (" + e + ")");
            } finally {
                SPAWNING.remove(key);
            }
        });
    }

    /** Whether this character is played by the carnival bot brain. */
    private static boolean isCarnivalBot(Character chr) {
        var bot = CharacterStorage.getBotById(chr.getId());
        return bot instanceof CarnivalPQBot;
    }

    /**
     * Drop every field record this class keeps (plugin shutdown / bot re-conversion).
     *
     * <p>The bot parties are actually dissolved rather than only forgotten: they are registered in
     * the world's party table and in their map's party list, so clearing the map without leaving
     * the parties would strand six characters in a party nothing owns - and they would keep
     * failing to be drafted, because they look committed.
     */
    /**
     * Drop every field record this class keeps, and stop the poll (plugin shutdown).
     *
     * <p>The bot parties are actually dissolved rather than only forgotten: they are registered in
     * the world's party table and in their map's party list, so clearing the map without leaving
     * the parties would strand six characters in a party nothing owns - and they would keep
     * failing to be drafted, because they look committed.
     *
     * <p>The poll is cancelled too. It reads the world and the channel map factories, and after an
     * unload there is no world to read; leaving it scheduled would have it throwing once a second
     * for the rest of the process (the daemon thread would never hold the JVM open, but the noise
     * and the repeated failed map lookups would be permanent).
     */
    public static void releaseAll() {
        // Cancel, then discard this thread and arm a fresh one for a possible reload. The plugin
        // can be unloaded and loaded again in the same process, and a ScheduledExecutorService
        // cannot be restarted once shut down - so `STARTED` must also be cleared, or the second
        // load would find a dead pool behind a "already started" flag and field nothing.
        POOL.shutdownNow();
        POOL = newPollThread();
        STARTED.set(false);
        for (Party party : new ArrayList<>(BOT_PARTIES.values())) {
            disband(party);
        }
        forgetFields();
    }

    /**
     * Drop only the bookkeeping, leaving the parties alone.
     *
     * <p>What the tests need: the maps are pure state, while {@link #releaseAll()} also talks to
     * the world's party registry (and would fail outside a running server, where no world exists).
     */
    static void forgetFields() {
        PENDING.clear();
        RUNNING.clear();
        COOLDOWN_UNTIL.clear();
        WAS_BUSY.clear();
        SPAWNING.clear();
        BOT_PARTIES.clear();
    }
}
