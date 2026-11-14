package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.gms.constants.id.MapId;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotLogic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Monster Carnival (CPQ) as the engine lays it out, for a bot playing the opposing team.
 *
 * <p>This is the one content type here that is not cooperative. Two parties are pitted against
 * each other ({@code p1.setEnemy(p2)}), each summons monsters into the shared arena to earn
 * Carnival Points, and the side with more wins. A bot "playing" it is therefore not helping a
 * player - it is standing in for the other team, and the useful thing for it to do is fight
 * like a team of its own level would.
 *
 * <p>The two versions differ only in level band and in which maps they use: CPQ1 for levels
 * 30-50 across {@code 9800001xx}, CPQ2 for 51-70 across {@code 980030xxx} and
 * {@code 980031-33xxx}. Both are recognised by the engine's own predicates, which is what the
 * bot uses rather than a list of its own.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code org/gms/server/partyquest/MonsterCarnival.java} - the run itself, the enemy
 *       pairing, the red/blue split and the CP counters</li>
 *   <li>{@code org/gms/server/partyquest/MonsterCarnivalParty.java} - {@code summons}, the
 *       eight monsters each side may call, and {@code canSummon()}</li>
 *   <li>{@code org/gms/server/maps/MapleMap.java} - {@code isCPQMap()} / {@code isCPQMap2()},
 *       which the bot reuses instead of hard-coding map ids</li>
 *   <li>{@code Character.getCP()} / {@code gainCP(int)} - the score, awarded by the engine on
 *       each monster killed</li>
 * </ul>
 */
public final class CarnivalPqData {

    private CarnivalPqData() {
    }

    /** Levels the two versions accept, from {@code NPCConversationManager.isCPQParty}. */
    public static final int CPQ1_MIN_LEVEL = 30;
    public static final int CPQ1_MAX_LEVEL = 50;
    public static final int CPQ2_MIN_LEVEL = 51;
    public static final int CPQ2_MAX_LEVEL = 70;

    /**
     * Where the party waits before a match, and where both sides are sent afterwards.
     *
     * <p>Deliberately no "summons per run" or "run length" constant here: both belong to the
     * arena map, which declares its own {@code mobGenMax} and {@code timeDefault}. A copy in the
     * plugin would be a second source of truth that drifts the moment a map is retuned.
     */
    public static final int LOBBY_MAP = 980000000;
    public static final int LOBBY_MAP_CPQ2 = 980030000;

    /**
     * The consume-on-pickup props the arena's monsters drop: the CPQ counterpart of LPQ's passes.
     *
     * <p>Every one of them is a drop-table row on all twenty arena monsters and carries both
     * {@code info/mcType} and {@code spec/consumeOnPickup=1} in WZ, so the engine consumes it the
     * moment it is picked up. What it grants is the point of the fight: the three Carnival Point
     * packs pay CP straight into the match budget ({@code spec/cp}), the four party items heal the
     * team, the two cubes and the stunner sicken the opposing side ({@code spec/nuffSkill}), and the
     * five potions restore HP/MP. A bot that walks past them fights with half a kit while the
     * player beside it does not, which is what the CPQ loot report describes.
     *
     * <p>Listed by id rather than derived from WZ on purpose: this is the set the quest's own
     * arena produces, and the base class's sweep contract is an explicit id list so a quest can
     * never eat another mechanic's floor item (HPQ seeds, OPQ springs). Deliberately NOT the
     * arena's other drops - equips, scrolls and Maple Coins are ordinary loot, not this quest's
     * props, and are not what the report is about. MESO is the one opt-in beyond the props:
     * the arena's monsters carry meso rows too, and a Chief Bandit's Pickpocket bags live and
     * die by being left on the floor - the shared sweep refuses a bag registered for detonation
     * (see {@code PqActions.canCollectFloorDrop}), so this entry opens the door for coins only,
     * never for the bomb's fuel.
     */
    public static final int[] ARENA_CONSUMABLES = {
            BotLogic.MESO_DROP_ID,                             // meso rows on the arena's drop tables
            2022157, 2022158, 2022159,                         // Carnival Point 1-3 (CP on pickup)
            2022160, 2022161, 2022162, 2022163,                // Party Mana/Elixir/Power All-Cure
            2022164, 2022165, 2022166,                         // Mini Cube / Cube of Darkness / Stunner
            2022174, 2022175, 2022176, 2022177, 2022178,       // White Potion / Elixir / Power Elixir / Mana / All-Cure
    };

    /**
     * Where a team is parked after its match is disposed.
     *
     * <p>These are the two ids {@code MonsterCarnival.dispose(true)} warps to (one per edition),
     * and each has a NPC that offers the way back to the hall. Deliberately NOT the arena's own
     * {@code returnMap}: that points at the field's holding map, which returns to the arena, so
     * anything parked there is pulled straight back into a map whose footholds may already be gone.
     */
    public static final int MATCH_OUT_MAP = 980000010;
    public static final int MATCH_OUT_MAP_CPQ2 = 980030010;

    /**
     * The field-lobby map a party is returned to when a start fails, by edition and field.
     *
     * <p>Deliberately a FIELD lobby (a party's waiting room), not the arena's returnMap: the arena
     * returns to its holding map, and the holding map returns to the arena - a party parked there
     * would be walked straight back into the map that just failed to open. The party's OWN field
     * is used when it is known, so a group that queued for field 3 lands back in field 3's lobby
     * rather than someone else's.
     */
    public static int rescueMap(boolean cpq2, int field) {
        return cpq2 ? lobbyForField2(field) : lobbyForField(field);
    }

    /**
     * Where a short field's replacement bots are spawned: that edition's hall.
     *
     * <p>A freshly created bot has no party yet, and {@code CarnivalPQBot.lobbyMapId} only treats
     * a field lobby as home while the bot belongs to a party waiting there. Spawning a top-up in
     * the field lobby therefore made it reclaim itself to the hall on its first tick, and the
     * field kept waiting for bodies it had already been given. The draft scans the whole channel's
     * storage, so a hall body is picked up exactly like a field one.
     */
    public static int topUpSpawnMap(boolean cpq2) {
        return cpq2 ? LOBBY_MAP_CPQ2 : LOBBY_MAP;
    }

    /** The post-match holding map of the edition {@code arenaOrOutMapId} belongs to. */
    public static int outMapFor(int arenaOrOutMapId) {
        return isSecondEdition(arenaOrOutMapId) ? MATCH_OUT_MAP_CPQ2 : MATCH_OUT_MAP;
    }

    /**
     * The spawn doors of a carnival map, in portal-id order.
     *
     * <p>A "spawn door" is {@code pt=0} with the engine's no-map target - the set
     * {@code MapleMap.getRandomPlayerSpawnpoint} draws from, narrowed to type 0 because the type-1
     * entries on an arena ({@code blue00}, {@code red00}, {@code *_revive}) are the teams' own side
     * doors: landing a bot on one puts it on its opponent's half of the field.
     *
     * <p>The maps this quest warps into carry several of these where it matters most - a hall has
     * three, a CPQ1 field lobby five (CPQ2's six), the CPQ1 holding map nine - which is what makes
     * them usable for spreading a party out. An <b>arena</b> is the exception: it ships a single
     * type-0 door ({@code sp}), so a team warped straight in has nothing to spread over and the
     * host's own entry would put it there anyway. Stage exits ({@code st00}), holding-map doors
     * ({@code out00}) and {@code pt=7} pads are deliberately excluded: they lead somewhere, so a
     * warp onto one is not an arrival.
     */
    public static List<Portal> spawnDoors(Collection<Portal> portals) {
        if (portals == null || portals.isEmpty()) {
            return List.of();
        }
        List<Portal> doors = new ArrayList<>();
        for (Portal portal : portals) {
            if (portal != null && portal.getType() == 0 && portal.getTargetMapId() == MapId.NONE) {
                doors.add(portal);
            }
        }
        doors.sort(Comparator.comparingInt(Portal::getId));
        return doors;
    }

    /**
     * The spawn door {@code slot} arrives on, or null when the map ships none.
     *
     * <p>Round-robin on purpose: a party of six warped in one tick must not all stand on the same
     * pixel, and modulo gives each member a different door for as long as the party is no larger
     * than the door count - a random pick would still collide about half the time at three doors.
     * A caller with no party slot to speak of (one bot following its leader home) passes a stable
     * per-bot number, which scatters the crowd the same way without coordination.
     *
     * <p>Takes the already-filtered list so a caller warping a whole team computes it once rather
     * than re-filtering and re-sorting per member.
     */
    public static Portal doorAt(List<Portal> doors, int slot) {
        return doors == null || doors.isEmpty()
                ? null : doors.get(Math.floorMod(slot, doors.size()));
    }

    /**
     * Whether a map is an arena rather than a lobby, using the engine's own test so a map
     * added to either version later is picked up without an edit here.
     */
    public static boolean isArena(org.gms.server.maps.MapleMap map) {
        return map != null && (map.isCPQMap() || map.isCPQMap2());
    }

    /** Which lobby a party of this level would be sent to. */
    public static int lobbyForLevel(int level) {
        return level >= CPQ2_MIN_LEVEL ? LOBBY_MAP_CPQ2 : LOBBY_MAP;
    }

    /** Whether a level can enter either version at all. */
    public static boolean levelEligible(int level) {
        return (level >= CPQ1_MIN_LEVEL && level <= CPQ1_MAX_LEVEL)
                || (level >= CPQ2_MIN_LEVEL && level <= CPQ2_MAX_LEVEL);
    }

    /**
     * The largest side a carnival match can hold.
     *
     * <p>Six is the game's own party cap, and {@code Party.joinParty} refuses a seventh member, so
     * no side the arena builds can be the reason a match is refused. It bounds the hall crowd
     * sizing and the player party the arena accepts; the opposing bot team is sized by
     * {@link #botTeamSizeFor(int)} instead.
     */
    public static final int MAX_TEAM_SIZE = 6;

    /**
     * The bot team a player party faces: one body fewer than the player side, never fewer than one.
     *
     * <p>The opponent used to be a full six every time, which made a two-player party fight a side
     * three times its size - the reported "the bot team is always full". This is the arena's own
     * sizing rule, not the quest NPC's: {@code cpqchallenge.js} only lets two challenges through
     * when both parties have the same member count, which a bot team can never count on here. The
     * engine's {@code MonsterCarnival} requires neither side to be empty and nothing more, so the
     * arena is free to field the smaller side the players are told to expect: one fewer bot, and a
     * lone player still gets an opponent.
     *
     * <p>The count is the whole party roster, not just its human members: a bot recruited in the
     * hall is a teammate, so it counts toward the side the opposing team is sized against.
     */
    public static int botTeamSizeFor(int playerPartySize) {
        return Math.max(1, playerPartySize - 1);
    }

    /**
     * How many fields each edition runs.
     *
     * <p>Six for CPQ1 ({@code 2042001.js} lists {@code i < 6}) and three for CPQ2
     * ({@code sendCPQMapLists2} loops {@code i < 3}). Kept apart because a single "six" would
     * have the poll look for three CPQ2 fields that do not ship - opening on those ids would warp
     * a party into a map that is not an arena.
     */
    public static final int FIELD_COUNT = 6;
    public static final int FIELD_COUNT_CPQ2 = 3;

    /** How many fields an edition runs. */
    public static int fieldCount(boolean cpq2) {
        return cpq2 ? FIELD_COUNT_CPQ2 : FIELD_COUNT;
    }

    /** The lobby a field's teams register in: {@code 980000100 + 100*field}. */
    public static int lobbyForField(int field) {
        return 980000100 + 100 * field;
    }

    /** The arena a field's match is played in: the lobby map plus one. */
    public static int arenaForField(int field) {
        return lobbyForField(field) + 1;
    }

    /**
     * The CPQ2 equivalent of {@link #lobbyForField}: {@code 980031000 + 1000*field}.
     *
     * <p>CPQ2's fields are spaced by a thousand, not a hundred (the arena sits at
     * {@code +100}, the winner and loser rooms at {@code +300}/{@code +400} - see
     * {@code MonsterCarnival.finish}, which warps to {@code arena + 200} / {@code arena + 300}),
     * which is why this is a separate function rather than an offset on the CPQ1 one.
     */
    public static int lobbyForField2(int field) {
        return 980031000 + 1000 * field;
    }

    /** The CPQ2 arena for a field. */
    public static int arenaForField2(int field) {
        return lobbyForField2(field) + 100;
    }

    /** Which field a CPQ lobby map belongs to, or -1 when it is not one. */
    public static int fieldOfLobby(int mapId) {
        if (mapId >= 980000100 && mapId <= 980000600 && (mapId - 980000100) % 100 == 0) {
            return (mapId - 980000100) / 100;
        }
        // CPQ2 ships three fields at 980031xxx / 980032xxx / 980033xxx, a thousand apart.
        if (mapId >= 980031000 && mapId <= 980033000 && (mapId - 980031000) % 1000 == 0) {
            return (mapId - 980031000) / 1000;
        }
        return -1;
    }

    /**
     * How long the field's ten-second count runs before the arena opens.
     *
     * <p>The host's number, read off both entry paths: {@code NPCConversationManager.startCPQ}
     * broadcasts {@code getClock(10)} and builds the {@code MonsterCarnival} at +11s, and
     * {@code startCPQ2} does the same with the clock at +0 and the build at +10s. The count the
     * player actually sees is ten seconds either way, so that is the count this class reproduces;
     * the arena opens when it reaches zero.
     */
    public static final long MATCH_WAIT_MS = 10_000L;

    /**
     * The host's own delay between arming the count and putting the clock on screen.
     *
     * <p>Both host entry paths show their field clock on a timer, not inline: {@code cpqLobby}
     * sends its three-minute clock 1.5 seconds after the party warps in, and {@code startCPQ} sends
     * the ten-second one 1.5 seconds after the challenge is accepted. The delay is load-bearing
     * here, not cosmetic: that three-minute clock is a one-shot {@code cpqLobby} never keeps a
     * handle on, so it cannot be cancelled, and a ten-second clock sent before it would simply be
     * replaced on every client that had just walked in. One poll tick of margin past the host's
     * 1.5s makes the order deterministic whichever way the two timers happen to race.
     */
    public static final long CLOCK_DELAY_MS = 2_000L;

    /** The countdown value, in seconds, that the field's client clock shows. */
    public static int matchWaitSeconds() {
        return (int) (MATCH_WAIT_MS / 1000L);
    }

    /**
     * Whether a field is unavailable because a match is already on it.
     *
     * <p>Deliberately only two facts: our own match record, and whether the arena instance holds
     * anybody. The <b>lobby</b> must never count - a player party standing in it is the thing
     * this whole poll is waiting for, and treating its presence as occupancy is exactly how the
     * match failed to open at all.
     */
    public static boolean fieldBusy(boolean ownMatch, boolean arenaOccupied) {
        return ownMatch || arenaOccupied;
    }

    /**
     * The NPC a finished match's reward room offers as the way out, or -1 when the room has none.
     *
     * <p>CPQ1 ships 2042002 in all twelve rooms. CPQ2 puts 2042007 in field 0's pair only: its
     * other fields' rooms are empty maps, so a bot there has nothing to walk up to and leaves for
     * the hall after reading the result.
     */
    public static int rewardNpcId(int mapId) {
        if (!isRewardRoomId(mapId)) {
            return -1;
        }
        if (!isSecondEdition(mapId)) {
            return 2042002;
        }
        return mapId == 980031300 || mapId == 980031400 ? 2042007 : -1;
    }

    /** Whether a map id is one of the post-match rooms of either edition. */
    public static boolean isRewardRoomId(int mapId) {
        // CPQ1: winner/loser are lobby + 3 / + 4 (arena sits at lobby + 1, and the engine's
        // finish() jumps arena + 2/+3). CPQ2: arena is lobby + 100, so the rooms are +300/+400.
        for (int field = 0; field < FIELD_COUNT; field++) {
            int lobby = lobbyForField(field);
            if (mapId == lobby + 3 || mapId == lobby + 4) {
                return true;
            }
        }
        for (int field = 0; field < FIELD_COUNT_CPQ2; field++) {
            int lobby = lobbyForField2(field);
            if (mapId == lobby + 300 || mapId == lobby + 400) {
                return true;
            }
        }
        return false;
    }

    /** Whether a map is one of the halls a party waits in before it picks a field. */
    public static boolean isHall(int mapId) {
        return mapId == LOBBY_MAP || mapId == LOBBY_MAP_CPQ2;
    }

    /** Whether a map belongs to the carnival at all: either hall, a field lobby, an arena, a room. */
    public static boolean isCarnivalMap(int mapId) {
        return isHall(mapId)
                || fieldOfLobby(mapId) >= 0
                || isArenaId(mapId)
                || isRewardRoomId(mapId);
    }

    /** Whether a map id is one of the arenas either edition ships. */
    public static boolean isArenaId(int mapId) {
        for (int field = 0; field < FIELD_COUNT; field++) {
            if (mapId == arenaForField(field)) {
                return true;
            }
        }
        for (int field = 0; field < FIELD_COUNT_CPQ2; field++) {
            if (mapId == arenaForField2(field)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a map belongs to the carnival's second edition.
     *
     * <p>Its maps run from the CPQ2 hall ({@code 980030000}) through the third field's loser room
     * ({@code 980033400}); the upper bound is the last map the edition actually ships, not a
     * generous round number, so a future map outside the range is not silently treated as CPQ2.
     */
    public static boolean isSecondEdition(int mapId) {
        return mapId >= 980030000 && mapId <= 980033400;
    }

    /**
     * Whether a map is one of the two post-match rooms.
     *
     * <p>Both editions park the finished teams in a pair of town-like rooms next to the arena: the
     * engine's {@code finish()} uses {@code arena + 1} and {@code arena + 2} (i.e. {@code +2} and
     * {@code +3} off the lobby) for CPQ1, {@code +200}/{@code +300} for CPQ2, while the map
     * predicates name the opposite rooms as "winner"/"loser" - the host disagrees with itself
     * there, so both are accepted and the distinction is never relied on. What matters for a bot is
     * only that it is not still on the field: it reads the result for a beat and walks itself home.
     *
     * <p>Both rooms have a carnival NPC that warps back to the hall, which is why a bot standing
     * in one must not be treated as having quit the run.
     */
    public static boolean isRewardMap(org.gms.server.maps.MapleMap map) {
        if (map == null) {
            return false;
        }
        return map.isCPQWinnerMap() || map.isCPQLoserMap();
    }

    /**
     * The monsters a side may call, and what each costs in Carnival Points.
     *
     * <p>Not a fixed list: the arena map declares its own, WZ-side
     * ({@code MapFactory} reads {@code mobGenMax} and the per-mob {@code spendCP} from the
     * map), and the client's summon request is an index into that list. So the bot asks the
     * map rather than carrying ids it would have to keep in step.
     */
    public static List<org.gms.util.Pair<Integer, Integer>> summonableOn(
            org.gms.server.maps.MapleMap map) {
        return map == null ? List.of() : map.getMobsToSpawn();
    }

    /**
     * The debuff a side may cast, likewise declared by the map rather than known here.
     */
    public static List<Integer> debuffsOn(org.gms.server.maps.MapleMap map) {
        return map == null ? List.of() : map.getSkillIds();
    }

    /**
     * Whether a summon is affordable and still allowed.
     *
     * <p>The engine checks the same two things in {@code MonsterCarnivalHandler} - CP against
     * the entry's cost, and the side's remaining summon allowance - so a bot that asks
     * anything else will send a request the server rejects.
     */
    public static boolean canSummon(int availableCp, int cost, boolean sideHasSummonsLeft) {
        return sideHasSummonsLeft && cost > 0 && availableCp >= cost;
    }
}
