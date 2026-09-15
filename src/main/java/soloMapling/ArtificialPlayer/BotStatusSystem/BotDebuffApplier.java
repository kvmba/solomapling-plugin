package soloMapling.ArtificialPlayer.BotStatusSystem;

import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.server.life.MobSkill;
import org.gms.server.life.MobSkillFactory;
import org.gms.server.life.MobSkillId;
import org.gms.server.life.MobSkillType;
import org.gms.server.life.Monster;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Decides, per bot tick, whether a nearby hostile mob lands a disease on the bot, and if so applies it
 * through {@link BotDebuffState}.
 *
 * Why the plugin, not the engine: the engine only fires a mob skill / disease when the mob's controller
 * client sends MOVE_LIFE / TAKE_DAMAGE. A bot is excluded from mob control
 * (Monster.getNextControllerCandidate) and its headless client sends nothing, so no disease ever
 * reaches it. This class re-derives that trigger from the mob's own skill list, using the engine's WZ
 * data (Monster.getSkills / MobSkillFactory) and its own hit roll, then hands the effect to the bot's
 * BotDebuffState.
 *
 * Only the diseases a bot visibly reacts to are considered (STUN / SEDUCE / SEAL / SLOW / WEAKEN /
 * DARKNESS / POISON) - the same set the engine's MobSkill.applyEffect turns into a Character disease.
 * Detection is folded into the movement tick's existing nearby-mob scan (BotContactDamage), which
 * already decides which mobs are in touch range, so this adds no scan of its own and stays behind the
 * same LOD gate. The caller passes the touch verdict; this class owns only the roll + bookkeeping.
 *
 * Threading: called on a bot's movement tick only; the per-bot cooldown map is shared across bots on
 * different threads, hence concurrent.
 */
public final class BotDebuffApplier {

    private BotDebuffApplier() {
    }

    // Per-bot per-disease cooldown, so a mob parked on a bot re-rolls on the WZ interval instead of
    // every 50 ms tick. Keyed botId -> (disease -> next-eligible epoch ms). Released on despawn.
    private static final Map<Integer, Map<Disease, Long>> nextByBot = new ConcurrentHashMap<>();

    // Fallback interval when the WZ skill carries none, so a disease cannot be re-rolled every tick.
    private static final long DEFAULT_COOLDOWN_MS = 5_000L;

    /**
     * Rolls this mob's diseases against the bot and applies any that land. Call ONLY for a mob the
     * caller has already found in touch range of the bot (so the caller's mob scan is the only one).
     */
    public static void consider(Character bot, Monster mob) {
        if (bot == null || mob == null || !mob.hasAnySkill()) {
            return;
        }
        BotDebuffState state = BotDebuffState.of(bot);
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (MobSkillId id : mob.getSkills()) {
            Disease disease = diseaseFor(id.type());
            if (disease == null || cooldownActive(bot.getId(), disease, now)) {
                continue;
            }
            MobSkill skill = skillFor(id);
            if (skill == null) {
                continue;
            }
            if (!skill.makeChanceResult()) {
                continue; // missed this roll - the cooldown below lets it try again later
            }
            // Stamp the cooldown whether or not the apply stuck (a second disease is refused by the
            // two-disease cap), so the same skill is not re-rolled every tick.
            markCooldown(bot.getId(), disease, now + cooldownMs(skill));
            state.apply(disease, skill);
        }
    }

    // ── Mapping: engine types the plugin models ──────────────────────────────────

    /** Package-visible so the type->disease mapping is unit-testable without WZ data. */
    static Disease diseaseFor(MobSkillType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case STUN -> Disease.STUN;
            case SEDUCE -> Disease.SEDUCE;
            case SEAL -> Disease.SEAL;
            case SLOW -> Disease.SLOW;
            case WEAKNESS -> Disease.WEAKEN;
            case DARKNESS -> Disease.DARKNESS;
            case POISON -> Disease.POISON;
            default -> null; // buffs / immunities / summons: not a bot-affecting disease here
        };
    }

    /** Resolves the concrete WZ skill. The id always resolves for a skill the mob actually has. */
    private static MobSkill skillFor(MobSkillId id) {
        try {
            return MobSkillFactory.getMobSkillOrThrow(id.type(), id.level());
        } catch (RuntimeException e) {
            return null; // malformed skill data - skip rather than kill the tick
        }
    }

    // ── Cooldown bookkeeping ─────────────────────────────────────────────────────

    private static boolean cooldownActive(int botId, Disease disease, long now) {
        Map<Disease, Long> m = nextByBot.get(botId);
        if (m == null) {
            return false;
        }
        Long next = m.get(disease);
        return next != null && next > now;
    }

    private static void markCooldown(int botId, Disease disease, long atMs) {
        nextByBot.computeIfAbsent(botId, k -> new ConcurrentHashMap<>()).put(disease, atMs);
    }

    private static long cooldownMs(MobSkill skill) {
        long cool = skill.getCoolTime();
        return cool > 0 ? cool : DEFAULT_COOLDOWN_MS;
    }

    /** Release a despawned bot's cooldowns so the maps don't grow unbounded. */
    public static void clearBot(int botId) {
        nextByBot.remove(botId);
    }
}
