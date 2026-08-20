package soloMapling.companion.memory;

import org.junit.jupiter.api.Test;
import soloMapling.companion.persistence.CompanionMemoryRepository;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConsolidationServiceTest {

    private static final Instant OCCURRED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REFERENCE = OCCURRED.plus(Duration.ofDays(90));

    @Test
    void ordinaryMemoryDecaysBelowThresholdAndIsArchived() {
        MemoryConsolidationService service =
                new MemoryConsolidationService(new FakeRepository());

        MemoryConsolidationService.Plan plan =
                service.plan(7, REFERENCE, List.of(memory("1", MemoryType.EPISODIC, 0.5)));

        assertEquals(List.of("1"), plan.archives().stream()
                .map(MemoryConsolidationService.ArchiveAction::memoryId)
                .toList());
    }

    @Test
    void maintenanceFrequencyDoesNotCompoundEffectiveStrengthDecay() {
        FakeRepository repository = new FakeRepository();
        MemoryConsolidationService service = new MemoryConsolidationService(repository);
        MemoryRecord candidate = memory("1", MemoryType.EPISODIC, 0.5);

        MemoryConsolidationService.Plan early =
                service.plan(7, OCCURRED.plus(Duration.ofDays(30)), List.of(candidate));
        assertTrue(early.archives().isEmpty());
        assertTrue(service.apply(early).successful());

        MemoryConsolidationService.Plan afterEarlyMaintenance =
                service.plan(7, REFERENCE, List.of(candidate));
        MemoryConsolidationService.Plan withoutEarlyMaintenance =
                service.plan(7, REFERENCE, List.of(candidate));

        assertEquals(
                withoutEarlyMaintenance.archives(), afterEarlyMaintenance.archives());
        assertEquals(List.of("1"), afterEarlyMaintenance.archives().stream()
                .map(MemoryConsolidationService.ArchiveAction::memoryId)
                .toList());
        assertEquals(0, repository.strengthUpdates);
    }

    @Test
    void commitmentUsesLongerHalfLifeAndSurvives() {
        MemoryConsolidationService service =
                new MemoryConsolidationService(new FakeRepository());

        MemoryConsolidationService.Plan plan =
                service.plan(7, REFERENCE, List.of(memory("1", MemoryType.COMMITMENT, 0.5)));

        assertTrue(plan.archives().isEmpty());
    }

    @Test
    void highSalienceMemoryReceivesExtendedProtection() {
        MemoryConsolidationService service =
                new MemoryConsolidationService(new FakeRepository());

        MemoryConsolidationService.Plan plan =
                service.plan(7, REFERENCE, List.of(memory("1", MemoryType.EPISODIC, 0.9)));

        assertTrue(plan.archives().isEmpty());
    }

    @Test
    void duplicateEpisodesProduceOneStableBoundedSemanticProposal() {
        FakeRepository repository = new FakeRepository();
        MemoryConsolidationService service = new MemoryConsolidationService(
                repository,
                episodes -> "A deliberately long generated semantic summary",
                new MemoryConsolidationService.Configuration(
                        0.15,
                        Duration.ofDays(30),
                        Duration.ofDays(180),
                        0.8,
                        3,
                        2,
                        20));
        MemoryRecord first = episode("2", "Met Alice", Set.of("friend", "quest"));
        MemoryRecord second = episode("1", "Helped Alice", Set.of("quest", "friend"));

        MemoryConsolidationService.Plan forward =
                service.plan(7, REFERENCE, List.of(first, second));
        MemoryConsolidationService.Plan reverse =
                service.plan(7, REFERENCE, List.of(second, first));

        assertEquals(forward, reverse);
        assertEquals(1, forward.summaryProposals().size());
        MemoryConsolidationService.SummaryProposal proposal =
                forward.summaryProposals().getFirst();
        assertEquals(List.of("1", "2"), proposal.sourceMemoryIds());
        assertEquals(MemoryType.SEMANTIC, proposal.memory().type());
        assertEquals("42", proposal.memory().actorKey());
        assertEquals("100000000", proposal.memory().mapKey());
        assertTrue(proposal.memory().content().length() <= 20);
        assertTrue(proposal.memory().tags().contains(proposal.consolidationTag()));
    }

    @Test
    void applyDoesNotInsertSameConsolidationTwice() {
        FakeRepository repository = new FakeRepository();
        MemoryConsolidationService service = new MemoryConsolidationService(repository);
        MemoryConsolidationService.Plan plan = service.plan(
                7,
                REFERENCE,
                List.of(
                        episode("1", "Met Alice", Set.of("friend")),
                        episode("2", "Helped Alice", Set.of("friend"))));

        MemoryConsolidationService.ApplyResult first = service.apply(plan);
        MemoryConsolidationService.ApplyResult second = service.apply(plan);

        assertTrue(first.successful());
        assertTrue(second.successful());
        assertEquals(1, repository.inserted.size());
        assertEquals(Set.of(1L, 2L), repository.archived);
        assertTrue(second.completed().stream().anyMatch(action ->
                action.type() == MemoryConsolidationService.ActionType.SUMMARY_ALREADY_PRESENT));
    }

    @Test
    void newEpisodeBatchProducesANewConsolidationKey() {
        FakeRepository repository = new FakeRepository();
        MemoryConsolidationService service = new MemoryConsolidationService(repository);
        MemoryConsolidationService.Plan first = service.plan(
                7,
                REFERENCE,
                List.of(
                        episode("1", "Met Alice", Set.of("friend")),
                        episode("2", "Helped Alice", Set.of("friend"))));
        assertTrue(service.apply(first).successful());

        MemoryConsolidationService.Plan next = service.plan(
                7,
                REFERENCE.plusSeconds(1),
                List.of(
                        repository.inserted.getFirst(),
                        episode("3", "Met Alice again", Set.of("friend")),
                        episode("4", "Helped Alice again", Set.of("friend"))));

        assertEquals(1, next.summaryProposals().size());
        assertEquals(List.of("3", "4"), next.summaryProposals().getFirst().sourceMemoryIds());
        assertFalse(first.summaryProposals().getFirst().consolidationTag()
                .equals(next.summaryProposals().getFirst().consolidationTag()));
    }

    @Test
    void summaryInsertFailureLeavesSourceEpisodesActive() {
        FakeRepository repository = new FakeRepository();
        repository.failInsert = true;
        MemoryConsolidationService service = new MemoryConsolidationService(repository);
        MemoryConsolidationService.Plan plan = service.plan(
                7,
                REFERENCE,
                List.of(
                        episode("1", "Met Alice", Set.of("friend")),
                        episode("2", "Helped Alice", Set.of("friend"))));
        plan = new MemoryConsolidationService.Plan(
                plan.companionId(),
                plan.referenceTime(),
                List.of(new MemoryConsolidationService.ArchiveAction(1L, "1")),
                plan.summaryProposals());

        MemoryConsolidationService.ApplyResult result = service.apply(plan);

        assertFalse(result.successful());
        assertTrue(repository.archived.isEmpty());
        assertTrue(result.failure().action().startsWith("persist-summary:"));
    }

    @Test
    void retryWithExistingSummaryCompletesRemainingSourceArchives() {
        FakeRepository repository = new FakeRepository();
        repository.failArchiveOnceId = 2L;
        MemoryConsolidationService service = new MemoryConsolidationService(repository);
        MemoryConsolidationService.Plan plan = service.plan(
                7,
                REFERENCE,
                List.of(
                        episode("1", "Met Alice", Set.of("friend")),
                        episode("2", "Helped Alice", Set.of("friend"))));

        MemoryConsolidationService.ApplyResult first = service.apply(plan);
        MemoryConsolidationService.ApplyResult retry = service.apply(plan);

        assertFalse(first.successful());
        assertEquals("archive-source:2", first.failure().action());
        assertTrue(retry.successful());
        assertEquals(1, repository.inserted.size());
        assertEquals(Set.of(1L, 2L), repository.archived);
        assertTrue(retry.completed().stream().anyMatch(action ->
                action.type() == MemoryConsolidationService.ActionType.SUMMARY_ALREADY_PRESENT));
    }

    @Test
    void repositoryFailureIsReturnedAsTypedAuditableResult() {
        FakeRepository repository = new FakeRepository();
        repository.failArchiveOnceId = 1L;
        MemoryConsolidationService service = new MemoryConsolidationService(repository);
        MemoryConsolidationService.Plan plan =
                service.plan(7, REFERENCE, List.of(memory("1", MemoryType.EPISODIC, 0.5)));

        MemoryConsolidationService.ApplyResult result = service.apply(plan);

        assertFalse(result.successful());
        assertTrue(result.completed().isEmpty());
        assertNotNull(result.failure());
        assertEquals(
                MemoryConsolidationService.FailureType.REPOSITORY_FAILURE,
                result.failure().type());
        assertEquals("archive:1", result.failure().action());
        assertNotNull(result.failure().cause());
    }

    private static MemoryRecord episode(String id, String content, Set<String> tags) {
        return new MemoryRecord(
                id,
                MemoryType.EPISODIC,
                content,
                0.7,
                0.8,
                REFERENCE.minus(Duration.ofDays(1)),
                null,
                "42",
                "100000000",
                tags,
                false);
    }

    private static MemoryRecord memory(String id, MemoryType type, double salience) {
        return new MemoryRecord(
                id,
                type,
                "Remember this",
                salience,
                0.5,
                OCCURRED,
                null,
                null,
                null,
                Set.of(),
                false);
    }

    private static final class FakeRepository implements CompanionMemoryRepository {

        private final List<MemoryRecord> inserted = new ArrayList<>();
        private final Set<String> persistedTags = new HashSet<>();
        private final Set<Long> archived = new HashSet<>();
        private boolean failInsert;
        private Long failArchiveOnceId;
        private int strengthUpdates;

        @Override
        public long insert(
                int companionCharacterId,
                String role,
                int importance,
                Instant expiresAt,
                MemoryRecord memory) throws SQLException {
            if (failInsert) {
                throw new SQLException("simulated summary insert failure");
            }
            inserted.add(memory);
            persistedTags.addAll(memory.tags());
            return inserted.size();
        }

        @Override
        public List<MemoryRecord> findCandidates(
                int companionCharacterId,
                Integer sourceCharacterId,
                Integer mapId,
                MemoryType type,
                boolean archived,
                Instant occurredFrom,
                Instant occurredTo,
                int limit) {
            return List.copyOf(inserted);
        }

        @Override
        public void markRecalled(long memoryId, Instant recalledAt) {
        }

        @Override
        public void archive(long memoryId) throws SQLException {
            archiveIfActive(memoryId);
        }

        @Override
        public void archiveIfActive(long memoryId) throws SQLException {
            if (failArchiveOnceId != null && failArchiveOnceId == memoryId) {
                failArchiveOnceId = null;
                throw new SQLException("simulated archive failure");
            }
            archived.add(memoryId);
        }

        @Override
        public void updateStrength(long memoryId, double strength) {
            strengthUpdates++;
        }

        @Override
        public boolean existsWithTag(int companionCharacterId, String tag) {
            return persistedTags.contains(tag);
        }
    }
}
