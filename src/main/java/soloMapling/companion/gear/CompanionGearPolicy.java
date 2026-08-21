package soloMapling.companion.gear;

import org.gms.constants.inventory.EquipType;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure, deterministic equipment rules shared by shop, drop, and runtime code.
 */
public final class CompanionGearPolicy {
    private CompanionGearPolicy() {
    }

    public enum Mode {
        SHOP,
        DROPS
    }

    public enum Slot {
        WEAPON(0, (short) -11),
        CAP(1, (short) -1),
        COAT(1, (short) -5),
        PANTS(1, (short) -6),
        LONGCOAT(1, (short) -5),
        SHOES(1, (short) -7),
        OTHER(2, (short) 0);

        private final int priority;
        private final short destination;

        Slot(int priority, short destination) {
            this.priority = priority;
            this.destination = destination;
        }

        public int priority() {
            return priority;
        }

        public short destination() {
            return destination;
        }
    }

    public record Stats(
            int watk,
            int matk,
            int str,
            int dex,
            int intelligence,
            int luk,
            int wdef,
            int mdef) {

        public int totalAttributes() {
            return str + dex + intelligence + luk;
        }

        public int totalDefense() {
            return wdef + mdef;
        }
    }

    public record GearItem(
            int itemId,
            String name,
            Slot slot,
            int gender,
            int requiredLevel,
            int requiredJob,
            Stats stats) {

        public GearItem {
            name = name == null || name.isBlank() ? "item-" + itemId : name;
            slot = slot == null ? Slot.OTHER : slot;
            stats = stats == null ? new Stats(0, 0, 0, 0, 0, 0, 0, 0) : stats;
        }
    }

    public record Summary(List<GearItem> equipped, List<GearItem> equipBackpack) {
        public Summary {
            equipped = List.copyOf(equipped);
            equipBackpack = List.copyOf(equipBackpack);
        }

        public String describe() {
            return "equipped=" + describeItems(equipped)
                    + "; EQUIP=" + describeItems(equipBackpack);
        }

        private static String describeItems(List<GearItem> items) {
            if (items.isEmpty()) {
                return "none";
            }
            return items.stream()
                    .sorted(displayOrder())
                    .map(item -> item.slot() + ":" + item.name() + "(" + item.itemId() + ")")
                    .toList()
                    .toString();
        }
    }

    public static Mode modeForLevel(int level) {
        return level <= 40 ? Mode.SHOP : Mode.DROPS;
    }

    public static boolean canWear(
            GearItem item, int characterLevel, int characterGender, int jobMask) {
        if (item == null || item.slot() == Slot.OTHER
                || item.requiredLevel() > characterLevel) {
            return false;
        }
        boolean genderMatches = item.gender() == 2 || item.gender() == characterGender;
        boolean jobMatches = item.requiredJob() == 0
                || (item.requiredJob() & jobMask) != 0;
        return genderMatches && jobMatches;
    }

    public static Optional<GearItem> bestUpgrade(
            Collection<GearItem> candidates,
            Collection<GearItem> equipped,
            int characterLevel,
            int characterGender,
            int jobMask) {
        Map<Slot, GearItem> current = bestBySlot(equipped);
        return candidates.stream()
                .filter(item -> canWear(item, characterLevel, characterGender, jobMask))
                .filter(item -> isUpgrade(item, currentFor(item.slot(), current)))
                .sorted(upgradeOrder())
                .findFirst();
    }

    public static boolean isUpgrade(GearItem candidate, GearItem equipped) {
        return candidate != null
                && (equipped == null || compareQuality(candidate, equipped) > 0);
    }

    public static int compareQuality(GearItem left, GearItem right) {
        if (left.slot() != right.slot()) {
            int priority = Integer.compare(
                    right.slot().priority(), left.slot().priority());
            if (priority != 0) return priority;
        }
        Stats a = left.stats();
        Stats b = right.stats();
        if (left.slot() == Slot.WEAPON) {
            int offense = Integer.compare(a.watk() + a.matk(), b.watk() + b.matk());
            if (offense != 0) return offense;
            int watt = Integer.compare(a.watk(), b.watk());
            if (watt != 0) return watt;
            int matt = Integer.compare(a.matk(), b.matk());
            if (matt != 0) return matt;
        }
        int attributes = Integer.compare(a.totalAttributes(), b.totalAttributes());
        if (attributes != 0) return attributes;
        int defense = Integer.compare(a.totalDefense(), b.totalDefense());
        if (defense != 0) return defense;
        return 0;
    }

    public static Slot slotFor(EquipType type) {
        if (type == null) return Slot.OTHER;
        return switch (type) {
            case CAP -> Slot.CAP;
            case COAT -> Slot.COAT;
            case PANTS -> Slot.PANTS;
            case LONGCOAT -> Slot.LONGCOAT;
            case SHOES -> Slot.SHOES;
            case SWORD, AXE, MACE, DAGGER, WAND, STAFF, SWORD_2H, AXE_2H,
                    MACE_2H, SPEAR, POLEARM, BOW, CROSSBOW, CLAW, KNUCKLER,
                    PISTOL -> Slot.WEAPON;
            default -> Slot.OTHER;
        };
    }

    private static Map<Slot, GearItem> bestBySlot(Collection<GearItem> items) {
        Map<Slot, GearItem> result = new EnumMap<>(Slot.class);
        for (GearItem item : items) {
            if (item == null || item.slot() == Slot.OTHER) continue;
            result.merge(item.slot(), item,
                    (left, right) -> compareQuality(left, right) >= 0 ? left : right);
        }
        return result;
    }

    private static GearItem currentFor(Slot candidateSlot, Map<Slot, GearItem> current) {
        if (candidateSlot != Slot.COAT && candidateSlot != Slot.LONGCOAT) {
            return current.get(candidateSlot);
        }
        GearItem coat = current.get(Slot.COAT);
        GearItem longcoat = current.get(Slot.LONGCOAT);
        if (coat == null) return longcoat;
        if (longcoat == null) return coat;
        return compareQuality(coat, longcoat) >= 0 ? coat : longcoat;
    }

    private static Comparator<GearItem> upgradeOrder() {
        return (left, right) -> {
            int priority = Integer.compare(left.slot().priority(), right.slot().priority());
            if (priority != 0) return priority;
            int quality = compareQuality(right, left);
            if (quality != 0) return quality;
            return Integer.compare(left.itemId(), right.itemId());
        };
    }

    private static Comparator<GearItem> displayOrder() {
        return Comparator.comparingInt((GearItem item) -> item.slot().priority())
                .thenComparing(item -> item.slot().ordinal())
                .thenComparingInt(GearItem::itemId);
    }
}
