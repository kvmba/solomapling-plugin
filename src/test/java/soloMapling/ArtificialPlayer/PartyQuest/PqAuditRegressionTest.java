package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.client.inventory.Item;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningStages;
import soloMapling.ArtificialPlayer.BotTypes.Horntail.HorntailPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Magatia.MagatiaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Papulatus.PapulatusPQBot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PqAuditRegressionTest {
    @BeforeAll
    static void hostServices() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
        GameConfigDO interval = new GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
    }

    @Test
    void completedCouponGridNeverHuntsAgain() {
        Character bot = mock(Character.class);
        try (var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.readEimGrid(bot)).thenReturn(0);
            KerningStages.workCoupons(bot);
            actions.verify(() -> PqActions.waitNearStageNpc(bot, 9020001));
            actions.verify(() -> PqActions.seekAndAttack(bot), never());
            actions.verify(() -> PqActions.talkTo(bot, 9020001), never());
        }
    }

    @Test
    void unansweredCouponGridClaimsAQuestionBeforeHunting() {
        Character bot = mock(Character.class);
        try (var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.readEimGrid(bot)).thenReturn(-1);
            KerningStages.workCoupons(bot);
            actions.verify(() -> PqActions.talkTo(bot, 9020001));
            actions.verify(() -> PqActions.seekAndAttack(bot), never());
        }
    }

    @Test
    void couponSurplusIsLootButReactorStacksAndAddressedPilesAreNot() {
        MapItem drop = mock(MapItem.class);
        when(drop.getItem()).thenReturn(new Item(4001007, (short) 0, (short) 3));
        assertTrue(PqActions.isFloorLoot(drop));
        when(drop.getItem()).thenReturn(new Item(4001022, (short) 0, (short) 3));
        assertFalse(PqActions.isFloorLoot(drop));
        when(drop.getItem()).thenReturn(new Item(4001007, (short) 0, (short) 3));
        when(drop.isPermanentOwner()).thenReturn(true);
        assertFalse(PqActions.isFloorLoot(drop));
    }

    @Test
    void aSingleKeyDroppedIntoItsReactorIsNotPickedBackUp() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        MapItem drop = mock(MapItem.class);
        var reactor = mock(org.gms.server.maps.Reactor.class);
        var point = new java.awt.Point(10, 20);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(point);
        when(bot.getPartyId()).thenReturn(-1);
        when(map.getMapObjectsInRange(eq(point), anyDouble(), anyList()))
                .thenAnswer(invocation -> new java.util.ArrayList<>(List.of(drop)));
        when(map.getAllReactors()).thenReturn(List.of(reactor));
        when(drop.getItem()).thenReturn(new Item(4001088, (short) 0, (short) 1));
        when(drop.getItemId()).thenReturn(4001088);
        when(drop.getPosition()).thenReturn(point);
        when(drop.getDropper()).thenReturn(bot);
        when(drop.getDropType()).thenReturn((byte) 2);
        when(reactor.isAlive()).thenReturn(true);
        when(reactor.getReactorType()).thenReturn(100);
        when(reactor.getArea()).thenReturn(new java.awt.Rectangle(0, 0, 100, 100));
        when(reactor.getReactItem((byte) 0)).thenReturn(new org.gms.util.Pair<>(4001088, 1));
        PqActions.loot(bot, point, 100, new int[]{4001088});
        // The host consumes by item/quantity/area, not dropper type. A kill landing
        // directly inside the trigger is already reactor feed too.
        when(drop.getDropper()).thenReturn(mock(org.gms.server.life.Monster.class));
        PqActions.loot(bot, point, 100, new int[]{4001088});
        verify(bot, never()).pickupItem(any());
        PqActions.clearSeekState(bot.getId());
    }

    @Test
    void magatiaRoutesBothVersionsWithoutSelectingPuzzleDoors() throws Exception {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        MagatiaPQBot bot = mock(MagatiaPQBot.class, CALLS_REAL_METHODS);
        doReturn(chr).when(bot).getChr();
        var route = MagatiaPQBot.class.getDeclaredMethod("forwardPortal");
        route.setAccessible(true);
        for (int base : new int[]{926100000, 926110000}) {
            String prefix = base == 926100000 ? "rnj" : "jnr";
            Portal puzzle = portal("pt00", 999999999, prefix + "5_rp");
            Portal exit = portal("out00", base + 400, null);
            when(map.getPortal("out00")).thenReturn(exit);
            when(map.getPortals()).thenReturn(List.of(exit, puzzle));
            for (int room = 301; room <= 304; room++) {
                when(chr.getMapId()).thenReturn(base + room);
                assertSame(exit, route.invoke(bot));
            }
            when(chr.getMapId()).thenReturn(base + 300);
            assertNull(route.invoke(bot));
            Portal linear = portal("pt00", 999999999, prefix + "3_out");
            when(map.getPortals()).thenReturn(List.of(linear, puzzle));
            when(chr.getMapId()).thenReturn(base + 200);
            assertSame(linear, route.invoke(bot));
        }
    }

    @Test
    void papulatusPickupPolicyChangesWithTheMapWithoutAnyMacroTick() {
        Character chr = mock(Character.class);
        PapulatusPQBot bot = mock(PapulatusPQBot.class, CALLS_REAL_METHODS);
        doReturn(chr).when(bot).getChr();
        when(chr.getMapId()).thenReturn(220080001);
        assertFalse(bot.allowsPetPickup(4000000));
        assertFalse(bot.allowsPetPickup(0));
        when(chr.getMapId()).thenReturn(220080000);
        assertTrue(bot.allowsPetPickup(4000000));
    }

    @Test
    void horntailCaveFeedsItsKeyInsteadOfGivingItToTheLeader() throws Exception {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        var reactor = mock(org.gms.server.maps.Reactor.class);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(240050102);
        when(map.getReactorByName("passKey2")).thenReturn(reactor);
        when(reactor.getObjectId()).thenReturn(77);
        HorntailPQBot bot = mock(HorntailPQBot.class, CALLS_REAL_METHODS);
        doReturn(chr).when(bot).getChr();
        var work = HorntailPQBot.class.getDeclaredMethod("workStage");
        work.setAccessible(true);
        try (var actions = mockStatic(PqActions.class)) {
            work.invoke(bot);
            actions.verify(() -> PqActions.feedReactor(chr, 77, 4001089));
            actions.verify(() -> PqActions.handItemsToLeader(chr, 4001089), never());
        }
        assertFalse(bot.allowsPetPickup(4001089));
    }

    @Test
    void eachMagatiaTowerWalksItsOwnAnswerOneRowAtATime() throws Exception {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        MagatiaPQBot bot = mock(MagatiaPQBot.class, CALLS_REAL_METHODS);
        doReturn(chr).when(bot).getChr();
        var work = MagatiaPQBot.class.getDeclaredMethod("workStage");
        work.setAccessible(true);
        String answer = "3012301230";
        Portal exit = portal("out00", 926110400, null);
        when(exit.getPosition()).thenReturn(new java.awt.Point(178, -3042));
        when(map.getPortal("out00")).thenReturn(exit);
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 4; col++) {
                Portal gate = portal("pt" + row + col, 999999999, "jnr5_rp");
                when(gate.getPosition()).thenReturn(new java.awt.Point(-300 + col * 166, -52 - row * 300));
                when(map.getPortal("pt" + row + col)).thenReturn(gate);
            }
        }
        for (int slot = 1; slot <= 4; slot++) {
            when(chr.getMapId()).thenReturn(926110300 + slot);
            for (int row = 0; row < 10; row++) {
                when(chr.getPosition()).thenReturn(new java.awt.Point(-300, -52 - row * 300));
                Portal expected = map.getPortal("pt" + row + answer.charAt(row));
                try (var actions = mockStatic(PqActions.class)) {
                    int tower = slot;
                    actions.when(() -> PqActions.readEimString(chr, "stage6_comb" + tower)).thenReturn(answer);
                    actions.when(() -> PqActions.walkTo(chr, expected.getPosition())).thenReturn(true);
                    work.invoke(bot);
                    actions.verify(() -> PqActions.enterPortal(chr, expected));
                    actions.verify(() -> PqActions.enterPortal(chr, exit), never());
                }
            }
        }
    }

    @Test
    void aMapChangeDuringTheTowerDoorWalkDoesNotClaimFromAnotherInstance() throws Exception {
        Character chr = mock(Character.class);
        MapleMap split = mock(MapleMap.class);
        MapleMap lobby = mock(MapleMap.class);
        var instance = mock(org.gms.scripting.event.EventInstanceManager.class);
        when(chr.getMap()).thenReturn(split);
        when(chr.getMapId()).thenReturn(926110300);
        when(chr.getEventInstance()).thenReturn(instance);
        Portal door = portal("pt00", 999999999, "jnr4_r1");
        when(door.getPosition()).thenReturn(new java.awt.Point(-468, 124));
        when(split.getPortal("pt00")).thenReturn(door);
        MagatiaPQBot bot = mock(MagatiaPQBot.class, CALLS_REAL_METHODS);
        doReturn(chr).when(bot).getChr();
        var work = MagatiaPQBot.class.getDeclaredMethod("workStage");
        work.setAccessible(true);
        try (var actions = mockStatic(PqActions.class)) {
            actions.when(() -> PqActions.walkTo(chr, door.getPosition())).thenAnswer(invocation -> {
                when(chr.getMap()).thenReturn(lobby);
                when(chr.getEventInstance()).thenReturn(null);
                return true;
            });
            work.invoke(bot);
            actions.verify(() -> PqActions.enterPortal(chr, door), never());
        }
        verifyNoInteractions(instance);
    }

    private static Portal portal(String name, int target, String script) {
        Portal portal = mock(Portal.class);
        when(portal.getName()).thenReturn(name);
        when(portal.getTargetMapId()).thenReturn(target);
        when(portal.getScriptName()).thenReturn(script);
        return portal;
    }
}
