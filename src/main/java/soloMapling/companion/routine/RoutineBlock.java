package soloMapling.companion.routine;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A recurring daily half-open interval. An end before its start crosses midnight.
 */
public record RoutineBlock(LocalTime start, LocalTime end, RoutineActivity activity) {

    public RoutineBlock {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        Objects.requireNonNull(activity, "activity must not be null");
        if (start.equals(end)) {
            throw new IllegalArgumentException(
                    "start and end must differ; use explicit blocks for an all-day schedule");
        }
        if (activity == RoutineActivity.OFFLINE) {
            throw new IllegalArgumentException("OFFLINE is reserved for schedule gaps");
        }
    }

    public boolean crossesMidnight() {
        return end.isBefore(start);
    }

    public boolean contains(LocalTime time) {
        Objects.requireNonNull(time, "time must not be null");
        if (crossesMidnight()) {
            return !time.isBefore(start) || time.isBefore(end);
        }
        return !time.isBefore(start) && time.isBefore(end);
    }
}
