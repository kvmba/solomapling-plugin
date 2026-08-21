package soloMapling.companion.agent;

import soloMapling.companion.persistence.CompanionRelationship;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Pure anti-spam policy for relationship-driven companion greetings. */
public final class ProactiveAttentionPolicy {
    private ProactiveAttentionPolicy() {
    }

    public static boolean shouldInitiate(
            Optional<CompanionRelationship> relationship,
            Instant now,
            Instant lastProactiveAt,
            boolean idle) {
        if (!idle || relationship.isEmpty()) {
            return false;
        }
        CompanionRelationship value = relationship.orElseThrow();
        if (value.familiarity() < 3 && value.affinity() < 1 && value.trust() < 1) {
            return false;
        }
        Instant baseline = later(value.lastInteractionAt(), lastProactiveAt);
        return baseline == null || !now.isBefore(baseline.plus(cooldown(value)));
    }

    public static Duration cooldown(CompanionRelationship relationship) {
        int closeness = relationship.familiarity()
                + relationship.trust() * 3
                + relationship.affinity() * 4;
        Duration base;
        if (closeness >= 30) {
            base = Duration.ofMinutes(5);
        } else if (closeness >= 12) {
            base = Duration.ofMinutes(15);
        } else {
            base = Duration.ofMinutes(30);
        }
        long stableJitterSeconds = Math.floorMod(
                relationship.companionCharacterId() * 31L
                        + relationship.relatedCharacterId() * 17L,
                121L);
        return base.plusSeconds(stableJitterSeconds);
    }

    private static Instant later(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }
}
