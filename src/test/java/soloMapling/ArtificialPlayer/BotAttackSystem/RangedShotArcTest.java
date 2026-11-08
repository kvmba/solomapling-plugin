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
import org.junit.jupiter.params.provider.ValueSource;
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
        Monster shelf = inject(monster(9300170, 99403, new Point(220, -140)));
        assertTrue(BotAttackDriver.canReach(bot, shelf),
                "220 across and 140 up is a real shelf shot (|dy| <= |dx| * 2)");
    }

    /** Just inside the box but on the same row: the ordinary case is untouched. */
    @Test
    void aSameRowShotAtFullReachStillLands() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster far = inject(monster(9300170, 99404, new Point(440, 0)));
        assertTrue(BotAttackDriver.canReach(bot, far), "dx=440 dy=0 is a plain level shot");
    }

    /**
     * The diagonal is the boundary itself: |dy| == |dx| is the steepest legal shot.
     * Off-by-one here means either a one-pixel band of legal shots is lost, or the rule leaks.
     */
    @ParameterizedTest
    @ValueSource(ints = {99, 100, 101})
    void theDiagonalIsTheExactBoundary(int dy) throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster mob = inject(monster(9300170, 99420 + dy, new Point(100, -dy)));
        assertEquals(dy <= 100, BotAttackDriver.canReach(bot, mob),
                "|dy|=" + dy + " against |dx|=100: legal up to and including the diagonal");
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

    /** The remote-firing search must not offer a ledge from which the shot's arc fails. */
    @Test
    void firingPositionsExcludeTheNearVerticalLane() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster target = inject(monster(9300170, 99407, new Point(600, -400)));
        Rectangle shooters = BotAttackDriver.firingPositions(bot, target);
        assertNotNull(shooters);
        assertFalse(shooters.contains(new Point(600, -400)), "never stand on the target's own column");
        assertFalse(shooters.intersects(new Rectangle(560, -900, 80, 800)),
                "the whole near-vertical lane is cut, not just the target's pixel");
        assertTrue(shooters.contains(new Point(200, -400)),
                "the lateral band on the bot's own side stays available");
    }

    /** Every legal shooter point must satisfy the same arc the swing applies. */
    @Test
    void everyFiringPositionSurvivesTheHitTest() throws Exception {
        createBot(Job.HUNTER, new Point(0, 0));
        Monster target = inject(monster(9300170, 99408, new Point(600, -400)));
        Rectangle shooters = BotAttackDriver.firingPositions(bot, target);
        Point tp = target.getPosition();
        for (int x = shooters.x; x < shooters.x + shooters.width; x++) {
            for (int y = shooters.y; y < shooters.y + shooters.height; y++) {
                assertTrue(!BotAttackDriver.isArcLimited(bot)
                                || BotAttackDriver.withinRangedArc(new Point(x, y), tp, false),
                        "firing box offers a shot outside the arc at (" + x + "," + y + ")");
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
