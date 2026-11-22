package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import org.gms.util.Pair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrbisRecordDayTest {

    @Test
    void readsTheInstanceMusicBoxInsteadOfTheWallClock() {
        MapleMap room = mock(MapleMap.class);
        Reactor music = mock(Reactor.class);
        when(room.getReactorByName("music")).thenReturn(music);
        when(music.isAlive()).thenReturn(true);
        when(music.getReactorType()).thenReturn(100);
        when(music.getEventState()).thenReturn((byte) 2);
        when(music.getReactItem((byte) 2)).thenReturn(new Pair<>(4001058, 1));
        when(music.getReactItem((byte) 6)).thenReturn(new Pair<>(4001062, 1));
        assertEquals(4001058, OPQBot.wantedRecord(room));
        when(music.getEventState()).thenReturn((byte) 6);
        assertEquals(4001062, OPQBot.wantedRecord(room));

        when(music.getReactorType()).thenReturn(-1);
        assertEquals(-1, OPQBot.wantedRecord(room));
    }

    @Test
    void doesNotInventARecordWhenThereIsNoMusicBox() {
        assertEquals(-1, OPQBot.wantedRecord(mock(MapleMap.class)));
    }
}
