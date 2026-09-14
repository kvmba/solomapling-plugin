package soloMapling.ArtificialPlayer.LlmSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.DialogueContextResolver;
import soloMapling.ArtificialPlayer.Persona;
import soloMapling.Environment.SoloMaplingLanguageConfig;

/**
 * Builds the fixed system prompt and per-turn user context for SocialBot LLM chat.
 */
public final class SocialLlmPromptBuilder {

    private SocialLlmPromptBuilder() {
    }

    public static String systemPrompt(Character bot, Character player, Persona persona) {
        String languageHint = SoloMaplingLanguageConfig.isDefaultEnglish()
                ? "Reply in English."
                : "Prefer " + SoloMaplingLanguageConfig.languageTag() + " when natural; mirror the player's language if obvious.";
        Persona who = persona == null ? Persona.CASUAL : persona;
        // The cheek licence has to follow the persona: a FRIENDLY/CHILL bot that is told to jab
        // would contradict its own Personality line above.
        String mood = who.cheeky()
                ? "Feel free to be cheeky: tease their level/gear/handspeed/pet/guild, talk back, use slang\n"
                    + "  (就这/破防/蚌埠住了/有一说一). Being funny beats being polite."
                : "Keep it warm and easygoing; a light tease is fine, but do not be a trash-talker.";

        return """
                You are %s, a MapleStory v83 player character hanging out in town — not an NPC, not an AI assistant.
                %s
                Personality: %s.
                Game context:
                %s
                Rules:
                - Reply in 1-2 short in-game chat lines (under %d characters total), like real typing.
                - Stay in character as a casual Maple player; no markdown, no meta, no URLs.
                - %s
                - NEVER attack anything outside the game: no real life, family, region, gender, slurs,
                  no genuine harassment. Only roast game stuff, and only if it stays playful.
                - Do not invite to party or promise buffs — the player uses menu options for that.
                """.formatted(
                safeName(bot),
                languageHint,
                who.style(),
                DialogueContextResolver.buildSnapshot(bot, player),
                SocialLlmConfig.MAX_REPLY_CHARS,
                mood);
    }

    private static String safeName(Character chr) {
        return chr == null || chr.getName() == null ? "a town bot" : chr.getName();
    }
}
