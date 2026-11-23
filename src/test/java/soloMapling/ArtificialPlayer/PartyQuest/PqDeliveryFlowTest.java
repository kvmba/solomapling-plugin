package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.life.Monster;
import org.gms.server.life.NPC;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPqData;
import soloMapling.ArtificialPlayer.BotTypes.Henesys.HenesysPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Henesys.HenesysPqData;
import soloMapling.ArtificialPlayer.BotTypes.Horntail.HorntailPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Magatia.MagatiaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Pirate.PiratePQBot;
import soloMapling.ArtificialPlayer.BotTypes.Zakum.ZakumPQBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PqDeliveryFlowTest {
    private static final int BOT_ID = 880199;
    private static final int LEADER_ID = 123;
    private static final int ITEM = 4001120;
    private static final int NPC_ID = 2094002;
    private static final Point NPC_POSITION = new Point(-1000, 0);
    private final Character member = mock(Character.class);
    private final Character leader = mock(Character.class);
    private final MapleMap map = mock(MapleMap.class);
    private final EventInstanceManager instance = mock(EventInstanceManager.class);
    private final AtomicReference<Point> position = new AtomicReference<>(new Point(500, 0));

    @BeforeAll
    static void stubHost() throws Exception {
        PqLobbySpawnTest.stubHost();
    }

    @BeforeEach
    void setUp() {
        when(member.getId()).thenReturn(BOT_ID);
        when(member.getHp()).thenReturn(100);
        when(member.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenAnswer(call -> map.getId());
        when(member.getPosition()).thenAnswer(call -> position.get());
        when(member.getEventInstance()).thenReturn(instance);
        when(instance.getLeader()).thenReturn(leader);
        when(instance.getPlayers()).thenReturn(List.of(leader, member));
        when(leader.getId()).thenReturn(LEADER_ID);
        when(leader.getMap()).thenReturn(map);
        when(leader.getMapId()).thenAnswer(call -> map.getId());
        when(leader.getPosition()).thenReturn(new Point(600, 0));
        Party party = mock(Party.class);
        PartyCharacter partyLeader = mock(PartyCharacter.class);
        when(member.getParty()).thenReturn(party);
        when(party.getLeader()).thenReturn(partyLeader);
        when(partyLeader.getPlayer()).thenReturn(leader);
    }

    @AfterEach
    void clearState() {
        PqActions.clearSeekState(BOT_ID);
    }

    @ParameterizedTest
    @CsvSource({
            "amoria,670010200,9201047,4031595,1",
            "amoria,670010500,9201045,4031597,50",
            "amoria,670010700,9201045,4031594,1",
            "kerning,103000804,9020001,4001008,10",
            "pirate,925100100,2094002,4001120,20",
            "horntail,240050300,2083001,4001093,6",
            "zakum,280010000,2032002,4001018,1",
            "magatia,926110200,2112005,4001134,1",
            "magatia,926100200,2112006,4001135,1",
            "ludi,922010100,2040036,4001022,1"
    })
    void completedCollectionsWalkToTheNamedNpcAndPostRealStockThere(
            String type, int mapId, int npcId, int itemId, int quota) {
        when(map.getId()).thenReturn(mapId);
        npc(npcId);
        when(instance.getIntProperty("statusStg4")).thenReturn(1);
        when(instance.getProperty("1stageclear")).thenReturn("true");
        AtomicInteger carried = new AtomicInteger(quota);
        when(member.getItemQuantity(itemId, false)).thenAnswer(call -> carried.get());
        if ("horntail".equals(type)) when(leader.getItemQuantity(4001092, false)).thenReturn(1);
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var inventory = mockStatic(InventoryManipulator.class);
             var drops = mockStatic(DropCommands.class)) {
            movement.when(GCMovement::isVirtualClock).thenReturn(true);
            inventory.when(() -> InventoryManipulator.removeById(
                    isNull(), any(InventoryType.class), eq(itemId), eq(quota), eq(true), eq(false)))
                    .thenAnswer(call -> {
                        carried.set(0);
                        return null;
                    });
            PartyQuestBot bot = bot(type);

            bot.workStage();

            assertEquals(quota, carried.get());
            movement.verify(() -> GCMovement.move(eq(member), eq(-1000), eq(0), any(), any()), atLeastOnce());
            drops.verifyNoInteractions();
            position.set(NPC_POSITION);
            bot.workStage();
            assertEquals(0, carried.get());
            drops.verify(() -> DropCommands.botThrowToOwnerItemQtyAt(
                    member, itemId, quota, leader, NPC_POSITION));
            drops.verify(() -> DropCommands.botThrowToOwnerItemQtyAt(
                    member, itemId, quota, leader, leader.getPosition()), never());
        }
    }

    @Test
    void neverHandsOffStockToALeaderInADifferentInstanceOfTheSameRoom() {
        when(map.getId()).thenReturn(925100100);
        MapleMap otherRoom = mock(MapleMap.class);
        when(otherRoom.getId()).thenReturn(925100100);
        when(leader.getMap()).thenReturn(otherRoom);
        npc(NPC_ID);
        position.set(NPC_POSITION);
        when(leader.getPosition()).thenReturn(NPC_POSITION);
        when(member.getItemQuantity(ITEM, false)).thenReturn(20);
        try (var inventory = mockStatic(InventoryManipulator.class);
             var drops = mockStatic(DropCommands.class)) {
            assertFalse(PqActions.leaderNear(member, leader));
            assertEquals(0, PqActions.handItemsToLeader(member, ITEM));
            assertEquals(0, PqActions.handItemsToLeaderAfterStage(member, ITEM, NPC_ID));
            PqActions.giveItemAt(member, leader, ITEM, 20, NPC_POSITION);
            inventory.verifyNoInteractions();
            drops.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "kerning,103000801,9020001,2stageclear",
            "kerning,103000802,9020001,3stageclear",
            "kerning,103000803,9020001,4stageclear",
            "amoria,670010300,9201044,2stageclear",
            "amoria,670010301,9201044,2stageclear",
            "amoria,670010302,9201044,2stageclear",
            "amoria,670010400,9201044,3stageclear",
            "ludi,922010800,2040043,8stageclear"
    })
    void clearedPlacementPuzzleReplacesTheOldSpotOrderWithTheNpc(
            String type, int mapId, int npcId, String clearFlag) {
        when(map.getId()).thenReturn(mapId);
        npc(npcId);
        when(instance.getProperty(clearFlag)).thenReturn("true");
        Point post = mapId == 670010400 ? new Point(1042, 240) : NPC_POSITION;
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class)) {
            movement.when(GCMovement::isVirtualClock).thenReturn(true);
            movement.when(() -> GCMovement.hasMoveTarget(member)).thenReturn(true);

            assertFalse(bot(type).workStage(), "clearing the puzzle does not end the run");

            movement.verify(() -> GCMovement.moveIfTargetChanged(member, post.x, post.y, 1, null));
            assertTrue(PqActions.movementShielded(BOT_ID));
        }
    }

    @Test
    void amoriaYieldsThePlacementToCombatWhileTheJudgeRefusesToCheck() {
        when(map.getId()).thenReturn(670010400);
        var spot = AmoriaPqData.ROPE_SPOTS.getFirst();
        var area = AmoriaPqData.areasFor(3).getFirst();
        position.set(spot);
        Monster penalty = mock(Monster.class);
        when(penalty.isAlive()).thenReturn(true);
        when(map.getAllMonsters()).thenReturn(List.of(penalty));
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class)) {
            PqActions.holdPuzzleSpot(member, spot, area);
            assertTrue(PqActions.movementShielded(BOT_ID));
            assertFalse(bot("amoria").workStage());
            assertFalse(PqActions.movementShielded(BOT_ID));
        }
    }

    @Test
    void splitInventoryAndPostedCardsCountTowardTheSameQuotaUntilTheLeaderPicksThemUp() {
        Character teammate = mock(Character.class);
        when(instance.getPlayers()).thenReturn(List.of(leader, member, teammate));
        when(member.getItemQuantity(ITEM, false)).thenReturn(8);
        when(teammate.getItemQuantity(ITEM, false)).thenReturn(12);
        assertFalse(PqActions.needsHuntForLeader(member, ITEM, 20));

        when(member.getItemQuantity(ITEM, false)).thenReturn(0);
        MapItem posted = postedDrop(ITEM, 8);
        when(map.getMapObjectsInRange(any(), anyDouble(), anyList()))
                .thenAnswer(call -> new ArrayList<>(List.of(posted)));
        assertFalse(PqActions.needsHuntForLeader(member, ITEM, 20));
        when(posted.isPickedUp()).thenReturn(true);
        when(leader.getItemQuantity(ITEM, false)).thenReturn(8);
        assertFalse(PqActions.needsHuntForLeader(member, ITEM, 20));
        when(leader.getItemQuantity(ITEM, false)).thenReturn(7);
        assertTrue(PqActions.needsHuntForLeader(member, ITEM, 20));
    }

    @Test
    void ordinaryFloorLootAndAnotherLeadersPostedCardsDoNotCompleteTheQuota() {
        MapItem drop = postedDrop(ITEM, 20);
        when(map.getMapObjectsInRange(any(), anyDouble(), anyList()))
                .thenAnswer(call -> new ArrayList<>(List.of(drop)));
        when(drop.isPermanentOwner()).thenReturn(false);
        assertTrue(PqActions.needsHuntForLeader(member, ITEM, 20));
        when(drop.isPermanentOwner()).thenReturn(true);
        when(drop.getOwnerId()).thenReturn(999);
        assertTrue(PqActions.needsHuntForLeader(member, ITEM, 20));
    }

    @ParameterizedTest
    @CsvSource({
            "amoria,670010500,4031597,50",
            "amoria,670010700,4031594,1",
            "pirate,925100100,4001120,20",
            "kerning,103000804,4001008,10",
            "horntail,240050300,4001093,6",
            "magatia,926110200,4001134,1"
    })
    void postedStockKeepsCollectorsStoppedEvenWhenMonstersRespawn(
            String type, int mapId, int itemId, int quota) {
        when(map.getId()).thenReturn(mapId);
        MapItem posted = postedDrop(itemId, quota);
        List<MapItem> piles = new ArrayList<>(List.of(posted));
        if ("magatia".equals(type)) piles.add(postedDrop(4001135, 1));
        when(map.getMapObjectsInRange(any(), anyDouble(), anyList()))
                .thenAnswer(call -> new ArrayList<>(piles));
        Monster respawn = mock(Monster.class);
        when(respawn.isAlive()).thenReturn(true);
        when(respawn.getPosition()).thenReturn(new Point(550, 0));
        when(map.getAllMonsters()).thenReturn(List.of(respawn));
        if ("horntail".equals(type)) when(leader.getItemQuantity(4001092, false)).thenReturn(1);
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var attacks = mockStatic(BotAttackDriver.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            PartyQuestBot bot = bot(type);
            bot.setRunning(true);
            bot.grindTick();
            bot.grindTick();
            bot.grindTick();

            attacks.verifyNoInteractions();
            verify(member, never()).pickupItem(any());
        }
    }

    @Test
    void aRoomWithoutTheTurnInNpcKeepsItsStockRatherThanUsingAnExitNpcOrTheLeadersFeet() {
        npc(999);
        when(member.getItemQuantity(ITEM, false)).thenReturn(1);
        try (var movement = mockStatic(GCMovement.class); var drops = mockStatic(DropCommands.class)) {
            assertEquals(0, PqActions.deliverAndWait(member, ITEM, NPC_ID));
            assertEquals(1, PqActions.countItem(member, ITEM));
            movement.verify(() -> GCMovement.move(any(), anyInt(), anyInt(), any(), any()), never());
            drops.verifyNoInteractions();
        }
    }

    @Test
    void henesysBotsLeaveCakesForPlayersAndGuardUntilTurnIn() {
        when(map.getId()).thenReturn(HenesysPqData.ENTRY_MAP);
        when(instance.getIntProperty("stage")).thenReturn(HenesysPqData.FLOWERS_TO_BLOOM);
        MapItem cake = mock(MapItem.class);
        when(cake.getPosition()).thenReturn(position.get());
        when(cake.getDropType()).thenReturn((byte) 2);
        when(cake.getItemId()).thenReturn(HenesysPqData.RICE_CAKE);
        when(cake.getItem()).thenReturn(new Item(HenesysPqData.RICE_CAKE, (short) 0, (short) 1));
        when(map.getMapObjectsInRange(any(), anyDouble(), eq(List.of(MapObjectType.ITEM))))
                .thenAnswer(call -> new ArrayList<>(List.of(cake)));
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var attacks = mockStatic(BotAttackDriver.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            PartyQuestBot bot = bot("henesys");
            bot.setRunning(true);
            bot.workStage();
            bot.grindTick();
            attacks.verify(() -> BotAttackDriver.botAttack(member), times(2));

            when(leader.getItemQuantity(HenesysPqData.RICE_CAKE, false)).thenReturn(HenesysPqData.RICE_CAKE_COUNT);
            bot.workStage();
            bot.grindTick();
            attacks.verify(() -> BotAttackDriver.botAttack(member), times(4));

            when(instance.getProperty("1stageclear")).thenReturn("true");
            bot.workStage();
            bot.grindTick();
            attacks.verify(() -> BotAttackDriver.botAttack(member), times(4));
            verify(map, never()).getMapObjectsInRange(any(), anyDouble(), eq(List.of(MapObjectType.ITEM)));
            verify(member, never()).pickupItem(any());
        }
    }

    @Test
    void horntailProgressKeysKeepTheirReactorHandoffInsteadOfWaitingForAnAbsentNpc() {
        when(map.getId()).thenReturn(240050101);
        when(member.getItemQuantity(4001088, false)).thenReturn(1);
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var actions = mockStatic(PqActions.class, call ->
                     "handItemsToLeader".equals(call.getMethod().getName()) ? 0 : call.callRealMethod());
             var attacks = mockStatic(BotAttackDriver.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            PartyQuestBot bot = bot("horntail");
            bot.setRunning(true);

            bot.workStage();
            bot.grindTick();

            actions.verify(() -> PqActions.handItemsToLeader(member, 4001088));
            actions.verify(() -> PqActions.deliverAndWait(any(), anyInt(), anyInt()), never());
            attacks.verify(() -> BotAttackDriver.botAttack(member));
        }
    }

    @Test
    void npcScriptsCannotStartAcrossTheRoom() {
        npc(NPC_ID);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(GCMovement::isVirtualClock).thenReturn(true);

            assertFalse(PqActions.talkTo(member, NPC_ID));
            movement.verify(() -> GCMovement.move(eq(member), eq(-1000), eq(0), any(), any()));
            position.set(NPC_POSITION);
            assertTrue(PqActions.walkToNpc(member, NPC_ID));
        }
    }

    @Test
    void returningToTheNpcRetiresOnlyTheOldChaseAndNeverCancelsANewerPickup() throws Exception {
        npc(NPC_ID);
        when(member.getItemQuantity(ITEM, false)).thenReturn(1);
        Point chase = new Point(1000, 0);
        Point pickup = new Point(800, 0);
        AtomicReference<Point> goal = new AtomicReference<>();
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(GCMovement::isVirtualClock).thenReturn(true);
            movement.when(() -> GCMovement.hasMoveTarget(member)).thenAnswer(call -> goal.get() != null);
            movement.when(() -> GCMovement.move(eq(member), anyInt(), anyInt(), any(), any()))
                    .thenAnswer(call -> {
                        goal.set(new Point(call.getArgument(1), call.getArgument(2)));
                        return null;
                    });
            movement.when(() -> GCMovement.stopIfTarget(eq(member), any())).thenAnswer(call -> {
                if (!call.getArgument(1).equals(goal.get())) return false;
                goal.set(null);
                return true;
            });
            PqActions.walkTo(member, chase);
            var statesField = PqActions.class.getDeclaredField("seekStates");
            statesField.setAccessible(true);
            Object state = ((Map<?, ?>) statesField.get(null)).get(BOT_ID);
            var chaseField = state.getClass().getDeclaredField("lastChaseGoal");
            chaseField.setAccessible(true);
            chaseField.set(state, chase);

            PqActions.deliverAndWait(member, ITEM, NPC_ID);

            assertEquals(NPC_POSITION, goal.get());
            movement.verify(() -> GCMovement.stopIfTarget(member, chase));
            goal.set(pickup);
            PqActions.deliverAndWait(member, ITEM, NPC_ID);
            assertEquals(pickup, goal.get());
            movement.verify(() -> GCMovement.stop(member), never());
        }
    }

    private void npc(int id) {
        NPC npc = mock(NPC.class);
        when(npc.getPosition()).thenReturn(NPC_POSITION);
        when(map.getNPCById(id)).thenReturn(npc);
    }

    private PartyQuestBot bot(String type) {
        return switch (type) {
            case "amoria" -> new AmoriaPQBot(member);
            case "pirate" -> new PiratePQBot(member);
            case "henesys" -> new HenesysPQBot(member);
            case "horntail" -> new HorntailPQBot(member);
            case "kerning" -> new KerningPQBot(member);
            case "zakum" -> new ZakumPQBot(member);
            case "magatia" -> new MagatiaPQBot(member);
            case "ludi" -> new LudiPQBot(member);
            default -> throw new AssertionError(type);
        };
    }

    private MapItem postedDrop(int itemId, int quantity) {
        MapItem drop = mock(MapItem.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(drop.getItem()).thenReturn(item);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getOwnerId()).thenReturn(LEADER_ID);
        when(drop.isPermanentOwner()).thenReturn(true);
        return drop;
    }
}
