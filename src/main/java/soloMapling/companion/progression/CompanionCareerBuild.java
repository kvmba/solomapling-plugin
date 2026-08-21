package soloMapling.companion.progression;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, versioned Explorer career direction selected when a companion is
 * provisioned. The build fixes the complete job lineage before first job so AP
 * and SP planning never need to re-roll at an advancement boundary.
 */
public enum CompanionCareerBuild {
    HERO_SWORD("hero-sword", 100, 110, 111, 112),
    PALADIN_SWORD("paladin-sword", 100, 120, 121, 122),
    DARK_KNIGHT_SPEAR("dark-knight-spear", 100, 130, 131, 132),
    FIRE_POISON_ARCHMAGE("fire-poison-archmage", 200, 210, 211, 212),
    ICE_LIGHTNING_ARCHMAGE("ice-lightning-archmage", 200, 220, 221, 222),
    BISHOP("bishop", 200, 230, 231, 232),
    BOWMASTER("bowmaster", 300, 310, 311, 312),
    MARKSMAN("marksman", 300, 320, 321, 322),
    NIGHT_LORD("night-lord", 400, 410, 411, 412),
    SHADOWER("shadower", 400, 420, 421, 422),
    BUCCANEER("buccaneer", 500, 510, 511, 512),
    CORSAIR("corsair", 500, 520, 521, 522);

    public static final String RULESET_VERSION = "v083-classic-v1";

    private final String id;
    private final int firstJobId;
    private final int secondJobId;
    private final int thirdJobId;
    private final int fourthJobId;

    CompanionCareerBuild(
            String id,
            int firstJobId,
            int secondJobId,
            int thirdJobId,
            int fourthJobId
    ) {
        this.id = id;
        this.firstJobId = firstJobId;
        this.secondJobId = secondJobId;
        this.thirdJobId = thirdJobId;
        this.fourthJobId = fourthJobId;
    }

    public String id() {
        return id;
    }

    public int firstJobId() {
        return firstJobId;
    }

    public int secondJobId() {
        return secondJobId;
    }

    public int thirdJobId() {
        return thirdJobId;
    }

    public int fourthJobId() {
        return fourthJobId;
    }

    public int jobForTier(int tier) {
        return switch (tier) {
            case 1 -> firstJobId;
            case 2 -> secondJobId;
            case 3 -> thirdJobId;
            case 4 -> fourthJobId;
            default -> throw new IllegalArgumentException("tier must be between 1 and 4");
        };
    }

    public boolean containsJob(int jobId) {
        return jobId == 0
                || jobId == firstJobId
                || jobId == secondJobId
                || jobId == thirdJobId
                || jobId == fourthJobId;
    }

    public static CompanionCareerBuild parse(String value) {
        String normalized = Objects.requireNonNull(value, "career build")
                .trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(build -> build.id.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported companion career build: " + value));
    }

    public static Optional<CompanionCareerBuild> forJob(int jobId) {
        if (jobId < 100) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(build -> jobId == build.secondJobId
                        || jobId == build.thirdJobId
                        || jobId == build.fourthJobId)
                .findFirst();
    }

    public static CompanionCareerBuild forFirstJobAndSeed(int firstJobId, long personaSeed) {
        CompanionCareerBuild[] family = Arrays.stream(values())
                .filter(build -> build.firstJobId == firstJobId)
                .toArray(CompanionCareerBuild[]::new);
        if (family.length == 0) {
            throw new IllegalArgumentException("Unsupported Explorer first job: " + firstJobId);
        }
        return family[Math.floorMod(mix64(personaSeed ^ firstJobId), family.length)];
    }

    public static CompanionCareerBuild fromSeed(long personaSeed) {
        int[] families = {100, 200, 300, 400, 500};
        int firstJobId = families[Math.floorMod(
                mix64(personaSeed ^ 0x41C64E6DL), families.length)];
        return forFirstJobAndSeed(firstJobId, personaSeed);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
