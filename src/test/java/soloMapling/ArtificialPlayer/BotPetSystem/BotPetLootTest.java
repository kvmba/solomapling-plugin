package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.Pet;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPqData;
import soloMapling.ArtificialPlayer.BotTypes.Pirate.PiratePqData;

import java.awt.Point;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class BotPetLootTest {
    @BeforeAll
    static void stubHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(Locale.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(context.getBean(anyString(), eq(MessageSource.class))).thenReturn(messages);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
    }

    @ParameterizedTest
    @CsvSource({"910010000,4001101", "922010900,4001023", "922010900,4001022", "922010700,4001156"})
    void playerTurnInsAndAltarPassesAreNotPickedUpByBotsOrByTheirPets(int mapId, int itemId) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Monster source = mock(Monster.class);
        Pet pet = mock(Pet.class);
        int petId = 987_642;
        Point pos = new Point();
        when(bot.getMap()).thenReturn(map);
        when(map.getId()).thenReturn(mapId);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getId()).thenReturn(987_643);
        when(bot.isEquippedItemPouch((byte) 0)).thenReturn(true);
        when(pet.getUniqueId()).thenReturn(petId);
        when(pet.getPos()).thenReturn(pos);

        MapItem questDrop = new MapItem(new Item(itemId, (short) 0, (short) 1),
                pos, source, bot, null, (byte) 1, false);
        MapItem ordinaryDrop = new MapItem(new Item(2000000, (short) 0, (short) 1),
                pos, mock(Monster.class), bot, null, (byte) 1, false);
        when(map.getMapObjectsInRange(eq(pos), anyDouble(), eq(List.of(MapObjectType.ITEM))))
                .thenReturn(List.of(questDrop, ordinaryDrop));

        try {
            assertFalse(DropCommands.botCanLoot(bot, questDrop));
            DropCommands.botLootSingleDrop(bot, questDrop);
            BotPetFollower.collectLoot(bot, map, new Pet[]{pet}, BotPetConfig.defaults());
            verify(bot, never()).pickupItem(questDrop);
            verify(bot, never()).pickupItem(questDrop, 0);
            verify(bot).pickupItem(ordinaryDrop, 0);
        } finally {
            BotPetFollower.forgetPet(petId);
        }
    }

    @Test
    void pirateDoorKeyRemainsOnTheFloorAfterACharacterOffersIt() {
        Character bot = mock(Character.class);
        when(bot.getMapId()).thenReturn(PiratePqData.STAGE_4);
        Item key = new Item(PiratePqData.DOOR_KEY, (short) 0, (short) 1);
        Point at = new Point();
        assertFalse(DropCommands.botCanLoot(bot, new MapItem(key, at, bot, bot, null, (byte) 2, false)));
        assertTrue(DropCommands.botCanLoot(bot, new MapItem(key, at, mock(Monster.class), bot, null, (byte) 2, false)));
    }

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
