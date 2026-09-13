package soloMapling.ArtificialPlayer.BotMedalSystem;

import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import soloMapling.ArtificialPlayer.BotCustomization;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 称号（勋章）投放 — hands a freshly decorated bot a medal so "老玩家有称号、新手无称号"
 * reads on the street.
 *
 * <p>A medal is the v83 title equip: worn in the {@code Me} slot (encoded {@code -49}).
 * Equipping it is pure inventory state — the host's {@code addCharInfo} then writes it
 * into every character-look packet, and {@code equipChanged()} broadcasts the change to
 * observers already on the map. No packet code, no host changes.
 *
 * <p>Injected at the single decoration finishing point, {@code BotDecorate.setBotVariables},
 * right after {@code BotFame.apply}, so <b>every</b> bot that is decorated — all types,
 * companions included — is covered. Level overrides (OPQ, attack-test bots) re-roll via
 * {@link #reroll(Character)} so the title matches the bot's final level.
 *
 * <p>The medals' own stat bonuses take effect automatically: the host's
 * {@code recalcEquipStats} sums every equipped {@code Equip}'s STR/DEX/INT/LUK/MHP/… into
 * the character's totals, and the medal sits in the same EQUIPPED inventory.
 */
public final class BotMedal {

    /** Global kill-switch, mirroring {@code BotDecorateNX.ENABLED}. */
    public static boolean ENABLED = true;

    private BotMedal() {
    }

    /**
     * Roll and apply a title for a decorated bot. Call after level/job/gender are final
     * and after {@code BotFame.apply} (fame feeds the reqPOP check and the roll is
     * level-based). Idempotent-ish: it leaves an existing title in place when this pass
     * rolls "no title".
     */
    public static void apply(Character bot) {
        if (!ENABLED || bot == null || bot.getMap() == null) {
            return;
        }
        if (bot.getLevel() < BotMedalAssigner.MIN_LEVEL) {
            remove(bot); // newcomers wear nothing (also clears a title left by a level reset)
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= BotMedalAssigner.wearChance(bot.getLevel())) {
            return;
        }
        Integer id = BotMedalAssigner.pick(bot);
        if (id != null) {
            equip(bot, id);
        }
    }

    /** Re-roll after a level override, so the title matches the bot's actual level. */
    public static void reroll(Character bot) {
        remove(bot);
        apply(bot);
    }

    /** Equip a specific legal medal (GM command / internal). No-op on a null bot. */
    public static void equip(Character bot, int medalId) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        BotCustomization.EquipBot(bot, medalId); // dst resolves to -49; replaces any current medal
    }

    /** Remove the bot's current title, if any. */
    public static void remove(Character bot) {
        if (bot == null) {
            return;
        }
        if (bot.getInventory(InventoryType.EQUIPPED).getItem(BotMedalPool.MEDAL_SLOT) != null) {
            BotCustomization.UnequipBot(bot, BotMedalPool.MEDAL_SLOT);
        }
    }

    /** Item id of the bot's current title, or 0 when it wears none. */
    public static int currentMedalId(Character bot) {
        if (bot == null) {
            return 0;
        }
        var item = bot.getInventory(InventoryType.EQUIPPED).getItem(BotMedalPool.MEDAL_SLOT);
        return item == null ? 0 : item.getItemId();
    }
}
