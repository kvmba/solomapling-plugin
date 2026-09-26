package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.gms.client.Character;
import org.gms.constants.inventory.EquipType;
import soloMapling.ArtificialPlayer.BotCustomization;
import org.gms.client.BotTier;
import soloMapling.itemPool.ShopEquipPool;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fast, lightweight equipment assignment for bots at spawn time.
 * Picks from the tiny curated {@link GenericEquipPool} - no WZ lookups.
 *
 * Higher-tier bots are more likely to have gear equipped.
 * Equipment is assigned in priority order:
 *   1. Clothing (top+bottom OR overall)  - highest priority
 *   2. Weapon (generic/classless)
 *   3. Cap
 *   4. Shoes
 *   5. Cape, Gloves                      - lowest priority
 *
 * Toggle on/off via {@link #ENABLED}.
 */
public class QuickEquip {

    public static boolean ENABLED = true;

    // Base probability that a bot of this tier has ANY equipment at all.
    // Individual slots scale down from this base.
    private static final Map<BotTier, Double> TIER_EQUIP_CHANCE = Map.of(
            BotTier.S, 0.90,
            BotTier.A, 0.75,
            BotTier.B, 0.55,
            BotTier.C, 0.35,
            BotTier.D, 0.15
    );

    /**
     * Apply quick generic equipment to a bot based on tier probability.
     * Fast path - only array lookups from the pre-loaded pool.
     */
    public static void apply(Character bot) {
        if (!ENABLED) return;
        if (!GenericEquipPool.isLoaded()) {
            GenericEquipPool.load();
        }

        BotTier tier = bot.getTier();
        double base = TIER_EQUIP_CHANCE.getOrDefault(tier, 0.30);
        int level = bot.getLevel();
        int gender = bot.getGender();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // Level-30+ bots pick from the gear closest to their level (falling back
        // down the pool only when nothing near exists); younger bots keep the
        // gentle fashion-decay pick over the starter-heavy low end.
        boolean preferHigh = level >= 30;

        // 1. Clothing (most important) - full base chance.
        // Overalls (sauna robes etc.) are heavily preferred at 75%.
        // If the chosen branch has no eligible item for this bot (e.g. a level-15
        // bot rolling overall but no low-level overall exists), fall back to the
        // other branch so the bot is never left bare-torso'd.
        if (rng.nextDouble() < base) {
            boolean wantOverall = rng.nextDouble() < 0.75;
            if (wantOverall) {
                if (!tryOverall(bot, level, gender, preferHigh)) tryTopBottom(bot, level, gender, preferHigh);
            } else {
                if (!tryTopBottom(bot, level, gender, preferHigh)) tryOverall(bot, level, gender, preferHigh);
            }
        }

        // 2. Weapon - 90% of base
        if (rng.nextDouble() < base * 0.90) {
            equipFromPool(bot, "weapons", level, gender, preferHigh);
        }

        // 3. Cap - 60% of base
        if (rng.nextDouble() < base * 0.60) {
            equipFromPool(bot, "caps", level, gender, preferHigh);
        }

        // 4. Shoes - 50% of base
        if (rng.nextDouble() < base * 0.50) {
            equipFromPool(bot, "shoes", level, gender, preferHigh);
        }

        // 5. Accessories - 30% of base
        if (rng.nextDouble() < base * 0.30) {
            equipFromPool(bot, "capes", level, gender, preferHigh);
        }
        if (rng.nextDouble() < base * 0.30) {
            equipFromPool(bot, "gloves", level, gender, preferHigh);
        }
    }

    private static void equipFromPool(Character bot, String category, int level, int gender,
                                      boolean preferHigh) {
        // Shop-sold gear first, probabilistically (see ShopEquipPool.DRAW_CHANCE):
        // keep many bots in gear a real player could buy at this level, but let the
        // curated YAML pool keep rarer looks in rotation. Falls back whenever the
        // roll misses or the shop pool has nothing for this draw.
        Integer itemId = null;
        EquipType shopType = shopEquipType(category);
        if (shopType != null && shopRoll() && ShopEquipPool.isLoaded()) {
            itemId = ShopEquipPool.getRandomEquip(shopType, level, 0, gender);
        }
        if (itemId == null) {
            itemId = GenericEquipPool.getRandom(category, level, gender, preferHigh);
        }
        if (itemId != null) {
            BotCustomization.EquipBot(bot, itemId);
        }
    }

    /** One roll for "should this slot come from shop stock?" — mirrors the main chain. */
    private static boolean shopRoll() {
        return ThreadLocalRandom.current().nextDouble() < ShopEquipPool.DRAW_CHANCE;
    }

    /**
     * Maps a QuickEquip category to the EquipType the shop pool stocks.
     * Weapons here are generic/classless — shop weapons are drawn for any
     * style, since QuickEquip runs before a bot's stats are aligned.
     */
    private static EquipType shopEquipType(String category) {
        return switch (category) {
            case "caps" -> EquipType.CAP;
            case "shoes" -> EquipType.SHOES;
            case "gloves" -> EquipType.GLOVES;
            case "capes" -> EquipType.CAPE;
            case "weapons" -> EquipType.SWORD;
            default -> null;
        };
    }

    /** @return true if an overall was found and equipped. */
    private static boolean tryOverall(Character bot, int level, int gender, boolean preferHigh) {
        // Shop-sold overalls first (probabilistic), then the curated YAML pool.
        Integer id = (shopRoll() && ShopEquipPool.isLoaded())
                ? ShopEquipPool.getRandomEquip(EquipType.LONGCOAT, level, 0, gender)
                : null;
        if (id == null) {
            id = GenericEquipPool.getRandom("overalls", level, gender, preferHigh);
        }
        if (id == null) return false;
        BotCustomization.EquipBot(bot, id);
        return true;
    }

    /**
     * Both-or-nothing within each pool: shop top+bottom first (one roll for the
     * pair, so both halves come from the same pool), YAML top+bottom second,
     * so a bot never ends up wearing a shirt with no pants.
     * @return true if both pieces were found and equipped.
     */
    private static boolean tryTopBottom(Character bot, int level, int gender, boolean preferHigh) {
        if (shopRoll() && ShopEquipPool.isLoaded()) {
            Integer shopTop = ShopEquipPool.getRandomEquip(EquipType.COAT, level, 0, gender);
            Integer shopBot = ShopEquipPool.getRandomEquip(EquipType.PANTS, level, 0, gender);
            if (shopTop != null && shopBot != null) {
                BotCustomization.EquipBot(bot, shopTop);
                BotCustomization.EquipBot(bot, shopBot);
                return true;
            }
        }
        Integer topId = GenericEquipPool.getRandom("tops", level, gender, preferHigh);
        Integer botId = GenericEquipPool.getRandom("bottoms", level, gender, preferHigh);
        if (topId == null || botId == null) return false;
        BotCustomization.EquipBot(bot, topId);
        BotCustomization.EquipBot(bot, botId);
        return true;
    }
}
