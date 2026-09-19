package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.constants.id.ItemId;

/**
 * Picks the gun bullet a gun-toting bot fires, by level - the flying-bullet sprite other clients
 * render. Like {@link ThrowingStarSelector} this is a cosmetic packet projectile (not an equipped
 * item), but unlike a star a bullet tier is a strict ladder (one per level band), so no per-bot
 * roll/cache is needed: the same bot always resolves the same tier for its level.
 *
 * <p>v83 bullets are 2330000 (reqLevel 10) up to 2330005 (reqLevel 110), each usable once the bot
 * reaches its reqLevel. We give every gun bot the best tier it has out-levelled, so a 110+ gunslinger
 * fires Eternal Bullets while a fresh one fires plain Bullets.
 */
public final class GunBulletSelector {

    private GunBulletSelector() {}

    // v83 bullet tiers, lowest first. Only ItemId.BULLET (2330000) has a named constant; the rest are
    // raw ids here rather than editing the Cosmic ItemId table (mod boundary).
    private static final int BULLET         = ItemId.BULLET; // 2330000, reqLevel 10
    private static final int SPLIT_BULLET   = 2330001;       // reqLevel 30
    private static final int MIGHTY_BULLET  = 2330002;       // reqLevel 50
    private static final int VITAL_BULLET   = 2330003;       // reqLevel 70
    private static final int SHINY_BULLET   = 2330004;       // reqLevel 90
    private static final int ETERNAL_BULLET = 2330005;       // reqLevel 110

    /** The highest bullet tier a bot of this level can use. */
    public static int forLevel(int level) {
        if (level >= 110) return ETERNAL_BULLET;
        if (level >= 90)  return SHINY_BULLET;
        if (level >= 70)  return VITAL_BULLET;
        if (level >= 50)  return MIGHTY_BULLET;
        if (level >= 30)  return SPLIT_BULLET;
        return BULLET;
    }
}
