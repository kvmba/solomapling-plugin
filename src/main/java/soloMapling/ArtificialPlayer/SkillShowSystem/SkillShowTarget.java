package soloMapling.ArtificialPlayer.SkillShowSystem;

import org.gms.server.life.LifeFactory;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 表演木桩：技能观测表演期间在 bot 脚下放置的超厚血量怪，让每类技能都有
 * 真实的可作用目标 —— 毒雾/影网要有框内怪才出招，能量获得要被触碰才充能，
 * 终极攻击要主挥击落地才掷 prop，影子貔貅要有钱包目标。
 *
 * <p>选 9300021（黑暗勇猛石巨人）：WZ 自带 exp=0 / maxHP≈10^7 / PADamage、
 * 全元素抗性（elemAttr I2F2L2H2S2）、推怪距离 99999 推不动。木桩身上不再
 * 由本类做禁杀处理：每步表演伤害按 BotDamageModel 一行几百到几千，7 位血量
 * 一场表演打不完；exp=0 使误杀无收益；dropsDisabled 关掉掉落。</p>
 *
 * <p>生命期与表演 bot 绑定：登场生成、退场移除（mapId + oid 双登记）。</p>
 */
public final class SkillShowTarget {

    private SkillShowTarget() {
    }

    /** 9300021 黑暗勇猛石巨人 —— 服务器木桩惯用 id（宿主 MobId 表没有它的常量）。 */
    static final int TARGET_MOB_ID = 9300021;

    /** mapId -> 本图当前的木桩。每职业一个，重登覆盖，防泄漏。 */
    private static final Map<Integer, TargetRef> TARGET_BY_MAP = new ConcurrentHashMap<>();

    private record TargetRef(MapleMap map, int oid) {
    }

    /**
     * 在 {@code at} 生成一只表演木桩并登记为本图的当前木桩。
     * 返回 false 表示 WZ 缺这只怪（照旧表演，条件技静默跳过，不阻塞）。
     */
    public static boolean spawn(Point at, MapleMap map, int botId) {
        Monster mob = LifeFactory.getMonster(TARGET_MOB_ID);
        if (mob == null) {
            return false;
        }
        // 木桩不掉落；exp=0 / 抗性 / 推不动由 WZ 行自带。
        mob.disableDrops();
        mob.setPosition(new Point(at.x, at.y + 5));
        map.spawnMonster(mob);
        TargetRef prev = TARGET_BY_MAP.put(map.getId(), new TargetRef(map, mob.getObjectId()));
        if (prev != null) {
            remove(prev); // 上一场的木桩忘收时在此兜底
        }
        return true;
    }

    /** 收掉本图当前的木桩（无登记时 no-op）。 */
    public static void remove(int mapId) {
        TargetRef ref = TARGET_BY_MAP.remove(mapId);
        if (ref != null) {
            remove(ref);
        }
    }

    /** 本图当前的木桩；已被 GM 手杀时清登记并返回 null。 */
    public static Monster find(int mapId) {
        TargetRef ref = TARGET_BY_MAP.get(mapId);
        if (ref == null) {
            return null;
        }
        MapleMap map = ref.map();
        if (map == null || !(map.getMapObject(ref.oid()) instanceof Monster mob)) {
            TARGET_BY_MAP.remove(mapId, ref);
            return null;
        }
        return mob;
    }

    /** 收掉并广播死亡包（GM 面前不留透明残骸）。killMonster(chr=null) 不走掉落/EXP。 */
    private static void remove(TargetRef ref) {
        try {
            MapleMap map = ref.map();
            if (map != null && map.getMapObject(ref.oid()) instanceof Monster mob) {
                mob.setHpZero();
                map.killMonster(mob, null, false);
            }
        } catch (Throwable t) {
            System.err.println("[SkillShowTarget] remove failed: " + t);
        }
    }
}
