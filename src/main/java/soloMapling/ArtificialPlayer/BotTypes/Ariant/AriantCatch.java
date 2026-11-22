package soloMapling.ArtificialPlayer.BotTypes.Ariant;

import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
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
        MapleMap map = bot.getMap();
        if (map == null || target.getId() != AriantPqData.SCORPION) {
            return false;
        }
        target.lockMonster();
        try {
            int oid = target.getObjectId();
            if (bot.getMap() != map || map.getMonsterByOid(oid) != target
                    || !AriantPqData.catchableAt(target.getHp(), target.getMaxHp())
                    || bot.getItemQuantity(AriantPqData.ELEMENT_ROCK, false) <= 0
                    || !bot.canHold(AriantPqData.SPIRIT_JEWEL, 1)) return false;

            if (Math.random() >= 0.5) {
                map.broadcastMessage(PacketCreator.catchMonster(oid, AriantPqData.ELEMENT_ROCK, (byte) 0));
                return false;
            }
            if (!InventoryManipulator.addById(bot.getClient(), AriantPqData.SPIRIT_JEWEL, (short) 1)) return false;
            InventoryManipulator.removeById(bot.getClient(), InventoryType.USE, AriantPqData.ELEMENT_ROCK, 1, true, true);
            map.broadcastMessage(PacketCreator.catchMonster(oid, AriantPqData.ELEMENT_ROCK, (byte) 1));
            map.killMonster(target, null, false);
            bot.updateAriantScore();
            return true;
        } finally {
            target.unlockMonster();
        }
    }
}
