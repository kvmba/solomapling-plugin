package soloMapling.companion.provisioning;

import org.gms.extension.api.HostCharacterProvisionMetadata;
import org.gms.extension.api.HostCharacterProvisionResult;
import org.gms.extension.api.HostCharacterProvisioner;
import org.gms.extension.api.HostCommandRegistry;
import org.gms.extension.api.HostConfig;
import org.gms.extension.api.HostEventBus;
import org.gms.extension.api.HostRuntime;
import org.junit.jupiter.api.Test;

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
        Map<Integer, Object> parameters = new HashMap<>();
        Connection connection = connection(parameters);
        CapturingHostProvisioner host = new CapturingHostProvisioner(connection);
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
        assertSame(connection, host.callbackConnection);
        assertEquals(700, parameters.get(1));
        assertEquals(800, parameters.get(2));
        assertEquals("Mira", parameters.get(3));
        assertEquals(42L, parameters.get(4));
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

    private static Connection connection(Map<Integer, Object> parameters) {
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
                (proxy, method, args) -> "prepareStatement".equals(method.getName())
                        ? statement
                        : defaultValue(method.getReturnType()));
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
        private char[] credential;
        private int worldId;
        private Connection callbackConnection;

        private CapturingHostProvisioner(Connection connection) {
            this.connection = connection;
        }

        @Override
        public HostCharacterProvisionResult provision(
                org.gms.extension.api.HostCharacterProvisionRequest request,
                org.gms.extension.api.HostCharacterMetadataCallback callback
        ) throws Exception {
            credential = request.credential();
            worldId = request.worldId();
            callbackConnection = connection;
            callback.persist(connection, new HostCharacterProvisionMetadata(
                    700, 800, request.accountName(), request.characterName(), request.worldId()));
            return new HostCharacterProvisionResult(700, 800, request.characterName());
        }
    }
}
