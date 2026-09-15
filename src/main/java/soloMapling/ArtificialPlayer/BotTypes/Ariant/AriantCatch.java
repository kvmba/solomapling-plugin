package soloMapling.ArtificialPlayer.BotTypes.Ariant;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.util.PacketCreator;

/**
 * Catching a scorpion, which is how Ariant Coliseum is scored.
 *
 * <p>A player catches by sending the catch request; {@code UseCatchItemHandler} then checks the
 * monster is a scorpion below the health threshold, rolls the fifty percent, spends one Element
 * Rock and adds a Spirit Jewel. A bot has no socket to send that request from, so this performs
 * the same steps - and all of them, because the jewel is what the arena reads for the score:
 * doing only the visible part would leave the bot with a catch animation and no points.
 *
 * <p>Kept apart from the bot so the rules stay in one place with the handler's own conditions,
 * rather than being restated alongside the fighting logic.
 */
final class AriantCatch {

    private AriantCatch() {
    }

    /**
     * Attempt a catch on a scorpion.
     *
     * @return true when the catch succeeded and a jewel was added
     */
    static boolean attempt(Character bot, Monster target) {
        if (bot == null || target == null || bot.getMap() == null) {
            return false;
        }
        if (target.getId() != AriantPqData.SCORPION) {
            return false;
        }
        // Same gate the handler applies before it rolls at all.
        if (!AriantPqData.catchableAt(target.getHp(), target.getMaxHp())) {
            return false;
        }
        if (bot.getItemQuantity(AriantPqData.ELEMENT_ROCK, false) <= 0) {
            return false;
        }
        if (!bot.canHold(AriantPqData.SPIRIT_JEWEL, 1)) {
            return false; // nowhere to put the jewel, so the handler would refuse too
        }

        // The handler's fifty percent. Note it charges the rock only when the roll succeeds,
        // so a failed attempt costs nothing - which is why the bot can keep trying rather than
        // having to hoard rocks.
        if (Math.random() >= 0.5) {
            bot.getMap().broadcastMessage(PacketCreator.catchMonster(
                    target.getObjectId(), AriantPqData.ELEMENT_ROCK, (byte) 0));
            return false;
        }

        spendRock(bot);
        InventoryManipulator.addById(bot.getClient(), AriantPqData.SPIRIT_JEWEL, (short) 1);
        bot.getMap().killMonster(target, null, false);
        // The arena reads its score off the inventory, so this is what makes the jewel count.
        bot.updateAriantScore();
        return true;
    }

    /**
     * Spend one Element Rock, which the handler does only on a successful catch.
     */
    private static void spendRock(Character bot) {
        InventoryManipulator.removeById(bot.getClient(), InventoryType.USE,
                AriantPqData.ELEMENT_ROCK, 1, true, true);
    }
}
