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
 * The three things a player can see are: the opposing team walking into the field and waiting
 * there, the field's own countdown, and the notice both sides receive on acceptance. This class
 * reproduces all three - see {@link #MATCH_WAIT_MS} and {@link #announceChallenge}.
 */
public final class CarnivalArena {

    private CarnivalArena() {
    }

    /**
     * How long the player party waits in the field before the bot team's challenge lands.
     *
     * <p>This is a rule from the players' side of the feature, not a cosmetic delay: a party that
     * walks into a field lobby sees its opponent walk in and stand there, watches the field's own
     * countdown, and the match opens when it reaches zero. Anything shorter would be the match
     * appearing out of nowhere, which is exactly what the previous jittered 8-15s opening looked
     * like from inside the lobby.
     */
    private static final long MATCH_WAIT_MS = CarnivalPqData.MATCH_WAIT_MS;

    /** How often the fields are polled. Once a second is enough for a ten-minute match. */
    private static final long TICK_PERIOD_MS = 1_000L;

    /** How long a field must stay quiet before its next match may open. */
    private static final long FIELD_COOLDOWN_MS = 20_000L;

    /**
     * One field's pending match: the player party waiting there, the bot team already standing in
     * the field with them, the field's own map (so the countdown clock can be taken off screen),
     * and when the countdown runs out.
     */
    private static final class PendingMatch {
        final Party players;
        final Party bots;
        final MapleMap lobby;
        final long dueAtMs;

        PendingMatch(Party players, Party bots, MapleMap lobby, long dueAtMs) {
            this.players = players;
            this.bots = bots;
            this.lobby = lobby;
            this.dueAtMs = dueAtMs;
        }
    }

    /** field key -> pending match. */
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
     * Advance one field one tick: reap a finished match, advance a countdown, or arm a new one.
     *
     * <p>The three states a field can be in, in order:
     * <ol>
     *   <li><b>a match is running</b> - our own record, or anybody standing in the arena instance
     *       (the NPC's player-versus-player path lands there too). Left alone.</li>
     *   <li><b>a countdown is running</b> - a player party is waiting and a bot team has already
     *       walked into the field with them. The countdown is refreshed on the field's own clock;
     *       when it expires the match opens.</li>
     *   <li><b>nothing</b> - a waiting player party starts a countdown and a bot team is fielded
     *       for it.</li>
     * </ol>
     *
     * <p>What is deliberately NOT a busy signal: a real player standing in the lobby. That player
     * is the whole point of the poll - the previous cut treated lobby presence as occupancy and
     * therefore never opened a match at all (the reported "the field never finds an opponent").
     */
    private static void tickField(Channel channel, int field, boolean cpq2) {
        int key = fieldKey(channel, cpq2, field);
        int lobbyId = cpq2 ? CarnivalPqData.lobbyForField2(field) : CarnivalPqData.lobbyForField(field);

        // A field we opened ourselves is reaped FIRST, before anything is allowed to treat it as
        // busy.
        //
        // This ordering is the whole bug that kept a field from ever being matched twice: written
        // as "is it busy? (RUNNING.containsKey) ... then reap (RUNNING.remove)", the reap sits
        // behind the busy early-return and can never run, so our own finished match keeps the
        // field busy for the life of the process.
        if (reapOwnMatch(key)) {
            COOLDOWN_UNTIL.put(key, System.currentTimeMillis() + FIELD_COOLDOWN_MS);
            dropPending(key);
            return;
        }
        // A team whose match is over and whose members are all back in a hall is no longer needed
        // here: release the party so its six bots rejoin the hall's recruitable crowd instead of
        // staying locked to a field nobody is fighting in.
        releaseIdleTeam(key);
        boolean ours = RUNNING.containsKey(key);
        if (CarnivalPqData.fieldBusy(ours, arenaOccupied(channel, cpq2, field))) {
            if (!ours) {
                WAS_BUSY.put(key, Boolean.TRUE);
                // Somebody else's match on this field: leave it alone and make sure our own
                // pending attempt does not fight it.
                abandonPending(key, channel, field, cpq2);
            }
            return;
        }
        if (WAS_BUSY.remove(key) != null) {
            // It was busy last poll and is free now without a record of ours ending: a foreign
            // match (the NPC's) just finished. Cool the field down so the players who just played
            // are not re-matched on the very next poll.
            COOLDOWN_UNTIL.put(key, System.currentTimeMillis() + FIELD_COOLDOWN_MS);
            dropPending(key);
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
            // The party left (or timed out): drop the countdown and send the waiting team home
            // rather than parking six bots in a field nobody is queuing for.
            abandonPending(key, channel, field, cpq2);
            return;
        }

        Party players = readyPlayerParty(lobby);
        if (players == null) {
            // A party that is not whole yet (a member still walking in, or a recruited teammate
            // still on its way). No match is ever opened out of a half-assembled party, and the
            // countdown restarts once the party is complete - but the bot team stays where it is:
            // sending it home and back on every poll would make the field flicker.
            dropPending(key);
            return;
        }

        PendingMatch waiting = PENDING.get(key);
        long now = System.currentTimeMillis();
        if (waiting == null || waiting.players != players) {
            // A new (or changed) party: field a bot team for it and start the countdown. The team
            // is walked into the field in the same step, so the player sees the opponent arrive
            // and wait with them rather than a match appearing.
            Party bots = botTeamFor(channel, field, cpq2);
            if (bots == null) {
                // Not enough carnival bots yet - the spawn was handed off; arm nothing and let
                // the next poll retry once they land.
                abandonPending(key, channel, field, cpq2);
                return;
            }
            moveTeamTo(bots, channel, lobbyId);
            PENDING.put(key, new PendingMatch(players, bots, lobby, now + MATCH_WAIT_MS));
            // ONE clock, not one per second: the client's CLOCK packet starts its own countdown
            // from the value it carries, so re-sending `ceil(remaining)` every poll would reset
            // the display to almost the same number each second and the countdown would appear
            // frozen. Sending it once at the start is what makes the field show a live 60 -> 0.
            sendCountdown(lobby, CarnivalPqData.matchWaitSeconds());
            return;
        }
        if (now < waiting.dueAtMs) {
            return; // the client's own countdown is running; nothing to re-send
        }
        // Claimed only once the match is really going to be attempted: a second poll in this same
        // second must not open the same match twice, but a failure inside openMatch must leave the
        // field free to retry with the team it already walked in.
        //
        // The lobby's countdown is stopped here rather than left to the arena's own clock packet:
        // MonsterCarnival does broadcast one on entry, but only after both parties have been moved,
        // so until then the field would be showing "60 seconds to a match" for a match that has
        // already started.
        dropPending(key);
        if (!openMatch(channel, players, waiting.bots, field, cpq2, key)) {
            // The door did not open (the arena has no carnival data, or the engine refused). The
            // field is on a cool-down; send the team home and free it, or the next attempt would
            // find six bots standing in a field nobody is fighting in.
            sendTeamHome(waiting.bots, channel, cpq2);
            Party team = BOT_PARTIES.remove(key);
            if (team != null) {
                disband(team);
            }
        }
    }

    /** Start the field's countdown on every client waiting there. */
    private static void sendCountdown(MapleMap lobby, int seconds) {
        lobby.broadcastMessage(org.gms.util.PacketCreator.getClock(seconds));
    }

    /** Clear a field's countdown bookkeeping, and take the clock off every waiting client. */
    private static void dropPending(int key) {
        PendingMatch waiting = PENDING.remove(key);
        if (waiting != null) {
            // A clock left on screen after the wait is abandoned keeps counting down to a match
            // that will never open. Cleared here rather than at each call site so every way the
            // wait can end (party left, half-assembled, match opened) behaves the same.
            waiting.lobby.broadcastMessage(org.gms.util.PacketCreator.removeClock());
        }
    }

    /**
     * Clear a field's countdown and walk its waiting team back to the hall.
     *
     * <p>For the countdown being abandoned (the player party left, or broke up): the bots were
     * walked into the field for that party, and a field lobby is a bot's own {@code lobbyMapId},
     * so nothing else would ever move them out. A live match owns its team and is left alone.
     */
    private static void abandonPending(int key, Channel channel, int field, boolean cpq2) {
        dropPending(key);
        if (RUNNING.containsKey(key)) {
            return;
        }
        Party team = BOT_PARTIES.remove(key);
        if (team == null) {
            return;
        }
        // Home first, then released: a party the world no longer knows about would leave its
        // members pointing at it, and every later draft would skip them as "already committed".
        sendTeamHome(team, channel, cpq2);
        disband(team);
    }

    /**
     * Reap our own match on this field when the engine has finished with it.
     *
     * <p>The engine is the authority on "still running": {@code MonsterCarnival.dispose()} disposes
     * the arena instance and nulls its own map pointer. Reading the run's own map is what makes
     * this test honest, and it is why the room registry is not used - the NPC path registers a
     * different room number for the same physical field.
     *
     * <p>Deliberately does NOT touch the bot party: the engine warps both sides to their reward
     * rooms first, and those bots still have the "read the result, then follow the players out"
     * beat to play there. The party is released later by {@link #releaseIdleTeam}, once its
     * members are back in a hall.
     *
     * @return true when a match of ours ended on this poll
     */
    private static boolean reapOwnMatch(int key) {
        MonsterCarnival run = RUNNING.get(key);
        if (run == null || run.getEventMap() != null) {
            return false; // no match of ours here, or it is still playing
        }
        RUNNING.remove(key);
        return true;
    }

    /**
     * Release a field's bot team once every member is back in a hall.
     *
     * <p>A released party lets its six bots rejoin the recruitable crowd and be drafted by the next
     * match. Waiting for "all members in a hall" is what keeps the reward-room beat intact: the
     * engine warps a finished team to its reward rooms, and disbanding the moment the match ends
     * would make {@code tickPartyQuest} see "no longer in a party" and send the bots straight home
     * instead of standing by the result with the players.
     */
    private static void releaseIdleTeam(int key) {
        Party team = BOT_PARTIES.get(key);
        if (team == null || RUNNING.containsKey(key) || PENDING.containsKey(key)) {
            return; // no team, a live match owns it, or it is standing in the field waiting
        }
        for (PartyCharacter mpc : team.getPartyMembers()) {
            Character mc = mpc.getPlayer();
            if (mc == null || mc.getParty() != team) {
                continue;
            }
            if (!CarnivalPqData.isHall(mc.getMapId())) {
                return; // still reading the result, or walking itself home
            }
        }
        BOT_PARTIES.remove(key);
        disband(team);
    }

    /**
     * Whether a match that is not ours is running (or being negotiated) on this field.
     *
     * <p>The arena a match actually plays on comes from {@code MapFactory.getDisposableMap}: it is
     * built outside the channel's map cache and never published into it, so the cached map is not
     * that arena and "does the cached arena have players" is always false - which is why this test
     * used to be silently useless.
     *
     * <p>What IS observable is the field lobby, and it carries the engine's own marker for exactly
     * this situation: the NPC's challenge path sets {@code challenged} on the opposing party's
     * leader ({@code challengeParty} / {@code challengeParty2}, and clears it when the match
     * starts), so a party here with a challenged leader means another match is being negotiated on
     * this field and our poll must stay out of it. Our own bot team never sets it.
     */
    /**
     * Whether a match that is not ours is running (or being negotiated) on this field.
     *
     * <p>Only the lobby is consulted, and only for the engine's own negotiation marker:
     * the NPC's challenge path sets {@code challenged} on the opposing party's leader
     * ({@code challengeParty} / {@code challengeParty2}) and clears it when the match starts, so a
     * party standing here with a challenged leader means another match is being negotiated on this
     * field and our poll must stay out of it. Our own bot team never sets it.
     *
     * <p>Deliberately NOT the channel's field registry
     * ({@code canInitMonsterCarnival}): the NPC registers {@code field + 1} there while this class
     * registers the plain {@code field}, so probing either number alone sees only one path - and
     * probing the shifted one makes field N look busy whenever OUR field N+1 has a match. The
     * arena itself cannot be used either: {@code MonsterCarnival} plays on
     * {@code MapFactory.getDisposableMap}, which is never published to the channel's map cache.
     *
     * <p>Consequence, stated rather than hidden: a player-versus-player match that has already
     * STARTED is invisible here. Opening a bot match on the same field during one is prevented by
     * the lobby being empty of un-challenged players when it starts - and if it ever happened, the
     * engine's own {@code fieldTaken} would still refuse a third party entry.
     */
    private static boolean arenaOccupied(Channel channel, boolean cpq2, int field) {
        int lobbyId = cpq2 ? CarnivalPqData.lobbyForField2(field) : CarnivalPqData.lobbyForField(field);
        if (!channel.getMapFactory().isMapLoaded(lobbyId)) {
            return false;
        }
        MapleMap lobby = channel.getMapFactory().getMap(lobbyId);
        if (lobby == null) {
            return false;
        }
        for (Character chr : lobby.getAllPlayers()) {
            if (!soloMapling.ArtificialPlayer.BotHelpers.isBot(chr) && chr.isChallenged()) {
                return true; // the NPC's own negotiation is in flight on this field
            }
        }
        return false;
    }

    /**
     * Whether a field is occupied right now - what the poll and the GM diagnostic both ask.
     *
     * <p>Public so {@code !bot cpqarena} reports the same thing the poll acts on; a diagnostic
     * that answers a different question than the scheduler is worse than none.
     */
    public static boolean fieldOccupied(Channel channel, int field, boolean cpq2) {
        return arenaOccupied(channel, cpq2, field);
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
            // A bot the player recruited is a teammate, not a player: its level was set from the
            // edition's band when it was spawned, so the check is the same one - but the side that
            // gets counted for readiness is the players'.
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
     * Start the match a field's countdown has just finished.
     *
     * <p>The bot team is already standing in the field with the players (it was walked in when the
     * countdown started), so this only has to create the engine's match object. It is created
     * through the same seam a real party uses, so every party-bound structure the arena reads - the
     * member list, the map's party registry, the leader - is populated exactly as it would be for
     * six players. Nothing here talks to an NPC.
     *
     * @return true when the match really started (and is recorded in {@code RUNNING})
     */
    private static boolean openMatch(Channel channel, Party players, Party bots, int field,
                                     boolean cpq2, int key) {
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
                return false;
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
            return false;
        }

        if (bots.getMembers().size() < CarnivalPqData.BOT_TEAM_SIZE) {
            BotLogger.log("CPQ field " + field + ": could not field a full bot team, skipping");
            return false;
        }

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
                return true;
            }
            BotLogger.log("CPQ field " + field + ": the engine refused to start the match");
            COOLDOWN_UNTIL.put(key, System.currentTimeMillis() + FIELD_COOLDOWN_MS);
            // Undo what the constructor already assigned before it failed.
            players.setEnemy(null);
            bots.setEnemy(null);
            releaseFighters(players);
            releaseFighters(bots);
            // The constructor force-warped both sides into the disposable arena BEFORE it gave up,
            // and every portal on an arena map is tm=999999999 - so without this the players would
            // stand in a map nothing will ever end (no clock, no timer, no exit).
            rescuePlayersFromArena(players, channel, field, cpq2);
            return false;
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
            rescuePlayersFromArena(players, channel, field, cpq2);
            channel.finishMonsterCarnival(!cpq2, room);
            return false;
        }
    }

    /**
     * Bring a player party back to the field lobby after a match failed to open.
     *
     * <p>Both failure branches above run AFTER {@code MonsterCarnival}'s constructor has already
     * force-warped every member into the arena (it moves the teams before the work that can
     * refuse). An arena map's portals are all the engine's no-map sentinel, so a party left there
     * cannot walk out and the field's own three-minute lobby timer has been cancelled - they would
     * be stranded until a relog. The field lobby is the map they came from and the one the quest
     * NPC sends them to, so it is the honest place to put them back.
     */
    private static void rescuePlayersFromArena(Party players, Channel channel, int field,
                                               boolean cpq2) {
        MapleMap lobby = channel.getMapFactory().getMap(CarnivalPqData.rescueMap(cpq2, field));
        if (lobby == null) {
            return; // no lobby to return to: dropping the warp is worse than a failed one
        }
        for (PartyCharacter mpc : players.getPartyMembers()) {
            Character mc = mpc.getPlayer();
            if (mc == null || mc.getMap() == null
                    || !CarnivalPqData.isArena(mc.getMap())) {
                continue; // never reached the arena, or already back somewhere real
            }
            try {
                mc.changeMap(lobby, lobby.getPortal(0));
            } catch (RuntimeException e) {
                BotLogger.log("CPQ: could not return " + mc.getName() + " to the lobby (" + e + ")");
            }
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
     * <p><b>A live match must be settled first.</b> {@code MonsterCarnival} settles its result by
     * walking each side's {@code getParty().getMembers()} from its own timer; disbanding the party
     * under it nulls every member's party, so that walk throws NPE, the constructor's timer ends
     * without ever calling {@code dispose()}, and the field stays registered in the channel's room
     * table for the life of the process. {@link #settleMatchIfRunning} runs the engine's own
     * teardown while the parties are still intact.
     *
     * <p>The per-member release runs even when that call throws: a party record the world no
     * longer knows about must not leave its members pointing at it, or every later match finds
     * them "already committed" and never drafts them again.
     */
    private static void disband(Party party) {
        settleMatchIfRunning(party);
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
     * Run the engine's own end-of-match teardown for this party, if it is in one.
     *
     * <p>{@code MonsterCarnival.exit()} is {@code dispose(false)}: it resets every member's
     * team/CP, disposes the disposable arena map, and — the part nothing else can do — removes the
     * field from the channel's {@code usedMC} registry via {@code finishMonsterCarnival}. Without
     * that call the NPC's own entry path keeps seeing the room as taken, and a plugin unload
     * during a live match leaves a permanently busy field plus a leaked arena instance.
     *
     * <p><b>Everyone must leave the arena BEFORE the dispose.</b> {@code dispose(false)} deletes
     * the map's footholds and portals but does NOT warp anybody out (the {@code warpout=true} form
     * is only reachable through the disconnect path), and it nulls every member's match pointer -
     * so a character still standing there afterwards cannot walk, cannot be found by a later
     * settle, and is stuck on a dead map until a relog. Getting them out first also matches what
     * the engine's own end-of-run does for a timed-out match, which sends both sides to the
     * holding map.
     *
     * <p>Runs before the party is dissolved, and resolves the match once per team: a team shares
     * one {@code MonsterCarnival}, so the first member that has it settles everybody.
     */
    private static void settleMatchIfRunning(Party party) {
        if (party == null || party.getEnemy() == null) {
            return; // never paired: nothing was opened for this party
        }
        MonsterCarnival run = null;
        for (PartyCharacter mpc : new ArrayList<>(party.getPartyMembers())) {
            Character mc = mpc.getPlayer();
            if (mc != null && mc.getMonsterCarnival() != null) {
                run = mc.getMonsterCarnival();
                break;
            }
        }
        if (run == null) {
            return; // the match already ended on its own - nothing left to settle
        }
        // Out of the arena first: dispose() invalidates the map under them, and its
        // warpout=false form does not move anybody. BOTH sides must go, not just this party:
        // dispose() resets every member of both teams, so an opponent left standing in the
        // arena would be stranded on the same dead map.
        //
        // The destination is the engine's own "out" map for a disposed match (dispose(true)
        // uses exactly these two ids), NOT the arena's returnMap: that points at the field's
        // holding map, which itself returns to the arena - anything parked there is pulled
        // straight back into a map whose footholds are gone.
        Party enemy = party.getEnemy();
        for (Party team : partiesToEvacuate(party)) {
            evacuateFromArena(team);
        }
        try {
            run.exit();
        } catch (RuntimeException e) {
            BotLogger.log("CPQ: could not settle the match (" + e + ")");
            releaseMatchPointers(party, enemy);
        }
    }

    /**
     * The parties a settled run owns: this one and its opponent.
     *
     * <p>A {@code MonsterCarnival} is shared by two parties, and {@code dispose()} resets every
     * member of BOTH - so pulling only the party we happen to be disbanding out of the arena would
     * leave the opponent standing on the dead map. Kept as a pure function because the pair is
     * the part worth stating: one team is not "the match".
     */
    static List<Party> partiesToEvacuate(Party self) {
        if (self == null) {
            return List.of();
        }
        Party enemy = self.getEnemy();
        return enemy == null || enemy == self ? List.of(self) : List.of(self, enemy);
    }

    /** Move every member of {@code team} that is still standing in an arena to the out map. */
    private static void evacuateFromArena(Party team) {
        for (PartyCharacter mpc : new ArrayList<>(team.getPartyMembers())) {
            Character mc = mpc.getPlayer();
            if (mc == null || mc.getMap() == null || !CarnivalPqData.isArena(mc.getMap())) {
                continue;
            }
            try {
                mc.changeMap(CarnivalPqData.outMapFor(mc.getMapId()));
            } catch (RuntimeException e) {
                BotLogger.log("CPQ: could not evacuate " + mc.getName() + " (" + e + ")");
            }
        }
    }

    /** Last-resort reset when the engine's own dispose threw before it could clear the teams. */
    private static void releaseMatchPointers(Party... teams) {
        for (Party team : teams) {
            if (team == null) {
                continue;
            }
            for (PartyCharacter mpc : new ArrayList<>(team.getPartyMembers())) {
                Character mc = mpc.getPlayer();
                if (mc != null) {
                    mc.setMonsterCarnival(null);
                    mc.setTeam(-1);
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
                // Already committed: a carnival team on a field, or a player's party that
                // recruited it in the hall. Both cases mean this body is busy elsewhere.
                continue;
            }
            if (!CarnivalPqData.isCarnivalMap(chr.getMapId())) {
                continue; // walked off (a GM warp, a stray script): not standing where it fights
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
     *
     * <p>The bots are spawned into the <b>hall</b>, never the field lobby. A freshly created
     * carnival bot has no party yet, and {@code CarnivalPQBot.lobbyMapId} only treats a field
     * lobby as home while the bot belongs to a party waiting there - so a bot spawned in the
     * field immediately reclaims itself to the hall on its first tick, and the field would wait
     * three more polls for the topped-up body it had already been given. The draft walks the
     * whole channel's storage, so a hall body is picked up just as well.
     */
    private static void requestTeamSpawn(Channel channel, int field, boolean cpq2, int want) {
        int key = fieldKey(channel, cpq2, field);
        if (SPAWNING.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        int hallId = CarnivalPqData.topUpSpawnMap(cpq2);
        int channelId = channel.getId();
        soloMapling.server.ExecutorServiceManager.runAsync(() -> {
            try {
                soloMapling.ArtificialPlayer.PartyQuest.PqBotSpawner
                        .spawnCarnivalTeam(want, hallId, cpq2, channelId);
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
