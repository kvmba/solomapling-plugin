package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.life.Monster;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guard-hide trigger must not fire on a box ledge that merely sits above a golem, and must
 * fire when the bodies can actually touch.
 *
 * <p>9300013 links to 4230110: stand frame lt=(-67,-139) rb=(84,0). That is 139px of body ABOVE
 * the golem's feet, and 151px wide - wide enough that a naive "distance to the golem" test reads
 * a box ledge as contact. The trigger has to be a body-box overlap.
 */
class LudiGuardFalsePositiveTest {

    @BeforeAll
    static void bootHost() throws Exception {
        org.springframework.context.ApplicationContext context =
                mock(org.springframework.context.ApplicationContext.class,
                        org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.gms.service.ConfigService config = mock(org.gms.service.ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(org.gms.service.ConfigService.class)).thenReturn(config);
        org.gms.property.ServiceProperty props = mock(org.gms.property.ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(org.gms.property.ServiceProperty.class)).thenReturn(props);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String n : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(n, org.springframework.context.MessageSource.class)).thenReturn(messages);
        }
        var setter = org.gms.manager.ServerManager.class.getDeclaredMethod("setApplicationContext",
                org.springframework.context.ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new org.gms.manager.ServerManager(), context);
        org.gms.dao.entity.GameConfigDO interval = new org.gms.dao.entity.GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        org.gms.config.GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
        BotNavigationGraphProvider.setCacheDir(java.nio.file.Path.of("cache"));
    }

    @Test
    void theBoxLedgeAboveAGolemIsNotContact() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010506);
        // Box ledges are at y=-630/-1064/-1459/-3535; the golem rows at y=-441/-862/-1266/-2497.
        // 187px is the smallest box-ledge-to-guard gap the WZ data has.
        assertFalse(touch(map, new Point(-83, -630), new Point(-80, -441)),
                "a box ledge 187px above a golem must not arm the hide");
        assertFalse(touch(map, new Point(92, -1064), new Point(55, -862)),
                "202px above a golem must not arm the hide");
    }

    @Test
    void aBodyInsideTheGolemsBoxIsContact() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010506);
        assertTrue(touch(map, new Point(0, -443 - 120), new Point(0, -443)),
                "120px above the golem's feet is inside its 139px body");
        assertTrue(touch(map, new Point(0, -443), new Point(0, -443)),
                "standing on the golem is contact");
        assertTrue(touch(map, new Point(60, -443), new Point(0, -443)),
                "60px to the side is inside its 151px-wide body");
    }

    private static boolean touch(MapleMap map, Point bot, Point golem) throws Exception {
        var chr = mock(org.gms.client.Character.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(map.getId());
        when(chr.getPosition()).thenReturn(bot);
        Monster mob = mock(Monster.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(mob.getId()).thenReturn(9300013);
        when(mob.getObjectId()).thenReturn(899001);
        when(mob.getType()).thenReturn(MapObjectType.MONSTER);
        when(mob.getPosition()).thenReturn(golem);
        when(mob.isAlive()).thenReturn(true);
        Field objects = MapleMap.class.getDeclaredField("mapobjects");
        objects.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, MapObject> objs = (Map<Integer, MapObject>) objects.get(map);
        objs.put(899001, mob);
        try {
            return GCMovement.monsterWithinTouch(chr, 9300013, 40);
        } finally {
            objs.remove(899001);
        }
    }
}
