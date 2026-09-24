package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Summon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Engine-facing half of the bot-summon feature: decides whether a bot gets a summon, teaches the
 * skill the host {@link Summon} requires, spawns the entity through
 * {@link BotSummonFollower#spawnEntity} and registers it. The decision half is the join of
 * {@link BotSummonTable} (which job owns which summon) and {@link BotSummonConfig}.
 *
 * <p><b>Ambient bots only.</b> A summon needs the summon skill learned, and a persistent companion
 * persists its skills/SP - teaching it a skill its build never allocated would corrupt the build, so
 * companions are never granted a summon (see {@code HostCompanionRuntimeAdapter}).</p>
 *
 * <p>No host buff stat is ever set - the summon is an entity we own, spawned directly through
 * {@code MapleMap.spawnSummon}. That keeps the host's own SUMMON/PUPPET buff lifecycle (and its
 * {@code reapplyLocalStats} foot-guns) entirely out of the picture.</p>
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
            return; // already has one (re-grant)
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
     * summon over a non-attacking one (e.g. a job owning both a hawk and a support summon shows the
     * hawk), and among equals the highest skill id (the later advancement).
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
        // The host's Summon constructor requires the owner to KNOW the skill (it reads the level for
        // the spawn packet and throws at level 0). Teach it the same way BotMount.learnRiderSkill
        // does - ambient bots are synthetic and spend no SP. Clamped to the skill's own max; the
        // client only needs a valid level to render.
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
        try {
            MapleMap map = bot.getMap();
            Summon summon = BotSummonFollower.spawnEntity(bot, skillId, spec, map);
            if (summon != null) {
                BotSummonFollower.register(bot.getId(), spec, summon, map);
            }
        } catch (Throwable t) {
            log.warn("summon spawn failed cid={} skill={}: {}", bot.getId(), skillId, t.toString());
        }
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
}
