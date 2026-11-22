package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import org.gms.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Pirate.PiratePQBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PqLootClaimTest {
    private static final int FIRST_ID = 880305;
    private static final int SECOND_ID = 880306;
    private final MapleMap room = mock(MapleMap.class);
    private final Party party = mock(Party.class);
    private final EventInstanceManager instance = mock(EventInstanceManager.class);

    @BeforeAll
    static void stubHost() throws Exception {
        PqLobbySpawnTest.stubHost();
    }

    @AfterEach
    void clearClaims() {
        PqActions.clearSeekState(FIRST_ID);
        PqActions.clearSeekState(SECOND_ID);
    }

    @ParameterizedTest
    @CsvSource({"ludi,922010100,4001022", "amoria,670010500,4031597", "pirate,925100400,4001117"})
    void macroWalkAndCombatPickupShareTheClosestClaim(String type, int mapId, int itemId) {
        when(room.getId()).thenReturn(mapId);
        Character farther = botOn(FIRST_ID, 0);
        Character closer = botOn(SECOND_ID, 300);
        PartyCharacter first = member(farther);
        PartyCharacter second = member(closer);
        when(party.getMembers()).thenReturn(List.of(first, second));
        Point destination = new Point(600, 0);
        MapItem drop = new MapItem(new Item(itemId, (short) 0, (short) 1), destination,
                mock(Monster.class), farther, null, (byte) 1, false);
        floor(drop);
        var failure = new AtomicReference<Runnable>();
        try (var bots = mockStatic(CharacterStorage.class); var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class)) {
            drop.setDropTime(System.currentTimeMillis() - 1_000);
            PartyQuestBot fartherBot = questBot(type, farther);
            PartyQuestBot closerBot = questBot(type, closer);
            bots.when(() -> CharacterStorage.getBotById(FIRST_ID)).thenReturn(fartherBot);
            bots.when(() -> CharacterStorage.getBotById(SECOND_ID)).thenReturn(closerBot);
            movement.when(() -> GCMovement.canPathTo(any(), eq(destination.x), eq(destination.y))).thenReturn(true);
            movement.when(() -> GCMovement.move(eq(closer), eq(destination.x), eq(destination.y), any(), any()))
                    .thenAnswer(call -> {
                        failure.set(call.getArgument(4));
                        return null;
                    });

            int[] items = {itemId};
            PqActions.loot(farther, farther.getPosition(), 260, items); // even the room-wide probe loses
            assertFalse(PqActions.approachRoomLoot(farther, items));
            assertTrue(PqActions.approachRoomLoot(closer, items));
            movement.verify(() -> GCMovement.move(eq(farther), anyInt(), anyInt(), any(), any()), never());
            movement.verify(() -> GCMovement.move(eq(closer), eq(destination.x), eq(destination.y), any(), any()));

            movement.when(() -> GCMovement.moveTargetOf(closer)).thenReturn(destination);
            when(farther.getPosition()).thenReturn(destination);
            PqActions.lootSteppedOn(farther, items);
            verify(farther, never()).pickupItem(drop); // do not steal a teammate's live claim

            failure.get().run();
            PqActions.lootSteppedOn(farther, items);
            verify(farther).pickupItem(drop);
        }
    }

    @Test
    void leavesAPlayersSingleOfferingOnTheReactorButCollectsAGenuineDrop() {
        int itemId = 4001058;
        Character bot = botOn(FIRST_ID, 0);
        Character player = mock(Character.class);
        Reactor music = mock(Reactor.class);
        when(music.isAlive()).thenReturn(true);
        when(music.getReactorType()).thenReturn(100);
        when(music.getReactItem((byte) 0)).thenReturn(new Pair<>(itemId, 1));
        when(music.getArea()).thenReturn(new Rectangle(-10, -10, 20, 20));
        when(room.getAllReactors()).thenReturn(List.of(music));

        MapItem offering = drop(itemId, new Point(), player, player);
        MapItem record = drop(itemId, new Point(30, 0), mock(Reactor.class), bot);
        floor(offering, record);
        doAnswer(call -> {
            record.setPickedUp(true);
            return null;
        }).when(bot).pickupItem(record);
        try (var movement = mockStatic(GCMovement.class)) {
            PqActions.lootSteppedOn(bot, new int[]{itemId});
            verify(bot, never()).pickupItem(offering);
            verify(bot).pickupItem(record);

            floor(offering);
            assertEquals(PqActions.LootBeat.NONE, PqActions.collectReactorLoot(bot, new int[]{itemId}));
            verify(bot, never()).pickupItem(offering);
        }
    }

    private Character botOn(int id, int x) {
        int mapId = room.getId();
        Character bot = mock(Character.class);
        Inventory equips = mock(Inventory.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getHp()).thenReturn(100);
        when(bot.getMap()).thenReturn(room);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getParty()).thenReturn(party);
        when(bot.getPartyId()).thenReturn(42);
        when(bot.getEventInstance()).thenReturn(instance);
        when(bot.getPosition()).thenReturn(new Point(x, 0));
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equips);
        return bot;
    }

    private static PartyQuestBot questBot(String type, Character character) {
        PartyQuestBot bot = switch (type) {
            case "ludi" -> new LudiPQBot(character);
            case "amoria" -> new AmoriaPQBot(character);
            case "pirate" -> new PiratePQBot(character);
            default -> throw new AssertionError(type);
        };
        bot.setRunning(true);
        return bot;
    }

    private static PartyCharacter member(Character bot) {
        int id = bot.getId();
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        return member;
    }

    private static MapItem drop(int itemId, Point at, MapObject source, Character owner) {
        return new MapItem(new Item(itemId, (short) 0, (short) 1), at, source, owner, null, (byte) 2, false);
    }

    private void floor(MapItem... drops) {
        when(room.getMapObjectsInRange(any(Point.class), anyDouble(), eq(List.of(MapObjectType.ITEM))))
                .thenAnswer(call -> {
                    Point center = call.getArgument(0);
                    double rangeSq = call.getArgument(1);
                    List<MapObject> found = new ArrayList<>();
                    for (MapItem drop : drops) {
                        if (!drop.isPickedUp() && center.distanceSq(drop.getPosition()) <= rangeSq) found.add(drop);
                    }
                    return found;
                });
    }
}
