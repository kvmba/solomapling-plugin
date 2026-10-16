package soloMapling.ArtificialPlayer;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bot event subscriptions follow the bot's RUNNING lifetime, and never stack.
 *
 * <p>Two defects lived at this seam. (1) Several bot types subscribed in their CONSTRUCTOR, while
 * the only release is {@code unsubscribeAll} in {@code stopScheduledTask} - which never runs for a
 * bot that was wrapped but never started (the GM {@code createAndSetBot} paths, e.g.
 * {@code !bot gamezonehost <cid>}), so the subscription outlived the bot on the process-wide bus.
 * (2) GachaBot re-subscribed from {@code resetGachaBotState} on every round; the bus APPENDS on
 * every subscribe, so each event fired the handler once per accumulated copy.
 *
 * <p>{@code BotSM.subscribeEvent} is the one seam both use now: idempotent per session, armed from
 * {@code startScheduledTask} and released with the rest at stop. These cases pin the seam itself.
 */
class BotEventSubscriptionLifecycleTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        // Mocking Character runs its static initializer, which resolves host services through
        // ServerManager - the standard stub every test in this module uses.
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
        GameConfigDO cfg = new GameConfigDO();
        cfg.setConfigType("server");
        cfg.setConfigSubType("0");
        cfg.setConfigCode("update_interval");
        cfg.setConfigValue("100");
        cfg.setConfigClazz("java.lang.Long");
        GameConfig.add(cfg);
        org.gms.net.server.Server.getInstance();
    }

    @AfterEach
    void cleanUp() throws Exception {
        // The bus is process-wide: drop only what THIS test added, never another class's
        // subscriptions (a blanket wipe would silently break any test sharing the JVM).
        for (BotSM bot : createdBots) {
            EventBus.getInstance().unsubscribeAll(bot);
        }
        createdBots.clear();
    }

    @Test
    void subscribingTwiceKeepsExactlyOneEntry() {
        BotSM bot = stubBot();
        bot.subscribeEvent(EventType.LEVEL_UP);
        bot.subscribeEvent(EventType.LEVEL_UP); // e.g. a stop/start cycle, or a per-round re-arm

        assertEquals(1, countOf(EventType.LEVEL_UP, bot),
                "re-arming a subscription must replace, never stack - the bus appends on every call");
    }

    @Test
    void theStopSeamReleasesThemAll() {
        BotSM bot = stubBot();
        bot.subscribeEvent(EventType.LEVEL_UP);
        bot.subscribeEvent(EventType.SCROLLING);
        assertEquals(1, countOf(EventType.LEVEL_UP, bot));
        assertEquals(1, countOf(EventType.SCROLLING, bot));

        // The production release seam (this also exercises the wheel-unregister side harmlessly).
        bot.stopScheduledTask();

        assertEquals(0, countOf(EventType.LEVEL_UP, bot), "a stopped bot must hold no subscription");
        assertEquals(0, countOf(EventType.SCROLLING, bot), "a stopped bot must hold no subscription");
    }

    /**
     * A BotSM that runs REAL method bodies without its constructor. {@code subscribeEvent} and
     * {@code stopScheduledTask} touch only the bus, the wheel and the character, so an
     * Objenesis-instantiated mock with {@code CALLS_REAL_METHODS} exercises the production bodies -
     * while the real constructor would drag in the host's WZ-backed ItemInformationProvider.
     */
    private static BotSM stubBot() {
        Character chr = mock(Character.class, RETURNS_DEEP_STUBS);
        when(chr.getId()).thenReturn(990_777);
        when(chr.getName()).thenReturn("sub-lifecycle");
        BotSM bot = mock(BotSM.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        setCharacter(bot, chr);
        createdBots.add(bot);
        return bot;
    }

    /** The bots this test put on the process-wide bus, so the after-each can take them off it. */
    private static final java.util.List<BotSM> createdBots = new java.util.ArrayList<>();

    private static void setCharacter(BotSM bot, Character chr) {
        try {
            java.lang.reflect.Field f = BotSM.class.getDeclaredField("character");
            f.setAccessible(true);
            f.set(bot, chr);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("BotSM.character is unreachable", e);
        }
    }

    private static int countOf(EventType type, EventSubscriber subscriber) {
        int n = 0;
        for (EventSubscriber sub : subscribersOf(type)) {
            if (sub == subscriber) {
                n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static List<EventSubscriber> subscribersOf(EventType type) {
        try {
            Field f = EventBus.class.getDeclaredField("subscribers");
            f.setAccessible(true);
            Map<EventType, List<EventSubscriber>> map = (Map<EventType, List<EventSubscriber>>) f.get(
                    EventBus.getInstance());
            List<EventSubscriber> list = map.get(type);
            return list == null ? List.of() : list;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EventBus.subscribers is unreachable", e);
        }
    }
}
