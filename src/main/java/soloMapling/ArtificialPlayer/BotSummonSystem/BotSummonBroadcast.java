package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.server.maps.Summon;

/**
 * Sends a single {@code SUMMON_ATTACK} frame for a bot's summon.
 *
 * <p>The host's {@code PacketCreator.summonAttack} takes a {@code List<SummonAttackEntry>}, and
 * {@code SummonAttackEntry} is a non-static inner class of the packet handler (so it cannot be
 * constructed without an owning handler instance). The packet layout is short and fixed and lives
 * here verbatim from that method - one mob, one damage line - so the plugin needs no handler
 * instance and no host change.</p>
 */
final class BotSummonBroadcast {

    /** The hit-action byte the host writes for every summon attack entry ("who knows" - host comment). */
    private static final int HIT_ACTION = 6;

    private BotSummonBroadcast() {}

    static void summonAttack(Character bot, Summon summon, byte direction, int mobOid, int damage) {
        OutPacket p = OutPacket.create(SendOpcode.SUMMON_ATTACK);
        p.writeInt(bot.getId());          // dwCharacterID
        p.writeInt(summon.getObjectId()); // dwSummonedID
        p.writeByte(0);                   // nCharLevel (host writes 0; client ignores for a bot)
        p.writeByte(direction);           // bLeft
        p.writeByte(1);                   // nMobCount
        p.writeInt(mobOid);               // ATTACKINFO->dwMobID
        p.writeByte(HIT_ACTION);          // ATTACKINFO->nHitAction
        p.writeInt(damage);               // ATTACKINFO->aDamage[0]
        bot.getMap().broadcastMessage(bot, p, summon.getPosition());
    }
}
