package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.server.maps.Summon;
import org.gms.server.movement.AbsoluteLifeMovement;

import java.awt.Point;
import java.util.List;

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

    /**
     * The per-attack "action" byte of SUMMON_ATTACK: NOT a plain 0/1 facing flag. The client
     * (BeiDou.exe sub_7A6882, verified in IDA) reads the relayed bytes as
     * {@code [oid][level][action][count][oid,hitByte,damage]...} and decodes the action byte as
     * {@code facing = byte & 0x80}, {@code node = table[(byte & 0x7F) - 4]} - a 15-slot name table
     * (base 0xBEC3BC, decrypted from the client's string pool): {@code stand, move, fly, summoned,
     * attack1, attack2, skill1..skill6, hit, die, say}. The node name feeds the
     * {@code Skill/<job>/<skill>/summon/<name>} WZ lookup, whose cached info carries the per-level
     * {@code ball} node ({@code level/<n>/ball}, fmt 2386) - the projectile sprite an observer draws.
     * <p>
     * A base-4 byte (0x04/0x84) indexes slot 0 = {@code stand}: the summon plays its STAND pose, the
     * damage entries still land (parsed independently), but NO attack node and NO ball ever render -
     * exactly the "hit with damage but no bullet" report. ATTACK1 is slot 4, so the byte must carry
     * action 8: {@code 0x08} facing right, {@code 0x88} facing left.
     */
    private static final int SUMMON_ATTACK1_ACTION = 8;
    private static final int SUMMON_FACING_LEFT_MASK = 0x80;

    /** Fragment command 0 = normal / absolute movement (the shape AbsoluteLifeMovement serialises). */
    private static final int MOVE_CMD_NORMAL = 0;

    private BotSummonBroadcast() {}

    /** One mob's damage line inside a SUMMON_ATTACK frame. */
    record Strike(int mobOid, int damage) {}

    static void summonAttack(Character bot, Summon summon, byte direction, List<Strike> hits) {
        bot.getMap().broadcastMessage(bot,
                summonAttackPacket(bot.getId(), summon.getObjectId(), direction, hits),
                summon.getPosition());
    }

    /** Pure seam for the byte layout (unit-testable without a live Character/Summon). */
    static OutPacket summonAttackPacket(int cid, int summonOid, byte direction, List<Strike> hits) {
        OutPacket p = OutPacket.create(SendOpcode.SUMMON_ATTACK);
        p.writeInt(cid);                  // dwCharacterID
        p.writeInt(summonOid);            // dwSummonedID
        p.writeByte(0);                   // nCharLevel (host writes 0; client ignores for a bot)
        p.writeByte((direction != 0 ? SUMMON_FACING_LEFT_MASK : 0) | SUMMON_ATTACK1_ACTION); // (bLeft<<7)|action
        p.writeByte(hits.size());         // nMobCount
        for (Strike hit : hits) {
            p.writeInt(hit.mobOid());     // ATTACKINFO->dwMobID
            p.writeByte(HIT_ACTION);      // ATTACKINFO->nHitAction
            p.writeInt(hit.damage());     // ATTACKINFO->aDamage[0]
        }
        return p;
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
