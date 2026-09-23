package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Summon;
import org.gms.server.maps.SummonMovementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Engine-facing half of the bot-summon feature: decides whether a bot gets a summon, spawns the
 * host {@link Summon} entity and registers it with {@link BotSummonFollower}. The decision half is
 * the join of {@link BotSummonTable} (which job owns which summon) and {@link BotSummonConfig}.
 *
 * <p>No skill is taught to the bot and no host buff stat is set - the summon is an entity we own,
 * spawned directly through {@code MapleMap.spawnSummon}. That keeps the host's own SUMMON/PUPPET
 * buff lifecycle (and its {@code reapplyLocalStats} foot-guns) entirely out of the picture.</p>
 */
public final class BotSummonController {

    private static final Logger log = LoggerFactory.getLogger(BotSummonController.class);

    private BotSummonController() {}

    /** Give {@code bot} its summon if its job owns one and the roll says so. Idempotent. */
    public static void grantForBot(Character bot, BotSummonConfig config) {
        if (bot == null || bot.getMap() == null) {
            return; // mapless bots (console bot) can't host a summon
        }
        if (BotSummonFollower.isTracked(bot.getId())) {
            return; // already has one (companion reload / re-grant)
        }
        if (bot.getLevel() < config.minLevel()) {
            return;
        }
        Integer skillId = chooseSummon(bot.getJob() == null ? -1 : bot.getJob().getId());
        if (skillId == null) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= config.spawnChance()) {
            return;
        }
        spawn(bot, skillId);
    }

    /**
     * The summon this bot's job should show: the most advanced one it owns, preferring an attacking
     * summon over a non-attacking decoy (a Ranger owns both Silver Hawk and Puppet - the hawk is the
     * one worth showing), and among equals the highest skill id (the later advancement).
     */
    static Integer chooseSummon(int jobId) {
        Integer best = null;
        BotSummonTable.Spec bestSpec = null;
        for (int skillId : BotSummonTable.summonsForJobId(jobId)) {
            BotSummonTable.Spec spec = BotSummonTable.forSkill(skillId);
            if (spec == null) {
                continue;
            }
            if (best == null
                    || (spec.attacks() && !bestSpec.attacks())
                    || (spec.attacks() == bestSpec.attacks() && skillId > best)) {
                best = skillId;
                bestSpec = spec;
            }
        }
        return best;
    }

    private static void spawn(Character bot, int skillId) {
        BotSummonTable.Spec spec = BotSummonTable.forSkill(skillId);
        if (spec == null) {
            return;
        }
        // The host's Summon constructor requires the owner to KNOW the skill (it reads the level
        // for the spawn packet and throws at level 0). Teach it the same way BotEnergyCharge /
        // BotMount.learnRiderSkill do - bots are synthetic and spend no SP. The granted level is
        // clamped to the skill's own max; the client only needs a valid level to render.
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return; // not in this server's Skill.wz (a client crash for observers if we sent it)
        }
        if (bot.getSkillLevel(skill) < 1) {
            bot.changeSkillLevel(skill, (byte) skill.getMaxLevel(), skill.getMaxLevel(), -1);
        }
        if (bot.getSkillLevel(skill) < 1) {
            return; // the host refused the grant; never construct without a level
        }
        SummonMovementType moveType = switch (spec.move()) {
            case STATIONARY -> SummonMovementType.STATIONARY;
            case CIRCLE -> SummonMovementType.CIRCLE_FOLLOW;
            case FOLLOW -> SummonMovementType.FOLLOW;
        };
        try {
            MapleMap map = bot.getMap();
            Point pos = spawnPosition(bot, map);
            Summon summon = new Summon(bot, skillId, pos, moveType);
            summon.setStance(stanceFor(spec, bot));
            map.spawnSummon(summon);
            if (summon.isPuppet()) {
                map.addPlayerPuppet(bot); // draw mob aggro onto the decoy, like a real puppet
            }
            BotSummonFollower.register(bot.getId(), spec, summon, map);
        } catch (Throwable t) {
            log.warn("summon spawn failed cid={} skill={}: {}", bot.getId(), skillId, t.toString());
        }
    }

    /** Where the entity appears: at the owner's feet for a ground summon, just above for a flyer. */
    private static Point spawnPosition(Character bot, MapleMap map) {
        Point owner = bot.getPosition();
        Foothold ground = GCMovement.footholdBelow(map, owner.x, owner.y);
        int y = ground != null ? ground.calculateFooting(owner.x) : owner.y;
        return new Point(owner.x, y);
    }

    /** Spawn nMoveAction: a stationary summon uses STAND (0) in the CSummoned action table. */
    private static int stanceFor(BotSummonTable.Spec spec, Character bot) {
        return 0; // STAND; the follower switches a moving summon to MOVE/FLY as it repositions
    }

    /** Tear down a bot's summons (called when it despawns or becomes an FM keeper). */
    public static void removeSummons(Character bot) {
        if (bot == null) {
            return;
        }
        try {
            BotSummonFollower.despawnAll(bot);
        } catch (Throwable t) {
            log.warn("summon remove failed cid={}: {}", bot.getId(), t.toString());
        }
    }

    /** Whether this bot is eligible for a summon at all (used by diagnostics). */
    static boolean eligible(Character bot, BotSummonConfig config) {
        return bot != null && bot.getLevel() >= config.minLevel()
                && !BotSummonTable.summonsForJobId(bot.getJob() == null ? -1 : bot.getJob().getId()).isEmpty();
    }
}
