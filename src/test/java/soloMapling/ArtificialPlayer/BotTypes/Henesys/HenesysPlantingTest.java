package soloMapling.ArtificialPlayer.BotTypes.Henesys;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import org.gms.service.ConfigService;
import org.gms.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotDeath;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.MapVFX.CustomReactor;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotTypes.Henesys.HenesysPqData.*;

class HenesysPlantingTest {
    private static final int BOT_ID = 880195;
    private final MapleMap map = mock(MapleMap.class);
    private final Character first = member(BOT_ID);
    private final Character second = member(BOT_ID + 1);
    private final List<Reactor> reactors = new ArrayList<>();
    private final Reactor firstFlower = flower(0, new Point(0, -200));
    private final Reactor secondFlower = flower(1, new Point(200, -200));
    private final Reactor firstLeaf = reactor(LEAF_FIRST, new Point(0, 0));
    private final Reactor secondLeaf = reactor(LEAF_FIRST + 1, new Point(60, 0));

    @BeforeAll
    static void stubHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(Locale.class)))
                .thenAnswer(call -> call.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, MessageSource.class)).thenReturn(messages);
        }
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
    }

    @AfterEach
    void releaseSeekState() {
        PqActions.clearSeekState(BOT_ID);
        PqActions.clearSeekState(BOT_ID + 1);
    }

    @Test
    void botsKeepDifferentSeedClaimsAndAStoppedBotDoesNotBlockItsFlower() {
        stubReactors();
        int firstLeafOid = firstLeaf.getObjectId();
        int secondLeafOid = secondLeaf.getObjectId();
        HenesysPQBot claimant = mock(HenesysPQBot.class);
        when(claimant.getChr()).thenReturn(first);
        when(claimant.getRunning()).thenReturn(true);
        when(claimant.death()).thenReturn(mock(BotDeath.class));
        try (var storage = mockStatic(CharacterStorage.class);
             var movement = movement(); var swings = mockStatic(BotAttack.class);
             var hits = mockStatic(CustomReactor.class)) {
            storage.when(() -> CharacterStorage.getBotById(BOT_ID)).thenReturn(claimant);
            swings.when(() -> BotAttack.basicSwing(any())).thenReturn(true);

            HenesysStages.plantOneFlower(first);
            HenesysStages.plantOneFlower(second);
            when(claimant.getRunning()).thenReturn(false);
            HenesysStages.plantOneFlower(second); // keep the second color even when the first opens up
            hits.verify(() -> CustomReactor.hitReactorWithScript(map, secondLeafOid, second), times(2));
            hits.verify(() -> CustomReactor.hitReactorWithScript(map, firstLeafOid, second), never());

            when(secondFlower.getState()).thenReturn((byte) 1);
            when(secondFlower.isActive()).thenReturn(false);
            HenesysStages.plantOneFlower(second);
            hits.verify(() -> CustomReactor.hitReactorWithScript(map, firstLeafOid, second));
            hits.verify(() -> CustomReactor.hitReactorWithScript(map, firstLeafOid, first));
        }
    }

    @Test
    void walksToTheRightSeedPicksItUpAndLeavesItForTheFlowerToConsume() {
        stubReactors();
        when(firstFlower.getState()).thenReturn((byte) 1);
        when(firstFlower.isActive()).thenReturn(false);
        Point seedAt = new Point(350, 0);
        Point flowerAt = secondFlower.getPosition();
        int seed = 4001096;
        MapItem wrong = seedDrop(4001095, new Point());
        when(first.getPartyId()).thenReturn(57);
        when(second.getPartyId()).thenReturn(57);
        MapItem wanted = new MapItem(new Item(seed, (short) 0, (short) 1), seedAt,
                secondLeaf, second, null, (byte) 1, false);
        wanted.setDropTime(Long.MAX_VALUE);
        MapItem planted = seedDrop(seed, flowerAt);
        AtomicInteger carried = new AtomicInteger();
        when(first.getItemQuantity(seed, false)).thenAnswer(call -> carried.get());
        List<MapItem> drops = new ArrayList<>(List.of(wrong, wanted));
        when(map.getMapObjectsInRange(any(), anyDouble(), anyList())).thenAnswer(call -> new ArrayList<>(drops));
        doAnswer(call -> {
            carried.incrementAndGet();
            wanted.setPickedUp(true);
            return null;
        }).when(first).pickupItem(wanted);

        try (var movement = movement(); var swings = mockStatic(BotAttack.class);
             var actions = mockStatic(PqActions.class, CALLS_REAL_METHODS)) {
            movement.when(() -> GCMovement.canPathTo(first, seedAt.x, seedAt.y)).thenReturn(true);
            actions.when(() -> PqActions.dropStack(first, seed, 1, flowerAt)).thenAnswer(call -> {
                assertEquals(1, carried.getAndDecrement());
                drops.add(planted);
                when(map.getMapObjectsInRect(eq(secondFlower.getArea()), anyList())).thenReturn(List.of(planted));
                return true;
            });

            HenesysStages.plantOneFlower(first);
            movement.when(() -> GCMovement.moveTargetOf(first)).thenReturn(seedAt);
            HenesysStages.plantOneFlower(first);
            movement.verify(() -> GCMovement.move(eq(first), eq(seedAt.x), eq(seedAt.y), any(), any()));
            verify(first, never()).pickupItem(any());

            when(first.getPosition()).thenReturn(seedAt);
            movement.when(() -> GCMovement.moveTargetOf(first)).thenReturn(null);
            HenesysStages.plantOneFlower(first);
            verify(first, never()).pickupItem(any());

            wanted.setDropTime(System.currentTimeMillis() - 1_000);
            assertFalse(DropCommands.botCanLoot(first, wanted), "the teammate's drop is still protected");
            HenesysStages.plantOneFlower(first);
            verify(first).pickupItem(wanted);
            movement.verify(() -> GCMovement.move(eq(first), eq(flowerAt.x), eq(flowerAt.y), any(), any()));

            when(first.getPosition()).thenReturn(flowerAt);
            HenesysStages.plantOneFlower(first);
            HenesysStages.plantOneFlower(first); // the script has not consumed the seed yet
            actions.verify(() -> PqActions.dropStack(first, seed, 1, flowerAt));
            verify(first, never()).pickupItem(wrong);
            verify(first, never()).pickupItem(planted);
            swings.verifyNoInteractions();
        }
    }

    @Test
    void unreachableFlowerIsReleasedEvenWhileTheClaimantIsStillRunning() {
        stubReactors();
        when(secondFlower.isActive()).thenReturn(false);
        Point flowerAt = firstFlower.getPosition();
        int oid = firstFlower.getObjectId();
        when(first.getItemQuantity(SEED_FIRST, false)).thenReturn(1);
        when(second.getItemQuantity(SEED_FIRST, false)).thenReturn(1);
        when(second.getPosition()).thenReturn(flowerAt);
        HenesysPQBot claimant = mock(HenesysPQBot.class);
        when(claimant.getChr()).thenReturn(first);
        when(claimant.getRunning()).thenReturn(true);
        when(claimant.death()).thenReturn(mock(BotDeath.class));
        AtomicReference<Runnable> abandon = new AtomicReference<>();
        try (var storage = mockStatic(CharacterStorage.class); var movement = movement();
             var actions = mockStatic(PqActions.class, CALLS_REAL_METHODS)) {
            storage.when(() -> CharacterStorage.getBotById(BOT_ID)).thenReturn(claimant);
            movement.when(() -> GCMovement.move(eq(first), eq(flowerAt.x), eq(flowerAt.y), any(), any()))
                    .thenAnswer(call -> { abandon.set(call.getArgument(4)); return null; });
            actions.when(() -> PqActions.dropStack(second, SEED_FIRST, 1, flowerAt)).thenReturn(true);

            for (int attempt = 0; attempt < 2; attempt++) {
                HenesysStages.plantOneFlower(first);
                abandon.get().run();
            }
            HenesysStages.plantOneFlower(first);
            assertTrue(PqActions.reactorApproachDeferred(first, oid));
            HenesysStages.plantOneFlower(first); // cannot claim it again before the teammate
            HenesysStages.plantOneFlower(second);
            actions.verify(() -> PqActions.dropStack(second, SEED_FIRST, 1, flowerAt));
        }
    }

    @Test
    void arrivalAtTheAlternateLeafDoesNotRevertToTheFirstEvenAfterItsCooldown() {
        Point blocked = new Point(300, 0);
        Point alternate = new Point(-300, 0);
        when(firstLeaf.getPosition()).thenReturn(blocked);
        int alternateOid = reactor(LEAF_FIRST, alternate).getObjectId();
        stubReactors();
        AtomicReference<Runnable> abandon = new AtomicReference<>();
        AtomicReference<Runnable> arrive = new AtomicReference<>();
        try (var movement = movement(); var swings = mockStatic(BotAttack.class);
             var hits = mockStatic(CustomReactor.class); var executor = mockStatic(ExecutorServiceManager.class)) {
            swings.when(() -> BotAttack.basicSwing(first)).thenReturn(true);
            movement.when(() -> GCMovement.move(eq(first), anyInt(), anyInt(), any(), any()))
                    .thenAnswer(call -> {
                        arrive.set(call.getArgument(3));
                        abandon.set(call.getArgument(4));
                        return null;
                    });
            for (int attempt = 0; attempt < 2; attempt++) {
                HenesysStages.plantOneFlower(first);
                abandon.get().run();
            }
            HenesysStages.plantOneFlower(first);
            movement.verify(() -> GCMovement.move(eq(first), eq(alternate.x), eq(alternate.y), any(), any()));

            when(first.getPosition()).thenReturn(alternate);
            movement.when(GCMovement::movementNowMs).thenReturn(60_000L);
            arrive.get().run();
            HenesysStages.plantOneFlower(first);
            hits.verify(() -> CustomReactor.hitReactorWithScript(map, alternateOid, first));
            movement.verify(() -> GCMovement.move(eq(first), eq(blocked.x), eq(blocked.y), any(), any()), times(2));
        }
    }

    private Character member(int id) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getMap()).thenReturn(map);
        when(member.getPosition()).thenReturn(new Point());
        return member;
    }

    private Reactor reactor(int dataId, Point position) {
        Reactor reactor = mock(Reactor.class);
        int oid = 100 + reactors.size();
        when(reactor.getId()).thenReturn(dataId);
        when(reactor.getObjectId()).thenReturn(oid);
        when(reactor.getPosition()).thenReturn(position);
        when(reactor.isAlive()).thenReturn(true);
        when(reactor.isActive()).thenReturn(true);
        when(map.getReactorByOid(oid)).thenReturn(reactor);
        reactors.add(reactor);
        return reactor;
    }

    private Reactor flower(int index, Point position) {
        Reactor flower = reactor(FLOWER_FIRST + index, position);
        when(flower.getReactorType()).thenReturn(100);
        when(flower.getReactItem((byte) 0)).thenReturn(new Pair<>(4001095 + index, 1));
        when(flower.getArea()).thenReturn(new Rectangle(position.x - 20, position.y - 42, 40, 58));
        return flower;
    }

    private MapItem seedDrop(int itemId, Point position) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItem()).thenReturn(new Item(itemId, (short) 0, (short) 1));
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getPosition()).thenReturn(position);
        when(drop.getOwnerId()).thenReturn(BOT_ID);
        return drop;
    }

    private void stubReactors() {
        when(map.getAllReactors()).thenReturn(reactors);
        when(map.getReactorsByIdRange(anyInt(), anyInt())).thenAnswer(call -> {
            int firstId = call.getArgument(0);
            int lastId = call.getArgument(1);
            return reactors.stream().filter(r -> r.getId() >= firstId && r.getId() <= lastId).toList();
        });
    }

    private MockedStatic<GCMovement> movement() {
        var movement = mockStatic(GCMovement.class);
        movement.when(() -> GCMovement.isSettled(any())).thenReturn(true);
        movement.when(() -> GCMovement.groundPointBelow(any(), anyInt(), anyInt()))
                .thenAnswer(call -> new Point(call.getArgument(1), call.getArgument(2)));
        // Separate upper and lower floors; being under the flower is not arrival.
        movement.when(() -> GCMovement.peekRegionIdAt(any(), anyInt(), anyInt()))
                .thenAnswer(call -> (int) call.getArgument(2) < 0 ? 1 : 2);
        return movement;
    }
}
