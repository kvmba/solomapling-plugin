package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
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
        // the spawn packet and throws at level 0). Teach it the way BotMount.learnRiderSkill does -
        // ambient bots are synthetic and spend no SP - but at the level the bot's character level
        // plausibly earned, not the skill's max: the summon's whole WZ row (its stun prop, its
        // Bahamut mobCount, its attack power) is read at that level, so a fresh 3rd jobber's hawk
        // must read like a level-1 hawk, not a maxed one. The same call keeps a live summon growing
        // as its owner levels (see BotSummonFollower.ensureSkillLevel).
        if (BotSummonFollower.ensureSkillLevel(bot, skillId) < 1) {
            return; // the bot has not reached the tier that owns it, or Skill.wz has no such skill
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
