package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the engine seams the arena opens a match through.
 *
 * <p>The whole design rests on a handful of host APIs being callable from the plugin without going
 * through an NPC conversation: {@code MonsterCarnival}'s two-party constructor, {@code Party}'s
 * creation and join paths, the channel's field registry, and the arena map's own carnival data. If
 * the host ever makes one of them non-public the arena fails at runtime with an access error that
 * no behavioural test would catch, so the signatures themselves are the contract.
 *
 * <p>Deliberately reflection-only and initialisation-free: this test reads class <em>shapes</em>,
 * never instances. Loading a host class like {@code Character} the ordinary way would run its
 * static initialiser, which needs the server's Spring context - a concern these signatures do not
 * have. {@code Class.forName(name, false, loader)} is what keeps the check honest and cheap.
 */
class CarnivalEngineSeamTest {

    private static Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, false, CarnivalEngineSeamTest.class.getClassLoader());
    }

    @Test
    void theMatchConstructorIsCallableFromThePlugin() throws Exception {
        Class<?> mc = cls("org.gms.server.partyquest.MonsterCarnival");
        Constructor<?> ctor = mc.getConstructor(
                cls("org.gms.net.server.world.Party"),
                cls("org.gms.net.server.world.Party"),
                int.class, boolean.class, int.class);
        assertNotNull(ctor, "the two-party constructor must exist");
        assertTrue(Modifier.isPublic(ctor.getModifiers()),
                "the constructor must stay public or the arena cannot open a match");
    }

    @Test
    void thePartyCreationAndJoinPathsArePublic() throws Exception {
        Class<?> party = cls("org.gms.net.server.world.Party");
        Class<?> chr = cls("org.gms.client.Character");

        Method create = party.getMethod("createParty", chr, boolean.class);
        assertTrue(Modifier.isPublic(create.getModifiers()), "Party.createParty must stay public");
        assertTrue(Modifier.isStatic(create.getModifiers()));

        Method join = party.getMethod("joinParty", chr, int.class, boolean.class);
        assertTrue(Modifier.isPublic(join.getModifiers()), "Party.joinParty must stay public");
        assertTrue(Modifier.isStatic(join.getModifiers()));

        // The enemy pairing is what makes the match a match: both sides must point at each other.
        assertTrue(Modifier.isPublic(party.getMethod("setEnemy", party).getModifiers()));
        assertTrue(Modifier.isPublic(party.getMethod("getEnemy").getModifiers()));
    }

    @Test
    void theChannelFieldRegistryIsPublic() throws Exception {
        Class<?> channel = cls("org.gms.net.server.channel.Channel");
        assertTrue(Modifier.isPublic(
                channel.getMethod("canInitMonsterCarnival", boolean.class, int.class).getModifiers()));
        assertTrue(Modifier.isPublic(
                channel.getMethod("initMonsterCarnival", boolean.class, int.class).getModifiers()));
        assertTrue(Modifier.isPublic(
                channel.getMethod("finishMonsterCarnival", boolean.class, int.class).getModifiers()));
    }

    @Test
    void theArenaExposesTheCarnivalDataTheBotReads() throws Exception {
        Class<?> map = cls("org.gms.server.maps.MapleMap");
        Class<?> monster = cls("org.gms.server.life.Monster");

        assertTrue(Modifier.isPublic(map.getMethod("getTimeDefault").getModifiers()),
                "the poll refuses to open a field whose clock did not load");
        assertTrue(Modifier.isPublic(map.getMethod("getMobsToSpawn").getModifiers()),
                "the summon list is the arena's own, never a hard-coded table");
        assertTrue(Modifier.isPublic(map.getMethod("getRandomSP", int.class).getModifiers()),
                "a summon without a spawn point must be refused before CP is spent");
        assertTrue(Modifier.isPublic(
                map.getMethod("addMonsterSpawn", monster, int.class, int.class).getModifiers()));
        assertTrue(Modifier.isPublic(
                map.getMethod("addAllMonsterSpawn", monster, int.class, int.class).getModifiers()),
                "the second registry is what the arena clears between matches");
    }

    @Test
    void theCharacterScoreSeamsArePublic() throws Exception {
        Class<?> chr = cls("org.gms.client.Character");
        assertTrue(Modifier.isPublic(chr.getMethod("gainCP", int.class).getModifiers()),
                "CP must be charged through gainCP so team totals move too");
        assertTrue(Modifier.isPublic(chr.getMethod("getCP").getModifiers()));
        assertTrue(Modifier.isPublic(chr.getMethod("setTeam", int.class).getModifiers()));
        assertTrue(Modifier.isPublic(chr.getMethod("getTeam").getModifiers()));
        assertTrue(Modifier.isPublic(
                chr.getMethod("setMonsterCarnival",
                        cls("org.gms.server.partyquest.MonsterCarnival")).getModifiers()));
        assertTrue(Modifier.isPublic(chr.getMethod("getMonsterCarnival").getModifiers()));
    }

    @Test
    void theArenaNeverNeedsAnNpcConversation() {
        // The constraint this whole design answers: no NPC script and no dialog. Every method the
        // arena itself declares must therefore be free of the scripting package - a check on the
        // shape of the class, so it holds even as the arena grows.
        for (Method m : CarnivalArena.class.getDeclaredMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                assertTrue(!p.getName().startsWith("org.gms.scripting"),
                        "CarnivalArena must not take a scripting object: " + m.getName());
            }
        }
        for (Constructor<?> c : CarnivalArena.class.getDeclaredConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                assertTrue(!p.getName().startsWith("org.gms.scripting"),
                        "CarnivalArena must not take a scripting object");
            }
        }
    }
}
