package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Job;
import org.gms.constants.skills.Archer;
import org.gms.constants.skills.Assassin;
import org.gms.constants.skills.Bandit;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.Buccaneer;
import org.gms.constants.skills.Cleric;
import org.gms.constants.skills.Corsair;
import org.gms.constants.skills.Crossbowman;
import org.gms.constants.skills.FPWizard;
import org.gms.constants.skills.Fighter;
import org.gms.constants.skills.Gunslinger;
import org.gms.constants.skills.Hunter;
import org.gms.constants.skills.ILWizard;
import org.gms.constants.skills.Magician;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.Page;
import org.gms.constants.skills.Spearman;
import org.gms.constants.skills.Warrior;
import org.gms.constants.skills.Crusader;
import org.gms.constants.skills.WhiteKnight;
import org.gms.constants.skills.DragonKnight;
import org.gms.constants.skills.Priest;
import org.gms.constants.skills.Hermit;
import org.gms.constants.skills.ChiefBandit;
import org.gms.constants.skills.Hero;
import org.gms.constants.skills.Paladin;
import org.gms.constants.skills.DarkKnight;
import org.gms.constants.skills.FPMage;
import org.gms.constants.skills.FPArchMage;
import org.gms.constants.skills.ILMage;
import org.gms.constants.skills.ILArchMage;
import org.gms.constants.skills.Bishop;
import org.gms.constants.skills.Bowmaster;
import org.gms.constants.skills.Marksman;
import org.gms.constants.skills.NightLord;
import org.gms.constants.skills.Shadower;
import org.gms.constants.skills.Pirate;
import org.gms.constants.skills.Rogue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/*
 * Bot-buff concept inspired by GreenCatMS; this per-job registry and the cosmetic (no stat/MP/cooldown) approach are original. Credit: NutNNut for the idea.
 * Per-job bot buff registry. Each entry lists only the buffs gained at that advancement;
 * a bot's full buff set is the union over every job in its lineage (so a Priest inherits
 * Magician + Cleric + Priest buffs). Keyed by job rather than class branch because a Cleric
 * and an I/L Wizard are both Magicians yet keep different buffs.
 */
public final class BotBuffConfig {

    // Each Job -> the buff skill ids it ADDS at that advancement (inherited ones
    // come from the lineage walk in buffsForJob). EnumMap iterates in enum-decl
    // order, so lower-tier buffs naturally resolve before higher-tier ones.
    private static final Map<Job, int[]> BUFFS_BY_JOB = new EnumMap<>(Job.class);

    /** Iron Body ("圣甲术", 1001003). See the note where it is registered. */
    private static final int IRON_BODY = 1001003;

    static {
        // ---- 1st job ----
        // The host constant Warrior.IRON_BODY reads 1000003, which is not a skill in the v83
        // Skill.wz (it resolves to nothing - and broadcasting it crashes an observing client).
        // 1001003 is Iron Body ("圣甲术"); the host has no constant for it, so it is named here.
        put(Job.WARRIOR,  IRON_BODY);                                  // 1001003 - W.Def up
        put(Job.MAGICIAN, Magician.MAGIC_GUARD, Magician.MAGIC_ARMOR); // 2001002 / 2001003
        put(Job.BOWMAN,   Archer.FOCUS);                               // 3001003 - acc/avoid up
        // THIEF 1st job: 诅咒术 (4001002) is a MOB-targeted debuff (the host applies only a monster
        // status, no self statup), like Hypnotize below - not a self aura, so it is not registered.
        // 隐身术 (4001003) IS registered: unlike GM hide, the v83 client renders it as a
        // semi-transparent shade that players still see, and it is a state-bound hide aura governed
        // by BotAuraState - an attack or a mount retires it, and while it holds monsters cannot
        // touch the bot (the same rules 橡木伪装 follows).
        put(Job.THIEF,    Rogue.DARK_SIGHT);                           // 4001003 隐身术
        // PIRATE 1st job: 疾驰 5001005 (a short speed/jump burst - its own puffy aura).
        put(Job.PIRATE,   Pirate.DASH);                                // 5001005 疾驰

        // ---- 2nd job (only what each job ADDS; 1st-job buffs come via lineage) ----
        // Warrior branch (+ weapon booster - attack speed up; the booster effect looks
        // the same regardless of weapon, so the representative one is fine for the visual)
        put(Job.FIGHTER,  Fighter.RAGE, Fighter.POWER_GUARD, Fighter.SWORD_BOOSTER);        // 1101006 (party atk), 1101007, 1101004
        put(Job.PAGE,     Page.POWER_GUARD, Page.SWORD_BOOSTER);                            // 1201007, 1201004
        put(Job.SPEARMAN, Spearman.IRON_WILL, Spearman.HYPER_BODY, Spearman.SPEAR_BOOSTER); // 1301006, 1301007 (party), 1301004
        // Magician branch (Spell Booster is 3rd job - see below)
        put(Job.FP_WIZARD, FPWizard.MEDITATION);                       // 2101001 (party m.atk)
        put(Job.IL_WIZARD, ILWizard.MEDITATION);                       // 2201001 (party m.atk)
        put(Job.CLERIC,    Cleric.BLESS, Cleric.INVINCIBLE);           // 2301004 (party), 2301003
        // Bowman branch (+ booster)
        put(Job.HUNTER,      Hunter.SOUL_ARROW, Hunter.BOW_BOOSTER);            // 3101004, 3101002
        put(Job.CROSSBOWMAN, Crossbowman.SOUL_ARROW, Crossbowman.CROSSBOW_BOOSTER); // 3201004, 3201002
        // Thief branch (+ booster)
        put(Job.ASSASSIN, Assassin.HASTE, Assassin.CLAW_BOOSTER);      // 4101004, 4101003
        put(Job.BANDIT,   Bandit.HASTE, Bandit.DAGGER_BOOSTER);        // 4201003, 4201002
        // Pirate branch (+ booster; brawler = knuckle, gunslinger = gun)
        put(Job.BRAWLER,    Brawler.KNUCKLER_BOOSTER, Brawler.OAK_BARREL); // 5101006 attack speed up, 5101007 橡木伪装
        put(Job.GUNSLINGER, Gunslinger.GUN_BOOSTER, Gunslinger.WINGS);// 5201003, 5201005 轻羽鞋
        // (Pirate 1st-job 疾驰 5001005 rides the lineage below.)

        // ---- 3rd job ----
        // Warrior branch
        put(Job.CRUSADER,     Crusader.COMBO);                             // 1111002 - combo (self)
        put(Job.WHITEKNIGHT,  WhiteKnight.SWORD_FIRE_CHARGE);              // 1211003 - elemental charge (self)
        put(Job.DRAGONKNIGHT, DragonKnight.DRAGON_BLOOD);                 // 1311008 - atk up (self)
        // Magician branch (F/P and I/L get Spell Booster at 3rd job; Cleric line doesn't)
        put(Job.FP_MAGE,      FPMage.SPELL_BOOSTER);                       // 2111005 - cast speed up
        put(Job.IL_MAGE,      ILMage.SPELL_BOOSTER);                       // 2211005 - cast speed up
        put(Job.PRIEST,       Priest.HOLY_SYMBOL);                         // 2311003 - exp/drop (party)
        // Thief branch
        put(Job.HERMIT,       Hermit.SHADOW_PARTNER, Hermit.MESO_UP);      // 4111002 (self), 4111001 (party meso)
        put(Job.CHIEFBANDIT,  ChiefBandit.MESO_GUARD, ChiefBandit.CHAKRA, ChiefBandit.PICKPOCKET); // 4211005, 4211001 转化术, 4211003 敛财术
        // Pirate branch
        put(Job.MARAUDER,    Marauder.TRANSFORMATION);                    // 5111005 - super transform (self)
        // (Ranger/Sniper inherit Soul Arrow; F/P & I/L 3rd inherit Meditation)
        // NOTE: Outlaw's Octopus turret is NOT a buff here - it is a real summon entity
        // (BotSummonSystem) that spawns/moves/attacks/removes on its own.

        // ---- 4th job (Maple Warrior for everyone + each class's signature buff) ----
        put(Job.HERO,        Hero.MAPLE_WARRIOR, Hero.ENRAGE, Hero.STANCE, Hero.HEROS_WILL);             // 1121000, 1121010, 1121002 稳如泰山, 1121011 勇士的意志
        put(Job.PALADIN,     Paladin.MAPLE_WARRIOR, Paladin.SWORD_HOLY_CHARGE, Paladin.STANCE, Paladin.HEROS_WILL); // 1221000, 1221003, 1221002, 1221012
        put(Job.DARKKNIGHT,  DarkKnight.MAPLE_WARRIOR, DarkKnight.BERSERK, DarkKnight.STANCE, DarkKnight.HEROS_WILL);// 1321000, 1320006, 1321002, 1321010
        put(Job.FP_ARCHMAGE, FPArchMage.MAPLE_WARRIOR, FPArchMage.INFINITY, FPArchMage.MANA_REFLECTION, FPArchMage.HEROS_WILL); // 2121000, 2121004, 2121002, 2121008
        put(Job.IL_ARCHMAGE, ILArchMage.MAPLE_WARRIOR, ILArchMage.INFINITY, ILArchMage.MANA_REFLECTION, ILArchMage.HEROS_WILL); // 2221000, 2221004, 2221002, 2221008
        put(Job.BISHOP,      Bishop.MAPLE_WARRIOR, Bishop.HOLY_SHIELD, Bishop.INFINITY, Bishop.MANA_REFLECTION, Bishop.HEROS_WILL); // 2321000, 2321005, 2321004, 2321002, 2321009
        put(Job.BOWMASTER,   Bowmaster.MAPLE_WARRIOR, Bowmaster.SHARP_EYES, Bowmaster.CONCENTRATE, Bowmaster.HAMSTRING, Bowmaster.HEROS_WILL); // 3121000, 3121002, 3121008, 3121007 击退箭, 3121009
        put(Job.MARKSMAN,    Marksman.MAPLE_WARRIOR, Marksman.SHARP_EYES, Marksman.BLIND, Marksman.HEROS_WILL);  // 3221000, 3221002, 3221006 致盲箭, 3221008
        put(Job.NIGHTLORD,   NightLord.MAPLE_WARRIOR, NightLord.SHADOW_STARS, NightLord.HEROS_WILL); // 4121000, 4121006, 4121009
        // Smoke Screen (4221006) is skipped: it is an area field left on the ground, not a self aura.
        put(Job.SHADOWER,    Shadower.MAPLE_WARRIOR, Shadower.HEROS_WILL); // 4221000, 4221008
        // Battle Ship (5221006) is skipped: it is a ride, not a castable aura.
        put(Job.BUCCANEER,   Buccaneer.MAPLE_WARRIOR, Buccaneer.SPEED_INFUSION, Buccaneer.SUPER_TRANSFORMATION, Buccaneer.PIRATES_RAGE); // 5121000, 5121009, 5121003 超级变身, 5121008 勇士的意志
        // Hypnotize (5221009 心灵控制) is skipped: it targets a MOB, not the bot itself.
        // Battle Ship (5221006 海盗船) IS registered as an aura: it is the gunner's attack enabler -
        // Battleship Cannon / Torpedo are illegal off the ship, and the ship's pose excludes the
        // 骑宠 mount and 疾驰 while held. The buff itself is never registered on the bot; BotAuraState
        // owns the aura's lifecycle (observer frame, expiry, exclusions) and the attack driver gates
        // the ship-only guns on it (see BotAuraState.isMorphedAs).
        put(Job.CORSAIR,     Corsair.MAPLE_WARRIOR, Corsair.HEROS_WILL, Corsair.BATTLE_SHIP); // 5221000, 5221010, 5221006
    }

    private BotBuffConfig() {}

    private static void put(Job job, int... skillIds) {
        BUFFS_BY_JOB.put(job, skillIds);
    }

    /*
     * The full buff list for a bot of this job: the union of every configured job
     * in the bot's lineage (Job.isA(Job)), lower-tier first. Empty if the
     * job (and its ancestors) have nothing configured.
     */
    public static List<Integer> buffsForJob(Job job) {
        if (job == null) return Collections.emptyList();

        List<Integer> result = new ArrayList<>();
        for (Map.Entry<Job, int[]> entry : BUFFS_BY_JOB.entrySet()) {
            if (job.isA(entry.getKey())) {
                for (int id : entry.getValue()) {
                    result.add(id);
                }
            }
        }
        return result;
    }
}
