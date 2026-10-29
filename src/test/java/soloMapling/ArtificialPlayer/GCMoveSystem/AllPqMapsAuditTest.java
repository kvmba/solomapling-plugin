package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.mockito.Mockito.*;

/**
 * FULL per-map audit of every PQ stage map (all quest families), loaded from the REAL WZ
 * through the production loader. Two passes per map:
 *   A. AS-IS (all WZ ropes): bake + rope-entry links;
 *   B. USABLE-ROPES-ONLY clone (uf=0 decorative ropes removed, which the WZ marks
 *      unclimbable and the engine's Rope record ignores): same audit. Differences
 *      attribute failures to decorative-rope noise.
 * The per-map fallback-launch sweep is report-only and opt-in
 * ({@code -Dpq.audit.fallbackScan=true}) - see FALLBACK_SCAN_ENABLED.
 * Report goes to /tmp/pq_audit_report.txt.
 */
public class AllPqMapsAuditTest {
    @BeforeAll
    static void stub() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }
    static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v); d.setConfigClazz("java.lang.Long");
        return d;
    }

    private static List<Integer> maps() throws Exception {
        // The audit's input used to be ONLY the baked /tmp/all_pq_maps.txt artifact, so on any
        // machine that never baked it the whole audit silently never ran — and a PQ map added to
        // the plugin afterwards could never enter the list. The list is now DERIVED from the two
        // sources that define the plugin's PQ content, both of which always ship:
        //   1. every numeric map id the plugin's own PQ data classes declare (BotTypes/**), and
        //   2. every numeric map id named by the HOST's own PQ event scripts - the scripts the
        //      plugin's quest families run on, found beside the wz tree.
        // /tmp/all_pq_maps.txt stays supported as an extra source for the old workflow, but the
        // audit no longer depends on it. Every id is WZ-validated before it is audited.
        TreeSet<Integer> ids = new TreeSet<>();
        ids.addAll(repoDeclaredPqMaps());
        ids.addAll(hostPqScriptMaps());
        Path baked = Path.of("/tmp/all_pq_maps.txt");
        if (Files.isRegularFile(baked)) {
            for (String line : Files.readAllLines(baked)) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    try {
                        ids.add(Integer.parseInt(t));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /** The host's PQ event scripts, i.e. the runs this plugin's quest bots actually play. */
    private static final String[] PQ_EVENTS = {
            "AmoriaPQ", "BossRushPQ", "EllinPQ", "HenesysPQ", "HorntailPQ", "KerningPQ",
            "LudiMazePQ", "LudiPQ", "MagatiaPQ_A", "MagatiaPQ_Z", "OrbisPQ", "PiratePQ", "ZakumPQ",
    };

    /**
     * The informational fallback-launch sweep probes every (rope, ground) region pair x 13 walk
     * hops with the production grab simulator. A warm JFR profile attributed 99.8% of this
     * audit's 563s runtime to the sweep, while it gates NOTHING: its fbMiss column is report-only.
     * It stays available but opt-in, so the default suite pays only for the gated bake/climb-link
     * verdicts. Enable with {@code -Dpq.audit.fallbackScan=true} when a rope-approach diagnosis
     * needs the sweep.
     */
    private static final boolean FALLBACK_SCAN_ENABLED =
            Boolean.getBoolean("pq.audit.fallbackScan");

    /**
     * Every map id the plugin's own PQ data classes declare, read from the sources so the list
     * cannot drift: a numeric {@code public static final int} in the quest data classes is an id.
     * Scanning (not a class list) is deliberate - adding a quest must not mean editing this audit.
     */
    private static List<Integer> repoDeclaredPqMaps() throws Exception {
        List<Integer> out = new ArrayList<>();
        Path root = Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes");
        if (!Files.isDirectory(root)) {
            return out; // packaged/other cwd - the host-script + baked sources still apply
        }
        java.util.regex.Pattern decl =
                java.util.regex.Pattern.compile("public\\s+static\\s+final\\s+int\\s+\\w+\\s*=\\s*(\\d{4,9})\\s*;");
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                java.util.regex.Matcher m = decl.matcher(Files.readString(p));
                while (m.find()) {
                    out.add(Integer.parseInt(m.group(1)));
                }
            }
        }
        return out;
    }

    /**
     * Every numeric map id named by the host's own PQ event scripts. The scripts are the authority
     * for "which maps does a run touch" - they warp by id, and they list the per-stage sub-rooms
     * the data classes only parameterise. Scoped to the plugin's own quest families, never the
     * whole event tree: an unrelated event's maps are not this audit's contract. Located through
     * the wz tree's sibling; absent (packaged runs) contributes nothing.
     */
    private static List<Integer> hostPqScriptMaps() throws Exception {
        List<Integer> out = new ArrayList<>();
        // Resolve the wz symlink for REAL before stepping to its parent: normalize() would collapse
        // "wz/.." textually and point at the project root instead of the host checkout that the
        // symlink targets. Absent (packaged runs) simply contributes nothing.
        Path scripts = null;
        try {
            Path wz = Path.of("wz").toRealPath();
            Path parent = wz.getParent();
            if (parent != null) {
                Path candidate = parent.resolve("scripts").resolve("event");
                if (Files.isDirectory(candidate)) {
                    scripts = candidate;
                }
            }
        } catch (IOException ignored) {
            // no wz tree beside this checkout - the repo-declared source still applies
        }
        if (scripts == null) {
            return out;
        }
        java.util.regex.Pattern id = java.util.regex.Pattern.compile("\\b(\\d{9})\\b");
        for (String event : PQ_EVENTS) {
            Path js = scripts.resolve(event + ".js");
            if (!Files.isRegularFile(js)) {
                continue;
            }
            java.util.regex.Matcher m = id.matcher(Files.readString(js));
            while (m.find()) {
                out.add(Integer.parseInt(m.group(1)));
            }
        }
        return out;
    }

    @Test
    void auditEveryPqStageMap() throws Exception {
        // No skip is possible any more: the map list is derived from sources that ship with the
        // audit, so this test either runs a real audit or fails. (The only machine dependency is
        // Map.wz itself, which every other WZ-backed test in this package also requires.)
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                        .getData("Map/Map9/922010100.img") != null,
                "Map.wz not resolvable from this working directory");
        List<Integer> mapIds = maps();
        // The plugin's PQ families declare well over 60 map ids and their host scripts add the
        // per-stage sub-rooms on top; a running count near the repo-only figure means a source
        // silently vanished (which is exactly how the fixed bug slipped through: `wz/..` was
        // normalised textually, so the script source contributed nothing while the audit still
        // looked green). The floor is deliberately between the two.
        org.junit.jupiter.api.Assertions.assertTrue(mapIds.size() >= 150,
                "PQ map list looks degenerate: " + mapIds.size() + " ids - a map-list source failed");
        StringBuilder report = new StringBuilder();
        int bakeFail = 0, linkFail = 0, audited = 0;
        for (int mapId : mapIds) {
            // Only audit ids that really are maps: the scanned sources also carry item/mob ids.
            try {
                if (org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                        .getData(BotNavigationMapLoader.getMapName(mapId)) == null) {
                    continue;
                }
            } catch (Throwable t) {
                continue;
            }
            String line = audit(mapId);
            report.append(line).append('\n');
            audited++;
            if (line.contains("BAKE-FAIL")) bakeFail++;
            // The uf=0 decorative-rope LINK-FAILs show up in the AS-IS pass by design (the
            // engine treats them as climbable; the USABLE pass is the truth). Only the
            // usable pass's verdict gates.
            if (line.contains("usable LINK-FAIL")) linkFail++;
        }
        Files.writeString(Path.of("/tmp/pq_audit_report.txt"), report.toString());
        System.out.println("[audit] maps=" + audited + " bakeFail=" + bakeFail
                + " linkFail=" + linkFail
                + " fallbackScan=" + (FALLBACK_SCAN_ENABLED ? "on" : "off"));
        // Assertions only on the classes that MUST be clean: bake + real climb links.
        // The fallback scan is opt-in (FALLBACK_SCAN_ENABLED): when enabled it is reported
        // per-map (informational) — a ground row far from any rope legitimately has no launch
        // and the walk layer handles approach, so a human can eyeball each one.
        org.junit.jupiter.api.Assertions.assertTrue(audited >= 60,
                "audited too few maps (" + audited + ") - check the map-list sources");
        org.junit.jupiter.api.Assertions.assertEquals(0, bakeFail, "bake failures - see /tmp/pq_audit_report.txt");
        org.junit.jupiter.api.Assertions.assertEquals(0, linkFail, "climb-link failures - see /tmp/pq_audit_report.txt");
    }

    private String audit(int mapId) {
        try {
            MapleMap raw = BotNavigationMapLoader.loadMapGeometry(mapId);
            if (raw.getFootholds() == null || raw.getFootholds().getAllFootholds().isEmpty()) {
                return mapId + " OK(no-footholds) ropes=" + raw.getRopes().size();
            }
            if (!hasDecorativeRopes(mapId)) {
                // No uf=0 rope: pass B's rope set is identical, so a re-keyed clone would re-bake
                // the same geometry for nothing. Report both verdicts from the one bake (the
                // second is a cache hit) so the usable-link column and its gate stay intact.
                int ropes = raw.getRopes().size();
                return mapId + " ropes=" + ropes + "/" + ropes
                        + " | " + auditMap(raw, mapId + " as-is")
                        + " | " + auditMap(raw, mapId + " usable");
            }
            // clone with only usable ropes (uf=0 decorative removed) for pass B
            MapleMap usable = cloneMapWithUsableRopes(raw, mapId);
            // Re-key the clone so the graph cache does not hand back the raw map's bake. The id
            // must be ONE THAT CANNOT COLLIDE WITH A SHIPPED MAP: this used to be `mapId + 5`,
            // which silently overwrote the real cache entry of any shipped map whose id is 5
            // above another (103000800+5=103000805, 922010400+5=922010405, 922010500+5=922010505,
            // 922010501+5=922010506, 240050100+5=240050105, 280011000/001+5=280011005/006) — those
            // real maps then loaded this clone's foreign geometry. A negative id cannot be a
            // shipped map, and each clone still gets its own cache key.
            usable = reKey(usable, -mapId);
            int usableRopes = usable.getRopes().size();
            String a = auditMap(raw, mapId + " as-is");
            String b = auditMap(usable, mapId + " usable");
            return mapId + " ropes=" + raw.getRopes().size() + "/" + usableRopes + " | " + a + " | " + b;
        } catch (Throwable t) {
            return mapId + " BAKE-FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** True when the WZ marks at least one rope uf=0 (decorative), i.e. pass B differs at all. */
    private boolean hasDecorativeRopes(int mapId) {
        org.gms.provider.DataProvider mapSource =
                org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
        org.gms.provider.Data mapData = mapSource.getData(
                "Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img");
        if (mapData == null) {
            return true; // unknown: keep the full two-pass path rather than skip a real check
        }
        org.gms.provider.Data ropeData = mapData.getChildByPath("ladderRope");
        if (ropeData == null) {
            return false;
        }
        for (org.gms.provider.Data rope : ropeData) {
            if (org.gms.provider.DataTool.getInt(rope.getChildByPath("uf"), 1) == 0) {
                return true;
            }
        }
        return false;
    }

    private MapleMap reKey(MapleMap src, int newId) throws Exception {
        MapleMap map = new MapleMap(newId, 0, 0, 920010000, 0.0f);
        map.setFootholds(src.getFootholds());
        java.awt.Rectangle vr = src.getMapArea();
        if (vr != null) {
            map.setMapLineBoundings(vr.y, vr.y + vr.height, vr.x, vr.x + vr.width);
        }
        map.setFieldLimit(src.getFieldLimit());
        for (Rope rope : src.getRopes()) {
            map.addRope(rope);
        }
        return map;
    }

    private MapleMap cloneMapWithUsableRopes(MapleMap src, int mapId) throws Exception {
        // read the WZ again, keep only uf=1 ropes (same as the loader, plus the uf filter)
        org.gms.provider.DataProvider mapSource =
                org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
        org.gms.provider.Data mapData = mapSource.getData(
                "Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img");
        MapleMap map = new MapleMap(mapId, 0, 0, 920010000, 0.0f);
        FootholdTree tree = new FootholdTree(new Point(-265, -4000), new Point(265, 600));
        // copy footholds + bounds from src
        map.setFootholds(src.getFootholds());
        java.awt.Rectangle vr = src.getMapArea();
        if (vr != null) {
            map.setMapLineBoundings(vr.y, vr.y + vr.height, vr.x, vr.x + vr.width);
        }
        map.setFieldLimit(src.getFieldLimit());
        org.gms.provider.Data ropeData = mapData.getChildByPath("ladderRope");
        if (ropeData != null) {
            for (org.gms.provider.Data rope : ropeData) {
                int uf = org.gms.provider.DataTool.getInt(rope.getChildByPath("uf"), 1);
                if (uf == 0) continue; // decorative
                int x = org.gms.provider.DataTool.getInt(rope.getChildByPath("x"));
                int y1 = org.gms.provider.DataTool.getInt(rope.getChildByPath("y1"));
                int y2 = org.gms.provider.DataTool.getInt(rope.getChildByPath("y2"));
                boolean ladder = org.gms.provider.DataTool.getInt(rope.getChildByPath("l"), 0) == 1;
                map.addRope(new Rope(x, y1, y2, ladder));
            }
        }
        return map;
    }

    private String auditMap(MapleMap map, String tag) {
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        java.util.Set<Integer> ropeIds = new java.util.HashSet<>();
        for (BotNavigationGraph.Region r : g.regions) {
            if (r.isRopeRegion) ropeIds.add(r.id);
        }
        java.util.Set<Integer> hasEntry = new java.util.HashSet<>();
        for (java.util.Map.Entry<Integer, List<BotNavigationGraph.Edge>> en : g.outgoingByRegionId.entrySet()) {
            for (BotNavigationGraph.Edge e : en.getValue()) {
                if (ropeIds.contains(e.toRegionId)) hasEntry.add(e.toRegionId);
            }
        }
        int ropeRegions = ropeIds.size(), ropeNoEntry = 0;
        for (BotNavigationGraph.Region r : g.regions) {
            if (!r.isRopeRegion) continue;
            boolean physicallyEnterable = false;
            for (BotNavigationGraph.Region ground : g.regions) {
                if (ground.isRopeRegion) continue;
                boolean yOverlap = r.minY <= ground.maxY + 90 && r.maxY >= ground.minY - 90;
                boolean xNear = Math.abs(r.minX - ground.minX) <= 400 || Math.abs(r.minX - ground.maxX) <= 400
                        || (r.minX >= ground.minX && r.minX <= ground.maxX);
                if (yOverlap && xNear) { physicallyEnterable = true; break; }
            }
            if (physicallyEnterable && !hasEntry.contains(r.id)) ropeNoEntry++;
        }
        int fallbackScans = 0, fallbackMiss = 0;
        BotMovementProfile profile = BotMovementProfile.base();
        // Opt-in only: see FALLBACK_SCAN_ENABLED. The sweep is report-only and used to dominate
        // the whole suite's wall time.
        if (FALLBACK_SCAN_ENABLED) {
            for (BotNavigationGraph.Region rope : g.regions) {
                if (!rope.isRopeRegion) continue;
                for (BotNavigationGraph.Region ground : g.regions) {
                    if (ground.isRopeRegion) continue;
                    if (ground.maxY <= rope.minY) continue;
                    if (Math.abs(rope.minX - ground.minX) > 400 && Math.abs(rope.minX - ground.maxX) > 400
                            && !(rope.minX >= ground.minX && rope.minX <= ground.maxX)) continue;
                    Point probe = ground.centerPoint();
                    try {
                        var m = BotFallbackMovementManager.class.getDeclaredMethod("nearestUpwardRopeLaunch",
                                MapleMap.class, Point.class, BotMovementProfile.class);
                        m.setAccessible(true);
                        fallbackScans++;
                        boolean found = false;
                        // walk toward the rope's X in walkStep hops (what the bot actually does);
                        // a launch found anywhere along the approach counts as a pass.
                        int step = BotPhysicsEngine.walkStep(map, profile);
                        for (int hop = 0; hop <= 12 && !found; hop++) {
                            int dir = Integer.compare(rope.minX, probe.x);
                            Point p2 = hop == 0 ? probe
                                    : new Point(probe.x + dir * hop * step, probe.y);
                            Point launch = (Point) m.invoke(null, map, p2, profile);
                            if (launch != null) found = true;
                        }
                        if (!found) fallbackMiss++;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        String verdict = "OK";
        if (ropeNoEntry > 0) verdict = "LINK-FAIL(" + ropeNoEntry + "/" + ropeRegions + ")";
        else if (FALLBACK_SCAN_ENABLED && fallbackMiss > 0) verdict = "fbMiss=" + fallbackMiss + "/" + fallbackScans;
        return tag + " " + verdict;
    }
}
