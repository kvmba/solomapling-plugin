package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorate;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.Carnival.CarnivalPqData;
import soloMapling.Environment.PlatformPlacement;

import java.util.List;
import java.util.Random;

/**
 * Puts quest bots where a player can actually recruit them.
 *
 * <p>The quests' eligibility checks are strict in two ways that a bot has to satisfy to be of
 * any use: it must be standing in the quest's recruit map, and its level must be inside the
 * quest's band. A bot that fails either is invisible to {@code getEligibleParty} when the leader
 * starts the run - which is why placing bots anywhere else, or at any level, amounts to not
 * placing them at all.
 *
 * <p>So each quest is spawned into its own lobby at a level drawn from its own band, in numbers
 * that fill a party. The counts come from the quest's {@code maxPlayers}, since quests generally
 * take the whole party in.
 *
 * <p>The mechanism is the same one the long-standing Orbis spawner uses - platform placement,
 * then a level assignment, then bot-type conversion - kept general so each quest is a row in
 * {@link PqRecruitPoints} rather than another method here.
 */
public final class PqBotSpawner {

    /** Levels are picked per bot, so the lobby does not look stamped out. */
    private static final Random RANDOM = new Random();

    /**
     * How many parties' worth of bots each lobby holds. Matches the density the Orbis lobby has
     * had all along (ten to fifteen in a room sized for five), so the quest lobbies added here
     * do not stand out as emptier than the one that already existed.
     */
    private static final int BOTS_PER_PARTY_MULTIPLE = 3;

    /**
     * The same scaling the rest of the ambient population goes through, so a server set to run a
     * lighter population runs lighter quest lobbies too rather than a fixed crowd that ignores
     * the setting. Delegated rather than reimplemented, so the two cannot drift apart.
     */
    private static int scaledAmbient(int base) {
        return soloMapling.Environment.EnvironmentManager.scaledAmbient(base);
    }

    private PqBotSpawner() {
    }

    /**
     * Spawn bots for one quest, in its own lobby and level band.
     *
     * @param point the quest's recruiting requirements
     * @param type  which bot type to convert them to
     * @return the ids of the bots that were created, empty when the map or platforms were missing
     */
    public static List<Integer> spawnFor(PqRecruitPoints.Point point,
                                         BotTypeManager.BotType type) {
        if (point == null || type == null) {
            return List.of();
        }

        // Three times what one player would need. A lobby wants a crowd the way Orbis's does,
        // not exactly enough to start: a handful of bots that matches the party size to the
        // person reads as a set of placeholders, and a player choosing a party wants spare
        // bodies to pick from. The multiple is applied to the party-filling count, so each
        // quest still offers at least one full party's worth.
        int want = point.botsToOffer(1) * BOTS_PER_PARTY_MULTIPLE;
        want = scaledAmbient(want);
        if (want <= 0) {
            return List.of();
        }

        // Placed by the map's own WZ terrain, not a recorded platform pack: only Orbis's lobby
        // ever shipped one, so the other quests' lobbies had no way to place a bot at all.
        List<Integer> all = PlatformPlacement.spawnBotsOnMap(want, point.recruitMap());
        if (all.isEmpty()) {
            return all;
        }

        // Level before conversion: the quest's eligibility test reads level at start time, and
        // a bot converted first would have a tick's worth of running at the wrong level.
        levelRange(all, point.minLevel(), point.maxLevel());
        BotTypeManager.setAndStartBots(all, type);
        return all;
    }

    /**
     * How many full teams' worth of carnival bots each hall keeps.
     *
     * <p>The hall is a recruit lobby first: a player walks in with a party of any size and needs
     * bots to invite, exactly like every other quest lobby. Four teams (24 bots) still reads as a
     * crowd rather than a matched set - the same "several parties' worth" the other quest lobbies
     * use - and leaves spare bodies while a team is out fighting a field. Six teams read as too
     * many bodies in one room; the arena tops the crowd up on demand when more fields need teams.
     */
    private static final int HALL_TEAMS = 4;

    /** Guards the warm start: {@code !env population reload} re-runs the whole wave plan. */
    private static final java.util.concurrent.atomic.AtomicBoolean CARNIVAL_HALLS_SEEDED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Warm-start the carnival: a crowd in each edition's hall, on every channel.
     *
     * <p>Two jobs, and the crowd serves both. In the hall the bots are <b>recruitable
     * teammates</b> for a player party that is short of six. The arena also drafts its opponent
     * team from these same bots, on demand, when a field actually has a player party waiting (see
     * {@code CarnivalArena}) - which is when they read as "the other team showed up".
     *
     * <p>Every channel gets its own crowd because a field, and the draft that staffs it, belong to
     * one channel: a player on channel 3 walking into field 0 must find bots there just like one
     * on channel 1.
     *
     * <p>Seeded once per process. The whole wave plan can be re-run ({@code !env population
     * reload}), and unlike the training cohorts - which self-discover field maps and spread out -
     * a hall crowd that is added a second time simply doubles in place. The arena tops the crowd
     * back up on demand, so one seeding is enough.
     */
    public static void spawnCarnivalTeams() {
        // No teams to field if the server has the carnival switched off; the NPC gates on the same
        // flag and the arena's poll returns immediately.
        if (!org.gms.config.GameConfig.getServerBoolean("use_cpq")) {
            return;
        }
        if (!CARNIVAL_HALLS_SEEDED.compareAndSet(false, true)) {
            return;
        }
        int want = scaledAmbient(CarnivalPqData.BOT_TEAM_SIZE * HALL_TEAMS);
        var world = org.gms.net.server.Server.getInstance()
                .getWorld(soloMapling.server.SoloMaplingConstants.GameConstants.WORLD_SCANIA);
        if (world == null) {
            CARNIVAL_HALLS_SEEDED.set(false); // nothing was placed; let a later call try
            return;
        }
        // Every channel that is up, by its own id: a channel that cannot take the spawn reports
        // it and the next one is still tried.
        for (var channel : world.getChannels()) {
            spawnCarnivalTeam(want, CarnivalPqData.LOBBY_MAP, false, channel.getId());
            spawnCarnivalTeam(want, CarnivalPqData.LOBBY_MAP_CPQ2, true, channel.getId());
        }
    }

    /**
     * Put {@code want} carnival bots in a map and convert them to the carnival brain.
     *
     * <p>Kept here rather than in the arena because it is the spawner that owns platform
     * placement and level assignment; the arena only ever asks for bodies and gets ids back.
     *
     * @param want     how many bots to add
     * @param lobbyId  the map to place them on (a hall, or a field lobby being topped up)
     * @param cpq2     whether the second edition's level band applies
     * @param channel  the channel the bots must live on; 0 lets the router choose
     * @return the ids that were created, empty when the map or platforms were missing
     */
    public static List<Integer> spawnCarnivalTeam(int want, int lobbyId, boolean cpq2, int channel) {
        if (want <= 0 || lobbyId <= 0) {
            return List.of();
        }
        // Level BEFORE conversion, exactly as spawnFor does: the arena reads the level when it
        // validates the field's parties, and a bot converted first runs a tick at the wrong one.
        int low = cpq2 ? CarnivalPqData.CPQ2_MIN_LEVEL : CarnivalPqData.CPQ1_MIN_LEVEL;
        int high = cpq2 ? CarnivalPqData.CPQ2_MAX_LEVEL : CarnivalPqData.CPQ1_MAX_LEVEL;
        // A field lobby only ever holds one team (the arena's); a hall holds a crowd.
        int budget = CarnivalPqData.isHall(lobbyId)
                ? want
                : Math.max(1, Math.min(want, CarnivalPqData.BOT_TEAM_SIZE));

        // Pinned to the channel: the arena drafts bots out of that channel's storage, so one
        // routed elsewhere by the capacity fallback would never be seen.
        List<Integer> ids = PlatformPlacement.spawnBotsOnMap(budget, lobbyId, channel);
        if (ids.isEmpty()) {
            return ids;
        }
        levelRange(ids, low, Math.min(high, low + 9));
        BotTypeManager.setAndStartBots(ids, BotTypeManager.BotType.CARNIVAL_PQ_BOT);
        return ids;
    }

    /** One field lobby's worth of carnival bots, on any channel. */
    public static List<Integer> spawnCarnivalTeam(int want, int lobbyId, boolean cpq2) {
        return spawnCarnivalTeam(want, lobbyId, cpq2, 0);
    }

    /**
     * Arm the hall seeding again (plugin unload/reload).
     *
     * <p>The crowd flag is process-wide state, and the plugin can be unloaded and loaded again in
     * the same process: without this reset the second load would find the flag set and skip the
     * seeding entirely, leaving a hall that has no bots to recruit.
     */
    public static void resetCarnivalHallsSeeded() {
        CARNIVAL_HALLS_SEEDED.set(false);
    }

    /**
     * Spawn a bot party in every quest's recruit lobby.
     *
     * <p>One call for the whole table, so adding a quest to the plan is a row there rather than
     * a new spawn method here. A quest whose lobby has no walkable ground, or whose bot type is
     * missing, is skipped rather than placed somewhere it cannot be recruited.
     */
    public static void spawnAllQuestLobbies() {
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            BotTypeManager.BotType type = botTypeFor(point.name());
            if (type == null) {
                continue; // no bot written for this quest yet
            }
            List<Integer> ids = spawnFor(point, type);
            if (!ids.isEmpty()) {
                soloMapling.BotLogger.log("PQ lobby: placed " + ids.size() + " "
                        + point.name() + " bots in map " + point.recruitMap()
                        + " at levels " + point.minLevel() + "+");
            }
        }
    }

    /**
     * Which bot type plays a quest, by the quest's event name.
     *
     * <p>Kept as a lookup rather than a field on the table so the recruit point stays purely
     * about where and who can be recruited - a quest can gain a bot, or lose one, without the
     * eligibility data being touched.
     */
    private static BotTypeManager.BotType botTypeFor(String questName) {
        return switch (questName) {
            case "HenesysPQ" -> BotTypeManager.BotType.HENESYS_PQ_BOT;
            case "KerningPQ" -> BotTypeManager.BotType.KERNING_PQ_BOT;
            case "LudiPQ" -> BotTypeManager.BotType.LUDI_PQ_BOT;
            case "PiratePQ" -> BotTypeManager.BotType.PIRATE_PQ_BOT;
            case "AmoriaPQ" -> BotTypeManager.BotType.AMORIA_PQ_BOT;
            case "EllinPQ" -> BotTypeManager.BotType.ELLIN_PQ_BOT;
            // Both Magatia versions are played by the same bot; the town it was spawned in decides
            // which lobby it returns to, so the Z row just needs the same type.
            case "MagatiaPQ", "MagatiaPQ_Z" -> BotTypeManager.BotType.MAGATIA_PQ_BOT;
            case "ZakumPQ" -> BotTypeManager.BotType.ZAKUM_PQ_BOT;
            case "HorntailPQ" -> BotTypeManager.BotType.HORNTAIL_PQ_BOT;
            case "BossRushPQ" -> BotTypeManager.BotType.BOSS_RUSH_PQ_BOT;
            case "Pyramid" -> BotTypeManager.BotType.PYRAMID_PQ_BOT;
            case "Dojo" -> BotTypeManager.BotType.DOJO_PQ_BOT;
            case "AriantPQ" -> BotTypeManager.BotType.ARIANT_PQ_BOT;
            case "PapulatusBattle" -> BotTypeManager.BotType.PAPULATUS_PQ_BOT;
            default -> null;
        };
    }

    /**
     * Put every bot's level inside the quest's band - and its job back in line with that level.
     *
     * <p>Drawn from the band's low end upward rather than at random across the whole thing: the
     * high ends run to 255, and a lobby of level-250 bots standing in Henesys would look wrong
     * even though it is legal.
     *
     * <p>The job is re-rolled for the final level rather than kept from the spawn. A bot is
     * decorated at the spawner's 10..80 default band, so a quest asking for 35-50 can pin a
     * level-78 job (a "Chief Bandit", a "Hero") onto a level-38 character. Skill use is already
     * gated by level (BotSkillLevelGate), but the LOOK and the damage tier are not - and a lobby
     * of level-38 三转 bots standing next to a real 38-level player is exactly as wrong as the
     * damage was. The base class is preserved (build the same class family - a warrior stays a
     * warrior), only the advancement is re-derived from the level that was just drawn.
     *
     * <p>Re-rolling restyles the character, so the dress, weapon, title and level-derived detail
     * data all have to follow: see {@link BotDecorate#alignCareerToLevel}.
     */
    private static void levelRange(List<Integer> botIds, int minLevel, int maxLevel) {
        int low = Math.max(1, minLevel);
        // A band a player would plausibly see: the bottom third of it, or at least ten levels of
        // room, so a handful of bots do not all land on the same level.
        int high = Math.min(maxLevel, low + Math.max(9, (maxLevel - low) / 3));
        if (high < low) {
            high = low;
        }
        for (int botId : botIds) {
            Character bot = BotHelpers.getCharFromChannelStorage(botId);
            if (bot == null) {
                continue;
            }
            bot.setLevel(low + RANDOM.nextInt((high - low) + 1));
            BotDecorate.alignCareerToLevel(bot);
        }
    }
}
