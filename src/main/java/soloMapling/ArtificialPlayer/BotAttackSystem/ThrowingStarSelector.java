package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.inventory.WeaponType;
import org.gms.constants.id.ItemId;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;

import java.util.concurrent.ThreadLocalRandom;

// Picks a rarity-graded throwing star for a claw-thief bot and caches it on the bot
// (BotSM.chosenStarId) so the same bot throws the same star until it outgrows it. Only claw
// throwers get one - assassins and rogues who took the claw path; daggers/bandits are melee and get
// none. The star is a cosmetic packet projectile (the flying-star sprite), not an equipped item.
// Ours (SoloMapling), not a GreenCatMS port.
public final class ThrowingStarSelector {

    private ThrowingStarSelector() {}

    // Only subi/crystal-ilbi are public ItemId constants; the mid tiers are raw v83 ids here rather
    // than editing the Cosmic ItemId table (mod boundary - we don't touch base for our own data).
    // Chinese names are the server's own (wz-zh-CN/String.wz/Consume.img.xml), as the zh-CN players
    // know them - keep them beside the id so the ladder is readable without a client open.
    private static final int SUBI         = ItemId.SUBI_THROWING_STARS;         // 2070000 海星镖 PAD15
    private static final int WOLBI        = 2070001;                            // 回旋镖 PAD17
    private static final int MOKBI        = 2070002;                            // 黑色利刃 PAD19
    private static final int KUMBI        = 2070003;                            // 雪花镖 PAD21
    private static final int TOBI         = 2070004;                            // 黑色刺 PAD23
    private static final int STEELY       = 2070005;                            // 金钱镖 PAD25
    private static final int ILBI         = 2070006;                            // 齿轮镖 PAD27
    private static final int CRYSTAL_ILBI = ItemId.CRYSTAL_ILBI_THROWING_STARS; // 2070016 水晶飞镖 PAD29

    // Intrinsic rarity weight per rung, commonest first: a star's own share of the roll, whatever the
    // bot's level. Rarer rungs weigh less, so even a bot that can afford the top star usually throws a
    // cheaper one - the rare tiers are what a high level gets a CHANCE at, not what it always throws.
    // A low level's window holds only the cheap end, which is what confines it to commons.
    private static final double[] LADDER_WEIGHT = {0.23, 0.19, 0.16, 0.13, 0.11, 0.08, 0.06, 0.04};

    // How many rungs a bot rolls across, counting down from its own. Keeps a level-200 off 海星镖 while
    // still letting a level-30 throw something cheaper than the best star it can afford.
    private static final int ROLL_WINDOW = 4;

    // Stars ascending by rarity, with the level at which a bot may first throw each. A bot's rung is
    // the last entry whose floor it meets; the roll then walks DOWN from there, so the cheap stars a
    // low level can reach are the only stars it can roll.
    //
    // Floors track the claw-thief job line (飞侠 -> 刺客 -> 无影人 -> 隐士): each advancement opens the
    // next tier, with the odd mid-point between, so a bot's star reads as the gear its job would carry.
    // 海星镖 starts at 1 so a sub-10 rogue still has a star. 木制陀螺 (2070009, PAD19) and 冰菱
    // (2070010, PAD21) are same-PAD skin variants of 黑色利刃 and 雪花镖 rather than rungs of their own
    // - they were two entries in the old band table and carrying them over would have doubled those
    // tiers' share of the roll. Tune the ladder here.
    private static final int[] LADDER = {
            SUBI,         // 海星镖 PAD15
            WOLBI,        // 回旋镖 PAD17
            MOKBI,        // 黑色利刃 PAD19
            KUMBI,        // 雪花镖 PAD21
            TOBI,         // 黑色刺 PAD23
            STEELY,       // 金钱镖 PAD25
            ILBI,         // 齿轮镖 PAD27
            CRYSTAL_ILBI, // 水晶飞镖 PAD29
    };

    // 1 初心者 / 10 飞侠(一转) / 20 / 30 刺客(二转) / 45 / 70 无影人(三转) / 90 / 120 隐士(四转)
    private static final int[] LADDER_MIN_LEVEL = {1, 10, 20, 30, 45, 70, 90, 120};

    // The star id a claw-thief bot should throw, or 0 if the bot is not a claw thrower
    // (dagger/bandit/non-thief). Called once from the BotSM constructor, by which point the Character
    // is fully decorated (weapon equipped, level/job set).
    public static int selectFor(Character chr) {
        if (chr == null || BotAttack.resolveEquippedWeaponType(chr) != WeaponType.CLAW) {
            return 0; // only claw throwers throw stars
        }
        return rollForLevel(chr.getLevel());
    }

    // The star this bot throws, or 0 if it isn't a claw thrower or isn't a registered bot (e.g. a GM
    // !bot attack on an unmanaged char). Read from the attack path so the projectile matches.
    public static int chosenStar(Character bot) {
        if (bot == null) {
            return 0;
        }
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        return sm != null ? sm.starForLevel(bot.getLevel()) : 0;
    }

    // The rung of the ladder this level tops out at, i.e. the best star it can afford. -1 below the
    // ladder. Two levels share a rung when neither unlocks a better star, which is what lets BotSM keep
    // a bot's star stable across ordinary level-ups inside the same rung.
    public static int rungForLevel(int level) {
        int rung = -1;
        for (int i = 0; i < LADDER.length; i++) {
            if (level >= LADDER_MIN_LEVEL[i]) {
                rung = i;
            }
        }
        return rung;
    }

    // Roll across the window ending at the bot's own rung: each star in reach takes its own rarity
    // weight, so a level-30 can only draw from subi..kumbi (commons) while a level-120 draws from
    // tobi..crystal-ilbi, mostly tobi with a rare crystal. Deliberately NOT a flat pick over the
    // reachable set - that made a level-45 throw steely as often as tobi, and NOT a top-weighted
    // pick either - that made crystal ilbi the most common star in the world.
    public static int rollForLevel(int level) {
        int rung = rungForLevel(level);
        if (rung < 0) {
            return SUBI; // below the ladder -> the beginner star
        }
        int lowest = Math.max(0, rung - ROLL_WINDOW + 1);
        double total = 0;
        for (int i = lowest; i <= rung; i++) {
            total += LADDER_WEIGHT[i];
        }
        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (int i = lowest; i <= rung; i++) {
            roll -= LADDER_WEIGHT[i];
            if (roll < 0) {
                return LADDER[i];
            }
        }
        return LADDER[lowest];
    }
}
