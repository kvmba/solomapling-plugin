package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the carnival's loot contract: the arena's monsters drop consume-on-pickup props on every
 * kill, and the bot has to take them the way LPQ's bot takes its passes.
 *
 * <p>Why this is not only a behaviour test: the two cheap "fixes" are both defects.
 * <ul>
 *   <li>sweeping <em>everything</em> on the arena floor would also eat ordinary equipment and
 *       scroll drops - farming loot the mode never handed the party;</li>
 *   <li>sweeping the consumable ids <em>outside</em> an arena would vacuum a town map, because the
 *       same ids sit on other monsters' drop tables.</li>
 * </ul>
 * So the contract is: an explicit id list, arena-only, wired into both loot seams the other quests
 * use (the 250ms combat beat, and the macro tick's walk-up pass).
 */
class CarnivalLootTest {

    /**
     * The consume-on-pickup props CPQ's monsters drop, straight out of WZ
     * ({@code Item.wz/Consume/0202.img.xml}, {@code spec/consumeOnPickup=1}) and the engine's drop
     * table ({@code drop_data} rows 9300127-36 and 9300315-24).
     */
    private static final int[] EXPECTED = {
            2022157, 2022158, 2022159,                         // Carnival Point 1-3 (CP on pickup)
            2022160, 2022161, 2022162, 2022163,                // party mana / elixir / power / all-cure
            2022164, 2022165, 2022166,                         // mini cube / cube of darkness / stunner
            2022174, 2022175, 2022176, 2022177, 2022178,       // white potion / elixir / power elixir / mana / all-cure
    };

    @Test
    void theConsumableListIsExactlyTheArenasConsumeOnPickupSet() {
        assertArrayEquals(EXPECTED, CarnivalPqData.ARENA_CONSUMABLES);
        Set<Integer> unique = new HashSet<>();
        for (int id : CarnivalPqData.ARENA_CONSUMABLES) {
            assertTrue(unique.add(id), "duplicate id " + id);
        }
    }

    @Test
    void everyListedItemIsActuallyAConsumeOnPickupProp() throws Exception {
        // The point of the list is that the ENGINE consumes these on pickup (CP is paid by
        // spec/cp, cures by spec/party). A plain potion would merely be pocketed - and a bot with
        // a full use-inventory would then fail the pickup outright.
        String xml = consumeWz();
        assumeTrue(xml != null, "Item.wz/Consume not resolvable from this working directory");
        for (int id : CarnivalPqData.ARENA_CONSUMABLES) {
            assertTrue(section(xml, id).contains("consumeOnPickup"),
                    id + " must carry spec/consumeOnPickup in WZ");
        }
    }

    @Test
    void theCombatBeatScoopsThemArenaOnly() throws Exception {
        String bot = javaSource(CARNIVAL_BOT);
        assertTrue(bot.contains("protected int[] steppedOnLootIds()"),
                "the carnival must opt into the combat beat's stepped-on sweep");
        assertTrue(bot.contains(
                        "CarnivalPqData.isArena(getChr().getMap()) ? CarnivalPqData.ARENA_CONSUMABLES : new int[0]"),
                "the sweep must be arena-only: a reward room's floor is the party's");
        assertTrue(javaSource(PARTY_QUEST_BOT)
                        .contains("PqActions.lootSteppedOn(bot, steppedOnLootIds())"),
                "the shared sweep is what carries these ids into the at-feet pickups");
    }

    @Test
    void theMacroTickAlsoWalksUpToDropsTheFightLeftBehind() throws Exception {
        String bot = javaSource(CARNIVAL_BOT);
        int branch = bot.indexOf("if (!map.getMonsters().isEmpty()) {");
        int fight = bot.indexOf("PqActions.seekAndAttack(getChr(), false, CarnivalPqData.ARENA_CONSUMABLES)");
        int loot = bot.indexOf("PqActions.loot(getChr(), getChr().getPosition(), ARENA_LOOT_RADIUS_PX,");
        assertTrue(branch >= 0 && fight > branch, "the fight must pass the ids, so a pickup precedes the chase");
        assertTrue(loot > fight, "the walk-up pass must follow the fight order");
        // The loot pass must sit OUTSIDE that branch: a drop the last kill left behind lands when the
        // room has no monsters left, and a sweep inside the branch would never run again.
        assertTrue(loot > matchingBrace(bot, branch),
                "the loot pass must sit outside the 'room has monsters' branch");
    }

    @Test
    void theReachStaysANeighbourhoodNotTheWholeRoom() throws Exception {
        // Same scale as LPQ's LOOT_RADIUS_PX: a kill lands its drop at the mob's x, so a
        // walk-up-and-take radius keeps a bot on its own share instead of racing the team.
        assertTrue(javaSource(CARNIVAL_BOT).contains("ARENA_LOOT_RADIUS_PX = 260"));
    }

    @Test
    void theHookIsAnOverrideOfTheSharedSeam() throws Exception {
        Method hook = CarnivalPQBot.class.getDeclaredMethod("steppedOnLootIds");
        assertTrue(Modifier.isProtected(hook.getModifiers()));
        assertFalse(Modifier.isStatic(hook.getModifiers()));
        assertEquals(int[].class, hook.getReturnType());
    }

    // =========================================================================
    // source / WZ access
    // =========================================================================

    /** The index just past the {@code }} that closes the block opened at {@code openBrace}. */
    private static int matchingBrace(String source, int openBrace) {
        int depth = 0;
        for (int i = source.indexOf('{', openBrace); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return source.length();
    }

    /** The one {@code <imgdir name="0xxxxxxx">} block for {@code itemId}, or null if absent. */
    private static String section(String xml, int itemId) {
        String header = String.format("<imgdir name=\"0%d\">", itemId);
        int start = xml.indexOf(header);
        if (start < 0) {
            return "";
        }
        return xml.substring(start, xml.indexOf("\n  </imgdir>", start));
    }

    /** The client's Consume image for the 2022xxx band, or null when the host WZ is not adjacent. */
    private static String consumeWz() throws Exception {
        for (Path candidate : new Path[]{
                Path.of("wz/Item.wz/Consume/0202.img.xml"),
                Path.of("../GMS083/gms-server/wz/Item.wz/Consume/0202.img.xml")}) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String javaSource(String path) throws Exception {
        try (var in = CarnivalLootTest.class.getClassLoader().getResourceAsStream(path);
             var raw = in != null ? in : new java.io.FileInputStream("src/main/java/" + path)) {
            return new String(raw.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final String CARNIVAL_BOT =
            "soloMapling/ArtificialPlayer/BotTypes/Carnival/CarnivalPQBot.java";
    private static final String PARTY_QUEST_BOT =
            "soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java";
}
