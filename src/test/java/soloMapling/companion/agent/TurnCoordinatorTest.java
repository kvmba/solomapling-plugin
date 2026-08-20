package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;
import soloMapling.companion.planner.CompanionPlannerResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnCoordinatorTest {

    @Test
    void rejectsOversizedMessagesBeforeTheyReachTheQueueOrBrain() {
        String oversized = "x".repeat(CompanionBrain.MAX_PLAYER_MESSAGE_LENGTH + 1);
        assertThrows(IllegalArgumentException.class,
                () -> new TurnCoordinator.Message(7, oversized));
        assertThrows(IllegalArgumentException.class, () -> new CompanionBrain.TurnRequest(
                10, 7, oversized, snapshot()));
    }

    @Test
    void allowsOnlyOnePlanningTurnAndExecutesCompletionOnTick() {
        AtomicLong now = new AtomicLong();
        TurnCoordinator coordinator = coordinator(now);
        CompletableFuture<CompanionPlannerResult> first = new CompletableFuture<>();
        AtomicInteger plans = new AtomicInteger();
        List<TurnCoordinator.PlannedTurn> executed = new ArrayList<>();

        assertTrue(coordinator.enqueue(new TurnCoordinator.Message(7, "hello")));
        assertTrue(coordinator.enqueue(new TurnCoordinator.Message(7, "and again")));
        coordinator.tick(message -> {
            plans.incrementAndGet();
            return first;
        }, executed::add);
        coordinator.tick(message -> {
            plans.incrementAndGet();
            return CompletableFuture.completedFuture(failure());
        }, executed::add);

        assertEquals(1, plans.get());
        assertTrue(coordinator.planning());
        first.complete(failure());
        assertTrue(executed.isEmpty(), "completion callback must not execute game work");

        coordinator.tick(message -> CompletableFuture.completedFuture(failure()), executed::add);
        assertEquals(1, executed.size());
        assertEquals(TurnCoordinator.State.COOLDOWN, coordinator.state());

        now.addAndGet(101);
        coordinator.tick(message -> {
            plans.incrementAndGet();
            return CompletableFuture.completedFuture(failure());
        }, executed::add);
        assertEquals(2, plans.get());
    }

    @Test
    void timeoutDiscardsLateResultAndSessionRejectsOtherPlayers() {
        AtomicLong now = new AtomicLong();
        TurnCoordinator coordinator = coordinator(now);
        CompletableFuture<CompanionPlannerResult> late = new CompletableFuture<>();
        List<TurnCoordinator.PlannedTurn> executed = new ArrayList<>();

        assertTrue(coordinator.enqueue(new TurnCoordinator.Message(7, "hello")));
        assertFalse(coordinator.enqueue(new TurnCoordinator.Message(8, "steal session")));
        coordinator.tick(message -> late, executed::add);

        now.addAndGet(1_001);
        coordinator.tick(message -> late, executed::add);
        assertEquals(TurnCoordinator.State.COOLDOWN, coordinator.state());
        assertEquals(1, executed.size());
        assertTrue(executed.getFirst().result() instanceof CompanionPlannerResult.Failure failure
                && failure.type() == CompanionPlannerResult.FailureType.TIMEOUT);
        late.complete(failure());

        now.addAndGet(101);
        coordinator.tick(message -> late, executed::add);
        assertEquals(1, executed.size(), "late provider completion must be discarded");
    }

    @Test
    void synchronousPlannerFailureBecomesSafeFailureResult() {
        AtomicLong now = new AtomicLong();
        TurnCoordinator coordinator = coordinator(now);
        List<TurnCoordinator.PlannedTurn> executed = new ArrayList<>();
        coordinator.enqueue(new TurnCoordinator.Message(7, "hello"));

        coordinator.tick(message -> {
            throw new IllegalStateException("database unavailable");
        }, executed::add);
        coordinator.tick(message -> null, executed::add);

        assertEquals(1, executed.size());
        assertTrue(executed.getFirst().result() instanceof CompanionPlannerResult.Failure);
    }

    private static TurnCoordinator coordinator(AtomicLong now) {
        return new TurnCoordinator(
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                now::get);
    }

    private static CompanionPlannerResult failure() {
        return CompanionPlannerResult.Failure.of(
                CompanionPlannerResult.FailureType.PROVIDER_FAILURE, "failed");
    }

    private static CompanionStateSnapshot snapshot() {
        return new CompanionStateSnapshot(
                100, java.util.Set.of(7), false, java.util.Set.of(100),
                java.util.Set.of(7), java.util.Set.of(), false);
    }
}
