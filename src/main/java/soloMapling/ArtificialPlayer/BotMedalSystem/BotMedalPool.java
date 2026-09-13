package soloMapling.ArtificialPlayer.BotMedalSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.provider.wz.XMLDomMapleData;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.io.FileInputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 合法称号（勋章）池 — the set of medals a bot may be handed.
 *
 * <p>A medal is the v83 "称号" equipment: item prefix {@code 114xxxx}, worn in the
 * {@code Me} slot (encoded slot {@code -49}). Once in that slot the host's
 * {@code addCharInfo} writes it into every character-look packet, so the title
 * shows above the bot with no extra packet code.
 *
 * <p>The pool is scanned once at startup from the client's {@code Character.wz}
 * (mirroring {@link soloMapling.itemPool.EquipMetadataCache}'s directory walk —
 * the filename IS the item id). We deliberately do NOT reuse
 * {@link soloMapling.itemPool.EquipMetadataCache}: the host {@code EquipType}
 * enum has no MEDAL value, so {@code 114xxxx} classifies as {@code UNDEFINED} and
 * the cache skips it entirely.
 *
 * <p>Admission rules (product requirements):
 * <ul>
 *   <li><b>Legal item only</b> — the id must be a real {@code islot=Me} medal whose
 *       name resolves through the host's localized String.wz. On a zh-CN server that
 *       means a name carrying han characters ({@link BotHelpers#isUsableItem(int)});
 *       half-finished WZ entries with no Chinese name are dropped.</li>
 *   <li><b>No fame / pet-intimacy titles</b> — medals whose name carries
 *       {@code 人气 / 宠物 / 亲密 / 好友} are excluded.</li>
 * </ul>
 */
public final class BotMedalPool {

    /** v83 medal range (host {@code ItemConstants.isMedal}): {@code [1140000, 1143000)}. */
    public static final int MEDAL_ID_MIN = 1140000;
    public static final int MEDAL_ID_MAX = 1143000;

    /** Medal equip slot (BodyPart.MEDAL = 49 → client-encoded -49). */
    public static final short MEDAL_SLOT = -49;

    /** Name fragments that mark a title we must NOT hand out. */
    private static final String[] EXCLUDED_NAME_FRAGMENTS = {"人气", "宠物", "亲密", "好友"};

    /** One admissible medal. Requirements come straight from WZ, read once at load. */
    public static final class Medal {
        public final int id;
        public final int reqLevel;
        public final int reqJob;   // 0 = any job; else bitmask 1=warrior 2=mage 4=bow 8=thief 16=pirate
        public final int reqStr;
        public final int reqDex;
        public final int reqInt;
        public final int reqLuk;
        public final int reqPop;
        public final String name;

        Medal(int id, int[] req, String name) {
            this.id = id;
            this.reqLevel = req[0];
            this.reqJob = req[1];
            this.reqStr = req[2];
            this.reqDex = req[3];
            this.reqInt = req[4];
            this.reqLuk = req[5];
            this.reqPop = req[6];
            this.name = name;
        }

        @Override
        public String toString() {
            return id + " " + name + " [lv" + reqLevel + (reqJob == 0 ? "" : " job" + reqJob) + "]";
        }
    }

    // Sorted ascending by id, so the assigner's low-id bias reads naturally.
    private static volatile List<Medal> medals = List.of();
    // Assigned once at the end of load(); volatile so the Loaded/legal state is safely published.
    private static volatile Set<Integer> legalIds = Set.of();
    private static volatile boolean loaded = false;

    private BotMedalPool() {
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** True if the id is a medal this server may hand to a bot. */
    public static boolean isLegal(int itemId) {
        return legalIds.contains(itemId);
    }

    /** All admissible medals (sorted ascending by id). */
    public static List<Medal> all() {
        return medals;
    }

    /**
     * Load the medal pool from the client WZ. Safe to call more than once; only
     * the first call scans. Never throws — a missing WZ leaves an empty pool and
     * bots simply wear no title.
     */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        long start = System.currentTimeMillis();
        List<Medal> found = new ArrayList<>();

        try {
            Path accessory = WZFiles.CHARACTER.getFile().resolve("Accessory");
            if (!Files.isDirectory(accessory)) {
                System.err.println("[BotMedalPool] Character.wz/Accessory not found at " + accessory);
                loaded = true;
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(accessory, "*.img.xml")) {
                for (Path file : stream) {
                    String fileName = file.getFileName().toString();
                    String base = fileName.substring(0, fileName.length() - ".img.xml".length());
                    int id;
                    try {
                        id = Integer.parseInt(base);
                    } catch (NumberFormatException e) {
                        continue; // not an id-named equip file
                    }
                    if (id < MEDAL_ID_MIN || id >= MEDAL_ID_MAX) {
                        continue; // not a medal
                    }

                    int[] req = readRequirements(file, accessory);
                    if (req == null) {
                        continue; // unreadable/half-finished WZ entry
                    }

                    // Legality gate: real, named (Chinese on a zh-CN server) item.
                    if (!BotHelpers.isUsableItem(id)) {
                        continue;
                    }
                    String name = BotHelpers.convertItemIdToName(id);
                    if (isExcludedName(name)) {
                        continue; // 人气 / 宠物亲密度 类称号不投放
                    }
                    found.add(new Medal(id, req, name));
                }
            }

            found.sort((a, b) -> Integer.compare(a.id, b.id));
            medals = Collections.unmodifiableList(found);
            Set<Integer> ids = new HashSet<>(found.size() * 2);
            for (Medal m : medals) {
                ids.add(m.id);
            }
            legalIds = ids;
        } catch (Exception e) {
            System.err.println("[BotMedalPool] Failed to scan medals: " + e);
        } finally {
            loaded = true;
        }

        System.out.println("[BotMedalPool] Loaded " + medals.size() + " legal medals in "
                + (System.currentTimeMillis() - start) + "ms");
    }

    private static boolean isExcludedName(String name) {
        if (name == null || name.equals("NULL")) {
            return true;
        }
        for (String fragment : EXCLUDED_NAME_FRAGMENTS) {
            if (name.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads {@code {reqLevel, reqJob, reqSTR, reqDEX, reqINT, reqLUK, reqPOP}} from a
     * medal's {@code info}, or null when the entry is unreadable. Read once at load so
     * the runtime eligibility check never touches WZ.
     */
    private static int[] readRequirements(Path file, Path parent) {
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            Data itemData = new XMLDomMapleData(fis, parent);
            Data info = itemData.getChildByPath("info");
            if (info == null) {
                return null;
            }
            return new int[]{
                    DataTool.getInt("reqLevel", info, 0),
                    DataTool.getInt("reqJob", info, 0),
                    DataTool.getInt("reqSTR", info, 0),
                    DataTool.getInt("reqDEX", info, 0),
                    DataTool.getInt("reqINT", info, 0),
                    DataTool.getInt("reqLUK", info, 0),
                    DataTool.getInt("reqPOP", info, 0)
            };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Medals this bot may legally wear right now. Enforces the host's
     * {@code canWearEquipment} rules so a title is never handed out that the
     * client would then strip from the look packet:
     * <ul>
     *   <li>{@code reqLevel <= bot level};</li>
     *   <li>{@code reqJob} is 0 (any) or shares a bit with the bot's base class;</li>
     *   <li>{@code reqSTR/DEX/INT/LUK/POP <= the bot's own totals}.</li>
     * </ul>
     */
    public static List<Medal> eligibleFor(Character bot) {
        if (bot == null || medals.isEmpty()) {
            return List.of();
        }
        int level = bot.getLevel();
        int jobBit = baseClassBit(bot.getJob());
        int str = bot.getTotalStr(), dex = bot.getTotalDex();
        int intel = bot.getTotalInt(), luk = bot.getTotalLuk();
        int fame = bot.getFame();

        List<Medal> out = new ArrayList<>();
        for (Medal m : medals) {
            if (m.reqLevel > level) {
                continue;
            }
            if (m.reqJob != 0 && (jobBit == 0 || (m.reqJob & jobBit) == 0)) {
                continue;
            }
            if (m.reqStr > str || m.reqDex > dex || m.reqInt > intel
                    || m.reqLuk > luk || m.reqPop > fame) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    /**
     * The v83 reqJob bitmask for a job's base class:
     * 1=warrior(1xx) 2=magician(2xx) 4=bowman(3xx) 8=thief(4xx) 16=pirate(5xx);
     * beginners and unknown jobs return 0 (so only jobless {@code reqJob==0} medals fit).
     */
    static int baseClassBit(Job job) {
        if (job == null) {
            return 0;
        }
        return switch (job.getId() / 100) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            case 4 -> 8;
            case 5 -> 16;
            default -> 0;
        };
    }
}
