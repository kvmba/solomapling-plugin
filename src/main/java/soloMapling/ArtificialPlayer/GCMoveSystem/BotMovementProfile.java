package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.server.ItemInformationProvider;
import org.gms.server.maps.FieldLimit;
import org.gms.server.maps.MapleMap;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

// Ported from GreenCatMS. Credit: NutNNut.
record BotMovementProfile(int totalSpeedStat, int totalJumpStat, boolean snowShoes)
        implements Serializable {
    // Serialized inside cached BotNavigationGraph instances; keep explicit so
    // cache compatibility is controlled by GRAPH_VERSION instead of compiler-generated UIDs.
    @Serial
    private static final long serialVersionUID = 1L;

    static final int BASE_TOTAL_STAT = 100;
    static final int STAT_BUCKET_SIZE = 5;
    static final int MAX_EFFECTIVE_SPEED_STAT = 200;
    static final int MAX_EFFECTIVE_JUMP_STAT = 123;
    static final BotMovementProfile BASE = new BotMovementProfile(BASE_TOTAL_STAT, BASE_TOTAL_STAT);

    // Class-aware "effective Haste". Bots' Haste buff is cosmetic (no stat), so thief speed/jump is
    // modelled here: thieves are the fast class, and a bot sharing a party with a high-level
    // haste-thief inherits the thief speed.
    // See Documents/Movement - 2026-07-06 Class-Aware Speed and Jump.
    //
    // SPEED SCALE — calibrated against the real client, where 100% = WALK_VEL (125 px/s).
    // These baselines REPLACE the 100 floor rather than stacking on top of it (equip/buff speed
    // still adds), so the tier number IS the bot's walking speed in %.
    //
    // Speed is CLASS-BASED, not level-based: every non-thief walks the same NORMAL_SPEED
    // regardless of level, thieves walk THIEF_SPEED (they're the Haste class). With thieves at
    // ~31% of the population that puts the population mean at ~110, inside the 100-120 band real
    // players occupy. Only speed gear / buffs (stacked on top via getTotalMoveSpeedStat() - 100)
    // can push a bot past this.
    static final int NORMAL_SPEED = 105;                       // every non-thief, all levels
    static final int THIEF_SPEED = 120;                        // thieves: the Haste class
    static final int HASTE_MAX_JUMP = MAX_EFFECTIVE_JUMP_STAT; // 123
    static final int HASTE_SELF_MAX_LEVEL = 55;                // haste-thief at/above this = max speed+jump
    static final int PARTY_HASTE_THIEF_LEVEL = 60;             // a haste-thief party member at/above this grants party Haste
    static final int YOUNG_THIEF_JUMP = 115;

    BotMovementProfile {
        totalSpeedStat = bucketStat(totalSpeedStat);
        totalJumpStat = bucketStat(totalJumpStat);
        totalSpeedStat = Math.min(totalSpeedStat, MAX_EFFECTIVE_SPEED_STAT);
        totalJumpStat = Math.min(totalJumpStat, MAX_EFFECTIVE_JUMP_STAT);
    }

    BotMovementProfile(int totalSpeedStat, int totalJumpStat) {
        this(totalSpeedStat, totalJumpStat, false);
    }

    static BotMovementProfile base() {
        return BASE;
    }

    static BotMovementProfile fromCharacter(Character character) {
        if (character == null) {
            return BASE;
        }
        if (hasForcedBaseMovementStats(character)) {
            return BASE;
        }
        // Speed is CLASS-BASED, not level-based — see the SPEED SCALE note above.
        // Jump still scales with level (and class, for thieves) so higher-level bots leap higher.
        // The baseline replaces the flat 100; any equip/real-buff speed/jump
        // (getTotal*Stat - 100) still stacks on top. Bucketed to the nearest 5 by the canonical
        // constructor, so the tiers above land on exact graph buckets.
        int level = character.getLevel();
        Job job = character.getJob();

        // Speed follows the Haste SKILL, not the class: a 1st-job thief (THIEF, 400) has not
        // learned Haste yet, so it walks at the ordinary NORMAL_SPEED like everyone else. Only
        // 2nd-job-and-up thieves (ASSASSIN/BANDIT lineages) get THIEF_SPEED.
        int speedBaseline = hasHasteSkill(job) ? THIEF_SPEED : NORMAL_SPEED;
        int jumpBaseline;
        if (isThief(job) && level >= HASTE_SELF_MAX_LEVEL) {
            jumpBaseline = HASTE_MAX_JUMP;         // capped thief: full effective Haste
        } else if (isThief(job)) {
            jumpBaseline = YOUNG_THIEF_JUMP;
        } else {
            jumpBaseline = levelJumpStat(level);
        }
        // Party Haste (immersion): a bot partied with a high-level haste-thief inherits max SPEED (not
        // jump). Pure roster logic — the thief never travels to cast it. max() so a young thief still
        // gets the full Haste speed rather than being dragged down to it.
        if (partyGrantsHaste(character)) {
            speedBaseline = Math.max(speedBaseline, THIEF_SPEED);
        }

        int totalSpeed = speedBaseline + (character.getTotalMoveSpeedStat() - BASE_TOTAL_STAT);
        int totalJump = jumpBaseline + (character.getTotalJumpStat() - BASE_TOTAL_STAT);
        return new BotMovementProfile(totalSpeed, totalJump, wearsSnowShoes(character));
    }

    // Thief lineage: THIEF(400) -> ASSASSIN(410)/BANDIT(420) -> ... -> NIGHTLORD(412)/SHADOWER(422).
    // isA covers the whole branch. Used for the JUMP baseline (any thief leaps a bit higher).
    private static boolean isThief(Job job) {
        return job != null && job.isA(Job.THIEF);
    }

    // Haste-thief lineage: Assassin→Hermit→Night Lord and Bandit→Chief Bandit→Shadower all learn
    // Haste at 2nd job (level 30+). isA covers the whole lineage and excludes first-job Rogue
    // (no Haste) — which is exactly the SPEED distinction: an unhasted 1st-job thief runs at the
    // ordinary NORMAL_SPEED, same as a warrior or mage.
    private static boolean hasHasteSkill(Job job) {
        return job != null && (job.isA(Job.ASSASSIN) || job.isA(Job.BANDIT));
    }

    // True if the bot shares an active party with an online, level-PARTY_HASTE_THIEF_LEVEL+ haste-thief —
    // the bot is treated as receiving that thief's party Haste. Roster-only; no map/proximity requirement.
    private static boolean partyGrantsHaste(Character character) {
        Party party = character.getParty();
        if (party == null) {
            return false;
        }
        for (PartyCharacter member : party.getMembers()) {
            if (member == null || member.getId() == character.getId() || !member.isOnline()) {
                continue;
            }
            if (member.getLevel() >= PARTY_HASTE_THIEF_LEVEL && isThief(member.getJob())) {
                return true;
            }
        }
        return false;
    }

    // Level -> jump % baseline (non-thief). Parallels the old speed ladder within the 100..123 jump
    // range so higher-level bots leap higher; ≤9 stays 100 so low-level bots are unchanged. Multiples
    // of STAT_BUCKET_SIZE (except the 123 cap) so they land on exact graph buckets.
    // NOTE: there is deliberately no levelSpeedStat() counterpart any more — walk speed is
    // class-based (NORMAL_SPEED / THIEF_SPEED), not level-based.
    private static int levelJumpStat(int level) {
        if (level <= 9) {
            return 100;
        }
        if (level <= 29) {
            return 105;
        }
        if (level <= 50) {
            return 110;
        }
        if (level <= 69) {
            return 115;
        }
        if (level <= 100) {
            return 120;
        }
        return HASTE_MAX_JUMP; // 123
    }

    /* Snowshoes carry WZ info/fs (e.g. 10) on the worn shoe and cancel field
     *  slipperiness client-side — the wearer gets normal walk physics on snow/ice maps. */
    private static boolean wearsSnowShoes(Character character) {
        try {
            Item shoe = character.getInventory(InventoryType.EQUIPPED).getItem((short) -7);
            if (shoe == null) {
                return false;
            }
            Map<String, Integer> stats =
                    ItemInformationProvider.getInstance().getEquipStats(shoe.getItemId());
            return stats != null && stats.getOrDefault("fs", 0) >= 1;
        } catch (Throwable t) {
            return false; // WZ/equip data unavailable (unit tests, partial mocks)
        }
    }

    private static boolean hasForcedBaseMovementStats(Character character) {
        MapleMap map = character.getMap();
        return map != null && FieldLimit.MOVEMENTSKILLS.check(map.getFieldLimit());
    }

    private static int bucketStat(int stat) {
        int clamped = Math.max(1, stat);
        if (clamped < STAT_BUCKET_SIZE) {
            return clamped;
        }
        // Nearest bucket, not floor: a 144% bot plays on the 145 graph — halves the
        // worst-case drift between real stats and the physics/graph profile.
        return (int) (Math.round(clamped / (double) STAT_BUCKET_SIZE) * STAT_BUCKET_SIZE);
    }

    double speedMultiplier() {
        return totalSpeedStat / (double) BASE_TOTAL_STAT;
    }

    double jumpMultiplier() {
        return totalJumpStat / (double) BASE_TOTAL_STAT;
    }

    double walkVelocityPxs() {
        return BotMovementManager.cfg.WALK_VEL * speedMultiplier();
    }

    double hForcePxs() {
        return BotPhysicsEngine.cfg.HFORCE_PXS * speedMultiplier();
    }

    float jumpSpeedPxs() {
        return (float) (BotPhysicsEngine.cfg.JUMP_SPEED_PXS * jumpMultiplier());
    }

    float ropeJumpSpeedPxs() {
        return (float) (BotPhysicsEngine.cfg.JUMP_ROPE_PXS * jumpMultiplier());
    }
}
