package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.WeaponType;
import org.gms.manager.ServerManager;
import org.gms.net.server.world.Party;
import org.gms.property.ServiceProperty;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotDeath;
import soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class CarnivalWaveReactionTest {

    private static final int BOT_ID = 880091;

    /** Character's static config and i18n lookups need the same stub host as other bot tests. */
    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, MessageSource.class)).thenReturn(messages);
        }
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
    }

    @AfterEach
    void clearCombatState() {
        PqActions.clearSeekState(BOT_ID);
        BotAttackDriver.clearBot(BOT_ID);
    }

    private static MapleMap arena() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(CarnivalPqData.arenaForField(0));
        when(map.isCPQMap()).thenReturn(true);
        return map;
    }

    private static Monster monster(MapleMap map, int team) {
        Monster monster = mock(Monster.class);
        when(monster.getMap()).thenReturn(map);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getTeam()).thenReturn(team);
        when(monster.getPosition()).thenReturn(new Point(20, 0));
        return monster;
    }

    private static void monstersOn(MapleMap map, Monster... monsters) {
        when(map.getAllMonsters()).thenReturn(List.of(monsters));
    }

    private static CarnivalPQBot.WaveReaction reaction(MapleMap map) {
        return new CarnivalPQBot.WaveReaction(map,
                enemy -> enemy != null && CarnivalPQBot.sideRule(0, enemy.getTeam()));
    }

    private static CarnivalPQBot bot(MapleMap map) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(BOT_ID);
        when(character.getMap()).thenReturn(map);
        doReturn(map.getId()).when(character).getMapId();
        when(character.getTeam()).thenReturn((byte) 0);
        when(character.getPosition()).thenReturn(new Point(0, 0));
        when(character.getParty()).thenReturn(mock(Party.class));
        doReturn(Job.BEGINNER).when(character).getJob();
        when(character.getLevel()).thenReturn(40);
        CarnivalPQBot bot = mock(CarnivalPQBot.class, CALLS_REAL_METHODS);
        doReturn(character).when(bot).getChr();
        doReturn(true).when(bot).getRunning();
        doReturn(mock(BotDeath.class)).when(bot).death();
        doReturn(mock(BotDebuffState.class)).when(bot).status();
        return bot;
    }

    @Test
    void aWaveWaitsBetween300And2000msWithoutSlidingTheDeadline() {
        MapleMap map = arena();
        Monster enemy = monster(map, 1);
        monstersOn(map, enemy);
        var reaction = reaction(map);

        assertFalse(reaction.admits(enemy, 10_000L));
        assertFalse(reaction.admits(enemy, 10_299L));
        assertTrue(reaction.admits(enemy, 12_000L));
        assertTrue(reaction.admits(enemy, 20_000L));
    }

    @Test
    void aClearAndRespawnBetweenTwoScansArmsTheNextWave() {
        MapleMap map = arena();
        Monster previous = monster(map, 1);
        monstersOn(map, previous);
        var reaction = reaction(map);
        assertFalse(reaction.admits(previous, 10_000L));
        assertTrue(reaction.admits(previous, 12_000L));

        when(previous.isAlive()).thenReturn(false);
        Monster next = monster(map, 1);
        monstersOn(map, next);
        assertFalse(reaction.admits(next, 12_250L), "no empty-map scan is needed between waves");
        assertFalse(reaction.admits(next, 12_549L));
        assertTrue(reaction.admits(next, 14_250L));
    }

    @Test
    void retargetingAndAddingEnemiesDuringALiveFightDoNotRestartTheReaction() {
        MapleMap map = arena();
        Monster first = monster(map, 1);
        Monster second = monster(map, 1);
        monstersOn(map, first, second);
        var reaction = reaction(map);
        assertFalse(reaction.admits(first, 10_000L));
        assertTrue(reaction.admits(first, 12_000L));

        when(first.isAlive()).thenReturn(false);
        assertTrue(reaction.admits(second, 12_250L));
        Monster extra = monster(map, 1);
        monstersOn(map, second, extra);
        assertTrue(reaction.admits(extra, 12_500L));
        when(second.isAlive()).thenReturn(false);
        assertTrue(reaction.admits(extra, 12_750L));
    }

    @Test
    void ownSideAndDeadMonstersDoNotKeepAnExpiredWaveAlive() {
        MapleMap map = arena();
        Monster own = monster(map, 0);
        Monster previous = monster(map, 1);
        monstersOn(map, own, previous);
        var reaction = reaction(map);
        assertFalse(reaction.admits(own, 10_000L));
        assertFalse(reaction.admits(previous, 10_000L));
        assertTrue(reaction.admits(previous, 12_000L));

        when(previous.isAlive()).thenReturn(false);
        Monster next = monster(map, 1);
        monstersOn(map, own, previous, next);
        assertFalse(reaction.admits(next, 12_250L));
        assertFalse(reaction.admits(previous, 15_000L));
        assertFalse(reaction.admits(own, 15_000L));
    }

    @Test
    void aWaveThatDiesDuringTheReactionDoesNotDonateItsDeadlineToTheNextOne() {
        MapleMap map = arena();
        Monster previous = monster(map, -1);
        monstersOn(map, previous);
        var reaction = reaction(map);
        assertFalse(reaction.admits(previous, 10_000L));

        when(previous.isAlive()).thenReturn(false);
        Monster next = monster(map, 1);
        monstersOn(map, next);
        assertFalse(reaction.admits(next, 20_000L));
        assertTrue(reaction.admits(next, 22_000L));
    }

    @Test
    void botsKeepIndependentReactionDeadlines() {
        MapleMap map = arena();
        Monster enemy = monster(map, 1);
        monstersOn(map, enemy);
        var first = reaction(map);
        var second = reaction(map);

        assertFalse(first.admits(enemy, 10_000L));
        assertFalse(second.admits(enemy, 12_000L));
        assertTrue(first.admits(enemy, 12_000L));
        assertFalse(second.admits(enemy, 12_299L));
        assertTrue(second.admits(enemy, 14_000L));
    }

    @Test
    void waitingDoesNotPauseSteppedOnLootOrMacroLoot() {
        MapleMap map = arena();
        monstersOn(map, monster(map, 1));
        CarnivalPQBot bot = bot(map);
        Character character = bot.getChr();
        Point position = character.getPosition();

        try (var actions = mockStatic(PqActions.class);
             var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.hasGraph(character)).thenReturn(true);
            bot.grindTick();
            bot.workStage();

            actions.verify(() -> PqActions.lootSteppedOn(character, CarnivalPqData.ARENA_CONSUMABLES));
            actions.verify(() -> PqActions.loot(character, position, 260, CarnivalPqData.ARENA_CONSUMABLES));
            actions.verify(() -> PqActions.seekAndAttack(character, false,
                    CarnivalPqData.ARENA_CONSUMABLES), times(2));
        }
    }

    @Test
    void anEnemySpawnedAfterAnEmptySweepGateStillMeetsTheReactionFilter() {
        MapleMap map = arena();
        monstersOn(map);
        CarnivalPQBot bot = bot(map);
        Character character = bot.getChr();

        assertTrue(bot.fightsOnSweep());
        Monster enemy = monster(map, 1);
        monstersOn(map, enemy);
        assertFalse(PqActions.huntFilterAdmits(character, enemy));
    }

    @Test
    void actualAttackAndChaseSelectionBothRejectTheWaitingWave() {
        MapleMap map = arena();
        Monster enemy = monster(map, 1);
        monstersOn(map, enemy);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(List.of(enemy));
        Character character = bot(map).getChr();
        var reaction = reaction(map);
        AtomicLong clock = new AtomicLong(10_000L);
        PqActions.setHuntFilter(character, candidate -> reaction.admits(candidate, clock.get()));
        BotAttackProfile profile = BotAttackProfile.melee(0, 1);

        try (var movement = mockStatic(GCMovement.class);
             var equipment = mockStatic(BotAttack.class);
             var config = mockStatic(BotAttackConfig.class)) {
            equipment.when(() -> BotAttack.resolveEquippedWeaponType(character)).thenReturn(WeaponType.CLAW);
            config.when(() -> BotAttackConfig.resolve(Job.BEGINNER, WeaponType.CLAW, 40))
                    .thenReturn(new BotAttackConfig.JobAttacks(profile, null, null));

            assertFalse(BotAttackDriver.canReach(character, enemy));
            assertFalse(BotAttackDriver.botAttack(character).hit());
            PqActions.seekAndAttack(character);
            movement.verify(() -> GCMovement.moveTracking(eq(character), anyInt(), anyInt(), anyInt()), never());

            clock.set(12_000L);
            assertTrue(BotAttackDriver.canReach(character, enemy));
        }
    }

    @Test
    void aRematchAndLeavingTheRunReplaceTheFilterWithoutReusingTheArenaId() {
        MapleMap map = arena();
        MapleMap rematch = arena();
        CarnivalPQBot bot = bot(map);
        Character character = bot.getChr();
        AtomicReference<Predicate<Monster>> installed = new AtomicReference<>();

        try (var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.setHuntFilter(eq(character), any())).thenAnswer(invocation -> {
                installed.set(invocation.getArgument(1));
                return null;
            });
            assertTrue(bot.fightsOnSweep());
            Predicate<Monster> first = installed.get();
            when(character.getMap()).thenReturn(rematch);
            assertTrue(bot.fightsOnSweep());
            Predicate<Monster> second = installed.get();
            assertNotSame(first, second);
            assertFalse(first.test(monster(rematch, 1)), "a stale filter must reject another map instance");

            bot.releaseRoomState();
            assertNull(installed.get());
            assertTrue(bot.fightsOnSweep());
            assertNotSame(second, installed.get());
        }
    }
}
