package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.constants.game.CharacterStance;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the pose of a bot suspended at the map-entry float point.
 *
 * <p>The float is what every observer sees right after a warp: the room is told
 * {@code SPAWN_PLAYER(enteringField=true)} (y - 42, stance 6) and the bot hangs there for the
 * "client load" beat before the natural fall.
 *
 * <p>The reported defect: a player who entered the destination map during that beat saw the bot
 * <em>hovering in a standing (or walking) pose</em>. Measured on a real map by decoding the frames
 * the driver actually emitted:
 *
 * <ul>
 *   <li>{@code Character.stance} at arrival was STAND (4) with the float armed - the pose the
 *       character carried was never JUMP, because {@code resolveStance} keyed on {@code inAir} and
 *       both float paths clear it first ({@code clearMovementState} in {@code teleportTo},
 *       {@code idleOnGround} in the hold);</li>
 *   <li>on an observed map the <em>packet</em> still carried JUMP (6), because
 *       {@code movementSnapshot()} re-derives and writes the stance on the way out - so existing
 *       observers were fine;</li>
 *   <li>a player joining then receives {@code sendSpawnData -> spawnPlayerMapObject(enteringField =
 *       false)}, which writes {@code chr.getStance()} verbatim, i.e. the stale STAND.</li>
 * </ul>
 *
 * <p>So the defect lives in the CHARACTER field, not in the broadcast: the pose must be owned by
 * the state, not repaired as a side effect of emitting a packet. The fix keys both wire bytes on
 * one predicate ({@code inAir || portalDropAtMs > 0}), so the arrival frame, the per-tick hold, the
 * broadcast snapshot, and the foothold byte all agree.
 *
 * <p>A real {@code MapleMap}/{@code Character} cannot be built in a unit test (their constructors
 * trip the Spring-backed {@code Server} static initializer; the same constraint as
 * {@code GroundSwayTest} / {@code EntryRaiseTest}), so both are mocks and the test owns the stance
 * field - the shape {@code RopeMidArrivalTest} uses for its bot.
 */
class EntryHoverPoseTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
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
    static void restoreClock() {
        MovementClock.reset();
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

    private static final class Fixture {
        final AtomicReference<Point> pos;
        final AtomicReference<Integer> stance = new AtomicReference<>(0);
        final Character bot;
        final BotMovementState state;

        Fixture(Point start) {
            MapleMap map = mock(MapleMap.class, RETURNS_DEEP_STUBS);
            pos = new AtomicReference<>(new Point(start));
            bot = mock(Character.class, RETURNS_DEEP_STUBS);
            when(bot.getMap()).thenReturn(map);
            when(bot.getMapId()).thenReturn(922010500);
            when(bot.getId()).thenReturn(4242);
            when(bot.getHp()).thenReturn(5000);
            when(bot.getChair()).thenReturn(0);
            when(bot.getPosition()).thenAnswer(inv -> pos.get());
            doAnswer(inv -> {
                pos.set(new Point((Point) inv.getArgument(0)));
                return null;
            }).when(bot).setPosition(any());
            when(bot.getStance()).thenAnswer(inv -> stance.get());
            doAnswer(inv -> {
                stance.set(inv.getArgument(0));
                return null;
            }).when(bot).setStance(any(Integer.class));
            when(bot.isFacingLeft()).thenReturn(false);

            state = new BotMovementState(bot, null);
            state.movementProfile = BotMovementProfile.base();
            state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
            BotPhysicsEngine.teleportTo(state, bot, start);
        }

        boolean jumpPose() {
            return CharacterStance.isJumping(stance.get());
        }
    }

    /** The pose is JUMP for the whole hold, and it lives in the character (not in a packet). */
    @Test
    void theArmedFloatCarriesTheJumpPoseInTheCharacter() {
        Fixture f = new Fixture(new Point(-208, -219));
        // Walk in with a WALK pose: that is the pose the reported case carried into the float.
        f.bot.setStance(CharacterStance.WALK_RIGHT_STANCE);

        // The driver arms the drop BEFORE it raises the bot (so the freeze-window is always visible
        // as "pending drop"); this is the same order.
        f.state.portalDropAtMs = 1_000_000L;
        Point raised = GCMovement.entrySpawnPoint(f.pos.get());
        BotPhysicsEngine.teleportTo(f.state, f.bot, raised);
        assertTrue(f.jumpPose(),
                "the arrival frame must already be the jump pose: a player entering during the "
                        + "hold reads Character.getStance() verbatim from sendSpawnData; got "
                        + f.stance.get());

        // The hold branch itself: idleOnGround clears inAir and syncs the stance every tick.
        BotPhysicsEngine.idleOnGround(f.state, f.bot);
        assertTrue(f.jumpPose(),
                "the per-tick hold must keep the jump pose in the character, even though nobody "
                        + "observes the map and no broadcast is built; got " + f.stance.get());
        assertEquals(raised, f.bot.getPosition(), "the hold must not move the bot");
    }

    /** A grounded bot (no pending drop) must still resolve to STAND - the gate is the drop, not inAir. */
    @Test
    void aGroundedBotIsNotAffectedByTheHoverRule() {
        Fixture f = new Fixture(new Point(-208, -219));
        f.state.portalDropAtMs = 0L;
        BotPhysicsEngine.idleOnGround(f.state, f.bot);
        assertTrue(CharacterStance.isStanding(f.stance.get()),
                "an ordinary idle bot stands; got " + f.stance.get());
    }

    /**
     * The wire foothold must use the SAME "off the ground" predicate as the wire stance.
     *
     * <p>A bot at the map-entry float carries a pending drop, not {@code inAir}. If the stance says
     * JUMP while the fh field still resolves a real ground id, the client snaps the sprite onto that
     * foothold's footing and takes its page - the pose and the id contradict each other in one frame.
     */
    @Test
    void theWireFootholdAgreesWithTheSuspendedPose() {
        Fixture f = new Fixture(new Point(-208, -219));
        f.state.portalDropAtMs = 1_000_000L;
        BotPhysicsEngine.idleOnGround(f.state, f.bot);
        // The bot is not inAir, so a naive fh rule would answer "land" and look the ground up.
        assertFalse(f.state.inAir, "the float is represented by the pending drop here");

        boolean airborne = BotPhysicsEngine.isAirbornePose(f.state);
        assertTrue(airborne, "the float is an airborne pose for the wire");
        assertEquals(0, BotMovementManager.resolveWireFh(false, airborne, false, 0, 309),
                "a suspended bot must send fh 0, never the ground id under it");
    }

    /** The release hands the bot to the fall (already JUMP) and the landing puts it back to STAND. */
    @Test
    void theReleaseAndLandingRestoreTheGroundPoses() {
        Fixture f = new Fixture(new Point(-208, -219));
        f.state.portalDropAtMs = 1_000_000L;
        BotPhysicsEngine.idleOnGround(f.state, f.bot);
        assertTrue(f.jumpPose());

        // Release: the drop is consumed and the fall begins.
        f.state.portalDropAtMs = 0L;
        BotPhysicsEngine.beginPortalDrop(f.state, f.bot, f.pos.get());
        assertTrue(f.jumpPose(), "the fall itself is a jump pose");

        BotPhysicsEngine.idleOnGround(f.state, f.bot);
        assertTrue(CharacterStance.isStanding(f.stance.get()),
                "after landing the bot stands; got " + f.stance.get());
    }
}
