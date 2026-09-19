package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.inventory.WeaponType;
import org.gms.constants.id.ItemId;
import org.gms.constants.skills.Bandit;
import org.gms.constants.skills.Bishop;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.Buccaneer;
import org.gms.constants.skills.Cleric;
import org.gms.constants.skills.Corsair;
import org.gms.constants.skills.DragonKnight;
import org.gms.constants.skills.FPArchMage;
import org.gms.constants.skills.FPMage;
import org.gms.constants.skills.FPWizard;
import org.gms.constants.skills.Gunslinger;
import org.gms.constants.skills.Hermit;
import org.gms.constants.skills.Hero;
import org.gms.constants.skills.ILArchMage;
import org.gms.constants.skills.ILMage;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.Outlaw;
import org.gms.constants.skills.Paladin;
import org.gms.constants.skills.Pirate;
import org.gms.constants.skills.Priest;
import org.gms.constants.skills.Rogue;
import org.gms.constants.skills.Shadower;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/*
 * Body-action ids extracted verbatim from GreenCatMS (BotAttackDataProvider.BODY_ACTION_ID_OVERRIDES). Credit: NutNNut.
 * Body-action ids for bot attacks - the attack packet's "direction" byte (the pose other
 * clients render). Gotcha: an id is the client's fixed internal enum value, not the action's
 * ordinal in the WZ file; the two agree only up to idx 23, so anything above that must use the
 * canonical enum value (copied from the proven GreenCatMS reference). Most skills use the weapon
 * default; special poses live in SKILL_ACTION.
 */
public final class BotAttackData {

    private BotAttackData() {}

    /* Facing mask for the close-range-attack stance byte. */
    public static final int FACING_RIGHT_MASK = 0x00;
    public static final int FACING_LEFT_MASK  = 0x80;

    /* Default attack speed when the weapon profile is unknown. v83 range is 2-9 (lower = faster). */
    public static final int DEFAULT_ATTACK_SPEED = 4;

    private static final int SWING_O1 = 5, SWING_O2 = 6, SWING_O3 = 7;   // 1H swing
    private static final int SWING_T1 = 9, SWING_T2 = 10, SWING_T3 = 11; // 2H swing
    private static final int SWING_P1 = 13, STAB_T1 = 19;                // polearm
    private static final int STAB_O1 = 16, STAB_O2 = 17;                 // 1H stab
    private static final int SHOOT_1 = 22, SHOOT_2 = 23;                 // bow/crossbow draw + fire
    private static final int SHOT = 93;                                  // "shot" (gun fire, as used by gunslingers)
    private static final int CLAW_1 = 24, CLAW_2 = 25, CLAW_3 = 26;      // claw throw
    private static final int WAND_1 = 28, WAND_2 = 29;                   // wand/staff cast
    // The client maps the direction byte through a FIXED internal enum (the client's
    // "actions.txt" order), NOT the order actions appear in Character/00002000.img. The two
    // AGREE up to ~shoot2 (idx 23) - walk/swing/stab/shoot - which is why basic swings and
    // shoot1 work, but DIVERGE above it (the enum reserves slots, e.g. claw 24-26 / wand 28-29).
    // So everything past 23 must use the canonical enum value, not the WZ file ordinal.
    // Values below match the GreenCatMS reference BODY_ACTION_ID_OVERRIDES (proven in-game).
    private static final int SAVAGE = 55;                               // "savage" dagger combo (Savage Blow)
    private static final int ALERT_3 = 42;                              // "alert3" roar (Dragon Roar)
    private static final int MAGIC_1 = 49, MAGIC_2 = 50, MAGIC_3 = 51;  // "magicN" 3rd-job spell casts
    private static final int BURSTER_2 = 54;                            // "burster2" spear thrust combo (Crusher lv16+)
    private static final int AVENGER = 56;                             // "avenger" claw arc throw
    // ----- 4th-job skill poses (canonical enum, from the reference map) -----
    private static final int ALERT_5 = 44;                             // "alert5" (Boomerang Step)
    private static final int ASSASSINATION = 59;                       // "assassination" (Assassinate)
    private static final int BRANDISH_1 = 63;                          // "brandish1" (Brandish)
    private static final int SANCTUARY = 65;                           // "sanctuary" (Heaven's Hammer)
    private static final int METEOR = 66;                              // "meteor" (Meteor Shower)
    private static final int BLIZZARD = 68;                            // "blizzard" (Blizzard)
    private static final int GENESIS = 69;                             // "genesis" (Genesis)
    private static final int BLAST = 71;                               // "blast" (Paladin Blast)

    // ----- Pirate poses (canonical v83 enum, from the client's action table) -----
    private static final int STRAIGHT = 79;                            // "straight" (Flash Fist / basic brawl punch)
    private static final int SOMERSAULT = 78;                          // "somersault" (Somersault Kick)
    private static final int DOUBLEFIRE = 86;                          // "doublefire" (Double Shot, 1st-job gun)
    private static final int TRIPLEFIRE = 87;                          // "triplefire" (Burst Fire / Triple Fire)
    private static final int BACKSPIN = 81;                            // "backspin" (Backspin Blow)
    private static final int DOUBLEUPPER = 84;                         // "doubleupper" (Double Uppercut)
    private static final int SCREW = 83;                               // "screw" (Corkscrew Blow)
    private static final int EBURSTER = 80;                            // "eburster" (Energy Blast)
    private static final int EDRAIN = 90;                              // "edrain" (Energy Drain)
    private static final int SHOCKWAVE_P = 157;                        // "shockwave" (Shockwave)
    private static final int FIST = 97;                                // "fist" (Barrage)
    private static final int DEMOLITION_P = 158;                       // "demolition" (Demolition)
    private static final int DRAGONSTRIKE = 85;                        // "dragonstrike" (Dragon Strike)
    private static final int EORB = 82;                                // "eorb" (Energy Orb)
    private static final int SNATCH = 159;                             // "snatch" (Snatch)
    private static final int FLAMEBURNER = 95;                         // "fireburner" (Flame Thrower)
    private static final int COOLINGEFFECT = 96;                       // "coolingeffect" (Ice Splitter)
    private static final int HOMING = 100;                             // "homing" (Homing Beacon)
    private static final int RAPIDFIRE = 99;                           // "rapidfire" (Rapid Fire)
    private static final int CANNON = 109;                             // "cannon" (Battleship Cannon)
    private static final int TORPEDO = 110;                            // "torpedo" (Battleship Torpedo)

    private static final int[] DEFAULT_1H_VARIANTS = {STAB_O1, STAB_O2, SWING_O1, SWING_O2, SWING_O3};
    private static final int[] HEAVY_2H_VARIANTS   = {STAB_O1, STAB_O2, SWING_T1, SWING_T2, SWING_T3};
    private static final int[] POLEARM_VARIANTS    = {SWING_P1, STAB_T1};
    private static final int[] WAND_VARIANTS       = {WAND_1, WAND_2};
    private static final int[] CLAW_VARIANTS       = {CLAW_1, CLAW_2, CLAW_3};
    // Bow and crossbow are NOT interchangeable: shoot1 is the bow draw, shoot2 the crossbow.
    // A shared {shoot1, shoot2} pool made each weapon fire the wrong pose ~half the time.
    private static final int[] BOW_VARIANTS        = {SHOOT_1};
    private static final int[] CROSSBOW_VARIANTS   = {SHOOT_2};
    // A gun fires with the "shot" pose (shootF/shoot6 would draw a bow/crossbow). Every v83
    // gunslinger/buccaneer attack has an explicit SKILL_ACTION entry, so this only backs a
    // gun-toting bot's no-skill basic swing.
    private static final int[] GUN_VARIANTS        = {SHOT};

    // Skills whose character keyframe differs from the weapon default, taken from each
    // skill's Skill.wz "action" node. A skill not listed uses its weapon's swing/cast/shoot
    // action. Extend this as more skills with special animations are confirmed against WZ.
    private static final Map<Integer, Integer> SKILL_ACTION = Map.ofEntries(
            Map.entry(Rogue.DOUBLE_STAB,             STAB_O1),   // 4001334 -> "stabO1" (dagger stab, not a random swing)
            Map.entry(FPWizard.FIRE_ARROW,           SHOOT_1),   // 2101004 -> "shoot1" (arrow draw, not a wand cast)
            Map.entry(Cleric.HOLY_ARROW,             SHOOT_1),   // 2301005 -> "shoot1"
            Map.entry(Bandit.SAVAGE_BLOW,            SAVAGE),    // 4201005 -> "savage" (dagger combo, not a normal stab)
            // ----- 3rd-job skills audited 2026-06-22 (action node read from Skill.wz) -----
            Map.entry(Hermit.AVENGER,                AVENGER),   // 4111005 -> "avenger" (claw arc throw)
            Map.entry(FPMage.EXPLOSION,              MAGIC_3),   // 2111002 -> "magic3" (F/P AoE cast)
            Map.entry(ILMage.ICE_STRIKE,             MAGIC_2),   // 2211002 -> "magic2" (I/L AoE cast)
            Map.entry(ILMage.THUNDER_SPEAR,          MAGIC_1),   // 2211003 -> "magic1" (I/L single cast)
            Map.entry(Priest.SHINING_RAY,            MAGIC_2),   // 2311004 -> "magic2" (Priest AoE cast)
            Map.entry(DragonKnight.DRAGON_ROAR,      ALERT_3),   // 1311006 -> "alert3" (roar pose)
            Map.entry(DragonKnight.SPEAR_CRUSHER,    BURSTER_2), // 1311001 -> "burster2" (spear thrust combo, lv16+)
            Map.entry(DragonKnight.POLE_ARM_CRUSHER, BURSTER_2), // 1311002 -> "burster2"
            // ----- 4th-job skills audited 2026-06-22c -----
            Map.entry(Hero.BRANDISH,                 BRANDISH_1),    // 1121008 -> "brandish1"
            Map.entry(Paladin.BLAST,                 BLAST),         // 1221009 -> "blast"
            Map.entry(Paladin.HEAVENS_HAMMER,        SANCTUARY),     // 1221011 -> "sanctuary"
            Map.entry(FPArchMage.METEOR_SHOWER,      METEOR),        // 2121007 -> "meteor"
            Map.entry(ILArchMage.BLIZZARD,           BLIZZARD),      // 2221007 -> "blizzard"
            Map.entry(Bishop.ANGEL_RAY,              SHOOT_1),       // 2321007 -> "shoot1" (holy bolt draw, like Holy Arrow)
            Map.entry(Bishop.GENESIS,                GENESIS),       // 2321008 -> "genesis"
            Map.entry(Shadower.ASSASSINATE,          ASSASSINATION), // 4221001 -> "assassination"
            Map.entry(Shadower.BOOMERANG_STEP,       ALERT_5),       // 4221007 -> "alert5"
            // Big Bang (2121001/2221001) has no action node -> wand default (+ charge int, see magicChargeFor)
            // ----- Pirate skills (action node read from Skill.wz; canonical v83 pose codes) -----
            // Brawler / buccaneer line (knuckle, melee)
            Map.entry(Pirate.FLASH_FIST,             STRAIGHT),        // 5001001 -> "straight"
            Map.entry(Pirate.SOMERSAULT_KICK,        SOMERSAULT),      // 5001002 -> "somersault"
            Map.entry(Brawler.BACK_SPIN_BLOW,        BACKSPIN),        // 5101002 -> "backspin"
            Map.entry(Brawler.DOUBLE_UPPERCUT,       DOUBLEUPPER),     // 5101003 -> "doubleupper"
            Map.entry(Brawler.CORKSCREW_BLOW,        SCREW),           // 5101004 -> "screw"
            Map.entry(Marauder.ENERGY_BLAST,         EBURSTER),        // 5111002 -> "eburster"
            Map.entry(Marauder.ENERGY_DRAIN,         EDRAIN),          // 5111004 -> "edrain"
            Map.entry(Marauder.SHOCKWAVE,            SHOCKWAVE_P),     // 5111006 -> "shockwave"
            Map.entry(Buccaneer.BARRAGE,             FIST),            // 5121007 -> "fist"
            Map.entry(Buccaneer.DEMOLITION,          DEMOLITION_P),    // 5121004 -> "demolition"
            Map.entry(Buccaneer.DRAGON_STRIKE,       DRAGONSTRIKE),    // 5121001 -> "dragonstrike"
            Map.entry(Buccaneer.ENERGY_ORB,          EORB),            // 5121002 -> "eorb"
            Map.entry(Buccaneer.SNATCH,              SNATCH),          // 5121005 -> "snatch"
            // Gunslinger / corsair line (gun, ranged)
            Map.entry(Pirate.DOUBLE_SHOT,            DOUBLEFIRE),      // 5001003 -> "doublefire"
            Map.entry(Gunslinger.INVISIBLE_SHOT,     DOUBLEFIRE),      // 5201001 -> no action node; single gun shot
            Map.entry(Outlaw.BURST_FIRE,             TRIPLEFIRE),      // 5210000 -> "triplefire" (3-round burst)
            Map.entry(Outlaw.FLAME_THROWER,          FLAMEBURNER),     // 5211004 -> "fireburner"
            Map.entry(Outlaw.ICE_SPLITTER,           COOLINGEFFECT),   // 5211005 -> "coolingeffect"
            Map.entry(Outlaw.HOMING_BEACON,          HOMING),          // 5211006 -> "homing"
            Map.entry(Corsair.RAPID_FIRE,            RAPIDFIRE),       // 5221004 -> "rapidfire"
            Map.entry(Corsair.BATTLESHIP_CANNON,     CANNON),          // 5221007 -> "cannon"
            Map.entry(Corsair.BATTLESHIP_TORPEDO,    TORPEDO)          // 5221008 -> "torpedo"
    );

    /* The body action for a skill on a weapon: the skill's own keyframe if it overrides, else the weapon default. */
    public static int actionFor(int skillId, WeaponType weaponType) {
        Integer override = SKILL_ACTION.get(skillId);
        return override != null ? override : randomActionFor(weaponType);
    }

    // Big Bang (F/P, I/L, Bishop) is a keydown CHARGE skill: its MAGIC_ATTACK packet carries a
    // trailing charge int that the viewer's client reads back (see MagicDamageHandler / the
    // parser's BIG_BANG branch). Sending charge = -1 omits those 4 bytes, the client over-reads,
    // and CRASHES - this was the 4th-job crash. Full keydown is ~1080 ms; send that for a full ball.
    private static final int BIG_BANG_CHARGE = 1080;
    private static final Set<Integer> CHARGE_MAGIC_SKILLS =
            Set.of(FPArchMage.BIG_BANG, ILArchMage.BIG_BANG, Bishop.BIG_BANG);

    /* The charge int a magic attack must carry (only Big Bang needs one), or -1 for every other skill. */
    public static int magicChargeFor(int skillId) {
        return CHARGE_MAGIC_SKILLS.contains(skillId) ? BIG_BANG_CHARGE : -1;
    }

    // Critical-hit display encoding. v83 has no crit flag in the attack packet: a damage line is
    // marked crit by NEGATING it, and the viewer's client renders a negative line as a yellow crit
    // (and shoves the mob back harder, since knockback keys off the displayed hit). The engine does
    // exactly this in AbstractDealDamageHandler.parseDamage and decodes it back before applying HP -
    // we mirror both sides so bot crits look identical and HP still subtracts the real amount.
    public static int encodeCritLine(int rawDamage) {
        return -Integer.MAX_VALUE + rawDamage - 1;
    }

    /* Real magnitude of a packet damage line: crit lines arrive negative-encoded, normal lines pass through. */
    public static int decodeDamageLine(int line) {
        return line < 0 ? line + Integer.MAX_VALUE : line;
    }

    /* Random body-action id appropriate for the given weapon class. */
    public static int randomActionFor(WeaponType weaponType) {
        int[] variants = variantsFor(weaponType);
        return variants[ThreadLocalRandom.current().nextInt(variants.length)];
    }

    // Standard v83 ammo ids (no named ItemId constant for arrows).
    private static final int ARROW_FOR_BOW = 2060000;
    private static final int ARROW_FOR_CROSSBOW = 2061000;

    /* The flying projectile a RANGED route renders for this weapon, or 0 if it needs none. */
    public static int projectileFor(WeaponType weaponType) {
        if (weaponType == null) {
            return 0;
        }
        return switch (weaponType) {
            case BOW      -> ARROW_FOR_BOW;
            case CROSSBOW -> ARROW_FOR_CROSSBOW;
            case CLAW     -> ItemId.SUBI_THROWING_STARS;
            case GUN      -> ItemId.BULLET;
            default       -> 0;
        };
    }

    /* Like projectileFor(weapon) but, for a claw bot, returns the level-appropriate star the bot chose
     * at creation (ThrowingStarSelector), falling back to subi when there is none / the attacker isn't
     * a registered bot. Bow/gun/dagger are unaffected. */
    public static int projectileFor(WeaponType weaponType, Character bot) {
        if (weaponType == WeaponType.CLAW) {
            int chosen = ThrowingStarSelector.chosenStar(bot);
            if (chosen > 0) {
                return chosen;
            }
        }
        return projectileFor(weaponType);
    }

    /*
     * True when a warrior's skill should use its alternate weapon form: axe/blunt (the
     * GENERAL* classes) and pole-arms take the alt id; swords and spears take the default.
     */
    public static boolean usesAltWarriorVariant(WeaponType weaponType) {
        if (weaponType == null) {
            return false;
        }
        return switch (weaponType) {
            case GENERAL1H_SWING, GENERAL1H_STAB, GENERAL2H_SWING, GENERAL2H_STAB,
                 POLE_ARM_SWING, POLE_ARM_STAB -> true;
            default -> false;
        };
    }

    private static int[] variantsFor(WeaponType weaponType) {
        if (weaponType == null) {
            return DEFAULT_1H_VARIANTS;
        }
        return switch (weaponType) {
            case SWORD2H, GENERAL2H_SWING, GENERAL2H_STAB               -> HEAVY_2H_VARIANTS;
            case SPEAR_SWING, SPEAR_STAB, POLE_ARM_SWING, POLE_ARM_STAB -> POLEARM_VARIANTS;
            case WAND, STAFF                                            -> WAND_VARIANTS;
            case CLAW                                                   -> CLAW_VARIANTS;
            case BOW                                                    -> BOW_VARIANTS;      // shoot1 (bow draw)
            case CROSSBOW                                               -> CROSSBOW_VARIANTS; // shoot2 (crossbow)
            case GUN                                                    -> GUN_VARIANTS;      // shot (gun fire)
            default                                                     -> DEFAULT_1H_VARIANTS; // 1H/dagger/knuckle/unarmed
        };
    }
}
