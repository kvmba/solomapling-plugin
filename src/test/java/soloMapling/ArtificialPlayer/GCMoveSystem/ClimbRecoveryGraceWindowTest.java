package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@code ClimbRecovery.isRecovering} — the 3s post-dismount grace the macro watchdog reads before
 * escalating (TrainingBot.grindWatchdog / RoamerBot's teleport beat).
 *
 * <p>Both halves matter and both used to be untestable: while climbing OR within 3s of a dismount
 * the watchdog must defer; past the window it must be free to act. A too-long grace leaves a wedged
 * bot un-rescued, a too-short one teleports bots that are mid-recovery. Driven on the shared
 * movement clock so the window elapses with no real waiting.
 */
public class ClimbRecoveryGraceWindowTest {

    private static final int MAP_ID = 920010300;
    private static final int LADDER_X = -44;

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        MovementClock.reset();
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource ms = mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(any(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    @AfterAll
    static void restoreClock() throws Exception {
        MovementClock.reset();
        Field f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        ((Map<?, ?>) f.get(null)).clear();
    }

    private static GameConfigDO config(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t);
        d.setConfigSubType("0");
        d.setConfigCode(k);
        d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private record Sim(GrindBrain brain, org.gms.client.Character bot) {
    }

    private static Sim simOnLadder(int y, int botId) throws Exception {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraphProvider.getGraph(map, profile);
        Rope ladder = null;
        for (Rope r : map.getRopes()) {
            if (r.x() == LADDER_X) {
                ladder = r;
            }
        }
        assertNotNull(ladder, "the 3224px ladder at x=-44");
        AtomicReference<Point> pos = new AtomicReference<>(new Point(LADDER_X, y));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP_ID);
        when(bot.getId()).thenReturn(botId);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getName()).thenReturn("Climber");
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());
        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(LADDER_X, y));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        BotPhysicsEngine.attachToRope(st, bot, ladder, y);
        st.grinding = true;

        Field f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, BotMovementState> states = (Map<Integer, BotMovementState>) f.get(null);
        states.put(bot.getId(), st);
        return new Sim(new GrindBrain(msg -> { }), bot);
    }

    /** While on the rope the watchdog must always defer. */
    @Test
    void aClimberAlwaysReadsAsRecovering() throws Exception {
        MovementClock.useVirtual(1_000_000L);
        Sim sim = simOnLadder(-2000, 501);
        assertTrue(sim.brain().isRecovering(sim.bot()),
                "a bot still on the rope is mid-recovery by definition");
        MovementClock.advance(60_000); // a whole minute later, still climbing
        assertTrue(sim.brain().isRecovering(sim.bot()),
                "climbing alone keeps the watchdog deferring, regardless of elapsed time");
    }

    /**
     * The 3s post-dismount grace: a real dismount arms it, and it lapses on its own. Exercised via
     * the stall net (1.2s of stillness), which is the production path that produces a dismount.
     */
    @Test
    void thePostDismountGraceLapsesAfterThreeSeconds() throws Exception {
        MovementClock.useVirtual(2_000_000L);
        Sim sim = simOnLadder(-2000, 502);

        // park it: 1.25s of stillness trips the 1.2s stall net and dismounts
        for (int i = 0; i < 6; i++) {
            ObserverTracker.markObservedNow(MAP_ID);
            sim.brain().tick(sim.bot());
            MovementClock.advance(250);
        }
        assertFalse(GCMovement.isClimbing(sim.bot()),
                "the stall net must have dismounted the parked climber first");
        assertTrue(sim.brain().isRecovering(sim.bot()),
                "right after a dismount the watchdog must still defer");

        // 2.9s total after the dismount: still inside the 3s grace
        MovementClock.advance(1_650);
        assertTrue(sim.brain().isRecovering(sim.bot()),
                "inside the 3s window the watchdog must keep deferring");

        // past 3s: the grace lapses so escalation can act
        MovementClock.advance(2_000);
        assertFalse(sim.brain().isRecovering(sim.bot()),
                "past the 3s grace the watchdog must be free to escalate");
    }
}
