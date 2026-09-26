package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins LPQ's stage arithmetic against the real room ids: 922010100..922010900 step by
 * HUNDRED, so the stage is the delta over 100. The raw delta read stage 2 as 101 and fell
 * through every switch case past stage 1 - the bots stood still from stage 2 on.
 */
class LudiPqStageOfTest {

    private static int stageOf(int mapId) throws Exception {
        Method m = LudiPQBot.class.getDeclaredMethod("stageOf", int.class);
        m.setAccessible(true);
        return (int) m.invoke(null, mapId);
    }

    @Test
    void everyRoomReadsItsOwnStage() throws Exception {
        assertEquals(1, stageOf(922010100));
        assertEquals(2, stageOf(922010200));
        assertEquals(3, stageOf(922010300));
        assertEquals(4, stageOf(922010400));
        assertEquals(5, stageOf(922010500));
        assertEquals(6, stageOf(922010600));
        assertEquals(7, stageOf(922010700));
        assertEquals(8, stageOf(922010800));
        assertEquals(9, stageOf(922010900));
    }

    @Test
    void outsideTheRunIsNotAStage() throws Exception {
        assertEquals(-1, stageOf(922010000));
        assertEquals(-1, stageOf(922011000));
    }
}
