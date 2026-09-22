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

    // TEMP DEBUG: pin every gun bot to one fixed bullet so a live test can tell whether the
    // client renders the projectile at all (independent of the level ladder). Change this one id
    // to try another tier: 子弹=2330000 手枪弹=2330001 铜头子弹=2330002 银子弹=2330003
    // 高爆弹=2330004 穿甲弹=2330005.
    private static final int DEBUG_BULLET = BULLET; // 2330000 子弹

    /** TEMP DEBUG: always the pinned bullet (level ladder disabled). */
    public static int forLevel(int level) {
        return DEBUG_BULLET;
    }
}
