package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.constants.game.CharacterStance;
import org.gms.constants.id.MapId;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.property.ServiceProperty;
import org.gms.server.expeditions.Expedition;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands;
import soloMapling.ArtificialPlayer.BotTypes.Ariant.AriantExpedition;
import soloMapling.ArtificialPlayer.BotTypes.Carnival.CarnivalArena;
import soloMapling.ArtificialPlayer.BotTypes.Carnival.CarnivalPQBot;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;

import java.awt.Point;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PqMapEntryExecutionTest {
    private static final int LOBBY_ID = 980000100;
    private final List<Avatar> avatars = new ArrayList<>();
    private MapleMap hall;
    private MapleMap lobby;
    private Channel channel;
    private List<Portal> doors;
    private Map<Integer, BotMovementState> states;
    private MockedStatic<BotNavigationGraphProvider> graphs;

    @BeforeAll
    static void stubHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configs = mock(ConfigService.class);
        when(configs.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(context.getBean(ConfigService.class)).thenReturn(configs);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(call -> call.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, MessageSource.class)).thenReturn(messages);
        }
        Method setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
        GameConfigDO interval = new GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MovementClock.useVirtual(1_000_000L);
        var field = GCMovement.class.getDeclaredField("STATES");
        field.setAccessible(true);
        states = (Map<Integer, BotMovementState>) field.get(null);
        graphs = mockStatic(BotNavigationGraphProvider.class);
        hall = mock(MapleMap.class);
        when(hall.getId()).thenReturn(980000000);
        lobby = mock(MapleMap.class);
        when(lobby.getId()).thenReturn(LOBBY_ID);
        FootholdTree terrain = new FootholdTree(new Point(-500, -500), new Point(500, 500));
        terrain.insert(new Foothold(new Point(300, -173), new Point(380, -173), 24));
        terrain.insert(new Foothold(new Point(100, -48), new Point(200, -48), 10));
        terrain.insert(new Foothold(new Point(-50, 132), new Point(50, 132), 7));
        when(lobby.getFootholds()).thenReturn(terrain);
        doors = List.of(door(0, 337, -258), door(1, 164, -136), door(2, 13, 26));
        when(lobby.getPortals()).thenReturn(doors);
        when(lobby.getPortal(0)).thenReturn(doors.getFirst());
        channel = mock(Channel.class, RETURNS_DEEP_STUBS);
        when(channel.getMapFactory().getMap(LOBBY_ID)).thenReturn(lobby);
        ObserverTracker.observe(Set.of(lobby), Set.of(), MovementClock.nowMs());
    }

    @AfterEach
    void cleanUp() {
        for (Avatar avatar : avatars) {
            states.remove(avatar.bot.getId());
        }
        graphs.close();
        ObserverTracker.observe(Set.of(), Set.of(), Long.MAX_VALUE - 10_000L);
        MovementClock.reset();
    }

    @Test
    void challengeEntryArmsTheFirstFrameAndTheFloatWithoutWaitingForATick() throws Throwable {
        Avatar right = avatar(CharacterStance.WALK_RIGHT_STANCE, true);
        Avatar left = avatar(CharacterStance.WALK_LEFT_STANCE, true);
        Avatar standing = avatar(CharacterStance.STAND_RIGHT_STANCE, true);

        moveTeam(right, left, standing);
        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);

        for (int i = 0; i < avatars.size(); i++) {
            Avatar avatar = avatars.get(i);
            assertEquals(i == 1 ? CharacterStance.JUMP_LEFT_STANCE : CharacterStance.JUMP_RIGHT_STANCE,
                    avatar.entryStance, "the host must read an airborne pose while building SPAWN_PLAYER");
            assertTrue(avatar.movementLocked, "the warp must not race the physics tick");
            assertSame(doors.get(i), avatar.arrivalDoor, "the team must retain its spread across spawn doors");
            Point raised = GCMovement.entrySpawnPoint(doors.get(i).getPosition());
            assertEquals(raised, avatar.bot.getPosition());
            assertSame(lobby, avatar.state.lastMap, "arrival must be initialized before the next scheduled tick");
            assertNull(avatar.state.moveTarget, "the previous map's move must not survive the warp");
            assertTrue(avatar.state.portalDropAtMs >= MovementClock.nowMs() + 1500);
            assertTrue(avatar.state.portalDropAtMs <= MovementClock.nowMs() + 2100);
            assertTrue(CharacterStance.isJumping(avatar.bot.getStance()));
            assertEquals(0, BotMovementManager.resolveWireFh(false,
                    BotPhysicsEngine.isAirbornePose(avatar.state), false, 0, 24));

            tick.invoke(null, avatar.state);
            assertEquals(raised, avatar.bot.getPosition(), "the first tick must not raise the bot a second time");
        }
    }

    @Test
    void followingTheLeaderIntoTheLobbyUsesTheSameAirborneArrival() throws Exception {
        Avatar avatar = avatar(CharacterStance.WALK_LEFT_STANCE, true);
        Character leader = mock(Character.class);
        when(leader.getMapId()).thenReturn(LOBBY_ID);
        PartyCharacter leaderEntry = mock(PartyCharacter.class);
        when(leaderEntry.getPlayer()).thenReturn(leader);
        Party party = mock(Party.class);
        when(party.getLeader()).thenReturn(leaderEntry);
        when(avatar.bot.getParty()).thenReturn(party);
        CarnivalPQBot brain = mock(CarnivalPQBot.class, CALLS_REAL_METHODS);
        doReturn(avatar.bot).when(brain).getChr();

        try (MockedStatic<WarpCommands> warps = mockStatic(WarpCommands.class)) {
            warps.when(() -> WarpCommands.mapForBot(avatar.bot, LOBBY_ID)).thenReturn(lobby);
            Method follow = CarnivalPQBot.class.getDeclaredMethod("followLeaderByWarp");
            follow.setAccessible(true);
            assertEquals(Boolean.TRUE, follow.invoke(brain));
        }

        Portal expectedDoor = doors.get(Math.floorMod(avatar.bot.getId(), doors.size()));
        assertSame(expectedDoor, avatar.arrivalDoor);
        assertEquals(CharacterStance.JUMP_LEFT_STANCE, avatar.entryStance);
        assertTrue(avatar.movementLocked);
        assertEquals(GCMovement.entrySpawnPoint(expectedDoor.getPosition()), avatar.bot.getPosition());
        assertSame(lobby, avatar.state.lastMap);
    }

    @Test
    void twoAlreadyAdmittedWarpsDoNotReenterTheSameMap() throws Exception {
        Avatar avatar = avatar(CharacterStance.WALK_RIGHT_STANCE, true);
        GCMovement.changeMap(avatar.bot, lobby, doors.get(0));
        Point raised = avatar.bot.getPosition();
        long dropAt = avatar.state.portalDropAtMs;

        GCMovement.changeMap(avatar.bot, lobby, doors.get(1));

        verify(avatar.bot, never()).changeMap(lobby, doors.get(1));
        assertEquals(raised, avatar.bot.getPosition());
        assertEquals(dropAt, avatar.state.portalDropAtMs);
        tick(avatar.state);
        assertEquals(raised, avatar.bot.getPosition());
    }

    @Test
    void aFailureAfterMapEntryStillInitializesTheArrival() {
        Avatar avatar = avatar(CharacterStance.WALK_RIGHT_STANCE, true);
        IllegalStateException failure = failAfterEntry(avatar);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> GCMovement.changeMap(avatar.bot, lobby, doors.getFirst())));

        assertSame(lobby, avatar.state.lastMap);
        assertEquals(GCMovement.entrySpawnPoint(doors.getFirst().getPosition()), avatar.bot.getPosition());
        assertTrue(avatar.state.portalDropAtMs > MovementClock.nowMs());
        assertTrue(CharacterStance.isJumping(avatar.bot.getStance()));
    }

    @Test
    void anArrivalFailureDoesNotHideTheOriginalWarpFailure() {
        Avatar avatar = avatar(CharacterStance.WALK_RIGHT_STANCE, true);
        IllegalStateException failure = failAfterEntry(avatar);
        IllegalStateException arrivalFailure = new IllegalStateException("graph warmup failed");
        graphs.when(() -> BotNavigationGraphProvider.warmGraphAsync(lobby, BotMovementProfile.base()))
                .thenThrow(arrivalFailure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> GCMovement.changeMap(avatar.bot, lobby, doors.getFirst())));
        assertArrayEquals(new Throwable[]{arrivalFailure}, failure.getSuppressed());
    }

    @Test
    void manualPlacementPreservesTheExactInstanceAndPublishesHpWithAnAirborneSpawn() throws Exception {
        Avatar avatar = avatar(CharacterStance.WALK_LEFT_STANCE, true);

        placeManually(avatar, doors.getFirst().getPosition());

        assertSame(lobby, avatar.bot.getMap());
        assertSame(lobby, avatar.state.lastMap);
        assertTrue(CharacterStance.isJumping(avatar.entryStance));
        assertTrue(avatar.movementLocked);
        assertEquals(GCMovement.entrySpawnPoint(doors.getFirst().getPosition()), avatar.bot.getPosition());
        verify(lobby).addPlayer(avatar.bot);
        verify(avatar.bot).updatePartyMemberHP();
        verify(avatar.bot, never()).changeMap(any(MapleMap.class), any(Portal.class));
    }

    @Test
    void replacingAManualSpawnAtTheSamePointStillInitializesArrival() throws Exception {
        Avatar avatar = avatar(CharacterStance.STAND_RIGHT_STANCE, true);
        Point point = doors.getFirst().getPosition();
        avatar.map.set(lobby);
        avatar.state.lastMap = lobby;
        avatar.state.moveTargetMap = lobby;
        avatar.position.set(new Point(point));

        placeManually(avatar, point);

        verify(lobby).removePlayer(avatar.bot);
        assertEquals(GCMovement.entrySpawnPoint(point), avatar.bot.getPosition());
        assertTrue(avatar.state.portalDropAtMs > MovementClock.nowMs());
        assertNull(avatar.state.moveTarget, "same-map replacement must discard the previous movement order");
    }

    @Test
    void hpPublicationFailureCannotUndoASamePointPlacement() {
        Avatar avatar = avatar(CharacterStance.STAND_RIGHT_STANCE, true);
        Point point = doors.getFirst().getPosition();
        avatar.map.set(lobby);
        avatar.state.lastMap = lobby;
        avatar.position.set(new Point(point));
        IllegalStateException failure = new IllegalStateException("party HP update failed");
        doThrow(failure).when(avatar.bot).updatePartyMemberHP();

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> placeManually(avatar, point));

        assertSame(failure, thrown.getCause());
        assertEquals(GCMovement.entrySpawnPoint(point), avatar.bot.getPosition());
        assertTrue(avatar.state.portalDropAtMs > MovementClock.nowMs());
    }

    @Test
    void aRecordedBotKeepsItsLegacyPoseAfterTheManualSpawnPacket() throws Exception {
        Avatar avatar = avatar(CharacterStance.STAND_RIGHT_STANCE, false);
        Point point = doors.getFirst().getPosition();

        placeManually(avatar, point);

        assertTrue(CharacterStance.isJumping(avatar.entryStance));
        assertEquals(CharacterStance.STAND_LEFT_STANCE, avatar.bot.getStance());
        assertEquals(point, avatar.bot.getPosition());
        assertFalse(GCMovement.isEnabled(avatar.bot));
    }

    @Test
    void returningFromAnyPartyQuestUsesTheProtectedArrival() throws Exception {
        Avatar avatar = avatar(CharacterStance.WALK_RIGHT_STANCE, true);
        PartyQuestBot brain = mock(PartyQuestBot.class, invocation -> switch (invocation.getMethod().getName()) {
            case "getChr" -> avatar.bot;
            case "lobbyMapId" -> LOBBY_ID;
            default -> invocation.callRealMethod();
        });
        try (MockedStatic<WarpCommands> warps = mockStatic(WarpCommands.class)) {
            warps.when(() -> WarpCommands.mapForBot(avatar.bot, LOBBY_ID)).thenReturn(lobby);
            Method leave = PartyQuestBot.class.getDeclaredMethod("returnToLobby", String.class);
            leave.setAccessible(true);
            leave.invoke(brain, "test finished");
        }
        assertTrue(CharacterStance.isJumping(avatar.entryStance));
        assertTrue(avatar.movementLocked);
        assertSame(lobby, avatar.state.lastMap);
    }

    @Test
    void ariantEnrolmentUsesTheProtectedArrival() throws Exception {
        Avatar avatar = avatar(CharacterStance.WALK_LEFT_STANCE, true);
        Expedition expedition = mock(Expedition.class);
        try (MockedStatic<WarpCommands> warps = mockStatic(WarpCommands.class)) {
            warps.when(() -> WarpCommands.mapForBot(avatar.bot, LOBBY_ID)).thenReturn(lobby);
            Method enrol = AriantExpedition.class.getDeclaredMethod(
                    "enrolOne", Character.class, Expedition.class, int.class);
            enrol.setAccessible(true);
            assertEquals(Boolean.TRUE, enrol.invoke(null, avatar.bot, expedition, LOBBY_ID));
        }
        assertTrue(CharacterStance.isJumping(avatar.entryStance));
        assertTrue(avatar.movementLocked);
        assertSame(lobby, avatar.state.lastMap);
    }

    @Test
    void carnivalRescueProtectsBotsWithoutChangingHumanEntryPoses() throws Exception {
        when(hall.isCPQMap()).thenReturn(true);
        Avatar bot = avatar(CharacterStance.WALK_LEFT_STANCE, true);
        Avatar human = avatar(CharacterStance.STAND_RIGHT_STANCE, false);
        when(human.bot.getId()).thenReturn(123);
        Method rescue = CarnivalArena.class.getDeclaredMethod(
                "rescuePlayersFromArena", Party.class, Channel.class, int.class, boolean.class);
        rescue.setAccessible(true);

        rescue.invoke(null, partyOf(bot, human), channel, 0, false);

        assertEquals(CharacterStance.JUMP_LEFT_STANCE, bot.entryStance);
        assertSame(lobby, bot.state.lastMap);
        assertEquals(CharacterStance.STAND_RIGHT_STANCE, human.entryStance);
        assertEquals(doors.getFirst().getPosition(), human.bot.getPosition());
    }

    @Test
    void carnivalEvacuationProtectsBotsWithoutChangingHumanEntryPoses() throws Exception {
        when(hall.isCPQMap()).thenReturn(true);
        Avatar bot = avatar(CharacterStance.WALK_RIGHT_STANCE, true);
        Avatar human = avatar(CharacterStance.STAND_LEFT_STANCE, false);
        when(human.bot.getId()).thenReturn(123);
        Method evacuate = CarnivalArena.class.getDeclaredMethod("evacuateFromArena", Party.class);
        evacuate.setAccessible(true);

        evacuate.invoke(null, partyOf(bot, human));

        assertEquals(CharacterStance.JUMP_RIGHT_STANCE, bot.entryStance);
        assertSame(lobby, bot.state.lastMap);
        assertEquals(CharacterStance.STAND_LEFT_STANCE, human.entryStance);
        assertEquals(doors.getFirst().getPosition(), human.bot.getPosition());
    }

    @Test
    void aRefusedWarpRestoresThePoseWithoutArmingAnArrival() throws Throwable {
        Avatar avatar = avatar(CharacterStance.WALK_LEFT_STANCE, true);
        doAnswer(call -> {
            avatar.entryStance = avatar.bot.getStance();
            return null;
        }).when(avatar.bot).changeMap(lobby, doors.getFirst());

        moveTeam(avatar);

        assertEquals(CharacterStance.JUMP_LEFT_STANCE, avatar.entryStance);
        assertEquals(CharacterStance.WALK_LEFT_STANCE, avatar.bot.getStance());
        assertSame(hall, avatar.state.lastMap);
        assertEquals(0L, avatar.state.portalDropAtMs);
        assertEquals(new Point(100, 0), avatar.state.moveTarget);
    }

    @Test
    void aFailedWarpRestoresThePoseAndPropagatesTheFailure() {
        Avatar avatar = avatar(CharacterStance.STAND_RIGHT_STANCE, true);
        IllegalStateException failure = new IllegalStateException("warp failed");
        doAnswer(call -> {
            avatar.entryStance = avatar.bot.getStance();
            throw failure;
        }).when(avatar.bot).changeMap(lobby, doors.getFirst());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> moveTeam(avatar)));
        assertEquals(CharacterStance.JUMP_RIGHT_STANCE, avatar.entryStance);
        assertEquals(CharacterStance.STAND_RIGHT_STANCE, avatar.bot.getStance());
        assertEquals(0L, avatar.state.portalDropAtMs);
    }

    @Test
    void aBotWithoutADriverDoesNotKeepTheTemporaryEntryPose() throws Throwable {
        Avatar avatar = avatar(CharacterStance.STAND_RIGHT_STANCE, false);

        moveTeam(avatar);

        assertEquals(CharacterStance.JUMP_RIGHT_STANCE, avatar.entryStance);
        assertEquals(CharacterStance.STAND_RIGHT_STANCE, avatar.bot.getStance());
        assertEquals(doors.getFirst().getPosition(), avatar.bot.getPosition());
        assertFalse(GCMovement.isEnabled(avatar.bot));
    }

    @Test
    void aTeamMemberAlreadyInTheLobbyDoesNotWarpAgain() throws Throwable {
        Avatar avatar = avatar(CharacterStance.STAND_RIGHT_STANCE, true);
        avatar.map.set(lobby);

        moveTeam(avatar);

        verify(avatar.bot, never()).changeMap(any(MapleMap.class), any(Portal.class));
        assertEquals(CharacterStance.STAND_RIGHT_STANCE, avatar.bot.getStance());
    }

    private Portal door(int id, int x, int y) {
        Portal door = mock(Portal.class);
        when(door.getId()).thenReturn(id);
        when(door.getType()).thenReturn(0);
        when(door.getTargetMapId()).thenReturn(MapId.NONE);
        when(door.getPosition()).thenReturn(new Point(x, y));
        return door;
    }

    private Avatar avatar(int stance, boolean dynamic) {
        Avatar avatar = new Avatar(990_700 + avatars.size(), stance, dynamic);
        avatars.add(avatar);
        return avatar;
    }

    private void moveTeam(Avatar... members) throws Throwable {
        Method move = CarnivalArena.class.getDeclaredMethod("moveTeamTo", Party.class, Channel.class, int.class);
        move.setAccessible(true);
        try {
            move.invoke(null, partyOf(members), channel, LOBBY_ID);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private Party partyOf(Avatar... members) {
        List<PartyCharacter> roster = new ArrayList<>();
        for (Avatar member : members) {
            PartyCharacter entry = mock(PartyCharacter.class);
            when(entry.getPlayer()).thenReturn(member.bot);
            roster.add(entry);
        }
        Party team = mock(Party.class);
        when(team.getPartyMembers()).thenReturn(roster);
        return team;
    }

    private IllegalStateException failAfterEntry(Avatar avatar) {
        IllegalStateException failure = new IllegalStateException("party update failed after map entry");
        doAnswer(call -> {
            avatar.arrive(doors.getFirst());
            throw failure;
        }).when(avatar.bot).changeMap(lobby, doors.getFirst());
        return failure;
    }

    private void placeManually(Avatar avatar, Point point) throws Exception {
        doAnswer(call -> {
            avatar.map.set(call.getArgument(0));
            return null;
        }).when(avatar.bot).setMap(any(MapleMap.class));
        doAnswer(call -> {
            avatar.recordEntry();
            return null;
        }).when(lobby).addPlayer(avatar.bot);
        Method place = BotGeneration.class.getDeclaredMethod(
                "placeBotOnMap", Character.class, Point.class, MapleMap.class);
        place.setAccessible(true);
        place.invoke(null, avatar.bot, point, lobby);
    }

    private void tick(BotMovementState state) throws Exception {
        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
        tick.invoke(null, state);
    }

    private final class Avatar {
        final Character bot = mock(Character.class);
        final AtomicReference<MapleMap> map = new AtomicReference<>(hall);
        final AtomicReference<Point> position = new AtomicReference<>(new Point(0, 0));
        final AtomicInteger stance;
        final BotMovementState state;
        int entryStance;
        boolean movementLocked;
        Portal arrivalDoor;

        Avatar(int id, int initialStance, boolean dynamic) {
            stance = new AtomicInteger(initialStance);
            when(bot.getId()).thenReturn(id);
            when(bot.getMap()).thenAnswer(call -> map.get());
            when(bot.getMapId()).thenAnswer(call -> map.get().getId());
            when(bot.getPosition()).thenAnswer(call -> new Point(position.get()));
            doAnswer(call -> {
                position.set(new Point((Point) call.getArgument(0)));
                return null;
            }).when(bot).setPosition(any(Point.class));
            when(bot.getStance()).thenAnswer(call -> stance.get());
            doAnswer(call -> {
                stance.set(call.getArgument(0));
                return null;
            }).when(bot).setStance(anyInt());
            when(bot.isFacingLeft()).thenAnswer(call -> (stance.get() & 1) != 0);
            when(bot.getHp()).thenReturn(5000);
            state = new BotMovementState(bot, null);
            state.movementProfile = BotMovementProfile.base();
            state.lastMap = hall;
            state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
            state.moveTarget = new Point(100, 0);
            state.moveTargetMap = hall;
            if (dynamic) states.put(id, state);
            doAnswer(call -> {
                arrive(call.getArgument(1));
                return null;
            }).when(bot).changeMap(eq(lobby), any(Portal.class));
            doAnswer(call -> {
                arrive(doors.getFirst());
                return null;
            }).when(bot).changeMap(anyInt());
        }

        void recordEntry() {
            entryStance = bot.getStance();
            movementLocked = Thread.holdsLock(state);
        }

        void arrive(Portal portal) {
            recordEntry();
            arrivalDoor = portal;
            map.set(lobby);
            bot.setPosition(portal.getPosition());
        }
    }
}
