package soloMapling.companion.agent;

/** Indicates that untrusted planner JSON did not match the strict decision schema. */
public final class AgentDecisionParseException extends IllegalArgumentException {

    public AgentDecisionParseException(String message) {
        super(message);
    }

    public AgentDecisionParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
