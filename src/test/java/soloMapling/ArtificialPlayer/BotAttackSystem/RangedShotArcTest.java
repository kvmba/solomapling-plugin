package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.WeaponType;
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
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The shot's arc: a projectile or single-target spell cannot hit a body almost straight above or below.
 *
 * <p>Before the fix the reach box was a bare rectangle around the bot, so a bowman/crossbowman/
 * gunslinger/hermit bot fired at any mob inside it - including one standing on the platform
 * directly overhead (dx ~ 0, dy up to the profile's vertical reach). The client draws those shots
 * flying along the shooter's row, so the hit read as hitting through the floor. Single-target magic
 * shares this limit; AoE spells may legitimately come straight down (Meteor Shower, Heal).
 */
class RangedShotArcTest {

    private static final int BOT_ID = 893001;
    private Character bot;
    private MapleMap map;
    private WeaponType weapon;
    private MockedStatic<BotAttack> attacks;

    /** The Job enum labels itself through the host's message source; boot it like the PQ tests do. */
    @BeforeAll
    static void bootHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
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
    }

    @AfterEach
    void release() {
        if (attacks != null) {
            attacks.close();
        }
        BotAttackDriver.clearBot(BOT_ID);
    }

    @Test
    void aMobDirectlyOverheadIsNotAHit() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster overhead = inject(monster(9300170, 99401, new Point(0, -140)));
        assertFalse(BotAttackDriver.canReach(bot, overhead),
                "a shot cannot climb straight up: dx=0 dy=-140 is not a hit");
        assertFalse(BotAttackDriver.botAttack(bot).hit(), "and the swing must not fire at it either");
    }

    @Test
    void aMobDirectlyBelowIsNotAHitEither() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster below = inject(monster(9300170, 99402, new Point(0, 140)));
        assertFalse(BotAttackDriver.canReach(bot, below),
                "a shot flies level, not down the shaft: dx=0 dy=140 is not a hit");
    }

    /** The near-vertical lane is what the regression is about; a genuine shelf shot must survive. */
    @Test
    void aShelfShotWithEnoughHorizontalOffsetStillLands() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster shelf = inject(monster(9300170, 99403, new Point(300, -70)));
        assertTrue(BotAttackDriver.canReach(bot, shelf),
                "300 across and 70 up is a real shelf shot within 20 degrees");
    }

    /** Just inside the box but on the same row: the ordinary case is untouched. */
    @Test
    void aSameRowShotAtFullReachStillLands() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster far = inject(monster(9300170, 99404, new Point(440, 0)));
        assertTrue(BotAttackDriver.canReach(bot, far), "dx=440 dy=0 is a plain level shot");
    }

    /**
     * The arc threshold is 20 degrees. Cases are chosen to sit
     * strictly INSIDE the reach box (reachY=150 for the single profile) so each one can only pass
     * or fail on the arc, never on the box.
     */
    @ParameterizedTest(name = "dx={0} dy={1} legal={2}")
    @CsvSource({
            "300, 109, true",  // atan(109/300) = 19.97 degrees
            "300, 108, true",
            "300, 110, false", // atan(110/300) = 20.14 degrees
            "200, 72, true",
            "200, 73, false",
            "400, 145, true",
            "400, 146, false",
    })
    void theArcThresholdIsExact(int dx, int dy, boolean legal) throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster mob = inject(monster(9300170, 99420 + dx + dy, new Point(dx, -dy)));
        assertEquals(legal, BotAttackDriver.canReach(bot, mob),
                "dx=" + dx + " dy=" + dy + " (maximum angle is 20 degrees)");
    }

    /**
     * The arc judges the mob's FOOTHOLD point and reads NO boss pad - the foothold IS the body's
     * closest edge across a platform gap, so any pad would let a short boss pass at a steep
     * angle. A Zakum-scale body is reached the honest way: stand beside it (dy≈0) and let the
     * reach BOX's pad confirm the swing, not the arc.
     */
    @Test
    void aBossStillNeedsTheArcBeyondItsPadding() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster boss = bossMonster(9300012, 99430, new Point(0, -500), 400);
        inject(boss);
        assertFalse(BotAttackDriver.canReach(bot, boss),
                "the arc reads the foothold: dy=500 at dx=0 is far outside the wedge");
    }

    @Test
    void aBossOverheadIsNotReachableEvenWithABody() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster boss = bossMonster(9300012, 99405, new Point(0, -260), 400);
        inject(boss);
        assertFalse(BotAttackDriver.canReach(bot, boss),
                "a 400px body does not buy the arc: dy=260 at dx=0 is a vertical shot");
    }

    /** A boss standing BESIDE the shooter (dy≈0, the Zakum altar case) is the legal hit. */
    @Test
    void aBossBesideTheShooterIsReachable() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster boss = bossMonster(9300012, 99434, new Point(150, 0), 400);
        inject(boss);
        assertTrue(BotAttackDriver.canReach(bot, boss),
                "dy=0 at dx=150: the shared foothold shot the Zakum floor fight is");
    }

    /**
     * THE REGRESSION: Monster Carnival's arena mobs carry the WZ boss flag at 61-83px tall on
     * 66-91px shelf gaps. Any pad canceling dy re-admitted near-vertical shots; the arc now
     * demands a shot within 20 degrees at every vertical gap.
     */
    @Test
    void aSmallBossFlaggedMobDirectlyOverheadIsNotAHit() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster arenaMob = bossMonster(9300315, 99431, new Point(0, -91), 83);
        inject(arenaMob);
        assertFalse(BotAttackDriver.canReach(bot, arenaMob),
                "dy=91 at dx=0: a shot through the shelf, not a hit");
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"HUNTER", "FP_WIZARD", "IL_WIZARD", "CLERIC"})
    void aSmallBossFlaggedMobNeedsTheShallowAngleLikeAnyMob(Job job) throws Exception {
        createBot(job, new Point(0, 0));
        Monster arenaMob = bossMonster(9300315, 99432, new Point(60, -91), 83);
        inject(arenaMob);
        assertFalse(BotAttackDriver.canReach(bot, arenaMob),
                "dy=91 needs dx>=251; dx=60 is still a steep shot through the platform edge");
        assertFalse(BotAttackDriver.forceSingle(bot).hit(), "forced single attacks obey the same arc");
        assertFalse(BotAttackDriver.botAttack(bot).hit(), "AUTO must not fire at the steep target either");
        // Move one mob: a pack would legitimately switch a wizard to arc-exempt magic AoE.
        when(arenaMob.getPosition()).thenReturn(new Point(250, -91));
        assertFalse(BotAttackDriver.canReach(bot, arenaMob), "the boss flag cannot widen the 20-degree arc");
        when(arenaMob.getPosition()).thenReturn(new Point(251, -91));
        assertTrue(BotAttackDriver.canReach(bot, arenaMob),
                "dy=91 at dx=251 is just inside 20 degrees, boss flag or not");
    }

    @ParameterizedTest
    @CsvSource({
            "MAGICIAN, 15", "FP_WIZARD, 40", "IL_WIZARD, 40", "CLERIC, 40",
            "FP_MAGE, 70", "IL_MAGE, 70", "PRIEST, 70",
            "FP_ARCHMAGE, 120", "IL_ARCHMAGE, 120", "BISHOP, 120"
    })
    void aSingleTargetMageSharesTheArc(Job job, int level) throws Exception {
        createBot(job, new Point(0, 0));
        when(bot.getLevel()).thenReturn(level);
        Monster overhead = inject(monster(9300170, 99406, new Point(0, -100)));
        assertTrue(BotAttackDriver.isArcLimited(bot), "the chase must respect the single-target spell's arc");
        assertFalse(BotAttackDriver.canReach(bot, overhead), "single-target magic cannot hit straight overhead");
        assertFalse(grindInAttackRange(overhead), "the chase must not hold for an illegal single-target spell");
        BotAttackDriver.AttackResult forced = BotAttackDriver.forceSingle(bot);
        assertFalse(forced.hit());
        assertTrue(forced.reason().contains("outside the shot's arc"), forced.reason());
        assertFalse(BotAttackDriver.botAttack(bot).hit(), "AUTO obeys the same single-target arc");
    }

    @ParameterizedTest(name = "mage dx={0} dy={1} legal={2}")
    @CsvSource({
            "300, -109, true", "300, -110, false",
            "-300, -109, true", "-300, -110, false",
            "300, 109, true", "300, 110, false",
            "-300, 109, true", "-300, 110, false",
            "0, 140, false", "440, 0, true", "-440, 0, true"
    })
    void aMageSharesTheExactArcInEveryDirection(int dx, int dy, boolean legal) throws Exception {
        createBot(Job.CLERIC, new Point(0, 0));
        Monster mob = inject(monster(9300170, 99436, new Point(dx, dy)));
        assertEquals(legal, BotAttackDriver.canReach(bot, mob), "magic uses the same 20-degree arc");
    }

    @ParameterizedTest
    @CsvSource({"HUNTER, 109, true", "HUNTER, 110, false", "CLERIC, 109, true", "CLERIC, 110, false"})
    void theChaseAndSwingShareTheTwentyDegreeBoundary(Job job, int dy, boolean legal) throws Exception {
        createBot(job, new Point(0, 0));
        Monster mob = inject(monster(9300170, 99443, new Point(300, -dy)));
        assertEquals(legal, BotAttackDriver.canReach(bot, mob));
        assertEquals(legal, grindInAttackRange(mob), "the chase must agree at both sides of the 20-degree boundary");
    }

    @ParameterizedTest
    @CsvSource({
            "IL_WIZARD, 40", "FP_MAGE, 70", "IL_MAGE, 70", "PRIEST, 70",
            "FP_ARCHMAGE, 120", "IL_ARCHMAGE, 120", "BISHOP, 120"
    })
    void aMageAoeCanStillHitAnOverheadPack(Job job, int level) throws Exception {
        createBot(job, new Point(0, 0));
        when(bot.getLevel()).thenReturn(level);
        Monster first = inject(monster(9300170, 99437, new Point(0, -100)));
        Monster second = inject(monster(9300170, 99438, new Point(30, -110)));
        assertTrue(BotAttackDriver.canReach(bot, first), "AUTO AoE is not limited by the single-target arc");
        assertTrue(BotAttackDriver.canReach(bot, second), "the overhead pack remains inside the AoE");
        assertTrue(grindInAttackRange(first), "the chase must allow the AUTO AoE instead of closing on the pack");
    }

    @Test
    void healCanStillDamageAnUndeadMobDirectlyOverhead() throws Exception {
        createBot(Job.CLERIC, new Point(0, 0));
        Monster undead = inject(monster(9300170, 99439, new Point(0, -100)));
        when(undead.getStats().isUndead()).thenReturn(true);
        assertTrue(BotAttackDriver.canReach(bot, undead), "Heal is an AoE, not a single-target bolt");
        assertTrue(grindInAttackRange(undead), "the chase must hold for Heal even when the bolt's arc fails");
    }

    /**
     * The remote-firing search returns a spatial PREFILTER, not the legal set: no rectangle can
     * express the 20-degree wedge (see BotAttackDriver.firingPositions).
     * The box must stay on the bot's own side of the
     * target so walking to it never crosses the target.
     */
    @Test
    void firingBoxStaysOnTheBotsOwnSideOfTheTarget() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster target = inject(monster(9300170, 99407, new Point(600, -400)));
        Rectangle shooters = BotAttackDriver.firingPositions(bot, target);
        assertNotNull(shooters);
        assertTrue(shooters.width > 0 && shooters.height > 0, "never a degenerate box");
        assertFalse(shooters.contains(new Point(600, -400)), "never stand on the target's own column");
        assertTrue(shooters.x + shooters.width <= 600,
                "the whole box is on the bot's side, so walking to it never crosses the target");
        assertTrue(shooters.contains(new Point(200, -400)),
                "the lateral band on the bot's own side stays available");
    }

    /**
     * The prefilter must never DROP a legal shot: every point the arc allows inside reach must be
     * inside the box, or the ledge search would rule out a position the swing would have accepted.
     * (The other direction - the box also contains illegal points - is by design; PqActions
     * filters the point it picks with withinRangedArc.)
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void thePrefilterBoxNeverDropsALegalShot(int side) throws Exception {
        createBot(Job.HUNTER, new Point(side < 0 ? 0 : 1200, 0));
        Monster target = inject(monster(9300170, 99408, new Point(600, -400)));
        Rectangle shooters = BotAttackDriver.firingPositions(bot, target);
        Point tp = target.getPosition();
        assertTrue(BotAttackDriver.isArcLimited(bot), "a bowman's shot is arc-limited");
        // dx starts at 1: dx=0 is legal only at dy=0, i.e. the target's own pixel, which is not
        // a position to shoot FROM (and the box deliberately stops at the target's column).
        for (int dx = 1; dx <= 450; dx++) {
            if (side < 0 && dx == 450) continue; // the forward reach box excludes its right edge
            for (int dy = -150; dy < 150; dy += 5) {
                Point shooter = new Point(tp.x + side * dx, tp.y - dy);
                if (!BotAttackDriver.withinRangedArc(shooter, tp, target)) continue;
                assertTrue(shooters.contains(shooter),
                        "legal shot at side=" + side + " dx=" + dx + " dy=" + dy + " was pruned");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"CROSSBOWMAN", "GUNSLINGER", "ASSASSIN"})
    void everyProjectileClassSharesTheArc(Job job) throws Exception {
        createBot(job, new Point(0, 0));
        Monster overhead = inject(monster(9300170, 99410, new Point(0, -120)));
        assertFalse(BotAttackDriver.canReach(bot, overhead), job + " must not hit a body straight overhead");
        Monster inside = inject(monster(9300170, 99441, new Point(300, -109)));
        Monster outside = inject(monster(9300170, 99442, new Point(300, -110)));
        assertTrue(BotAttackDriver.canReach(bot, inside), job + " must accept a shot just inside 20 degrees");
        assertFalse(BotAttackDriver.canReach(bot, outside), job + " must reject a shot just outside 20 degrees");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private boolean grindInAttackRange(Monster mob) throws Exception {
        Method inRange = GrindBrain.class.getDeclaredMethod("inAttackRange", Character.class, Monster.class);
        inRange.setAccessible(true);
        return (boolean) inRange.invoke(new GrindBrain(null), bot, mob);
    }

    private void createBot(Job job, Point at) throws Exception {
        map = new MapleMap(922019995, 0, 0, 922010000, 0f);
        map.setMapLineBoundings(-2000, 2000, -2000, 2000);
        map.setFootholds(new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000)));
        map.getFootholds().insert(new Foothold(new Point(-600, 0), new Point(900, 0), 1));
        AtomicReference<Point> position = new AtomicReference<>(at);
        bot = mock(Character.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        weapon = switch (job) {
            case CROSSBOWMAN -> WeaponType.CROSSBOW;
            case GUNSLINGER -> WeaponType.GUN;
            case ASSASSIN -> WeaponType.CLAW;
            case MAGICIAN, FP_WIZARD, IL_WIZARD, CLERIC, FP_MAGE, IL_MAGE, PRIEST,
                    FP_ARCHMAGE, IL_ARCHMAGE, BISHOP -> WeaponType.WAND;
            default -> WeaponType.BOW;
        };
        when(bot.getId()).thenReturn(BOT_ID);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(40);
        when(bot.getPosition()).thenAnswer(inv -> position.get());
        when(bot.getInventory(InventoryType.EQUIPPED).getItem(anyShort())).thenReturn(mock(Item.class));
        when(bot.getParty()).thenReturn(null);
        attacks = mockStatic(BotAttack.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        attacks.when(() -> BotAttack.resolveEquippedWeaponType(bot)).thenReturn(weapon);
    }

    private static Monster monster(int id, int oid, Point position) {
        Monster mob = mock(Monster.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(mob.getId()).thenReturn(id);
        when(mob.getObjectId()).thenReturn(oid);
        when(mob.getType()).thenReturn(MapObjectType.MONSTER);
        when(mob.getPosition()).thenReturn(position);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getName()).thenReturn("arc target");
        return mob;
    }

    /**
     * A boss-flagged mob whose body stands {@code bodyHeight} above its foothold (the WZ bbox's
     * upward span): the geometry the boss pad is derived from.
     */
    private static Monster bossMonster(int id, int oid, Point position, int bodyHeight) {
        Monster mob = monster(id, oid, position);
        when(mob.isBoss()).thenReturn(true);
        when(mob.getStats().hasBbox()).thenReturn(bodyHeight > 0);
        when(mob.getStats().getBboxMinY()).thenReturn(-bodyHeight);
        when(mob.getStats().getImgheight()).thenReturn(bodyHeight);
        when(mob.getName()).thenReturn("boss target");
        return mob;
    }

    @SuppressWarnings("unchecked")
    private Monster inject(Monster mob) throws Exception {
        Field objects = MapleMap.class.getDeclaredField("mapobjects");
        objects.setAccessible(true);
        ((Map<Integer, MapObject>) objects.get(map)).put(mob.getObjectId(), mob);
        return mob;
    }
}
