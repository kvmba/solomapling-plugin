package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reported symptom, reproduced: {@code [BotDialogueHandler] failed to load <n>PQBotDialogue.yaml}
 * on the console when a player first spoke to a party-quest bot in a lobby.
 *
 * <p>That line is printed by {@code BotDialogueHandler.readDialogueRoot} when the bot's own pack
 * cannot be opened - which for every PQ type it could not, because the per-quest file was named in
 * the constructor but never shipped. The pack now exists and is shared, so the loader must open it
 * cleanly. This drives the real loader (not a stub) with the language switched between the packs,
 * and fails if the loader prints anything while resolving the nodes a lobby bot uses.
 */
class PartyQuestDialogueLoadsWithoutErrorTest {

    private static final String PACK = "PartyQuestBotDialogue.yaml";

    private static final List<String> BOT_TYPES = Arrays.asList(
            "LudiPQBot", "KerningPQBot", "HenesysPQBot", "OPQBot", "PiratePQBot", "AmoriaPQBot",
            "EllinPQBot", "MagatiaPQBot", "ZakumPQBot", "HorntailPQBot", "BossRushPQBot",
            "CarnivalPQBot", "PyramidPQBot", "DojoPQBot", "PapulatusPQBot", "AriantPQBot");

    private static final List<String> NODES = Arrays.asList(
            "PartyAccept", "PartyDecline", "TradeDecline",
            "Greeting", "Apology", "Thanks", "Praise", "Cheer", "TeaseBack", "Wow", "Laugh", "Agree");

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
        BotDialogueHandler.invalidateDialogueCache();
        BotDialogueHandler.invalidateDialogueNodes();
    }

    @Test
    void loadingTheSharedPackPrintsNoFailure() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            for (String languageTag : Arrays.asList("en-US", "zh-CN")) {
                // Re-read from disk for each language: the cache is namespaced by tag, so this is
                // the same code path a fresh server process takes.
                SoloMaplingLanguageConfig.setLanguageTag(languageTag);
                BotDialogueHandler.invalidateDialogueCache();
                List<String> resolved = new ArrayList<>();
                for (String botType : BOT_TYPES) {
                    for (String node : NODES) {
                        var con = BotDialogueHandler.getDialogueCon(PACK, botType, node);
                        if (con != null) {
                            resolved.add(languageTag + "|" + botType + "|" + node);
                        }
                    }
                }
                assertTrue(resolved.size() == BOT_TYPES.size() * NODES.size(),
                        languageTag + ": only " + resolved.size() + " of "
                                + (BOT_TYPES.size() * NODES.size()) + " lobby nodes resolved");
            }
        } finally {
            System.setErr(originalErr);
        }
        String stderr = captured.toString(StandardCharsets.UTF_8);
        assertFalse(stderr.contains("failed to load"), "loader reported a failure: " + stderr);
        assertFalse(stderr.contains("not found"), "loader reported a missing file: " + stderr);
        assertTrue(stderr.isEmpty(), "expected a silent load, got: " + stderr);
    }
}
