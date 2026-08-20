package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentDecisionParserTest {

    private final AgentDecisionParser parser = new AgentDecisionParser();

    @Test
    void parsesAllowlistedActionsIntoImmutableDomainTypes() {
        AgentDecision decision = parser.parse("""
                {
                  "schemaVersion": 1,
                  "reply": "Let's train.",
                  "reason": "The player asked for company.",
                  "actions": [
                    {"schemaVersion": 1, "type": "SAY", "text": "Let's go!"},
                    {"schemaVersion": 1, "type": "EMOTE", "emote": "CHEER"},
                    {"schemaVersion": 1, "type": "ACCEPT_PARTY", "characterId": 42},
                    {"schemaVersion": 1, "type": "INVITE_PARTY", "characterId": 42},
                    {"schemaVersion": 1, "type": "FOLLOW", "characterId": 42},
                    {"schemaVersion": 1, "type": "GO_TO", "mapId": 100000000},
                    {"schemaVersion": 1, "type": "TRAIN_WITH", "characterId": 42},
                    {"schemaVersion": 1, "type": "REST"}
                  ]
                }
                """);

        assertEquals(1, decision.schemaVersion());
        assertEquals(8, decision.actions().size());
        assertInstanceOf(CompanionAction.Say.class, decision.actions().get(0));
        assertEquals(new CompanionAction.GoTo(100000000), decision.actions().get(5));
        assertThrows(UnsupportedOperationException.class,
                () -> decision.actions().add(new CompanionAction.Goodbye()));
    }

    @Test
    void rejectsUnknownActionsFieldsAndMalformedOrDuplicateJson() {
        List<String> hostileOutputs = List.of(
                decisionWithAction("""
                        {"schemaVersion":1,"type":"DELETE_PLAYER"}
                        """),
                """
                        {"schemaVersion":1,"reply":"","reason":"x","actions":[],"admin":true}
                        """,
                decisionWithAction("""
                        {"schemaVersion":1,"type":"REST","command":"rm -rf /"}
                        """),
                """
                        {"schemaVersion":1,"reply":"","reason":"x","actions":"REST"}
                        """,
                """
                        {"schemaVersion":1,"schemaVersion":1,"reply":"","reason":"x","actions":[]}
                        """,
                "```json\n{}\n```");

        for (String output : hostileOutputs) {
            assertThrows(AgentDecisionParseException.class, () -> parser.parse(output), output);
        }
    }

    @Test
    void rejectsBadTypesUnsupportedVersionsAndInvalidIds() {
        List<String> invalidOutputs = List.of(
                """
                        {"schemaVersion":"1","reply":"","reason":"x","actions":[]}
                        """,
                """
                        {"schemaVersion":2,"reply":"","reason":"x","actions":[]}
                        """,
                decisionWithAction("""
                        {"schemaVersion":1,"type":"GO_TO","mapId":"100000000"}
                        """),
                decisionWithAction("""
                        {"schemaVersion":1,"type":"GO_TO","mapId":-1}
                        """),
                decisionWithAction("""
                        {"schemaVersion":1,"type":"FOLLOW","characterId":-7}
                        """));

        for (String output : invalidOutputs) {
            assertThrows(AgentDecisionParseException.class, () -> parser.parse(output), output);
        }
    }

    @Test
    void parsesMapZeroAsAValidMapId() {
        AgentDecision decision = parser.parse(decisionWithAction(
                """
                {"schemaVersion":1,"type":"GO_TO","mapId":0}
                """));

        assertEquals(List.of(new CompanionAction.GoTo(0)), decision.actions());
    }

    @Test
    void rejectsActionCountAboveLimit() {
        String actions = java.util.stream.IntStream.range(0, AgentDecision.MAX_ACTIONS + 1)
                .mapToObj(ignored -> """
                        {"schemaVersion":1,"type":"REST"}""")
                .collect(java.util.stream.Collectors.joining(","));

        assertThrows(AgentDecisionParseException.class, () -> parser.parse(
                """
                {"schemaVersion":1,"reply":"","reason":"x","actions":[%s]}
                """.formatted(actions)));
    }

    @Test
    void rejectsOverlongReplyAndSayText() {
        String tooLong = "x".repeat(CompanionAction.MAX_CHAT_LENGTH + 1);

        assertThrows(AgentDecisionParseException.class, () -> parser.parse(
                """
                {"schemaVersion":1,"reply":"%s","reason":"x","actions":[]}
                """.formatted(tooLong)));
        assertThrows(AgentDecisionParseException.class, () -> parser.parse(
                decisionWithAction("""
                        {"schemaVersion":1,"type":"SAY","text":"%s"}
                        """.formatted(tooLong))));
    }

    @Test
    void acceptsGoodbyeAsAllowlistedTerminalAction() {
        AgentDecision decision = parser.parse(decisionWithAction(
                """
                {"schemaVersion":1,"type":"GOODBYE"}
                """));

        assertEquals(List.of(new CompanionAction.Goodbye()), decision.actions());
    }

    private static String decisionWithAction(String action) {
        return """
                {"schemaVersion":1,"reply":"","reason":"test","actions":[%s]}
                """.formatted(action);
    }
}
