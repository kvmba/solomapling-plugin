package soloMapling.ArtificialPlayer.BotCommandsPack;

import org.gms.client.Character;
import org.gms.extension.event.ChatType;
import org.gms.net.server.Server;
import soloMapling.ArtificialPlayer.BotHelpers;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.server.SoloMaplingUtilities.generateRandomNumber;

/*
Commands related to bots socializing with the player
 */

public class SocialCommands {
    static boolean botChatTypingStyle = false;
    static boolean debugSkipDialog = false;

    public static void BotFullChat(Character fakechar, String message) {
        fakechar.getMap().broadcastMessage(PacketCreator.getChatText(fakechar.getId(), message, fakechar.isGM(), (byte) 0));
    }

    // Chance an ordinary spoken line is punctuated by a facial expression. The face is a random
    // standard expression from the friendly palette - deliberately NOT matched to the line's mood;
    // the mood-matched path lives in the dialogue packs (each line's own `emote`). Rolled only when a
    // real player is on the map, since the expression is a packet nobody would otherwise see.
    public static final double SPEAK_EMOTE_CHANCE = 0.60;

    // Same pleasant palette BotFlavor / BotChatter use for idle chatter (F-keys 1,2,5,6): we skip
    // the sour ones (3 troubled / 4 cry / 7 stunned) so a random face on an ordinary line reads as
    // lively, not despondent.
    private static final int[] SPEAK_EMOTES = {1, 2, 5, 6};

    /**
     * Speaks a line, and occasionally (see {@link #SPEAK_EMOTE_CHANCE}) flashes a random expression
     * with it. Use this for ordinary chatter; use {@link #BotSpeakPlain} when the caller already
     * plays its own emote for the same utterance (scripted dialogue, per-line/event faces).
     */
    public static void BotSpeak(Character fakechar, String message) {
        if (botChatTypingStyle) {
            BotChatbubbleTyping(fakechar, message, 150);
        } else {
            BotFullChat(fakechar, message);
        }
        maybeEmoteOnSpeak(fakechar);
    }

    /** Speaks a line with no random expression - for callers that manage their own emote. */
    public static void BotSpeakPlain(Character fakechar, String message) {
        if (botChatTypingStyle) {
            BotChatbubbleTyping(fakechar, message, 150);
        } else {
            BotFullChat(fakechar, message);
        }
    }

    private static void maybeEmoteOnSpeak(Character fakechar) {
        if (fakechar == null || fakechar.getMap() == null) {
            return;
        }
        if (!GCMovement.isMapObserved(fakechar.getMapId())) {
            return; // nobody watching - the expression is just a packet, don't pay for it
        }
        if (ThreadLocalRandom.current().nextDouble() < SPEAK_EMOTE_CHANCE) {
            BotEmote(fakechar, SPEAK_EMOTES[ThreadLocalRandom.current().nextInt(SPEAK_EMOTES.length)]);
        }
    }

    /**
     * Plays the emote a spoken line asked for, or - when it asked for none ({@code preferred <= 0}) -
     * the same random {@link #SPEAK_EMOTE_CHANCE} face {@link #BotSpeak} would. Lets dialogue
     * playback share the "speaking occasionally shows a face" rule while still honouring a line's
     * own mood-matched emote when it has one.
     */
    public static void emoteAfterLine(Character fakechar, int preferred) {
        if (preferred > 0) {
            BotEmote(fakechar, preferred);
        } else {
            maybeEmoteOnSpeak(fakechar);
        }
    }

    // --- Directed-channel replies ---------------------------------------------------------------
    // A bot answering a whisper / party / guild / buddy / alliance line must send on THAT channel,
    // not broadcast to its own map: the player may be on another map, where a map bubble is
    // invisible, and same-map bystanders would otherwise see a private line as public map chat.
    // These calls are programmatic (they do not go through the chat packet handlers), so they never
    // re-enter the inbound event path - no echo.

    /**
     * Speaks one line back on the channel the conversation arrived on. {@code type == null} means a
     * map (general) conversation, which keeps the existing map-bubble behaviour.
     */
    public static void BotReply(Character fakechar, ChatType type, Character player, String message) {
        if (fakechar == null || message == null) {
            return;
        }
        if (type == null) {
            BotSpeak(fakechar, message);
            return;
        }
        BotReplyOnChannel(fakechar, type, player, message);
    }

    /**
     * Like {@link #BotReply} but never rolls the random speak-face - for callers that play their own
     * (mood-matched) emote for the same utterance.
     */
    public static void BotReplyPlain(Character fakechar, ChatType type, Character player, String message) {
        if (fakechar == null || message == null) {
            return;
        }
        if (type == null) {
            BotSpeakPlain(fakechar, message);
            return;
        }
        BotReplyOnChannel(fakechar, type, player, message);
    }

    private static void BotReplyOnChannel(Character fakechar, ChatType type, Character player, String message) {
        switch (type) {
            case WHISPER -> BotReplyWhisper(fakechar, player, message);
            case PARTY -> BotReplyParty(fakechar, message);
            case GUILD -> BotReplyGuild(fakechar, message);
            case BUDDY -> BotReplyBuddy(fakechar, player, message);
            case ALLIANCE -> BotReplyAlliance(fakechar, message);
        }
    }

    public static void BotReplyWhisper(Character fakechar, Character target, String message) {
        if (target == null || target.getClient() == null) {
            return;
        }
        target.sendPacket(PacketCreator.getWhisperReceive(
                fakechar.getName(), fakechar.getClient().getChannel() - 1, fakechar.isGM(), message));
    }

    public static void BotReplyParty(Character fakechar, String message) {
        if (fakechar.getParty() != null) {
            fakechar.getWorldServer().partyChat(fakechar.getParty(), message, fakechar.getName());
        }
    }

    public static void BotReplyGuild(Character fakechar, String message) {
        if (fakechar.getGuildId() > 0) {
            Server.getInstance().guildChat(fakechar.getGuildId(), fakechar.getName(), fakechar.getId(), message);
        }
    }

    public static void BotReplyBuddy(Character fakechar, Character target, String message) {
        if (target != null) {
            fakechar.getWorldServer().buddyChat(
                    new int[]{target.getId()}, fakechar.getId(), fakechar.getName(), message);
        }
    }

    public static void BotReplyAlliance(Character fakechar, String message) {
        var guild = fakechar.getGuild();
        if (guild == null) {
            return;
        }
        int allianceId = guild.getAllianceId();
        if (allianceId > 0) {
            Server.getInstance().allianceMessage(allianceId,
                    PacketCreator.multiChat(fakechar.getName(), message, 3), fakechar.getId(), -1);
        }
    }

    public static void BotChatbubble(Character fakechar, String message) {
        fakechar.getMap().broadcastMessage(PacketCreator.getChatText(fakechar.getId(), message, false, 1)); // Send message just bubble, no chat box
    }

    public static void BotChatbubbleTyping(Character fakechar, String message, int delayMillis) {
        BotChatbubbleTyping(fakechar, message, delayMillis, true);
    }

    // Deliberate blocking leaf primitive: the typing rhythm IS the effect and the
    // duration is data-driven (message length). Callers pick the thread.
    public static void BotChatbubbleTyping(Character fakechar, String message, int delayMillis, boolean showInChat) {
        StringBuilder displayedMessage = new StringBuilder();
        for (int i = 0; i < message.length(); i++) {
            displayedMessage.append(message.charAt(i)); // Add the next character

            // Broadcast the current state of the message
            fakechar.getMap().broadcastMessage(PacketCreator.getChatText(fakechar.getId(), displayedMessage.toString(), false, 1));

            // If the next character exists, add it to simulate 1-2 letters at a time
            if (i + 1 < message.length()) {
                displayedMessage.append(message.charAt(++i));
                fakechar.getMap().broadcastMessage(PacketCreator.getChatText(fakechar.getId(), displayedMessage.toString(), false, 1));
            }

            // Pause to simulate typing
            BotHelpers.blockingSleep(delayMillis);
        }

        if (showInChat) {
            BotFullChat(fakechar, message);
        }

    }

    // Deliberate blocking leaf primitive: line count is data-driven; callers
    // (dialogue playback) depend on this holding their thread until done.
    public static void BotDialogue(Character fakechar, List<String> dialogue) {
        if (debugSkipDialog) {
            return;
        }
        for (int i = 0; i < dialogue.size(); i++) {
            // Plain playback: the dialogue engine plays the node's own emote after the lines, so a
            // random per-line face here would fight that (and flash a different mood every line).
            BotSpeakPlain(fakechar, dialogue.get(i));
            if (i < dialogue.size() - 1) { // Skip sleep for the last element
                BotHelpers.blockingSleep(5000);
            }
        }
    }

    public static void BotEmote(Character fakechar) {
        int emote = generateRandomNumber(1, 22);
        BotEmote(fakechar, emote);
    }

    public static void BotEmote(Character fakechar, int emote) {
        if (fakechar != null) {
            fakechar.getMap().broadcastMessage(fakechar, PacketCreator.facialExpression(fakechar, emote), true);
            fakechar.getMap().broadcastMessage(fakechar, PacketCreator.facialExpression(fakechar, emote), fakechar.getPosition());
        }
    }

    public static void displayPlayerChatCommands(Character chr, List<String> commands) {
        displayPlayerChatCommands(chr, buildPlayerChatCommands(commands));
    }

    public static void displayPlayerChatCommands(Character chr, String str) {
        List<String> singleStr = new ArrayList<>();
        singleStr.add(str);
        displayPlayerChatCommands(chr, singleStr);
    }

    public static void displayPlayerChatCommands(Character chr, ChatCommandResult msg1) {
        int maxWidth = msg1.getMaxWidth();
        if (maxWidth < 5) {
            maxWidth = 60;
        } else {
            maxWidth = msg1.getMaxWidth() * 10;
        }
        int duration = 25;
        chr.getClient().sendPacket(PacketCreator.sendHint(msg1.getMessage(), maxWidth, duration));
        chr.getClient().sendPacket(PacketCreator.enableActions());
    }

    public static void expirePlayerChatCommands(Character chr) {
        chr.getClient().sendPacket(PacketCreator.sendHint(".", 40, 0));
        chr.getClient().sendPacket(PacketCreator.enableActions());
    }

    public static void spawnCygnusGuide(Character chr, boolean spawn) {
        chr.getClient().sendPacket(PacketCreator.spawnGuide(spawn));
    }

    public static void talkCygnusGuide(Character chr, String msg) {
        chr.getClient().sendPacket(PacketCreator.talkGuide(msg));
    }

    public static void talkCygnusGuideCommands(Character chr, List<String> commands) {
        ChatCommandResult msg1 = buildPlayerChatCommands(commands);
        talkCygnusGuide(chr, msg1.getMessage());
    }

    public static void botSetChalkboard(Character fakechar, String chalkboardMessage) {
        fakechar.setChalkboard(chalkboardMessage);
        fakechar.getMap().broadcastMessage(PacketCreator.useChalkboard(fakechar, false));
        fakechar.sendPacket(PacketCreator.enableActions());
    }

    public static void botClearChalkboard(Character fakechar) {
        fakechar.setChalkboard(null);
        fakechar.getMap().broadcastMessage(PacketCreator.useChalkboard(fakechar, true));
    }

    protected static ChatCommandResult buildPlayerChatCommands(List<String> commands) {
        StringBuilder msgBuilder = new StringBuilder();
        int maxWidth = 0;

        // First pass: Calculate max width
        for (String command : commands) {
            maxWidth = Math.max(maxWidth, command.length());
        }

        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            // Pad the command to the max width
            String paddedCommand = command + " ".repeat((maxWidth - command.length()));

            msgBuilder.append(i + 1)  // Line number starts from 1
                    .append(". ")  // Add period and space
                    .append(command) // Append command
                    .append("\r\n"); // Append new line
        }

        return new ChatCommandResult(msgBuilder.toString(), commands.size(), maxWidth);
    }

    public static class ChatCommandResult {
        private final String message;
        private final int numberOfLines;
        private final int maxWidth;

        public ChatCommandResult(String message, int numberOfLines, int maxWidth) {
            this.message = message;
            this.numberOfLines = numberOfLines;
            this.maxWidth = maxWidth;
        }

        public String getMessage() {
            return message;
        }

        public int getNumberOfLines() {
            return numberOfLines;
        }

        public int getMaxWidth() {
            return maxWidth;
        }
    }
}
