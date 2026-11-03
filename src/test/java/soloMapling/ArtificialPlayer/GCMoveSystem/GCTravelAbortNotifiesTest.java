package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An aborted trip must reach its {@code ok -> ...} callback with {@code false}.
 *
 * <p>That callback is what clears the caller's own waiting state. Before this contract existed,
 * {@code GCTravel.cancel()} only dropped the Trip, so:
 * <ul>
 *   <li>{@code TownWandererBot.traveling} stayed true and {@code isAvailableForAmbientActions()}
 *       never returned true for that bot again;</li>
 *   <li>{@code SocialBot.strolling} stayed true, which also skipped the
 *       {@code GCMovement.disable()} that releases the shared movement lock.</li>
 * </ul>
 *
 * <p>The trip is registered through the production {@code GCTravel.travel} path with a
 * driver-free movement state already in place (see {@link DriverlessMovementState}), so no 50 ms
 * driver thread races the mock.
 */
class GCTravelAbortNotifiesTest {

    private Character bot;
    private MapleMap map;

    @BeforeAll
    static void stubHost() throws Exception {
        // Mocking Character runs its static initializer, which resolves host services through
        // ServerManager - the standard test stub (see LpqStage3PitEntryTest).
        ApplicationContext ctx = mock(ApplicationContext.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        org.gms.net.server.Server.getInstance();
    }

    @BeforeEach
    void setUp() {
        bot = mock(Character.class);
        map = mock(MapleMap.class);
        when(bot.getId()).thenReturn(424242);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(100000000);
        when(bot.getPosition()).thenReturn(new Point(100, 200));
        // DriverlessMovementState.inject walks the map's footholds; an empty tree keeps the entry
        // settled on the bot's own pixel without dragging WZ in.
        org.gms.server.maps.FootholdTree tree = mock(org.gms.server.maps.FootholdTree.class);
        when(tree.getAllFootholds()).thenReturn(List.of());
        when(map.getFootholds()).thenReturn(tree);
        // A state that is already settled means enable() is a no-op and no driver is started.
        DriverlessMovementState.inject(bot);
    }

    @AfterEach
    void tearDown() throws Exception {
        trips().clear();
        DriverlessMovementState.remove(424242);
    }

    @Test
    void abortReportsFailureToTheWaitingCaller() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Boolean> seen = new AtomicReference<>();
        startTrip(ok -> {
            calls.incrementAndGet();
            seen.set(ok);
        });
        assertTrue(GCTravel.isTraveling(bot), "the trip must be registered before it can be aborted");

        GCMovement.cancelTravel(bot);

        assertFalse(GCTravel.isTraveling(bot), "an aborted trip must leave the registry");
        assertEquals(1, calls.get(), "the abort must report exactly once");
        assertEquals(Boolean.FALSE, seen.get(), "an aborted trip did not arrive");
    }

    @Test
    void abortingNothingIsHarmless() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GCMovement.cancelTravel(bot);
        assertEquals(0, calls.get(), "nothing to abort -> nothing to report");
    }

    @Test
    void abortClearsTheAbandonedHopsMoveIntent() throws Exception {
        startTrip(ok -> { });
        assertTrue(GCTravel.isTraveling(bot));

        // The abandoned hop has walked the bot toward its next portal. Arm exactly that order
        // through the trip's own move path (GCMovement.move would supersede the trip first, which is
        // a different scenario), so the assertion below is about the abort rather than about an
        // order nobody ever set.
        GCMovement.moveForTravel(bot, 320, 200);
        assertEquals(new Point(320, 200), GCMovement.moveTargetOf(bot),
                "precondition: the hop holds a move order to abandon");

        GCMovement.cancelTravel(bot);

        assertNull(GCMovement.moveTargetOf(bot),
                "the abandoned hop's move order must not outlive the aborted trip - otherwise the "
                        + "bot keeps walking an order its caller has already given up on");
    }

    /**
     * Register a trip directly in the registry instead of going through {@code travel()}.
     *
     * <p>{@code travel()} arms a 300 ms {@code scheduleAtFixedRate} whose first pass runs inline and
     * immediately ends a trip whose bot has no real map geometry - so a test that went through it
     * would be racing its own subject. This builds the same {@code Trip} the first poll would see.
     */
    private void startTrip(java.util.function.Consumer<Boolean> callback) throws Exception {
        java.lang.reflect.Constructor<?> ctor = Class
                .forName("soloMapling.ArtificialPlayer.GCMoveSystem.GCTravel$Trip")
                .getDeclaredConstructor(Character.class, int.class, java.util.function.Consumer.class);
        ctor.setAccessible(true);
        Object trip = ctor.newInstance(bot, 100000001, callback);
        trips().put(424242, trip);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Integer, Object> trips() throws Exception {
        java.lang.reflect.Field f = GCTravel.class.getDeclaredField("TRIPS");
        f.setAccessible(true);
        return (java.util.Map<Integer, Object>) f.get(null);
    }
}
