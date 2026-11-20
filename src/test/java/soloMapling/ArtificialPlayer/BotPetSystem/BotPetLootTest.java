package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Pet;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPqData;

import java.awt.Point;
import java.util.List;

import static org.mockito.Mockito.*;

class BotPetLootTest {
    @Test
    void aPetLeavesKerningCouponsForTheMemberButStillCollectsOtherDrops() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Pet pet = mock(Pet.class);
        Monster monster = mock(Monster.class);
        MapItem coupon = mock(MapItem.class);
        MapItem ordinaryDrop = mock(MapItem.class);
        Point pos = new Point();
        int petId = 987_641;

        when(map.getId()).thenReturn(KerningPqData.STAGE_1);
        when(bot.isEquippedItemPouch((byte) 0)).thenReturn(true);
        when(pet.getUniqueId()).thenReturn(petId);
        when(pet.getPos()).thenReturn(pos);
        when(coupon.getItemId()).thenReturn(KerningPqData.COUPON);
        when(coupon.getDropper()).thenReturn(monster);
        when(ordinaryDrop.getItemId()).thenReturn(2000000);
        when(ordinaryDrop.getDropper()).thenReturn(monster);
        when(map.getMapObjectsInRange(eq(pos), anyDouble(), eq(List.of(MapObjectType.ITEM))))
                .thenReturn(List.of(coupon, ordinaryDrop));

        try (var drops = mockStatic(DropCommands.class)) {
            drops.when(() -> DropCommands.botCanLoot(eq(bot), any(MapItem.class))).thenReturn(true);
            BotPetFollower.collectLoot(bot, map, new Pet[]{pet}, BotPetConfig.defaults());
            verify(bot, never()).pickupItem(coupon, 0);
            verify(bot).pickupItem(ordinaryDrop, 0);
        } finally {
            BotPetFollower.forgetPet(petId);
        }
    }
}
