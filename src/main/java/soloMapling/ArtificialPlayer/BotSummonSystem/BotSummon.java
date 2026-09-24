package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Summon;

/**
 * One live bot summon: the host entity plus the small state the tick needs (the behaviour spec and
 * the map the entity currently lives on, so an owner warp can be detected).
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

    /** Absolute epoch-ms before which an attacking summon may not strike again. */
    long nextAttackAtMs;

    BotSummon(int botId, int skillId, BotSummonTable.Spec spec) {
        this.botId = botId;
        this.skillId = skillId;
        this.spec = spec;
    }

    boolean attacks() {
        return spec.attacks();
    }
}
