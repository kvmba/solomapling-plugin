package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.BossRush.BossRushPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Dojo.DojoPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Ellin.EllinPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Horntail.HorntailPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiStages;
import soloMapling.ArtificialPlayer.BotTypes.Magatia.MagatiaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQOrchestrator;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQSharedContext;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OrbisPqData;
import soloMapling.ArtificialPlayer.BotTypes.Pirate.PiratePQBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTickService;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class PqStageTransitionTest {
    private static final int KERNING_1 = 103000800;
    private static final int BOT_ID = 880192;
    private final Character member = mock(Character.class);
    private final Character leader = mock(Character.class);
    private final Party party = mock(Party.class);
    private final EventInstanceManager instance = mock(EventInstanceManager.class);
    private int stageTicks;

    @BeforeAll
    static void stubHost() throws Exception {
        PqLobbySpawnTest.stubHost();
    }

    @BeforeEach
    void joinParty() {
        when(member.getId()).thenReturn(BOT_ID);
        when(member.getHp()).thenReturn(100);
        when(member.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        when(member.getParty()).thenReturn(party);
        when(leader.getId()).thenReturn(123);
        when(leader.getParty()).thenReturn(party);
        when(party.getLeaderId()).thenReturn(123);
        PartyCharacter captain = mock(PartyCharacter.class);
        when(captain.getPlayer()).thenReturn(leader);
        when(party.getLeader()).thenReturn(captain);
    }

    @Test
    void walkingToTheDoorOwnsTheTickButAScriptRefusalReturnsItToStageWork() {
        MapleMap here = room(KERNING_1);
        place(member, here);
        place(leader, room(KERNING_1 + 1));
        Portal door = door(here, "next00", KERNING_1 + 1);
        PartyQuestBot bot = shell();
        try (var actions = mockStatic(PqActions.class); var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.hasMoveTarget(member)).thenReturn(true);
            bot.tickPartyQuest();
            assertEquals(0, stageTicks, "a wait or delivery in the old room must not overwrite the door walk");

            actions.when(() -> PqActions.walkToPortal(member, door)).thenReturn(true);
            bot.tickPartyQuest(); // entry did not change the map
            actions.verify(() -> PqActions.enterPortal(member, door));
            assertEquals(1, stageTicks, "a refused gate must not disable the work that opens it");
        }
    }

    @Test
    void catchesUpAcrossTwoGatedDoorsWithoutRunningWorkInEitherOldRoom() {
        MapleMap first = room(KERNING_1);
        MapleMap second = room(KERNING_1 + 1);
        MapleMap third = room(KERNING_1 + 2);
        place(member, first);
        place(leader, third);
        Portal firstDoor = door(first, "next00", second.getId());
        Portal secondDoor = door(second, "next00", third.getId());
        PartyQuestBot bot = shell();
        try (var actions = mockStatic(PqActions.class); var movement = mockStatic(GCMovement.class)) {
            actions.when(() -> PqActions.walkToPortal(eq(member), any(Portal.class))).thenReturn(true);
            actions.when(() -> PqActions.enterPortal(member, firstDoor)).thenAnswer(call -> {
                place(member, second);
                return null;
            });
            actions.when(() -> PqActions.enterPortal(member, secondDoor)).thenAnswer(call -> {
                place(member, third);
                return null;
            });
            bot.tickPartyQuest();
            assertSame(third, member.getMap());
            assertEquals(0, stageTicks);
        }
    }

    @ParameterizedTest
    @CsvSource({"STAGE_2_NAVIGATE,1", "STAGE_2_WAIT,2"})
    void orbisMusicTurnInReturnsThroughTheTower(OPQBot.OPQBotState state, int clearFlag) {
        MapleMap music = room(920010400);
        MapleMap tower = room(920010100);
        place(member, music);
        place(leader, room(920010500));
        when(member.getPosition()).thenReturn(new Point(0, 0));
        when(member.getEventInstance()).thenReturn(instance);
        Portal exit = door(music, "st00", 999999999);
        OPQOrchestrator run = mock(OPQOrchestrator.class);
        when(run.getSharedContext()).thenReturn(new OPQSharedContext());
        try (var generation = mockStatic(BotGeneration.class);
             var coordinator = mockStatic(OPQOrchestrator.class, call -> run);
             var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.readEimInt(member, "statusStg3", -1)).thenReturn(clearFlag);
            actions.when(() -> PqActions.partyLeader(member)).thenReturn(leader);
            actions.when(() -> PqActions.walkThroughPortal(member, exit)).thenAnswer(call -> {
                place(member, tower);
                return null;
            });

            OPQBot bot = new OPQBot(member);
            bot.setRunning(true);
            bot.setStateForDebug(state);
            bot.updateState();
            assertSame(tower, member.getMap());
        }
    }

    @Test
    void leaderEntryWakesOnlyStragglersOfTheSameRun() {
        MapleMap from = room(KERNING_1);
        MapleMap destination = room(KERNING_1 + 1);
        place(member, from);
        place(leader, destination);
        PartyCharacter teammate = mock(PartyCharacter.class);
        when(teammate.getId()).thenReturn(BOT_ID);
        when(party.getMembers()).thenReturn(List.of(teammate));
        PartyQuestBot bot = shell();
        bot.setRunning(true);
        try (var storage = mockStatic(CharacterStorage.class); var ticks = mockStatic(BotTickService.class)) {
            storage.when(() -> CharacterStorage.getBotById(BOT_ID)).thenReturn(bot);
            PartyQuestBot.wakeForLeaderEntry(leader, destination);
            ticks.verify(() -> BotTickService.requestEarlierTick(eq(BOT_ID), any(Runnable.class),
                    longThat(ms -> ms >= 150 && ms <= 700)));

            when(from.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
            ticks.clearInvocations();
            PartyQuestBot.wakeForLeaderEntry(leader, destination);
            ticks.verifyNoInteractions();
        }
    }

    @Test
    void inRunCadenceUsesTheMapsInstanceButPublicMapsStaySlow() {
        MapleMap stage = room(KERNING_1);
        place(member, stage);
        PartyQuestBot bot = shell();
        long fighting = bot.lowPriorityDelayMs(); // the character's own event instance is null
        assertTrue(fighting >= 2_000 && fighting <= 3_500);
        when(stage.getEventInstance()).thenReturn(null);
        long publicMap = bot.lowPriorityDelayMs();
        assertTrue(publicMap >= 36_000 && publicMap < 48_000);
    }

    @ParameterizedTest
    @CsvSource({
            "pirate,925100000,925100100,next00",
            "pirate,925100201,925100400,out00",
            "ellin,930000100,930000200,east00",
            "magatia,926110000,926110100,pt00",
            "magatia,926100203,926100300,out00",
            "dojang,925030101,925030301,out001",
            "horntail,240050105,240050100,in00",
            "bossrush,970030500,970030001,clear"
    })
    void knowsTheQuestSpecificDoorEvenWhenWzHasNoDestination(String type, int from, int to, String name) {
        MapleMap here = room(from);
        place(member, here);
        place(leader, room(to));
        Portal exit = door(here, name, 999999999);
        try (var generation = mockStatic(BotGeneration.class); var actions = mockStatic(PqActions.class);
             var movement = mockStatic(GCMovement.class)) {
            actions.when(() -> PqActions.walkToPortal(member, exit)).thenReturn(true);
            actions.when(() -> PqActions.enterPortal(member, exit)).thenAnswer(call -> {
                place(member, leader.getMap());
                return null;
            });
            bot(type).tickPartyQuest();
            assertSame(leader.getMap(), member.getMap());
        }
    }

    @Test
    void ludiTowerKeepsClimbingWhenTheLeaderIsAlreadyPastIt() {
        MapleMap tower = room(922010600);
        place(member, tower);
        place(leader, room(922010700));
        when(member.getEventInstance()).thenReturn(instance);
        when(instance.getProperty("6stageclear")).thenReturn("true");
        when(member.getPosition()).thenReturn(new Point(0, -100));
        Portal rung = door(tower, "h000", tower.getId());
        Portal exit = door(tower, "next00", 922010700);
        when(rung.getTarget()).thenReturn("next00");
        when(exit.getPosition()).thenReturn(new Point(0, -300));
        when(tower.getPortals()).thenReturn(List.of(rung, exit));
        try (var generation = mockStatic(BotGeneration.class); var actions = mockStatic(PqActions.class);
             var movement = mockStatic(GCMovement.class)) {
            actions.when(() -> PqActions.readEimString(member, "6stageclear")).thenReturn("true");
            new LudiPQBot(member).tickPartyQuest();
            actions.verify(() -> PqActions.walkThroughPortal(member, rung));
            actions.verify(() -> PqActions.walkToPortal(member, exit), never());
        }
    }

    @Test
    void ludiTrapRoomUsesItsOwnDoorAndTheSharedWalkToPortalRule() {
        MapleMap trap = room(LudiPqData.TRAP_ROOM);
        place(member, trap);
        when(member.getPosition()).thenReturn(new Point(0, 0));
        Portal exit = door(trap, "out00", LudiPqData.STAGE_2);
        Point position = new Point(100, 0);
        when(exit.getPosition()).thenReturn(position);
        try (var actions = mockStatic(PqActions.class); var movement = mockStatic(GCMovement.class)) {
            actions.when(() -> PqActions.readEimString(member, "2stageclear")).thenReturn("true");
            movement.when(() -> GCMovement.portalApproachPoint(trap, exit)).thenReturn(position);
            LudiStages.workTrapRoom(member);
            actions.verify(() -> PqActions.walkThroughPortal(member, exit));
            actions.verify(() -> PqActions.enterPortal(member, exit), never());
        }
    }

    @Test
    void ludiSubroomChoosesTheReachableExitEvenAfterStageClear() {
        MapleMap subroom = room(LudiPqData.STAGE5_ROOM_FIRST);
        place(member, subroom);
        place(leader, room(LudiPqData.STAGE_5));
        when(member.getPosition()).thenReturn(new Point(100, -120));
        Portal upper = door(subroom, "out00", LudiPqData.STAGE_5);
        Portal lower = door(subroom, "out01", LudiPqData.STAGE_5);
        Point upperPosition = new Point(100, -900);
        Point lowerPosition = new Point(200, -100);
        when(upper.getPosition()).thenReturn(upperPosition);
        when(lower.getPosition()).thenReturn(lowerPosition);
        when(subroom.getPortals()).thenReturn(List.of(upper, lower));

        try (var generation = mockStatic(BotGeneration.class); var actions = mockStatic(PqActions.class);
             var movement = mockStatic(GCMovement.class)) {
            actions.when(() -> PqActions.readEimString(member, "5stageclear")).thenReturn("true");
            movement.when(() -> GCMovement.hasMoveTarget(member)).thenReturn(true);
            movement.when(() -> GCMovement.portalApproachPoint(subroom, upper)).thenReturn(upperPosition);
            movement.when(() -> GCMovement.portalApproachPoint(subroom, lower)).thenReturn(lowerPosition);
            movement.when(() -> GCMovement.canPathTo(member, lowerPosition.x, lowerPosition.y)).thenReturn(true);
            new LudiPQBot(member).tickPartyQuest();

            actions.verify(() -> PqActions.walkThroughPortal(member, lower));
            actions.verify(() -> PqActions.walkToPortal(member, upper), never());
        } finally {
            LudiStages.releaseRoomState(BOT_ID);
        }
    }

    @ParameterizedTest
    @EnumSource(value = OPQBot.OPQBotState.class, names = {"STAGE_1_RETURN", "STAGE_1_WAIT"})
    void orbisCloudStageStillUsesTheTowerDoorWhenTheLeaderIsTwoRoomsAhead(OPQBot.OPQBotState state) {
        MapleMap clouds = room(920010000);
        place(member, clouds);
        place(leader, room(920010200));
        when(member.getEventInstance()).thenReturn(instance);
        Portal exit = door(clouds, "in00", 920010100);
        OPQOrchestrator run = mock(OPQOrchestrator.class);
        when(run.getSharedContext()).thenReturn(new OPQSharedContext());
        try (var generation = mockStatic(BotGeneration.class);
             var coordinator = mockStatic(OPQOrchestrator.class, call -> run);
             var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.readEimInt(member, "statusStg0", -1)).thenReturn(1);
            actions.when(() -> PqActions.partyLeader(member)).thenReturn(leader);
            OPQBot bot = new OPQBot(member);
            bot.setRunning(true);
            bot.setStateForDebug(state);
            bot.updateState();
            assertEquals(OPQBot.OPQBotState.STAGE_1_TRANSITION, bot.getOPQBotState());
            actions.verify(() -> PqActions.walkThroughPortal(member, exit));
        }
    }

    @Test
    void orbisSealedRoomGathersAtEakUntilTheLeaderReturnsToTheTower() {
        MapleMap sealed = room(OrbisPqData.STAGE_SEALED);
        place(member, sealed);
        place(leader, sealed);
        Portal exit = door(sealed, "st00", 999999999);
        OPQOrchestrator run = mock(OPQOrchestrator.class);
        when(run.getSharedContext()).thenReturn(new OPQSharedContext());
        try (var generation = mockStatic(BotGeneration.class);
             var coordinator = mockStatic(OPQOrchestrator.class, call -> run);
             var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.readEimInt(member, "statusStg4", -1)).thenReturn(1);
            actions.when(() -> PqActions.partyLeader(member)).thenReturn(leader);
            OPQBot bot = new OPQBot(member);
            bot.setRunning(true);

            bot.updateState();
            actions.verify(() -> PqActions.waitNearPuzzleNpc(member, OrbisPqData.EAK));
            actions.verify(() -> PqActions.walkThroughPortal(member, exit), never());

            place(leader, room(OrbisPqData.TOWER_MAP));
            bot.updateState();
            actions.verify(() -> PqActions.walkThroughPortal(member, exit));
            actions.verify(() -> PqActions.waitNearPuzzleNpc(member, OrbisPqData.EAK));
        }
    }

    private PartyQuestBot bot(String type) {
        return switch (type) {
            case "pirate" -> new PiratePQBot(member);
            case "ellin" -> new EllinPQBot(member);
            case "magatia" -> new MagatiaPQBot(member);
            case "dojang" -> new DojoPQBot(member);
            case "horntail" -> new HorntailPQBot(member);
            case "bossrush" -> new BossRushPQBot(member);
            default -> throw new AssertionError(type);
        };
    }

    private PartyQuestBot shell() {
        return new PartyQuestBot(member) {
            @Override
            protected boolean isInsideQuest(int id) {
                return id >= KERNING_1 && id <= KERNING_1 + 4;
            }

            @Override
            protected int lobbyMapId() {
                return 103000000;
            }

            @Override
            protected boolean workStage() {
                stageTicks++;
                return false;
            }
        };
    }

    private MapleMap room(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        when(map.getEventInstance()).thenReturn(instance);
        return map;
    }

    private static void place(Character character, MapleMap map) {
        int id = map.getId();
        when(character.getMap()).thenReturn(map);
        when(character.getMapId()).thenReturn(id);
    }

    private static Portal door(MapleMap map, String name, int target) {
        Portal door = mock(Portal.class);
        when(door.getName()).thenReturn(name);
        when(door.getTargetMapId()).thenReturn(target);
        when(door.getPortalStatus()).thenReturn(true);
        when(map.getPortal(name)).thenReturn(door);
        when(map.getPortals()).thenReturn(List.of(door));
        return door;
    }
}
