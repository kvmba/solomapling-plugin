package soloMapling.companion.agent;

import soloMapling.companion.planner.CompanionPlannerResult;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Tick-owned companion macro state machine. Async completions only enqueue results;
 * all state transitions and game-facing execution happen in {@link #tick}.
 */
public final class TurnCoordinator {

    public enum State { ROUTINE, ENGAGED, PLANNING, EXECUTING, COOLDOWN }

    public record Message(int playerCharacterId, String content) {
        public Message {
            if (playerCharacterId <= 0) {
                throw new IllegalArgumentException("playerCharacterId must be positive");
            }
            content = Objects.requireNonNull(content, "content").trim();
            if (content.isEmpty()) {
                throw new IllegalArgumentException("content must not be blank");
            }
            if (content.length() > CompanionBrain.MAX_PLAYER_MESSAGE_LENGTH) {
                throw new IllegalArgumentException(
                        "content exceeds " + CompanionBrain.MAX_PLAYER_MESSAGE_LENGTH + " characters");
            }
        }
    }

    public record PlannedTurn(long turnId, Message message, CompanionPlannerResult result) {
    }

    private record AsyncResult(long turnId, Message message, CompanionPlannerResult result) {
    }

    private final long sessionTimeoutMs;
    private final long planningTimeoutMs;
    private final long cooldownMs;
    private final LongSupplier clock;
    private final ConcurrentLinkedQueue<Message> messages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AsyncResult> results = new ConcurrentLinkedQueue<>();

    private State state = State.ROUTINE;
    private long nextTurnId;
    private long activeTurnId;
    private long planningDeadline;
    private long cooldownUntil;
    private long sessionDeadline;
    private volatile int sessionPlayerId;
    private Message activeMessage;

    public TurnCoordinator(Duration sessionTimeout, Duration planningTimeout, Duration cooldown) {
        this(sessionTimeout, planningTimeout, cooldown, System::currentTimeMillis);
    }

    TurnCoordinator(
            Duration sessionTimeout,
            Duration planningTimeout,
            Duration cooldown,
            LongSupplier clock) {
        this.sessionTimeoutMs = positiveMillis(sessionTimeout, "sessionTimeout");
        this.planningTimeoutMs = positiveMillis(planningTimeout, "planningTimeout");
        this.cooldownMs = nonNegativeMillis(cooldown, "cooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Safe on event, Netty, dispatcher, or test threads: enqueue only. */
    public synchronized boolean enqueue(Message message) {
        Objects.requireNonNull(message, "message");
        if (sessionPlayerId != 0 && sessionPlayerId != message.playerCharacterId()) {
            return false;
        }
        sessionPlayerId = message.playerCharacterId();
        messages.add(message);
        return true;
    }

    /** Event-owned turn (for example a live party invite); bypasses chat-session ownership. */
    public synchronized void enqueueEvent(Message message) {
        Objects.requireNonNull(message, "message");
        sessionPlayerId = message.playerCharacterId();
        messages.add(message);
    }

    public State state() {
        return state;
    }

    public boolean planning() {
        return state == State.PLANNING;
    }

    public boolean acceptsContinuation(int playerCharacterId) {
        return playerCharacterId > 0 && sessionPlayerId == playerCharacterId;
    }

    public void tick(
            Function<Message, CompletionStage<CompanionPlannerResult>> planner,
            Consumer<PlannedTurn> executor) {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(executor, "executor");
        long now = clock.getAsLong();

        if (state == State.PLANNING) {
            AsyncResult completed;
            while ((completed = results.poll()) != null) {
                if (completed.turnId == activeTurnId) {
                    state = State.EXECUTING;
                    executor.accept(new PlannedTurn(
                            completed.turnId, completed.message, completed.result));
                    enterCooldown(now);
                    return;
                }
            }
            if (now >= planningDeadline) {
                state = State.EXECUTING;
                executor.accept(new PlannedTurn(
                        activeTurnId,
                        activeMessage,
                        CompanionPlannerResult.Failure.of(
                                CompanionPlannerResult.FailureType.TIMEOUT,
                                "Companion planning timed out")));
                enterCooldown(now);
            }
            return;
        }

        if (state == State.COOLDOWN && now < cooldownUntil) {
            return;
        }
        if (state == State.COOLDOWN) {
            state = now < sessionDeadline ? State.ENGAGED : State.ROUTINE;
            if (state == State.ROUTINE) {
                sessionPlayerId = 0;
            }
        }
        if (state == State.ENGAGED && now >= sessionDeadline && messages.isEmpty()) {
            state = State.ROUTINE;
            sessionPlayerId = 0;
            return;
        }

        Message message = messages.poll();
        if (message == null) {
            return;
        }
        sessionDeadline = now + sessionTimeoutMs;
        state = State.ENGAGED;
        startPlanning(message, planner, now);
    }

    private void startPlanning(
            Message message,
            Function<Message, CompletionStage<CompanionPlannerResult>> planner,
            long now) {
        long turnId = ++nextTurnId;
        activeTurnId = turnId;
        activeMessage = message;
        planningDeadline = now + planningTimeoutMs;
        state = State.PLANNING;
        final CompletionStage<CompanionPlannerResult> future;
        try {
            future = planner.apply(message);
        } catch (Throwable error) {
            results.add(new AsyncResult(turnId, message, providerFailure()));
            return;
        }
        if (future == null) {
            results.add(new AsyncResult(turnId, message, providerFailure()));
            return;
        }
        future.whenComplete((result, error) -> results.add(new AsyncResult(
                turnId,
                message,
                error == null && result != null ? result : providerFailure())));
    }

    private void enterCooldown(long now) {
        activeTurnId = 0;
        activeMessage = null;
        cooldownUntil = now + cooldownMs;
        state = State.COOLDOWN;
    }

    private static CompanionPlannerResult providerFailure() {
        return CompanionPlannerResult.Failure.of(
                CompanionPlannerResult.FailureType.PROVIDER_FAILURE,
                "Companion planning failed");
    }

    private static long positiveMillis(Duration value, String field) {
        long millis = nonNegativeMillis(value, field);
        if (millis == 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return millis;
    }

    private static long nonNegativeMillis(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value.toMillis();
    }
}
