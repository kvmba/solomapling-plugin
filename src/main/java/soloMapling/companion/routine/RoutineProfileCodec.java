package soloMapling.companion.routine;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strict, versioned persistence format for recurring daily routines.
 *
 * <p>{@code v1|09:00-12:00=TRAIN,13:00-17:00=SOCIAL}. A blank value is the
 * backwards-compatible safe default: an all-offline schedule.</p>
 */
public final class RoutineProfileCodec {
    private static final String VERSION_PREFIX = "v1|";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private RoutineProfileCodec() {
    }

    public static RoutineSchedule parse(String timezone, String profile) {
        final ZoneId zone;
        try {
            zone = ZoneId.of(requireText(timezone, "routine timezone"));
        } catch (RuntimeException exception) {
            throw new RoutineProfileParseException(
                    "INVALID_TIMEZONE", "Invalid routine timezone: " + timezone, exception);
        }

        if (profile == null || profile.isBlank()) {
            return new RoutineSchedule(zone, List.of());
        }
        if (!profile.startsWith(VERSION_PREFIX)) {
            throw new RoutineProfileParseException(
                    "UNSUPPORTED_VERSION", "Routine profile must start with " + VERSION_PREFIX);
        }

        String body = profile.substring(VERSION_PREFIX.length());
        if (body.isEmpty()) {
            throw new RoutineProfileParseException(
                    "EMPTY_V1_PROFILE", "v1 routine profile must contain at least one block");
        }

        List<RoutineBlock> blocks = new ArrayList<>();
        String[] encodedBlocks = body.split(",", -1);
        for (int index = 0; index < encodedBlocks.length; index++) {
            blocks.add(parseBlock(encodedBlocks[index], index));
        }
        try {
            return new RoutineSchedule(zone, blocks);
        } catch (IllegalArgumentException exception) {
            throw new RoutineProfileParseException(
                    "OVERLAPPING_BLOCKS", exception.getMessage(), exception);
        }
    }

    private static RoutineBlock parseBlock(String encoded, int index) {
        if (!encoded.equals(encoded.trim()) || encoded.isEmpty()) {
            throw invalidBlock(index, "blocks must be non-empty and contain no whitespace");
        }
        int equals = encoded.indexOf('=');
        int dash = encoded.indexOf('-');
        if (dash != 5 || equals != 11 || encoded.indexOf('=', equals + 1) >= 0) {
            throw invalidBlock(index, "expected HH:mm-HH:mm=ACTIVITY");
        }

        LocalTime start = parseTime(encoded.substring(0, 5), index);
        LocalTime end = parseTime(encoded.substring(6, 11), index);
        final RoutineActivity activity;
        try {
            activity = RoutineActivity.valueOf(encoded.substring(12));
        } catch (IllegalArgumentException exception) {
            throw invalidBlock(index, "unknown activity");
        }
        if (activity == RoutineActivity.OFFLINE) {
            throw invalidBlock(index, "OFFLINE is represented by schedule gaps");
        }
        try {
            return new RoutineBlock(start, end, activity);
        } catch (IllegalArgumentException exception) {
            throw invalidBlock(index, exception.getMessage());
        }
    }

    private static LocalTime parseTime(String value, int index) {
        try {
            return LocalTime.parse(value, TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw invalidBlock(index, "invalid time " + value);
        }
    }

    private static RoutineProfileParseException invalidBlock(int index, String detail) {
        return new RoutineProfileParseException(
                "INVALID_BLOCK", "Invalid routine block " + index + ": " + detail);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public static final class RoutineProfileParseException extends IllegalArgumentException {
        private final String code;

        public RoutineProfileParseException(String code, String message) {
            super(message);
            this.code = code;
        }

        public RoutineProfileParseException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
