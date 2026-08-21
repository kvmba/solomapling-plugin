package soloMapling.ArtificialPlayer.LlmSystem;

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
 * SocialBot-facing facade for asynchronous LLM chat.
 */
public final class SocialLlmService {

    private static final Logger log = LoggerFactory.getLogger(SocialLlmService.class);

    private SocialLlmService() {
    }

    private static volatile LlmClient client;

    public static void configure(HostConfig config) {
        SocialLlmConfig.configure(config);
        client = null;
        if (!SocialLlmConfig.enabled()) {
            log.info("SocialBot LLM disabled (set {}=true and provide api-key)", SocialLlmConfig.KEY_ENABLED);
            return;
        }
        client = DeepSeekLlmClient.create(SocialLlmConfig.apiKey());
        log.info("SocialBot LLM enabled model={} maxTokens={} timeoutMs={} historyTurns={}",
                SocialLlmConfig.model(),
                SocialLlmConfig.maxTokens(),
                SocialLlmConfig.timeoutMs(),
                SocialLlmConfig.historyTurns());
    }

    static void configure(LlmClient injectedClient) {
        client = injectedClient;
    }

    public static boolean isEnabled() {
        return client != null;
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
        LlmClient selectedClient = client;
        int botId = bot.getId();
        int playerId = player.getId();
        SocialChatSessionStore.addUser(botId, playerId, userMessage);

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(SocialLlmPromptBuilder.systemPrompt(bot, player)));
        messages.addAll(SocialChatSessionStore.toMessages(botId, playerId, SocialLlmConfig.historyTurns()));

        LlmRequest request = new LlmRequest(
                messages,
                SocialLlmConfig.model(),
                SocialLlmConfig.maxTokens(),
                0.85);
        completeAsync(selectedClient, request, SocialLlmConfig.timeoutMs(), onSuccess, onFailure,
                error -> log.warn("SocialBot LLM call failed bot={} player={}: {}",
                        bot.getName(), player.getName(), error.toString()));
    }

    static void completeAsync(LlmRequest request,
                              long timeoutMs,
                              Consumer<String> onSuccess,
                              Runnable onFailure) {
        LlmClient selectedClient = client;
        if (selectedClient == null) {
            onFailure.run();
            return;
        }
        completeAsync(selectedClient, request, timeoutMs, onSuccess, onFailure,
                error -> log.warn("LLM call failed: {}", error.toString()));
    }

    private static void completeAsync(LlmClient selectedClient,
                                      LlmRequest request,
                                      long timeoutMs,
                                      Consumer<String> onSuccess,
                                      Runnable onFailure,
                                      Consumer<Throwable> onError) {
        final CompletableFuture<String> completion;
        try {
            completion = selectedClient.complete(request);
            if (completion == null) {
                onFailure.run();
                return;
            }
        } catch (Throwable error) {
            onError.accept(error);
            onFailure.run();
            return;
        }

        completion.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((raw, error) -> {
                    if (error != null) {
                        onError.accept(error);
                        onFailure.run();
                        return;
                    }
                    String reply = truncateForMapChat(raw);
                    if (reply == null || reply.isBlank()) {
                        onFailure.run();
                        return;
                    }
                    try {
                        onSuccess.accept(reply);
                    } catch (Throwable callbackError) {
                        onError.accept(callbackError);
                        onFailure.run();
                    }
                });
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
