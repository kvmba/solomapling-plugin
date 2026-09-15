package soloMapling.MapVFX;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.server.TimerManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import org.gms.server.maps.ReactorDropEntry;
import org.gms.server.maps.ReactorFactory;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static soloMapling.ArtificialPlayer.BotHelpers.isUnusableItem;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.itemPool.GachaFillerSystem.getRandomMesoGachaFiller;

    /*
    reactor drops todo's
    [] fading pattern until last item (port) - basically all items disappear, only leaving 1 item. for a wheel of fortune effect
    [] invisible reactor
     */

public class CustomReactor {
    private static final int dropSprayLength = 2; // length from center of item drop spray size (Gachapon drop vfx effect)
    private static final int dropSprayFullWidth = dropSprayLength * 2; // full width of item drop spray
    private static final int itemDropOffset = 30; // space between each item drop

    public static void getAllReactorsData(Character fakechar) {
        List<Reactor> reactors = fakechar.getMap().getAllReactors();
        for (Reactor reactor : reactors) {
            if (reactor.getState() < 4 && reactor.isAlive()) {
                debugprint("Reactor: ", reactor.getObjectId(), reactor.getId(), reactor.getPosition(), reactor.isAlive(), reactor.isActive(), reactor.getState());
            }
        }
    }

    public static int spawnReactor(Character fakechar) {
        return spawnReactor(fakechar.getPosition(), fakechar.getMap());
    }

    public static int spawnReactor(Point position, MapleMap map) {
        int reactorID = 2202004;
        Reactor reactor = new Reactor(ReactorFactory.getReactorS(reactorID), reactorID);
        reactor.setPosition(position);
        reactor.resetReactorActions(0);
        map.spawnReactor(reactor);
        return reactor.getObjectId();
    }

    public static void deleteReactor(MapleMap map, int oid) {
        // destroyReactor broadcasts the destroy packet (clients drop the sprite) and marks the
        // reactor dead, but the host only unregisters the map object when Reactor.destroy() reports
        // it was ALREADY dead - a second pass. A one-shot synthetic box (delay 0) never respawns, so
        // without the explicit removeMapObject it would linger in the map's object table forever.
        map.destroyReactor(oid);
        map.removeMapObject(oid);
    }

    /*
        Allows you to "Hit" a reactor via server code manually. After 4, it will break
        Doesn't "Spray" Items, purely animation based.
        Ludi PQ Blue Box: 2202004
         */
    public static void hitReactor(MapleMap map, int oid) {
        Reactor reactor = map.getReactorByOid(oid);
        byte state = reactor.getState();
        state++;
        reactor.setState(state);
        map.broadcastMessage(PacketCreator.triggerReactor(reactor, (short) 0));
    }

    /*
        Hit a reactor through the engine's own Reactor.hitReactor, i.e. the same path a
        player's swing takes: it advances the reactor's state machine, runs its script
        (act()) when the WZ data says the state walk is done, and re-arms the drop logic.
        The bare hitReactor above does none of that - it bumps the state and plays the
        animation only, so a bot breaking a quest reactor gets neither the drop
        (2002001.js act() -> rm.dropItems()) nor the flags the map script keys on
        (2006000.js act() -> rm.spawnNpc()).

        Hit counts are per reactor and must not be assumed; callers should loop until the
        effect they want appears. Cloud pieces take 4 hits; the Orbis altar and music box
        take 1, through different branches of the same state walk. Re-hitting a spent
        reactor is a no-op (isActive() goes false once its state has no WZ entry).

        Requires a live client player: Reactor.hitReactor reads c.getPlayer() for its GM
        debug line, and ReactorActionManager.dropItems returns early on a null player. A
        bot published on its own client (BotGeneration.createPQBotClient) satisfies this;
        on a shared client, wrap in BotClientBinding.runWithBoundPlayer.
     */
    public static void hitReactorWithScript(MapleMap map, int oid, Character bot) {
        if (map == null || bot == null) return;
        Reactor reactor = map.getReactorByOid(oid);
        if (reactor == null) return;
        reactor.hitReactor(false, bot.getPosition().x, (short) 0, 0, bot.getClient());
    }

    /*
        Report every item-triggered reactor on the map: what it wants, and where a drop
        aimed at it actually lands.

        The trigger box alone is not enough to aim a throw. MapleMap#calcDropPos re-seats
        whatever it is given onto the ground 85px below, so a throw has to be aimed at a
        spot whose re-seated position still falls inside the box - the Orbis music box sits
        68px above the floor its drop lands on. This prints the pair for the same reason
        the OPQ altar bug was invisible for so long: a box that looks reachable is not.

        Printed to the caller's client on purpose. debugprint() is a no-op in a normal run
        (DebugUtilities.DEBUG is false, and it also requires a debugger attached), so a
        diagnostic that went through it would say nothing exactly when it was needed.

        The Orbis values this was written for: altar 2006000 at (377,66) wants 4001063x20
        and lands at (377,99); music box 2008006 at (-1706,-240) wants 4001056+day and
        lands at (-1706,-172).
     */
    public static void dumpItemReactorBoxes(Character chr) {
        if (chr == null || chr.getMap() == null) return;
        MapleMap map = chr.getMap();
        for (Reactor reactor : map.getAllReactors()) {
            if (reactor.getReactorType() != 100) continue;
            Rectangle area = reactor.getArea();
            Pair<Integer, Integer> item = reactor.getReactItem(reactor.getEventState());
            Point at = reactor.getPosition();
            Point landing = map.calcDropPos(at, at);
            chr.dropMessage(6, "Item reactor: name=" + reactor.getName()
                    + " dataId=" + reactor.getId() + " oid=" + reactor.getObjectId()
                    + " at=" + at + " evstate=" + reactor.getEventState()
                    + " wants=" + (item == null ? "?" : item.getLeft() + "x" + item.getRight())
                    + " box=" + area
                    + " drop@reactor lands on " + landing
                    + " inside=" + area.contains(landing));
        }
    }

    public static void threeHitReactor(MapleMap map, int oid) {
        hitReactor(map, oid);
        hitReactor(map, oid);
        hitReactor(map, oid);
    }

    // Synth reactors (spawnReactor) never leave the map on their own: hitReactor bumps the
    // state directly instead of going through Reactor.hitReactor, and destroyReactor's
    // destroy() only removes an alive reactor with delay > 0. Left alone, one pile per gacha
    // round accumulates forever, so schedule the box's removal once its spray + pickup window
    // has passed.
    private static final long REACTOR_CLEANUP_MS = 15000;

    public static void scheduleReactorCleanup(MapleMap map, int oid) {
        TimerManager.getInstance().schedule(() -> deleteReactor(map, oid), REACTOR_CLEANUP_MS);
    }

    public static List<ReactorDropEntry> createReactorDropList(List<Integer> itemIds) {
        List<ReactorDropEntry> items = new ArrayList<>(List.of());
        for (Integer id : itemIds) {
            items.add(new ReactorDropEntry(id, 15, 0));
        }
        return items;
    }

    /*
        Spray Animation - Looks like a fountain dropping items
         */
    public static void sprayFromReactor(MapleMap map, int oid, List<ReactorDropEntry> drops, Character owner) {
        dropFromReactorCustom(map, oid, drops, owner, true, null);
    }

    public static void dropFromReactor(MapleMap map, int oid, List<ReactorDropEntry> drops, Character owner) {
        dropFromReactorCustom(map, oid, drops, owner, false, null);
    }

    private static void notDelayedReactorDrops(Character owner, List<ReactorDropEntry> drops, Reactor reactor, Point dropPos, int posX) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        byte p = 1;
        for (ReactorDropEntry d : drops) {
            if (d.itemId == 0) {
                dropPos.x = posX + ((p % 2 == 0) ? (25 * ((p + 1) / 2)) : -(25 * (p / 2)));
                p++;
                int mesoDrop = (int) (1000 * owner.getWorldServer().getMesoRate());
                reactor.getMap().spawnMesoDrop(mesoDrop, reactor.getMap().calcDropPos(dropPos,
                        reactor.getPosition()), reactor, owner, false, (byte) 2, (short) 0);
            } else if (isUnusableItem(d.itemId)) {
                continue; // skip the entry entirely — no gap left in the spray
            } else {
                dropPos.x = posX + ((p % 2 == 0) ? (25 * ((p + 1) / 2)) : -(25 * (p / 2)));
                p++;
                Item drop;

                if (ItemConstants.getInventoryType(d.itemId) != InventoryType.EQUIP) {
                    drop = new Item(d.itemId, (short) 0, (short) 1);
                } else {
                    drop = (Equip) ii.getEquipById(d.itemId);
                }
                reactor.getMap().dropFromReactor(owner, reactor, drop, dropPos, (short) d.questid, (short) 0);
            }
        }
    }

    private static Point adjustCenterPositionXAxis(Point center, int currIndex, int initialIncrement, int subsequentIncrement, int offset) {
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

    // Uses back-and-forth spray pattern
    private static void delayedReactorDrops(Character owner, List<ReactorDropEntry> drops, Reactor reactor,
                                            Point dropPos, Item prize) {
        final int worldMesoRate = (int) owner.getWorldServer().getMesoRate();

        Point center2 = dropPos;
        short delay = 0;
        int dropIndex = 0;
        boolean prizeClaimed = false;
        for (ReactorDropEntry d : drops) {
            if (isUnusableItem(d.itemId)) {
                continue; // skip the entry entirely — no gap in the spray, no delay burned on it
            }
            center2 = adjustCenterPositionXAxis(center2, dropIndex, dropSprayLength, dropSprayFullWidth, itemDropOffset);
            if (d.itemId == 0) {
                int mesoDrop = getRandomMesoGachaFiller();
                reactor.getMap().spawnMesoDrop(mesoDrop, reactor.getMap().calcDropPos(center2, reactor.getPosition()), reactor, owner,
                        false, (byte) 2, delay);
            } else {
                final Item drop;
                if (prize != null && prize.getItemId() == d.itemId && !prizeClaimed) {
                    drop = prize;         // already built (and gutted) by the caller
                    prizeClaimed = true; // one jackpot per round, even if filler repeats the id
                } else if (ItemConstants.getInventoryType(d.itemId) != InventoryType.EQUIP) {
                    drop = new Item(d.itemId, (short) 0, (short) 1);
                } else {
                    ItemInformationProvider ii = ItemInformationProvider.getInstance();
                    drop = (Equip) ii.getEquipById(d.itemId);
                }
                reactor.getMap().dropFromReactor(owner, reactor, drop, center2, (short) d.questid, delay);
            }
            delay += 200;
            dropIndex++;
        }
    }

    // Original single line spray drops
    private static void delayedReactorDropsStandard(Character owner, List<ReactorDropEntry> drops, Reactor reactor, Point dropPos, int posX) {
        final int worldMesoRate = (int) owner.getWorldServer().getMesoRate();

        dropPos.x -= (12 * drops.size());
        short delay = 0;
        for (ReactorDropEntry d : drops) {
            if (isUnusableItem(d.itemId)) {
                continue; // skip the entry entirely — no gap in the spray, no delay burned on it
            }
            if (d.itemId == 0) {
                int mesoDrop = 1000 * worldMesoRate;
                reactor.getMap().spawnMesoDrop(mesoDrop, reactor.getMap().calcDropPos(dropPos, reactor.getPosition()), reactor, owner,
                        false, (byte) 2, delay);
            } else {
                final Item drop;
                if (ItemConstants.getInventoryType(d.itemId) != InventoryType.EQUIP) {
                    drop = new Item(d.itemId, (short) 0, (short) 1);
                } else {
                    ItemInformationProvider ii = ItemInformationProvider.getInstance();
                    drop = (Equip) ii.getEquipById(d.itemId);
                }
                reactor.getMap().dropFromReactor(owner, reactor, drop, dropPos, (short) d.questid, delay);
            }

            dropPos.x += 25;
            delay += 200;
        }
    }

    private static void dropFromReactorCustom(MapleMap map, int oid, List<ReactorDropEntry> drops, Character owner,
                                              boolean delayed, Item prize) {
        Reactor reactor = map.getReactorByOid(oid);
        int posX = (int) reactor.getPosition().getX();
        int posY = (int) reactor.getPosition().getY();
        boolean meso = true;
        int mesoChance = 10;
        final int minMeso = 100;
        final int maxMeso = 500;
        int minItems = 1;

        if (owner == null) {
            return;
        }

        if (drops.size() % 2 == 0) {
            posX -= 12;
        }
        final Point dropPos = new Point(posX, posY);

        if (!delayed) {
            notDelayedReactorDrops(owner, drops, reactor, dropPos, posX);
        } else {
            delayedReactorDrops(owner, drops, reactor, dropPos, prize);
        }
        hitReactor(map, oid);
    }

    public static void gachaPop(Character fakechar, List<ReactorDropEntry> drops) {
        gachaPop(fakechar, drops, null);
    }

    /**
     * Gacha spray carrying a prebuilt jackpot, matched by id rather than by
     * position: the caller says which item it already built, and every entry in
     * the spray with that id is swapped for it.
     * <p>
     * Matching on id rather than on an index matters because the spray skips
     * unusable entries as it goes, so a list position and a spray position
     * drift apart the moment one is filtered out.
     */
    public static void gachaPop(Character fakechar, List<ReactorDropEntry> drops, Item prize) {
        int reactorOid = spawnReactor(fakechar);
        threeHitReactor(fakechar.getMap(), reactorOid);
        dropFromReactorCustom(fakechar.getMap(), reactorOid, drops, fakechar, true, prize);
        // The synthetic box never removes itself (see scheduleReactorCleanup) - each round
        // otherwise leaks one more reactor onto the map.
        scheduleReactorCleanup(fakechar.getMap(), reactorOid);
    }
}
