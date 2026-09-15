package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.Environment.PlatformPlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static soloMapling.Environment.PlatformPlacement.getMainPlatformIds;

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
        List<String> platforms = getMainPlatformIds(point.recruitMap());
        if (platforms.isEmpty()) {
            // A map with no walkable platforms is a data gap, not a reason to place bots badly.
            return List.of();
        }

        int want = point.botsToOffer(1); // one player is the case worth covering
        if (want <= 0) {
            return List.of();
        }

        List<Integer> all = placeOn(point, platforms, want);
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
     * Spawn a bot party in every quest's recruit lobby.
     *
     * <p>One call for the whole table, so adding a quest to the plan is a row there rather than
     * a new spawn method here. A quest whose lobby has no platforms, or whose bot type is
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
            case "MagatiaPQ" -> BotTypeManager.BotType.MAGATIA_PQ_BOT;
            case "ZakumPQ" -> BotTypeManager.BotType.ZAKUM_PQ_BOT;
            case "HorntailPQ" -> BotTypeManager.BotType.HORNTAIL_PQ_BOT;
            case "BossRushPQ" -> BotTypeManager.BotType.BOSS_RUSH_PQ_BOT;
            default -> null;
        };
    }

    /**
     * Spread the bots over the map's platforms, a few per platform, and return their ids.
     *
     * <p>Spread rather than piled in one spot so a recruiting player sees a lobby that looks
     * like a lobby; the placement helper already avoids overlapping them.
     */
    private static List<Integer> placeOn(PqRecruitPoints.Point point, List<String> platforms,
                                         int count) {
        List<Integer> ids = new ArrayList<>();
        int perPlatform = Math.max(1, count / platforms.size());
        int remainder = count % platforms.size();
        for (int i = 0; i < platforms.size(); i++) {
            int here = perPlatform + (i < remainder ? 1 : 0);
            if (here <= 0) {
                continue;
            }
            ids.addAll(PlatformPlacement.spawnBotsOnMapOnPlatform(here, point.recruitMap(),
                    platforms.get(i)));
        }
        return ids;
    }

    /**
     * Put every bot's level inside the quest's band.
     *
     * <p>Drawn from the band's low end upward rather than at random across the whole thing: the
     * high ends run to 255, and a lobby of level-250 bots standing in Henesys would look wrong
     * even though it is legal.
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
        }
    }
}
