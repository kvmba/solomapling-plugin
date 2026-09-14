package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.Persona;
import soloMapling.ArtificialPlayer.SocialPersonaConfig;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the server-culture persona layer: the spread stays "cheeky-leaning" but not a monolith, the
 * cheek/harsh classifiers bound who escalates, and the light {@code Banter} pool that carries the
 * everyday mouth exists in BOTH languages (the harsh {@code SmackTalk} pool is Chinese-only).
 */
class SocialPersonaTest {

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void spreadIsCheekyLeaningButNotAMonolith() {
        Map<Persona, Integer> counts = new HashMap<>();
        for (int id = 0; id < 600; id++) {
            counts.merge(Persona.of(id), 1, Integer::sum);
        }
        // Trash-talkers dominate the default server spread...
        assertTrue(counts.get(Persona.FRIENDLY) > 0, "a few friendly bots must exist");
        assertTrue(counts.get(Persona.TEASE) + counts.get(Persona.CASUAL) + counts.get(Persona.HYPE)
                        + counts.get(Persona.SARCASTIC) > counts.get(Persona.FRIENDLY) * 2,
                "cheeky personas should outweigh friendly ones; got " + counts);
    }

    @Test
    void sameIdAlwaysSamePersona() {
        for (int id = 0; id < 50; id++) {
            assertEquals(Persona.of(id), Persona.of(id));
        }
    }

    @Test
    void harshIsASubsetOfCheeky() {
        for (Persona p : Persona.values()) {
            if (p.harsh()) {
                assertTrue(p.cheeky(), p + " is harsh but not cheeky");
            }
        }
        org.junit.jupiter.api.Assertions.assertFalse(Persona.FRIENDLY.cheeky(),
                "friendly must never volunteer a jab");
        org.junit.jupiter.api.Assertions.assertFalse(Persona.CHILL.cheeky(),
                "chill must never volunteer a jab");
    }

    @Test
    void chillSpreadSoftensTowardFriendly() {
        Map<Persona, Integer> counts = new HashMap<>();
        for (int id = 0; id < 600; id++) {
            counts.merge(Persona.ofChill(id), 1, Integer::sum);
        }
        assertTrue(counts.get(Persona.FRIENDLY) > counts.get(Persona.TEASE),
                "the chill spread should lean friendly, got " + counts);
    }

    @Test
    void banterPoolExistsInBothLanguages() {
        for (String tag : new String[]{"zh-CN", "en-US"}) {
            SoloMaplingLanguageConfig.setLanguageTag(tag);
            var con = BotDialogueHandler.getDialogueCon("SocialBotDialogue.yaml", "SocialBot", "Banter");
            assertNotNull(con, tag + ": Banter node should exist");
            assertTrue(con.getDialogue().size() >= 40,
                    tag + ": Banter pool too small to avoid repeats: " + con.getDialogue().size());
        }
    }

    @Test
    void defaultConfigUsesTheCheekySpread() {
        SocialPersonaConfig.configure(null);
        Set<Persona> seen = EnumSet.noneOf(Persona.class);
        for (int id = 0; id < 200; id++) {
            seen.add(SocialPersonaConfig.personaFor(id));
        }
        assertTrue(seen.contains(Persona.TEASE), "default spread should include cheeky bots");
    }
}
