package soloMapling.companion.routine;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable recurring schedule. Uncovered times are deliberately OFFLINE.
 */
public final class RoutineSchedule {
    private final ZoneId zoneId;
    private final List<RoutineBlock> blocks;

    public RoutineSchedule(ZoneId zoneId, List<RoutineBlock> blocks) {
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId must not be null");
        Objects.requireNonNull(blocks, "blocks must not be null");
        if (blocks.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("blocks must not contain null");
        }
        rejectOverlaps(blocks);
        this.blocks = blocks.stream()
                .sorted(Comparator.comparing(RoutineBlock::start))
                .toList();
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public List<RoutineBlock> blocks() {
        return blocks;
    }

    public Optional<RoutineBlock> blockAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        LocalTime localTime = instant.atZone(zoneId).toLocalTime();
        return blocks.stream().filter(block -> block.contains(localTime)).findFirst();
    }

    public RoutineActivity activityAt(Instant instant) {
        return blockAt(instant).map(RoutineBlock::activity).orElse(RoutineActivity.OFFLINE);
    }

    public boolean hasGaps() {
        if (blocks.isEmpty()) {
            return true;
        }
        long coveredNanos = blocks.stream().mapToLong(RoutineSchedule::durationNanos).sum();
        return coveredNanos < 86_400_000_000_000L;
    }

    private static void rejectOverlaps(List<RoutineBlock> blocks) {
        for (int first = 0; first < blocks.size(); first++) {
            for (int second = first + 1; second < blocks.size(); second++) {
                if (overlaps(blocks.get(first), blocks.get(second))) {
                    throw new IllegalArgumentException(
                            "routine blocks overlap: " + blocks.get(first) + " and " + blocks.get(second));
                }
            }
        }
    }

    private static boolean overlaps(RoutineBlock first, RoutineBlock second) {
        for (long[] left : segments(first)) {
            for (long[] right : segments(second)) {
                if (left[0] < right[1] && right[0] < left[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<long[]> segments(RoutineBlock block) {
        long start = block.start().toNanoOfDay();
        long end = block.end().toNanoOfDay();
        long day = 86_400_000_000_000L;
        if (block.crossesMidnight()) {
            return List.of(new long[]{start, day}, new long[]{0, end});
        }
        return List.of(new long[]{start, end});
    }

    private static long durationNanos(RoutineBlock block) {
        long start = block.start().toNanoOfDay();
        long end = block.end().toNanoOfDay();
        return block.crossesMidnight()
                ? 86_400_000_000_000L - start + end
                : end - start;
    }
}
