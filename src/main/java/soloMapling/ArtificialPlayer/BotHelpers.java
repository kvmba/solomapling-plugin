package soloMapling.ArtificialPlayer;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.extension.api.ArtificialCharacters;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.server.ItemInformationProvider;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;

import java.awt.*;
import java.util.List;
import java.util.Random;

public class BotHelpers {

    // BotHelpers - bot related stuff with regards to programming (object manipulation, etc)

    public static Character getCharFromChannelStorage(int cid) {
        Channel channel = Server.getInstance().getChannel(0, 1);
        Character exact = channel.getPlayerStorage().getCharacterById(cid);
        if (isBot(exact)) {
            return exact;
        }

        // Legacy generated bots use 20000-based ids, and old GM workflows
        // commonly omit that prefix. Prefer the exact id first so native
        // persistent companions such as cid=5 remain addressable.
        if (cid < 1000) {
            Character legacy = channel.getPlayerStorage().getCharacterById(cid + 20000);
            if (isBot(legacy)) {
                return legacy;
            }
        }
        return null;
    }

    public static boolean isBot(Character chr) {
        return chr != null && isBot(chr.getId());
    }

    /**
     * Prefers the host {@link ArtificialCharacters} registry (registered in onLoad).
     * Falls back to the historical SoloMapling id convention when no classifier is bound.
     */
    public static boolean isBot(int id) {
        if (id == 999) {
            return true;
        }
        if (!ArtificialCharacters.classifiers().isEmpty()) {
            return ArtificialCharacters.isArtificial(id);
        }
        return id > 20000;
    }

    /**
     * Host item-name lookup. Returns null when the item has no usable name.
     * <p>
     * Three separate things make the raw {@code ItemInformationProvider.getName}
     * call unsafe from a bot tick, and all three mean the same thing to us -
     * "this item has no name":
     * <ul>
     *   <li>the id is a half-finished WZ entry with no String.wz record
     *       (getName already returns null for it),</li>
     *   <li>the record exists but its name is empty/blank,</li>
     *   <li>the host's XML DOM walk ({@code XMLDomMapleData.getChildByPath})
     *       is not thread safe and bot ticks share those provider trees across
     *       virtual threads, so a concurrent read surfaces an NPE instead of a
     *       value.</li>
     * </ul>
     */
    public static String itemNameOrNull(int itemId) {
        try {
            return ItemInformationProvider.getInstance().getName(itemId);
        } catch (RuntimeException e) {  // host DOM race - see javadoc
            return null;
        }
    }

    /**
     * Whether a raw name from the host counts as a real name. Pure, so the
     * usable/unusable contract is testable without loading WZ.
     */
    static boolean hasUsableName(String itemName) {
        return itemName != null && !itemName.isBlank();
    }

    /**
     * Item name for display/logging. Never null: several call sites immediately
     * call {@code .toLowerCase()} on the result. Unnameable items resolve to
     * {@code "NULL"} - use {@link #isUsableItem(int)} when the caller cares
     * whether the item is real rather than just printable.
     */
    public static String convertItemIdToName(int itemId) {
        String itemName = itemNameOrNull(itemId);
        return hasUsableName(itemName) ? itemName : "NULL";
    }

    /**
     * Whether an item is a finished item with a real name.
     * <p>
     * Unnamed ids are half-finished WZ data: they must not be bought, listed
     * for sale, traded, dropped or equipped by a bot - there is nothing a
     * player could do with one anyway.
     */
    public static boolean isUsableItem(int itemId) {
        return hasUsableName(itemNameOrNull(itemId));
    }

    /**
     * Inverse of {@link #isUsableItem(int)} - reads better at guard sites.
     */
    public static boolean isUnusableItem(int itemId) {
        return !isUsableItem(itemId);
    }

    /**
     * Whether an {@link org.gms.client.inventory.Item} is a finished item with
     * a real name. Null items are unusable.
     */
    public static boolean isUsableItem(org.gms.client.inventory.Item item) {
        return item != null && isUsableItem(item.getItemId());
    }

    /**
     * Inverse of {@link #isUsableItem(org.gms.client.inventory.Item)}.
     */
    public static boolean isUnusableItem(org.gms.client.inventory.Item item) {
        return !isUsableItem(item);
    }

    public static Point adjustCenterPositionXAxis(Point center, int currIndex, int initialIncrement, int subsequentIncrement, int offset) {
        // initialIncrement = How many units it will go left
        // SubsequentIncrement = How many units it will go right (Usually 2x initial Increment for an even "spread"
        // Offset = how much space between each item
        if (currIndex < initialIncrement) {
            center.x += offset;
        } else {
            int adjustedIndex = currIndex - initialIncrement;
            int cycle = (adjustedIndex / subsequentIncrement) % 2;

            if (cycle == 0) { // Even cycle, increment by 30
                if (adjustedIndex % subsequentIncrement < subsequentIncrement) {
                    center.x -= offset;
                }
            } else { // Odd cycle, decrement by 30
                if (adjustedIndex % subsequentIncrement < subsequentIncrement) {
                    center.x += offset;
                }
            }
        }
        return center;
    }

    public static boolean checkSecondListInsideFirstList(List<MapObject> list1, List<MapObject> list2) {
        if (list1.size() < list2.size()) {
            System.out.println("Current List is greater than 1st");
            return false;
        }

        for (MapObject obj2 : list2) {
            boolean found = false;
            for (MapObject obj1 : list1) {
                if (areObjectsEqual(obj1, obj2)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                System.out.println("Item Not Found");
                return false;
            }
        }
        return true;
    }

    private static boolean areObjectsEqual(MapObject obj1a, MapObject obj2b) {
        MapItem obj1 = (MapItem) obj1a;
        MapItem obj2 = (MapItem) obj2b;
        if (obj1 == obj2) return true;
        if (obj1 == null || obj2 == null) return false;

        return obj1.getItemId() == obj2.getItemId() &&
                obj1.getOwnerId() == obj2.getOwnerId() &&
                obj1.getItem().getQuantity() == (obj2.getItem().getQuantity());
    }

    // Blocks the current thread for the given number of milliseconds.
    // ONLY for deliberate synchronous choreography: scripts whose steps have
    // data-driven durations (dialogue playback, movement recordings, blocking
    // warps) run sequentially on a virtual thread and are MEANT to hold it.
    // Every such call site carries a "deliberate" comment saying why.
    // For everything else use the BotTiming toolkit (decision table in its
    // header): pacing the FSM's next action = BotSM.waitFor, one delayed
    // side-effect = BotTiming.after, scripted beats = BotTiming.chain.
    // Returns true if the sleep completed, false if the thread was interrupted
    // (interrupt flag restored so the caller can bail).
    public static boolean blockingSleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static Rectangle createRectangle(Point center, int width, int height) {
        // Calculate half dimensions
        int halfWidth = width / 2;
        int halfHeight = height / 2;

        // Calculate the vertical offset: 20% of the height
        int verticalOffset = (int) (height * 0.2);

        // Adjust the center point vertically to place it closer to the bottom
        int centerYAdjusted = center.y - halfHeight + verticalOffset;

        // Calculate top-left corner of the rectangle
        int topLeftX = center.x - halfWidth;
        int topLeftY = centerYAdjusted - halfHeight;

        // Create and return the Rectangle
        return new Rectangle(topLeftX, topLeftY, width, height);
    }

    public static boolean isPointWithinRectangle(Point[] rectangle, Point point) {
        if (rectangle.length != 2) {
            throw new IllegalArgumentException("Rectangle must have exactly two points: top-left and bottom-right.");
        }

        Point topLeft = rectangle[0];
        Point bottomRight = rectangle[1];

        // Check if the point is within the bounds of the rectangle
        return point.x >= topLeft.x && point.x <= bottomRight.x &&
                point.y >= topLeft.y && point.y <= bottomRight.y;
    }

    // Blocks the replaying thread for the gap between two recorded packet timestamps.
    // Deliberately blocking: recording replay is data-driven synchronous choreography
    // and runs on a virtual thread. Returns false if the thread was interrupted
    // (interrupt flag restored) so the replay loop can bail; true if the wait completed.
    // Diff is clamped at 0 so an out-of-order timestamp can't throw from Thread.sleep.
    //
    // A long diff is normal, not a fault: timestamps are wall-clock times captured while a
    // human was being recorded, so a pause by the recorder replays as a pause here. Across
    // all 440 shipped recordings (7001 gaps), 90.6% land in 500-1000ms and only 4 exceed
    // 2s, together adding ~1.2s of sleep. The character barely moves across those 4 gaps
    // (2.5px on average vs 36.2px for a normal one) - the recorder was standing still.
    public static boolean waitBetweenTwoLong(long timestamp1, long timestamp2) {
        long diff = Math.max(0, timestamp2 - timestamp1);
        // Commented out: this reads as a fault report but only ever meant the recorder paused
        // mid-capture, so it was crying wolf on healthy playback. See the note above.
        // if (diff > 2000) {
        //     System.out.println("More than 2 seconds waiting");
        // }
        try {
            Thread.sleep(diff);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static Point getRandomizedPointXAxis(Point original) {
        return getRandomizedPointXAxis(original, 50);
    }

    public static Point getRandomizedPointXAxis(Point original, int range) {
        int minX = original.x - range;
        int maxX = original.x + range;
        int randomX = new Random().nextInt(maxX - minX + 1) + minX;
        return new Point(randomX, original.y);
    }

    /*
        // Lasts for about 10 seconds, no ereve bird
        #r TEST #k = red text
        #b TEST #k = blue text
        #g TEST #k = green text
        \r\n = new line
        String msg = "#rTEST#k\r\n1. #bTest 1#k\r\n2. #rTest 2#k\r\n3. #bTest 3#k\r\n4. #rTest 4#k";
    */

}
