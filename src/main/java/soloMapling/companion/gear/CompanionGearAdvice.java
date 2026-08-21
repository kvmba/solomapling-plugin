package soloMapling.companion.gear;

import org.gms.constants.inventory.EquipType;
import soloMapling.itemPool.EquipMetadataCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds bounded, authoritative equipment facts for one player question. */
public final class CompanionGearAdvice {
    private static final int DEFAULT_TARGET_LEVEL = 40;
    private static final int MAX_DROP_LOOKUPS = 16;
    private static final int MAX_DROP_FACTS = 3;
    private static final Pattern LEVEL = Pattern.compile("(\\d{1,3})\\s*级");
    private static final Map<String, EquipType> EQUIP_TYPES = equipTypes();

    private CompanionGearAdvice() {
    }

    public static List<String> forQuestion(
            String playerMessage, int currentMapId, GearDropSourceProvider dropSources) {
        if (playerMessage == null || playerMessage.isBlank()) {
            return List.of();
        }
        String message = playerMessage.toLowerCase(Locale.ROOT);
        List<String> facts = new ArrayList<>();
        EquipType requestedType = requestedType(message);
        if (asksWhere(message) && asksToBuy(message)) {
            facts.add(shopFact(requestedType, currentMapId));
        }
        if (asksWhere(message) && asksToFarm(message) && requestedType != null) {
            facts.addAll(dropFacts(requestedType, requestedLevel(message), dropSources));
        }
        return List.copyOf(facts);
    }

    private static String shopFact(EquipType requestedType, int currentMapId) {
        if (requestedType == EquipType.STAFF || requestedType == EquipType.WAND) {
            return "法师法杖/魔杖优先查询魔法密林武器店，mapId=101000001；"
                    + "实际是否出售指定物品仍以该 NPC 当前目录为准。";
        }
        int nearest = CompanionGearRoute.equipmentShopFor(currentMapId);
        return "1到40级普通装备主要在职业主城的武器/防具 NPC 商店购买："
                + "战士勇士部落 mapId=102000001，法师魔法密林 mapId=101000001，"
                + "弓箭手射手村 mapId=100000101，飞侠废弃都市 mapId=103000001"
                + (nearest < 0 ? "。" : "；按当前位置计算的最近装备店 mapId=" + nearest + "。");
    }

    private static List<String> dropFacts(
            EquipType type, int targetLevel, GearDropSourceProvider dropSources) {
        if (dropSources == null || !EquipMetadataCache.isInitialized()) {
            return List.of("装备或掉落数据当前不可用，不能可靠回答掉落地点。");
        }
        List<EquipMetadataCache.EquipEntry> candidates = EquipMetadataCache.get()
                .query(type)
                .nonCash()
                .levelBetween(Math.max(0, targetLevel - 15), targetLevel + 5)
                .asList().stream()
                .filter(entry -> !entry.quest)
                .sorted(Comparator
                        .comparingInt((EquipMetadataCache.EquipEntry entry) ->
                                Math.abs(entry.reqLevel - targetLevel))
                        .thenComparing(Comparator.comparingInt(
                                (EquipMetadataCache.EquipEntry entry) -> entry.reqLevel).reversed())
                        .thenComparingInt(entry -> entry.id))
                .limit(MAX_DROP_LOOKUPS)
                .toList();
        List<String> facts = new ArrayList<>();
        for (EquipMetadataCache.EquipEntry item : candidates) {
            GearDropSourceProvider.DropSource source = dropSources.sourcesFor(item.id).stream()
                    .filter(candidate -> candidate.mapId() >= 0)
                    .max(Comparator.comparingDouble(GearDropSourceProvider.DropSource::chance))
                    .orElse(null);
            if (source == null) {
                continue;
            }
            facts.add("itemId=" + item.id + ", itemName=" + item.name
                    + ", equipType=" + item.equipType + ", requiredLevel=" + item.reqLevel
                    + ", monsterId=" + source.mobId() + ", monsterName=" + source.mobName()
                    + ", mapId=" + source.mapId() + ", mapName=" + source.mapName()
                    + ", dropChance=" + source.chance() + ", boss=" + source.boss());
            if (facts.size() >= MAX_DROP_FACTS) {
                break;
            }
        }
        return facts.isEmpty()
                ? List.of("实时掉落表中没有找到约" + targetLevel + "级"
                        + type + "的可靠怪物与地图来源。")
                : facts;
    }

    private static int requestedLevel(String message) {
        Matcher matcher = LEVEL.matcher(message);
        if (!matcher.find()) {
            return DEFAULT_TARGET_LEVEL;
        }
        return Math.max(1, Math.min(200, Integer.parseInt(matcher.group(1))));
    }

    private static EquipType requestedType(String message) {
        for (Map.Entry<String, EquipType> entry : EQUIP_TYPES.entrySet()) {
            if (message.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean asksWhere(String message) {
        return message.contains("哪里") || message.contains("哪儿")
                || message.contains("哪") || message.contains("where");
    }

    private static boolean asksToBuy(String message) {
        return message.contains("买") || message.contains("购买")
                || message.contains("商店") || message.contains("shop");
    }

    private static boolean asksToFarm(String message) {
        return message.contains("打") || message.contains("刷")
                || message.contains("掉") || message.contains("爆")
                || message.contains("farm") || message.contains("drop");
    }

    private static Map<String, EquipType> equipTypes() {
        Map<String, EquipType> types = new LinkedHashMap<>();
        types.put("法杖", EquipType.STAFF);
        types.put("魔杖", EquipType.WAND);
        types.put("staff", EquipType.STAFF);
        types.put("wand", EquipType.WAND);
        types.put("枪", EquipType.SPEAR);
        types.put("矛", EquipType.POLEARM);
        types.put("弓", EquipType.BOW);
        types.put("弩", EquipType.CROSSBOW);
        types.put("短剑", EquipType.DAGGER);
        types.put("拳套", EquipType.CLAW);
        types.put("头盔", EquipType.CAP);
        types.put("帽子", EquipType.CAP);
        return Map.copyOf(types);
    }
}
