package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.constants.id.ItemId;

/**
 * Picks the gun bullet a gun-toting bot fires, by level - the flying-bullet sprite other clients
 * render. Like {@link ThrowingStarSelector} this is a cosmetic packet projectile (not an equipped
 * item), but unlike a star a bullet tier is a strict ladder (one per level band), so no per-bot
 * roll/cache is needed: the same bot always resolves the same tier for its level.
 *
 * <p>v83 ships six bullet ids (2330000..2330005). We do not use their own WZ reqLevels; instead the
 * six tiers are laid out evenly across the bot level range: plain Bullets cover everything up to 49,
 * then the remaining five tiers step up every 25 levels, with Eternal Bullets from 150. So a fresh
 * gunner fires plain Bullets and a 150+ one fires Eternal.
 */
public final class GunBulletSelector {

    private GunBulletSelector() {}

    // v83 bullet ids, lowest first. Only ItemId.BULLET (2330000) has a named constant; the rest are
    // raw ids here rather than editing the Cosmic ItemId table (mod boundary).
    private static final int BULLET         = ItemId.BULLET; // 2330000
    private static final int SPLIT_BULLET   = 2330001;
    private static final int MIGHTY_BULLET  = 2330002;
    private static final int VITAL_BULLET   = 2330003;
    private static final int SHINY_BULLET   = 2330004;
    private static final int ETERNAL_BULLET = 2330005;

    // Bot-level thresholds for each tier, evenly spaced from 50 to 150. Below 50 -> plain Bullet.
    private static final int SPLIT_AT   = 50;
    private static final int MIGHTY_AT  = 75;
    private static final int VITAL_AT   = 100;
    private static final int SHINY_AT   = 125;
    private static final int ETERNAL_AT = 150;

    /** The bullet tier a bot of this level fires: plain below 50, then +1 tier every 25 levels. */
    public static int forLevel(int level) {
        if (level >= ETERNAL_AT) return ETERNAL_BULLET;
        if (level >= SHINY_AT)   return SHINY_BULLET;
        if (level >= VITAL_AT)   return VITAL_BULLET;
        if (level >= MIGHTY_AT)  return MIGHTY_BULLET;
        if (level >= SPLIT_AT)   return SPLIT_BULLET;
        return BULLET;
    }
}
