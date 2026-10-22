package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrindTickRegistryTest {

    @Test
    void trainingBotUsesTheSharedParticipantBoundary() {
        assertTrue(GrindTickRegistry.Participant.class.isAssignableFrom(TrainingBot.class));
    }

    @Test
    void duplicateRegistrationStillTicksParticipantOnce() {
        AtomicInteger tickerStarts = new AtomicInteger();
        GrindTickRegistry registry = new GrindTickRegistry(ticker -> {
            tickerStarts.incrementAndGet();
            return null;
        });
        AtomicInteger ticks = new AtomicInteger();
        GrindTickRegistry.Participant participant = ticks::incrementAndGet;
        try {
            registry.register(participant);
            registry.register(participant);

            registry.sweep();

            assertEquals(1, ticks.get());
            assertEquals(1, tickerStarts.get());
        } finally {
            registry.unregister(participant);
        }
    }

    @Test
    void isolatesParticipantFailureAndSupportsUnregister() {
        GrindTickRegistry registry = new GrindTickRegistry(ticker -> null);
        AtomicInteger healthyTicks = new AtomicInteger();
        GrindTickRegistry.Participant broken = () -> {
            throw new AssertionError("broken participant");
        };
        GrindTickRegistry.Participant healthy = healthyTicks::incrementAndGet;
        try {
            registry.register(broken);
            registry.register(healthy);
            registry.sweep();
            assertEquals(1, healthyTicks.get());

            registry.unregister(healthy);
            registry.sweep();
            assertEquals(1, healthyTicks.get());
        } finally {
            registry.unregister(broken);
            registry.unregister(healthy);
        }
    }

    @Test
    void failedSchedulerStartRollsBackParticipantAndAllowsRetry() {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<Runnable> scheduledTicker = new AtomicReference<>();
        GrindTickRegistry registry = new GrindTickRegistry(ticker -> {
            if (starts.getAndIncrement() == 0) {
                throw new IllegalStateException("scheduler unavailable");
            }
            scheduledTicker.set(ticker);
            return null;
        });
        AtomicInteger ticks = new AtomicInteger();
        GrindTickRegistry.Participant participant = ticks::incrementAndGet;

        assertThrows(IllegalStateException.class, () -> registry.register(participant));
        assertEquals(0, registry.participantCount(),
                "failed startup must roll back the registering participant");

        registry.register(participant);
        assertEquals(2, starts.get());
        assertEquals(1, registry.participantCount());

        scheduledTicker.get().run();
        assertEquals(1, ticks.get());
    }

    @Test
    void shutdownCancelsTheArmedSweepAndLatchesSoItCannotRearm() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger starts = new AtomicInteger();
        GrindTickRegistry registry = new GrindTickRegistry(ticker -> {
            starts.incrementAndGet();
            return new CancelledFuture(cancelled);
        });
        GrindTickRegistry.Participant participant = () -> { };

        registry.register(participant);
        registry.shutdown();

        assertTrue(cancelled.get(), "the periodic sweep must be cancelled, not just emptied");
        assertEquals(0, registry.participantCount());

        // A late registration after unload must not arm a second sweep that nobody can cancel.
        registry.register(participant);
        assertEquals(1, starts.get(), "shutdown must keep the registry latched off");
    }

    /** Minimal ScheduledFuture whose only observable behaviour here is cancel(). */
    private static final class CancelledFuture implements java.util.concurrent.ScheduledFuture<Object> {
        private final AtomicBoolean cancelled;

        CancelledFuture(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled.set(true);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, java.util.concurrent.TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(java.util.concurrent.TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }
    }
}
