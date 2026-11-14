package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.extension.api.HostRuntime;
import org.gms.extension.event.PartyQuestStageClearedEvent;
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
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.Environment.BotMessages;
import soloMapling.plugin.HostGameplayEventBridge;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class PqRearguardTest {

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
        registered.forEach(CharacterStorage::removeActiveBot);
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
    void catchingUpAfterTheLeaderLeavesDoesNotRepeatTheRearguardLine() {
        Bot follower = addBot(40, 50, room);
        doReturn(room(922010200)).when(leader).getMap();
        Portal exit = mock(Portal.class);
        when(exit.getTargetMapId()).thenReturn(922010200);
        when(exit.getPosition()).thenReturn(new Point(100, 0));
        when(room.getPortals()).thenReturn(List.of(exit));

        try (var actions = mockStatic(PqActions.class);
             var movement = mockStatic(GCMovement.class)) {
            assertFalse(follower.bot().followLeaderIntoNextRoom());
            actions.verify(() -> PqActions.walkTo(follower.character(), exit.getPosition()));
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
