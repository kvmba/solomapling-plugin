package soloMapling.ArtificialPlayer.BotTypes.Ellin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two Ellin rooms that carry the run after the frog room.
 *
 * <p>Both were handled by "nothing": {@code EllinPQBot.workStage} returned for the frog room and
 * then fell through to a generic fight/break loop, so no one ever broke the stone box and no one
 * ever fed the altar. The consequences are structural, not cosmetic:</p>
 *
 * <ul>
 *   <li>{@code 930000500} - the exit NPC {@code 2133004} refuses to warp the team until the
 *       LEADER carries {@code 4001163} (the Purple Stone of Magic). The stone only comes out of
 *       the box reactors in that room ({@code reactordrops} V1.0.58 row 374: 3002001 →
 *       4001163), so a party whose bots never break a box can never leave the room.</li>
 *   <li>{@code 930000600} - the altar reactor {@code 3001000} is an item reactor (type 100,
 *       wants {@code 4001163}x1) whose script spawns {@code 9300180}; the Poison Golem chain ends
 *       at {@code 9300182}, and {@code EllinPQ.monsterKilled} clears the run when it dies. An
 *       altar nobody feeds spawns nothing.</li>
 * </ul>
 *
 * <p>{@code Character} needs a Spring context, so the bot cannot be stepped in a plain unit test;
 * these read the source, the same approach the attack-layer guards use.</p>
 */
class EllinStoneAndAltarTest {

    private static final Path BOT = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ellin/EllinPQBot.java");
    private static final Path DATA = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ellin/EllinPqData.java");

    @Test
    void theStoneRoomBreaksTheBoxAndHandsTheStoneOver() throws IOException {
        String bot = read(BOT);
        assertTrue(bot.contains("mapId == EllinPqData.STONE_BOX_ROOM"),
                "the stone room needs its own branch; the generic loop never broke a box there");
        String work = methodBody(bot, "private void workStoneBox()");
        assertTrue(work.contains("EllinPqData.STONE_BOX"),
                "it has to strike the room's box reactor");
        assertTrue(work.contains("handItemsToLeader") && work.contains("PURPLE_STONE"),
                "the stone must reach the leader - the exit NPC only reads HIS inventory");
    }

    @Test
    void theAltarRoomFeedsTheAltarAndFightsWhatItSpawns() throws IOException {
        String bot = read(BOT);
        assertTrue(bot.contains("mapId == EllinPqData.ALTAR_ROOM"),
                "the altar room needs its own branch");
        String work = methodBody(bot, "private void workAltar()");
        assertTrue(work.contains("feedReactor") && work.contains("ALTAR"),
                "the altar is an item reactor; hitting it does nothing");
        assertTrue(work.contains("seekAndAttack"),
                "the golem it raises is what clears the run, so the bot has to fight it");
    }

    /** The two reactor ids and the item are the quest's own, taken from WZ and the drop table. */
    @Test
    void theIdsAreTheQuestsOwn() throws IOException {
        String data = read(DATA);
        assertTrue(data.contains("STONE_BOX = 3002001"), "930000500's box reactor is 3002001");
        assertTrue(data.contains("PURPLE_STONE = 4001163"), "the stone the exit NPC demands");
        assertTrue(data.contains("ALTAR = 3001000"), "930000600's altar reactor is 3001000");
        assertTrue(data.contains("STONE_BOX_ROOM = 930000500") && data.contains("ALTAR_ROOM = 930000600"),
                "the two rooms are 930000500 and 930000600");
    }

    /**
     * The frog room's "catch, don't kill" rule must survive: this bot must still not swing there,
     * or it would kill the guards the party has to capture.
     */
    @Test
    void theFrogRoomStillForbidsSwings() throws IOException {
        String bot = read(BOT);
        String sweep = methodBody(bot, "protected boolean fightsOnSweep()");
        assertTrue(sweep.contains("FROG_ROOM"),
                "the frog room's guards die to ordinary swings and cannot be captured after that");
        assertFalse(sweep.contains("ALTAR_ROOM"),
                "the altar room is a fight - it must stay swingable");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertFalse(start < 0, "method not found: " + signature);
        int depth = 0;
        int i = src.indexOf('{', start);
        for (; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(start, i + 1);
            }
        }
        return src.substring(start);
    }
}
