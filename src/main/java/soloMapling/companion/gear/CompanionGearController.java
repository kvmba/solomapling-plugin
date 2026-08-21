package soloMapling.companion.gear;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.EquipType;
import org.gms.server.ItemInformationProvider;
import org.gms.server.Shop;
import org.gms.server.ShopFactory;
import org.gms.server.ShopItem;
import org.gms.server.life.NPC;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.itemPool.EquipMetadataCache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static soloMapling.itemPool.ItemInformationProviderUtilities.getReqJobViaJobStyle;

/**
 * Thin host runtime adapter. Selection remains in the pure policy classes;
 * this class only reads native inventories/catalogs and invokes native buy/equip.
 */
public final class CompanionGearController {
    private static final Logger log = LoggerFactory.getLogger(CompanionGearController.class);
    private static final int MAX_AUTO_EQUIPS_PER_PASS = 6;
    private static final long CHECK_INTERVAL_MS = 5_000L;
    private static final long SHOP_RETRY_MS = 30 * 60_000L;
    private static final long GOAL_REFRESH_MS = 5 * 60_000L;
    private static final long RUN_TIMEOUT_MS = 180_000L;

    private long nextCheckAt;
    private long shopRetryAt;
    private long nextGoalRefreshAt;
    private ShopRun shopRun;
    private Optional<CompanionGearGoal> cachedGoal = Optional.empty();

    public boolean gearRunActive() {
        return shopRun != null;
    }

    public Optional<CompanionGearGoal> cachedGoal() {
        return cachedGoal;
    }

    public void cancel() {
        shopRun = null;
    }

    /**
     * Low-frequency lifecycle. Returns true while an equipment-shop trip owns movement.
     */
    public boolean tick(Character companion, GearDropSourceProvider dropSources) {
        if (!usable(companion)) {
            return gearRunActive();
        }
        long now = System.currentTimeMillis();
        if (now < nextCheckAt) {
            return gearRunActive();
        }
        nextCheckAt = now + CHECK_INTERVAL_MS;
        autoEquipBackpackUpgrades(companion);

        if (CompanionGearPolicy.modeForLevel(companion.getLevel())
                == CompanionGearPolicy.Mode.DROPS) {
            shopRun = null;
            if (now >= nextGoalRefreshAt) {
                nextGoalRefreshAt = now + GOAL_REFRESH_MS;
                cachedGoal = currentDropGoal(companion, dropSources);
            }
            return false;
        }
        cachedGoal = Optional.empty();
        if (shopRun != null) {
            advanceShopRun(companion, now);
            return gearRunActive();
        }
        if (now < shopRetryAt
                || companion.getMeso() <= CompanionShopGearPolicy.potionMesosReserve(
                        companion.getLevel(), companion.getMeso())) {
            return false;
        }
        int destination = CompanionGearRoute.equipmentShopFor(companion.getMapId());
        if (destination < 0) {
            shopRetryAt = now + SHOP_RETRY_MS;
            return false;
        }
        shopRun = new ShopRun(companion.getMapId(), destination, now, false, 0);
        GCMovement.travel(companion, destination);
        log.info("Companion gear shop run started cid={} fromMap={} shopMap={}",
                companion.getId(), companion.getMapId(), destination);
        return true;
    }

    private void advanceShopRun(Character companion, long now) {
        ShopRun run = shopRun;
        if (run == null) return;
        if (now - run.startedAt() > RUN_TIMEOUT_MS) {
            if (run.returning()) {
                shopRun = null;
                shopRetryAt = now + SHOP_RETRY_MS;
                log.warn("Companion gear shop return timed out cid={} returnMap={}",
                        companion.getId(), run.returnMapId());
            } else {
                returnFromShop(companion, run, now, "timeout");
            }
            return;
        }
        if (!run.returning() && companion.getMapId() == run.shopMapId()) {
            boolean bought = run.purchases() < MAX_AUTO_EQUIPS_PER_PASS
                    && purchaseCurrentShopUpgrade(companion);
            if (bought) {
                shopRun = new ShopRun(
                        run.returnMapId(), run.shopMapId(), run.startedAt(), false,
                        run.purchases() + 1);
                return;
            }
            returnFromShop(companion, run, now, "complete");
        } else if (run.returning() && companion.getMapId() == run.returnMapId()) {
            shopRun = null;
            shopRetryAt = now + SHOP_RETRY_MS;
            log.info("Companion gear shop run completed cid={} returnMap={} purchases={}",
                    companion.getId(), run.returnMapId(), run.purchases());
        }
    }

    private void returnFromShop(Character companion, ShopRun run, long now, String reason) {
        shopRun = new ShopRun(
                run.returnMapId(), run.shopMapId(), now, true, run.purchases());
        GCMovement.travel(companion, run.returnMapId());
        log.info("Companion gear shop returning cid={} reason={} purchases={}",
                companion.getId(), reason, run.purchases());
    }

    public CompanionGearPolicy.Summary summarize(Character companion) {
        Objects.requireNonNull(companion, "companion");
        return new CompanionGearPolicy.Summary(
                mapItems(companion.getInventory(InventoryType.EQUIPPED).list()),
                mapItems(companion.getInventory(InventoryType.EQUIP).list()));
    }

    public String summarizeText(Character companion) {
        return summarize(companion).describe();
    }

    /**
     * Equips newly looted upgrades through the normal host manipulator.
     */
    public int autoEquipBackpackUpgrades(Character companion) {
        if (!usable(companion)) return 0;
        int equippedCount = 0;
        for (int attempt = 0; attempt < MAX_AUTO_EQUIPS_PER_PASS; attempt++) {
            CompanionGearPolicy.Summary summary = summarize(companion);
            Optional<CompanionGearPolicy.GearItem> choice =
                    CompanionGearPolicy.bestUpgrade(
                            wearableBackpack(companion, summary.equipBackpack()),
                            summary.equipped(),
                            companion.getLevel(), companion.getGender(),
                            getReqJobViaJobStyle(companion.getJobStyle()));
            if (choice.isEmpty() || !equipFromBackpack(companion, choice.orElseThrow())) {
                break;
            }
            log.info("Companion auto-equipped upgrade cid={} item={} slot={}",
                    companion.getId(), choice.orElseThrow().itemId(),
                    choice.orElseThrow().slot());
            equippedCount++;
        }
        if (equippedCount > 0) {
            companion.saveCharToDB(true);
        }
        return equippedCount;
    }

    private List<CompanionGearPolicy.GearItem> wearableBackpack(
            Character companion, List<CompanionGearPolicy.GearItem> candidates) {
        ItemInformationProvider provider = ItemInformationProvider.getInstance();
        return candidates.stream()
                .filter(candidate -> companion.getInventory(InventoryType.EQUIP).list().stream()
                        .filter(item -> item instanceof Equip
                                && item.getItemId() == candidate.itemId()
                                && item.getPosition() > 0)
                        .map(Equip.class::cast)
                        .anyMatch(equip -> provider.canWearEquipment(
                                companion, equip, candidate.slot().destination())))
                .toList();
    }

    /**
     * Buys and equips at most one level-0..40 upgrade from an equipment shop
     * already present on the companion's current map.
     */
    public boolean purchaseCurrentShopUpgrade(Character companion) {
        if (!usable(companion)
                || CompanionGearPolicy.modeForLevel(companion.getLevel())
                != CompanionGearPolicy.Mode.SHOP) {
            return false;
        }
        CompanionGearPolicy.Summary summary = summarize(companion);
        int jobMask = getReqJobViaJobStyle(companion.getJobStyle());
        Shop shop = equipmentShopOnCurrentMap(companion);
        if (shop == null) return false;

        int reserve = CompanionShopGearPolicy.potionMesosReserve(
                companion.getLevel(), companion.getMeso());
        Optional<CompanionShopGearPolicy.ShopOffer> choice =
                CompanionShopGearPolicy.chooseUpgrade(
                        companion.getLevel(), companion.getGender(), jobMask,
                        companion.getMeso(), reserve, offers(shop, companion), summary.equipped());
        if (choice.isEmpty()) return false;

        CompanionShopGearPolicy.ShopOffer offer = choice.orElseThrow();
        int before = companion.getInventory(InventoryType.EQUIP)
                .countById(offer.item().itemId());
        withBoundClient(companion, () -> {
            shop.buy(companion.getClient(), offer.shopSlot(),
                    offer.item().itemId(), (short) 1);
            return null;
        });
        int after = companion.getInventory(InventoryType.EQUIP)
                .countById(offer.item().itemId());
        if (after <= before || !equipFromBackpack(companion, offer.item())) {
            return false;
        }
        companion.saveCharToDB(true);
        return true;
    }

    public Optional<CompanionGearGoal> currentDropGoal(
            Character companion, GearDropSourceProvider dropSources) {
        if (!usable(companion) || !EquipMetadataCache.isInitialized()) {
            return Optional.empty();
        }
        CompanionGearPolicy.Summary summary = summarize(companion);
        return CompanionDropGearPolicy.chooseGoal(
                companion.getLevel(), companion.getGender(),
                getReqJobViaJobStyle(companion.getJobStyle()),
                cacheCandidates(companion), summary.equipped(), dropSources);
    }

    private List<CompanionShopGearPolicy.ShopOffer> offers(Shop shop, Character companion) {
        List<CompanionShopGearPolicy.ShopOffer> offers = new ArrayList<>();
        List<ShopItem> catalog = shop.getItems();
        for (short slot = 0; slot < catalog.size(); slot++) {
            ShopItem shopItem = catalog.get(slot);
            CompanionGearPolicy.GearItem item = cleanGearItem(shopItem.getItemId());
            Equip equip = cleanEquip(shopItem.getItemId());
            if (item != null && equip != null
                    && item.slot() != CompanionGearPolicy.Slot.OTHER
                    && ItemInformationProvider.getInstance().canWearEquipment(
                            companion, equip, item.slot().destination())) {
                offers.add(new CompanionShopGearPolicy.ShopOffer(
                        slot, Math.max(0, shopItem.getPrice()), item));
            }
        }
        return offers;
    }

    private List<CompanionGearPolicy.GearItem> cacheCandidates(Character companion) {
        List<CompanionGearPolicy.GearItem> candidates = new ArrayList<>();
        int jobMask = getReqJobViaJobStyle(companion.getJobStyle());
        for (EquipMetadataCache.EquipEntry entry : EquipMetadataCache.get().all()) {
            if (entry.cash || entry.quest
                    || entry.reqLevel > companion.getLevel()
                    || !(entry.gender == 2 || entry.gender == companion.getGender())
                    || !(entry.reqJob == 0 || (entry.reqJob & jobMask) != 0)
                    || CompanionGearPolicy.slotFor(entry.equipType)
                    == CompanionGearPolicy.Slot.OTHER) {
                continue;
            }
            Equip equip = cleanEquip(entry.id);
            CompanionGearPolicy.Slot slot = CompanionGearPolicy.slotFor(entry.equipType);
            if (equip == null || !ItemInformationProvider.getInstance().canWearEquipment(
                    companion, equip, slot.destination())) continue;
            candidates.add(new CompanionGearPolicy.GearItem(
                    entry.id, entry.name,
                    CompanionGearPolicy.slotFor(entry.equipType),
                    entry.gender, entry.reqLevel, entry.reqJob, stats(equip)));
        }
        return candidates;
    }

    private List<CompanionGearPolicy.GearItem> mapItems(Collection<Item> items) {
        List<CompanionGearPolicy.GearItem> result = new ArrayList<>();
        for (Item item : items) {
            if (item instanceof Equip equip) {
                CompanionGearPolicy.GearItem mapped = gearItem(equip);
                if (mapped.slot() != CompanionGearPolicy.Slot.OTHER) {
                    result.add(mapped);
                }
            }
        }
        return result;
    }

    private CompanionGearPolicy.GearItem cleanGearItem(int itemId) {
        Equip equip = cleanEquip(itemId);
        return equip == null ? null : gearItem(equip);
    }

    private CompanionGearPolicy.GearItem gearItem(Equip equip) {
        ItemInformationProvider provider = ItemInformationProvider.getInstance();
        int itemId = equip.getItemId();
        Map<String, Integer> metadata = provider.getEquipStats(itemId);
        EquipType type = EquipType.getEquipTypeById(itemId);
        return new CompanionGearPolicy.GearItem(
                itemId, provider.getName(itemId),
                CompanionGearPolicy.slotFor(type),
                deriveGender(itemId),
                value(metadata, "reqLevel"),
                value(metadata, "reqJob"),
                stats(equip));
    }

    private static CompanionGearPolicy.Stats stats(Equip equip) {
        return new CompanionGearPolicy.Stats(
                equip.getWatk(), equip.getMatk(), equip.getStr(), equip.getDex(),
                equip.getInt(), equip.getLuk(), equip.getWdef(), equip.getMdef());
    }

    private static Equip cleanEquip(int itemId) {
        try {
            Item item = ItemInformationProvider.getInstance().getEquipById(itemId);
            return item instanceof Equip equip ? equip : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean equipFromBackpack(
            Character companion, CompanionGearPolicy.GearItem selected) {
        Item source = companion.getInventory(InventoryType.EQUIP).list().stream()
                .filter(item -> item instanceof Equip && item.getItemId() == selected.itemId())
                .max(Comparator.comparingInt(Item::getPosition))
                .orElse(null);
        if (source == null || selected.slot().destination() >= 0) return false;
        withBoundClient(companion, () -> {
            InventoryManipulator.equip(
                    companion.getClient(), source.getPosition(),
                    selected.slot().destination());
            return null;
        });
        Item worn = companion.getInventory(InventoryType.EQUIPPED)
                .getItem(selected.slot().destination());
        return worn != null && worn.getItemId() == selected.itemId();
    }

    private Shop equipmentShopOnCurrentMap(Character companion) {
        for (MapObject object : companion.getMap().getMapObjects()) {
            if (object.getType() != MapObjectType.NPC) continue;
            Shop shop = ShopFactory.getInstance().getShopForNPC(((NPC) object).getId());
            if (shop != null && !offers(shop, companion).isEmpty()) {
                return shop;
            }
        }
        return null;
    }

    private static boolean usable(Character companion) {
        return companion != null && companion.getMap() != null
                && companion.getClient() != null;
    }

    private static int value(Map<String, Integer> metadata, String key) {
        return metadata == null ? 0 : metadata.getOrDefault(key, 0);
    }

    private static int deriveGender(int itemId) {
        String id = Integer.toString(itemId);
        if (id.length() < 4) return 2;
        int gender = java.lang.Character.digit(id.charAt(3), 10);
        return gender >= 0 && gender <= 2 ? gender : 2;
    }

    private static <T> T withBoundClient(
            Character companion, Supplier<T> operation) {
        Client client = Objects.requireNonNull(companion.getClient(), "companion client");
        synchronized (client) {
            Character previous = client.getPlayer();
            client.setPlayer(companion);
            try {
                return operation.get();
            } finally {
                client.setPlayer(previous);
            }
        }
    }

    private record ShopRun(
            int returnMapId,
            int shopMapId,
            long startedAt,
            boolean returning,
            int purchases) {
    }
}
