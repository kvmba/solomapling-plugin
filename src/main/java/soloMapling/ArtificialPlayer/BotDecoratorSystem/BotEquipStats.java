package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.server.ItemInformationProvider;

import java.util.Map;

/**
 * Aligns a freshly decorated bot's raw stats to the gear it is actually wearing.
 *
 * <p>The client builds every character's appearance through
 * {@code PacketCreator.addCharEquips -> ItemInformationProvider.canWearEquipment(chr, equips)},
 * which drops any equip whose {@code reqLevel / reqSTR / reqDEX / reqINT / reqLUK / reqPOP}
 * exceeds the character's own stats. Bots are dressed with level- and job-coherent gear
 * (a level-60 thief's coat can demand 90 DEX + 140 LUK), but {@code BotDecorate.setBotVariables}
 * only ever set the bot's level/job/gender - never its STR/DEX/INT/LUK or fame. So the bot's
 * raw stats stay at the template character's beginner values (4/4/4/4) and the host silently
 * omits the gear from the look packet: the items sit in slots -5/-6 (so
 * {@code BotEquipChecker} reads them as "dressed") while the client renders the bot bare.
 *
 * <p>This runs once per decoration pass and lifts each raw stat (and fame) to just cover the
 * gear now equipped, so {@code canWearEquipment} passes for every piece and the wire look
 * matches the server-side inventory. It never lowers a stat, and bot combat does not derive
 * damage from these stats (bot attacks roll off level/job), so the only effect is visibility.
 */
public class BotEquipStats {

    private BotEquipStats() {
    }

    /**
     * Raise the bot's STR/DEX/INT/LUK and fame so every currently equipped item passes the
     * host's wearability filter. Idempotent and never lowers a value. Call after the gear
     * (including the NX cosmetic layer) is on and fame has been rolled.
     */
    public static void alignToEquipped(Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        int needStr = 0, needDex = 0, needInt = 0, needLuk = 0, needPop = 0;
        // The host sums the equipped items' own stat bonuses on top of the raw stat, so those
        // bonuses count towards the requirement and only the shortfall has to be made up.
        int bonusStr = 0, bonusDex = 0, bonusInt = 0, bonusLuk = 0;

        for (Item item : bot.getInventory(InventoryType.EQUIPPED)) {
            if (!(item instanceof Equip equip)) {
                continue;
            }
            Map<String, Integer> stats = ii.getEquipStats(equip.getItemId());
            if (stats == null) {
                continue;
            }
            needStr = Math.max(needStr, stats.getOrDefault("reqSTR", 0));
            needDex = Math.max(needDex, stats.getOrDefault("reqDEX", 0));
            needInt = Math.max(needInt, stats.getOrDefault("reqINT", 0));
            needLuk = Math.max(needLuk, stats.getOrDefault("reqLUK", 0));
            needPop = Math.max(needPop, stats.getOrDefault("reqPOP", 0));
            bonusStr += equip.getStr();
            bonusDex += equip.getDex();
            bonusInt += equip.getInt();
            bonusLuk += equip.getLuk();
        }

        bot.setStr(baseFor(needStr, bonusStr, bot.getStr()));
        bot.setDex(baseFor(needDex, bonusDex, bot.getDex()));
        bot.setInt(baseFor(needInt, bonusInt, bot.getInt()));
        bot.setLuk(baseFor(needLuk, bonusLuk, bot.getLuk()));
        if (bot.getFame() < needPop) {
            bot.setFame(needPop);
        }
    }

    /**
     * The raw stat value that lets an equip with requirement {@code req} pass while the same
     * equip contributes {@code equipBonus} to that stat. The host sums the slot's equip
     * bonuses on top of the raw stat, so the raw stat only has to cover the shortfall.
     */
    static int baseFor(int req, int equipBonus, int current) {
        return Math.max(current, req - equipBonus);
    }
}
