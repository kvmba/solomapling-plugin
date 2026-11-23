package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.extension.api.HostRuntime;
import org.gms.extension.event.PartyQuestStageClearedEvent;
import org.gms.extension.event.PartyQuestStageWrongEvent;
import org.gms.extension.runtime.InMemoryHostEventBus;
import org.gms.manager.ServerManager;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.property.ServiceProperty;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotDeath;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OrbisPqData;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.Environment.BotMessages;
import soloMapling.plugin.HostGameplayEventBridge;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PqRearguardTest {

    private static final Point FIRST = new Point(5, 5);
    private static final Point SECOND = new Point(25, 5);
    private static final Point THIRD = new Point(45, 5);
    private static final List<Rectangle> AREAS = List.of(
            new Rectangle(0, 0, 10, 10), new Rectangle(20, 0, 10, 10), new Rectangle(40, 0, 10, 10));
    private static final List<PositionCombination.Candidate> CANDIDATES = List.of(
            new PositionCombination.Candidate(PositionCombination.maskToPattern(0b110), List.of(SECOND, THIRD)),
            new PositionCombination.Candidate(PositionCombination.maskToPattern(0b101), List.of(FIRST, THIRD)));

    @BeforeAll
    static void bootHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, org.springframework.context.MessageSource.class)).thenReturn(messages);
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

    private final List<Integer> registered = new ArrayList<>();
    private final List<PartyCharacter> members = new ArrayList<>();
    private InMemoryHostEventBus events;
    private EventInstanceManager instance;
    private MapleMap room;
    private Character leader;
    private Party party;
    private MockedStatic<SocialCommands> speech;
    private String line;

    @BeforeEach
    void openQuest() throws Exception {
        speech = mockStatic(SocialCommands.class);
        events = new InMemoryHostEventBus();
        HostRuntime runtime = mock(HostRuntime.class);
        when(runtime.events()).thenReturn(events);
        var field = HostGameplayEventBridge.class.getDeclaredField("REGISTERED");
        field.setAccessible(true);
        AtomicBoolean registration = (AtomicBoolean) field.get(null);
        boolean wasRegistered = registration.getAndSet(false);
        try {
            HostGameplayEventBridge.register(runtime);
        } finally {
            registration.set(wasRegistered);
        }
        room = room(922010100);
        party = mock(Party.class);
        when(party.getMembers()).thenReturn(members);
        leader = character(1, 200, room);
        PartyCharacter leaderMember = mock(PartyCharacter.class);
        when(leaderMember.getId()).thenReturn(1);
        when(leaderMember.getPlayer()).thenReturn(leader);
        when(party.getLeader()).thenReturn(leaderMember);
        members.add(leaderMember);
        instance = instance();
        line = BotMessages.get("pq.rearguard");
        assertNotEquals("pq.rearguard", line);
    }

    @AfterEach
    void releaseBots() {
        speech.close();
        for (int id : registered) {
            CharacterStorage.removeActiveBot(id);
            PqActions.clearSeekState(id);
        }
    }

    @Test
    void theClearEventSpeaksBeforeAnyBotTickOrLeaderDeparture() {
        addBot(30, 30, room);
        Bot highest = addBot(50, 50, room);
        addBot(40, 40, room);

        clear();

        speech.verify(() -> SocialCommands.BotSpeak(highest.character(), line));
        speech.verifyNoMoreInteractions();
    }

    @Test
    void equalLevelsChooseTheLowerCharacterIdRegardlessOfRosterOrder() {
        addBot(42, 50, room);
        Bot chosen = addBot(40, 50, room);

        clear();

        speech.verify(() -> SocialCommands.BotSpeak(chosen.character(), line));
        speech.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dead", "paused", "stopped"})
    void anUnavailableBotDoesNotSilenceTheRoom(String state) {
        Bot unavailable = addBot(50, 70, room);
        Bot chosen = addBot(40, 50, room);
        switch (state) {
            case "dead" -> when(unavailable.death().isCorpse()).thenReturn(true);
            case "paused" -> doReturn(BotSM.BotState.PAUSE).when(unavailable.bot()).getState();
            case "stopped" -> doReturn(false).when(unavailable.bot()).getRunning();
        }

        clear();

        speech.verify(() -> SocialCommands.BotSpeak(chosen.character(), line));
        speech.verifyNoMoreInteractions();
    }

    @Test
    void anotherInstanceWithTheSameMapIdCannotTakeTheVoice() {
        addBot(50, 70, room(room.getId()));
        Bot chosen = addBot(40, 50, room);

        clear();

        speech.verify(() -> SocialCommands.BotSpeak(chosen.character(), line));
        speech.verifyNoMoreInteractions();
    }

    @Test
    void aNonQuestBotCannotTakeTheVoice() {
        register(60, mock(BotSM.class));
        Bot chosen = addBot(40, 50, room);

        clear();

        speech.verify(() -> SocialCommands.BotSpeak(chosen.character(), line));
        speech.verifyNoMoreInteractions();
    }

    @Test
    void duplicateClearEffectsSpeakOnlyOnceButTheNextStageStillSpeaks() {
        Bot chosen = addBot(40, 50, room);
        clear();
        clear();
        room = room(922010200);
        when(leader.getMap()).thenReturn(room);
        when(chosen.character().getMap()).thenReturn(room);
        clear();
        clear();

        speech.verify(() -> SocialCommands.BotSpeak(chosen.character(), line), times(2));
        speech.verifyNoMoreInteractions();
    }

    @Test
    void aNewRunCanSpeakAgainOnTheSameMap() {
        Bot chosen = addBot(40, 50, room);
        clear();
        instance = instance();
        clear();

        speech.verify(() -> SocialCommands.BotSpeak(chosen.character(), line), times(2));
        speech.verifyNoMoreInteractions();
    }

    @Test
    void anAbsentLeaderDoesNotConsumeTheRoomsAnnouncement() {
        Bot chosen = addBot(40, 50, room);
        doReturn(room(922010200)).when(leader).getMap();
        clear();
        speech.verifyNoInteractions();

        when(leader.getMap()).thenReturn(room);
        clear();
        speech.verify(() -> SocialCommands.BotSpeak(chosen.character(), line));
    }

    @Test
    void anEmptyRoomDoesNotConsumeTheRoomsAnnouncement() {
        clear();
        speech.verifyNoInteractions();

        Bot chosen = addBot(40, 50, room);
        clear();
        speech.verify(() -> SocialCommands.BotSpeak(chosen.character(), line));
    }

    @Test
    void orbisUsesTheSameClearEventAsTheOtherQuests() {
        room = room(OrbisPqData.STAGE_SEALED);
        when(leader.getMap()).thenReturn(room);
        Character character = character(50, 70, room);
        OPQBot bot = mock(OPQBot.class);
        when(bot.getChr()).thenReturn(character);
        when(bot.getRunning()).thenReturn(true);
        when(bot.death()).thenReturn(mock(BotDeath.class));
        register(50, bot);

        clear();
        clear();
        speech.verify(() -> SocialCommands.BotSpeak(character, line));
        speech.verifyNoMoreInteractions();
    }

    @Test
    void thePuzzleLineNamesTheActualMissingSpotAndSurvivesSpeakerHandover() {
        Bot high = addBot(55, 70, room);
        Bot low = addBot(50, 50, room);
        var candidate = new PositionCombination.Candidate(
                PositionCombination.maskToPattern(0b111), List.of(FIRST, SECOND, THIRD));
        var ledger = PositionCombination.ledgerFor(room);
        var plan = PositionCombination.decide(ledger, high.character(), List.of(candidate), AREAS, null, 2, null);
        String expected = BotMessages.get("pq.position_plan.fill", 8, 1,
                BotMessages.get("pq.position_plan.spot", 1, 1));
        when(high.character().getPosition()).thenReturn(THIRD);
        when(low.character().getPosition()).thenReturn(SECOND);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.isMapObserved(room)).thenReturn(true);
            PqStageChat.announcePositionPlan(high.bot(), 8, plan, AREAS);
            speech.verifyNoInteractions(); // Right count, but the bots still have to swap spots.

            when(high.character().getPosition()).thenReturn(SECOND);
            when(low.character().getPosition()).thenReturn(THIRD);
            movement.when(() -> GCMovement.isMoving(low.character())).thenReturn(true);
            PqStageChat.announcePositionPlan(high.bot(), 8, plan, AREAS);
            speech.verifyNoInteractions();

            movement.when(() -> GCMovement.isHoldingClimb(low.character())).thenReturn(true);
            PqStageChat.announcePositionPlan(low.bot(), 8, plan, AREAS);
            when(high.death().isCorpse()).thenReturn(true);
            PqStageChat.announcePositionPlan(low.bot(), 8, plan, AREAS);

            speech.verify(() -> SocialCommands.BotFullChat(high.character(), room, expected));
            speech.verifyNoMoreInteractions();
        }
    }

    @Test
    void aFullBotLineupTellsAnExtraPlayerToStepOffInsteadOfStayingSilent() {
        Bot low = addBot(50, 50, room);
        Bot high = addBot(55, 70, room);
        when(low.character().getPosition()).thenReturn(THIRD);
        when(high.character().getPosition()).thenReturn(SECOND);
        when(leader.getPosition()).thenReturn(SECOND);
        when(instance.getPlayers()).thenReturn(List.of(leader, low.character(), high.character()));
        var plan = puzzlePlan(high, 2, null);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.isMapObserved(room)).thenReturn(true);
            PqStageChat.announcePositionPlan(low.bot(), 8, plan, AREAS);
            PqStageChat.announcePositionPlan(high.bot(), 8, plan, AREAS);
            speech.verify(() -> SocialCommands.BotFullChat(high.character(), room,
                    BotMessages.get("pq.position_plan.step_off", 8, 1)));
            speech.verifyNoMoreInteractions();
        }
    }

    @Test
    void aValidWrongEffectStartsOneSharedChatThenMovementSequence() {
        Bot low = addBot(50, 50, room);
        Bot high = addBot(55, 70, room);
        MapleMap otherCopy = room(room.getId());
        Bot elsewhere = addBot(60, 90, otherCopy);
        when(instance.getPlayers()).thenReturn(List.of(leader, low.character(), high.character(), elsewhere.character()));
        var first = puzzlePlan(high, 2, null);
        when(leader.getNpcCooldown()).thenReturn(1L);
        assertSame(first, puzzlePlan(low, 2, null), "ordinary dialogue cannot advance a round");

        when(low.character().getPosition()).thenReturn(THIRD);
        wrong();
        when(high.character().getPosition()).thenReturn(SECOND);
        when(leader.getPosition()).thenReturn(SECOND);
        wrong(); // extra body
        assertSame(first, puzzlePlan(low, 2, null));
        verify(low.bot(), never()).requestTickWithin(anyLong());

        when(leader.getPosition()).thenReturn(new Point(-20, 0));
        events.publish(new PartyQuestStageWrongEvent(instance, otherCopy));
        assertSame(first, puzzlePlan(low, 2, null));
        var ledger = PositionCombination.ledgerFor(room);
        var clock = new AtomicLong(10_000);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(GCMovement::movementNowMs).thenAnswer(call -> clock.get());
            movement.when(() -> GCMovement.isMapObserved(room)).thenReturn(true);
            wrong();
            long chatAt = ledger.retryChatAt;
            long chatDelay = chatAt - clock.get();
            assertTrue(chatDelay >= 1_000 && chatDelay <= 2_000);
            verify(low.bot()).requestTickWithin(chatDelay);
            verify(high.bot()).requestTickWithin(chatDelay);
            verify(elsewhere.bot(), never()).requestTickWithin(anyLong());

            clock.set(chatAt - 1);
            wrong(); // an extra click while the old formation is still standing
            assertEquals(chatAt, ledger.retryChatAt, "a duplicate click must not restart the delay");
            assertNull(standOn(high, 1), "an early macro tick may not move to the next lineup");
            PqStageChat.announcePositionPlan(low.bot(), 8, first, AREAS);
            speech.verifyNoInteractions();
            movement.verify(() -> GCMovement.moveIfTargetChanged(eq(high.character()),
                    eq(FIRST.x), eq(FIRST.y), eq(1), any()), never());

            clock.set(chatAt + 250); // movement must be delayed from the actual line, not its deadline
            var next = standOn(low, 0);
            assertEquals(2, next.attempt());
            assertEquals(List.of(FIRST, THIRD), next.spots());
            PqStageChat.announcePositionPlan(low.bot(), 8, next, AREAS);
            PqStageChat.announcePositionPlan(high.bot(), 8, next, AREAS);
            speech.verify(() -> SocialCommands.BotFullChat(high.character(), room,
                    BotMessages.get("pq.position_plan.retry", 8, 2)));
            long moveAt = ledger.retryMoveAt;
            long moveDelay = moveAt - clock.get();
            assertTrue(moveDelay >= 500 && moveDelay <= 1_500);
            verify(low.bot(), times(moveDelay == chatDelay ? 2 : 1)).requestTickWithin(moveDelay);

            clock.set(moveAt - 1);
            assertNull(standOn(high, 1));
            movement.verify(() -> GCMovement.moveIfTargetChanged(eq(high.character()),
                    eq(FIRST.x), eq(FIRST.y), eq(1), any()), never());

            clock.set(moveAt);
            assertSame(next, standOn(high, 1));
            movement.verify(() -> GCMovement.moveIfTargetChanged(eq(high.character()),
                    eq(FIRST.x), eq(FIRST.y), eq(1), any()));
            when(high.character().getPosition()).thenReturn(FIRST);
            PqStageChat.announcePositionPlan(low.bot(), 8, next, AREAS);
            PqStageChat.announcePositionPlan(high.bot(), 8, next, AREAS);
            speech.verifyNoMoreInteractions();
        }
    }

    @Test
    void anAnswerResetAllowsTheFirstRoundToBeTriedAgain() {
        Bot bot = addBot(50, 50, room);
        String property = "stage2combo";
        when(bot.character().getPosition()).thenReturn(THIRD);
        when(leader.getPosition()).thenReturn(SECOND);
        when(instance.getPlayers()).thenReturn(List.of(bot.character(), leader));
        var first = puzzlePlan(bot, 1, property);
        wrong();
        var ledger = PositionCombination.ledgerFor(room);
        long chatAt = ledger.retryChatAt;
        assertFalse(ledger.tried.isEmpty());

        when(instance.getProperty(property)).thenReturn("");
        wrong();
        var restarted = puzzlePlan(bot, 1, property);
        assertEquals(first, restarted);
        assertTrue(ledger.tried.isEmpty());
        assertEquals(chatAt, ledger.retryChatAt);
        verify(bot.bot()).requestTickWithin(longThat(delay -> delay >= 1_000 && delay <= 2_000));
    }

    @Test
    void orbisUsesTheMapCountsAndConsumesTheFirstWrongEffect() {
        room = room(OrbisPqData.STAGE_SEALED);
        when(leader.getMap()).thenReturn(room);
        Bot bot = addBot(50, 50, room);
        when(instance.getPlayers()).thenReturn(List.of(bot.character(), leader));
        when(room.getNumPlayersInArea(1)).thenReturn(1);
        when(room.getNumPlayersInArea(2)).thenReturn(1);
        puzzlePlan(bot, 1, null);
        wrong();
        assertTrue(PositionCombination.ledgerFor(room).tried.contains(CANDIDATES.getFirst().pattern()));
        verify(bot.bot()).requestTickWithin(longThat(delay -> delay >= 1_000 && delay <= 2_000));
    }

    private static PositionCombination.Plan puzzlePlan(Bot bot, int seated, String resetProperty) {
        Character character = bot.character();
        return PositionCombination.decide(PositionCombination.ledgerFor(character.getMap()), character,
                CANDIDATES, AREAS, resetProperty, seated, null);
    }

    private static PositionCombination.Plan standOn(Bot bot, int index) {
        return PositionCombination.standOn(bot.character(), index, CANDIDATES, AREAS, null, null, () -> {});
    }

    private void wrong() {
        when(room.getEventInstance()).thenReturn(instance);
        events.publish(new PartyQuestStageWrongEvent(instance, room));
    }

    @Test
    void catchingUpAfterTheLeaderLeavesDoesNotRepeatTheRearguardLine() {
        Bot follower = addBot(40, 50, room);
        doReturn(room(922010200)).when(leader).getMap();
        Portal exit = mock(Portal.class);
        when(exit.getTargetMapId()).thenReturn(922010200);
        when(exit.getPosition()).thenReturn(new Point(100, 0));
        when(exit.getPortalStatus()).thenReturn(true);
        when(room.getPortals()).thenReturn(List.of(exit));

        try (var actions = mockStatic(PqActions.class);
             var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.hasMoveTarget(follower.character())).thenReturn(true);
            follower.bot().tickPartyQuest();
            actions.verify(() -> PqActions.walkToPortal(follower.character(), exit));
            speech.verifyNoInteractions();
        }
    }

    private void clear() {
        events.publish(new PartyQuestStageClearedEvent(instance, room));
    }

    private EventInstanceManager instance() {
        EventInstanceManager result = mock(EventInstanceManager.class);
        when(result.getLeader()).thenReturn(leader);
        Properties properties = new Properties();
        when(result.getProperty(anyString())).thenAnswer(invocation ->
                properties.getProperty(invocation.getArgument(0)));
        when(result.setProperty(anyString(), anyString(), eq(true)))
                .thenAnswer(invocation -> properties.setProperty(
                        invocation.getArgument(0), invocation.getArgument(1)));
        return result;
    }

    private static MapleMap room(int id) {
        MapleMap result = mock(MapleMap.class);
        when(result.getId()).thenReturn(id);
        return result;
    }

    private Character character(int id, int level, MapleMap map) {
        Character result = mock(Character.class);
        when(result.getId()).thenReturn(id);
        when(result.getLevel()).thenReturn(level);
        when(result.getMap()).thenReturn(map);
        when(result.getMapId()).thenAnswer(invocation -> result.getMap().getId());
        when(result.getParty()).thenReturn(party);
        return result;
    }

    private Bot addBot(int id, int level, MapleMap map) {
        Character character = character(id, level, map);
        when(character.getEventInstance()).thenReturn(instance);
        BotDeath death = mock(BotDeath.class);
        PartyQuestBot bot = mock(LudiPQBot.class, CALLS_REAL_METHODS);
        doReturn(character).when(bot).getChr();
        doReturn(true).when(bot).getRunning();
        doReturn(BotSM.BotState.RUNNING).when(bot).getState();
        doReturn(death).when(bot).death();
        register(id, bot);
        return new Bot(character, bot, death);
    }

    private void register(int id, BotSM bot) {
        CharacterStorage.addActiveBot(id, bot);
        registered.add(id);
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        members.add(member);
    }

    private record Bot(Character character, PartyQuestBot bot, BotDeath death) {
    }
}
