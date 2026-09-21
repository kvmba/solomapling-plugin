package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.inventory.WeaponType;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.Buccaneer;
import org.gms.constants.skills.Corsair;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.Outlaw;
import org.gms.constants.skills.Pirate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pirate skill body-action ids against the canonical v83 client action enum.
 *
 * <p>These codes were read from the client's own {@code s_aCharacterActionData} table (the array
 * {@code get_action_code_from_name} walks; slot index == action code). Cross-checking that table
 * reproduces every code the plugin already shipped (savage=55, alert3=42, magic1/2/3=49/50/51,
 * burster2=54, avenger=56, alert5=44, assassination=59, brandish1=63, sanctuary=65, meteor=66,
 * blizzard=68, genesis=69, blast=71) is what validates the table this test samples from. A wrong
 * code makes the viewer render the wrong pose (or crash), so this is a hard contract.
 */
class PirateActionIdTest {

    @Test
    void pirateSkillsResolveToTheirCanonicalPose() {
        // Each is the "action" node of the skill's Skill.wz entry, mapped through the v83 enum.
        assertEquals(79,  BotAttackData.actionFor(Pirate.FLASH_FIST, null));      // straight
        assertEquals(78,  BotAttackData.actionFor(Pirate.SOMERSAULT_KICK, null)); // somersault
        assertEquals(86,  BotAttackData.actionFor(Pirate.DOUBLE_SHOT, null));     // doublefire
        assertEquals(81,  BotAttackData.actionFor(Brawler.BACK_SPIN_BLOW, null)); // backspin
        assertEquals(84,  BotAttackData.actionFor(Brawler.DOUBLE_UPPERCUT, null));// doubleupper
        assertEquals(83,  BotAttackData.actionFor(Brawler.CORKSCREW_BLOW, null)); // screw
        assertEquals(80,  BotAttackData.actionFor(Marauder.ENERGY_BLAST, null));  // eburster
        assertEquals(90,  BotAttackData.actionFor(Marauder.ENERGY_DRAIN, null));  // edrain
        assertEquals(157, BotAttackData.actionFor(Marauder.SHOCKWAVE, null));     // shockwave
        assertEquals(97,  BotAttackData.actionFor(Buccaneer.BARRAGE, null));      // fist
        assertEquals(158, BotAttackData.actionFor(Buccaneer.DEMOLITION, null));   // demolition
        assertEquals(85,  BotAttackData.actionFor(Buccaneer.DRAGON_STRIKE, null));// dragonstrike
        assertEquals(82,  BotAttackData.actionFor(Buccaneer.ENERGY_ORB, null));   // eorb
        assertEquals(159, BotAttackData.actionFor(Buccaneer.SNATCH, null));       // snatch
        assertEquals(87,  BotAttackData.actionFor(Outlaw.BURST_FIRE, null));      // triplefire
        assertEquals(95,  BotAttackData.actionFor(Outlaw.FLAME_THROWER, null));   // fireburner
        assertEquals(96,  BotAttackData.actionFor(Outlaw.ICE_SPLITTER, null));    // coolingeffect
        assertEquals(100, BotAttackData.actionFor(Outlaw.HOMING_BEACON, null));   // homing
        assertEquals(99,  BotAttackData.actionFor(Corsair.RAPID_FIRE, null));     // rapidfire
        assertEquals(109, BotAttackData.actionFor(Corsair.BATTLESHIP_CANNON, null));   // cannon
        assertEquals(110, BotAttackData.actionFor(Corsair.BATTLESHIP_TORPEDO, null));  // torpedo
    }

    @Test
    void gunUsesTheShotPoseAsItsWeaponDefault() {
        // A gun bot fires with "shot" (93) - the gunner's own fire pose, which is exactly what the
        // real client sends in the RANGED_ATTACK direction byte (live v83 capture: a no-skill gun
        // swing carries 0x5D = 93). shootF (27) is not a gun fire action and renders no shot/bullet.
        assertEquals(93, BotAttackData.randomActionFor(WeaponType.GUN));
    }

    @Test
    void piratePosesDoNotCollideWithTheMeleeDefaults() {
        // A pirate pose must not equal a plain weapon swing, or the override would be a no-op
        // (indistinguishable from the default) and a mis-mapped code could hide here.
        Set<Integer> meleeDefaults = Set.of(5, 6, 7, 9, 10, 11, 13, 16, 17, 19); // swing/stab defaults
        int[] piratePoses = {79, 78, 86, 87, 81, 84, 83, 80, 90, 157, 97, 158, 85, 82, 159, 95, 96, 100, 99, 109, 110};
        for (int p : piratePoses) {
            assertTrue(!meleeDefaults.contains(p), "pirate pose " + p + " collides with a melee default");
        }
    }
}
