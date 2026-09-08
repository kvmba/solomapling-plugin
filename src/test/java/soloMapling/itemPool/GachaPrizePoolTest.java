package soloMapling.itemPool;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.Environment.PluginResources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gacha prize pool exists to disappoint: big-name equips whose stats have
 * been cut to nothing, so the reward reads like a boss drop and lands as a
 * 1-def hat.
 * <p>
 * Only the WZ-independent half is asserted here. {@code Equip} and
 * {@code ItemInformationProvider} cannot initialize in a unit test (they pull
 * the loaded WZ and the host's DB), so {@code degrade()} is pinned through the
 * per-stat rule it is built from, and the pool is read straight from the yaml
 * the same way production does. The wiring between them is thin enough to read.
 */
class GachaPrizePoolTest {

    private static final String POOL_PATH = "itemPool/itemConfig/gachaPrizePool.yaml";

    /** The host checkout sits next door in this workspace; absent elsewhere. */
    private static final Path WZ_ZH_CN =
            Paths.get("../GMS083/gms-server/wz-zh-CN/String.wz");

    // --- cut(): the per-stat rule -------------------------------------------

    @Test
    void zeroStaysZeroRatherThanGoingNegative() {
        // Bare shorts with no clamping - a plain decrement would ship -1.
        assertEquals(0, GachaPrizePool.cut((short) 0));
    }

    @Test
    void smallStatsCollapseToZero() {
        // Below the divisor there is nothing left: 5 -> 0, 9 -> 0.
        assertEquals(0, GachaPrizePool.cut((short) 5));
        assertEquals(0, GachaPrizePool.cut((short) 9));
    }

    @Test
    void largeStatsAreCappedToOne() {
        // Zakum Helm def 150, Maple bow atk 78 - both must land on 1, not 15 or 7.
        assertEquals(1, GachaPrizePool.cut((short) 10));
        assertEquals(1, GachaPrizePool.cut((short) 78));
        assertEquals(1, GachaPrizePool.cut((short) 150));
        assertEquals(1, GachaPrizePool.cut(Short.MAX_VALUE));
    }

    /**
     * The contract is not "monotonic" but "nothing survives above 1, and never
     * below 0". A value already at the cap is left intact, which is why 1 comes
     * out as 1 while 2 - a bigger start - divides down to 0.
     */
    @Test
    void cutOnlyEverYieldsZeroOrOne() {
        for (int v = 0; v <= 400; v++) {
            short cut = GachaPrizePool.cut((short) v);
            assertTrue(cut >= 0, "negative stat at input " + v);
            assertTrue(cut <= 1, "stat survived above the cap at " + v + ": " + cut);
        }
    }

    /**
     * Below the divisor there is nothing left to keep, so mid-small values all
     * flatten to 0 while anything at the cap stays put. Either way the floor
     * only ever sees 0 or 1.
     */
    @Test
    void cutFlattensMidRangeValuesToZero() {
        assertEquals(0, GachaPrizePool.cut((short) 2));
        assertEquals(0, GachaPrizePool.cut((short) 3));
        assertEquals(1, GachaPrizePool.cut((short) 1));
    }

    /** A second pass must not drift further - whatever survives stays put. */
    @Test
    void cutIsIdempotent() {
        for (int v = 0; v <= 400; v++) {
            short once = GachaPrizePool.cut((short) v);
            assertEquals(once, GachaPrizePool.cut(once), "not idempotent at " + v);
        }
    }

    // --- pool contents -------------------------------------------------------

    @Test
    void poolHasBothSectionsAndIsLargeEnough() {
        Map<String, Object> root = readPool();
        List<Map<String, Object>> equips = section(root, "equips");
        List<Map<String, Object>> items = section(root, "items");

        assertTrue(equips.size() >= 50, "too few equips: " + equips.size());
        assertTrue(items.size() >= 50, "too few junk items: " + items.size());
    }

    /**
     * An untranslated id is filtered out at load, so the floor never shows raw
     * English - and more importantly the prize slot is never silently empty.
     * <p>
     * Checked against the zh-CN String.wz on disk rather than through
     * {@code BotHelpers.isUnusableItem}, which needs the loaded WZ and so
     * cannot run here. The rule being pinned is the same one the guard applies:
     * on a Chinese server a name only counts once it actually carries han.
     */
    @Test
    void everyIdInThePoolIsLocalized() {
        Assumptions.assumeTrue(Files.isDirectory(WZ_ZH_CN),
                "zh-CN String.wz not adjacent; skipping the localization sweep");
        Map<Integer, String> names = loadChineseNames();
        Map<String, Object> root = readPool();
        for (String key : List.of("equips", "items")) {
            for (Map<String, Object> row : section(root, key)) {
                int id = Integer.parseInt(String.valueOf(row.get("id")));
                String name = names.get(id);
                assertNotNull(name, "no zh-CN name at all for " + key + " id " + id);
                assertTrue(containsHan(name),
                        "untranslated (" + name + ") id in " + key + ": " + id);
            }
        }
    }

    @Test
    void everyEntryCarriesAPositiveWeight() {
        Map<String, Object> root = readPool();
        for (String key : List.of("equips", "items")) {
            for (Map<String, Object> row : section(root, key)) {
                int weight = row.get("weight") == null
                        ? 1
                        : Integer.parseInt(String.valueOf(row.get("weight")));
                assertTrue(weight > 0, "non-positive weight in " + key
                        + " for id " + row.get("id"));
            }
        }
    }

    @Test
    void noDuplicateIdsAcrossThePool() {
        Map<String, Object> root = readPool();
        List<Integer> seen = new ArrayList<>();
        for (String key : List.of("equips", "items")) {
            for (Map<String, Object> row : section(root, key)) {
                int id = Integer.parseInt(String.valueOf(row.get("id")));
                assertFalse(seen.contains(id), "duplicate id " + id + " in " + key);
                seen.add(id);
            }
        }
    }

    /**
     * The joke needs name recognition, so the pool should carry ids whose wz
     * entries are far above what survives the cut - otherwise there is no gap
     * between what the name promises and what the player gets.
     */
    @Test
    void poolContainsHighStatEquipsSoTheCutIsVisible() {
        // Zakum Helm (400 points of stats), the Lv70 Maple cape, the Maple bow.
        // All are localized, so all survive the load filter.
        Map<String, Object> root = readPool();
        List<Integer> ids = new ArrayList<>();
        for (Map<String, Object> row : section(root, "equips")) {
            ids.add(Integer.parseInt(String.valueOf(row.get("id"))));
        }
        for (int marquee : new int[]{1002357, 1102168, 1452045, 1092008}) {
            assertTrue(ids.contains(marquee),
                    "marquee equip " + marquee + " missing from the prize pool");
        }
    }

    /**
     * Read every numeric name entry out of the zh-CN String.wz with a plain
     * StAX walk. The host-side provider cannot initialize in a unit test, so
     * this goes at the same files directly; what is being pinned is the rule
     * the load filter applies, not the plumbing around it.
     */
    private static Map<Integer, String> loadChineseNames() {
        Map<Integer, String> names = new HashMap<>();
        for (String file : List.of("Eqp", "Consume", "Etc", "Ins", "Cash", "Pet")) {
            Path path = WZ_ZH_CN.resolve(file + ".img.xml");
            if (!Files.isRegularFile(path)) continue;
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            try (InputStream in = Files.newInputStream(path)) {
                XMLStreamReader reader = factory.createXMLStreamReader(in);
                String currentId = null;
                while (reader.hasNext()) {
                    int ev = reader.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String el = reader.getLocalName();
                        if ("imgdir".equals(el)) {
                            String name = reader.getAttributeValue(null, "name");
                            if (name != null && name.matches("\\d+")) {
                                currentId = name;
                            }
                        } else if ("string".equals(el) && currentId != null
                                && "name".equals(reader.getAttributeValue(null, "name"))) {
                            // <string name="name" value="..."/> is self-closing:
                            // the text lives in the value attribute, not the body.
                            String value = reader.getAttributeValue(null, "value");
                            if (value != null) {
                                names.put(Integer.parseInt(currentId), value);
                            }
                            currentId = null;
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT
                            && "imgdir".equals(reader.getLocalName())) {
                        currentId = null;
                    }
                }
                reader.close();
            } catch (Exception e) {
                throw new AssertionError("could not read " + path, e);
            }
        }
        return names;
    }

    /** Same han-character test BotHelpers applies on a zh-CN server. */
    private static boolean containsHan(String s) {
        return s != null && s.codePoints().anyMatch(Character::isIdeographic);
    }

    // --- prize odds ----------------------------------------------------------

    /**
     * A round must be able to come up empty - otherwise the jackpot is not an
     * event, it is a given - but it must not be so rare the pool never shows.
     * Sampled loosely: this is a coin with a known bias, not an exact count.
     */
    @Test
    void someRoundsCarryNoPrizeAtAll() {
        GachaPrizePool pool = GachaPrizePool.load();
        int withPrize = 0;
        int rounds = 4000;
        for (int i = 0; i < rounds; i++) {
            if (pool.rollForRound() != null) withPrize++;
        }
        double rate = (double) withPrize / rounds;
        assertTrue(rate > 0.02, "prize never appears: " + rate);
        assertTrue(rate < 0.40, "prize too common to be an event: " + rate);
    }

    /** Whatever a round yields is a single usable id, never a pair of them. */
    @Test
    void aRoundYieldsAtMostOneUsablePrize() {
        GachaPrizePool pool = GachaPrizePool.load();
        for (int i = 0; i < 2000; i++) {
            GachaPrizePool.Entry entry = pool.rollForRound();
            assertTrue(entry == null || entry.itemId > 0,
                    "round produced an unusable prize id");
        }
    }

    private static Map<String, Object> readPool() {
        try (Reader r = PluginResources.openReader(POOL_PATH)) {
            return (Map<String, Object>) new YamlReader(r).read();
        } catch (Exception e) {
            throw new AssertionError("could not read " + POOL_PATH, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> section(Map<String, Object> root, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) root.get(key);
        return rows == null ? List.of() : rows;
    }
}
