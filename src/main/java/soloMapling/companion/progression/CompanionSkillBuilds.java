package soloMapling.companion.progression;

import org.gms.constants.skills.*;
import soloMapling.companion.progression.CompanionSkillBuild.BlockedPolicy;
import soloMapling.companion.progression.CompanionSkillBuild.SkillMilestone;
import soloMapling.companion.progression.CompanionSkillBuild.SkillPrerequisite;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static soloMapling.companion.progression.CompanionSkillBuild.MAX;

/** v0.83 regular-training SP profiles for all twelve Explorer branches. */
public final class CompanionSkillBuilds {
    private static final Map<CompanionCareerBuild, CompanionSkillBuild> BUILDS = buildAll();

    private CompanionSkillBuilds() {
    }

    public static CompanionSkillBuild forCareer(CompanionCareerBuild career) {
        CompanionSkillBuild build = BUILDS.get(career);
        if (build == null) {
            throw new IllegalArgumentException("No skill build for " + career);
        }
        return build;
    }

    private static Map<CompanionCareerBuild, CompanionSkillBuild> buildAll() {
        EnumMap<CompanionCareerBuild, CompanionSkillBuild> builds =
                new EnumMap<>(CompanionCareerBuild.class);
        for (CompanionCareerBuild career : CompanionCareerBuild.values()) {
            List<SkillMilestone> milestones = new ArrayList<>();
            milestones.addAll(firstJob(career));
            milestones.addAll(secondJob(career));
            milestones.addAll(thirdJob(career));
            milestones.addAll(fourthJob(career));
            builds.put(career, new CompanionSkillBuild(
                    career, CompanionCareerBuild.RULESET_VERSION, milestones));
        }
        return Map.copyOf(builds);
    }

    private static List<SkillMilestone> firstJob(CompanionCareerBuild career) {
        return switch (career.firstJobId()) {
            case 100 -> List.of(
                    at(Warrior.IMPROVED_HPREC, 5, 10),
                    at(Warrior.IMPROVED_MAXHP, 10, 10, req(Warrior.IMPROVED_HPREC, 5)),
                    at(Warrior.SLASH_BLAST, 20, 10),
                    at(Warrior.POWER_STRIKE, 20, 10),
                    at(Warrior.ENDURE, 6, 10));
            case 200 -> List.of(
                    at(Magician.ENERGY_BOLT, 1, 8),
                    at(Magician.IMPROVED_MP_RECOVERY, 5, 8),
                    at(Magician.IMPROVED_MAX_MP_INCREASE, 10, 8,
                            req(Magician.IMPROVED_MP_RECOVERY, 5)),
                    at(Magician.MAGIC_CLAW, 20, 8, req(Magician.ENERGY_BOLT, 1)),
                    at(Magician.IMPROVED_MP_RECOVERY, 16, 8),
                    at(Magician.MAGIC_GUARD, 20, 8));
            case 300 -> List.of(
                    at(Archer.ARROW_BLOW, 1, 10),
                    at(Archer.BLESSING_OF_AMAZON, 3, 10),
                    at(Archer.EYE_OF_AMAZON, 8, 10,
                            req(Archer.BLESSING_OF_AMAZON, 3)),
                    at(Archer.DOUBLE_SHOT, 10, 10, req(Archer.ARROW_BLOW, 1)),
                    at(Archer.CRITICAL_SHOT, 20, 10),
                    at(Archer.FOCUS, 9, 10),
                    at(Archer.DOUBLE_SHOT, 20, 30, req(Archer.ARROW_BLOW, 1)));
            case 400 -> career == CompanionCareerBuild.NIGHT_LORD
                    ? List.of(
                            at(Rogue.LUCKY_SEVEN, 20, 10),
                            at(Rogue.NIMBLE_BODY, 3, 10),
                            at(Rogue.KEEN_EYES, 8, 10, req(Rogue.NIMBLE_BODY, 3)),
                            at(Rogue.DISORDER, 3, 10),
                            at(Rogue.DARK_SIGHT, 20, 10, req(Rogue.DISORDER, 3)),
                            at(Rogue.NIMBLE_BODY, 10, 10))
                    : List.of(
                            at(Rogue.DOUBLE_STAB, 20, 10),
                            at(Rogue.DISORDER, 3, 10),
                            at(Rogue.DARK_SIGHT, 20, 10, req(Rogue.DISORDER, 3)),
                            at(Rogue.NIMBLE_BODY, 18, 10));
            case 500 -> career == CompanionCareerBuild.BUCCANEER
                    ? List.of(
                            at(Pirate.BULLET_TIME, 20, 10),
                            at(Pirate.SOMERSAULT_KICK, 20, 10),
                            at(Pirate.FLASH_FIST, 20, 10),
                            at(Pirate.DASH, 1, 10))
                    : List.of(
                            at(Pirate.BULLET_TIME, 20, 10),
                            at(Pirate.DOUBLE_SHOT, 20, 10),
                            at(Pirate.DASH, 10, 10),
                            at(Pirate.SOMERSAULT_KICK, 11, 10));
            default -> throw new IllegalStateException("Unsupported Explorer family");
        };
    }

    private static List<SkillMilestone> secondJob(CompanionCareerBuild career) {
        return switch (career) {
            case HERO_SWORD -> List.of(
                    at(Fighter.SWORD_MASTERY, 20, 30),
                    at(Fighter.SWORD_BOOSTER, 20, 30, req(Fighter.SWORD_MASTERY, 5)),
                    at(Fighter.RAGE, 20, 30),
                    at(Fighter.POWER_GUARD, 30, 30),
                    at(Fighter.FINAL_ATTACK_SWORD, 30, 30),
                    at(Fighter.AXE_MASTERY, 1, 30));
            case PALADIN_SWORD -> List.of(
                    at(Page.SWORD_MASTERY, 20, 30),
                    at(Page.SWORD_BOOSTER, 20, 30, req(Page.SWORD_MASTERY, 5)),
                    at(Page.THREATEN, 20, 30),
                    at(Page.POWER_GUARD, 30, 30, req(Page.THREATEN, 3)),
                    at(Page.FINAL_ATTACK_SWORD, 30, 30),
                    at(Page.BW_MASTERY, 1, 30));
            case DARK_KNIGHT_SPEAR -> List.of(
                    at(Spearman.SPEAR_MASTERY, 20, 30),
                    at(Spearman.SPEAR_BOOSTER, 20, 30, req(Spearman.SPEAR_MASTERY, 5)),
                    at(Spearman.IRON_WILL, 20, 30),
                    at(Spearman.HYPER_BODY, 30, 30, req(Spearman.IRON_WILL, 3)),
                    at(Spearman.POLEARM_MASTERY, 20, 30),
                    at(Spearman.POLEARM_BOOSTER, 11, 30,
                            req(Spearman.POLEARM_MASTERY, 5)));
            case FIRE_POISON_ARCHMAGE -> List.of(
                    at(FPWizard.TELEPORT, 20, 30),
                    at(FPWizard.FIRE_ARROW, 30, 30),
                    at(FPWizard.POISON_BREATH, 30, 30),
                    at(FPWizard.MP_EATER, 20, 30),
                    at(FPWizard.MEDITATION, 20, 30, req(FPWizard.MP_EATER, 3)),
                    at(FPWizard.SLOW, 1, 30));
            case ICE_LIGHTNING_ARCHMAGE -> List.of(
                    at(ILWizard.TELEPORT, 20, 30),
                    at(ILWizard.THUNDERBOLT, 30, 30),
                    at(ILWizard.COLD_BEAM, 30, 30),
                    at(ILWizard.MP_EATER, 20, 30),
                    at(ILWizard.MEDITATION, 20, 30, req(ILWizard.MP_EATER, 3)),
                    at(ILWizard.SLOW, 1, 30));
            case BISHOP -> List.of(
                    at(Cleric.TELEPORT, 20, 30),
                    at(Cleric.HEAL, 30, 30),
                    at(Cleric.INVINCIBLE, 20, 30, req(Cleric.HEAL, 5)),
                    at(Cleric.BLESS, 20, 30, req(Cleric.INVINCIBLE, 5)),
                    at(Cleric.MP_EATER, 20, 30),
                    at(Cleric.HOLY_ARROW, 11, 30));
            case BOWMASTER -> List.of(
                    at(Hunter.ARROW_BOMB, 1, 30),
                    at(Hunter.BOW_MASTERY, 20, 30),
                    at(Hunter.BOW_BOOSTER, 15, 30, req(Hunter.BOW_MASTERY, 5)),
                    at(Hunter.SOUL_ARROW, 6, 30, req(Hunter.BOW_BOOSTER, 5)),
                    at(Hunter.ARROW_BOMB, 30, 30),
                    at(Hunter.POWER_KNOCKBACK, 20, 30),
                    at(Hunter.FINAL_ATTACK, 30, 30));
            case MARKSMAN -> List.of(
                    at(Crossbowman.IRON_ARROW, 1, 30),
                    at(Crossbowman.CROSSBOW_MASTERY, 20, 30),
                    at(Crossbowman.CROSSBOW_BOOSTER, 15, 30,
                            req(Crossbowman.CROSSBOW_MASTERY, 5)),
                    at(Crossbowman.SOUL_ARROW, 6, 30,
                            req(Crossbowman.CROSSBOW_BOOSTER, 5)),
                    at(Crossbowman.IRON_ARROW, 30, 30),
                    at(Crossbowman.POWER_KNOCKBACK, 20, 30),
                    at(Crossbowman.FINAL_ATTACK, 30, 30));
            case NIGHT_LORD -> List.of(
                    at(Assassin.CRITICAL_THROW, 30, 30),
                    at(Assassin.HASTE, 20, 30),
                    at(Assassin.CLAW_MASTERY, 20, 30),
                    at(Assassin.CLAW_BOOSTER, 20, 30, req(Assassin.CLAW_MASTERY, 5)),
                    at(Assassin.DRAIN, 28, 30),
                    at(Assassin.ENDURE, 3, 30));
            case SHADOWER -> List.of(
                    at(Bandit.DAGGER_MASTERY, 20, 30),
                    at(Bandit.SAVAGE_BLOW, 30, 30),
                    at(Bandit.HASTE, 20, 30),
                    at(Bandit.DAGGER_BOOSTER, 20, 30, req(Bandit.DAGGER_MASTERY, 5)),
                    at(Bandit.ENDURE, 20, 30),
                    at(Bandit.STEAL, 11, 30));
            case BUCCANEER -> List.of(
                    at(Brawler.IMPROVE_MAX_HP, 10, 30),
                    at(Brawler.KNUCKLER_MASTERY, 20, 30),
                    at(Brawler.KNUCKLER_BOOSTER, 20, 30,
                            req(Brawler.KNUCKLER_MASTERY, 5)),
                    at(Brawler.BACK_SPIN_BLOW, 20, 30),
                    at(Brawler.DOUBLE_UPPERCUT, 20, 30),
                    at(Brawler.CORKSCREW_BLOW, 20, 30),
                    at(Brawler.MP_RECOVERY, 10, 30),
                    at(Brawler.OAK_BARREL, 1, 30));
            case CORSAIR -> List.of(
                    at(Gunslinger.GUN_MASTERY, 20, 30),
                    at(Gunslinger.INVISIBLE_SHOT, 20, 30),
                    at(Gunslinger.GUN_BOOSTER, 20, 30, req(Gunslinger.GUN_MASTERY, 5)),
                    at(Gunslinger.WINGS, 10, 30),
                    at(Gunslinger.RECOIL_SHOT, 20, 30, req(Gunslinger.WINGS, 5)),
                    at(Gunslinger.GRENADE, 20, 30),
                    at(Gunslinger.BLANK_SHOT, 11, 30));
        };
    }

    private static List<SkillMilestone> thirdJob(CompanionCareerBuild career) {
        return switch (career) {
            case HERO_SWORD -> List.of(
                    at(Crusader.COMBO, 30, 70),
                    at(Crusader.SWORD_PANIC, 30, 70, req(Crusader.COMBO, 1)),
                    at(Crusader.SWORD_COMA, 30, 70, req(Crusader.COMBO, 1)),
                    at(Crusader.SHOUT, 30, 70),
                    at(Crusader.IMPROVING_MPREC, 20, 70),
                    at(Crusader.ARMOR_CRASH, 11, 70));
            case PALADIN_SWORD -> List.of(
                    at(WhiteKnight.SWORD_FIRE_CHARGE, 30, 70),
                    at(WhiteKnight.SWORD_ICE_CHARGE, 30, 70),
                    at(WhiteKnight.SWORD_LIT_CHARGE, 30, 70),
                    at(WhiteKnight.CHARGE_BLOW, 30, 70),
                    at(WhiteKnight.IMPROVING_MP_RECOVERY, 20, 70),
                    at(WhiteKnight.MAGIC_CRASH, 11, 70));
            case DARK_KNIGHT_SPEAR -> List.of(
                    at(DragonKnight.SPEAR_CRUSHER, 30, 70),
                    at(DragonKnight.SPEAR_DRAGON_FURY, 30, 70),
                    at(DragonKnight.SACRIFICE, 3, 70),
                    at(DragonKnight.DRAGON_ROAR, 30, 70, req(DragonKnight.SACRIFICE, 3)),
                    at(DragonKnight.SACRIFICE, 20, 70),
                    at(DragonKnight.ELEMENTAL_RESISTANCE, 20, 70),
                    at(DragonKnight.DRAGON_BLOOD, 20, 70),
                    at(DragonKnight.POWER_CRASH, 1, 70));
            case FIRE_POISON_ARCHMAGE -> List.of(
                    at(FPMage.POISON_MIST, 30, 70),
                    at(FPMage.EXPLOSION, 30, 70),
                    at(FPMage.ELEMENT_AMPLIFICATION, 30, 70),
                    at(FPMage.SPELL_BOOSTER, 20, 70, req(FPMage.ELEMENT_AMPLIFICATION, 3)),
                    at(FPMage.ELEMENT_COMPOSITION, 30, 70),
                    at(FPMage.PARTIAL_RESISTANCE, 10, 70),
                    at(FPMage.SEAL, 1, 70));
            case ICE_LIGHTNING_ARCHMAGE -> List.of(
                    at(ILMage.ICE_STRIKE, 30, 70),
                    at(ILMage.THUNDER_SPEAR, 30, 70),
                    at(ILMage.ELEMENT_AMPLIFICATION, 30, 70),
                    at(ILMage.SPELL_BOOSTER, 20, 70, req(ILMage.ELEMENT_AMPLIFICATION, 3)),
                    at(ILMage.ELEMENT_COMPOSITION, 30, 70),
                    at(ILMage.PARTIAL_RESISTANCE, 10, 70),
                    at(ILMage.SEAL, 1, 70));
            case BISHOP -> List.of(
                    at(Priest.DISPEL, 3, 70),
                    at(Priest.HOLY_SYMBOL, 30, 70, req(Priest.DISPEL, 3)),
                    at(Priest.SHINING_RAY, 30, 70),
                    at(Priest.DISPEL, 20, 70),
                    at(Priest.SUMMON_DRAGON, 30, 70),
                    at(Priest.ELEMENTAL_RESISTANCE, 20, 70),
                    at(Priest.MYSTIC_DOOR, 20, 70, req(Priest.DISPEL, 3)),
                    at(Priest.DOOM, 1, 70));
            case BOWMASTER -> List.of(
                    at(Ranger.STRAFE, 1, 70),
                    at(Ranger.MORTAL_BLOW, 5, 70),
                    at(Ranger.ARROW_RAIN, 30, 70, req(Ranger.MORTAL_BLOW, 5)),
                    at(Ranger.STRAFE, 30, 70),
                    at(Ranger.PUPPET, 20, 70),
                    at(Ranger.SILVER_HAWK, 30, 70, req(Ranger.PUPPET, 5)),
                    at(Ranger.INFERNO, 30, 70),
                    at(Ranger.THRUST, 6, 70));
            case MARKSMAN -> List.of(
                    at(Sniper.BLIZZARD, 1, 70),
                    at(Sniper.MORTAL_BLOW, 5, 70),
                    at(Sniper.ARROW_ERUPTION, 30, 70, req(Sniper.MORTAL_BLOW, 5)),
                    at(Sniper.STRAFE, 30, 70),
                    at(Sniper.PUPPET, 20, 70),
                    at(Sniper.GOLDEN_EAGLE, 15, 70, req(Sniper.PUPPET, 5)),
                    at(Sniper.BLIZZARD, 21, 70),
                    at(Sniper.MORTAL_BLOW, 20, 70),
                    at(Sniper.THRUST, 15, 70));
            case NIGHT_LORD -> List.of(
                    at(Hermit.SHADOW_PARTNER, 30, 70),
                    at(Hermit.AVENGER, 30, 70),
                    at(Hermit.FLASH_JUMP, 20, 70, req(Hermit.AVENGER, 5)),
                    at(Hermit.ALCHEMIST, 20, 70),
                    at(Hermit.MESO_UP, 20, 70),
                    at(Hermit.SHADOW_WEB, 20, 70),
                    at(Hermit.SHADOW_MESO, 11, 70));
            case SHADOWER -> List.of(
                    at(ChiefBandit.MESO_EXPLOSION, 30, 70),
                    at(ChiefBandit.CHAKRA, 3, 70),
                    at(ChiefBandit.MESO_GUARD, 20, 70, req(ChiefBandit.CHAKRA, 3)),
                    at(ChiefBandit.BAND_OF_THIEVES, 30, 70),
                    at(ChiefBandit.ASSAULTER, 30, 70),
                    at(ChiefBandit.PICKPOCKET, 20, 70),
                    at(ChiefBandit.CHAKRA, 20, 70),
                    at(ChiefBandit.SHIELD_MASTERY, 1, 70));
            case BUCCANEER -> List.of(
                    at(Marauder.ENERGY_CHARGE, 40, 70),
                    at(Marauder.ENERGY_BLAST, 30, 70, req(Marauder.ENERGY_CHARGE, 1)),
                    at(Marauder.TRANSFORMATION, 20, 70),
                    at(Marauder.SHOCKWAVE, 30, 70, req(Marauder.TRANSFORMATION, 1)),
                    at(Marauder.STUN_MASTERY, 20, 70),
                    at(Marauder.ENERGY_DRAIN, 11, 70, req(Marauder.ENERGY_CHARGE, 1)));
            case CORSAIR -> List.of(
                    at(Outlaw.BURST_FIRE, 20, 70),
                    at(Outlaw.ICE_SPLITTER, 26, 70),
                    at(Outlaw.FLAME_THROWER, 30, 70),
                    at(Outlaw.OCTOPUS, 30, 70),
                    at(Outlaw.HOMING_BEACON, 30, 70),
                    at(Outlaw.GAVIOTA, 15, 70));
        };
    }

    private static List<SkillMilestone> fourthJob(CompanionCareerBuild career) {
        return switch (career) {
            case HERO_SWORD -> fourth(
                    Hero.BRANDISH, Hero.ADVANCED_COMBO, Hero.STANCE, Hero.RUSH,
                    Hero.MAPLE_WARRIOR, Hero.ACHILLES, Hero.ENRAGE, Hero.HEROS_WILL);
            case PALADIN_SWORD -> fourth(
                    Paladin.BLAST, Paladin.ADVANCED_CHARGE, Paladin.STANCE,
                    Paladin.SWORD_HOLY_CHARGE, Paladin.RUSH, Paladin.MAPLE_WARRIOR,
                    Paladin.ACHILLES, Paladin.HEAVENS_HAMMER, Paladin.HEROS_WILL);
            case DARK_KNIGHT_SPEAR -> fourth(
                    DarkKnight.BERSERK, DarkKnight.STANCE, DarkKnight.RUSH,
                    DarkKnight.BEHOLDER, DarkKnight.HEX_OF_BEHOLDER,
                    DarkKnight.MAPLE_WARRIOR, DarkKnight.ACHILLES,
                    DarkKnight.AURA_OF_BEHOLDER, DarkKnight.HEROS_WILL);
            case FIRE_POISON_ARCHMAGE -> fourth(
                    FPArchMage.METEOR_SHOWER, FPArchMage.PARALYZE,
                    FPArchMage.MAPLE_WARRIOR, FPArchMage.ELQUINES,
                    FPArchMage.INFINITY, FPArchMage.BIG_BANG,
                    FPArchMage.FIRE_DEMON, FPArchMage.MANA_REFLECTION,
                    FPArchMage.HEROS_WILL);
            case ICE_LIGHTNING_ARCHMAGE -> fourth(
                    ILArchMage.BLIZZARD, ILArchMage.CHAIN_LIGHTNING,
                    ILArchMage.MAPLE_WARRIOR, ILArchMage.IFRIT,
                    ILArchMage.INFINITY, ILArchMage.BIG_BANG,
                    ILArchMage.ICE_DEMON, ILArchMage.MANA_REFLECTION,
                    ILArchMage.HEROS_WILL);
            case BISHOP -> fourth(
                    Bishop.GENESIS, Bishop.ANGEL_RAY, Bishop.MAPLE_WARRIOR,
                    Bishop.BAHAMUT, Bishop.RESURRECTION, Bishop.HOLY_SHIELD,
                    Bishop.INFINITY, Bishop.BIG_BANG, Bishop.MANA_REFLECTION,
                    Bishop.HEROS_WILL);
            case BOWMASTER -> fourth(
                    Bowmaster.HURRICANE, Bowmaster.SHARP_EYES,
                    Bowmaster.BOW_EXPERT, Bowmaster.MAPLE_WARRIOR,
                    Bowmaster.PHOENIX, Bowmaster.CONCENTRATE,
                    Bowmaster.DRAGONS_BREATH, Bowmaster.HAMSTRING,
                    Bowmaster.HEROS_WILL);
            case MARKSMAN -> fourth(
                    Marksman.PIERCING_ARROW, Marksman.SHARP_EYES,
                    Marksman.MARKSMAN_BOOST, Marksman.SNIPE,
                    Marksman.MAPLE_WARRIOR, Marksman.FROST_PREY,
                    Marksman.DRAGONS_BREATH, Marksman.BLIND,
                    Marksman.HEROS_WILL);
            case NIGHT_LORD -> fourth(
                    NightLord.TRIPLE_THROW, NightLord.SHADOW_STARS,
                    NightLord.SHADOW_SHIFTER, NightLord.MAPLE_WARRIOR,
                    NightLord.VENOMOUS_STAR, NightLord.NINJA_STORM,
                    NightLord.TAUNT, NightLord.HEROS_WILL);
            case SHADOWER -> fourth(
                    Shadower.BOOMERANG_STEP, Shadower.ASSASSINATE,
                    Shadower.SMOKE_SCREEN, Shadower.SHADOW_SHIFTER,
                    Shadower.MAPLE_WARRIOR, Shadower.VENOMOUS_STAB,
                    Shadower.TAUNT, Shadower.HEROS_WILL);
            case BUCCANEER -> fourth(
                    Buccaneer.DRAGON_STRIKE, Buccaneer.BARRAGE,
                    Buccaneer.SPEED_INFUSION, Buccaneer.SUPER_TRANSFORMATION,
                    Buccaneer.DEMOLITION, Buccaneer.TIME_LEAP,
                    Buccaneer.MAPLE_WARRIOR, Buccaneer.ENERGY_ORB,
                    Buccaneer.PIRATES_RAGE);
            case CORSAIR -> fourth(
                    Corsair.BATTLE_SHIP, Corsair.BATTLESHIP_CANNON,
                    Corsair.RAPID_FIRE, Corsair.BULLSEYE,
                    Corsair.ELEMENTAL_BOOST, Corsair.BATTLESHIP_TORPEDO,
                    Corsair.AERIAL_STRIKE, Corsair.WRATH_OF_THE_OCTOPI,
                    Corsair.MAPLE_WARRIOR, Corsair.HEROS_WILL);
        };
    }

    private static List<SkillMilestone> fourth(int... skillIds) {
        List<SkillMilestone> milestones = new ArrayList<>(skillIds.length);
        for (int i = 0; i < skillIds.length; i++) {
            milestones.add(new SkillMilestone(
                    skillIds[i],
                    MAX,
                    120,
                    i + 1 == skillIds.length ? BlockedPolicy.RESERVE : BlockedPolicy.SKIP,
                    fourthPrerequisites(skillIds[i])));
        }
        return List.copyOf(milestones);
    }

    private static List<SkillPrerequisite> fourthPrerequisites(int skillId) {
        return switch (skillId) {
            case Hero.ADVANCED_COMBO -> List.of(req(Crusader.COMBO, 30));
            case Bowmaster.BOW_EXPERT -> List.of(req(Hunter.BOW_MASTERY, 20));
            case Bowmaster.PHOENIX -> List.of(req(Ranger.SILVER_HAWK, 15));
            case Marksman.MARKSMAN_BOOST ->
                    List.of(req(Crossbowman.CROSSBOW_MASTERY, 20));
            case Marksman.FROST_PREY -> List.of(req(Sniper.GOLDEN_EAGLE, 15));
            case Bishop.BAHAMUT -> List.of(req(Priest.SUMMON_DRAGON, 15));
            case Buccaneer.SUPER_TRANSFORMATION ->
                    List.of(req(Marauder.TRANSFORMATION, 20));
            case Buccaneer.ENERGY_ORB -> List.of(req(Marauder.ENERGY_CHARGE, 1));
            case Corsair.RAPID_FIRE -> List.of(req(Outlaw.BURST_FIRE, 20));
            case Corsair.WRATH_OF_THE_OCTOPI -> List.of(req(Outlaw.OCTOPUS, 30));
            case Corsair.AERIAL_STRIKE -> List.of(req(Outlaw.GAVIOTA, 15));
            case Corsair.BULLSEYE -> List.of(req(Outlaw.HOMING_BEACON, 30));
            case Corsair.BATTLESHIP_CANNON, Corsair.BATTLESHIP_TORPEDO ->
                    List.of(req(Corsair.BATTLE_SHIP, 1));
            default -> List.of();
        };
    }

    private static SkillMilestone at(int skillId, int target, int level) {
        return new SkillMilestone(
                skillId, target, level, BlockedPolicy.RESERVE, List.of());
    }

    private static SkillMilestone at(
            int skillId, int target, int level, SkillPrerequisite... prerequisites) {
        return new SkillMilestone(
                skillId, target, level, BlockedPolicy.RESERVE, List.of(prerequisites));
    }

    private static SkillPrerequisite req(int skillId, int level) {
        return new SkillPrerequisite(skillId, level);
    }
}
