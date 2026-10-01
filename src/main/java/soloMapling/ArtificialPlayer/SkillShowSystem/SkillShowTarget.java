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
 * <p>选 9300093（冒牌泰勒斯，宿主常量 MobId.TYLUS）：WZ 自带 PADamage=0 /
 * MADamage=0 / pushed=0 / exp=0 / maxHP=10 万 / dropItemPeriod=10s。关键点：</p>
 * <ul>
 *   <li><b>PADamage=0</b>：接触伤害扫描 {@code rollMobDamage} 按
 *       {@code getPADamage() × 0.5} 结算，0 → Math.max(1,·) 兜底 1 点/次，
 *       表演 bot 绝不会被自己的木桩打死（黑暗勇猛石巨人 9300021 的
 *       PADamage=1999 会，那正是它不能用的原因）。</li>
 *   <li><b>无 elemAttr</b>：元素全部 NORMAL，毒雾的 POISON 状态真实生效、
 *       跳出毒伤数字。</li>
 *   <li><b>dropItemPeriod=10s</b>：宿主 spawnMonster 对 TYLUS 特判启动定时
 *       掉落（dropFromFriendlyMonster），木桩的 dropsDisabled 使该定时器每
 *       次 tick 直接 return —— 但定时器句柄本身仍会挂到木桩移除为止。可接受：
 *       每职业一只、分钟级生命期，与 HPQ/护卫任务模式一致。</li>
 * </ul>
 *
 * <p>生命期与表演 bot 绑定：登场生成、退场移除（mapId + oid 双登记）。</p>
 */
public final class SkillShowTarget {

    private SkillShowTarget() {
    }

    /** 9300093 冒牌泰勒斯 —— 无攻击力 / 推不动 / exp=0，且在宿主 MobId 表有常量。 */
    static final int TARGET_MOB_ID = org.gms.constants.id.MobId.TYLUS;

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
