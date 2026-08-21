package soloMapling.companion.gear;

import java.util.Collection;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Computes a drop-backed goal without depending on a host drop-table API.
 */
public final class CompanionDropGearPolicy {
    private static final int MAX_DROP_LOOKUPS = 64;

    private CompanionDropGearPolicy() {
    }

    public static Optional<CompanionGearGoal> chooseGoal(
            int level,
            int gender,
            int jobMask,
            Collection<CompanionGearPolicy.GearItem> cacheCandidates,
            Collection<CompanionGearPolicy.GearItem> equipped,
            GearDropSourceProvider dropSources) {
        if (CompanionGearPolicy.modeForLevel(level) != CompanionGearPolicy.Mode.DROPS
                || dropSources == null) {
            return Optional.empty();
        }
        List<CompanionGearPolicy.GearItem> remaining = new ArrayList<>(cacheCandidates);
        for (int lookup = 0; lookup < MAX_DROP_LOOKUPS; lookup++) {
            Optional<CompanionGearPolicy.GearItem> item = CompanionGearPolicy.bestUpgrade(
                    remaining, equipped, level, gender, jobMask);
            if (item.isEmpty()) {
                return Optional.empty();
            }
            CompanionGearPolicy.GearItem selected = item.orElseThrow();
            remaining.removeIf(candidate -> candidate.itemId() == selected.itemId());
            List<GearDropSourceProvider.DropSource> raw = dropSources.sourcesFor(selected.itemId());
            if (raw == null) {
                continue;
            }
            Optional<GearDropSourceProvider.DropSource> source = raw.stream()
                    .filter(candidate -> candidate != null && candidate.chance() > 0)
                    .max(Comparator.comparingDouble(GearDropSourceProvider.DropSource::chance)
                            .thenComparingInt(candidate -> -candidate.mobId())
                            .thenComparingInt(candidate -> -candidate.mapId()));
            if (source.isPresent()) {
                GearDropSourceProvider.DropSource value = source.orElseThrow();
                return Optional.of(new CompanionGearGoal(
                        selected.name(), selected.itemId(), selected.slot(), selected.requiredLevel(),
                        value.mobId(), value.mobName(), value.mapId(),
                        value.mapName(), value.chance(), value.boss()));
            }
        }
        return Optional.empty();
    }
}
