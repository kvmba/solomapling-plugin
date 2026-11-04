package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.gms.client.Character;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code removeMessageIfConditionMet} is a gate on the queue HEAD: it removes that line when the
 * condition holds and leaves the queue untouched otherwise.
 *
 * <p>It used to poll-and-re-offer, which moved the head to the TAIL, and it dereferenced the head
 * unconditionally - so an empty queue threw NPE and a false condition silently reordered every
 * pending line.
 */
class MessageQueueHeadGateTest {

    /** Character's static initializer reads Spring config/i18n; same stub the other tests use. */
    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(MessageSource.class))).thenReturn(messageSource);
        when(ctx.getBean(eq("logSource"), eq(MessageSource.class))).thenReturn(messageSource);
        when(ctx.getBean(eq("exceptionSource"), eq(MessageSource.class))).thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
    }

    @Test
    void anEmptyQueueIsNotAGateAndMustNotThrow() {
        MessageQueue queue = MessageQueue.getInstance();
        queue.getQueue(MessageQueue.QueueType.PRIMARY).clear();

        assertFalse(queue.removeMessageIfConditionMet(MessageQueue.QueueType.PRIMARY, false));
        assertFalse(queue.removeMessageIfConditionMet(MessageQueue.QueueType.PRIMARY, true));
    }

    @Test
    void aFalseConditionLeavesTheHeadInPlace() {
        MessageQueue queue = MessageQueue.getInstance();
        var primary = queue.getQueue(MessageQueue.QueueType.PRIMARY);
        primary.clear();
        ChatMessage first = message("first");
        ChatMessage second = message("second");
        primary.add(first);
        primary.add(second);

        assertFalse(queue.removeMessageIfConditionMet(MessageQueue.QueueType.PRIMARY, false));

        assertSame(first, primary.peek(), "the head must stay at the head, not move to the tail");
        assertSame(second, primary.toArray()[1]);
        primary.clear();
    }

    @Test
    void aTrueConditionRemovesExactlyTheHead() {
        MessageQueue queue = MessageQueue.getInstance();
        var primary = queue.getQueue(MessageQueue.QueueType.PRIMARY);
        primary.clear();
        ChatMessage first = message("first");
        ChatMessage second = message("second");
        primary.add(first);
        primary.add(second);

        assertTrue(queue.removeMessageIfConditionMet(MessageQueue.QueueType.PRIMARY, true));

        assertSame(second, primary.peek());
        primary.clear();
    }

    private static ChatMessage message(String content) {
        return new ChatMessage(mock(Character.class), content);
    }
}
