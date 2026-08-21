package soloMapling.companion.progression;

import java.util.Objects;

/**
 * Pure v0.83 regular-stat AP planner. It deliberately ignores equipment and
 * computes the deterministic target for the companion's current level.
 */
public final class CompanionApPlanner {
    private static final int MAGICIAN_SECONDARY_CAP = 165;
    private static final int BOWMAN_SECONDARY_CAP = 125;
    private static final int CROSSBOW_GUN_SECONDARY_CAP = 120;
    private static final int THIEF_SECONDARY_CAP = 160;
    private static final int WARRIOR_SECONDARY_CAP = 300;

    private CompanionApPlanner() {
    }

    public static Plan plan(
            CompanionCareerBuild build,
            int level,
            Stats current,
            int availableAp,
            int maximumStat
    ) {
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(current, "current");
        if (level < 1) {
            throw new IllegalArgumentException("level must be positive");
        }
        if (availableAp < 0) {
            throw new IllegalArgumentException("availableAp must not be negative");
        }
        if (maximumStat < 4) {
            throw new IllegalArgumentException("maximumStat must be at least four");
        }

        MutablePlan allocation = new MutablePlan(availableAp, current, maximumStat);
        applyFirstJobMinimum(build, level, allocation);
        applyRegularSecondary(build, level, allocation);
        allocation.add(primaryStat(build), allocation.remaining());

        int secondaryTarget = secondaryTarget(build, level);
        return allocation.freeze(primaryStat(build), secondaryStat(build), secondaryTarget);
    }

    private static void applyFirstJobMinimum(
            CompanionCareerBuild build, int level, MutablePlan allocation) {
        int firstJobLevel = build.firstJobId() == 200 ? 8 : 10;
        if (level < firstJobLevel) {
            return;
        }
        switch (build.firstJobId()) {
            case 100 -> allocation.addToTarget(Stat.STR, 35);
            case 200 -> allocation.addToTarget(Stat.INT, 20);
            case 300, 400 -> allocation.addToTarget(Stat.DEX, 25);
            case 500 -> allocation.addToTarget(Stat.DEX, 20);
            default -> throw new IllegalStateException(
                    "Unsupported Explorer family: " + build.firstJobId());
        }
    }

    private static void applyRegularSecondary(
            CompanionCareerBuild build, int level, MutablePlan allocation) {
        allocation.addToTarget(secondaryStat(build), secondaryTarget(build, level));
    }

    static int secondaryTarget(CompanionCareerBuild build, int level) {
        return switch (build) {
            case FIRE_POISON_ARCHMAGE, ICE_LIGHTNING_ARCHMAGE, BISHOP ->
                    Math.min(level + 3, MAGICIAN_SECONDARY_CAP);
            case BOWMASTER -> Math.min(level + 5, BOWMAN_SECONDARY_CAP);
            case MARKSMAN, CORSAIR -> Math.min(level, CROSSBOW_GUN_SECONDARY_CAP);
            case NIGHT_LORD, SHADOWER -> Math.min(
                    level < 40 ? Math.min(level * 2, 80) : level + 40,
                    THIEF_SECONDARY_CAP);
            case HERO_SWORD, PALADIN_SWORD, DARK_KNIGHT_SPEAR, BUCCANEER ->
                    Math.min(
                            level < 40 ? Math.min(level * 2, 80) : level + 40,
                            WARRIOR_SECONDARY_CAP);
        };
    }

    static Stat primaryStat(CompanionCareerBuild build) {
        return switch (build) {
            case HERO_SWORD, PALADIN_SWORD, DARK_KNIGHT_SPEAR, BUCCANEER -> Stat.STR;
            case FIRE_POISON_ARCHMAGE, ICE_LIGHTNING_ARCHMAGE, BISHOP -> Stat.INT;
            case BOWMASTER, MARKSMAN, CORSAIR -> Stat.DEX;
            case NIGHT_LORD, SHADOWER -> Stat.LUK;
        };
    }

    static Stat secondaryStat(CompanionCareerBuild build) {
        return switch (build) {
            case HERO_SWORD, PALADIN_SWORD, DARK_KNIGHT_SPEAR, BUCCANEER,
                    NIGHT_LORD, SHADOWER -> Stat.DEX;
            case FIRE_POISON_ARCHMAGE, ICE_LIGHTNING_ARCHMAGE, BISHOP -> Stat.LUK;
            case BOWMASTER, MARKSMAN, CORSAIR -> Stat.STR;
        };
    }

    public enum Stat {
        STR,
        DEX,
        INT,
        LUK
    }

    public record Stats(int str, int dex, int intStat, int luk) {
        public Stats {
            if (str < 4 || dex < 4 || intStat < 4 || luk < 4) {
                throw new IllegalArgumentException("base stats must be at least four");
            }
        }

        int value(Stat stat) {
            return switch (stat) {
                case STR -> str;
                case DEX -> dex;
                case INT -> intStat;
                case LUK -> luk;
            };
        }
    }

    public record Plan(
            int str,
            int dex,
            int intStat,
            int luk,
            int unspent,
            Stat primaryStat,
            Stat secondaryStat,
            int secondaryTarget
    ) {
        public Plan {
            if (str < 0 || dex < 0 || intStat < 0 || luk < 0 || unspent < 0) {
                throw new IllegalArgumentException("AP deltas must not be negative");
            }
        }

        public int spent() {
            return str + dex + intStat + luk;
        }

        public String summary() {
            return "apTarget=" + secondaryStat + ":" + secondaryTarget
                    + ";apDelta=STR:" + str + ",DEX:" + dex
                    + ",INT:" + intStat + ",LUK:" + luk
                    + ";apUnspent=" + unspent;
        }
    }

    private static final class MutablePlan {
        private final Stats current;
        private final int maximumStat;
        private int remaining;
        private int str;
        private int dex;
        private int intStat;
        private int luk;

        private MutablePlan(int remaining, Stats current, int maximumStat) {
            this.remaining = remaining;
            this.current = current;
            this.maximumStat = maximumStat;
        }

        private int remaining() {
            return remaining;
        }

        private void addToTarget(Stat stat, int target) {
            int currentValue = current.value(stat) + delta(stat);
            add(stat, Math.max(0, target - currentValue));
        }

        private void add(Stat stat, int requested) {
            int room = maximumStat - current.value(stat) - delta(stat);
            int points = Math.min(remaining, Math.min(Math.max(0, requested), Math.max(0, room)));
            if (points <= 0) {
                return;
            }
            switch (stat) {
                case STR -> str += points;
                case DEX -> dex += points;
                case INT -> intStat += points;
                case LUK -> luk += points;
            }
            remaining -= points;
        }

        private int delta(Stat stat) {
            return switch (stat) {
                case STR -> str;
                case DEX -> dex;
                case INT -> intStat;
                case LUK -> luk;
            };
        }

        private Plan freeze(
                Stat primaryStat, Stat secondaryStat, int secondaryTarget) {
            return new Plan(
                    str, dex, intStat, luk, remaining,
                    primaryStat, secondaryStat, secondaryTarget);
        }
    }
}
