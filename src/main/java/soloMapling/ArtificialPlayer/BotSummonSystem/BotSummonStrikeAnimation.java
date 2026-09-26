package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.DataType;
import org.gms.provider.wz.WZFiles;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long a summon's own strike animation runs, read from {@code Skill.wz} at
 * {@code <job>.img/skill/<skillId>/summon/attack1}.
 *
 * <p><b>Why read the WZ instead of a constant.</b> A real client will not start the summon's next
 * attack until the current swing has played out, so the animation length is the floor under the
 * whole strike cadence - and it is far from uniform: the v83 rows run 600ms (Summon Dragon) to
 * 2280ms (Ifrit). One constant would either gore the fast summons or make the slow ones attack
 * mid-swing. The host exposes no accessor for this node ({@code Skill.getAnimationTime()} sums the
 * skill's root {@code effect} node - the CAST flourish, not the summon's {@code attack1} frames),
 * so the plugin reads the same WZ the host loaded, exactly as {@code MobHitboxIndex} does for mob
 * bodies.</p>
 *
 * <p>Only the direct {@code canvas} children of {@code attack1} are counted: their {@code delay}
 * ints are the frame timings. The {@code info} sibling (a range box and the hit-effect frames) is
 * deliberately skipped - folding its delays in would roughly double every reading.</p>
 */
final class BotSummonStrikeAnimation {

    /**
     * Fallback when the node cannot be read at all (no Skill.wz, an unregistered skill, a
     * malformed tree). A flat 1000ms sits inside the real 600..2280ms spread, so a bot whose WZ is
     * unreadable still strikes at a believable pace instead of never.
     */
    private static final int DEFAULT_ANIM_MS = 1000;

    // Skill id -> its attack1 animation length in ms. Memoized: the WZ tree is immutable once
    // loaded, so one lookup per summon skill for the life of the process.
    private static final Map<Integer, Integer> CACHE = new ConcurrentHashMap<>();

    private BotSummonStrikeAnimation() {}

    /** The summon's {@code attack1} swing length in ms, or {@link #DEFAULT_ANIM_MS} when unreadable. */
    static int animationMs(int skillId) {
        // The factory hands back the one process-wide provider for Skill.wz (it is synchronized
        // and memoized itself), and CACHE means this is reached at most once per skill id.
        return CACHE.computeIfAbsent(skillId,
                id -> read(DataProviderFactory.getDataProvider(WZFiles.SKILL), id));
    }

    /**
     * The swing length of one skill read off {@code source}, or {@link #DEFAULT_ANIM_MS} when the
     * node is missing. Package-private so a test can drive it with a provider built straight from
     * the host's WZ tree.
     */
    static int read(DataProvider source, int skillId) {
        try {
            if (source == null) {
                return DEFAULT_ANIM_MS;
            }
            // A skill's data lives in the file named after its job prefix, zero-padded to 3 (the
            // host's own loadAllSkills walks the same files): 3111005 -> 311.img.
            String file = String.format("%03d.img", skillId / 10000);
            Data root = source.getData(file);
            if (root == null) {
                return DEFAULT_ANIM_MS;
            }
            Data attack = root.getChildByPath("skill/" + skillId + "/summon/attack1");
            if (attack == null) {
                return DEFAULT_ANIM_MS;
            }
            int total = 0;
            for (Data frame : attack) {
                if (frame.getType() != DataType.CANVAS) {
                    continue;
                }
                Data delay = frame.getChildByPath("delay");
                if (delay != null) {
                    total += ((Number) delay.getData()).intValue();
                }
            }
            return total > 0 ? total : DEFAULT_ANIM_MS;
        } catch (RuntimeException e) {
            return DEFAULT_ANIM_MS;
        }
    }


}
