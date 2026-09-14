package soloMapling.ArtificialPlayer.BotPetSystem;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.Environment.PluginResources;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bot-pet tuning, read from the plugin's own resource file (never the host's
 * application.yml — see {@link soloMapling.companion.intake.CompanionIntakeConfig}
 * for the same reasoning). A copy dropped at any of the {@link PluginResources}
 * resolution roots wins over the packaged one, so an operator can retune without
 * a rebuild.
 *
 * <p>Read once at startup; there is no hot reload. A missing or broken file
 * falls back to the defaults below, because a bad pet config must not stop the
 * rest of the plugin from coming up.</p>
 */
public final class BotPetConfig {

    /** Relative to the {@code soloMapling/} package root. */
    public static final String RESOURCE_PATH = "ArtificialPlayer/BotPetSystem/BotPetConfig.yaml";

    // ── defaults ────────────────────────────────────────────────────────────
    private static final boolean DEF_ENABLED = true;
    private static final double DEF_P_MAX = 0.30;
    private static final int DEF_LEVEL_CAP = 120;
    private static final double DEF_EXPONENT = 1.4;
    private static final double DEF_TIER_WEIGHT = 0.25;
    private static final int DEF_MIN_LEVEL = 10;

    private static final int DEF_PET_LEVEL_BASE = 1;
    private static final int DEF_PET_LEVEL_PER_STRENGTH = 4;
    private static final int DEF_PET_LEVEL_MAX = 5;
    private static final double DEF_NAMING_CHANCE = 0.70;

    private static final int DEF_ITEM_POUCH_ID = 1812001;
    private static final double DEF_ITEM_POUCH_CHANCE = 0.60;
    private static final int DEF_MESO_MAGNET_ID = 1812000;
    private static final double DEF_MESO_MAGNET_CHANCE = 0.30;
    private static final double DEF_NAME_TAG_CHANCE = 0.70;

    private static final long DEF_FOLLOW_TICK_MS = 300L;
    private static final int DEF_EPS_PX = 25;
    private static final double DEF_FOLLOW_SPEED = 200.0;
    private static final int DEF_TELEPORT_DIST_PX = 160;
    private static final int DEF_SWIM_OFFSET = 14;

    private static final int DEF_PICKUP_MAX_PER_TICK = 1;
    private static final int DEF_PICKUP_RANGE = 120;
    private static final long DEF_PICKUP_COOLDOWN_MS = 450L;

    private static final double DEF_SPEAK_CHANCE = 0.10;
    private static final long DEF_SPEAK_MIN_INTERVAL_MS = 8_000L;
    private static final long DEF_SPEAK_MAX_INTERVAL_MS = 25_000L;

    private static final boolean DEF_PERSIST_COMPANIONS = true;
    private static final boolean DEF_EXCLUDE_FM_SHOP = true;

    /**
     * Default carry-count bands, keyed on {@code strength}. Ordered by ascending
     * {@code sMax}; the first band whose {@code sMax} the strength is below wins,
     * with the last band catching everything above.
     */
    private static final List<CountBand> DEF_COUNT_BANDS = List.of(
            new CountBand(0.25, 100, 0, 0),
            new CountBand(0.50, 80, 20, 0),
            new CountBand(0.75, 60, 32, 8),
            new CountBand(2.00, 45, 37, 18)
    );

    /** A count-distribution row: weights (per-mille) for carrying 1, 2 or 3 pets. */
    public record CountBand(double sMax, int w1, int w2, int w3) {
        public int weightFor(int count) {
            return switch (count) {
                case 1 -> w1;
                case 2 -> w2;
                case 3 -> w3;
                default -> 0;
            };
        }
    }

    private final boolean enabled;
    private final double pMax;
    private final int levelCap;
    private final double exponent;
    private final double tierWeight;
    private final int minLevel;

    private final int petLevelBase;
    private final int petLevelPerStrength;
    private final int petLevelMax;
    private final double namingChance;

    private final int itemPouchId;
    private final double itemPouchChance;
    private final int mesoMagnetId;
    private final double mesoMagnetChance;
    private final double nameTagChance;

    private final long followTickMs;
    private final int epsPx;
    private final double followSpeed;
    private final int teleportDistPx;
    private final int swimOffset;

    private final int pickupMaxPerTick;
    private final int pickupRange;
    private final long pickupCooldownMs;

    private final double speakChance;
    private final long speakMinIntervalMs;
    private final long speakMaxIntervalMs;

    private final boolean persistCompanions;
    private final boolean excludeFmShop;

    private final List<CountBand> countBands;

    private BotPetConfig(Builder b) {
        this.enabled = b.enabled;
        this.pMax = b.pMax;
        this.levelCap = b.levelCap;
        this.exponent = b.exponent;
        this.tierWeight = b.tierWeight;
        this.minLevel = b.minLevel;
        this.petLevelBase = b.petLevelBase;
        this.petLevelPerStrength = b.petLevelPerStrength;
        this.petLevelMax = b.petLevelMax;
        this.namingChance = b.namingChance;
        this.itemPouchId = b.itemPouchId;
        this.itemPouchChance = b.itemPouchChance;
        this.mesoMagnetId = b.mesoMagnetId;
        this.mesoMagnetChance = b.mesoMagnetChance;
        this.nameTagChance = b.nameTagChance;
        this.followTickMs = b.followTickMs;
        this.epsPx = b.epsPx;
        this.followSpeed = b.followSpeed;
        this.teleportDistPx = b.teleportDistPx;
        this.swimOffset = b.swimOffset;
        this.pickupMaxPerTick = b.pickupMaxPerTick;
        this.pickupRange = b.pickupRange;
        this.pickupCooldownMs = b.pickupCooldownMs;
        this.speakChance = b.speakChance;
        this.speakMinIntervalMs = b.speakMinIntervalMs;
        this.speakMaxIntervalMs = b.speakMaxIntervalMs;
        this.persistCompanions = b.persistCompanions;
        this.excludeFmShop = b.excludeFmShop;
        this.countBands = b.countBands;
    }

    public boolean enabled() { return enabled; }
    public double pMax() { return pMax; }
    public int levelCap() { return levelCap; }
    public double exponent() { return exponent; }
    public double tierWeight() { return tierWeight; }
    public int minLevel() { return minLevel; }

    public int petLevelBase() { return petLevelBase; }
    public int petLevelPerStrength() { return petLevelPerStrength; }
    public int petLevelMax() { return petLevelMax; }
    public double namingChance() { return namingChance; }

    public int itemPouchId() { return itemPouchId; }
    public double itemPouchChance() { return itemPouchChance; }
    public int mesoMagnetId() { return mesoMagnetId; }
    public double mesoMagnetChance() { return mesoMagnetChance; }
    public double nameTagChance() { return nameTagChance; }

    public long followTickMs() { return followTickMs; }
    public int epsPx() { return epsPx; }
    public double followSpeed() { return followSpeed; }
    public int teleportDistPx() { return teleportDistPx; }
    public int swimOffset() { return swimOffset; }

    public int pickupMaxPerTick() { return pickupMaxPerTick; }
    public int pickupRange() { return pickupRange; }
    public long pickupCooldownMs() { return pickupCooldownMs; }

    public double speakChance() { return speakChance; }
    public long speakMinIntervalMs() { return speakMinIntervalMs; }
    public long speakMaxIntervalMs() { return speakMaxIntervalMs; }

    public boolean persistCompanions() { return persistCompanions; }
    public boolean excludeFmShop() { return excludeFmShop; }

    public List<CountBand> countBands() { return countBands; }

    /** All defaults, no file. */
    public static BotPetConfig defaults() {
        return new Builder().build();
    }

    @SuppressWarnings("unchecked")
    public static BotPetConfig load() {
        try (Reader reader = openReader()) {
            if (reader == null) {
                return defaults();
            }
            Object parsed = new YamlReader(reader).read();
            if (!(parsed instanceof Map<?, ?> raw)) {
                return defaults();
            }
            return parse((Map<String, Object>) raw);
        } catch (Exception e) {
            System.err.println("[BotPetConfig] failed to load " + RESOURCE_PATH + ": "
                    + e.getMessage() + " — using defaults");
            return defaults();
        }
    }

    private static Reader openReader() throws Exception {
        if (PluginResources.exists(RESOURCE_PATH)) {
            return PluginResources.openReader(RESOURCE_PATH);
        }
        return null;
    }

    /** Test seam: build a config from an in-memory map (missing keys -> defaults). */
    static BotPetConfig fromMap(Map<String, Object> root) {
        return parse(root);
    }

    @SuppressWarnings("unchecked")
    private static BotPetConfig parse(Map<String, Object> root) {
        Builder b = new Builder();
        b.enabled = bool(root.get("enabled"), DEF_ENABLED);

        Map<String, Object> curve = map(root.get("curve"));
        b.pMax = dbl(curve.get("p_max"), DEF_P_MAX);
        b.levelCap = intOf(curve.get("level_cap"), DEF_LEVEL_CAP);
        b.exponent = dbl(curve.get("exponent"), DEF_EXPONENT);
        b.tierWeight = dbl(curve.get("tier_weight"), DEF_TIER_WEIGHT);
        b.minLevel = intOf(curve.get("min_level"), DEF_MIN_LEVEL);

        Map<String, Object> petLevel = map(root.get("pet_level"));
        b.petLevelBase = intOf(petLevel.get("base"), DEF_PET_LEVEL_BASE);
        b.petLevelPerStrength = intOf(petLevel.get("per_strength"), DEF_PET_LEVEL_PER_STRENGTH);
        b.petLevelMax = intOf(petLevel.get("max"), DEF_PET_LEVEL_MAX);

        Map<String, Object> naming = map(root.get("naming"));
        b.namingChance = dbl(naming.get("chance"), DEF_NAMING_CHANCE);

        Map<String, Object> gear = map(root.get("gear"));
        Map<String, Object> pouch = map(gear.get("item_pouch"));
        b.itemPouchId = intOf(pouch.get("id"), DEF_ITEM_POUCH_ID);
        b.itemPouchChance = dbl(pouch.get("chance"), DEF_ITEM_POUCH_CHANCE);
        Map<String, Object> magnet = map(gear.get("meso_magnet"));
        b.mesoMagnetId = intOf(magnet.get("id"), DEF_MESO_MAGNET_ID);
        b.mesoMagnetChance = dbl(magnet.get("chance"), DEF_MESO_MAGNET_CHANCE);
        b.nameTagChance = dbl(gear.get("name_tag_chance"), DEF_NAME_TAG_CHANCE);

        Map<String, Object> follow = map(root.get("follow"));
        b.followTickMs = lng(follow.get("tick_ms"), DEF_FOLLOW_TICK_MS);
        b.epsPx = intOf(follow.get("eps_px"), DEF_EPS_PX);
        b.followSpeed = dbl(follow.get("speed"), DEF_FOLLOW_SPEED);
        b.teleportDistPx = intOf(follow.get("teleport_dist_px"), DEF_TELEPORT_DIST_PX);
        b.swimOffset = intOf(follow.get("swim_offset"), DEF_SWIM_OFFSET);

        Map<String, Object> pickup = map(root.get("pickup"));
        b.pickupMaxPerTick = intOf(pickup.get("max_per_tick"), DEF_PICKUP_MAX_PER_TICK);
        b.pickupRange = intOf(pickup.get("range"), DEF_PICKUP_RANGE);
        b.pickupCooldownMs = lng(pickup.get("cooldown_ms"), DEF_PICKUP_COOLDOWN_MS);

        Map<String, Object> speak = map(root.get("speak"));
        b.speakChance = dbl(speak.get("chance"), DEF_SPEAK_CHANCE);
        b.speakMinIntervalMs = lng(speak.get("min_interval_ms"), DEF_SPEAK_MIN_INTERVAL_MS);
        b.speakMaxIntervalMs = lng(speak.get("max_interval_ms"), DEF_SPEAK_MAX_INTERVAL_MS);

        Map<String, Object> persist = map(root.get("persist"));
        b.persistCompanions = bool(persist.get("companions"), DEF_PERSIST_COMPANIONS);

        b.excludeFmShop = bool(root.get("exclude_fm_shop"), DEF_EXCLUDE_FM_SHOP);

        Object bandsRaw = root.get("counts");
        if (bandsRaw instanceof List<?> list && !list.isEmpty()) {
            List<CountBand> bands = new ArrayList<>();
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> band = (Map<String, Object>) m;
                List<?> w = (band.get("w") instanceof List<?> wl) ? wl : List.of();
                int w1 = w.size() > 0 ? intOf(w.get(0), 100) : 100;
                int w2 = w.size() > 1 ? intOf(w.get(1), 0) : 0;
                int w3 = w.size() > 2 ? intOf(w.get(2), 0) : 0;
                double sMax = dbl(band.get("sMax"), 2.0);
                bands.add(new CountBand(sMax, w1, w2, w3));
            }
            bands.sort((x, y) -> Double.compare(x.sMax(), y.sMax()));
            if (!bands.isEmpty()) {
                b.countBands = List.copyOf(bands);
            }
        }

        return b.build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    private static double dbl(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long lng(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static final class Builder {
        boolean enabled = DEF_ENABLED;
        double pMax = DEF_P_MAX;
        int levelCap = DEF_LEVEL_CAP;
        double exponent = DEF_EXPONENT;
        double tierWeight = DEF_TIER_WEIGHT;
        int minLevel = DEF_MIN_LEVEL;
        int petLevelBase = DEF_PET_LEVEL_BASE;
        int petLevelPerStrength = DEF_PET_LEVEL_PER_STRENGTH;
        int petLevelMax = DEF_PET_LEVEL_MAX;
        double namingChance = DEF_NAMING_CHANCE;
        int itemPouchId = DEF_ITEM_POUCH_ID;
        double itemPouchChance = DEF_ITEM_POUCH_CHANCE;
        int mesoMagnetId = DEF_MESO_MAGNET_ID;
        double mesoMagnetChance = DEF_MESO_MAGNET_CHANCE;
        double nameTagChance = DEF_NAME_TAG_CHANCE;
        long followTickMs = DEF_FOLLOW_TICK_MS;
        int epsPx = DEF_EPS_PX;
        double followSpeed = DEF_FOLLOW_SPEED;
        int teleportDistPx = DEF_TELEPORT_DIST_PX;
        int swimOffset = DEF_SWIM_OFFSET;
        int pickupMaxPerTick = DEF_PICKUP_MAX_PER_TICK;
        int pickupRange = DEF_PICKUP_RANGE;
        long pickupCooldownMs = DEF_PICKUP_COOLDOWN_MS;
        double speakChance = DEF_SPEAK_CHANCE;
        long speakMinIntervalMs = DEF_SPEAK_MIN_INTERVAL_MS;
        long speakMaxIntervalMs = DEF_SPEAK_MAX_INTERVAL_MS;
        boolean persistCompanions = DEF_PERSIST_COMPANIONS;
        boolean excludeFmShop = DEF_EXCLUDE_FM_SHOP;
        List<CountBand> countBands = DEF_COUNT_BANDS;

        BotPetConfig build() {
            return new BotPetConfig(this);
        }
    }
}
