package soloMapling.ArtificialPlayer.LlmSystem;

import io.github.sashirestela.openai.SimpleOpenAIDeepseek;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import io.github.sashirestela.openai.domain.chat.ChatRequest;
import org.gms.client.Character;
import org.gms.extension.api.HostConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Async DeepSeek chat for active SocialBot player sessions (simple-openai client).
 */
public final class SocialLlmService {

    private static final Logger log = LoggerFactory.getLogger(SocialLlmService.class);

    private SocialLlmService() {
    }

    private static volatile SimpleOpenAIDeepseek client;

    public static void configure(HostConfig config) {
        SocialLlmConfig.configure(config);
        client = null;
        if (!SocialLlmConfig.enabled()) {
            log.info("SocialBot LLM disabled (set {}=true and provide api-key)", SocialLlmConfig.KEY_ENABLED);
            return;
        }
        client = SimpleOpenAIDeepseek.builder()
                .apiKey(SocialLlmConfig.apiKey())
                .build();
        log.info("SocialBot LLM enabled model={} maxTokens={} timeoutMs={} historyTurns={}",
                SocialLlmConfig.model(),
                SocialLlmConfig.maxTokens(),
                SocialLlmConfig.timeoutMs(),
                SocialLlmConfig.historyTurns());
    }

    public static boolean isEnabled() {
        return SocialLlmConfig.enabled() && client != null;
    }

    public static void completeAsync(Character bot,
                                     Character player,
                                     String userMessage,
                                     Consumer<String> onSuccess,
                                     Runnable onFailure) {
        if (!isEnabled()) {
            onFailure.run();
            return;
        }
        int botId = bot.getId();
        int playerId = player.getId();
        SocialChatSessionStore.addUser(botId, playerId, userMessage);

        Thread.startVirtualThread(() -> {
            try {
                String reply = complete(bot, player, userMessage);
                if (reply == null || reply.isBlank()) {
                    onFailure.run();
                    return;
                }
                onSuccess.accept(reply);
            } catch (Exception e) {
                log.warn("SocialBot LLM call failed bot={} player={}: {}", bot.getName(), player.getName(), e.toString());
                onFailure.run();
            }
        });
    }

    private static String complete(Character bot, Character player, String userMessage) throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.SystemMessage.of(SocialLlmPromptBuilder.systemPrompt(bot, player)));
        messages.addAll(SocialChatSessionStore.toChatMessages(bot.getId(), player.getId(), SocialLlmConfig.historyTurns()));

        ChatRequest request = ChatRequest.builder()
                .model(SocialLlmConfig.model())
                .messages(messages)
                .maxCompletionTokens(SocialLlmConfig.maxTokens())
                .temperature(0.85)
                .build();

        CompletableFuture<String> future = client.chatCompletions()
                .create(request)
                .orTimeout(SocialLlmConfig.timeoutMs(), TimeUnit.MILLISECONDS)
                .thenApply(chat -> chat == null ? null : chat.firstContent());

        String raw = future.join();
        return truncateForMapChat(raw);
    }

    static String truncateForMapChat(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().replace('\n', ' ').replaceAll("\\s+", " ");
        if (trimmed.length() <= SocialLlmConfig.MAX_REPLY_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, SocialLlmConfig.MAX_REPLY_CHARS - 1).trim() + "…";
    }
}
