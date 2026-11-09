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
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;

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
 * The shot's arc: a thrown/launched projectile cannot hit a body almost straight above or below.
 *
 * <p>Before the fix the reach box was a bare rectangle around the bot, so a bowman/crossbowman/
 * gunslinger/hermit bot fired at any mob inside it - including one standing on the platform
 * directly overhead (dx ~ 0, dy up to the profile's vertical reach). The client draws those shots
 * flying along the shooter's row, so the hit read as hitting through the floor. A cast is exempt:
 * a spell may legitimately come straight down (Meteor Shower, Heal).
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
                "300 across and 70 up is a real shelf shot (|dy| * 4 <= |dx|)");
    }

    /** Just inside the box but on the same row: the ordinary case is untouched. */
    @Test
    void aSameRowShotAtFullReachStillLands() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster far = inject(monster(9300170, 99404, new Point(440, 0)));
        assertTrue(BotAttackDriver.canReach(bot, far), "dx=440 dy=0 is a plain level shot");
    }

    /**
     * The arc threshold is |dy| * ARC_SLOPE <= |dx|, i.e. ~14 degrees. Cases are chosen to sit
     * strictly INSIDE the reach box (reachY=150 for the single profile) so each one can only pass
     * or fail on the arc, never on the box.
     */
    @ParameterizedTest(name = "dx={0} dy={1} legal={2}")
    @CsvSource({
            "300, 75, true",   // exactly on the arc: 75*4 == 300
            "300, 74, true",   // just inside
            "300, 76, false",  // just outside
            "200, 50, true",   // exact at a shorter range
            "200, 51, false",  // inside the box, but too steep for the arc
            "440, 110, true",  // full horizontal reach, shallow angle
            "440, 111, false", // one pixel over the arc at the same range
    })
    void theArcThresholdIsExact(int dx, int dy, boolean legal) throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster mob = inject(monster(9300170, 99420 + dx + dy, new Point(dx, -dy)));
        assertEquals(legal, BotAttackDriver.canReach(bot, mob),
                "dx=" + dx + " dy=" + dy + " (arc is |dy|*4 <= |dx|)");
    }

    /** Bosses get the body padding, not a blanket exemption. */
    @Test
    void aBossStillNeedsTheArcBeyondItsPadding() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster boss = monster(9300012, 99430, new Point(0, -500));
        when(boss.isBoss()).thenReturn(true);
        inject(boss);
        assertFalse(BotAttackDriver.canReach(bot, boss),
                "BOSS_Y_PAD is 300: dy=500 at dx=0 is still far outside the arc");
    }

    @Test
    void aBossOverheadIsStillReachable() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster boss = monster(9300012, 99405, new Point(0, -260));
        when(boss.isBoss()).thenReturn(true);
        inject(boss);
        assertTrue(BotAttackDriver.canReach(bot, boss),
                "a multi-bodied boss is padded by BOSS_Y_PAD, so its upper chain is a legal target");
    }

    @Test
    void aMageIsNotArcLimited() throws Exception {
        createBot(Job.CLERIC, new Point(0, 0));
        Monster overhead = inject(monster(9300170, 99406, new Point(0, -140)));
        assertTrue(BotAttackDriver.canReach(bot, overhead),
                "a cast may come straight down; the arc rule is a projectile rule only");
    }

    /**
     * The remote-firing search returns a spatial PREFILTER, not the legal set: at 1:4 the wedge
     * needs reachY * ARC_SLOPE = 600px of room against reachX = 450, so no rectangle can express
     * it (see BotAttackDriver.firingPositions). The box must stay on the bot's own side of the
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
    @Test
    void thePrefilterBoxNeverDropsALegalShot() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster target = inject(monster(9300170, 99408, new Point(600, -400)));
        Rectangle shooters = BotAttackDriver.firingPositions(bot, target);
        Point tp = target.getPosition();
        assertTrue(BotAttackDriver.isArcLimited(bot), "a bowman's shot is arc-limited");
        // dx starts at 1: dx=0 is legal only at dy=0, i.e. the target's own pixel, which is not
        // a position to shoot FROM (and the box deliberately stops at the target's column).
        for (int dx = 1; dx <= 450; dx++) {
            for (int dy = 0; dy <= 150; dy += 5) {
                if (dy * 4 > dx) continue; // not a legal shot in the first place
                assertTrue(shooters.contains(new Point(tp.x - dx, tp.y - dy)),
                        "legal shot at dx=" + dx + " dy=" + dy + " was pruned by the prefilter");
                assertTrue(shooters.contains(new Point(tp.x - dx, tp.y + dy)),
                        "legal shot at dx=" + dx + " dy=-" + dy + " was pruned by the prefilter");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"CROSSBOWMAN", "GUNSLINGER", "ASSASSIN"})
    void everyProjectileClassSharesTheArc(Job job) throws Exception {
        createBot(job, new Point(0, 0));
        Monster overhead = inject(monster(9300170, 99410, new Point(0, -120)));
        assertFalse(BotAttackDriver.canReach(bot, overhead), job + " must not hit a body straight overhead");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

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

    @SuppressWarnings("unchecked")
    private Monster inject(Monster mob) throws Exception {
        Field objects = MapleMap.class.getDeclaredField("mapobjects");
        objects.setAccessible(true);
        ((Map<Integer, MapObject>) objects.get(map)).put(mob.getObjectId(), mob);
        return mob;
    }
}
