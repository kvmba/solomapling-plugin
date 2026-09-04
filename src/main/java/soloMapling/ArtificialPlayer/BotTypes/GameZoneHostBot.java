package soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.YesNo;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotChatbubble;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.BotLogger.log;
import static soloMapling.server.SoloMaplingUtilities.random;

public class GameZoneHostBot extends BotSM {
    private HostBotState hostBotState = HostBotState.RESET;
    private Boolean drinkAccepted;
    private int selectedDrink;

    private long startTime;
    private long endTime;

    // Drink item IDs - placeholder values, to be updated with actual item IDs
    private static final int DRINK_COKE = 2020031;
    private static final int DRINK_WATER = 2022000;
    private static final int DRINK_ELIXIR = 2000012;

    // Drink names shown in the option menu and matched against what the player types. Localized:
    // the menu displays these, and handleDrinkPickResponse() matches on them, so a Chinese menu
    // is answerable in Chinese. Any per-language aliases come from
    // menu.gamezone.drink.<name>.keywords in the message pack.
    private static final String[] DRINK_SUFFIXES = {"coke", "water", "elixir"};
    private static final int[] DRINK_IDS = {DRINK_COKE, DRINK_WATER, DRINK_ELIXIR};
    private static final String[][] DRINK_KEYWORDS = {
            {"coke"}, {"water"}, {"elixir"}
    };

    // Resolved per instance rather than in a static field: a static would freeze at class-load
    // time and would not follow the configured language.
    private final String[] drinkNames =
            BotMessages.labels("gamezone.drink", DRINK_SUFFIXES).toArray(new String[0]);
    private final List<List<String>> drinkKeywords =
            BotMessages.keywords("gamezone.drink", DRINK_SUFFIXES, DRINK_KEYWORDS);

    private List<String> hint;

    public GameZoneHostBot(Character character) {
        super(character);
        dialoguePath = "GameZoneHostBotDialogue.yaml";
        botType = "GameZoneHostBot";
        hint = Collections.singletonList(character.getName());
        EventBus.getInstance().subscribe(EventType.MAP_ENTERED, this);
    }

    private enum HostBotState {
        RESET,
        IDLE_WAITING,
        DRINK_OFFER,
        WAIT_DRINK_RESPONSE,
        DRINK_SELECTION,
        WAIT_DRINK_PICK,
        SERVE_DRINK,
        FAREWELL
    }

    private void setHostBotState(HostBotState state) {
        this.hostBotState = state;
    }

    private void resetHostBotState() {
        setHostBotState(HostBotState.RESET);
        drinkAccepted = null;
        selectedDrink = 0;
        hint = Collections.singletonList(getChr().getName());
        startTime = 0;
        endTime = 0;
    }

    // --- Event-driven passive greeting (decoupled from state machine) ---

    @Override
    public void handleEvent(GameEvent event) {
        if (event.getType() == EventType.MAP_ENTERED) {
            String playerName = event.getPlayerName();
            String greetingLine = BotDialogueHandler.getRandomDialogueLine(this, "PassiveGreeting");
            greetingLine = greetingLine.replace("%PLAYER_NAME%", playerName);
            BotChatbubble(getChr(), greetingLine);
            BotEmote(getChr(), 2);
        }
    }

    // --- Main state machine ---

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }

        processQueuedEvents();

        getDebugger().debugLoggingFull(
                String.format("%s HostBotState: %s", getChr().getName(), hostBotState),
                String.format("%s", hostBotState));

        switch (hostBotState) {
            case RESET:
                resetHostBotState();
                setHostBotState(HostBotState.IDLE_WAITING);
                break;

            case IDLE_WAITING:
                idleFlavorText();
                checkForInquirer();
                break;

            case DRINK_OFFER:
                offerDrink();
                setHostBotState(HostBotState.WAIT_DRINK_RESPONSE);
                break;

            case WAIT_DRINK_RESPONSE:
                waitForResponse();
                if (drinkAccepted == null) {
                    return;
                }
                if (drinkAccepted) {
                    setHostBotState(HostBotState.DRINK_SELECTION);
                } else {
                    getDialogueHandler().executeBotDialogue("DeclineResponse", GameZoneHostBot.this);
                    setHostBotState(HostBotState.FAREWELL);
                }
                break;

            case DRINK_SELECTION:
                presentDrinkOptions();
                setHostBotState(HostBotState.WAIT_DRINK_PICK);
                break;

            case WAIT_DRINK_PICK:
                waitForResponse();
                if (selectedDrink == 0) {
                    return;
                }
                setHostBotState(HostBotState.SERVE_DRINK);
                break;

            case SERVE_DRINK:
                serveDrink();
                setHostBotState(HostBotState.FAREWELL);
                break;

            case FAREWELL:
                farewell();
                getInteractors().resetRespondant();
                setHostBotState(HostBotState.RESET);
                break;

            default:
                log("Unexpected state: " + hostBotState);
                state = BotState.FINISHED;
                resetHostBotState();
                throw new IllegalStateException("Unexpected state: " + hostBotState);
        }
    }

    // --- Player detection (inquirer promotion) ---

    private void checkForInquirer() {
        if (getInteractors().getListInquirer().isEmpty()) {
            return;
        }
        Character inquirer = getInteractors().getInquirer();
        getInteractors().removeInquirer(inquirer);
        getInteractors().setRespondant(inquirer);
        setHostBotState(HostBotState.DRINK_OFFER);
    }

    // --- Drink offer ---

    private void offerDrink() {
        getDialogueHandler().executeBotDialogue("DrinkOffer", GameZoneHostBot.this);
        hint = yesNoLabels();
        displayCommands(getInteractors().getRespondant());
        startTimer(20_000);
    }

    // --- Drink selection ---

    private void presentDrinkOptions() {
        getDialogueHandler().executeBotDialogue("DrinkSelection", GameZoneHostBot.this);
        hint = List.of(drinkNames);
        displayCommands(getInteractors().getRespondant());
        startTimer(30_000);
    }

    // --- Serve drink ---

    private void serveDrink() {
        getDialogueHandler().executeBotDialogue("ServeDrink", GameZoneHostBot.this);
        DropCommands.botThrowItem(getChr(), selectedDrink, getInteractors().getRespondant().getPosition());
    }

    // --- Farewell ---

    private void farewell() {
        getDialogueHandler().executeBotFlavorDialogue("Farewell", GameZoneHostBot.this);
    }

    // --- Idle flavor text ---

    private void idleFlavorText() {
        if (random.nextInt(100) < 6) {
            getDialogueHandler().executeBotFlavorDialogue("Flavor", GameZoneHostBot.this);
        }
    }

    // --- Timer ---

    private void startTimer(long durationMs) {
        startTime = System.currentTimeMillis();
        endTime = startTime + durationMs;
    }

    // --- Response handling ---

    private void waitForResponse() {
        if (System.currentTimeMillis() < endTime) {
            processMessages();
        } else {
            BotSpeak(getChr(), BotMessages.get("gamezone.talk_again"));
            state = BotState.FINISHED;
            resetHostBotState();
        }
    }

    @Override
    public void displayCommands(Character chr) {
        SocialCommands.displayPlayerChatCommands(chr, hint);
    }

    @Override
    public void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance().getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) {
                return;
            }
            handleMessage(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(ChatMessage message) {
        if (!getInteractors().isMessageFromRespondant(message)) {
            return;
        }
        String content = message.getContent().toLowerCase();
        if (hostBotState == HostBotState.WAIT_DRINK_RESPONSE) {
            handleDrinkOfferResponse(content);
        } else if (hostBotState == HostBotState.WAIT_DRINK_PICK) {
            handleDrinkPickResponse(content);
        }
    }

    private void handleDrinkOfferResponse(String content) {
        if (YesNo.isYes(content)) {
            drinkAccepted = true;
        } else if (YesNo.isNo(content)) {
            drinkAccepted = false;
        }
    }

    private static List<String> yesNoLabels() {
        return YesNo.labels();
    }

    private void handleDrinkPickResponse(String content) {
        // Ignore stale yes/no from the previous drink offer phase
        if (content.contains("yes") || content.contains("no")) {
            return;
        }
        for (int i = 0; i < drinkNames.length; i++) {
            for (String keyword : drinkKeywords.get(i)) {
                if (!content.contains(keyword)) {
                    continue;
                }
                selectedDrink = DRINK_IDS[i];
                BotSpeak(getChr(), BotMessages.get("gamezone.serve_drink", drinkNames[i]));
                waitFor(2000); // beat before SERVE_DRINK ticks
                return;
            }
        }
        BotSpeak(getChr(), BotMessages.get("gamezone.no_such_drink"));
        BotEmote(getChr(), 6);
    }
}
