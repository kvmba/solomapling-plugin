package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Stage 5's floor rule: only a kit carrying 隐身术 / 橡木伪装 may go down to the golems'
 * corridor of 922010500.
 *
 * <p>The rule is not cosmetic. WZ puts the two invincible 9300013 on that floor (x=162/26,
 * feet y=54/55), and 922010506's two exits both land at the tower's BOTTOM (`in06`, y=251) -
 * the one floor where the only route back up to the stage door crosses the golems' ledge. A
 * hide-less body that walks down there is charged 999 x 0.5 per touch with no way back until
 * the stage-clear warp collects it.
 */
class LudiStage5FloorRuleTest {

    @BeforeAll
    static void bootHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(props);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String n : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(n, org.springframework.context.MessageSource.class)).thenReturn(messages);
        }
        var setter = ServerManager.class.getDeclaredMethod(
                "setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
        GameConfigDO interval = new GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
    }

    /**
     * Only the two hide kits may descend. The gunslinger line is the trap: its pirates are
     * pirates, but 橡木伪装 (5101007) is the Brawler's skill and is not in their registry.
     */
    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"THIEF", "ASSASSIN", "BANDIT", "HERMIT",
            "CHIEFBANDIT", "NIGHTLORD", "SHADOWER", "BRAWLER", "MARAUDER", "BUCCANEER"})
    void hideKitsMayDescend(Job job) {
        Character bot = botOnStage5(job);
        assertTrue(LudiStages.carriesGuardHide(bot), job + " carries a guard hide");
        assertFalse(LudiStages.refusesFloor(bot, new Point(-1, 60)),
                job + " may take a goal on the golems' floor");
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"BEGINNER", "WARRIOR", "FIGHTER", "PAGE", "SPEARMAN",
            "MAGICIAN", "FP_WIZARD", "IL_WIZARD", "CLERIC", "BOWMAN", "HUNTER", "CROSSBOWMAN",
            "PIRATE", "GUNSLINGER", "OUTLAW", "CORSAIR", "NIGHTWALKER1", "THUNDERBREAKER1"})
    void kitsWithoutTheHideMayNotDescend(Job job) {
        Character bot = botOnStage5(job);
        assertFalse(LudiStages.carriesGuardHide(bot), job + " has no guard hide");
        assertTrue(LudiStages.refusesFloor(bot, new Point(-1, 60)),
                job + " must not be given a target on the golems' floor");
    }

    /** The upper floors are ordinary party movement for every kit: the rule must not fire there. */
    @Test
    void anUpperFloorGoalIsNeverRefused() {
        Character bot = botOnStage5(Job.GUNSLINGER);
        for (Point goal : List.of(new Point(128, -216), new Point(58, -225),
                new Point(-208, -219), new Point(213, -855), new Point(-145, -1292),
                new Point(-123, -1292), new Point(84, -525))) {
            assertFalse(LudiStages.refusesFloor(bot, goal),
                    "upper-floor goal " + goal + " must stand");
        }
    }

    /** The rule is scoped to 922010500: another map's y>=0 goal is not this stage's business. */
    @Test
    void anotherMapIsNotJudged() {
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(890031);
        when(bot.getJob()).thenReturn(Job.GUNSLINGER);
        when(bot.getMapId()).thenReturn(922010400);
        assertFalse(LudiStages.refusesFloor(bot, new Point(-1, 60)),
                "stage 4 must not pay for stage 5's rule");
        assertFalse(LudiStages.refusesFloor(bot, null), "no goal, no verdict");
    }

    /** The floor line sits between the stage NPC post and the golems, read off the WZ life table. */
    @Test
    void theFloorLineSeparatesThePostFromTheGolems() {
        Character gunner = botOnStage5(Job.GUNSLINGER);
        assertFalse(LudiStages.refusesFloor(gunner, new Point(58, -225)), "the stage NPC post is upstairs");
        assertFalse(LudiStages.refusesFloor(gunner, new Point(128, -216)), "the stage door is upstairs");
        assertFalse(LudiStages.refusesFloor(gunner, new Point(-208, -219)), "the spawn ledge is upstairs");
        assertFalse(LudiStages.refusesFloor(gunner, new Point(0, -1)), "y=-1 is still the upper tower");
        assertTrue(LudiStages.refusesFloor(gunner, new Point(0, 0)),
                "the line is conservative: y>=0 is the guards' shaft, whose real ledge is y=58");
        assertTrue(LudiStages.refusesFloor(gunner, new Point(162, 54)), "golem 1's feet are on the floor");
        assertTrue(LudiStages.refusesFloor(gunner, new Point(26, 55)), "golem 2's feet are on the floor");
        assertTrue(LudiStages.refusesFloor(gunner, new Point(-123, 251)), "the tower's bottom landing");
        assertFalse(LudiStages.refusesFloor(gunner, null), "no point, no floor");
        assertFalse(LudiStages.refusesFloor(null, new Point(-1, 60)), "no bot, no verdict");
    }

    /** The same skill gate drives 922010506's admission - one switch, both halves of the rule. */
    @Test
    void theGuardRoomAdmitsTheSameKits() {
        assertFalse(LudiStages.stage5AdmitsTo(botOnStage5(Job.CORSAIR), LudiPqData.STAGE5_ROOM_LAST),
                "a gunner's pirate cannot enter the seven-golem room");
        assertFalse(LudiStages.stage5AdmitsTo(botOnStage5(Job.NIGHTWALKER1), LudiPqData.STAGE5_ROOM_LAST),
                "a night walker has no registered hide either");
        assertTrue(LudiStages.stage5AdmitsTo(botOnStage5(Job.BRAWLER), LudiPqData.STAGE5_ROOM_LAST),
                "a brawler's 橡木伪装 opens it");
        assertTrue(LudiStages.stage5AdmitsTo(botOnStage5(Job.THIEF), LudiPqData.STAGE5_ROOM_LAST),
                "a thief's 隐身术 opens it");
    }

    private static Character botOnStage5(Job job) {
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(890031);
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(40);
        when(bot.getMapId()).thenReturn(LudiPqData.STAGE_5);
        return bot;
    }
}
