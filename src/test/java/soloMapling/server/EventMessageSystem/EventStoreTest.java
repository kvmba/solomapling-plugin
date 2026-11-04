package soloMapling.server.EventMessageSystem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The store is a ring buffer: {@code head} wraps and old slots are overwritten in place. Reading
 * the live entries as {@code buffer[0..size)} returned the stale slots the ring had already passed
 * and missed the newest ones once a full lap had been written.
 */
class EventStoreTest {

    @Test
    void typeQueryReturnsTheLiveWindowAfterTheRingWrapped() {
        EventStore store = new EventStore(4);
        GameEvent first = event(EventType.CHAT_GENERAL, 1);
        GameEvent second = event(EventType.MAP_ENTERED, 2);
        GameEvent third = event(EventType.CHAT_GENERAL, 3);
        GameEvent fourth = event(EventType.CHAT_GENERAL, 4);
        GameEvent fifth = event(EventType.CHAT_GENERAL, 5);
        store.add(first);
        store.add(second);
        store.add(third);
        store.add(fourth);
        store.add(fifth); // overwrites `first`

        List<GameEvent> general = store.getEventsByType(EventType.CHAT_GENERAL);

        assertEquals(List.of(third, fourth, fifth), general,
                "only the live window answers, in insertion order");
    }

    @Test
    void locationQueryReturnsTheLiveWindowAfterTheRingWrapped() {
        EventStore store = new EventStore(3);
        GameEvent second = event(EventType.CHAT_GENERAL, 2, 10);
        GameEvent third = event(EventType.CHAT_GENERAL, 3, 10);
        GameEvent newest = event(EventType.CHAT_GENERAL, 4, 10);
        store.add(event(EventType.CHAT_GENERAL, 1, 10));
        store.add(second);
        store.add(third);
        store.add(newest); // overwrites entry 1

        List<GameEvent> onMapTen = store.getEventsByLocation(0, 1, 10);

        assertEquals(List.of(second, third, newest), onMapTen,
                "the window slides forward instead of replaying the overwritten head slot");
    }

    private static GameEvent event(EventType type, int timestamp) {
        GameEvent event = mock(GameEvent.class);
        when(event.getType()).thenReturn(type);
        when(event.getTimestamp()).thenReturn((long) timestamp);
        return event;
    }

    private static GameEvent event(EventType type, int timestamp, int mapId) {
        GameEvent event = event(type, timestamp);
        when(event.getWorld()).thenReturn(0);
        when(event.getChannel()).thenReturn(1);
        org.gms.server.maps.MapleMap map = mock(org.gms.server.maps.MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(event.getMap()).thenReturn(map);
        return event;
    }
}
