package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.life.Monster;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiStages;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.MapVFX.CustomReactor;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Behavioural regressions from the review of 2f37975 / 17d4145. */
class LpqReviewRegressionTest {
    private static final int BOT_ID = 891001;
    private Character bot;
    private BotMovementState state;
    @TempDir static Path navigationCache;
    private static Path originalNavigationCache;

    @BeforeAll
    static void bootHost() throws Exception {
        originalNavigationCache = BotNavigationGraphProvider.cacheDir();
        BotNavigationGraphProvider.setCacheDir(navigationCache);
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
        interval.setConfigType("server"); interval.setConfigSubType("0");
        interval.setConfigCode("update_interval"); interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
    }

    @AfterAll
    static void restoreNavigationCache() {
        BotNavigationGraphProvider.setCacheDir(originalNavigationCache);
    }

    @BeforeEach
    void useClock() {
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterEach
    void releaseState() throws Exception {
        states().remove(BOT_ID);
        PqActions.clearSeekState(BOT_ID);
        LudiStages.releaseRoomState(BOT_ID);
        BotAttackDriver.clearBot(BOT_ID);
        BotAuraState.clearBot(BOT_ID);
        MovementClock.reset();
    }

    @Test
    void aSecondFallIntoTheSamePitIsRescuedAfterCooldown() throws Exception {
        MapleMap map = map(922019971);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, -100, -500, 100);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        state.moveTarget = new Point(0, -500);
        assertTrue(BotMovementManager.selfHealDeadPit(state));
        assertEquals(new Point(0, -500), bot.getPosition());

        BotPhysicsEngine.teleportTo(state, bot, new Point(0, 0));
        BotMovementManager.resetEntryStateAfterTeleport(state);
        state.moveTarget = new Point(0, -500);
        assertFalse(BotMovementManager.selfHealDeadPit(state), "rescue cooldown still applies");
        state.unstuckCooldownMs = 0;
        assertTrue(BotMovementManager.selfHealDeadPit(state), "a cached dead verdict still requires rescue");
        assertEquals(new Point(0, -500), bot.getPosition());
    }

    @Test
    void aPitVerdictIsRecheckedWhenMovementCapabilitiesChange() throws Exception {
        MapleMap map = map(922019972);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, -100, -120, 100);
        floor(map, 3, -100, -600, 100);
        createBot(map, new Point(0, 0), Job.CLERIC);
        state.moveTarget = new Point(0, -120);
        assertFalse(BotMovementManager.selfHealDeadPit(state), "Teleport is a real exit");
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        assertTrue(BotMovementManager.selfHealDeadPit(state), "the same floor traps a non-blink bot");
    }

    @Test
    void aFallbackTargetKeepsItsDeadlineUntilItIsAbandoned() throws Exception {
        MapleMap map = map(922019973);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, -100, -500, 100);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        inject(map, monster(9300005, 99101, new Point(0, -500)));
        PqActions.seekAndAttack(bot);
        assertNotNull(state.moveTarget);
        for (int beat = 0; beat < 17; beat++) {
            MovementClock.advance(250);
            PqActions.seekAndAttack(bot);
        }
        assertNull(state.moveTarget, "the unreachable fallback must time out after four seconds");
        assertFalse(state.moveTargetTracking);
    }

    @Test
    void aForeignLedgeCannotStealAMeleeSwingsFacing() throws Exception {
        MapleMap map = map(922019974);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, -100, -40, -1);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        Monster blocked = monster(9300005, 99102, new Point(-40, -40));
        Monster reachable = monster(9300005, 99103, new Point(70, 0));
        inject(map, blocked); inject(map, reachable);
        assertTrue(BotAttackDriver.canReach(bot, reachable));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.forceSingle(bot).hit(), "face and hit the same-floor target");
            effects.verify(() -> BotAttackEffects.meleeStrike(eq(bot),
                    argThat(hits -> hits.containsKey(reachable) && !hits.containsKey(blocked)),
                    anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyShort()));
        }
    }

    @Test
    void autoFacesTheAttackableMobInsteadOfTheCloserOutOfRangeMob() throws Exception {
        MapleMap map = map(922019981);
        floor(map, 1, -100, 0, 500);
        floor(map, 2, -100, -160, -1);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster blocked = monster(9300169, 99113, new Point(-30, -160));
        Monster target = monster(9300170, 99114, new Point(400, 0));
        inject(map, blocked); inject(map, target);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, target));
            assertFalse(BotAttackDriver.canReach(bot, blocked));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
            effects.verify(() -> BotAttackEffects.rangedStrike(eq(bot),
                    argThat(hits -> hits.containsKey(target) && !hits.containsKey(blocked)),
                    anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyShort()));
            for (int beat = 0; beat < 80; beat++) {
                PqActions.seekAndAttack(bot, true);
                MovementClock.advance(250);
            }
            assertNull(state.moveTarget, "stationary is safe here because AUTO really can hit the target");
        }
    }

    @Test
    void autoReachIncludesThePackProfileAndItsTargetCap() throws Exception {
        MapleMap map = map(922019988);
        floor(map, 1, -100, 0, 800);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster first = monster(9300005, 99121, new Point(100, -180));
        Monster second = monster(9300005, 99122, new Point(200, -180));
        inject(map, first); inject(map, second);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, first), "AUTO chooses the taller pack box");
            assertTrue(BotAttackDriver.canReach(bot, second));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
            effects.verify(() -> BotAttackEffects.rangedStrike(eq(bot),
                    argThat(hits -> hits.containsKey(first) && hits.containsKey(second)),
                    anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyShort()));
        }
    }

    @Test
    void autoReachDoesNotClaimAMobOutsideTheActualTargetCap() throws Exception {
        MapleMap map = map(922019989);
        floor(map, 1, -100, 0, 800);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        Monster first = monster(9300005, 99123, new Point(30, 0));
        Monster second = monster(9300005, 99124, new Point(70, 0));
        inject(map, first); inject(map, second);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, first));
            assertFalse(BotAttackDriver.canReach(bot, second));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
            effects.verify(() -> BotAttackEffects.meleeStrike(eq(bot),
                    argThat(hits -> hits.containsKey(first) && !hits.containsKey(second)),
                    anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyShort()));
        }
    }

    @Test
    void anAttackBlockingDebuffDoesNotCountAsAWorkingFiringPosition() throws Exception {
        MapleMap map = map(922019992);
        floor(map, 1, -100, 0, 800);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster target = monster(9300170, 99130, new Point(200, 0));
        inject(map, target);
        var status = mock(soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState.class);
        when(status.blocksAttack()).thenReturn(true);
        try (var statuses = mockStatic(soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState.class)) {
            statuses.when(() -> soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState.of(bot)).thenReturn(status);
            assertFalse(BotAttackDriver.canReach(bot, target));
            assertFalse(BotAttackDriver.botAttack(bot).hit());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-150, -145, 149})
    void legalVerticalBoundaryShotsAreNotFilteredOut(int targetY) throws Exception {
        MapleMap map = map(922019982 + targetY + 150);
        floor(map, 1, 0, 0, 300);
        floor(map, 2, 500, targetY, 600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster target = monster(9300170, 99115, new Point(550, targetY));
        inject(map, target);
        assertFalse(GCMovement.canPathTo(bot, 550, targetY));
        assertTrue(BotAttackDriver.firingPositions(bot, target).contains(new Point(200, 0)));
        PqActions.seekAndAttack(bot, true);
        assertNotNull(state.moveTarget);
        Point firing = new Point(state.moveTarget);
        assertEquals(0, firing.y);
        assertTrue(GCMovement.canPathTo(bot, firing.x, firing.y));
        BotPhysicsEngine.teleportTo(state, bot, firing);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, target));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-449, 450})
    void firingBoxAndActualSwingAgreeAtBothHorizontalEdges(int shooterX) throws Exception {
        MapleMap map = map(922019987);
        floor(map, 1, -600, 0, 600);
        createBot(map, new Point(shooterX, 0), Job.HUNTER);
        Monster target = monster(9300170, 99120, new Point(0, 0));
        inject(map, target);
        assertTrue(BotAttackDriver.firingPositions(bot, target).contains(bot.getPosition()));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, target));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
        }
    }

    @Test
    void aTargetThatBecomesUnreachableYieldsToAnotherPathableMob() throws Exception {
        MapleMap map = map(922019986);
        floor(map, 1, -100, 0, 800);
        floor(map, 2, 450, -500, 550);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        Monster first = monster(9300005, 99116, new Point(500, 0));
        inject(map, first);
        PqActions.seekAndAttack(bot);
        assertEquals(new Point(500, 0), state.moveTarget);
        when(first.getPosition()).thenReturn(new Point(500, -500));
        inject(map, monster(9300005, 99117, new Point(600, 0)));
        MovementClock.advance(1250);
        PqActions.seekAndAttack(bot);
        assertEquals(new Point(600, 0), state.moveTarget);
    }

    @Test
    void enteringAttackRangeMidFlightDoesNotRetireTheLandingOrder() throws Exception {
        MapleMap map = map(922019991);
        floor(map, 1, -100, 0, 800);
        createBot(map, new Point(100, -40), Job.HUNTER);
        state.inAir = true;
        inject(map, monster(9300170, 99129, new Point(200, 0)));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            PqActions.seekAndAttack(bot, true);
            assertNotNull(state.moveTarget, "an airborne shot is not a settled firing position");
            assertTrue(state.inAir);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"HUNTER", "CLERIC"})
    void projectilesStillFaceAndHitAForeignLedge(Job job) throws Exception {
        MapleMap map = map(job == Job.HUNTER ? 922019977 : 922019978);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, 200, -40, 300);
        createBot(map, new Point(0, 0), job);
        Monster target = monster(9300005, 99111, new Point(250, -40));
        inject(map, target);
        assertTrue(BotAttackDriver.canReach(bot, target));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.forceSingle(bot).hit(), "route-aware facing preserves cross-ledge shots");
        }
    }

    @Test
    void anUnreachableCrystalNeverArmsAWalkAndCannotBlockTheBoss() throws Exception {
        MapleMap map = map(922019975);
        floor(map, 1, -400, 184, 1200);
        floor(map, 2, 550, 0, 650);
        createBot(map, new Point(-171, 184), Job.BEGINNER);
        Reactor crystal = reactor(2201003, 99104, new Point(599, 0));
        inject(map, crystal);
        assertFalse(GCMovement.canPathTo(bot, 599, 0));
        LudiStages.fightBoss(bot);
        assertNull(state.moveTarget, "summoning is best-effort, never an unreachable approach");
        assertFalse(PqActions.movementShielded(BOT_ID));

        when(crystal.getReactorType()).thenReturn(-1);
        Monster boss = monster(9300012, 99105, new Point(700, 184));
        when(boss.isBoss()).thenReturn(true);
        inject(map, boss);
        LudiStages.fightBoss(bot);
        assertEquals(new Point(700, 184), state.moveTarget, "immediately chase the summoned Boss");
    }

    @Test
    void aCrystalInHonestReachIsStillStruck() throws Exception {
        MapleMap map = map(922019976);
        floor(map, 1, 500, 0, 700);
        createBot(map, new Point(590, 0), Job.BEGINNER);
        inject(map, reactor(2201003, 99106, new Point(599, 0)));
        try (var swings = mockStatic(BotAttack.class); var reactors = mockStatic(CustomReactor.class)) {
            LudiStages.fightBoss(bot);
            reactors.verify(() -> CustomReactor.hitReactorWithScript(map, 99106, bot));
            swings.verify(() -> BotAttack.basicSwing(bot));
        }
        assertNull(state.moveTarget);
        assertFalse(PqActions.movementShielded(BOT_ID));
    }

    @Test
    void aDistantCrystalDoesNotOverwriteAnotherProducersWalk() throws Exception {
        MapleMap map = map(922019979);
        floor(map, 1, -400, 184, 1200);
        floor(map, 2, 550, 0, 650);
        createBot(map, new Point(-171, 184), Job.BEGINNER);
        inject(map, reactor(2201003, 99112, new Point(599, 0)));
        Point pickup = new Point(0, 184);
        GCMovement.move(bot, pickup.x, pickup.y);
        LudiStages.fightBoss(bot);
        assertEquals(pickup, state.moveTarget, "a failed summon attempt owns no movement order");
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"HUNTER"})
    void stage7RangedBotWalksToAFiringPositionAndKeepsItOnCombatBeats(Job job) throws Exception {
        MapleMap map = realMap(922010700);
        createBot(map, new Point(-43, -783), job);
        state.movementProfile = BotMovementProfile.fromCharacter(bot);
        BotNavigationGraphProvider.getGraph(map, state.movementProfile);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            int[][] targets = {{9300170, 224, -1044}, {9300169, 219, -1276}, {9300171, 228, -1543}};
            Monster[] mobs = new Monster[targets.length];
            for (int i = 0; i < targets.length; i++) {
                mobs[i] = monster(targets[i][0], 99107 + i, new Point(targets[i][1], targets[i][2]));
                inject(map, mobs[i]);
            }
            for (int i = 0; i < targets.length; i++) {
                int[] row = targets[i];
                Monster target = mobs[i];
                assertFalse(GCMovement.canPathTo(bot, row[1], row[2]));
                LudiStages.gatherPasses(bot, 7);
                if (!BotAttackDriver.canReach(bot, target)) {
                    assertNotNull(state.moveTarget, "an inaccessible mob floor is not an inaccessible shot");
                    Point firing = new Point(state.moveTarget);
                    assertTrue(GCMovement.canPathTo(bot, firing.x, firing.y));
                    assertNotEquals(target.getPosition(), firing);
                }
                for (int tick = 0; tick < 1200 && !BotAttackDriver.canReach(bot, target); tick++) {
                    if (tick % 5 == 0) PqActions.seekAndAttack(bot);
                    tick();
                }
                assertTrue(BotAttackDriver.canReach(bot, target),
                        "reach the firing ledge for mob " + row[0] + "; ended at " + bot.getPosition());
                assertTrue(BotAttackDriver.forceSingle(bot).hit(), "the selected shot must actually hit");
                PqActions.seekAndAttack(bot);
                assertNull(state.moveTarget, "hold the shot instead of walking onto the mob's ledge");
                when(target.isAlive()).thenReturn(false);
            }
        }
    }

    @Test
    void stage7MeleeBotDoesNotWalkAtAnImpossibleShot() throws Exception {
        MapleMap map = realMap(922010700);
        createBot(map, new Point(-43, -783), Job.BEGINNER);
        inject(map, monster(9300170, 99110, new Point(224, -1044)));
        LudiStages.gatherPasses(bot, 7);
        assertNull(state.moveTarget);
        for (int beat = 0; beat < 20; beat++) {
            MovementClock.advance(250);
            PqActions.seekAndAttack(bot);
            assertNull(state.moveTarget, "the sticky restriction also governs the combat beat");
        }
    }

    /**
     * A cast is not the "ranged personnel" the frame asks for. Stage 7's shelves stay out of a
     * mage's reach: it is never offered a firing ledge, so it never snipes the three Ratz from
     * across the room. (Its own in-range AoE is untouched - only the remote role is refused.)
     */
    @Test
    void aMageIsNeverGivenAStage7FiringPosition() throws Exception {
        MapleMap map = realMap(922010700);
        createBot(map, new Point(-43, -783), Job.CLERIC);
        state.movementProfile = BotMovementProfile.fromCharacter(bot);
        BotNavigationGraphProvider.getGraph(map, state.movementProfile);
        Monster ratz = monster(9300170, 99150, new Point(224, -1044));
        inject(map, ratz);
        assertNull(BotAttackDriver.firingPositions(bot, ratz),
                "a mage gets no remote firing ledge");
        LudiStages.gatherPasses(bot, 7);
        assertNull(state.moveTarget,
                "with no firing ledge there is no shot to walk to, so the mage stays put");
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"HUNTER"})
    void actualAutoBeatsKillAllThreeStage7Targets(Job job) throws Exception {
        MapleMap map = realMap(922010700);
        createBot(map, new Point(-43, -783), job);
        state.movementProfile = BotMovementProfile.fromCharacter(bot);
        BotNavigationGraphProvider.getGraph(map, state.movementProfile);
        Monster[] mobs = {monster(9300170, 99125, new Point(224, -1044)),
                monster(9300169, 99126, new Point(219, -1276)), monster(9300171, 99127, new Point(228, -1543))};
        for (Monster mob : mobs) inject(map, mob);
        AtomicInteger kills = new AtomicInteger();
        org.mockito.stubbing.Answer<Boolean> landHits = inv -> {
            Map<Monster, ?> hits = inv.getArgument(1);
            for (Monster mob : hits.keySet()) {
                if (mob.isAlive()) kills.incrementAndGet();
                doReturn(false).when(mob).isAlive();
            }
            return true;
        };
        try (var effects = mockStatic(BotAttackEffects.class)) {
            effects.when(() -> BotAttackEffects.rangedStrike(eq(bot), anyMap(), anyInt(), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyInt(), anyShort())).thenAnswer(landHits);
            effects.when(() -> BotAttackEffects.magicStrike(eq(bot), anyMap(), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyInt(), anyShort())).thenAnswer(landHits);
            for (int t = 0; t < 1200 && kills.get() < mobs.length; t++) {
                if (t % 5 == 0) {
                    BotAttackDriver.clearBot(BOT_ID); // advance attack cadence without real sleeps
                    if (t == 0) LudiStages.gatherPasses(bot, 7);
                    else PqActions.seekAndAttack(bot);
                }
                tick();
            }
        }
        assertEquals(3, kills.get(), "only actual AUTO target lists retire the mobs, never forceSingle");
    }

    @Test
    void firingSearchResolvesCombatAndMovementOnceInsteadOfPerCandidate() throws Exception {
        MapleMap map = map(922019990);
        floor(map, 1, -100, 0, 100);
        for (int i = 0; i < 12; i++) floor(map, 2 + i, 200 + i * 10, -50, 205 + i * 10);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster target = monster(9300170, 99128, new Point(500, -100));
        try (var config = mockStatic(soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig.class, CALLS_REAL_METHODS);
             var profiles = mockStatic(BotMovementProfile.class, CALLS_REAL_METHODS)) {
            Method search = PqActions.class.getDeclaredMethod("findFiringPosition", Character.class, Monster.class);
            search.setAccessible(true);
            assertNotNull(search.invoke(null, bot, target));
            config.verify(() -> soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig.resolve(any(), any()), times(1));
            profiles.verify(() -> BotMovementProfile.fromCharacter(bot), times(1));
        }
    }

    @Test
    void reachableGroundUsesTheRealSlopeAndVerticalBox() throws Exception {
        MapleMap map = map(922019993);
        map.getFootholds().insert(new Foothold(new Point(0, 0), new Point(300, -150), 1));
        createBot(map, new Point(0, 0), Job.HUNTER);
        var bounds = new java.awt.Rectangle(100, -145, 150, 35);
        Point shot = GCMovement.nearestReachableGround(bot, bounds);
        assertNotNull(shot);
        assertTrue(bounds.contains(shot));
        assertEquals(-shot.x / 2, shot.y, 1);
        assertTrue(GCMovement.canPathTo(bot, shot.x, shot.y));
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"THIEF", "BRAWLER"})
    void theCrossingPolicyOwnsAndRetiresOnlyItsOwnHide(Job job) throws Exception {
        MapleMap map = map(922010506);
        floor(map, 1, -200, 0, 200);
        floor(map, 2, -200, -630, 200);
        createBot(map, new Point(0, 0), job);
        inject(map, monster(9300013, 99131, new Point(0, 0)));
        try (var buffs = mockStatic(BotBuffEffects.class)) {
            buffs.when(() -> BotBuffEffects.showBuff(eq(bot), anyInt())).thenAnswer(inv -> {
                BotAuraState.onAuraShown(bot, inv.getArgument(1));
                return 120000;
            });
            tick();
            assertTrue(BotAuraState.isMonsterImmune(bot));
            BotPhysicsEngine.teleportTo(state, bot, new Point(0, -630));
            tick();
            assertFalse(BotAuraState.isMonsterImmune(bot));
            BotAuraState.onAuraShown(bot, job == Job.THIEF ? 4001003 : 5101007);
            tick();
            assertTrue(BotAuraState.isMonsterImmune(bot), "a separate hide was not granted by the crossing policy");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2000, 6000})
    void guardHideTracksPhysicsRatherThanTheMacroBeat(int macroMs) throws Exception {
        MapleMap map = realMap(922010506);
        createBot(map, new Point(179, -223), Job.THIEF);
        state.movementProfile = BotMovementProfile.fromCharacter(bot);
        BotNavigationGraphProvider.getGraph(map, state.movementProfile);
        inject(map, reactor(2202003, 99108, new Point(-83, -630)));
        Monster guard = monster(9300013, 99109, new Point(62, -443));
        inject(map, guard);
        AtomicInteger contacts = new AtomicInteger();
        Method touching = BotContactDamage.class.getDeclaredMethod("isMobTouchingBot",
                BotMovementState.class, Character.class, Monster.class);
        touching.setAccessible(true);
        var skillData = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.SKILL)
                .getData("400.img").getChildByPath("skill/4001003");
        Method loadSkill = org.gms.client.SkillFactory.class.getDeclaredMethod("loadFromData",
                int.class, org.gms.provider.Data.class);
        loadSkill.setAccessible(true);
        var darkSight = (org.gms.client.Skill) loadSkill.invoke(null, 4001003, skillData);
        try (var damage = mockStatic(BotContactDamage.class, CALLS_REAL_METHODS);
             var skills = mockStatic(org.gms.client.SkillFactory.class, CALLS_REAL_METHODS)) {
            skills.when(() -> org.gms.client.SkillFactory.getSkill(4001003)).thenReturn(darkSight);
            damage.when(() -> BotContactDamage.tickMobDamage(state, bot)).thenAnswer(inv -> {
                if ((boolean) touching.invoke(null, state, bot, guard)) {
                    contacts.incrementAndGet();
                    assertTrue(BotAuraState.isMonsterImmune(bot),
                            "hide must be up BEFORE the touch check at " + bot.getPosition());
                }
                state.lastMobTouchCheckPos = new Point(bot.getPosition());
                state.lastMobTouchMapId = bot.getMapId();
                return null;
            });
            for (int tick = 0; tick < 120; tick++) {
                if (tick * 50 % macroMs == 0) LudiStages.sneakDoorRooms(bot);
                tick();
            }
        }
        assertTrue(contacts.get() > 0, "the simulation must actually cross the guard's body");
        BotPhysicsEngine.teleportTo(state, bot, new Point(-83, -630));
        tick();
        assertFalse(BotAuraState.isMonsterImmune(bot), "the safe box ledge is not a permanent hide");
    }

    /**
     * LPQ stage 4's door rooms are 1866px wide, and the room a bot warps into is NOT always its
     * right-hand spawn ledge: {@code 922010400}'s {@code in01/in02/in05} target the room's
     * {@code st00} (x≈295, right side) while {@code in03/in04} target {@code out00} (x≈-1366, the
     * LEFT exit). The room's only mob stands on the left shelf, so the two spawn-ledge landings
     * sat 914/917px away while the left-exit landings sat 697/726px away. The chase used to filter
     * candidates by {@code |dx| <= 900}, which read exactly those two rooms as "quiet" while
     * {@code LudiStages.workDoorRooms}' whole-map {@code killableMobPresent} still reported work
     * left - the bot neither fought nor left, and only a real player entering the room ever moved
     * it (the host hands mob control to real clients only, so the mob sat still until someone
     * walked in and dragged it into range).
     */
    @ParameterizedTest(name = "room {0}: st00 landing({1},{2}) -> mob({3},{4})")
    @CsvSource({
            "922010401, 295, -79, -619, 225",  // dx 914 - 14px past the old cap
            "922010405, 307, -79, -610, 225"   // dx 917
    })
    void aStage4DoorRoomMobBeyondTheOldHorizontalCapIsStillHunted(int mapId, int entryX, int entryY,
                                                                  int mobX, int mobY) throws Exception {
        MapleMap map = realMap(mapId);
        createBot(map, new Point(entryX, entryY), Job.BEGINNER);
        Monster mob = monster(9300008, 99140 + mapId % 10, new Point(mobX, mobY));
        inject(map, mob);
        assertTrue(Math.abs(mobX - entryX) > 900, "this room only exercises the bug past the old cap");

        PqActions.seekAndAttack(bot);
        assertNotNull(state.moveTarget, "the room's only mob must be chased, not read as quiet");
        assertEquals(mobX, state.moveTarget.x, "the chase must aim at the mob's own floor pixel");
        assertTrue(GCMovement.canPathTo(bot, mobX, mobY), "the mob's shelf must be reachable");

        // End to end: production driver ticks must actually close the gap into attack reach.
        boolean reached = false;
        for (int beat = 0; beat < 400 && !reached; beat++) { // 100s at 250ms/beat
            PqActions.seekAndAttack(bot);
            reached = BotAttackDriver.canReach(bot, mob);
            if (!reached) {
                for (int t = 0; t < 5; t++) tick(); // 250ms of 50ms ticks
            }
        }
        assertTrue(reached, "the bot must walk from the door into the mob's attack reach; ended at "
                + bot.getPosition());
    }

    @Test
    void aStage4LeftExitLandingStillWorks() throws Exception {
        // in03 targets out00 (-1366), 726px from the mob - inside the old cap, so it never stalled.
        MapleMap map = realMap(922010403);
        createBot(map, new Point(-1366, -81), Job.BEGINNER);
        inject(map, monster(9300008, 99145, new Point(-640, 225)));
        PqActions.seekAndAttack(bot);
        assertNotNull(state.moveTarget, "the in-range left-exit landing must keep working");
        assertEquals(-640, state.moveTarget.x);
    }

    @Test
    void aWarpOutOfTheGuardRoomRetiresItsHide() throws Exception {
        MapleMap guardRoom = map(922010506);
        floor(guardRoom, 1, -100, 0, 100);
        createBot(guardRoom, new Point(0, 0), Job.THIEF);
        inject(guardRoom, monster(9300013, 99118, new Point(0, 0)));
        try (var buffs = mockStatic(BotBuffEffects.class)) {
            buffs.when(() -> BotBuffEffects.showBuff(bot, 4001003)).thenAnswer(inv -> {
                BotAuraState.onAuraShown(bot, 4001003);
                return 120000;
            });
            tick();
        }
        assertTrue(BotAuraState.isMonsterImmune(bot));
        MapleMap next = map(922019980);
        floor(next, 1, -100, 0, 100);
        when(bot.getMap()).thenReturn(next); when(bot.getMapId()).thenReturn(next.getId());
        tick();
        assertFalse(BotAuraState.isMonsterImmune(bot));
    }

    @Test
    void aStaleMacroPositionCannotCancelThePhysicsGuardsHide() throws Exception {
        MapleMap map = map(922010506);
        floor(map, 1, -200, -630, 200);
        floor(map, 2, -200, 0, 200);
        createBot(map, new Point(0, -630), Job.THIEF);
        inject(map, monster(9300013, 99119, new Point(0, -443)));
        AtomicReference<Point> position = new AtomicReference<>(new Point(0, -630));
        CountDownLatch sampled = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(bot.getPosition()).thenAnswer(inv -> {
            Point snapshot = position.get();
            if (Thread.currentThread().getName().equals("lpq-macro-review")) {
                sampled.countDown();
                assertTrue(resume.await(5, TimeUnit.SECONDS));
            }
            return snapshot;
        });
        try (var macro = Executors.newSingleThreadExecutor(r -> new Thread(r, "lpq-macro-review"));
             var buffs = mockStatic(BotBuffEffects.class)) {
            buffs.when(() -> BotBuffEffects.showBuff(bot, 4001003)).thenAnswer(inv -> {
                BotAuraState.onAuraShown(bot, 4001003);
                return 120000;
            });
            var work = macro.submit(() -> LudiStages.sneakDoorRooms(bot));
            try {
                assertTrue(sampled.await(5, TimeUnit.SECONDS));
                position.set(new Point(0, -443));
                synchronized (state) {
                    BotAuraState.updateGuardHide(bot);
                }
                assertTrue(BotAuraState.isMonsterImmune(bot));
            } finally {
                resume.countDown();
            }
            work.get(5, TimeUnit.SECONDS);
            assertTrue(BotAuraState.isMonsterImmune(bot), "macro work never owns guard-hide cancellation");
        }
    }

    private void tick() throws Exception {
        ObserverTracker.markObservedNow(bot.getMapId());
        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
        try {
            tick.invoke(null, state);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof AssertionError assertion) throw assertion;
            throw e;
        }
        MovementClock.advance(50);
    }

    private void createBot(MapleMap map, Point initial, Job job) throws Exception {
        // Synthetic rooms reuse some real map ids to exercise map-scoped policy. Their
        // geometry must replace any earlier in-memory graph, never load an unrelated disk cache.
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        AtomicReference<Point> position = new AtomicReference<>(initial);
        bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(BOT_ID); when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId()); when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(40); when(bot.getHp()).thenReturn(5000);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100); when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getPosition()).thenAnswer(inv -> position.get());
        when(bot.getParty()).thenReturn(null); when(bot.getEventInstance()).thenReturn(null);
        when(bot.getInventory(any()).getItem(anyShort())).thenReturn(null);
        when(bot.getClient().getChannel()).thenReturn(1); when(bot.getClient().getWorld()).thenReturn(0);
        doAnswer(inv -> { position.set(new Point(inv.getArgument(0))); return null; })
                .when(bot).setPosition(any());
        state = new BotMovementState(bot, null);
        state.movementProfile = BotMovementProfile.base();
        state.lastMap = map; state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        state.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(state, bot, initial);
        states().put(BOT_ID, state);
    }

    private static MapleMap map(int id) {
        MapleMap map = new MapleMap(id, 0, 0, 922010000, 0f);
        map.setMapLineBoundings(-4000, 4000, -4000, 4000);
        map.setFootholds(new FootholdTree(new Point(-4000, -4000), new Point(4000, 4000)));
        return map;
    }

    private static MapleMap realMap(int id) {
        var source = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
        assumeTrue(source.getData(BotNavigationMapLoader.getMapName(id)) != null, "GMS083 Map.wz required");
        return BotNavigationMapLoader.loadMapGeometry(id);
    }

    private static void floor(MapleMap map, int id, int left, int y, int right) {
        map.getFootholds().insert(new Foothold(new Point(left, y), new Point(right, y), id));
    }

    private static Monster monster(int id, int oid, Point position) {
        Monster mob = mock(Monster.class, RETURNS_DEEP_STUBS);
        when(mob.getId()).thenReturn(id); when(mob.getObjectId()).thenReturn(oid);
        when(mob.getType()).thenReturn(MapObjectType.MONSTER); when(mob.getPosition()).thenReturn(position);
        when(mob.isAlive()).thenReturn(true); when(mob.getName()).thenReturn("regression target");
        return mob;
    }

    private static Reactor reactor(int id, int oid, Point position) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getId()).thenReturn(id); when(reactor.getObjectId()).thenReturn(oid);
        when(reactor.getType()).thenReturn(MapObjectType.REACTOR); when(reactor.getPosition()).thenReturn(position);
        when(reactor.isAlive()).thenReturn(true); when(reactor.isActive()).thenReturn(true);
        when(reactor.getReactorType()).thenReturn(100);
        return reactor;
    }

    @SuppressWarnings("unchecked")
    private static void inject(MapleMap map, MapObject object) throws Exception {
        Field objects = MapleMap.class.getDeclaredField("mapobjects"); objects.setAccessible(true);
        ((Map<Integer, MapObject>) objects.get(map)).put(object.getObjectId(), object);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, BotMovementState> states() throws Exception {
        Field states = GCMovement.class.getDeclaredField("STATES"); states.setAccessible(true);
        return (Map<Integer, BotMovementState>) states.get(null);
    }
}
