package soloMapling.companion.survival;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.server.Shop;
import org.gms.server.ShopFactory;
import org.gms.server.ShopItem;
import org.gms.server.StatEffect;
import org.gms.server.life.NPC;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static soloMapling.companion.survival.CompanionSurvivalPolicy.Resource.HP;

/**
 * Executes the deterministic survival loop owned by one persistent companion:
 * consume native USE items, visit a real NPC shop, sell ordinary loot under
 * inventory pressure, purchase stock with mesos, and return to training.
 */
public final class CompanionSurvivalController {
    private static final Logger log =
            LoggerFactory.getLogger(CompanionSurvivalController.class);
    private static final long CHECK_INTERVAL_MS = 500L;
    private static final long USE_COOLDOWN_MS = 1_000L;
    private static final long SUPPLY_TIMEOUT_MS = 180_000L;
    private static final long FAILED_SUPPLY_RETRY_MS = 300_000L;
    private static final int MAX_SALE_STACKS = 24;
    private static final Set<Integer> UNREADABLE_EFFECT_ITEMS =
            ConcurrentHashMap.newKeySet();

    private long nextCheckAt;
    private long nextUseAt;
    private long supplyRetryAt;
    private SupplyRun supplyRun;

    public boolean supplyRunActive() {
        return supplyRun != null;
    }

    public void cancel() {
        supplyRun = null;
    }

    /**
     * Runs cheaply on the shared grind ticker. Returns true while survival work
     * owns movement, so the caller must not run combat movement in this tick.
     */
    public boolean tick(Character companion) {
        if (companion == null || companion.getMap() == null) {
            return supplyRunActive();
        }
        long now = System.currentTimeMillis();
        if (now < nextCheckAt) {
            return supplyRunActive();
        }
        nextCheckAt = now + CHECK_INTERVAL_MS;

        if (now >= nextUseAt && useNeededPotion(companion)) {
            nextUseAt = now + USE_COOLDOWN_MS;
        }

        if (supplyRun != null) {
            advanceSupplyRun(companion, now);
            return supplyRunActive();
        }
        if (now >= supplyRetryAt && needsSupplyRun(companion)) {
            int currentMapId = companion.getMapId();
            int shopMapId = CompanionSupplyRoute.potionShopFor(currentMapId);
            if (shopMapId < 0) {
                supplyRetryAt = now + FAILED_SUPPLY_RETRY_MS;
                log.warn("Companion has no safe potion-shop route cid={} map={}",
                        companion.getId(), currentMapId);
                return false;
            }
            supplyRun = new SupplyRun(currentMapId, shopMapId, now, false);
            log.info("Companion supply run started cid={} fromMap={} shopMap={}",
                    companion.getId(), currentMapId, shopMapId);
            GCMovement.travel(companion, shopMapId);
            return true;
        }
        return false;
    }

    private boolean useNeededPotion(Character companion) {
        if (!companion.isAlive()) {
            return false;
        }
        List<CompanionSurvivalPolicy.Potion> inventory = inventoryPotions(companion);
        Optional<CompanionSurvivalPolicy.Potion> selected =
                CompanionSurvivalPolicy.chooseForUse(
                        HP, companion.getHp(), companion.getCurrentMaxHp(), inventory);
        if (selected.isEmpty()) {
            return false;
        }
        CompanionSurvivalPolicy.Potion potion = selected.orElseThrow();
        Inventory use = companion.getInventory(InventoryType.USE);
        Item item = use.findById(potion.itemId());
        StatEffect effect = item == null
                ? null : safeItemEffect(
                        ItemInformationProvider.getInstance(), item.getItemId());
        if (item == null || item.getQuantity() <= 0 || effect == null) {
            return false;
        }

        int hpBefore = companion.getHp();
        int mpBefore = companion.getMp();
        boolean applied = withBoundClient(companion, () -> {
            Item current = companion.getInventory(InventoryType.USE)
                    .getItem(item.getPosition());
            if (current == null || current.getItemId() != item.getItemId()
                    || current.getQuantity() <= 0) {
                return false;
            }
            if (!effect.applyTo(companion)) {
                return false;
            }
            InventoryManipulator.removeFromSlot(
                    companion.getClient(), InventoryType.USE,
                    current.getPosition(), (short) 1, false);
            return true;
        });
        if (!applied) {
            log.warn("Companion potion application failed cid={} item={}",
                    companion.getId(), item.getItemId());
            return false;
        }
        companion.updatePartyMemberHP();
        log.info("Companion used potion cid={} item={} hp={}->{} mp={}->{} remaining={}",
                companion.getId(), item.getItemId(), hpBefore, companion.getHp(),
                mpBefore, companion.getMp(),
                companion.getInventory(InventoryType.USE).countById(item.getItemId()));
        return true;
    }

    private boolean needsSupplyRun(Character companion) {
        List<CompanionSurvivalPolicy.Potion> inventory = inventoryPotions(companion);
        int hpStock = stock(inventory, HP);
        return needsSupplyRun(hpStock, inventoryPressure(companion));
    }

    static boolean needsSupplyRun(int hpStock, boolean inventoryPressure) {
        return CompanionSurvivalPolicy.needsRestock(hpStock) || inventoryPressure;
    }

    private void advanceSupplyRun(Character companion, long now) {
        SupplyRun run = supplyRun;
        if (run == null) {
            return;
        }
        if (!run.returning()) {
            if (companion.getMapId() == run.shopMapId()) {
                Shop shop = shopOnCurrentMap(companion);
                if (shop == null) {
                    failAndReturn(companion, "no NPC shop on destination map");
                    return;
                }
                int sold = sellOrdinaryLoot(companion, shop);
                int bought = restock(companion, shop);
                companion.saveCharToDB(true);
                if (needsSupplyRun(companion)) {
                    supplyRetryAt = now + FAILED_SUPPLY_RETRY_MS;
                }
                supplyRun = new SupplyRun(
                        run.returnMapId(), run.shopMapId(), now, true);
                log.info("Companion supply shop completed cid={} map={} soldStacks={} bought={} mesos={}",
                        companion.getId(), companion.getMapId(), sold, bought, companion.getMeso());
                GCMovement.travel(companion, run.returnMapId());
                return;
            }
        } else if (companion.getMapId() == run.returnMapId()) {
            log.info("Companion supply run completed cid={} returnMap={}",
                    companion.getId(), run.returnMapId());
            supplyRun = null;
            return;
        }
        if (now - run.startedAt() > SUPPLY_TIMEOUT_MS) {
            failAndReturn(companion, "timeout");
        }
    }

    private void failAndReturn(Character companion, String reason) {
        SupplyRun run = supplyRun;
        if (run == null) {
            return;
        }
        supplyRetryAt = System.currentTimeMillis() + FAILED_SUPPLY_RETRY_MS;
        if (companion.getMapId() != run.returnMapId()) {
            supplyRun = new SupplyRun(
                    run.returnMapId(), run.shopMapId(), System.currentTimeMillis(), true);
            GCMovement.travel(companion, run.returnMapId());
        } else {
            supplyRun = null;
        }
        log.warn("Companion supply run failed cid={} reason={} currentMap={} returnMap={}",
                companion.getId(), reason, companion.getMapId(), run.returnMapId());
    }

    private int restock(Character companion, Shop shop) {
        return restockResource(companion, shop, HP);
    }

    private int restockResource(
            Character companion,
            Shop shop,
            CompanionSurvivalPolicy.Resource resource) {
        List<CompanionSurvivalPolicy.Potion> inventory = inventoryPotions(companion);
        int currentStock = stock(inventory, resource);
        int desired = CompanionSurvivalPolicy.restockQuantity(currentStock);
        if (desired <= 0) {
            return 0;
        }
        int maximum = resource == HP
                ? companion.getCurrentMaxHp() : companion.getCurrentMaxMp();
        Optional<CompanionSurvivalPolicy.Potion> selected =
                CompanionSurvivalPolicy.chooseForPurchase(
                        resource, maximum, companion.getMeso(), shopPotions(companion, shop));
        if (selected.isEmpty()) {
            log.warn("Companion shop has no affordable {} potion cid={} shop={}",
                    resource, companion.getId(), shop.getId());
            return 0;
        }
        CompanionSurvivalPolicy.Potion potion = selected.orElseThrow();
        int quantity = CompanionSurvivalPolicy.affordableQuantity(
                desired, potion.unitPrice(), companion.getMeso());
        if (quantity <= 0) {
            return 0;
        }
        int before = companion.getInventory(InventoryType.USE).countById(potion.itemId());
        withBoundClient(companion, () -> {
            shop.buy(companion.getClient(), potion.shopSlot(),
                    potion.itemId(), (short) quantity);
            return null;
        });
        int after = companion.getInventory(InventoryType.USE).countById(potion.itemId());
        return Math.max(0, after - before);
    }

    private int sellOrdinaryLoot(Character companion, Shop shop) {
        if (!inventoryPressure(companion)) {
            return 0;
        }
        int sold = 0;
        sold += sellInventory(companion, shop, InventoryType.USE, sold);
        if (sold < MAX_SALE_STACKS) {
            sold += sellInventory(companion, shop, InventoryType.ETC, sold);
        }
        if (sold < MAX_SALE_STACKS) {
            sold += sellInventory(companion, shop, InventoryType.EQUIP, sold);
        }
        return sold;
    }

    private int sellInventory(
            Character companion, Shop shop, InventoryType type, int alreadySold) {
        ItemInformationProvider items = ItemInformationProvider.getInstance();
        List<Item> snapshot =
                new ArrayList<>(companion.getInventory(type).list());
        snapshot.sort(Comparator
                .comparingInt((Item item) ->
                        items.getPrice(item.getItemId(), item.getQuantity()))
                .thenComparingInt(Item::getItemId)
                .thenComparingInt(Item::getPosition));
        int sold = 0;
        for (Item item : snapshot) {
            if (alreadySold + sold >= MAX_SALE_STACKS
                    || !CompanionSurvivalPolicy.inventoryPressure(
                            companion.getInventory(type).getNumFreeSlot())) {
                break;
            }
            if (item == null || item.getQuantity() <= 0 || item.isUntradeable()
                    || items.isCash(item.getItemId())
                    || items.isQuestItem(item.getItemId())
                    || !item.getOwner().isEmpty()
                    || !item.getItemLog().isEmpty()
                    || items.getPrice(item.getItemId(), item.getQuantity()) <= 0) {
                continue;
            }
            if (type == InventoryType.USE) {
                StatEffect effect = safeItemEffect(items, item.getItemId());
                if (ItemConstants.isRechargeable(item.getItemId())
                        || effect != null && (effect.getHp() > 0 || effect.getMp() > 0
                        || effect.getHpRate() > 0 || effect.getMpRate() > 0)) {
                    continue;
                }
            }
            short quantity = type == InventoryType.EQUIP
                    ? (short) 1 : item.getQuantity();
            withBoundClient(companion, () -> {
                shop.sell(companion.getClient(), type, item.getPosition(), quantity);
                return null;
            });
            sold++;
        }
        return sold;
    }

    private static boolean inventoryPressure(Character companion) {
        return CompanionSurvivalPolicy.inventoryPressure(
                        companion.getInventory(InventoryType.EQUIP).getNumFreeSlot())
                || CompanionSurvivalPolicy.inventoryPressure(
                        companion.getInventory(InventoryType.USE).getNumFreeSlot())
                || CompanionSurvivalPolicy.inventoryPressure(
                        companion.getInventory(InventoryType.ETC).getNumFreeSlot());
    }

    private static int stock(
            Collection<CompanionSurvivalPolicy.Potion> potions,
            CompanionSurvivalPolicy.Resource resource) {
        return potions.stream()
                .filter(potion -> potion.restore(resource) > 0)
                .mapToInt(CompanionSurvivalPolicy.Potion::quantity)
                .sum();
    }

    private static Shop shopOnCurrentMap(Character companion) {
        for (MapObject object : companion.getMap().getMapObjects()) {
            if (object.getType() != MapObjectType.NPC) {
                continue;
            }
            Shop shop = ShopFactory.getInstance().getShopForNPC(((NPC) object).getId());
            if (shop != null && shopPotions(companion, shop).stream()
                    .anyMatch(potion -> potion.hpRestore() > 0)) {
                return shop;
            }
        }
        return null;
    }

    private static List<CompanionSurvivalPolicy.Potion> inventoryPotions(
            Character companion) {
        ItemInformationProvider provider = ItemInformationProvider.getInstance();
        List<CompanionSurvivalPolicy.Potion> potions = new ArrayList<>();
        for (Item item : companion.getInventory(InventoryType.USE).list()) {
            if (!isPotionCandidate(item.getItemId())) {
                continue;
            }
            StatEffect effect = safeItemEffect(provider, item.getItemId());
            CompanionSurvivalPolicy.Potion potion = potion(
                    companion, item.getItemId(), item.getQuantity(),
                    Math.max(0, provider.getPrice(item.getItemId(), 1)),
                    (short) -1, effect);
            if (potion != null) {
                potions.add(potion);
            }
        }
        return potions;
    }

    private static List<CompanionSurvivalPolicy.Potion> shopPotions(
            Character companion, Shop shop) {
        ItemInformationProvider provider = ItemInformationProvider.getInstance();
        List<CompanionSurvivalPolicy.Potion> potions = new ArrayList<>();
        List<ShopItem> catalog = shop.getItems();
        for (short slot = 0; slot < catalog.size(); slot++) {
            ShopItem item = catalog.get(slot);
            if (!isPotionCandidate(item.getItemId())) {
                continue;
            }
            StatEffect effect = safeItemEffect(provider, item.getItemId());
            CompanionSurvivalPolicy.Potion potion = potion(
                    companion, item.getItemId(), 0, item.getPrice(), slot, effect);
            if (potion != null) {
                potions.add(potion);
            }
        }
        return potions;
    }

    private static CompanionSurvivalPolicy.Potion potion(
            Character companion,
            int itemId,
            int quantity,
            int price,
            short shopSlot,
            StatEffect effect) {
        if (effect == null) {
            return null;
        }
        try {
            if (ItemInformationProvider.getInstance().getScriptedItemInfo(itemId) != null) {
                return null;
            }
        } catch (RuntimeException exception) {
            log.debug("Skipping item with unreadable script metadata item={}", itemId);
            return null;
        }
        int hp = Math.max(0, effect.getHp())
                + (int) Math.round(companion.getCurrentMaxHp() * effect.getHpRate());
        int mp = Math.max(0, effect.getMp())
                + (int) Math.round(companion.getCurrentMaxMp() * effect.getMpRate());
        if (hp <= 0 && mp <= 0) {
            return null;
        }
        return new CompanionSurvivalPolicy.Potion(
                itemId, quantity, hp, mp, Math.max(0, price), shopSlot);
    }

    private static StatEffect safeItemEffect(
            ItemInformationProvider provider, int itemId) {
        try {
            return provider.getItemEffect(itemId);
        } catch (RuntimeException exception) {
            if (UNREADABLE_EFFECT_ITEMS.add(itemId)) {
                log.debug("Skipping item with unreadable effect item={}", itemId);
            }
            return null;
        }
    }

    static boolean isPotionCandidate(int itemId) {
        int category = itemId / 10_000;
        return category >= 200 && category <= 202;
    }

    private static <T> T withBoundClient(
            Character companion, Supplier<T> operation) {
        Client client = Objects.requireNonNull(
                companion.getClient(), "companion client");
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

    private record SupplyRun(
            int returnMapId,
            int shopMapId,
            long startedAt,
            boolean returning) {
    }
}
