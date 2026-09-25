package soloMapling.ArtificialPlayer.PartyQuest;

import soloMapling.Environment.BotMessages;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Lobby recruit-chat generator for every quest bot that is not Orbis.
 *
 * <p>The same shape {@code OPQRecruitMessages} gives the Orbis lobby - a prefix, the quest's
 * own name, an optional filler - with a different shout list per quest plus an occasional
 * "Lv N Job" self-tag. It exists because the Orbis bot's generator hardcodes Orbis's names,
 * and a Henesys lobby shouting "OPQ" reads worse than saying nothing.
 *
 * <p>Every word lives in {@code BotMessages} under {@code pq.recruit.name.<questName>} plus the
 * shared {@code opq.recruit.prefix/filler} lists, so the same shout is localized with the rest
 * of the player-visible strings. Counts there must match the constants below.
 */
public final class PqRecruitMessages {

    private PqRecruitMessages() {
    }

    /** Prefix list size; must match {@code opq.recruit.prefix.N} in BotMessages. */
    private static final int PREFIXES = 15;
    /** Filler list size; must match {@code opq.recruit.filler.N} in BotMessages. */
    private static final int FILLERS = 9;

    /** Odds of self-tagging with level+job, matching OPQRecruitMessages. */
    private static final double TAG_CHANCE = 0.35;
    /** Odds of shout-capping, matching OPQRecruitMessages (no-op for non-Latin text). */
    private static final double SHOUT_CAP_CHANCE = 0.15;

    /**
     * One recruit line for the given quest.
     *
     * @param questName the quest's event name (the {@code pq.recruit.name.*} key suffix and the
     *                  same string {@link PqBotSpawner} keys its table on)
     * @param level     the bot's level, for the optional self-tag
     * @param jobName   the bot's job name, for the optional self-tag (localized by the host)
     */
    public static String generateRecruitMessage(String questName, int level, String jobName) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // An unlisted quest would otherwise shout a raw key ("pq.recruit.name.FooPQ"). The
        // generic pair reads as a plain "LF> PQ" instead, which is the honest thing to shout.
        String name = BotMessages.getOr("pq.recruit.name." + questName,
                BotMessages.getOr("pq.recruit.name.generic", "PQ"));

        StringBuilder sb = new StringBuilder();
        sb.append(BotMessages.get("opq.recruit.prefix." + random.nextInt(PREFIXES))).append(' ');
        if (level > 0 && jobName != null && !jobName.isEmpty() && random.nextDouble() < TAG_CHANCE) {
            sb.insert(0, BotMessages.get("opq.recruit.level_tag", level, jobName) + " ");
        }
        sb.append(name);

        int fillerCount = random.nextInt(3); // 0-2
        for (int i = 0; i < fillerCount; i++) {
            sb.append(' ').append(BotMessages.get("opq.recruit.filler." + random.nextInt(FILLERS)));
        }

        // Same as Orbis: no "@@@@" padding, and capping is a no-op for Chinese.
        String out = sb.toString().replaceAll("\\[", "").replaceAll("]", "");
        return random.nextDouble() < SHOUT_CAP_CHANCE ? out.toUpperCase() : out;
    }
}
