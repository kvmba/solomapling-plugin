package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.server.partyquest.AriantColiseum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.Ariant.AriantPQBot;
import soloMapling.ArtificialPlayer.BotTypes.BossRush.BossRushPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Dojo.DojoPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Ellin.EllinPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Henesys.HenesysPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Horntail.HorntailPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPqData;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiStages;
import soloMapling.ArtificialPlayer.BotTypes.Magatia.MagatiaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQOrchestrator;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQSharedContext;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OrbisPqData;
import soloMapling.ArtificialPlayer.BotTypes.Papulatus.PapulatusPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Pirate.PiratePQBot;
import soloMapling.ArtificialPlayer.BotTypes.Pyramid.PyramidPQBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTickService;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        when(captain.getId()).thenReturn(123);
        when(captain.getPlayer()).thenReturn(leader);
        when(party.getLeader()).thenReturn(captain);
        when(party.getMembers()).thenReturn(List.of(captain));
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

    @Test
    void kerningWaitsForClotoThenTakesTheBonusAndTheExit() {
        MapleMap boss = room(KerningPqData.STAGE_5);
        MapleMap bonus = room(KerningPqData.BONUS_MAP);
        MapleMap exit = room(KerningPqData.EXIT_MAP);
        MapleMap town = room(KerningPqData.RECRUIT_MAP);
        place(member, boss);
        place(leader, boss);
        when(instance.isEventCleared()).thenReturn(true);
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.findHumanPartyRoom(eq(member), any())).thenCallRealMethod();
            actions.when(() -> PqActions.readEimString(member, "5stageclear")).thenReturn("true");
            actions.when(() -> PqActions.talkTo(member, KerningPqData.NPC_CLOTO, 0)).thenAnswer(call -> {
                place(member, bonus);
                return true;
            });
            actions.when(() -> PqActions.talkTo(member, KerningPqData.NPC_NELLA, 0))
                    .thenAnswer(call -> {
                        place(member, member.getMap() == bonus ? exit : town);
                        return true;
                    });
            PartyQuestBot bot = new KerningPQBot(member);

            bot.tickPartyQuest();
            assertSame(boss, member.getMap());
            actions.verify(() -> PqActions.waitNearStageNpc(member, KerningPqData.NPC_CLOTO));
            movement.verify(() -> GCMovement.changeMap(any(), any(), any()), never());

            place(leader, bonus);
            bot.tickPartyQuest();
            assertSame(bonus, member.getMap());
            bot.tickPartyQuest();
            actions.verify(() -> PqActions.seekAndAttack(member));

            place(leader, exit);
            when(exit.getEventInstance()).thenReturn(null); // Nella removed the player from the EIM
            bot.tickPartyQuest();
            assertSame(exit, member.getMap());
            bot.tickPartyQuest();
            actions.verify(() -> PqActions.waitNearStageNpc(member, KerningPqData.NPC_NELLA));
            assertSame(exit, member.getMap());

            place(leader, town);
            bot.tickPartyQuest();
            assertSame(town, member.getMap());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "henesys,910010100,1012112,100000200",
            "henesys,910010400,1012112,100000200",
            "pirate,925100600,2094001,251010404",
            "magatia,926110600,2112018,261000021",
            "magatia,926100600,2112018,261000011",
            "horntail,240050400,2083002,240050000",
            "papulatus,220080001,2041025,220080000",
            "pyramid,926010001,2103013,926010000",
            "bossrush,970030001,9000037,970030000"
    })
    void eachBotGathersAtItsOwnJudgeUntilTheLastTeammateLeaves(
            String type, int rewardMap, int npc, int lobbyMap) {
        MapleMap reward = room(rewardMap);
        MapleMap lobby = room(lobbyMap);
        when(lobby.getEventInstance()).thenReturn(null);
        when(member.getEventInstance()).thenReturn(instance);
        when(instance.isEventCleared()).thenReturn(true);
        Character teammate = mock(Character.class);
        when(teammate.getId()).thenReturn(124);
        PartyCharacter second = mock(PartyCharacter.class);
        when(second.getId()).thenReturn(124);
        when(second.getPlayer()).thenReturn(teammate);
        PartyCharacter captain = party.getLeader();
        when(party.getMembers()).thenReturn(List.of(captain, second));
        place(leader, lobby);
        place(teammate, reward);
        place(member, lobby); // Magatia remembers the side from which this bot was recruited

        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var warps = mockStatic(WarpCommands.class);
             var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.findHumanPartyRoom(eq(member), any())).thenCallRealMethod();
            warps.when(() -> WarpCommands.mapForBot(member, lobbyMap)).thenReturn(lobby);
            PartyQuestBot bot = bot(type);
            place(member, reward);

            bot.tickPartyQuest();
            actions.verify(() -> PqActions.waitNearStageNpc(member, npc));
            movement.verify(() -> GCMovement.changeMap(any(), any(), any()), never());

            MapleMap otherInstance = room(rewardMap);
            when(otherInstance.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
            place(teammate, otherInstance);
            bot.tickPartyQuest();
            movement.verify(() -> GCMovement.changeMap(eq(member), same(lobby), any()));
        }
    }

    @Test
    void orbisBreaksTheTreasureBoxWithoutWarpingOutOfTheSameRoom() {
        MapleMap treasure = room(OrbisPqData.TREASURE_MAP);
        place(member, treasure);
        place(leader, treasure);
        when(member.getPosition()).thenReturn(new Point(0, 0));
        when(instance.isEventCleared()).thenReturn(true);
        OPQOrchestrator run = mock(OPQOrchestrator.class);
        when(run.getSharedContext()).thenReturn(new OPQSharedContext());
        try (var generation = mockStatic(BotGeneration.class);
             var coordinator = mockStatic(OPQOrchestrator.class, call -> run);
             var movement = mockStatic(GCMovement.class);
             var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.findHumanPartyRoom(eq(member), any())).thenCallRealMethod();
            actions.when(() -> PqActions.findReactorOid(member, OrbisPqData.TREASURE_BOX)).thenReturn(42);
            OPQBot bot = new OPQBot(member);
            bot.setRunning(true);

            bot.updateState();
            actions.verify(() -> PqActions.hitReactor(member, 42));
            movement.verify(() -> GCMovement.changeMap(any(), any(), any()), never());

            MapleMap prize = room(OrbisPqData.REWARD_MAP);
            // The leader has left; a different human from the same run is still settling.
            place(leader, room(OrbisPqData.LOBBY_MAP));
            Character teammate = mock(Character.class);
            when(teammate.getId()).thenReturn(124);
            place(teammate, prize);
            PartyCharacter remaining = mock(PartyCharacter.class);
            when(remaining.getId()).thenReturn(124);
            when(remaining.getPlayer()).thenReturn(teammate);
            PartyCharacter captain = party.getLeader();
            when(party.getMembers()).thenReturn(List.of(captain, remaining));
            bot.updateState();
            movement.verify(() -> GCMovement.changeMap(member, prize, null));

            place(member, prize);
            bot.updateState();
            actions.verify(() -> PqActions.waitNearStageNpc(member, OrbisPqData.MINERVA));
        }
    }

    @Test
    void ariantStaysForItsOwnMatchesResultWithoutAParty() {
        MapleMap result = room(980010010);
        MapleMap lobby = room(980010000);
        place(member, result);
        place(leader, result);
        when(member.getParty()).thenReturn(null);
        when(result.getAllPlayers()).thenReturn(List.of(member, leader));
        AriantColiseum match = mock(AriantColiseum.class);
        when(member.getAriantColiseum()).thenReturn(match);
        when(leader.getAriantColiseum()).thenReturn(match);
        try (var generation = mockStatic(BotGeneration.class);
             var warps = mockStatic(WarpCommands.class);
             var movement = mockStatic(GCMovement.class);
             var actions = mockStatic(PqActions.class)) {
            warps.when(() -> WarpCommands.mapForBot(member, 980010000)).thenReturn(lobby);
            PartyQuestBot bot = new AriantPQBot(member);
            bot.tickPartyQuest();
            actions.verify(() -> PqActions.waitNearStageNpc(member, 2101016));
            movement.verify(() -> GCMovement.changeMap(any(), any(), any()), never());

            when(leader.getAriantColiseum()).thenReturn(mock(AriantColiseum.class));
            bot.tickPartyQuest();
            movement.verify(() -> GCMovement.changeMap(eq(member), same(lobby), any()));
        }
    }

    @Test
    void magatiaWalksTheAssignedLaneInsteadOfWaitingForTheLeaderToClearIt() {
        MapleMap assembly = room(926110300);
        MapleMap lane = room(926110302);
        place(member, assembly);
        place(leader, room(926110401));
        var entrance = door(assembly, "pt01", 999999999);
        Portal rung = door(lane, "pt02", 999999999);
        when(rung.getPosition()).thenReturn(new Point(300, -52));
        when(member.getPosition()).thenReturn(new Point(0, 120));
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.readEimInt(member, "statusStg5", 0)).thenReturn(1);
            actions.when(() -> PqActions.readEimString(member, "stage6_comb2")).thenReturn("2000000000");
            actions.when(() -> PqActions.walkThroughPortal(member, entrance)).thenAnswer(call -> {
                place(member, lane);
                return null;
            });
            PartyQuestBot bot = new MagatiaPQBot(member);
            bot.tickPartyQuest();
            assertSame(lane, member.getMap());
            bot.tickPartyQuest();
            actions.verify(() -> PqActions.walkThroughPortal(member, rung));
        }
    }

    @Test
    void ellinFeedsTheFountainInsteadOfHittingAnItemTriggeredSpine() {
        MapleMap forest = room(930000200);
        place(member, forest);
        place(leader, forest);
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.findReactorOid(member, 3009000)).thenReturn(7);
            actions.when(() -> PqActions.findReactorOid(member, 3002000)).thenReturn(11);
            actions.when(() -> PqActions.feedReactor(member, 11, 4001161)).thenReturn(true);

            new EllinPQBot(member).tickPartyQuest();
            actions.verify(() -> PqActions.feedReactor(member, 7, 4001162));
            actions.verify(() -> PqActions.feedReactor(member, 11, 4001161));
            actions.verify(() -> PqActions.hitReactor(eq(member), anyInt()), never());
        }
    }

    @Test
    void ellinRetriesTheMazeDialogueUntilThePartyActuallyMoves() {
        MapleMap maze = room(930000300);
        MapleMap frogs = room(930000400);
        place(member, maze);
        place(leader, maze);
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.inInstance(member)).thenReturn(true);
            actions.when(() -> PqActions.talkTo(member, 2133001, 0)).thenReturn(true).thenAnswer(call -> {
                place(member, frogs);
                return true;
            });
            PartyQuestBot bot = new EllinPQBot(member);
            bot.tickPartyQuest();
            assertSame(maze, member.getMap());
            bot.tickPartyQuest();
            assertSame(frogs, member.getMap());
            actions.verify(() -> PqActions.talkTo(member, 2133001, 0), times(2));
        }
    }

    @Test
    void ellinWalksTheCurrentMazeCellBeforeTalkingToTheNpc() {
        MapleMap maze = room(930000300);
        Portal start = door(maze, "01st", 930000300);
        Portal end = door(maze, "16st", 930000300);
        Portal path = door(maze, "001E", 999999999);
        when(start.getPosition()).thenReturn(new Point(0, -6));
        when(end.getPosition()).thenReturn(new Point(1471, 1754));
        when(path.getScriptName()).thenReturn("party6_stage501");
        when(maze.getPortals()).thenReturn(List.of(start, end, path));
        when(member.getPosition()).thenReturn(new Point(-159, 150));
        place(member, maze);
        place(leader, maze);
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var actions = mockStatic(PqActions.class)) {
            movement.when(() -> GCMovement.portalApproachPoint(maze, path)).thenReturn(new Point(182, -7));
            movement.when(() -> GCMovement.canReachOnFoot(member, 182, -7)).thenReturn(true);
            new EllinPQBot(member).tickPartyQuest();
            actions.verify(() -> PqActions.walkThroughPortal(member, path));
            actions.verify(() -> PqActions.talkTo(member, 2133001, 0), never());
        }
    }

    @Test
    void ludiGuardRoomNeverUsesTheCombatSweepEvenBeforeAGuardIsInRange() {
        place(member, room(LudiPqData.STAGE5_ROOM_LAST));
        when(member.getPosition()).thenReturn(new Point(0, 0));
        try (var generation = mockStatic(BotGeneration.class)) {
            PartyQuestBot bot = new LudiPQBot(member);
            assertFalse(bot.fightsOnSweep());
        }
    }

    @Test
    void amoriaBreaksTheOrdinaryBonusBoxesWithoutLookingForBossLoot() {
        MapleMap bonus = room(670010800);
        place(member, bonus);
        place(leader, bonus);
        Reactor box = mock(Reactor.class);
        when(box.getId()).thenReturn(6702003);
        when(box.getObjectId()).thenReturn(33);
        when(box.isActive()).thenReturn(true);
        when(bonus.getAllReactors()).thenReturn(List.of(box));
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var actions = mockStatic(PqActions.class)) {
            new soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPQBot(member).tickPartyQuest();
            actions.verify(() -> PqActions.approachReactor(member, 33, false, true));
            actions.verify(() -> PqActions.collectForLeader(eq(member), any(int[].class), anyInt(), anyInt()), never());
        }
    }

    @Test
    void theLastBotClosesTheInstanceButEarlierExitsDoNot() {
        Character anotherBot = mock(Character.class);
        when(member.getEventInstance()).thenReturn(instance);
        when(anotherBot.getEventInstance()).thenReturn(instance);
        AtomicInteger remaining = new AtomicInteger(2);
        when(instance.getPlayerCount()).thenAnswer(call -> remaining.get());
        doAnswer(call -> {
            Character leaving = call.getArgument(0);
            when(leaving.getEventInstance()).thenReturn(null);
            remaining.decrementAndGet();
            return null;
        }).when(instance).unregisterPlayer(any());

        PqActions.leaveEventInstance(member);
        verify(instance, never()).dispose(false);

        PqActions.leaveEventInstance(anotherBot);
        PqActions.leaveEventInstance(anotherBot);
        var order = inOrder(instance);
        order.verify(instance).unregisterPlayer(member);
        order.verify(instance).unregisterPlayer(anotherBot);
        order.verify(instance).dispose(false);
        verify(instance, times(1)).dispose(false);
    }

    @Test
    void aBotInAnInstancedCopyOfItsLobbyMovesToThePublicMap() {
        MapleMap copied = room(KerningPqData.RECRUIT_MAP);
        MapleMap lobby = mock(MapleMap.class);
        when(lobby.getId()).thenReturn(KerningPqData.RECRUIT_MAP);
        place(member, copied);
        when(member.getEventInstance()).thenReturn(instance);
        doAnswer(call -> {
            when(member.getEventInstance()).thenReturn(null);
            return null;
        }).when(instance).unregisterPlayer(member);
        try (var generation = mockStatic(BotGeneration.class);
             var movement = mockStatic(GCMovement.class);
             var warps = mockStatic(WarpCommands.class)) {
            warps.when(() -> WarpCommands.mapForBot(member, KerningPqData.RECRUIT_MAP)).thenReturn(lobby);
            movement.when(() -> GCMovement.changeMap(member, lobby, null)).thenAnswer(call -> {
                assertNull(member.getEventInstance(), "detach before the actual warp resolves its destination");
                place(member, lobby);
                return null;
            });

            new KerningPQBot(member).tickPartyQuest();

            assertSame(lobby, member.getMap());
            verify(instance).dispose(false);
        }
    }

    @Test
    void orbisDoesNotUnregisterTheNextRunWhileWaitingForItsEntryWarp() {
        MapleMap lobby = mock(MapleMap.class);
        when(lobby.getId()).thenReturn(OrbisPqData.LOBBY_MAP);
        place(member, lobby);
        when(member.getEventInstance()).thenReturn(instance);
        var orchestrator = mock(OPQOrchestrator.class);
        when(orchestrator.getSharedContext()).thenReturn(new OPQSharedContext());
        try (var generation = mockStatic(BotGeneration.class);
             var coordinator = mockStatic(OPQOrchestrator.class, call -> orchestrator)) {
            OPQBot bot = new OPQBot(member);
            bot.setRunning(true);
            bot.setStateForDebug(OPQBot.OPQBotState.EXIT_DETECT);

            bot.updateState();

            assertEquals(OPQBot.OPQBotState.IN_PARTY_IDLE, bot.getOPQBotState());
            verify(instance, never()).unregisterPlayer(member);
        }
    }

    @Test
    void aBotLeavingDoesNotEraseTheRoomsPuzzleLedger() {
        MapleMap puzzle = room(KERNING_1 + 1);
        MapleMap otherCopy = room(puzzle.getId());
        place(member, puzzle);
        var ledger = PositionCombination.ledgerFor(puzzle);
        ledger.tried.add(1L);

        shell().releaseRoomState();

        assertSame(ledger, PositionCombination.ledgerFor(puzzle));
        assertTrue(ledger.tried.contains(1L));
        assertNotSame(ledger, PositionCombination.ledgerFor(otherCopy));
        assertTrue(PositionCombination.ledgerFor(otherCopy).tried.isEmpty());
    }

    @Test
    void aLeaderInAnotherCopyCannotJudgeThisRoomsPuzzle() {
        MapleMap puzzle = room(KERNING_1 + 1);
        place(member, puzzle);
        place(leader, room(puzzle.getId()));
        var rooms = List.of(new java.awt.Rectangle(0, 0, 10, 10));
        var candidates = List.of(new PositionCombination.Candidate(1L, List.of(new Point(2, 2))));
        try (var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.partyLeader(member)).thenReturn(leader);
            PositionCombination.decide(member, candidates, rooms, 2, "stg2Property", 1, null);
            actions.verify(() -> PqActions.observedPattern(member, rooms), never());
        }
    }

    private PartyQuestBot bot(String type) {
        return switch (type) {
            case "henesys" -> new HenesysPQBot(member);
            case "pirate" -> new PiratePQBot(member);
            case "ellin" -> new EllinPQBot(member);
            case "magatia" -> new MagatiaPQBot(member);
            case "dojang" -> new DojoPQBot(member);
            case "horntail" -> new HorntailPQBot(member);
            case "bossrush" -> new BossRushPQBot(member);
            case "papulatus" -> new PapulatusPQBot(member);
            case "pyramid" -> new PyramidPQBot(member);
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
