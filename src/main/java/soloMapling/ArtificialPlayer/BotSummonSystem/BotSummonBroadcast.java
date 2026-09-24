package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.server.maps.Summon;
import org.gms.server.movement.AbsoluteLifeMovement;

import java.awt.Point;

/**
 * Hands the plugin the two summon frames the host will not build for us.
 *
 * <p><b>Why they live here.</b> The host's {@code PacketCreator.summonAttack} takes a
 * {@code List<SummonAttackEntry>} whose class is a non-static inner class of the packet handler (so
 * it cannot be constructed without an owning handler instance), and {@code moveSummon} is
 * InPacket-only (it re-broadcasts a client's own movement stream - a headless bot has no client to
 * send one). Both layouts are short and fixed and are reproduced here verbatim from those methods,
 * so the plugin needs no handler instance and no host change.</p>
 *
 * <p><b>Why the server authors movement.</b> A client animates only the summon it owns (the local
 * player's own); a summon belonging to anyone else - a bot's included - is rendered purely from the
 * {@code MOVE_SUMMON} frames the server relays. A bot has no client to produce those frames, so
 * without {@link #summonMove} its summon would stand frozen at its spawn point for every observer
 * while the bot fought elsewhere. The fragment encoding is the client's own
 * {@link AbsoluteLifeMovement} layout - the same bytes {@code PacketCreator.movePet} serialises and
 * the plugin already sends for pets - and the frame around it ({@code [cid][oid][startPos][move
 * path]}) is exactly what the host's {@code MoveSummonHandler} broadcasts for a real player, so an
 * observing client cannot tell the difference.</p>
 */
final class BotSummonBroadcast {

    /** The hit-action byte the host writes for every summon attack entry ("who knows" - host comment). */
    private static final int HIT_ACTION = 6;

    /** Fragment command 0 = normal / absolute movement (the shape AbsoluteLifeMovement serialises). */
    private static final int MOVE_CMD_NORMAL = 0;

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

    /*
     * One absolute-move frame for a summon: [cid][oid][startPos] + a one-fragment move path.
     * The caller must have already re-seated the entity at dest (the host's echoed frames do the
     * same - the client interpolates from startPos to the fragment position over durationMs).
     * `fh` is the foothold the client may snap the entity onto: 0 for a flying summon (all moving
     * bot summons are flyers), the real foothold id for a grounded one. `action` is the CSummoned
     * action byte (STAND 0 / MOVE 1 / FLY 2).
     */
    static void summonMove(Character bot, Summon summon, Point start, Point dest,
                           int vx, int vy, int fh, int action, int durationMs) {
        OutPacket p = summonMovePacket(bot.getId(), summon.getObjectId(), start, dest,
                new Point(vx, vy), fh, action, durationMs);
        bot.getMap().broadcastMessage(bot, p, dest);
    }

    /*
     * Static seam for the byte layout (unit-testable without a live Character/Summon), mirroring the
     * pet system's packet seams. Shape, in order:
     *   LE opcode, cid, oid, start x/y, fragment count = 1, fragment (type 0 | x y | vx vy | fh |
     *   action | duration) - the fragment serialised by the host's own AbsoluteLifeMovement.
     */
    static OutPacket summonMovePacket(int cid, int summonOid, Point start, Point dest,
                                      Point velocity, int fh, int action, int durationMs) {
        OutPacket p = OutPacket.create(SendOpcode.MOVE_SUMMON);
        p.writeInt(cid);
        p.writeInt(summonOid);
        p.writePos(start);
        p.writeByte(1); // one movement fragment
        AbsoluteLifeMovement move = new AbsoluteLifeMovement(MOVE_CMD_NORMAL, dest, durationMs, action);
        move.setPixelsPerSecond(velocity);
        move.setFh(fh);
        move.serialize(p);
        return p;
    }
}
