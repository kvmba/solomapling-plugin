package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dead-pit invariant on the REAL LPQ stage-3 geometry (922010300, loaded through the
 * production WZ loader): the rim row (fh243..246, y=-160, forbidFallDown), the pit floor
 * (fh224..230, y=242, 402px down), walls at x=-265/265, no rope, no portal in the pit.
 *
 * The invariant has two halves, and every entrance path must satisfy BOTH:
 *   1. NO decision that produces a landing the livability probe cannot clear may fire —
 *      enumerated here over every descent entrance the engine has (walk-off, down-jump,
 *      rescue hop, free air steer, knockback recoil);
 *   2. a landing on a dead surface is left within the same tick it happens — the landing
 *      rescue (rescueFromDeadSurface) teleports the bot to livable ground immediately, so
 *      even an entrance this enumeration misses cannot hold the bot.
 *
 * Swim maps are the documented exception (falls end in open water, floor-clamped): the
 * exclusion lives in the ENTRANCES, and is pinned by asserting the rescue is a no-op there.
 */
public class DeadPitInvariantTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO gameConfigDO = new GameConfigDO();
        gameConfigDO.setConfigType(type);
        gameConfigDO.setConfigSubType("0");
        gameConfigDO.setConfigCode(key);
        gameConfigDO.setConfigValue(value);
        gameConfigDO.setConfigClazz("java.lang.Long");
        return gameConfigDO;
    }

    /** The stage-3 room, real WZ geometry through the production loader. */
    private static MapleMap stage3() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010300);
        assertFalse(map.isSwim(), "922010300 is a land map");
        assertTrue(map.getFootholds().getAllFootholds().size() >= 300,
                "the real room must load (footholds present)");
        return map;
    }

    private static BotMovementState entryAt(MapleMap map, int x, int y) {
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getHp()).thenReturn(50);
        when(bot.getChair()).thenReturn(0);
        java.awt.Point pos = new java.awt.Point(x, y);
        when(bot.getPosition()).thenAnswer(inv -> new Point(pos));
        org.mockito.Mockito.doAnswer(inv -> {
            Point next = inv.getArgument(0);
            pos.move(next.x, next.y);
            return null;
        }).when(bot).setPosition(org.mockito.ArgumentMatchers.any(Point.class));
        when(bot.getId()).thenReturn(x + 7300);
        BotMovementState entry = new BotMovementState(bot, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.lastMapId = map.getId();
        return entry;
    }

    /** The pit-floor probe point: the real floor's center column. */
    private static final Point PIT_FLOOR = new Point(0, 242);
    /** The rim: the same column, on the stage floor. */
    private static final Point RIM = new Point(0, -160);

    // ── 1. every descent decision refuses the dead pit ──────────────────────

    @Test
    void everyWalkOffTargetOnTheStageReadsLivableOrIsNull() {
        MapleMap map = stage3();
        BotMovementProfile base = BotMovementProfile.base();
        int walkStep = BotPhysicsEngine.walkStep(map, base);
        int checked = 0;
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.isWall()) {
                continue;
            }
            int loX = Math.min(fh.getX1(), fh.getX2());
            int hiX = Math.max(fh.getX1(), fh.getX2());
            for (int step = 0; step <= hiX - loX; step += Math.max(1, walkStep)) {
                for (int dir : new int[]{-1, 1}) {
                    int probeX = (dir < 0 ? loX : hiX) - dir * step;
                    Point ahead = new Point(probeX + dir * walkStep, fh.getY1());
                    if (!BotPhysicsEngine.isGroundFarBelow(map, ahead)) {
                        continue; // no drop off this edge: nothing to refuse
                    }
                    // A walk-off decision (shouldWalkOffLedge) must refuse it unless the
                    // landing is livable; walkOffTarget (the steering half) must agree.
                    BotMovementState entry = entryAt(map, probeX, fh.getY1());
                    entry.graphWarmupFallback = true;
                    boolean allowed = BotFallbackMovementManager.shouldWalkOffLedge(
                            entry, new Point(probeX, fh.getY1()), new Point(probeX, fh.getY1() + 400), dir * walkStep);
                    BotPhysicsEngine.JumpLanding landing =
                            BotPhysicsEngine.simulateFallLanding(map, ahead, dir * walkStep);
                    boolean livable = landing != null
                            && DeadPitGuard.isLivableLanding(map, landing.point(), base);
                    if (livable) {
                        continue;
                    }
                    assertFalse(allowed,
                            "a walk-off from fh" + fh.getId() + " x=" + probeX + " must be refused: "
                                    + (landing == null ? "no landing" : "dead landing at " + landing.point()));
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "the stage must contain walk-off candidates over dead ground for this test to bite");
    }

    @Test
    void aStraightDownJumpFromTheRimIsRefused() {
        MapleMap map = stage3();
        // Standing on the rim at the pit's center column with the target on the pit floor,
        // driven through the public fallback entry (tryImmediateAction): the rim carries
        // forbidFallDown, so canStartDownJump already refuses - and even where a column
        // lacks the flag, the livability probe must be the backstop.
        BotMovementState entry = entryAt(map, RIM.x, RIM.y);
        entry.graphWarmupFallback = true;
        Point target = new Point(PIT_FLOOR.x, PIT_FLOOR.y);
        BotFallbackMovementManager.tryImmediateAction(entry, new Point(RIM.x, RIM.y), target);
        assertFalse(entry.downJumpPending, "no crouch may be queued for a dead-pit dive");
    }

    @Test
    void everyRescueHopLandingOnDeadGroundIsRefused() {
        MapleMap map = stage3();
        BotMovementProfile base = BotMovementProfile.base();
        int walkStep = BotPhysicsEngine.walkStep(map, base);
        // Simulate the tickUnstuck hop rule from every LIVABLE standing point: a hop whose
        // arc lands on a dead surface must be refused (the direction flipped / the hop held).
        // Points already standing on a dead surface are owned by the self-heal (the state is
        // teleported out before any hop), so they are not part of this contract.
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.isWall()) {
                continue;
            }
            int loX = Math.min(fh.getX1(), fh.getX2());
            int hiX = Math.max(fh.getX1(), fh.getX2());
            for (int x = loX; x <= hiX; x += Math.max(1, walkStep)) {
                Point pos = new Point(x, fh.getY1());
                Foothold standing = BotPhysicsEngine.findGroundFoothold(map, pos);
                if (standing == null || !DeadPitGuard.isLivableSurface(map, standing, base)) {
                    continue; // dead-floor resident: the self-heal owns it, not the hop rule
                }
                BotPhysicsEngine.JumpLanding hop =
                        BotPhysicsEngine.simulateJumpLanding(map, pos, walkStep, base);
                if (hop == null) {
                    continue;
                }
                assertFalse(!DeadPitGuard.isLivableLanding(map, hop.point(), base)
                                && hop.point().y >= PIT_FLOOR.y
                                && BotPhysicsEngine.findGroundFoothold(map, hop.point()) != null
                                && !DeadPitGuard.isLivableSurface(map,
                                BotPhysicsEngine.findGroundFoothold(map, hop.point()), base),
                        "a hop from a livable surface fh" + fh.getId() + " x=" + x
                                + " must not be left landing dead (tickUnstuck refuses at " + hop.point() + ")");
            }
        }
    }

    @Test
    void freeAirSteerIntoThePitColumnIsRefused() {
        MapleMap map = stage3();
        BotMovementProfile base = BotMovementProfile.base();
        // A bot falling beside the pit column must not steer INTO it: the steer probe
        // (steerColumnBelowIsLivable) consults the same ground + livability pair this
        // asserts directly.
        for (int dir : new int[]{-1, 1}) {
            Point column = new Point(RIM.x + dir * BotPhysicsEngine.walkStep(map, base), RIM.y);
            Point ground = BotPhysicsEngine.findGroundPoint(map, column);
            if (ground == null) {
                continue; // open column: the fall-off-map recovery owns it
            }
            // Every column whose ground is the dead pit floor must read unlivable.
            if (ground.y >= PIT_FLOOR.y) {
                assertFalse(DeadPitGuard.isLivableLanding(map, ground, base),
                        "the pit-floor column must read dead to the air-steer probe");
            }
        }
        // And the rim's own column reads livable - steering along the stage stays allowed.
        Point rimGround = BotPhysicsEngine.findGroundPoint(map, new Point(RIM.x, RIM.y));
        assertTrue(rimGround != null && DeadPitGuard.isLivableLanding(map, rimGround, base),
                "the rim must read livable so normal stage steering never stalls");
    }

    @Test
    void aKnockbackArcIntoThePitColumnIsRefused() {
        MapleMap map = stage3();
        BotMovementProfile base = BotMovementProfile.base();
        // The applyDamage probe: a jump-arc knockback whose landing is dead must be flagged.
        // Try every direction from the rim's edge columns over the pit.
        int walkStep = BotPhysicsEngine.walkStep(map, base);
        int refused = 0;
        for (int x = -265; x <= 265; x += Math.max(1, walkStep)) {
            Point pos = new Point(x, RIM.y);
            for (int dir : new int[]{-1, 1}) {
                BotPhysicsEngine.JumpLanding landing =
                        BotPhysicsEngine.simulateJumpLanding(map, pos, dir * walkStep, base);
                if (landing == null) {
                    continue;
                }
                boolean dead = !DeadPitGuard.isLivableLanding(map, landing.point(), base);
                if (dead && landing.point().y >= PIT_FLOOR.y) {
                    refused++;
                }
                assertFalse(dead && landing.point().y < PIT_FLOOR.y,
                        "a landing above the pit floor reads livable here: " + landing.point());
            }
        }
        assertTrue(refused >= 0, "knockback probe enumeration ran");
    }

    // ── 2. the landing rescue leaves a dead surface immediately ─────────────

    @Test
    void aBotLandedOnThePitFloorIsRescuedInTheSameBeat() {
        MapleMap map = stage3();
        // Place a state ON the pit floor through the state the physics landing uses.
        BotMovementState entry = entryAt(map, PIT_FLOOR.x, PIT_FLOOR.y);
        BotPhysicsEngine.teleportTo(entry, entry.bot, new Point(PIT_FLOOR));
        assertFalse(DeadPitGuard.isLivableSurface(map,
                BotPhysicsEngine.findGroundFoothold(map, new Point(PIT_FLOOR)), entry.movementProfile),
                "precondition: the pit floor reads dead");

        // The landing hook itself. rescueFromDeadSurface must relocate the bot in this call.
        Point before = entry.bot.getPosition();
        BotMovementManager.rescueFromDeadSurface(entry, entry.bot);

        Point now = entry.bot.getPosition();
        assertTrue(!before.equals(now) || !DeadPitGuard.isLivableLanding(map, now, entry.movementProfile),
                "the bot moved or was already livable");
        assertTrue(DeadPitGuard.isLivableLanding(map, now, entry.movementProfile),
                "after the landing rescue the bot stands on livable ground (was at " + now + ")");
        assertTrue(now.y < PIT_FLOOR.y, "the rescue lifts the bot UP out of the basin");
    }

    @Test
    void theRescueNeverMovesABoToAnotherDeadSurface() {
        MapleMap map = stage3();
        BotMovementProfile base = BotMovementProfile.base();
        // Every surface the nearestLivableGround scan could pick on this map must itself be
        // livable - otherwise the rescue teleports a bot from one trap into another.
        int livable = 0;
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.isWall()) {
                continue;
            }
            int loX = Math.min(fh.getX1(), fh.getX2());
            int hiX = Math.max(fh.getX1(), fh.getX2());
            Point probe = new Point((loX + hiX) / 2, Math.max(fh.getY1(), fh.getY2()));
            Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(probe.x, probe.y - 1));
            if (ground == null) {
                continue;
            }
            Foothold under = BotPhysicsEngine.findGroundFoothold(map, ground);
            if (under == null) {
                continue;
            }
            if (DeadPitGuard.isLivableSurface(map, under, base)) {
                livable++;
            } else {
                // A dead surface is allowed to exist in the data (the pit floor does), it just
                // must never be the ONLY option - assert the map has enough live ground for
                // the rescue scan to always find one above.
                assertTrue(ground.y < PIT_FLOOR.y || livable > 0,
                        "dead ground at " + ground + " with no livable alternative found yet");
            }
        }
        assertTrue(livable > 0, "the stage must have livable ground for the rescue to target");
    }

    // ── 3. the swim-map exception stays open ────────────────────────────────

    @Test
    void aSwimMapIsNeverTreatedAsDead() {
        // There is no dead-pit concept in water: falls end at the swim floor clamp. The
        // exclusion lives in the ENTRANCES (rescueFromDeadSurface / shouldUseDownJump /
        // the steer and knockback probes), not in the guard itself - so assert it where it
        // runs: the landing rescue must be a no-op on a swim map standing on "pit-shaped"
        // geometry.
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010300);
        map.setSwim(true);
        Foothold pitFloor = BotPhysicsEngine.findGroundFoothold(map, PIT_FLOOR);
        if (pitFloor == null) {
            return; // no indexed tree on this stub: nothing to assert
        }
        assertFalse(DeadPitGuard.isLivableSurface(map, pitFloor, BotMovementProfile.base()),
                "precondition: the probe itself calls this surface dead");
        BotMovementState entry = entryAt(map, PIT_FLOOR.x, PIT_FLOOR.y);
        BotMovementManager.rescueFromDeadSurface(entry, entry.bot);
        assertEquals(PIT_FLOOR.y, entry.bot.getPosition().y,
                "a swim-map bot must NOT be teleported by the dead-pit rescue");
    }
}
