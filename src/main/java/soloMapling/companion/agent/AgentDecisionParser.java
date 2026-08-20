package soloMapling.companion.agent;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Strict parser for untrusted LLM output.
 *
 * <p>Unknown and duplicate fields, coercions, unsupported versions and action
 * types are rejected rather than ignored.</p>
 */
public final class AgentDecisionParser {

    private static final Set<String> DECISION_FIELDS =
            Set.of("schemaVersion", "reply", "reason", "actions");
    private static final Set<String> ACTION_BASE_FIELDS = Set.of("schemaVersion", "type");
    private static final int MAX_JSON_LENGTH = 32_768;

    private final ObjectMapper mapper;

    public AgentDecisionParser() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(12)
                        .maxStringLength(4_096)
                        .maxDocumentLength(MAX_JSON_LENGTH)
                        .build())
                .build();
        mapper = new ObjectMapper(factory);
    }

    public AgentDecision parse(String json) {
        if (json == null || json.isBlank()) {
            throw new AgentDecisionParseException("decision JSON must not be blank");
        }
        if (json.length() > MAX_JSON_LENGTH) {
            throw new AgentDecisionParseException("decision JSON is too large");
        }

        try {
            JsonNode root = mapper.readTree(json);
            requireObject(root, "decision");
            requireExactFields(root, DECISION_FIELDS, DECISION_FIELDS, "decision");

            int version = requireInt(root, "schemaVersion", "decision");
            String reply = requireString(root, "reply", "decision");
            String reason = requireString(root, "reason", "decision");
            JsonNode actionNodes = root.get("actions");
            if (!actionNodes.isArray()) {
                throw invalid("decision.actions must be an array");
            }
            if (actionNodes.size() > AgentDecision.MAX_ACTIONS) {
                throw invalid("decision.actions exceeds maximum of " + AgentDecision.MAX_ACTIONS);
            }

            List<CompanionAction> actions = new ArrayList<>(actionNodes.size());
            for (int index = 0; index < actionNodes.size(); index++) {
                actions.add(parseAction(actionNodes.get(index), index));
            }
            return new AgentDecision(version, reply, reason, actions);
        } catch (AgentDecisionParseException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new AgentDecisionParseException("malformed decision JSON", exception);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new AgentDecisionParseException("invalid decision: " + exception.getMessage(), exception);
        }
    }

    private CompanionAction parseAction(JsonNode node, int index) {
        String location = "decision.actions[" + index + "]";
        requireObject(node, location);
        int version = requireInt(node, "schemaVersion", location);
        if (version != CompanionAction.SCHEMA_VERSION) {
            throw invalid(location + ".schemaVersion is unsupported: " + version);
        }

        String typeText = requireString(node, "type", location);
        CompanionAction.ActionType type;
        try {
            type = CompanionAction.ActionType.valueOf(typeText);
        } catch (IllegalArgumentException exception) {
            throw invalid(location + ".type is not allowed: " + typeText);
        }

        return switch (type) {
            case SAY -> {
                requireActionFields(node, location, "text");
                yield new CompanionAction.Say(requireString(node, "text", location));
            }
            case EMOTE -> {
                requireActionFields(node, location, "emote");
                yield new CompanionAction.Emote(requireString(node, "emote", location));
            }
            case ACCEPT_PARTY -> {
                requireActionFields(node, location, "characterId");
                yield new CompanionAction.AcceptParty(requireInt(node, "characterId", location));
            }
            case INVITE_PARTY -> {
                requireActionFields(node, location, "characterId");
                yield new CompanionAction.InviteParty(requireInt(node, "characterId", location));
            }
            case FOLLOW -> {
                requireActionFields(node, location, "characterId");
                yield new CompanionAction.Follow(requireInt(node, "characterId", location));
            }
            case GO_TO -> {
                requireActionFields(node, location, "mapId");
                yield new CompanionAction.GoTo(requireInt(node, "mapId", location));
            }
            case TRAIN_WITH -> {
                requireActionFields(node, location, "characterId");
                yield new CompanionAction.TrainWith(requireInt(node, "characterId", location));
            }
            case REST -> {
                requireActionFields(node, location);
                yield new CompanionAction.Rest();
            }
            case GOODBYE -> {
                requireActionFields(node, location);
                yield new CompanionAction.Goodbye();
            }
        };
    }

    private static void requireActionFields(JsonNode node, String location, String... payloadFields) {
        Set<String> allowed = new HashSet<>(ACTION_BASE_FIELDS);
        allowed.addAll(List.of(payloadFields));
        requireExactFields(node, allowed, allowed, location);
    }

    private static void requireObject(JsonNode node, String location) {
        if (node == null || !node.isObject()) {
            throw invalid(location + " must be an object");
        }
    }

    private static void requireExactFields(
            JsonNode node, Set<String> required, Set<String> allowed, String location) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid(location + " contains unknown field: " + name);
            }
        }
        for (String name : required) {
            if (!node.has(name)) {
                throw invalid(location + " is missing field: " + name);
            }
        }
    }

    private static String requireString(JsonNode object, String field, String location) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(location + "." + field + " must be a string");
        }
        return value.textValue();
    }

    private static int requireInt(JsonNode object, String field, String location) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(location + "." + field + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static AgentDecisionParseException invalid(String message) {
        return new AgentDecisionParseException(message);
    }
}
