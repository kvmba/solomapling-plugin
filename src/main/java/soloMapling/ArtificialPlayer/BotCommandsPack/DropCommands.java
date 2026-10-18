package soloMapling.ArtificialPlayer.BotCommandsPack;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Item;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.TimerManager;
import soloMapling.ArtificialPlayer.BotClientBinding;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLogic;
import org.gms.util.PacketCreator;

import java.awt.*;
import java.util.Arrays;
import java.util.List;

import static soloMapling.ArtificialPlayer.BotHelpers.isUnusableItem;
import static soloMapling.ArtificialPlayer.BotLogic.checkForItemsOnFloor;

public class DropCommands {

    /*
        // Drop type 0 = 0 is DROP_USEROWN = 15 second delay
        // 2 = (DROP_NOOWN) = can loot instantly
        // playerDrop false = pet lootable (for owner, or if magic scales)

        [x] Meso Drop
            Equip Drop
            Item Drop (Qty 1-x)
        [x] Throw (Drop to specific location)
        [x] Throw to owner - Lootable ONLY by owner for 15s

        [later] Pet Lootable - dropType = 0 (user own) (15 second delay)
                     - dropType = 2 (no owner) - lootable instantly
                     - playerDrop false = pet lootable
                        // dropType 2 & playerDrop false = pet lootable
     */

    // Meso, Equip, Item, Item Qty - FFA Player Drop - Anyone can loot

    // Dropping only requires the id to be legal: half-finished WZ entries with
    // no name are not, but meso (itemId 0) is - it is a legitimate drop even
    // though it has no String.wz name.

    public static void botDropMeso(Character fakechar, int meso) {
        fakechar.getMap().spawnMesoDrop(meso, fakechar.getPosition(), fakechar, fakechar, true, (byte) 2, (short) 0);
    }

    public static void botDropEquip(Character fakechar, int itemId) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItemEquip(itemId);
        fakechar.getMap().spawnItemDrop(fakechar, fakechar, itemToDrop,
                fakechar.getPosition(), true, true);
    }

    public static void botDropItem(Character fakechar, int itemId) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItem(itemId);
        fakechar.getMap().spawnItemDrop(fakechar, fakechar, itemToDrop,
                fakechar.getPosition(), true, true);
    }

    private static final long PICKUP_GRACE_PERIOD_MS = 3000;

    public static MapItem botDropItemWithExpiry(Character fakechar, int itemId, boolean isEquip, long expiryMs) {
        if (isUnusableItem(itemId)) {
            return null;
        }
        Item itemToDrop = isEquip ? BotLogic.generateCleanItemEquip(itemId) : BotLogic.generateCleanItem(itemId);
        MapItem drop = fakechar.getMap().spawnItemDropNoExpire(fakechar, fakechar, itemToDrop,
                fakechar.getPosition(), true, true);
        if (drop != null && expiryMs > 0) {
            TimerManager.getInstance().schedule(() -> {
                if (drop.isPickedUp()) return;
                fakechar.getMap().broadcastMessage(
                        PacketCreator.removeItemFromMap(drop.getObjectId(), 0, 0),
                        drop.getPosition());
            }, expiryMs);

            TimerManager.getInstance().schedule(() -> {
                fakechar.getMap().makeDisappearItemFromMap(drop);
            }, expiryMs + PICKUP_GRACE_PERIOD_MS);
        }
        return drop;
    }

    public static void botDropItemQty(Character fakechar, int itemId, int qty) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItemWithQty(itemId, qty);
        fakechar.getMap().spawnItemDrop(fakechar, fakechar, itemToDrop,
                fakechar.getPosition(), true, true);
    }

    // Meso, Equip, Item, Item Qty - Throw to specific location - Anyone can loot
    // Set playerDrop = false for pet-lootable (by everybody I think)

    public static void botThrowMeso(Character fakechar, int meso, Point throwPos) {
        fakechar.getMap().spawnMesoDrop(meso, throwPos, fakechar, fakechar, false, (byte) 2, (short) 0);
    }

    public static void botThrowEquip(Character fakechar, Equip eq, Point throwPos) {
        fakechar.getMap().spawnItemDrop(fakechar, fakechar, eq,
                throwPos, true, false);
    }

    public static void botThrowEquip(Character fakechar, int itemId, Point throwPos) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItemEquip(itemId);
        fakechar.getMap().spawnItemDrop(fakechar, fakechar, itemToDrop,
                throwPos, true, false);
    }

    public static Item botThrowItem(Character fakechar, int itemId, Point throwPos) {
        if (isUnusableItem(itemId)) {
            return null;
        }
        Item itemToDrop = BotLogic.generateCleanItem(itemId);
        fakechar.getMap().spawnItemDrop(fakechar, fakechar, itemToDrop,
                throwPos, true, false);
        return itemToDrop;
    }

    public static MapItem botThrowItemNoExpire(Character fakechar, int itemId, Point throwPos) {
        if (isUnusableItem(itemId)) {
            return null;
        }
        Item itemToDrop = BotLogic.generateCleanItem(itemId);
        return fakechar.getMap().spawnItemDropNoExpire(fakechar, fakechar, itemToDrop,
                throwPos, true, false);
    }

    public static MapItem botThrowItemNoExpireOwnerOnly(Character fakechar, int itemId, Point throwPos) {
        if (isUnusableItem(itemId)) {
            return null;
        }
        Item itemToDrop = BotLogic.generateCleanItem(itemId);
        MapItem drop = fakechar.getMap().spawnItemDropNoExpire(fakechar, fakechar, itemToDrop,
                throwPos, false, false);
        markPermanentOwner(drop);
        return drop;
    }

    public static void botDropItemQtyOwnerOnly(Character fakechar, int itemId, int qty) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItemWithQty(itemId, qty);
        MapItem drop = fakechar.getMap().spawnItemDropNoExpire(fakechar, fakechar, itemToDrop,
                fakechar.getPosition(), false, false);
        markPermanentOwner(drop);
    }

    public static void botThrowItemQty(Character fakechar, int itemId, int qty, Point throwPos) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItemWithQty(itemId, qty);
        fakechar.getMap().spawnItemDrop(fakechar, fakechar, itemToDrop,
                throwPos, true, false);
    }

    public static void botThrowItemToOwner(Character dropper, int itemId, Point throwPos, Character owner) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItem(itemId);
        MapItem drop = dropper.getMap().spawnItemDropNoExpire(dropper, owner, itemToDrop,
                throwPos, false, false);
        markPermanentOwner(drop);
    }

    /**
     * Mark an owner-only drop's permanent ownership under the host's item lock.
     *
     * <p>{@code MapItem.setPermanentOwner} is deprecated precisely because it does not hold the
     * item lock, and the host's contract is explicit: {@code permanentOwner} is read under that
     * lock on the pickup path ({@code Character.pickupItem} -> {@code canBePickedBy}), so the
     * write must take the same lock or the field can tear against a live pickup. The addressed
     * hand-off path already sidesteps this through {@code MapleMap.spawnOwnerOnlyItemDrop};
     * these are the no-expire drops, which have no owner-only host seam, so they take the lock
     * themselves.
     */
    private static void markPermanentOwner(MapItem drop) {
        if (drop == null) {
            return;
        }
        drop.lockItem();
        try {
            drop.setPermanentOwnerLocked(true);
        } finally {
            drop.unlockItem();
        }
    }

    /**
     * Read a drop's permanent-owner flag under the host's item lock - the lock the field's own
     * contract requires: {@code setPermanentOwnerLocked} writes it under that lock and the pickup
     * path reads it under that lock ({@code Character.pickupItem} -> {@code canBePickedBy}). The
     * plugin's entitlement gates are the same kind of read, so they go through this one seam
     * rather than touching the raw field. Callers already hold a non-null drop (they filter for
     * it), so nothing guards that here.
     */
    public static boolean isPermanentOwnerLocked(MapItem drop) {
        drop.lockItem();
        try {
            return drop.isPermanentOwner();
        } finally {
            drop.unlockItem();
        }
    }

    // Meso, Equip, Item, Item Qty - Throw to Owner - Owner only can loot
    // dropType 0 = userOwn
    // playerDrop false = pet lootable

    public static void botThrowToOwnerMeso(Character fakechar, int meso, Character owner) {
        fakechar.getMap().spawnMesoDrop(meso, owner.getPosition(), fakechar, owner, false, (byte) 0, (short) 0);
    }

    public static void botThrowToOwnerEquip(Character fakechar, int itemId, Character owner) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItemEquip(itemId);
        fakechar.getMap().spawnItemDrop(fakechar, owner, itemToDrop,
                owner.getPosition(), false, false);
    }

    public static void botThrowToOwnerItem(Character fakechar, int itemId, Character owner) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItem(itemId);
        fakechar.getMap().spawnItemDrop(fakechar, owner, itemToDrop,
                owner.getPosition(), false, false);
    }

    public static void botThrowToOwnerItemQty(Character fakechar, int itemId, int qty, Character owner) {
        botThrowToOwnerItemQtyAt(fakechar, itemId, qty, owner, owner.getPosition());
    }

    /**
     * As {@link #botThrowToOwnerItemQty}, but the addressed pile lands at an explicit spot -
     * the delivery-post rule needs piles AT the stage NPC, not at wherever the receiver
     * happens to stand.
     */
    public static void botThrowToOwnerItemQtyAt(Character fakechar, int itemId, int qty, Character owner, Point at) {
        if (isUnusableItem(itemId)) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItemWithQty(itemId, qty);
        // Addressed hand-off: the host's own owner-locked drop (permanentOwner set under the
        // item lock inside the map call). The plain spawnItemDrop left the pile unaddressed -
        // party members and (after the 15s window) every bot's sweep could pick it, so the
        // isPermanentOwner guards in botCanLoot/isFloorLoot/recoverUngatheredHandoffs never
        // engaged and a leader's pile could ping-pong or be lost to the despawn sweeper.
        fakechar.getMap().spawnOwnerOnlyItemDrop(fakechar, owner, itemToDrop, at);
    }

    // Throwing multiple items in a row (for display purposes)

    public static void botThrowEquipsInARow(Character fakechar, int[] items, Point centerPos, int spacing) {
        int itemCount = items.length;
        int startX = centerPos.x - (itemCount / 2) * spacing; // Calculate starting X position

        for (int i = 0; i < itemCount; i++) {
            int itemId = items[i];
            int adjustedX = startX + i * spacing; // Adjust X position based on index
            Point adjustedPos = new Point(adjustedX, centerPos.y); // Maintain the same Y position
            botThrowEquip(fakechar, itemId, adjustedPos);
        }
    }

    // Bot pickup commands

    // BotLoot at feet
    // BotPickUp
    // NOTE on units: these legacy helpers take a SQUARED range (the value the engine compares
    // directly: distanceSq <= rangeSq). The callers' magic numbers are documented in those
    // terms ("1000" = ~31px "at feet", "9000" = ~95px ~= 2.5 character widths). New call sites
    // should prefer BotLogic.checkForItemsOnFloor, which takes PIXELS and squares internally -
    // passing a pixel value to these raw helpers would scan its square root.
    public static void botLoot(Character fakechar, double rangeSq) {
        botLoot(fakechar, fakechar.getPosition(), rangeSq);
    }

    public static void botLoot(Character fakechar, Point itemLocation, double rangeSq) {
        List<MapObject> items = fakechar.getMap().getMapObjectsInRange(itemLocation, rangeSq, Arrays.asList(MapObjectType.ITEM));
        for (MapObject item : items) {
            botLootSingleDrop(fakechar, (MapItem) item);
        }
    }

    public static void botLootLocation(Character fakechar, Point itemLocation) {
        double rangeSq = 1000; // ~31px = next to feet
        botLoot(fakechar, itemLocation, rangeSq);
    }

    // Bot loots their own items at item location position within specified sq range
    public static void botLootOwnerItems(Character fakechar, Point itemLocation, double rangeSq) {
        botLootTargetCharactersItems(fakechar, fakechar, itemLocation, rangeSq);
    }

    // Bot loots target character's items at item location within specified sq range
    public static void botLootTargetCharactersItems(Character fakechar, Character targetCharacter, Point itemLocation, double rangeSq) {
        List<MapObject> items = fakechar.getMap().getMapObjectsInRange(itemLocation, rangeSq, Arrays.asList(MapObjectType.ITEM));
        for (MapObject item : items) {
            MapItem mapItem = (MapItem) item;
            if (mapItem.getOwnerId() == targetCharacter.getId()) {
                botLootSingleDrop(fakechar, mapItem);
            }
        }
    }

    // Deliberate blocking leaf primitive: per-item pickup stagger, item count is
    // data-driven. Callers (blackjack payouts, loot scripts) rely on the hold.
    public static void lootItemListOnFloor(Character fakechar, List<MapObject> items) {
        for (MapObject item : items) {
            MapItem mapItem = (MapItem) item;
            botLootSingleDrop(fakechar, mapItem);
            BotHelpers.blockingSleep(100);
        }
    }

    /** Removes synthetic table props without applying normal inventory pickup effects. */
    public static void clearItemListFromFloor(Character fakechar, List<MapObject> items) {
        if (fakechar == null || fakechar.getMap() == null || items == null) {
            return;
        }
        for (MapObject item : items) {
            if (item instanceof MapItem mapItem) {
                fakechar.getMap().makeDisappearItemFromMap(mapItem);
                BotHelpers.blockingSleep(100);
            }
        }
    }

    public static void botLootSelectedItems(Character chr, int[] itemsToLoot) {
        // The hats were thrown in a row at the bot's own feet (75px spacing), so a
        // neighbourhood scan is the honest reach - BotLogic squares the px value.
        List<MapObject> itemsOnFloor = checkForItemsOnFloor(chr, chr.getPosition(), 300, itemsToLoot);
        if (!itemsOnFloor.isEmpty()) {
            DropCommands.lootItemListOnFloor(chr, itemsOnFloor);
        }
    }

    // True when a bot is entitled to loot this drop. Delegates to the HOST's own rule
    // (MapItem.canBePickedBy) instead of restating a narrower one: a party-owned drop is
    // lootable by the whole party immediately (that is what a real client does), and the
    // owner-protection window still gates strangers. Reading and writing party ownership is
    // the host's item-lock contract, hence the lock.
    public static boolean botCanLoot(Character fakechar, MapItem mapItem) {
        if (mapItem == null || mapItem.isPickedUp() || mapItem.getPosition() == null) {
            return false;
        }
        if (mapItem.getOwnerId() == fakechar.getId()) {
            return true; // the bot's own drop
        }
        if (isPermanentOwnerLocked(mapItem)) {
            return false; // somebody's addressed hand-off (a PQ leader's pile) - not floor loot
        }
        mapItem.lockItem();
        try {
            return mapItem.canBePickedBy(fakechar);
        } finally {
            mapItem.unlockItem();
        }
    }

    // Pick up one specific drop (organic single-item loot, paced by the caller for a natural look).
    public static void botLootSingleDrop(Character fakechar, MapItem mapItem) {
        if (fakechar == null || fakechar.getMap() == null
                || mapItem == null || mapItem.isPickedUp()) {
            return;
        }
        // Use the engine's authoritative pickup path. It enforces ownership,
        // inventory capacity and quest restrictions and adds the actual item
        // before removing the map object.
        //
        // pickupItem resolves the character through client.getPlayer(), so the bot
        // must be published on its (shared, headless) client for the call or the
        // engine NPEs inside InventoryManipulator.checkSpace. See BotClientBinding.
        BotClientBinding.runWithBoundPlayer(fakechar, () -> fakechar.pickupItem(mapItem));
    }

    // Sweep all drops the bot is entitled to (own + free-for-all) within sq range. Used as a tidy-up when a
    // bot leaves a spot so it doesn't trail a pile; for in-combat looting use the staggered single pickups.
    public static void botLootOwnAndFreeForAll(Character fakechar, Point itemLocation, double rangeSq) {
        List<MapObject> items = fakechar.getMap().getMapObjectsInRange(itemLocation, rangeSq, Arrays.asList(MapObjectType.ITEM));
        for (MapObject item : items) {
            MapItem mapItem = (MapItem) item;
            if (botCanLoot(fakechar, mapItem)) {
                botLootSingleDrop(fakechar, mapItem);
            }
        }
    }

    // obsolete can delete
//    public static void jackpotRoulette(Character fakechar) {
//        List<Integer> random_drop_list = Arrays.asList();
//        Point center2 = fakechar.getPosition();
//        int length = generateRandomNumber(29, 41);
//        for (int currIndex = 0; currIndex < length; currIndex++) {
//            int item_id = getRandomId(random_drop_list);
////            botThrowItem(fakechar, item_id); // todo
//        }
//    }


}
