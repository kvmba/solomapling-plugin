package soloMapling.companion.memory;

import soloMapling.companion.persistence.CompanionMemoryRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Engine-independent, low-frequency memory maintenance. Planning is pure;
 * applying performs repository writes in the exact order represented by a plan.
 */
public final class MemoryConsolidationService {

    private static final String CONSOLIDATION_PREFIX = "consolidation:";

    private final CompanionMemoryRepository repository;
    private final MemorySummarizer summarizer;
    private final Configuration configuration;

    public MemoryConsolidationService(CompanionMemoryRepository repository) {
        this(repository, defaultSummarizer(), Configuration.defaults());
    }

    public MemoryConsolidationService(
            CompanionMemoryRepository repository,
            MemorySummarizer summarizer,
            Configuration configuration) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer must not be null");
        this.configuration =
                Objects.requireNonNull(configuration, "configuration must not be null");
    }

    /** Computes a deterministic plan without reading or writing the repository. */
    public Plan plan(
            int companionId, Instant referenceTime, Collection<MemoryRecord> candidates) {
        if (companionId <= 0) {
            throw new IllegalArgumentException("companionId must be positive");
        }
        Objects.requireNonNull(referenceTime, "referenceTime must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");

        List<MemoryRecord> ordered = candidates.stream()
                .map(memory -> Objects.requireNonNull(
                        memory, "candidates must not contain null"))
                .sorted(Comparator.comparing(MemoryRecord::id))
                .toList();
        rejectDuplicateIds(ordered);

        List<ArchiveAction> archives = new ArrayList<>();
        for (MemoryRecord memory : ordered) {
            if (memory.archived()) {
                continue;
            }
            if (effectiveStrength(memory, referenceTime) < configuration.archiveThreshold()) {
                archives.add(new ArchiveAction(numericId(memory.id()), memory.id()));
            }
        }

        Set<String> existingKeys = new TreeSet<>();
        ordered.stream()
                .filter(memory -> memory.type() == MemoryType.SEMANTIC)
                .filter(memory -> !memory.archived())
                .flatMap(memory -> memory.tags().stream())
                .filter(tag -> tag.startsWith(CONSOLIDATION_PREFIX))
                .forEach(existingKeys::add);

        List<MemoryRecord> effectiveCandidates = ordered.stream()
                .map(memory -> withStrength(
                        memory, effectiveStrength(memory, referenceTime)))
                .toList();
        Map<String, MemoryRecord> originalsById = new LinkedHashMap<>();
        ordered.forEach(memory -> originalsById.put(memory.id(), memory));
        List<MemoryRecord> activeEpisodes = MemorySelector.select(
                effectiveCandidates,
                ordered.size(),
                configuration.archiveThreshold(),
                new MemoryScorer.Context(referenceTime, null, null, Set.of()),
                MemoryScorer.Parameters.defaults())
                .stream()
                .filter(memory -> memory.type() == MemoryType.EPISODIC)
                .map(memory -> originalsById.get(memory.id()))
                .toList();

        Map<GroupKey, List<MemoryRecord>> groups = new LinkedHashMap<>();
        activeEpisodes.stream()
                .sorted(Comparator.comparing(MemoryRecord::occurredAt)
                        .thenComparing(MemoryRecord::id))
                .forEach(memory -> groups.computeIfAbsent(
                        new GroupKey(memory.actorKey(), memory.mapKey(), memory.tags()),
                        ignored -> new ArrayList<>())
                        .add(memory));

        List<SummaryProposal> summaries = groups.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= configuration.minimumGroupSize())
                .map(entry -> summaryProposal(companionId, referenceTime, entry))
                .filter(proposal -> !existingKeys.contains(proposal.consolidationTag()))
                .sorted(Comparator.comparing(SummaryProposal::consolidationTag))
                .toList();

        return new Plan(
                companionId,
                referenceTime,
                List.copyOf(archives),
                summaries);
    }

    /**
     * Applies a previously generated plan. Failures are returned with completed
     * actions so callers can safely audit and retry.
     */
    public ApplyResult apply(Plan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        List<AppliedAction> completed = new ArrayList<>();
        Set<String> proposedSourceIds = new TreeSet<>();
        plan.summaryProposals().forEach(
                proposal -> proposedSourceIds.addAll(proposal.sourceMemoryIds()));
        for (ArchiveAction archive : plan.archives()) {
            if (proposedSourceIds.contains(archive.memoryId())) {
                continue;
            }
            try {
                repository.archiveIfActive(archive.repositoryId());
                completed.add(new AppliedAction(ActionType.ARCHIVED, archive.memoryId()));
            } catch (SQLException exception) {
                return failed(completed, "archive:" + archive.memoryId(), exception);
            }
        }
        for (SummaryProposal proposal : plan.summaryProposals()) {
            try {
                if (repository.existsWithTag(
                        plan.companionId(), proposal.consolidationTag())) {
                    completed.add(new AppliedAction(
                            ActionType.SUMMARY_ALREADY_PRESENT, proposal.consolidationTag()));
                } else {
                    repository.insert(
                            plan.companionId(),
                            "semantic",
                            (int) Math.round(proposal.memory().salience() * 100.0),
                            null,
                            proposal.memory());
                    completed.add(new AppliedAction(
                            ActionType.SUMMARY_INSERTED, proposal.consolidationTag()));
                }
            } catch (SQLException exception) {
                return failed(
                        completed,
                        "persist-summary:" + proposal.consolidationTag(),
                        exception);
            }
            for (String sourceMemoryId : proposal.sourceMemoryIds()) {
                try {
                    repository.archiveIfActive(numericId(sourceMemoryId));
                    completed.add(new AppliedAction(
                            ActionType.SOURCE_ARCHIVED, sourceMemoryId));
                } catch (SQLException exception) {
                    return failed(
                            completed, "archive-source:" + sourceMemoryId, exception);
                }
            }
        }
        return new ApplyResult(List.copyOf(completed), null);
    }

    private double effectiveStrength(MemoryRecord memory, Instant referenceTime) {
        Duration halfLife = memory.type() == MemoryType.COMMITMENT
                ? configuration.commitmentHalfLife()
                : configuration.regularHalfLife();
        if (memory.salience() >= configuration.highSalienceThreshold()) {
            halfLife = halfLife.multipliedBy(configuration.highSalienceHalfLifeMultiplier());
        }
        MemoryScorer.Parameters decayOnly = new MemoryScorer.Parameters(
                0.0, 1.0, halfLife, halfLife, 0.0, 0.0, 0.0);
        return clamp(MemoryScorer.score(
                memory,
                new MemoryScorer.Context(referenceTime, null, null, Set.of()),
                decayOnly));
    }

    private SummaryProposal summaryProposal(
            int companionId,
            Instant referenceTime,
            Map.Entry<GroupKey, List<MemoryRecord>> entry) {
        GroupKey group = entry.getKey();
        List<MemoryRecord> episodes = List.copyOf(entry.getValue());
        List<String> sourceMemoryIds =
                episodes.stream().map(MemoryRecord::id).sorted().toList();
        String consolidationTag = CONSOLIDATION_PREFIX + sha256(
                companionId + "|" + safe(group.actorKey()) + "|" + safe(group.mapKey())
                        + "|" + String.join("\u001f", group.tags())
                        + "|" + sourceMemoryIds.stream()
                                .map(MemoryConsolidationService::safe)
                                .reduce((left, right) -> left + "\u001f" + right)
                                .orElseThrow());
        String content = requireSummary(summarizer.summarize(episodes));
        TreeSet<String> summaryTags = new TreeSet<>(group.tags());
        summaryTags.add("consolidated");
        summaryTags.add(consolidationTag);
        double salience = episodes.stream()
                .mapToDouble(MemoryRecord::salience)
                .max()
                .orElseThrow();
        double strength = episodes.stream()
                .mapToDouble(MemoryRecord::strength)
                .max()
                .orElseThrow();
        MemoryRecord semantic = new MemoryRecord(
                consolidationTag,
                MemoryType.SEMANTIC,
                content,
                salience,
                strength,
                referenceTime,
                null,
                group.actorKey(),
                group.mapKey(),
                summaryTags,
                false);
        return new SummaryProposal(
                consolidationTag,
                sourceMemoryIds,
                semantic);
    }

    private static MemoryRecord withStrength(MemoryRecord memory, double strength) {
        return new MemoryRecord(
                memory.id(),
                memory.type(),
                memory.content(),
                memory.salience(),
                strength,
                memory.occurredAt(),
                memory.lastRecalledAt(),
                memory.actorKey(),
                memory.mapKey(),
                memory.tags(),
                memory.archived());
    }

    private static ApplyResult failed(
            List<AppliedAction> completed, String action, SQLException exception) {
        return new ApplyResult(
                List.copyOf(completed),
                new ApplyFailure(
                        FailureType.REPOSITORY_FAILURE,
                        action,
                        exception.getMessage(),
                        exception));
    }

    private String requireSummary(String summary) {
        Objects.requireNonNull(summary, "summarizer result must not be null");
        String normalized = summary.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("summarizer result must not be blank");
        }
        if (normalized.length() <= configuration.maximumSummaryLength()) {
            return normalized;
        }
        return normalized.substring(0, configuration.maximumSummaryLength()).stripTrailing();
    }

    private static MemorySummarizer defaultSummarizer() {
        return episodes -> episodes.stream()
                .map(MemoryRecord::content)
                .reduce((left, right) -> left + " | " + right)
                .orElseThrow();
    }

    private static void rejectDuplicateIds(List<MemoryRecord> memories) {
        for (int index = 1; index < memories.size(); index++) {
            if (memories.get(index - 1).id().equals(memories.get(index).id())) {
                throw new IllegalArgumentException(
                        "duplicate candidate memory id: " + memories.get(index).id());
            }
        }
    }

    private static long numericId(String id) {
        try {
            long value = Long.parseLong(id);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "persisted memory id must be a positive integer: " + id, exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "<null>" : value.length() + ":" + value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record Configuration(
            double archiveThreshold,
            Duration regularHalfLife,
            Duration commitmentHalfLife,
            double highSalienceThreshold,
            long highSalienceHalfLifeMultiplier,
            int minimumGroupSize,
            int maximumSummaryLength) {

        public Configuration {
            if (!Double.isFinite(archiveThreshold)
                    || archiveThreshold < 0.0
                    || archiveThreshold > 1.0) {
                throw new IllegalArgumentException("archiveThreshold must be within [0, 1]");
            }
            regularHalfLife = requirePositive(regularHalfLife, "regularHalfLife");
            commitmentHalfLife = requirePositive(commitmentHalfLife, "commitmentHalfLife");
            if (commitmentHalfLife.compareTo(regularHalfLife) < 0) {
                throw new IllegalArgumentException(
                        "commitmentHalfLife must be at least regularHalfLife");
            }
            if (!Double.isFinite(highSalienceThreshold)
                    || highSalienceThreshold < 0.0
                    || highSalienceThreshold > 1.0) {
                throw new IllegalArgumentException(
                        "highSalienceThreshold must be within [0, 1]");
            }
            if (highSalienceHalfLifeMultiplier < 1) {
                throw new IllegalArgumentException(
                        "highSalienceHalfLifeMultiplier must be at least one");
            }
            if (minimumGroupSize < 2) {
                throw new IllegalArgumentException("minimumGroupSize must be at least two");
            }
            if (maximumSummaryLength <= 0) {
                throw new IllegalArgumentException("maximumSummaryLength must be positive");
            }
        }

        public static Configuration defaults() {
            return new Configuration(
                    0.15,
                    Duration.ofDays(30),
                    Duration.ofDays(180),
                    0.8,
                    3,
                    2,
                    512);
        }
    }

    public record Plan(
            int companionId,
            Instant referenceTime,
            List<ArchiveAction> archives,
            List<SummaryProposal> summaryProposals) {

        public Plan {
            if (companionId <= 0) {
                throw new IllegalArgumentException("companionId must be positive");
            }
            Objects.requireNonNull(referenceTime, "referenceTime must not be null");
            archives = List.copyOf(archives);
            summaryProposals = List.copyOf(summaryProposals);
        }
    }

    public record ArchiveAction(long repositoryId, String memoryId) {
    }

    public record SummaryProposal(
            String consolidationTag, List<String> sourceMemoryIds, MemoryRecord memory) {

        public SummaryProposal {
            sourceMemoryIds = List.copyOf(sourceMemoryIds);
        }
    }

    public enum ActionType {
        ARCHIVED,
        SOURCE_ARCHIVED,
        SUMMARY_INSERTED,
        SUMMARY_ALREADY_PRESENT
    }

    public record AppliedAction(ActionType type, String target) {
    }

    public enum FailureType {
        REPOSITORY_FAILURE
    }

    public record ApplyFailure(
            FailureType type, String action, String message, SQLException cause) {
    }

    public record ApplyResult(List<AppliedAction> completed, ApplyFailure failure) {

        public ApplyResult {
            completed = List.copyOf(completed);
        }

        public boolean successful() {
            return failure == null;
        }
    }

    private record GroupKey(String actorKey, String mapKey, Set<String> tags) {

        private GroupKey {
            tags = Collections.unmodifiableSet(new TreeSet<>(tags));
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
