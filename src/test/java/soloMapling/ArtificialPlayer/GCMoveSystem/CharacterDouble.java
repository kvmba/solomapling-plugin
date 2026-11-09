package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.world.Party;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A real {@link Character} answering the handful of accessors a movement sim reads, with no
 * Mockito interception.
 *
 * <p>Why not {@code mock(Character.class, RETURNS_DEEP_STUBS)}: Mockito's inline mock maker
 * retransforms every class a mock touches, and {@code Character} is an 800-method class whose
 * accessors the driver reads dozens of times per tick. Two costs followed, both measured:
 * <ul>
 *   <li>each intercepted call costs ~10-16µs instead of ~50ns, so a 24k-tick sim spent ~18s inside
 *       Mockito ({@code LpqStage1ExecutionSimTest}'s perch case: 19.4s -> 1.2s);</li>
 *   <li>worse, an UNSTUBBED deep-stub accessor mints a mock of its return type - so the first
 *       {@code getPosition()} on a deep-stub Character mocks {@code java.awt.Point} for the whole
 *       JVM. Every real {@code new Point()} then pays a dispatcher lookup (measured 9ms -> 157ms
 *       per 300k constructions), which is the process-wide tax the surefire forks in pom.xml exist
 *       to dodge.</li>
 * </ul>
 *
 * <p>Answers are the constants these sims previously pinned by hand (level 40, 100/100 stats, no
 * buffs, no chair). A test that must VERIFY an interaction, or that reaches an accessor not covered
 * here, still needs a mock - this double is for driving the tick, not for asserting on the avatar.
 */
final class CharacterDouble extends Character {

    private final int id;
    private final MapleMap map;
    private final int mapId;
    private final Supplier<Point> position;
    private final Consumer<Point> positionSink;
    private final Map<InventoryType, Inventory> inventories = new EnumMap<>(InventoryType.class);

    CharacterDouble(int id, MapleMap map, Supplier<Point> position, Consumer<Point> positionSink) {
        super();
        this.id = id;
        this.map = map;
        this.mapId = map == null ? 0 : map.getId();
        this.position = position;
        this.positionSink = positionSink;
        setMap(map);
    }

    @Override public int getId() { return id; }
    @Override public int getMapId() { return mapId; }
    @Override public MapleMap getMap() { return map; }
    @Override public int getHp() { return 5000; }
    @Override public Job getJob() { return Job.BEGINNER; }
    @Override public int getLevel() { return 40; }
    @Override public int getTotalMoveSpeedStat() { return 100; }
    @Override public int getTotalJumpStat() { return 100; }
    @Override public Integer getBuffedValue(org.gms.client.BuffStat stat) { return null; }
    @Override public int getChair() { return 0; }
    @Override public Party getParty() { return null; }
    @Override public org.gms.scripting.event.EventInstanceManager getEventInstance() { return null; }
    @Override public String getName() { return "test-bot"; }

    @Override public Point getPosition() {
        return position != null ? position.get() : super.getPosition();
    }

    @Override public void setPosition(Point p) {
        if (positionSink != null) {
            positionSink.accept(p);
        } else {
            super.setPosition(p);
        }
    }

    /**
     * A plain (non-deep) stub: {@code BotAttack.resolveEquippedWeaponType} dereferences the equipped
     * inventory without a null check, and an empty stub is the no-weapon path.
     */
    @Override public Inventory getInventory(InventoryType type) {
        return inventories.computeIfAbsent(type, ignored -> org.mockito.Mockito.mock(Inventory.class));
    }
}
