package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotDeath;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the stage-8 announcement contract: the room has ONE voice (the highest-level bot standing
 * in it), so the shared crate ledger's attempt is spoken once instead of once per bot.
 */
class LudiStage8SpeakerTest {

    /** The host bootstrap every Character-mocking test in this tree needs. */
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
                .thenAnswer(inv -> inv.getArgument(0));
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
    /** The party roster as it stands now: a bot added mid-test is visible to every member. */
    private final List<PartyCharacter> members = new ArrayList<>();
    private Party party;
    /** The stage-8 room the bots stand in; same instance = same room. */
    private MapleMap room;

    @BeforeEach
    void openParty() {
        party = mock(Party.class);
        when(party.getMembers()).thenReturn(members);
        room = mock(MapleMap.class);
    }

    @AfterEach
    void releaseStorage() {
        registered.forEach(CharacterStorage::removeActiveBot);
    }

    /**
     * The same plan, offered to a room of three bots, is spoken by the level-50 one only - not by
     * the two lower-level bodies, which is the whole defect (every bot broadcast the identical
     * line at every judged attempt).
     */
    @Test
    void onlyTheHighestLevelBotInTheRoomAnnounces() {
        Bot bot30 = add(30, 30);
        Bot bot50 = add(31, 50);
        Bot bot40 = add(32, 40);
        PositionCombination.Plan plan = new PositionCombination.Plan(List.of(), 2, 3);

        assertFalse(bot30.pq.announceCratePlan(plan), "a lower-level bot must stay quiet");
        assertTrue(bot50.pq.announceCratePlan(plan), "the highest-level bot in the room speaks");
        assertFalse(bot40.pq.announceCratePlan(plan), "a lower-level bot must stay quiet");
    }

    /**
     * The voice belongs to a room, not to the run: each room picks its own speaker, so a stronger
     * bot next door neither speaks for this room nor silences it. Within a room a same-level tie
     * goes to the lower character id, so exactly one bot wins.
     */
    @Test
    void eachRoomPicksItsOwnSpeakerAndBreaksTiesById() {
        Bot strongestHere = add(20, 45);
        Bot tiedHigherId = add(22, 45); // same level, higher id: must yield
        Bot strongerElsewhere = add(21, 60, mock(MapleMap.class));

        assertTrue(strongestHere.pq.announceCratePlan(plan(1)),
                "the lower id wins the tie, and the stronger bot next door must not take this voice");
        assertFalse(tiedHigherId.pq.announceCratePlan(plan(1)), "the id tie-break picks one speaker");
        assertTrue(strongerElsewhere.pq.announceCratePlan(plan(1)),
                "the other room's own speaker still announces there - the voices are independent");
    }

    /**
     * A speaker that is down hands the voice to the next bot in the room rather than muting the
     * stage - the announcement is the party's only cue to talk to the balloon - and the transfer
     * still leaves exactly one speaker.
     */
    @Test
    void aDeadSpeakerHandsTheVoiceOver() {
        Bot strongest = add(40, 60);
        Bot next = add(41, 50);
        Bot lowest = add(42, 40);
        when(strongest.death.isCorpse()).thenReturn(true);

        assertTrue(next.pq.announceCratePlan(plan(2)), "the next body in the room speaks instead");
        assertFalse(lowest.pq.announceCratePlan(plan(2)), "the hand-over still leaves one speaker");
    }

    /** The same attempt is never repeated by the speaker; a new attempt is. */
    @Test
    void theSpeakerDoesNotRepeatItself() {
        Bot only = add(50, 50);

        assertTrue(only.pq.announceCratePlan(plan(4)));
        assertFalse(only.pq.announceCratePlan(plan(4)), "same attempt, same line: stay quiet");
        assertTrue(only.pq.announceCratePlan(plan(5)), "a new attempt is a new line");
    }

    /**
     * A paused bot neither speaks nor blocks the voice: both halves of the eligibility test must be
     * symmetric, or the room's bots pick different winners and shout the same line twice.
     */
    @Test
    void aPausedBotNeitherSpeaksNorSilencesTheRoom() {
        Bot paused = add(60, 70);
        Bot next = add(61, 50);
        when(paused.pq.getState()).thenReturn(BotSM.BotState.PAUSE);

        assertFalse(paused.pq.announceCratePlan(plan(6)), "a paused bot does not speak");
        assertTrue(next.pq.announceCratePlan(plan(6)), "and the next body takes the voice");
    }

    private static PositionCombination.Plan plan(int attempt) {
        return new PositionCombination.Plan(List.of(), 0, attempt);
    }

    private record Bot(BotDeath death, LudiPQBot pq) {
    }

    private Bot add(int id, int level) {
        return add(id, level, room);
    }

    /** One running bot at {@code level} in {@code room}, joining this test's party. */
    private Bot add(int id, int level, MapleMap room) {
        Character me = mock(Character.class);
        when(me.getId()).thenReturn(id);
        when(me.getLevel()).thenReturn(level);
        when(me.getMap()).thenReturn(room);
        when(me.getParty()).thenReturn(party);

        BotDeath death = mock(BotDeath.class);
        LudiPQBot pq = mock(LudiPQBot.class, CALLS_REAL_METHODS);
        doReturn(me).when(pq).getChr();
        doReturn(true).when(pq).getRunning();
        doReturn(BotSM.BotState.RUNNING).when(pq).getState();
        doReturn(death).when(pq).death();

        CharacterStorage.addActiveBot(id, pq);
        registered.add(id);
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        members.add(member);
        return new Bot(death, pq);
    }
}
