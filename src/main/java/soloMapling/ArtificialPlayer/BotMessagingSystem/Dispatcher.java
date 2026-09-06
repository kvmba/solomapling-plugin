package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.gms.client.Character;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.CompanionBot;

import java.awt.Point;
import java.util.Collection;
import java.util.concurrent.*;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.expirePlayerChatCommands;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.checkIfInvisibleBot;
import static soloMapling.server.ExecutorServiceManager.getExecutorService;
import static soloMapling.server.ExecutorServiceManager.getScheduledExecutorService;
import static soloMapling.BotLogger.log;

public class Dispatcher implements Runnable {

    // Drain cap per 2s tick. One-per-tick starved the queue: the cleaner expires anything older than
    // 10s, so a couple of chatty players in town could push a name-call out before it was ever read.
    private static final int MAX_DRAIN_PER_TICK = 32;

    private static final Dispatcher dispatcher = new Dispatcher(MessageQueue.getInstance());
    private final MessageQueue messageQueue;
    private final ExecutorService executor = getExecutorService();
    private final ScheduledExecutorService scheduler = getScheduledExecutorService();

    public Dispatcher(MessageQueue messageQueue) {
        log("Dispatcher OBJECT Created");
        this.messageQueue = messageQueue;
        this.scheduler.scheduleAtFixedRate(this, 0, 2, TimeUnit.SECONDS);
    }

    // Static method to access the singleton instance
    public static Dispatcher getInstance() {
        log("Dispatcher getInstance");
        return dispatcher;
    }

    @Override
    public void run() {
//        log("Dispatcher: RUN");
        for (int i = 0; i < MAX_DRAIN_PER_TICK; i++) {
            if (!processMessages()) {
                return;
            }
        }
    }

    // Returns false when the primary queue is drained (or the message could not be handled).
    private boolean processMessages() {
        try {
            ChatMessage message = messageQueue.getMessageNonBlocking("primary");
            if (message == null) {
                return false;
            }
            Collection<Character> chars_on_map = message.getMap().getCharacters();
            final int[] botToCall = new int[1];  // Using an array to hold the bot ID

            boolean characterFound = checkIfCharacterOnMap(chars_on_map, message, botToCall);

            // Determine if the message is for any registered bot
            if (characterFound) {
                handleBotRunning(botToCall[0], message);
            } else {
                // Nobody was named, but the line may still be an open party offer ("anyone want to
                // party?"). Hand it to every bot inside the speaker's viewport so a player can
                // recruit a crowd without learning anyone's name first. Runs before the respondant/
                // inquirer path and never replaces it.
                if (BotRecruitManager.isRecruitShout(message.getContent())) {
                    BotRecruitManager.broadcastRecruit(message.getSender(), message.getContent());
                }
                handleMessageWithNoBotName(message);
            }
            // Runs in ADDITION to a name call, not instead of it: "Tiger 跟我来" should still
            // reach Tiger's own party mates, and Tiger itself is skipped below so it isn't
            // handed the same line twice.
            deliverToPartyBots(message, characterFound ? botToCall[0] : -1);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Addresses every bot in the speaker's own party that shares the speaker's map, WITHOUT the
     * speaker having typed any bot's name.
     *
     * <p>The keyword is handed to each bot individually rather than through the shared tertiary
     * queue: that queue is single-consumer (whoever polls first takes the message), so broadcasting
     * through it would deliver a "come here" to exactly one bot and silently drop it for the rest
     * of the party - the whole point here is that they all answer.
     *
     * <p>Bots that recognise the line act on it silently. If nobody recognised it (ordinary party
     * chatter), exactly one bot - the closest to the speaker - shows its option menu, because the
     * client only ever displays one hint balloon and N bots fighting over it just flickers.
     *
     * @param namedBotId a bot already reached through the name-call path this tick, or -1
     */
    private void deliverToPartyBots(ChatMessage message, int namedBotId) {
        Character sender = message.getSender();
        Party party = sender == null ? null : sender.getParty();
        if (party == null) {
            return;
        }
        BotSM fallback = null;
        int fallbackDistSq = Integer.MAX_VALUE;
        Point origin = sender.getPosition();
        for (PartyCharacter pc : party.getMembers()) {
            Character member = pc == null ? null : pc.getPlayer();
            if (member == null || member.getId() == namedBotId || !isBot(member)) {
                continue;
            }
            if (member.getMapId() != sender.getMapId()) {
                continue;
            }
            BotSM bot = getBotById(member.getId());
            if (bot == null || !bot.getRunning()) {
                continue;
            }
            if (bot.offerKeyword(sender, message.getContent())) {
                bot.getInteractors().setInquirer(sender);
                bot.nudgeSoon(0L); // speaker is on this bot's map -> answer on the next tick
                continue;
            }
            int distSq = distanceSq(origin, member.getPosition());
            if (distSq < fallbackDistSq) {
                fallbackDistSq = distSq;
                fallback = bot;
            }
        }
        // Nobody claimed the line: show the menu once, on the nearest bot.
        if (fallback != null) {
            BotSM shown = fallback;
            executor.execute(() -> {
                shown.getInteractors().setInquirer(sender);
                shown.getDialogueHandler().listOptions(sender, shown);
            });
        }
    }

    private static int distanceSq(Point a, Point b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        int dx = a.x - b.x;
        int dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private boolean checkIfCharacterOnMap(Collection<Character> chars_on_map, ChatMessage message, int[] botToCall) {
        boolean characterFound = false;
        for (Character character : chars_on_map) {
            if (message.getContent().contains(character.getName())) {
                if (!checkIfInvisibleBot(character.getId())) {
                    botToCall[0] = character.getId();
                    characterFound = true;
                    break;
                }
            }
        }
        return characterFound;
    }

    private void handleBotRunning(int namedBotId, ChatMessage message) {
        executor.execute(() -> {
            BotSM bot = getBotById(namedBotId);
            if (bot == null) {
                logBotNotFound(namedBotId);
                return;
            }

            if (!bot.getRunning()) {
                startNewBotSession(bot, message);
            } else {
                handleExistingBotSession(bot, message);
            }
        });
    }

    private void startNewBotSession(BotSM bot, ChatMessage message) {
        log("Bot not running. Start scheduledTask line");
        bot.setRunning(true);
        bot.startScheduledTask();
        if (bot instanceof CompanionBot companionBot) {
            companionBot.enqueuePlayerMessage(message.getSender(), message.getContent());
            return;
        }
        bot.getInteractors().setRespondant(message.getSender());
        if (bot instanceof SocialBot socialBot) {
            socialBot.onFirstInteraction(message.getSender());
        }
    }

    private void handleExistingBotSession(BotSM bot, ChatMessage message) {
        if (bot instanceof CompanionBot companionBot) {
            companionBot.enqueuePlayerMessage(message.getSender(), message.getContent());
            companionBot.nudgeSoon(0L);
            return;
        }
        if (bot instanceof SocialBot socialBot) {
            handleSocialBotSession(socialBot, message);
            return;
        }
        log("bot already running");
        bot.getInteractors().setInquirer(message.getSender());
        bot.getDialogueHandler().listOptions(message.getSender(), bot);
        // One-line inquiry: a naming message that also carries a keyword ("Tiger wana party?") pre-selects the
        // menu option in the same breath. Strip the bot's name first so a name that itself contains a keyword
        // can't self-match, then re-enqueue the remainder onto the tertiary queue exactly as a stand-alone
        // follow-up would have arrived — BotOptionMenu.poll drains it on the bot's next tick with the same
        // trim/lowercase/contains matcher. The menu is already active (listOptions ran synchronously above),
        // and a non-matching remainder ("yo Tiger") falls through harmlessly like a junk follow-up would.
        String remainder = message.getContent().replace(bot.getChr().getName(), "").trim();
        if (!remainder.isEmpty()) {
            messageQueue.addMessage("tertiary", new ChatMessage(message.getSender(), remainder));
            bot.nudgeSoon(0L); // player is on the bot's map -> pull the pre-selected option forward so it feels instant
        }
    }

    private void handleSocialBotSession(SocialBot socialBot, ChatMessage message) {
        if (socialBot.hasActiveRespondant()) {
            log("[Dispatcher] SocialBot busy, ignoring second player");
            return;
        }
        log("[Dispatcher] SocialBot available, setting respondant");
        socialBot.getInteractors().setRespondant(message.getSender());
        socialBot.onFirstInteraction(message.getSender());
    }

    private void logBotNotFound(int botId) {
        log("No bot found for ID: " + botId);
    }

//    private void handleBotRunning(int[] botToCall, ChatMessage message) {
//        executor.execute(() -> {
//            BotSM bot = getBotById(botToCall[0]);
//            if (bot != null && !bot.getRunning()) {
//                log("Bot not running. Start scheduledTask line");
//                bot.setRunning(true);
//                bot.setRespondant(message.getSender());
//                bot.startScheduledTask();
//            } else if (bot != null & bot.getRunning()) {
//                log("bot already running");
//                bot.setInquirer(message.getSender());
//                bot.listOptions(message.getSender());
//            } else {
//                log("No bot found for ID: " + botToCall[0] + " or bot is already running.");
//            }
//        });
//    }

    private void handleMessageWithNoBotName(ChatMessage message) {
        // message does not contain any info w/ bot names.
        Character respondant = message.getSender();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            if (bot instanceof CompanionBot companionBot
                    && companionBot.acceptsContinuation(respondant)) {
                companionBot.enqueuePlayerMessage(respondant, message.getContent());
                companionBot.nudgeSoon(0L);
                return;
            }
        }
        if (CharacterStorage.checkIfRespondant(respondant)) { // Check if message contains a respondant
            expirePlayerChatCommands(respondant); // Expires any bubbles for respondants, catch all.
            messageQueue.addMessage(message); // Put into 2nd queue
        } else if (CharacterStorage.checkIfInquirer(respondant)) {
            messageQueue.addMessage("tertiary", message);
        }
    }

    /*
     * Intentionally no shutdown()/stop() method.
     *
     * This class's tasks run on the PROCESS-WIDE pools owned by ExecutorServiceManager
     * (getExecutorService / getScheduledExecutorService), shared with every other
     * subsystem: the bot tick wheel, the movement driver, the grind sweep, spawn
     * choreography. Shutting them down here would stop all of them, not just this
     * dispatcher -- chat routing is one tenant of those pools, not their owner.
     *
     * A previous version had exactly such a method. It was never called, so the bug
     * was latent; wiring it into any lifecycle (plugin unload, reload, a GM command)
     * would have silently frozen the whole plugin. Removing it rather than leaving it
     * as a trap.
     */
}

            /*
            Message:
            - Has Bot Name -> start that bot

            - Doesn't have bot name
                -> contains a respondant -> 2ndary queue
                -> doesn't contain a respondant -> random chat, remove

            2ndary queue:
                - active bots pull from this.
             */