package soloMapling.ArtificialPlayer.BotStatusSystem;

import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.life.MobSkill;
import org.gms.service.ConfigService;
import org.gms.util.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the second way a bot can catch a disease: the engine's own {@code Character.diseases}, which
 * is where Monster Carnival's tab-1 Seal / Darkness / Weakness / Slow / Poison land
 * ({@code MonsterCarnivalHandler -> Character.giveDebuff}).
 *
 * <p>The defect these pin: a bot reads its behaviour only from {@link BotDebuffState}, and the engine
 * only registers its disease-expire task for real players (at login). So an opponent's debuff used to
 * paint an icon over a bot that kept walking, swinging and hitting at full strength - and, because
 * nothing ever expired it, it also sat on the engine's "at most two diseases" cap forever.
 */
class BotDebuffStateHostAdoptionTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        // Mocking Character runs its static initializer, which resolves host services through
        // ServerManager - the standard test stub (see PqLootRadiusTest).
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
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
        GameConfigDO updateInterval = new GameConfigDO();
        updateInterval.setConfigType("server");
        updateInterval.setConfigSubType("0");
        updateInterval.setConfigCode("update_interval");
        updateInterval.setConfigValue("100");
        updateInterval.setConfigClazz("java.lang.Long");
        GameConfig.add(updateInterval);
        org.gms.net.server.Server.getInstance();
    }

    private static Character bot() {
        Character chr = mock(Character.class, RETURNS_DEEP_STUBS);
        when(chr.getHp()).thenReturn(1_000);
        return chr;
    }

    /** One disease as the engine holds it: remaining ms + the WZ skill that applied it. */
    private static Map<Disease, Pair<Long, MobSkill>> hostHolding(Disease disease, long remainingMs) {
        Map<Disease, Pair<Long, MobSkill>> held = new LinkedHashMap<>();
        held.put(disease, new Pair<>(remainingMs, (MobSkill) null));
        return held;
    }

    @Test
    void anEngineAppliedDiseaseActuallyGatesTheBot() {
        Character chr = bot();
        when(chr.getDiseasesSize()).thenReturn(1);
        when(chr.getAllDiseases()).thenReturn(hostHolding(Disease.SEAL, 60_000L));
        BotDebuffState s = new BotDebuffState(chr);

        s.tickAt(1_000_000L);

        assertTrue(s.blocksAttack(), "the enemy's Seal must disarm the bot, not just draw an icon");
        assertEquals(1, s.size());
    }

    @Test
    void aSlowFromTheEngineScalesMovement() {
        Character chr = bot();
        when(chr.getDiseasesSize()).thenReturn(1);
        when(chr.getAllDiseases()).thenReturn(hostHolding(Disease.SLOW, 6_000L));
        BotDebuffState s = new BotDebuffState(chr);

        s.tickAt(1_000_000L);

        assertTrue(s.moveFactor() < 1.0, "the enemy's Slow must slow the bot down");
    }

    @Test
    void anAdoptedDiseaseEndsWhenItsEngineTimeRunsOut() {
        Character chr = bot();
        when(chr.getDiseasesSize()).thenReturn(1);
        when(chr.getAllDiseases()).thenReturn(hostHolding(Disease.STUN, 5_000L));
        BotDebuffState s = new BotDebuffState(chr);
        long t0 = 2_000_000L;

        s.tickAt(t0);
        assertTrue(s.isFrozen());

        s.tickAt(t0 + 5_001L);

        assertFalse(s.isFrozen(), "a stun with an engine-side duration must wear off");
        // The engine broadcast the give frame, so the engine must broadcast the matching cancel -
        // and it is the only thing that clears the entry blocking its own two-disease cap.
        verify(chr).dispelDebuff(Disease.STUN);
    }

    @Test
    void aDiseasedBotReReadsTheEngineListOnItsOwnClockNotEveryTick() {
        Character chr = bot();
        when(chr.getDiseasesSize()).thenReturn(1);
        when(chr.getAllDiseases()).thenReturn(hostHolding(Disease.STUN, 60_000L));
        BotDebuffState s = new BotDebuffState(chr);
        long t0 = 6_000_000L;

        // The movement tick runs at 20 Hz; one second of it must not become 20 list copies. Reads
        // land at t0 and every HOST_SYNC_INTERVAL_MS after it: 0, 250, 500, 750 -> four in [t0, t0+1s).
        for (int tick = 0; tick < 20; tick++) {
            s.tickAt(t0 + tick * 50L);
        }
        verify(chr, times(4)).getAllDiseases();

        s.tickAt(t0 + 1_000L);
        verify(chr, times(5)).getAllDiseases();
    }

    @Test
    void anEngineSideClearIsMirroredWithinOneSyncWindow() {
        Character chr = bot();
        when(chr.getDiseasesSize()).thenReturn(1);
        when(chr.getAllDiseases()).thenReturn(hostHolding(Disease.SEAL, 60_000L));
        BotDebuffState s = new BotDebuffState(chr);
        long t0 = 7_000_000L;
        s.tickAt(t0); // adopts, and arms the sync window
        assertTrue(s.blocksAttack());

        // An all-cure (a quest script's dispelDebuffs, the match-end sweep) clears the engine's copy.
        // A bot keeps fighting only until the next scheduled read - 250 ms, six times finer than the
        // 1500 ms expire pass the engine itself runs for players.
        when(chr.getDiseasesSize()).thenReturn(0);
        when(chr.getAllDiseases()).thenReturn(Map.of());
        s.tickAt(t0 + 100L);
        s.tickAt(t0 + BotDebuffTable.HOST_SYNC_INTERVAL_MS);

        assertFalse(s.blocksAttack(), "the cure must clear the mirror on the next sync");
        assertEquals(0, s.size());
    }

    @Test
    void anEngineDiseaseLandsWithinOneSyncWindow() {
        Character chr = bot();
        when(chr.getDiseasesSize()).thenReturn(1);
        when(chr.getAllDiseases()).thenReturn(Map.of());
        BotDebuffState s = new BotDebuffState(chr);
        long t0 = 7_500_000L;
        s.tickAt(t0); // arms the window with nothing live

        // A Seal landed in between; the throttle delays the read, it never swallows the disease.
        when(chr.getAllDiseases()).thenReturn(hostHolding(Disease.SEAL, 60_000L));
        s.tickAt(t0 + BotDebuffTable.HOST_SYNC_INTERVAL_MS);

        assertTrue(s.blocksAttack(), "the Seal is adopted on the first sync after the window");
    }

    @Test
    void clearingTheBotAlsoEndsTheEngineCopy() {
        Character chr = bot();
        when(chr.getDiseasesSize()).thenReturn(1);
        when(chr.getAllDiseases()).thenReturn(hostHolding(Disease.SLOW, 60_000L));
        BotDebuffState s = new BotDebuffState(chr);
        s.tickAt(4_000_000L);

        s.clearAll();

        assertEquals(0, s.size());
        verify(chr).dispelDebuff(Disease.SLOW);
    }

    @Test
    void aPluginSideApplyWillNotDoubleTrackWhatTheEngineHolds() {
        Character chr = bot();
        when(chr.hasDisease(Disease.SEAL)).thenReturn(true);
        BotDebuffState s = new BotDebuffState(chr);

        assertFalse(s.apply(Disease.SEAL, 60_000L, 1, null),
                "the engine's copy is adopted on the next tick, not mirrored with a second clock");
        assertEquals(0, s.size());
    }

    @Test
    void nothingIsReadFromTheEngineWhileNoDiseaseIsLive() {
        Character chr = bot();
        when(chr.getDiseasesSize()).thenReturn(0);
        BotDebuffState s = new BotDebuffState(chr);

        s.tickAt(5_000_000L);

        // The common case must stay allocation-free: getAllDiseases builds a fresh LinkedHashMap
        // under the character's lock, so an idle bot never pays for it.
        verify(chr, never()).getAllDiseases();
    }

    @Test
    void theEngineCopyOutranksASimulatedOneAtTheCap() {
        Character chr = bot();
        when(chr.getDiseasesSize()).thenReturn(1);
        when(chr.getAllDiseases()).thenReturn(hostHolding(Disease.SEAL, 60_000L));
        BotDebuffState s = new BotDebuffState(chr);
        long t0 = 8_000_000L;

        // Two simulated mob-contact diseases already fill the shared cap.
        assertTrue(s.apply(Disease.STUN, 60_000L, 1, null, t0));
        assertTrue(s.apply(Disease.POISON, 60_000L, 1, null, t0));
        assertEquals(BotDebuffTable.MAX_ACTIVE, s.size());

        s.tickAt(t0);

        // At the cap the question is only WHICH two survive. The engine's copy is already on every
        // observer's client (the engine broadcast the give frame and will not cancel it for us), so
        // dropping it would reproduce the original defect in a corner: a bot visibly wearing a Seal
        // while it keeps swinging. The simulated entry is the one whose cancel is ours to send.
        assertTrue(s.blocksAttack(), "the engine-applied Seal must be honoured at the cap");
        assertEquals(BotDebuffTable.MAX_ACTIVE, s.size(), "still at most two live diseases");
    }
}
