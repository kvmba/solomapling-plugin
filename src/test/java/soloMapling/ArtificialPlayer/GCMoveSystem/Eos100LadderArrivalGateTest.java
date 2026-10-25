package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindStyle;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.*;

/**
 * 玩具塔100层 (221024400) 左数第二条梯子 (x=-130, span 858..1119) 的
 * 「跳抓 → 卡在绳上 → 放手掉下来 → 过一会儿被门控拉回锚点平台(瞬移)」回归。
 *
 * <p>这根梯子顶端就在上层 y=856 据点锚点下方 2px，而 {@code CampStrategy.within()} 给了 ±120px 的垂直
 * 宽容（为斜坡/叠层锚点留的）。于是 bot 还在跳抓弧线中（inAir）时策略层就判「已到达」并
 * {@code GCMovement.stop()}：目标与 nav 边被清空，下一 tick 抓住梯子 → 一个<b>无目标的 climber</b>；
 * 而 {@code stop()} 在绳上本就是「显式停驻」（{@code climbPositionHeld}，GM {@code !gcmove stop} 依赖
 * 这个语义），于是 {@code shouldHoldClimbIdle} 每 tick 把它冻住；1.2s 后 {@code ClimbRecovery} 判停滞
 * 踢下绳（「放手掉下来」），落回下层后 off-anchor 门控走回家又卡、再过 2.5s 被 {@code teleportTo}
 * 拉回锚点（「瞬移」）。
 *
 * <p>本测试驱动<b>真实的</b> {@link GrindBrain}（CAMP 策略）与生产 driver tick，不复制策略判定：
 * 它走真实 {@code SpotFinder} 画像、真实 spot 认领、真实 {@code doTravel}。仅把 {@code MapleMap} 做成
 * mock 以便注入活怪（真实地图几何仍由 {@code BotNavigationMapLoader} 提供）。
 */
public class Eos100LadderArrivalGateTest {

    private static final int MAP_ID = 221024400;
    private static final int LADDER_X = -130;
    private static final int ANCHOR_Y = 856;
    private static final int BOT_ID = 777;

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        MovementClock.useVirtual(1_000_000L);
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class),
                org.mockito.ArgumentMatchers.any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("messageSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("logSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("exceptionSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(),
                "Map.wz not resolvable from this working directory (sim needs the real WZ)");
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    static boolean mapWzAvailable() {
        try {
            org.gms.provider.DataProvider mapSource =
                    org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return mapSource.getData("Map/Map2/221024400.img") != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos, GrindBrain brain) {
        Point p() {
            return pos.get();
        }
    }

    private static final Method TICK;
    private static final Field STATES;

    static {
        try {
            TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            TICK.setAccessible(true);
            STATES = GCMovement.class.getDeclaredField("STATES");
            STATES.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** A live hostile standing on the y=856 row — the natural camp feed of this floor. */
    private static Monster mob(AtomicReference<Point> at) {
        Monster m = mock(Monster.class, RETURNS_DEEP_STUBS);
        when(m.isAlive()).thenReturn(true);
        when(m.getId()).thenReturn(3110102);
        when(m.getObjectId()).thenReturn(555);
        when(m.getType()).thenReturn(org.gms.server.maps.MapObjectType.MONSTER);
        when(m.getStats().isFriendly()).thenReturn(false);
        when(m.getStats().isBoss()).thenReturn(false);
        when(m.getStats().isUndead()).thenReturn(false);
        when(m.isBoss()).thenReturn(false);
        when(m.getExp()).thenReturn(65);
        when(m.getLevel()).thenReturn(32);
        when(m.getName()).thenReturn("Toy Trooper");
        when(m.getPosition()).thenAnswer(inv -> at.get());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Sim sim(int spawnX, int spawnY) {
        MapleMap real = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        BotNavigationGraphProvider.getGraph(real, profile);

        // The map is the REAL WZ-loaded object with the live feed injected through the host's own
        // collections - NOT a Mockito mock delegating to it by reflection. That mock made every
        // geometry query pay a Class.getMethod dispatch, and the collision probes call
        // getFootholds() once per candidate: JFR attributed 68% of this test's samples to mock
        // dispatch, ~50s of pure overhead per run. It also meant the production tick ran against a
        // mock instead of the map it ships against. loadMapGeometry() returns a fresh map per sim,
        // so there is never a previous feed to clear.
        MapleMap map = real;
        for (Monster injected : MOBS) {
            map.addMapObject(injected);
        }
        // The camp spot needs four same-ledge spawns on the y=856 row. The loader does not read the
        // WZ life list, so the row is seeded through the host's own spawn collection - the same
        // addAllMonsterSpawn the world server uses when it populates a map.
        for (Point spawn : SPAWNS) {
            Monster marker = mock(Monster.class);
            when(marker.getId()).thenReturn(3110102);
            when(marker.isMobile()).thenReturn(true);
            when(marker.getPosition()).thenReturn(new Point(spawn));
            map.addAllMonsterSpawn(marker, -1, 0);
        }

        AtomicReference<Point> pos = new AtomicReference<>(new Point(spawnX, spawnY));
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP_ID);
        when(bot.getId()).thenReturn(BOT_ID);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getLevel()).thenReturn(40);
        when(bot.getName()).thenReturn("tester");
        // No equipped weapon: BotAttackDriver resolves a null profile and returns reach 0 before it
        // touches the DB-backed ItemInformationProvider (same contract as LpqLiveMobChaseRealSeekTest).
        when(bot.getInventory(any())).thenReturn(mock(org.gms.client.inventory.Inventory.class, RETURNS_DEEP_STUBS));
        when(bot.getInventory(any()).getItem(anyShort())).thenReturn(null);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(real);
        st.grinding = true;
        BotPhysicsEngine.teleportTo(st, bot, new Point(spawnX, spawnY));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        try {
            ((Map<Integer, BotMovementState>) STATES.get(null)).put(BOT_ID, st);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        GrindBrain brain = new GrindBrain(msg -> { });
        brain.forceStyle(GrindStyle.CAMP); // the reported bot is a camp grinder
        return new Sim(st, pos, brain);
    }

    private static final List<Monster> MOBS = new ArrayList<>();
    private static final List<Point> SPAWNS = new ArrayList<>();

    /** The grind profile is cached per map id; the injected feed must build a fresh one. */
    private static void resetProfileCache() {
        try {
            Field cache = Class.forName("soloMapling.ArtificialPlayer.BotGrindSystem.SpotFinder")
                    .getDeclaredField("CACHE");
            cache.setAccessible(true);
            ((Map<?, ?>) cache.get(null)).remove(MAP_ID);
            Field claims = Class.forName("soloMapling.ArtificialPlayer.BotSpotClaims")
                    .getDeclaredField("CLAIMS");
            claims.setAccessible(true);
            ((Map<?, ?>) claims.get(null)).remove(MAP_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One live mob on the anchor row, left of the ladder. */
    private static void seedAnchorRowFeed() {
        MOBS.clear();
        SPAWNS.clear();
        AtomicReference<Point> at = new AtomicReference<>(new Point(-218, 856));
        MOBS.add(mob(at));
        // Four spawn entries on the y=856 row: SpotFinder's campability floor is 4 same-ledge
        // spawns, so the anchor row is the map's camp spot (and the y=1071 shelf below is not).
        SPAWNS.add(new Point(-218, 856));
        SPAWNS.add(new Point(-120, 856));
        SPAWNS.add(new Point(0, 856));
        SPAWNS.add(new Point(129, 856));
        resetProfileCache();
    }

    private static void tick(Sim sim, int i) {
        // The grind tick is gated to the observed map; mark it like a real player standing there.
        if (i % 20 == 0) {
            ObserverTracker.markObservedNow(MAP_ID);
        }
        try {
            TICK.invoke(null, sim.st());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
        MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
    }

    private static Rope ladder(MapleMap map) {
        for (Rope r : map.getRopes()) {
            if (r.x() == LADDER_X) {
                return r;
            }
        }
        throw new IllegalStateException("the second-from-left ladder (x=" + LADDER_X + ") is missing on " + MAP_ID);
    }

    /**
     * THE REPORTED WEDGE, driven end-to-end through the production grind brain: the bot starts on the
     * y=1032 shelf below the anchor row, the CAMP spot is the y=856 ledge, and the only route up is the
     * jump-grab onto x=-130. The brain must get the bot onto the anchor row without ever leaving it
     * parked on the ladder.
     */
    @Test
    void campTravelUpTheSecondLadderLandsOnTheAnchorRow() {
        seedAnchorRowFeed();
        Sim sim = sim(-99, 1032);
        sim.brain().start(sim.st().bot); // SELECT_SPOT -> claim -> TRAVEL (production entry)

        soloMapling.ArtificialPlayer.BotGrindSystem.Spot picked =
                soloMapling.ArtificialPlayer.BotGrindSystem.SpotFinder.profile(sim.st().bot.getMap()).spots().stream()
                        .filter(sp -> Math.abs(sp.anchor().y - ANCHOR_Y) <= 8).findFirst().orElse(null);
        assertTrue(picked != null, "precondition: the injected feed makes the y=856 row a camp spot");

        // The symptom is a FROZEN CLIMB, not just a bad end state: without the gate the bot grabs
        // the ladder with its goal already cleared, so it hangs pixel-still (y never changes) while
        // ClimbRecovery's 1.2s net counts down, then gets kicked off and eventually reaches the row
        // anyway via the watchdog. So track the longest stretch of a climbing body that does not
        // move vertically at all.
        int frozenClimbTicks = 0;
        int worstFrozenStretch = 0;
        Integer climbY = null;
        for (int beat = 0; beat < 80; beat++) { // 20s of 250ms grind beats
            sim.brain().tick(sim.st().bot);
            for (int i = 0; i < 5; i++) { // 5 driver ticks per beat
                tick(sim, beat * 5 + i);
                BotMovementState s = sim.st();
                if (s.climbing && climbY != null && climbY.equals(s.bot.getPosition().y)) {
                    frozenClimbTicks++;
                    worstFrozenStretch = Math.max(worstFrozenStretch, frozenClimbTicks);
                } else {
                    frozenClimbTicks = 0;
                }
                climbY = s.climbing ? s.bot.getPosition().y : null;
            }
        }
        BotMovementState st = sim.st();
        // A frozen climb is the reported hang: 5 ticks (250ms) is already a visible statue, and the
        // pre-fix tree freezes for 1.2s+ (24 ticks) until ClimbRecovery dismounts it.
        assertTrue(worstFrozenStretch < 5,
                "the camp travel must not freeze on the ladder (froze " + worstFrozenStretch
                        + " ticks without vertical progress; ended at " + sim.p() + ")");
        assertTrue(Math.abs(sim.p().y - ANCHOR_Y) <= 8,
                "it must reach the anchor row (y=" + ANCHOR_Y + "), ended at " + sim.p());
    }

    /**
     * The arrival predicate itself: an on-rope or mid-arc body is NOT settled, while a standing one is.
     * This is what {@code CampStrategy}/{@code StackStrategy} now gate on — and what keeps {@code stop()}
     * (which on a rope means "hang here", GM {@code !gcmove stop}) out of the travel path.
     */
    @Test
    void isSettledRefusesRopeAndAirBodies() {
        seedAnchorRowFeed();
        Sim sim = sim(20, 1276);
        MapleMap real = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        assertTrue(GCMovement.isSettled(sim.st().bot), "a standing bot is settled");

        BotPhysicsEngine.attachToRope(sim.st(), sim.st().bot, ladder(real), 1000);
        assertFalse(GCMovement.isSettled(sim.st().bot),
                "a climber must not read as arrived — this is the gate the travel beats were missing");

        GCMovement.stop(sim.st().bot);
        assertTrue(sim.st().climbPositionHeld,
                "stop() on a rope is the explicit hang contract (GM !gcmove stop), which is why travel "
                        + "must never call it mid-climb");
    }
}
