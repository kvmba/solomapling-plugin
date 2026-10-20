package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.SkillFactory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.WeaponType;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotComboOrb;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotFlavorSystem.BotFlavor;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiStages;
import soloMapling.ArtificialPlayer.BotTypes.Pirate.PiratePQBot;
import soloMapling.ArtificialPlayer.BotTypes.Pirate.PiratePqData;
import soloMapling.ArtificialPlayer.BotTypes.Zakum.ZakumPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Zakum.ZakumPqData;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.MapVFX.CustomReactor;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotAuditRegressionTest {
    @BeforeAll
    static void bootHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(call -> call.getArgument(0));
        for (String bean : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(bean, MessageSource.class)).thenReturn(messages);
        }
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
    }

    @Test
    void rangedAndMagicKeepCrossLedgeCandidatesWhileMeleeRejectsThem() throws Exception {
        MapleMap map = mock(MapleMap.class);
        Character bot = character(map, 781001);
        Monster mob = mock(Monster.class);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getPosition()).thenReturn(new Point(250, -20));
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(List.of(mob));
        var seek = BotAttackDriver.class.getDeclaredMethod("seek", Character.class, BotAttackProfile.class,
                WeaponType.class);
        seek.setAccessible(true);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.peekRegionIdAt(map, 0, 0)).thenReturn(1);
            movement.when(() -> GCMovement.onDifferentLedge(map, 1, 250, -20)).thenReturn(true);
            for (BotAttackProfile profile : List.of(
                    BotAttackProfile.ranged(0, 1), BotAttackProfile.magic(0, 1), BotAttackProfile.basicSwing())) {
                Object scan = seek.invoke(null, bot, profile, null);
                var nearest = scan.getClass().getDeclaredMethod("nearest");
                nearest.setAccessible(true);
                assertEquals(profile.route == BotAttackProfile.Route.CLOSE ? null : mob, nearest.invoke(scan));
                if (profile.route != BotAttackProfile.Route.CLOSE) {
                    var inReach = BotAttackDriver.class.getDeclaredMethod(
                            "mobsInReach", scan.getClass(), BotAttackProfile.class, WeaponType.class, boolean.class);
                    inReach.setAccessible(true);
                    assertEquals(List.of(mob), inReach.invoke(null, scan, profile, null, false));
                }
            }
        }
    }

    @Test
    void crossLedgeMobOutsideTheVerticalBoxDoesNotStealFacingFromAHittableMob() throws Exception {
        MapleMap map = mock(MapleMap.class);
        Character bot = character(map, 781008);
        Monster overhead = mock(Monster.class);
        when(overhead.isAlive()).thenReturn(true);
        when(overhead.getPosition()).thenReturn(new Point(-10, -170));
        Monster reachable = mock(Monster.class);
        when(reachable.isAlive()).thenReturn(true);
        when(reachable.getPosition()).thenReturn(new Point(300, 0));
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), anyList()))
                .thenReturn(List.of(overhead, reachable));
        var seek = BotAttackDriver.class.getDeclaredMethod("seek", Character.class, BotAttackProfile.class,
                WeaponType.class);
        seek.setAccessible(true);
        Object scan = seek.invoke(null, bot, BotAttackProfile.ranged(0, 1), null);
        var nearest = scan.getClass().getDeclaredMethod("nearest");
        nearest.setAccessible(true);
        assertSame(reachable, nearest.invoke(scan));
    }

    @Test
    void approachUsesTheBaseAttackBoxWhileTheComboFinisherCools() {
        MapleMap map = mock(MapleMap.class);
        Character bot = character(map, 781009);
        when(bot.getJob()).thenReturn(Job.CRUSADER);
        Monster mob = mock(Monster.class);
        when(mob.getPosition()).thenReturn(new Point(150, 0));
        var skill = mock(org.gms.client.Skill.class);
        var effect = mock(org.gms.server.StatEffect.class);
        when(skill.getEffect(anyInt())).thenReturn(effect);
        when(effect.getAttackBox(any(), anyBoolean())).thenAnswer(call -> {
            int width = call.<Boolean>getArgument(1) ? -200 : 200;
            return new java.awt.Rectangle(Math.min(0, width), -60, 200, 120);
        });
        try (var skills = mockStatic(SkillFactory.class);
             var combo = mockStatic(BotComboOrb.class, CALLS_REAL_METHODS)) {
            skills.when(() -> SkillFactory.getSkill(org.gms.constants.skills.Crusader.SWORD_PANIC))
                    .thenReturn(skill);
            combo.when(() -> BotComboOrb.finisherReady(bot)).thenReturn(true);
            assertTrue(BotAttackDriver.isInAttackRange(bot, mob));
            combo.when(() -> BotComboOrb.finisherReady(bot)).thenReturn(false);
            assertFalse(BotAttackDriver.isInAttackRange(bot, mob));
            when(mob.getPosition()).thenReturn(new Point(70, 0));
            assertTrue(BotAttackDriver.isInAttackRange(bot, mob));
        }
    }

    @Test
    void approachDoesNotStopForAMeleeTargetOnAnotherLedgeOrOutsideItsBox() {
        MapleMap map = mock(MapleMap.class);
        Character bot = character(map, 781002);
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        Monster mob = mock(Monster.class);
        when(mob.getPosition()).thenReturn(new Point(50, -20));
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.onDifferentLedge(map, 0, 0, 50, -20)).thenReturn(true);
            assertFalse(BotAttackDriver.isInAttackRange(bot, mob));
            movement.when(() -> GCMovement.onDifferentLedge(map, 0, 0, 50, -20)).thenReturn(false);
            assertTrue(BotAttackDriver.isInAttackRange(bot, mob));
            when(mob.getPosition()).thenReturn(new Point(50, -75));
            assertFalse(BotAttackDriver.isInAttackRange(bot, mob), "the old 90px approach floor exceeded melee reach");
        }
    }

    @Test
    void rangedAndMagicApproachGatesUseTheirFullCrossLedgeReach() {
        MapleMap map = mock(MapleMap.class);
        Character bot = character(map, 781007);
        Monster mob = mock(Monster.class);
        try (var movement = mockStatic(GCMovement.class);
             var skills = mockStatic(SkillFactory.class)) {
            movement.when(() -> GCMovement.onDifferentLedge(eq(map), anyInt(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);
            for (Job job : List.of(Job.BOWMAN, Job.MAGICIAN)) {
                when(bot.getJob()).thenReturn(job);
                when(mob.getPosition()).thenReturn(new Point(400, -20));
                assertTrue(BotAttackDriver.isInAttackRange(bot, mob));
                when(mob.getPosition()).thenReturn(new Point(451, -20));
                assertFalse(BotAttackDriver.isInAttackRange(bot, mob));
            }
        }
    }

    @Test
    void reactorBeatKeepsItsTargetWhenTheBodyRefusesThenHitsAfterUnlock() throws Exception {
        MapleMap map = mock(MapleMap.class);
        Character bot = character(map, 781003);
        Reactor reactor = mock(Reactor.class);
        when(reactor.isActive()).thenReturn(true);
        when(reactor.getPosition()).thenReturn(new Point(0, 0));
        when(map.getReactorByOid(7)).thenReturn(reactor);
        Field statesField = GCMovement.class.getDeclaredField("STATES");
        statesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, BotMovementState> states = (Map<Integer, BotMovementState>) statesField.get(null);
        BotMovementState state = new BotMovementState(bot, null);
        states.put(bot.getId(), state);
        MovementClock.useVirtual(1_000_000L);
        state.attackActionUntilMs = MovementClock.nowMs() + GCMovement.ATTACK_ACTION_LOCK_MS;
        try (var custom = mockStatic(CustomReactor.class)) {
            PqActions.armReactorBeat(bot, 7);
            assertFalse(PqActions.workReactorOnBeat(bot));
            assertTrue(PqActions.reactorBeatArmed(bot));
            custom.verifyNoInteractions();
            MovementClock.advance(GCMovement.ATTACK_ACTION_LOCK_MS);
            assertFalse(PqActions.workReactorOnBeat(bot));
            custom.verify(() -> CustomReactor.hitReactorWithScript(map, 7, bot));
            assertTrue(PqActions.reactorBeatArmed(bot), "a live multi-hit box remains armed");
        } finally {
            states.remove(bot.getId());
            PqActions.clearReactorBeat(bot.getId());
            PqActions.clearSeekState(bot.getId());
            MovementClock.reset();
        }
    }

    @Test
    void ludiComboPacesAndRetriesATemporaryRefusal() throws Exception {
        MapleMap map = mock(MapleMap.class);
        Character bot = character(map, 781004);
        Reactor reactor = mock(Reactor.class);
        when(reactor.isActive()).thenReturn(true, true, false);
        when(map.getReactorByOid(7)).thenReturn(reactor);
        var combo = LudiStages.class.getDeclaredMethod("breakBoxInPlace", Character.class, int.class);
        combo.setAccessible(true);
        BotSM owner = mock(BotSM.class);
        when(owner.getRunning()).thenReturn(true);
        when(bot.isAlive()).thenReturn(true);
        CharacterStorage.addActiveBot(bot.getId(), owner);
        try (var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.hitReactor(bot, 7)).thenReturn(false, true);
            combo.invoke(null, bot, 7);
            actions.verify(() -> PqActions.hitReactor(bot, 7), times(2));
        } finally {
            CharacterStorage.removeActiveBot(bot.getId());
        }
    }

    @Test
    void ludiComboDoesNotKeepSwingingAfterItsOwnerStops() throws Exception {
        MapleMap map = mock(MapleMap.class);
        Character bot = character(map, 781010);
        when(bot.isAlive()).thenReturn(true);
        Reactor reactor = mock(Reactor.class);
        when(reactor.isActive()).thenReturn(true);
        when(map.getReactorByOid(7)).thenReturn(reactor);
        BotSM owner = mock(BotSM.class);
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(owner.getRunning()).thenAnswer(call -> running.get());
        CharacterStorage.addActiveBot(bot.getId(), owner);
        var combo = LudiStages.class.getDeclaredMethod("breakBoxInPlace", Character.class, int.class);
        combo.setAccessible(true);
        try (var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.hitReactor(bot, 7)).thenAnswer(call -> {
                running.set(false);
                return false;
            });
            combo.invoke(null, bot, 7);
            actions.verify(() -> PqActions.hitReactor(bot, 7), times(1));
        } finally {
            CharacterStorage.removeActiveBot(bot.getId());
        }
    }

    @Test
    void questMacrosDelegateBoxesToTheNonBlockingBeat() throws Exception {
        MapleMap map = mock(MapleMap.class);
        Character bot = character(map, 781006);
        when(map.getId()).thenReturn(PiratePqData.STAGE_4);
        PiratePQBot pirate = mock(PiratePQBot.class, CALLS_REAL_METHODS);
        when(pirate.getChr()).thenReturn(bot);
        ZakumPQBot zakum = mock(ZakumPQBot.class, CALLS_REAL_METHODS);
        when(zakum.getChr()).thenReturn(bot);
        try (var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.findReactorOid(bot, PiratePqData.STAGE_4_BOXES[0])).thenReturn(7);
            actions.when(() -> PqActions.findReactorOid(bot, ZakumPqData.CRATES[0])).thenReturn(8);
            actions.when(() -> PqActions.approachReactor(bot, 7, true, true))
                    .thenReturn(PqActions.Approach.IN_POSITION);
            for (BotSM owner : List.of(pirate, zakum)) {
                var work = owner.getClass().getDeclaredMethod("workStage");
                work.setAccessible(true);
                assertEquals(false, work.invoke(owner));
            }
            actions.verify(() -> PqActions.approachReactor(bot, 7, true, true));
            actions.verify(() -> PqActions.approachReactor(bot, 8, true, true));
            actions.verify(() -> PqActions.hitReactor(any(), anyInt()), never());
            actions.verify(() -> PqActions.seekAndAttack(bot), never());
        }
    }

    @Test
    void flavorCleanupIsNotUndoneByAnExpressionThatFinishesAfterStop() throws Exception {
        MapleMap map = mock(MapleMap.class);
        Character character = character(map, 781005);
        BotSM owner = mock(BotSM.class);
        when(owner.getChr()).thenReturn(character);
        when(owner.getRunning()).thenReturn(true);
        double chance = BotFlavor.FLAVOR_CHANCE;
        BotFlavor.FLAVOR_CHANCE = 1;
        Field field = BotFlavor.class.getDeclaredField("cooldownUntil");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> cooldowns = (Map<Integer, Long>) field.get(null);
        try (var movement = mockStatic(GCMovement.class);
             var swings = mockStatic(BotAttack.class)) {
            movement.when(() -> GCMovement.isMapObserved(anyInt())).thenReturn(true);
            BotFlavor.maybeExpress(owner);
            assertTrue(cooldowns.containsKey(character.getId()));
            BotFlavor.forget(character);
            assertFalse(cooldowns.containsKey(character.getId()));
            when(owner.getRunning()).thenReturn(false);
            BotFlavor.maybeExpress(owner);
            assertFalse(cooldowns.containsKey(character.getId()));
            cooldowns.put(character.getId(), 1L); // a new owner published after conversion
            BotFlavor.maybeExpress(owner);
            assertEquals(1L, cooldowns.get(character.getId()), "the old owner must not erase the new cooldown");
        } finally {
            BotFlavor.FLAVOR_CHANCE = chance;
            BotFlavor.forget(character);
        }
    }

    private static Character character(MapleMap map, int id) {
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(id);
        when(bot.getMap()).thenReturn(map);
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11)).thenReturn(null);
        return bot;
    }
}
