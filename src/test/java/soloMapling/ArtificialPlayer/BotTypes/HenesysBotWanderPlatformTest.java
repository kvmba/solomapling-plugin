package soloMapling.ArtificialPlayer.BotTypes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the wander roll's "stay where I am" branch against an unresolvable position.
 *
 * <p>{@code getCurrentPlatform} legitimately returns null while the bot's position resolves to no
 * main platform - mid-jump between them, on a climb/connector recording, off the recorded bounds -
 * and the branch must read that as "not on a wanderable platform", never crash. It used to ask the
 * map's set directly, and the two list flavours answer null differently: Pet Park's set is
 * {@code List.of("m1")} (an ImmutableCollections List12), whose {@code contains(null)} throws NPE
 * (List12.indexOf, Objects.requireNonNull), while every other map's set is the ArrayList from
 * {@code getMainPlatformIds}, whose {@code contains(null)} answers false. That split crashed
 * {@code HenesysBot.wanderPlatforms} on Pet Park only; this pins the single guard the branch now
 * routes through.
 */
class HenesysBotWanderPlatformTest {

    @Test
    void anUnresolvablePositionIsNotOnPetParksPlatform() {
        // The exact set Pet Park hands the wander roll - the List12 that used to throw.
        assertFalse(HenesysBot.isOnWanderablePlatform(null, List.of("m1")),
                "a null current platform must answer false, not NPE");
    }

    @Test
    void anUnresolvablePositionIsNotOnAnyOtherMapsPlatforms() {
        // Every other map hands out the ArrayList from getMainPlatformIds; same false, no NPE.
        assertFalse(HenesysBot.isOnWanderablePlatform(null, new ArrayList<>(List.of("m1", "m2"))));
    }

    @Test
    void resolvedPositionsStillMatchTheirPlatforms() {
        assertTrue(HenesysBot.isOnWanderablePlatform("m1", List.of("m1")));
        assertTrue(HenesysBot.isOnWanderablePlatform("m2", List.of("m1", "m2")));
        assertFalse(HenesysBot.isOnWanderablePlatform("m2", List.of("m1")),
                "Pet Park wanders only its m1");
    }
}
