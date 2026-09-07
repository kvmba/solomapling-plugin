package soloMapling.companion.provisioning;

import org.gms.extension.api.HostCharacterProvisionMetadata;
import org.gms.extension.api.HostCharacterProvisionResult;
import org.gms.extension.api.HostCharacterProvisioner;
import org.gms.extension.api.HostCommandRegistry;
import org.gms.extension.api.HostConfig;
import org.gms.extension.api.HostEventBus;
import org.gms.extension.api.HostRuntime;
import org.junit.jupiter.api.Test;
import soloMapling.companion.progression.CompanionCareerBuild;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRuntimeCompanionProvisionerTest {

    @Test
    void mapsRequestAndPersistsProfileOnHostConnection() throws Exception {
        CapturingHostProvisioner host = new CapturingHostProvisioner();
        HostRuntimeCompanionProvisioner adapter =
                new HostRuntimeCompanionProvisioner(runtime("beidou", host));
        char[] credential = "temporary-secret".toCharArray();

        CompanionProvisionResult result = adapter.provision(
                new CompanionProvisionRequest("Mira", 42L, 3),
                "cmp_account",
                credential);

        assertEquals(new CompanionProvisionResult(700, 800, "Mira"), result);
        assertSame(credential, host.credential);
        assertEquals(3, host.worldId);
        assertSame(host.connection, host.callbackConnection);
        // The spawn-map UPDATE runs after the INSERT on the same host connection
        // and reuses parameter indexes, so read the profile columns from the
        // captured INSERT rather than from the shared parameter map.
        assertEquals(700, host.profileParameters.get(1));
        assertEquals(800, host.profileParameters.get(2));
        assertEquals("Mira", host.profileParameters.get(3));
        assertEquals(42L, host.profileParameters.get(4));
        assertEquals(CompanionCareerBuild.fromSeed(42L).id(), host.profileParameters.get(5));
        // A routine, or the companion's schedule parses as all-offline and the
        // lifecycle coordinator never spawns it at all.
        assertTrue(host.profileParameters.get(6) instanceof String,
                "routine timezone must be written");
        String routine = (String) host.profileParameters.get(7);
        assertTrue(routine != null && routine.startsWith("v1|"),
                "routine profile must be a v1 profile, got " + routine);
        assertEquals("Asia/Shanghai", host.profileParameters.get(6));
    }

    @Test
    void forcesTheSpawnOntoTheBeginnerIsland() throws Exception {
        CapturingHostProvisioner host = new CapturingHostProvisioner();
        HostRuntimeCompanionProvisioner adapter =
                new HostRuntimeCompanionProvisioner(runtime("beidou", host));

        adapter.provision(
                new CompanionProvisionRequest("Mira", 42L, 3), "cmp_account",
                "temporary-secret".toCharArray());

        // Map 10000 (Mushroom Town) is where a beginner starts and the only map
        // the walk to Southperry begins from. The host's alternative is a dead
        // end, so the provisioner overrides whatever the host picked.
        assertEquals(10_000, host.spawnMapId(), "spawn map was not forced");
        assertEquals(700, host.spawnCharacterId(), "spawn map applied to the wrong character");
    }

    @Test
    void rejectsUnsupportedOrMissingHostCapability() {
        HostRuntimeCompanionProvisioner other =
                new HostRuntimeCompanionProvisioner(runtime("cosmic", null));
        HostRuntimeCompanionProvisioner missing =
                new HostRuntimeCompanionProvisioner(runtime("beidou", null));

        assertFalse(other.isAvailable());
        assertTrue(other.unavailableReason().contains("not BeiDou"));
        assertFalse(missing.isAvailable());
        assertTrue(missing.unavailableReason().contains("missing"));
    }

    private static HostRuntime runtime(String hostId, HostCharacterProvisioner provisioner) {
        return new HostRuntime() {
            public HostConfig config() { return null; }
            public HostEventBus events() { return null; }
            public HostCommandRegistry commands() { return null; }
            public String hostId() { return hostId; }
            public Optional<HostCharacterProvisioner> characterProvisioner() {
                return Optional.ofNullable(provisioner);
            }
        };
    }

    /**
     * The callback runs two statements on one connection and both number their
     * parameters from 1, so a single shared map would let the second statement
     * overwrite the first's captured values. Statements are recorded per SQL
     * instead: {@code parameters} keeps whatever ran last, and the profile
     * columns are read from {@code profileParameters}.
     */
    private static Connection connection(
            Map<Integer, Object> parameters, Map<Integer, Object> profileParameters) {
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setInt", "setString", "setLong" -> {
                        parameters.put((Integer) args[0], args[1]);
                        yield null;
                    }
                    case "executeUpdate" -> 1;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (!"prepareStatement".equals(method.getName())) {
                        return defaultValue(method.getReturnType());
                    }
                    String sql = String.valueOf(args[0]);
                    // The profile INSERT is the one that names the table.
                    return sql.contains("INSERT INTO bot_profiles")
                            ? recording(profileParameters)
                            : statement;
                });
    }

    private static PreparedStatement recording(Map<Integer, Object> parameters) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setInt", "setString", "setLong" -> {
                        parameters.put((Integer) args[0], args[1]);
                        yield null;
                    }
                    case "executeUpdate" -> 1;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private static final class CapturingHostProvisioner implements HostCharacterProvisioner {
        private final Connection connection;
        private final Map<Integer, Object> parameters = new HashMap<>();
        /** Parameters of the bot_profiles INSERT, which the spawn UPDATE would otherwise overwrite. */
        final Map<Integer, Object> profileParameters = new HashMap<>();
        private char[] credential;
        private int worldId;
        private Connection callbackConnection;
        private Integer spawnMapId;
        private Integer spawnCharacterId;

        private CapturingHostProvisioner() {
            this.connection = connection(parameters, profileParameters);
        }

        int spawnMapId() {
            return spawnMapId == null ? -1 : spawnMapId;
        }

        int spawnCharacterId() {
            return spawnCharacterId == null ? -1 : spawnCharacterId;
        }

        @Override
        public HostCharacterProvisionResult provision(
                org.gms.extension.api.HostCharacterProvisionRequest request,
                org.gms.extension.api.HostCharacterMetadataCallback callback
        ) throws Exception {
            credential = request.credential();
            worldId = request.worldId();
            callbackConnection = connection;
            // The UPDATE shares the connection, so the spawn map it sets is
            // captured here by watching what the callback writes.
            Connection watching = watchingConnection(connection, this);
            callback.persist(watching, new HostCharacterProvisionMetadata(
                    700, 800, request.accountName(), request.characterName(), request.worldId()));
            return new HostCharacterProvisionResult(700, 800, request.characterName());
        }
    }

    /** A connection that notes the spawn-map UPDATE's arguments as they are set. */
    private static Connection watchingConnection(
            Connection delegate, CapturingHostProvisioner owner) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(delegate, args);
                    if ("prepareStatement".equals(method.getName())
                            && args.length > 0
                            && String.valueOf(args[0]).contains("UPDATE characters")) {
                        return spawnStatement(owner);
                    }
                    return result;
                });
    }

    private static PreparedStatement spawnStatement(CapturingHostProvisioner owner) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setInt" -> {
                        if ((Integer) args[0] == 1) {
                            owner.spawnMapId = (Integer) args[1];
                        } else if ((Integer) args[0] == 2) {
                            owner.spawnCharacterId = (Integer) args[1];
                        }
                        yield null;
                    }
                    case "executeUpdate" -> 1;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }
}
