package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Pins the JUMP half of Haste.
 *
 * <p>WZ {@code Skill.wz/410.img} for 4101004 (轻功) carries both {@code speed} AND {@code jump},
 * rising together per level (level 1 = speed 2 / jump 1, level 20 = speed 40 / jump 20). The bot
 * movement model carried only the speed half: {@code partyGrantsHaste} raised the walk baseline and
 * the comment even said "(not jump)". That made a hasted party leap exactly like an unhasted one -
 * and it is the difference between clearing Ludi PQ's stage-5 top ledge and not:
 *
 * <ul>
 *   <li>the door (in01 of 922010500) sits on fh420 y=-1291, above the fh415/416 row y=-1276,
 *       above the fh405/408 row y=-1176 - the climbable step is 100px, bridged by no foothold,
 *       rope or portal;</li>
 *   <li>WZ Physics.img (gravity 2000, jumpSpeed 555) gives 110 jump a 93px rise: the whole 35-50
 *       LPQ band falls short;</li>
 *   <li>+20 jump from a party Haste (the maxed skill) lands on 123 (the profile's ceiling), whose
 *       rise is 116px - clears it;</li>
 *   <li>and the room is NOT optional: all six stage-5 rooms hold four pass boxes and the stage
 *       asks for 24, so a party without the jump cannot finish the stage at all.</li>
 * </ul>
 *
 * <p>The level gate is the skill's own too: Haste is learned at 2nd job (level 30+), not at 60 -
 * a 60 gate could never fire in the 35-50 band these quests run.
 *
 * <p>These cases go through {@code BotMovementProfile.fromCharacter}, the actual production path:
 * a test that hand-builds a profile would pass even with the grant deleted (verified by mutating
 * the grant away and watching this fail).
 */
class HasteJumpGrantTest {

    @BeforeAll
    static void stubHost() throws Exception {
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
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        // Job's static initializer resolves its names through I18nUtil's MessageSource beans - touch
        // it once here, after the context is installed, so the per-test bodies never race it.
        org.gms.client.Job.BEGINNER.getId();
    }

    static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    /** The rise the engine derives from a jump stat at WZ Physics.img constants. */
    private static int riseForJumpStat(int jumpStat) {
        double v = 555.0 * (jumpStat / 100.0);
        return (int) (v * v / (2.0 * 2000.0));
    }

    @Test
    void theHasteBonusIsTheSkillRowsMaxJump() throws Exception {
        // WZ 4101004's level-20 row: speed 40 / jump 20.
        assertEquals(20, staticField("HASTE_JUMP_BONUS"));
    }

    @Test
    void theWholeLpqBandFallsShortWithoutHaste() throws Exception {
        // A level-50 warrior alone in a party: 110 jump -> 93px rise < the 100px stage-5 step.
        org.gms.client.Character solo = level50Warrior(null);
        assertEquals(110, BotMovementProfile.fromCharacter(solo).totalJumpStat());
        assertTrue(riseForJumpStat(110) < 100,
                "an unhasted LPQ body must NOT be able to clear the top ledge");
    }

    @Test
    void aHastedPartyGrantsTheJumpThatClearsTheStep() throws Exception {
        // Same body, now sharing a party with a level-35 haste-thief: Haste is a 2nd-job skill, so
        // the level-35 thief in the LPQ band is exactly the party member that supplies it.
        org.gms.client.Character hasted = level50Warrior(level35Thief());
        int jump = BotMovementProfile.fromCharacter(hasted).totalJumpStat();
        assertEquals(123, jump, "+20 Haste jump on a 110 baseline, clamped to the 123 ceiling");
        assertTrue(riseForJumpStat(jump) >= 100,
                "a hasted body must clear the stage-5 step (116px of rise)");
    }

    @Test
    void theLevelGateIsTheSkillsOwn() throws Exception {
        // Haste is learned at 2nd job (level 30). A 60 gate can never fire in a 35-50 quest party,
        // which is why the party Haste was dead code in exactly the band that needs it.
        assertEquals(30, staticField("PARTY_HASTE_THIEF_LEVEL"));
    }

    @Test
    void aYoungThiefDoesNotDoubleCountItsOwnHaste() throws Exception {
        // A haste-thief carries the skill itself, so its baseline already includes it; the party
        // grant must not stack a second +20 on top.
        org.gms.client.Character thief = mock(org.gms.client.Character.class);
        when(thief.getLevel()).thenReturn(40);
        when(thief.getJob()).thenReturn(org.gms.client.Job.ASSASSIN);
        when(thief.getTotalMoveSpeedStat()).thenReturn(100);
        when(thief.getTotalJumpStat()).thenReturn(100);
        PartyCharacter mate = level35Thief();
        Party thiefParty = partyWith(mate, 77);
        when(thief.getParty()).thenReturn(thiefParty);
        when(thief.getId()).thenReturn(77);
        when(thief.getBuffedValue(org.gms.client.BuffStat.MONSTER_RIDING)).thenReturn(null);
        int jump = BotMovementProfile.fromCharacter(thief).totalJumpStat();
        assertEquals(115, jump, "a 2nd-job thief's own Haste baseline is 115; the grant must not stack");
    }

    /**
     * A level-50 non-thief in a party with {@code mate} (null = solo), as the production profile
     * reader sees it: level/job/stat accessors plus the party roster.
     */
    private static org.gms.client.Character level50Warrior(PartyCharacter mate) {
        org.gms.client.Character bot = mock(org.gms.client.Character.class);
        when(bot.getLevel()).thenReturn(50);
        when(bot.getJob()).thenReturn(org.gms.client.Job.FIGHTER);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getId()).thenReturn(11);
        when(bot.getBuffedValue(org.gms.client.BuffStat.MONSTER_RIDING)).thenReturn(null);
        Party roster = (mate == null) ? null : partyWith(mate, 11);
        when(bot.getParty()).thenReturn(roster);
        return bot;
    }

    private static PartyCharacter level35Thief() {
        PartyCharacter thief = mock(PartyCharacter.class);
        when(thief.getId()).thenReturn(22);
        when(thief.getLevel()).thenReturn(35);
        when(thief.getJob()).thenReturn(org.gms.client.Job.ASSASSIN);
        when(thief.isOnline()).thenReturn(true);
        return thief;
    }

    private static Party partyWith(PartyCharacter mate, int selfId) {
        Party party = mock(Party.class);
        PartyCharacter self = mock(PartyCharacter.class);
        when(self.getId()).thenReturn(selfId);
        when(party.getMembers()).thenReturn(List.of(self, mate));
        return party;
    }

    private static int staticField(String name) throws Exception {
        java.lang.reflect.Field f = BotMovementProfile.class.getDeclaredField(name);
        f.setAccessible(true);
        return (int) f.get(null);
    }
}
