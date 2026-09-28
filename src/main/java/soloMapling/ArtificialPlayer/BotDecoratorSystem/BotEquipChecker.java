package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.net.server.Server;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.BotLogger.log;
import static soloMapling.server.ExecutorServiceManager.runAsync;

public class BotEquipChecker {

    private static ScheduledFuture<?> task;
    private static final long INTERVAL_MS = 2 * 60 * 1000;

    // -5 holds a top (104xxxx) OR an overall (105xxxx) - host BodyPart.LONGCOAT shares
    // the top's slot value, so the slot alone cannot tell them apart. The old check
    // demanded BOTH -5 and -6, which flagged every overall wearer as naked: an overall
    // never fills -6, and the repair (equipTopBottom) cannot put pants on a pirate
    // (pirates have no coat/pants in WZ, only overalls), so the flag never cleared.
    private static final short SLOT_TOP_OR_OVERALL = -5;
    private static final short SLOT_PANTS = -6;

    /** v83 overall id band (ItemConstants maps the 105 prefix to BodyPart.LONGCOAT). */
    private static final int OVERALL_ID_BAND = 105;
    /** 7-digit item id -> its 3-digit equip prefix (1052095 / 10000 == 105). */
    private static final int PREFIX_DIVISOR = 10000;

    public static void start() {
        if (task != null) return;
        task = ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(
                BotEquipChecker::check, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS
        );
        System.out.println("[BotEquipChecker] Started — interval=" + (INTERVAL_MS / 60000) + "min");
    }

    private static void check() {
        try {
            long startMs = System.currentTimeMillis();
            List<Character> allChars = new ArrayList<>(
                    soloMapling.server.SoloMaplingUtilities.world.getPlayerStorage().getAllCharacters()
            );

            int totalBots = 0;
            List<Character> naked = new ArrayList<>();
            for (Character chr : allChars) {
                if (!isBot(chr)) continue;
                // Mapless bots (e.g. the Console bot) can't be dressed - equipping
                // broadcasts a char-look update to the bot's map.
                if (chr.getMap() == null) continue;
                totalBots++;
                if (isNaked(chr)) naked.add(chr);
            }

            long elapsed = System.currentTimeMillis() - startMs;
            System.out.println("[BotEquipChecker] Scan complete: " + naked.size() + "/" + totalBots
                    + " naked (" + elapsed + "ms)");

            if (!naked.isEmpty()) {
                StringBuilder sample = new StringBuilder();
                for (int i = 0; i < naked.size() && i < 10; i++) {
                    if (i > 0) sample.append(", ");
                    Character chr = naked.get(i);
                    sample.append(chr.getName()).append("(job=").append(chr.getJob().name())
                            .append(" lv=").append(chr.getLevel()).append(")");
                }
                log("[BotEquipChecker] Found " + naked.size() + "/" + totalBots + " naked, fixing..."
                        + " sample: " + sample);
                final int[] fixedCount = {0};
                final int[] failedCount = {0};
                for (Character chr : naked) {
                    runAsync(() -> {
                        try {
                            BotDecorateEquips.equipTopBottom(chr);
                            // The repair equips a top/bottom that may out-demand this bot's raw
                            // stats; align them or the host's canWearEquipment filter hides the
                            // piece and the bot stays visually bare despite the filled slot.
                            BotEquipStats.alignToEquipped(chr);
                            fixedCount[0]++;
//                            System.out.println("[BotEquipChecker] Equipped " + chr.getName()
//                                    + " (job=" + chr.getJob().name() + " lv=" + chr.getLevel()
//                                    + ") [" + fixedCount[0] + "/" + naked.size() + "]");
                        } catch (Exception e) {
                            failedCount[0]++;
                            System.out.println("[BotEquipChecker] FAILED " + chr.getName()
                                    + " (job=" + chr.getJob().name() + " lv=" + chr.getLevel()
                                    + "): " + e.getMessage());
                        }
                    });
                }
            } else {
                System.out.println("[BotEquipChecker] All " + totalBots + " bots are dressed.");
            }
        } catch (Exception e) {
            System.out.println("[BotEquipChecker] Error during check: " + e.getMessage());
            log("[BotEquipChecker] Error: " + e.getMessage());
        }
    }

    private static boolean isNaked(Character chr) {
        Item topOrOverall = chr.getInventory(InventoryType.EQUIPPED).getItem(SLOT_TOP_OR_OVERALL);
        if (topOrOverall == null) {
            return true; // nothing on the torso slot - bare regardless of pants
        }
        return isNakedWearing(topOrOverall.getItemId(),
                chr.getInventory(InventoryType.EQUIPPED).getItem(SLOT_PANTS) != null);
    }

    /**
     * Package-visible pure decision given the torso item's id and whether the pants
     * slot is filled: unit-tested without a Character (host Equip can't be built
     * outside a booted server).
     */
    static boolean isNakedWearing(int topOrOverallItemId, boolean hasPants) {
        // An overall covers torso and legs together; a top needs pants beside it.
        return topOrOverallItemId / PREFIX_DIVISOR != OVERALL_ID_BAND && !hasPants;
    }
}
