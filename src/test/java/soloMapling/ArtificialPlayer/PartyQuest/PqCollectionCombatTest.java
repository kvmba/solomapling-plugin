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
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Magatia.MagatiaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Pirate.PiratePQBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPqData.STATUE_PIECE;

class PqCollectionCombatTest {
    private static final int BOT_ID = 880198;
    private static final int ROOKIE = 4001120;
    private static final int RISING = 4001121;
    private static final int VETERAN = 4001122;
    private static final Point NPC_POSITION = new Point(-600, 0);

    private final Character member = mock(Character.class);
    private final Character leader = mock(Character.class);
    private final MapleMap map = mock(MapleMap.class);
    private final EventInstanceManager instance = mock(EventInstanceManager.class);
    private final Point position = new Point();

    @BeforeAll
    static void stubHost() throws Exception {
        PqLobbySpawnTest.stubHost();
    }

    @BeforeEach
    void setUp() {
        when(member.getId()).thenReturn(BOT_ID);
        when(member.getHp()).thenReturn(100);
        when(member.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        when(member.getParty()).thenReturn(mock(Party.class));
        when(member.getEventInstance()).thenReturn(instance);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenAnswer(call -> map.getId());
        when(member.getPosition()).thenReturn(position);
        when(instance.getLeader()).thenReturn(leader);
        when(instance.getPlayers()).thenReturn(List.of(leader, member));
        PartyCharacter partyLeader = mock(PartyCharacter.class);
        when(member.getParty().getLeader()).thenReturn(partyLeader);
        when(partyLeader.getPlayer()).thenReturn(leader);
        for (int npcId : new int[]{2094002, 9201045, 2112003, 2112004, 2112005, 2112006}) {
            org.gms.server.life.NPC npc = mock(org.gms.server.life.NPC.class);
            when(npc.getPosition()).thenReturn(NPC_POSITION);
            when(map.getNPCById(npcId)).thenReturn(npc);
        }
        when(leader.getMapId()).thenAnswer(call -> map.getId());
        when(leader.getPosition()).thenReturn(new Point(600, 0));
    }

    @AfterEach
    void clearCombat() {
        PqActions.clearSeekState(BOT_ID);
    }

    @Test
    void pirateStopsAtTheCurrentRoundsQuotaAndResumesForTheNextMedal() {
        PartyQuestBot bot = start(925100100, () -> new PiratePQBot(member));
        when(leader.getItemQuantity(ROOKIE, false)).thenReturn(21);
        when(leader.getItemQuantity(RISING, false)).thenReturn(19);
        AtomicInteger carried = new AtomicInteger();
        when(member.getItemQuantity(RISING, false)).thenAnswer(call -> carried.get());
        MapItem rookie = medalDrop(ROOKIE);
        MapItem rising = medalDrop(RISING);
        when(map.getMapObjectsInRange(eq(position), anyDouble(), anyList()))
                .thenAnswer(call -> new ArrayList<>(List.of(rookie, rising)));
        doAnswer(call -> {
            carried.incrementAndGet();
            when(rising.isPickedUp()).thenReturn(true);
            return null;
        }).when(member).pickupItem(rising);

        try (var movement = movementWithGraph(); var attack = mockStatic(BotAttackDriver.class)) {
            bot.workStage();
            bot.grindTick();
            verify(member, never()).pickupItem(any());
            attack.verifyNoInteractions();

            when(instance.getIntProperty("stage2")).thenReturn(1);
            bot.grindTick();
            verify(member, never()).pickupItem(rookie);
            verify(member).pickupItem(rising);
            attack.verify(() -> BotAttackDriver.botAttack(member), never());

            // The bot holds the last missing medal. Its next work beat must deliver, not chase.
            bot.workStage();
            bot.grindTick();
            movement.verify(() -> GCMovement.move(eq(member), eq(-600), eq(0), any(), any()), atLeastOnce());
            attack.verify(() -> BotAttackDriver.botAttack(member), never());
        }
    }

    @Test
    void pirateDoesNotAttackIfTheLeaderWinsTheRaceForTheLastMedal() {
        PartyQuestBot bot = start(925100100, () -> new PiratePQBot(member));
        MapItem medal = medalDrop(ROOKIE);
        AtomicInteger leaderMedals = new AtomicInteger(19);
        when(leader.getItemQuantity(ROOKIE, false)).thenAnswer(call -> leaderMedals.get());
        when(map.getMapObjectsInRange(eq(position), anyDouble(), anyList()))
                .thenAnswer(call -> new ArrayList<>(List.of(medal)));
        doAnswer(call -> {
            leaderMedals.incrementAndGet();
            when(medal.isPickedUp()).thenReturn(true);
            return null;
        }).when(member).pickupItem(medal);

        try (var movement = movementWithGraph(); var attack = mockStatic(BotAttackDriver.class)) {
            bot.grindTick();
            verify(member).pickupItem(medal);
            attack.verifyNoInteractions();
        }
    }

    @Test
    void pirateDoesNotLootThePreviousMedalIfTheRoundChangesDuringTheSweep() {
        PartyQuestBot bot = start(925100100, () -> new PiratePQBot(member));
        MapItem rookie = medalDrop(ROOKIE);
        MapItem rising = medalDrop(RISING);
        when(instance.getIntProperty("stage2")).thenReturn(0, 1);
        when(map.getMapObjectsInRange(eq(position), anyDouble(), anyList()))
                .thenAnswer(call -> new ArrayList<>(List.of(rookie, rising)));

        try (var movement = movementWithGraph(); var attack = mockStatic(BotAttackDriver.class)) {
            bot.grindTick();
            verify(member, never()).pickupItem(rookie);
            bot.grindTick();
            verify(member).pickupItem(rising);
        }
    }

    @Test
    void pirateStopsAfterTheLastSubmissionEvenThoughTheMedalsWereConsumed() {
        PartyQuestBot bot = start(925100100, () -> new PiratePQBot(member));
        try (var movement = movementWithGraph(); var attack = mockStatic(BotAttackDriver.class)) {
            when(instance.getIntProperty("stage2")).thenReturn(2);
            when(leader.getItemQuantity(VETERAN, false)).thenReturn(19);
            bot.grindTick();
            attack.verify(() -> BotAttackDriver.botAttack(member));
            verify(leader, atLeastOnce()).getItemQuantity(VETERAN, false);

            when(instance.getIntProperty("stage2")).thenReturn(3);
            when(leader.getItemQuantity(VETERAN, false)).thenReturn(0);
            bot.workStage();
            bot.grindTick();
            attack.verify(() -> BotAttackDriver.botAttack(member)); // still just the pre-submit attack
        }
    }

    @Test
    void amoriaStopsAtFiftyAndDoesNotResumeWhenAmosConsumesThePieces() {
        PartyQuestBot bot = start(670010500, () -> new AmoriaPQBot(member));
        try (var movement = movementWithGraph(); var attack = mockStatic(BotAttackDriver.class)) {
            when(leader.getItemQuantity(STATUE_PIECE, false)).thenReturn(49);
            bot.grindTick();
            attack.verify(() -> BotAttackDriver.botAttack(member));

            when(leader.getItemQuantity(STATUE_PIECE, false)).thenReturn(50);
            bot.workStage();
            bot.grindTick();
            when(leader.getItemQuantity(STATUE_PIECE, false)).thenReturn(0);
            when(instance.getProperty("4stageclear")).thenReturn("true");
            bot.workStage();
            bot.grindTick();
            attack.verify(() -> BotAttackDriver.botAttack(member)); // both beats stood down

            when(map.getId()).thenReturn(670010600);
            bot.grindTick(); // the following survival stage still fights
            attack.verify(() -> BotAttackDriver.botAttack(member), times(2));
        }
    }

    @Test
    void amoriaDeliversTheTeamsSplitQuotaAtTheNpcWithoutLettingCombatRetargetTheWalk() {
        PartyQuestBot bot = start(670010500, () -> new AmoriaPQBot(member));
        // A second member can supply the other 25. This one must not wait to get all 50 itself.
        when(member.getItemQuantity(STATUE_PIECE, false)).thenReturn(25);
        Character teammate = mock(Character.class);
        when(teammate.getItemQuantity(STATUE_PIECE, false)).thenReturn(25);
        when(instance.getPlayers()).thenReturn(List.of(leader, member, teammate));
        try (var movement = movementWithGraph();
             var inventory = mockStatic(InventoryManipulator.class);
             var drops = mockStatic(DropCommands.class);
             var actions = mockStatic(PqActions.class, call -> switch (call.getMethod().getName()) {
                 case "seekAndAttack", "attack" -> RETURNS_DEFAULTS.answer(call);
                 default -> call.callRealMethod();
             })) {
            bot.workStage();
            movement.verify(() -> GCMovement.move(eq(member), eq(-600), eq(0), any(), any()), atLeastOnce());
            bot.grindTick();
            actions.verify(() -> PqActions.attack(member), never());
            actions.verify(() -> PqActions.seekAndAttack(member, false, new int[]{STATUE_PIECE}), never());

            Point at = NPC_POSITION;
            when(member.getPosition()).thenReturn(at);
            bot.workStage();
            inventory.verify(() -> InventoryManipulator.removeById(
                    isNull(), any(InventoryType.class), eq(STATUE_PIECE), eq(25), eq(true), eq(false)));
            drops.verify(() -> DropCommands.botThrowToOwnerItemQtyAt(member, STATUE_PIECE, 25, leader, at));
        }
    }

    @Test
    void amoriaReturnsToTheNpcRatherThanTryingToReachTheLeadersHuntingPosition() {
        PartyQuestBot bot = start(670010500, () -> new AmoriaPQBot(member));
        when(member.getItemQuantity(STATUE_PIECE, false)).thenReturn(50);
        try (var movement = movementWithGraph()) {
            movement.when(() -> GCMovement.nearestReachableGround(eq(member), any(Rectangle.class), any()))
                    .thenReturn(new Point(300, 0));
            bot.workStage();
            movement.verify(() -> GCMovement.move(eq(member), eq(-600), eq(0), any(), any()), atLeastOnce());
            movement.verify(() -> GCMovement.nearestReachableGround(eq(member), any(Rectangle.class), any()), never());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "926110100, statusStg3, 2, 3", "926100100, statusStg3, 2, 3",
            "926110200, statusStg4, 0, 1", "926100200, statusStg4, 0, 1"
    })
    void magatiaStopsBothAttackPathsWhenACollectionRoomIsDone(int mapId, String flag, int open, int cleared) {
        PartyQuestBot bot = start(mapId, () -> new MagatiaPQBot(member));
        when(map.getMonsters()).thenReturn(List.of(mock(Monster.class)));
        try (var movement = movementWithGraph(); var attack = mockStatic(BotAttackDriver.class)) {
            when(instance.getIntProperty(flag)).thenReturn(open);
            bot.grindTick();
            bot.workStage();
            attack.verify(() -> BotAttackDriver.botAttack(member), times(2));
            attack.clearInvocations();

            when(instance.getIntProperty(flag)).thenReturn(cleared);
            bot.grindTick();
            bot.workStage();
            attack.verifyNoInteractions();

            when(map.getId()).thenReturn(mapId / 1000 * 1000 + 401);
            bot.grindTick(); // the escort room has its own work, regardless of the earlier flag
            attack.verify(() -> BotAttackDriver.botAttack(member));
        }
    }

    @ParameterizedTest
    @CsvSource({"926110200, 4001130, 4001131", "926100200, 4001131, 4001130"})
    void magatiaStillDeliversTheRightLetterAfterTheDocumentsButNotAfterTheLetterTurnIn(
            int mapId, int letter, int otherLetter) {
        PartyQuestBot bot = start(mapId, () -> new MagatiaPQBot(member));
        when(instance.getIntProperty("statusStg4")).thenReturn(1);
        PartyCharacter partyLeader = mock(PartyCharacter.class);
        when(member.getParty().getLeader()).thenReturn(partyLeader);
        when(partyLeader.getPlayer()).thenReturn(leader);
        when(member.getItemQuantity(otherLetter, false)).thenReturn(1);

        try (var movement = movementWithGraph(); var attack = mockStatic(BotAttackDriver.class)) {
            bot.workStage();
            movement.verify(() -> GCMovement.move(eq(member), eq(-600), eq(0), any(), any()), atLeastOnce());

            when(member.getItemQuantity(letter, false)).thenReturn(1);
            bot.workStage();
            bot.grindTick();
            movement.verify(() -> GCMovement.move(member, 600, 0), never());
            attack.verifyNoInteractions();

            when(instance.getIntProperty("npcShocked")).thenReturn(1);
            bot.workStage();
            movement.verify(() -> GCMovement.move(member, 600, 0), never());
        }
    }

    private PartyQuestBot start(int mapId, Supplier<PartyQuestBot> factory) {
        when(map.getId()).thenReturn(mapId);
        try (var generation = mockStatic(BotGeneration.class)) {
            PartyQuestBot bot = factory.get();
            bot.setRunning(true);
            return bot;
        }
    }

    private MockedStatic<GCMovement> movementWithGraph() {
        var movement = mockStatic(GCMovement.class);
        movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
        movement.when(GCMovement::isVirtualClock).thenReturn(true);
        return movement;
    }

    private MapItem medalDrop(int itemId) {
        MapItem drop = mock(MapItem.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) 1);
        when(drop.getItem()).thenReturn(item);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getPosition()).thenReturn(position);
        when(drop.getOwnerId()).thenReturn(BOT_ID);
        return drop;
    }
}
