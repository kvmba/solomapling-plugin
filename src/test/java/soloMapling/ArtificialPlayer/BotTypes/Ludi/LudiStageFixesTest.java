package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the five audit defects whose fixes are visible in the stage code itself:
 *
 * <ul>
 *   <li>stage 2 - the trap box is sprung, and only after the tower is spent;</li>
 *   <li>stage 3 - a crate is not opened while its own spawn is still alive (not fixed: the
 *       quest's own pacing; kept out of scope deliberately);</li>
 *   <li>stage 7 - the ledge mobs are the pass source and the box is never walked at;</li>
 *   <li>stage 9 - the altar is struck, and an unreachable target is not chased;</li>
 *   <li>after stage 9 - the bonus room breaks boxes and never loots.</li>
 * </ul>
 *
 * <p>These are source-shape assertions on purpose: the behaviour they pin (reactor ids,
 * ordering gates, the loot opt-out) is exactly what a refactor can silently drop, and the
 * execution-level simulations for the same stages already live in GCMoveSystem.
 */
class LudiStageFixesTest {

    @BeforeAll
    static void bootHost() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource ms = mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfigDO d = new GameConfigDO();
        d.setConfigType("server"); d.setConfigSubType("0"); d.setConfigCode("update_interval");
        d.setConfigValue("100"); d.setConfigClazz("java.lang.Long");
        GameConfig.add(d);
        org.gms.net.server.Server.getInstance();
    }

    private static String source(Class<?> type) throws Exception {
        String name = type.getSimpleName();
        java.nio.file.Path p = java.nio.file.Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ludi/"
                + name + ".java");
        return java.nio.file.Files.readString(p);
    }

    private static String methodBody(String java, String signature) {
        int start = java.indexOf(signature);
        assertTrue(start > 0, "missing method: " + signature);
        int open = java.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < java.length(); i++) {
            char c = java.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return java.substring(open, i + 1);
        }
        fail("unbalanced braces after " + signature);
        return null;
    }

    @Test
    void stage2SpringsTheTrapBoxOnlyAfterTheTowerIsSpent() throws Exception {
        assertEquals(2200002, LudiPqData.BOX_STAGE2_TRAP,
                "the trap is the tower's 12th box; the turn-in needs its room's four passes");

        String body = methodBody(source(LudiStages.class), "public static void breakTowerBoxes(");
        int towerWork = body.indexOf("nearestOwnedBoxOid(bot, LudiPqData.BOX_STAGE2)");
        int trapGate = body.indexOf("findAllReactorOids(bot, LudiPqData.BOX_STAGE2).isEmpty()");
        int trapWork = body.indexOf("LudiPqData.BOX_STAGE2_TRAP");

        assertTrue(towerWork >= 0, "the tower boxes are still the first work");
        assertTrue(trapGate > towerWork, "the trap gate must come AFTER the tower work");
        assertTrue(trapWork > trapGate, "the trap must only be worked behind that gate");
        // The gate must be the map-wide emptiness test: a per-bot share also reads empty while
        // teammates still hold standing boxes, and springing the trap then warps them mid-climb.
        assertFalse(body.contains("nearestOwnedBoxOid(bot, LudiPqData.BOX_STAGE2) < 0"),
                "box < 0 is the wrong gate (teammates' claims also produce it)");
    }

    @Test
    void stage7WorksTheLedgeMobsAndNeverWalksAtTheBox() throws Exception {
        assertArrayEquals(new int[]{9300169, 9300170, 9300171}, LudiPqData.STAGE7_LEDGE_MOBS,
                "the three ledge mobs are the stage's only 4001156 source");

        String gather = methodBody(source(LudiStages.class), "public static void gatherPasses(");
        assertTrue(gather.contains("stage7LedgeMobAlive(bot)"),
                "stage 7 must hunt the ledge mobs");
        assertTrue(gather.contains("stage7PassStillWanted(bot)"),
                "and keep working while a pass is still owed");
        assertTrue(gather.contains("seekAndAttack(bot, true)"),
                "with reachableOnly: an unreachable mob is out of the chase, not an 8s dead end");
        assertFalse(gather.contains("hitReactorRotate(bot, box, LudiPqData.BOX_STAGE7)"),
                "the box sits on a ledge with no inbound edge - walking at it can never work");
        // ORDER MATTERS: the ledge mobs are ordinary killable mobs, so the shared
        // "anything killable" branch would swallow them and chase with the unrestricted seek.
        int stage7At = gather.indexOf("if (stage == 7)");
        int genericAt = gather.indexOf("if (killableMobPresent(bot))");
        assertTrue(stage7At > 0 && genericAt > stage7At,
                "the stage-7 branch must run BEFORE the shared killable branch");
        String stage7Body = gather.substring(stage7At, genericAt);
        assertFalse(stage7Body.contains("PqActions.loot"),
                "stage 7 must not sweep: the 4001156 drop is what arms the box, and a picked-up "
                        + "item can never trigger it");
    }

    @Test
    void stage9StrikesTheAltarAndDoesNotChaseWhatItCannotReach() throws Exception {
        assertEquals(2201003, LudiPqData.BOSS_CRYSTAL);
        String boss = methodBody(source(LudiStages.class), "public static void fightBoss(");
        assertTrue(boss.contains("LudiPqData.BOSS_CRYSTAL"),
                "the altar is the only thing that summons Alishar on this server");
        assertTrue(boss.contains("hitReactorIfInReach(bot, crystal)"),
                "striking it is a plain reactor hit - type 100 does not block a weapon hit");
        assertTrue(boss.indexOf("BOSS_CRYSTAL") < boss.indexOf("seekAndAttack"),
                "summon first, then add damage");
        assertTrue(boss.contains("seekAndAttack(bot, true)"),
                "the altar ledge's Ratz are unreachable: the chase must not walk at them");
    }

    @Test
    void bonusRoomBreaksBoxesAndNeverLoots() throws Exception {
        assertEquals(2202004, LudiPqData.BONUS_BOX);
        assertEquals(922011000, LudiPqData.BONUS_MAP);
        assertEquals(922011100, LudiPqData.BONUS_TAIL_MAP);

        String bonus = methodBody(source(LudiStages.class), "public static void breakBonusBoxes(");
        assertTrue(bonus.contains("LudiPqData.BONUS_BOX"), "the reward boxes are the work");
        assertFalse(bonus.contains("PqActions.loot"),
                "the sprayed rewards belong to the party's humans - the bot opens, never picks up");
    }

    @Test
    void stage7PassStillWantedUsesTheArmedStateNotIsActive() throws Exception {
        String body = methodBody(source(LudiStages.class), "private static boolean stage7PassStillWanted(");
        // 2201002 has TWO states and both carry a type, so isActive() never goes false and a
        // stage that tested it would never reach the delivery branch.
        assertTrue(body.contains("getReactorType() == 100"),
                "a fired box must read as spent: type 100 is the engine's 'still wants its item'");
        assertFalse(body.contains("findAllReactorOids(bot, LudiPqData.BOX_STAGE7)"),
                "findAllReactorOids filters on isActive(), which stays true for both 2201002 states");
    }

    @Test
    void stage9NoLongerEndsTheRun() throws Exception {
        String work = methodBody(source(LudiPQBot.class), "protected boolean workStage()");
        assertTrue(work.contains("LudiPqData.BONUS_MAP"),
                "the bonus rooms are routed before any stage arithmetic");
        assertFalse(work.contains("return stage >= 9"),
                "stage 9's turn-in clears the PQ and warps the team to the bonus room");
    }
}
