package soloMapling.ArtificialPlayer.PartyQuest;

import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot.Beat;
import soloMapling.Environment.BotMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
 */
public final class PqStageChat {

    private PqStageChat() {
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
