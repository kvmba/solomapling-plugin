package soloMapling.ArtificialPlayer.LlmSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.DialogueContextResolver;
import soloMapling.Environment.SoloMaplingLanguageConfig;

/**
 * Builds the fixed system prompt and per-turn user context for SocialBot LLM chat.
 */
public final class SocialLlmPromptBuilder {

    private SocialLlmPromptBuilder() {
    }

    public static String systemPrompt(Character bot, Character player) {
        String languageHint = SoloMaplingLanguageConfig.isDefaultEnglish()
                ? "Reply in English."
                : "Prefer " + SoloMaplingLanguageConfig.languageTag() + " when natural; mirror the player's language if obvious.";

        return """
                You are %s, a MapleStory v83 player character hanging out in town — not an NPC, not an AI assistant.
                %s
                Game context:
                %s
                Rules:
                - Reply in 1-2 short in-game chat lines (under %d characters total).
                - Stay in character as a casual Maple player; no markdown, no meta, no URLs.
                - Do not invite to party or promise buffs — the player uses menu options for that.
                - Be friendly and slightly playful; reference the game world when it fits.
                """.formatted(
                safeName(bot),
                languageHint,
                DialogueContextResolver.buildSnapshot(bot, player),
                SocialLlmConfig.MAX_REPLY_CHARS);
    }

    private static String safeName(Character chr) {
        return chr == null || chr.getName() == null ? "a town bot" : chr.getName();
    }
}
