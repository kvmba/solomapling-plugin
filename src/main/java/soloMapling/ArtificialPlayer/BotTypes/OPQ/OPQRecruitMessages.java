package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.gms.client.Character;

import soloMapling.Environment.BotMessages;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomized OPQ lobby recruit-chat generator.
 *
 * Mirrors the structure of MerchantBot.MerchantBotMessageCreator:
 *   [level/job tag] [prefix] [pq name] [optional filler]
 *
 * The goal is visual noise variety, not believability — these lines appear in
 * the Orbis PQ lobby crowd, interleaved with real-player chat.
 *
 * <p>Every word lives in {@code BotMessages} under {@code opq.recruit.*}, so a zh-CN lobby
 * recruits in Chinese instead of shouting "J> OPQ plz". The counts below must match the lists
 * in {@code BotMessages.yaml}; a missing key degrades to the raw key, which is why they are
 * kept as plain indexed keys rather than a variable-length list.
 */
public final class OPQRecruitMessages {

    private OPQRecruitMessages() {}

    private static final int PREFIXES = 15;
    private static final int PQ_NAMES = 30;
    private static final int FILLERS = 9;

    // Odds of self-tagging with level+job, and of shout-capping the whole line
    // (same as MerchantBotMessageCreator). Capping is a no-op for non-Latin scripts.
    private static final double TAG_CHANCE = 0.35;
    private static final double SHOUT_CAP_CHANCE = 0.15;

    public static String generateRecruitMessage(Character chr) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        StringBuilder sb = new StringBuilder();
        sb.append(BotMessages.get("opq.recruit.prefix." + random.nextInt(PREFIXES))).append(' ');

        // Self-tag with level+job ("Lvl 55 Priest J> OPQ" style). Job.getName() is the localized
        // name ("英雄"); name() is the raw Java enum constant ("HERO"), which reads as English
        // even on a Chinese server.
        if (chr != null && chr.getJob() != null && random.nextDouble() < TAG_CHANCE) {
            sb.insert(0, BotMessages.get("opq.recruit.level_tag", chr.getLevel(),
                    chr.getJob().getName()) + " ");
        }

        sb.append(BotMessages.get("opq.recruit.name." + random.nextInt(PQ_NAMES)));

        // 0–2 filler appends
        int fillerCount = random.nextInt(3);
        for (int i = 0; i < fillerCount; i++) {
            sb.append(' ').append(BotMessages.get("opq.recruit.filler." + random.nextInt(FILLERS)));
        }

        // No "@@@@" run here: shout padding draws from the filler list, and a long @ run reads
        // as spam.
        String out = sb.toString().replaceAll("\\[", "").replaceAll("]", "");

        return random.nextDouble() < SHOUT_CAP_CHANCE ? out.toUpperCase() : out;
    }
}
