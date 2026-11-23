package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot.Beat;
import soloMapling.Environment.BotMessages;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Line pool for the moments inside a run that a {@link PartyQuestBot} may talk through:
 * the run's start, the work itself, a stage reporting done, and the walk to the next room.
 *
 * <p>Deliberately a twin of {@link PqRecruitMessages} rather than an extension of it: the
 * lobby shout is one composed line, this is one line drawn from a pool, and the two share
 * nothing but the message pack. Both read every word from {@code BotMessages}, so the same
 * shout is localized with the rest of the player-visible strings.
 *
 * <p>Two pools per beat, per quest: the quest's own
 * {@code pq.chat.<questName>.<beat>.<n>}, and the shared {@code pq.chat.generic.<beat>.<n>}.
 * Both are merged and drawn from together - a quest may cover only the beats it has
 * something to say about, and a bot that never set {@code questName} rides the generic pool
 * alone.
 *
 * <p>That merge is what constrains the generic pool: every line in it can be spoken in ANY
 * quest's room, so it must not assume one. It cannot promise a monster to fight, a spawn point
 * to avoid, a boss ahead or a potion going dry - several quests are box-breaking, climbing and
 * placement puzzles whose rooms hold nothing to attack at all, and a combat line there reads as
 * the bot narrating a fight that is not happening. The quest's own pool is where room-specific
 * talk belongs; the generic pool stays true for a silent, empty puzzle room.
 */
public final class PqStageChat {

    private PqStageChat() {
    }

    public static boolean isRoomSpeaker(BotSM bot) {
        Character character = bot.getChr();
        return character.getMap() != null && isAvailable(bot)
                && roomSpeaker(character, character, bot.getClass()::isInstance) == character;
    }

    /** One voice per party and map object: highest level, then lowest character id. */
    static Character roomSpeaker(Character anchor, Character best, Predicate<BotSM> applies) {
        var party = anchor.getParty();
        if (party == null) return best;
        var room = anchor.getMap();
        for (var member : party.getMembers()) {
            BotSM bot = CharacterStorage.getBotById(member.getId());
            if (!applies.test(bot)) continue;
            Character candidate = bot.getChr();
            if (candidate.getMap() != room || !isAvailable(bot)) continue;
            if (best == null || candidate.getLevel() > best.getLevel()
                    || candidate.getLevel() == best.getLevel() && candidate.getId() < best.getId()) {
                best = candidate;
            }
        }
        return best;
    }

    static boolean isAvailable(BotSM bot) {
        BotSM.BotState state = bot.getState();
        return bot.getRunning() && state != BotSM.BotState.PAUSE
                && state != BotSM.BotState.FINISHED && !bot.death().isCorpse();
    }

    /** Report initial readiness or the next lineup, independently of ambient chatter. */
    public static void announcePositionPlan(BotSM bot, int stage, PositionCombination.Plan plan,
                                            List<Rectangle> areas) {
        Character character = bot.getChr();
        if (plan == null || !GCMovement.isMapObserved(character.getMap()) || !isAvailable(bot)) return;
        // The last bot to arrive can trigger the line without waiting for the speaker's own tick.
        Character speaker = roomSpeaker(character, character, bot.getClass()::isInstance);
        PositionCombination.announcePlan(speaker, stage, plan, areas);
    }

    static void sayPositionPlan(Character speaker, MapleMap room, int stage, PositionCombination.Plan plan,
                                List<Rectangle> areas, PositionCombination.PositionLine line) {
        String message = switch (line) {
            case READY -> BotMessages.get("pq.position_plan.ready", stage, plan.attempt());
            case STEP_OFF -> BotMessages.get("pq.position_plan.step_off", stage, plan.attempt());
            case FILL -> BotMessages.get("pq.position_plan.fill", stage, plan.attempt(), playerSpots(plan, areas));
            case RETRY -> plan.free() == 0
                    ? BotMessages.get("pq.position_plan.retry", stage, plan.attempt())
                    : BotMessages.get("pq.position_plan.retry_fill", stage, plan.attempt(), playerSpots(plan, areas));
        };
        SocialCommands.BotFullChat(speaker, room, message);
    }

    private static StringJoiner playerSpots(PositionCombination.Plan plan, List<Rectangle> areas) {
        // Bots take spots from the end of the list; all earlier ones are the players' share.
        var needed = new StringJoiner(BotMessages.get("pq.position_plan.separator"));
        for (int area = 0; area < areas.size(); area++) {
            int count = 0;
            for (int spot = 0; spot < plan.free(); spot++) {
                if (areas.get(area).contains(plan.spots().get(spot))) count++;
            }
            if (count > 0) needed.add(BotMessages.get("pq.position_plan.spot", area + 1, count));
        }
        return needed;
    }

    /** Remember the last line in the event room, not on the speaker that may leave it. */
    public static boolean claimRoomLine(Character bot, String kind, String stamp) {
        var instance = bot.getEventInstance();
        if (instance == null) return true;
        String key = "solomapling." + kind + "." + bot.getMapId();
        return !stamp.equals(instance.getProperty(key))
                && !stamp.equals(instance.setProperty(key, stamp, true));
    }

    /**
     * One line for a beat of the run, or null when the pool is empty (the caller stays
     * silent - never a raw key, never a blank bubble).
     *
     * @param questName the quest's event name ({@code null} rides the generic pool alone)
     * @param beat      which moment of the run the line is for
     */
    static String line(String questName, Beat beat) {
        String suffix = beat.name().toLowerCase();
        List<String> lines = new ArrayList<>();
        collectPool(lines, "pq.chat." + (questName == null ? "generic" : questName) + "." + suffix);
        if (questName != null) {
            collectPool(lines, "pq.chat.generic." + suffix);
        }
        if (lines.isEmpty()) {
            return null;
        }
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    /**
     * Append every line of one pool, in index order.
     *
     * <p>Pools are numbered from one ({@code <base>.1}, {@code <base>.2}, ...). {@code
     * BotMessages.get} answers the key itself on a miss, which is exactly the sentinel for
     * the end of the list.
     */
    private static void collectPool(List<String> out, String base) {
        for (int i = 1; i <= POOL_LIMIT; i++) {
            String key = base + "." + i;
            String line = BotMessages.get(key);
            if (line.equals(key)) {
                return; // off the end of this pool
            }
            out.add(line);
        }
    }

    /** Guard against a miscounted pool looping forever; any real pool is far below this. */
    private static final int POOL_LIMIT = 512;
}
