package soloMapling.companion.gear;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionGearAdviceTest {

    @Test
    void answersLevelFortyShopQuestionWithProfessionTownsAndNearestShop() {
        List<String> facts = CompanionGearAdvice.forQuestion(
                "在哪里能买40级装备？", 103000104, ignored -> List.of());

        assertEquals(1, facts.size());
        assertTrue(facts.getFirst().contains("勇士部落 mapId=102000001"));
        assertTrue(facts.getFirst().contains("魔法密林 mapId=101000001"));
        assertTrue(facts.getFirst().contains("最近装备店 mapId=103000001"));
    }

    @Test
    void answersStaffShopQuestionWithElliniaWeaponShop() {
        List<String> facts = CompanionGearAdvice.forQuestion(
                "法杖在哪里买？", 103000104, ignored -> List.of());

        assertEquals(1, facts.size());
        assertTrue(facts.getFirst().contains("魔法密林武器店"));
        assertTrue(facts.getFirst().contains("mapId=101000001"));
    }

    @Test
    void ignoresUnrelatedConversation() {
        assertTrue(CompanionGearAdvice.forQuestion(
                "我们继续练级吧", 103000104, ignored -> List.of()).isEmpty());
    }
}
