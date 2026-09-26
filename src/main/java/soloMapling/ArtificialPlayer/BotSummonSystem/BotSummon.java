package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Summon;

/**
 * One live bot summon: the host entity plus the small state the follower tick needs (the behaviour
 * spec, the map the entity currently lives on so an owner warp can be detected, and the bob phase
 * that de-syncs a summon's float from its owner's other summons).
 *
 * <p>Mutable on purpose and only ever touched from the single follower tick plus the grant/remove
 * callers on the bot's own lifecycle thread, so no locking is required.</p>
 */
final class BotSummon {

    final int botId;
    final int skillId;
    final BotSummonTable.Spec spec;

    /** The host entity, valid from spawn until teardown. */
    Summon summon;

    /** The map the summon entity is currently placed on (used to notice the owner warped away). */
    MapleMap map;

    /** Random bob phase (radians) so a summon's float is not in lockstep with the owner's others. */
    double phaseRad;

    /**
     * This summon's stable follow distance (px) - the pet's own comfort ring. Held for the
     * summon's whole life, never re-rolled, so the follow is a leash and not a chase (the pet
     * system's rule); only its side of the owner can change.
     */
    int followDistancePx;

    /** Absolute epoch-ms before which an attacking summon may not strike again. */
    long nextAttackAtMs;

    /**
     * Absolute epoch-ms before which this STATIONARY turret may not be re-seat probed again (the
     * relocate throttle; unused by flyers). Written only by the follower tick, like everything
     * else here, so no locking is required.
     */
    long nextRelocateProbeAtMs;

    BotSummon(int botId, int skillId, BotSummonTable.Spec spec) {
        this.botId = botId;
        this.skillId = skillId;
        this.spec = spec;
    }

    boolean attacks() {
        return spec.attacks();
    }
}
