package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.manager.ServerManager;
import org.gms.net.server.world.Party;
import org.gms.property.ServiceProperty;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPqData.*;

class KerningStageOneTest {
    private static final int BOT_ID = 880197;
    private final Character member = mock(Character.class);
    private final Character leader = mock(Character.class);
    private final EventInstanceManager instance = mock(EventInstanceManager.class);
    private KerningPQBot bot;
    private int question = -1;
    private int coupons;
    private int passes;

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
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(call -> call.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, MessageSource.class)).thenReturn(messages);
        }
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
    }

    @BeforeEach
    void setUp() {
        when(member.getId()).thenReturn(BOT_ID);
        when(member.getHp()).thenReturn(100);
        when(member.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        when(member.getEventInstance()).thenReturn(instance);
        when(member.getMapId()).thenReturn(STAGE_1);
        when(member.getMap()).thenReturn(mock(MapleMap.class));
        when(member.getParty()).thenReturn(mock(Party.class));
        when(member.getItemQuantity(COUPON, false)).thenAnswer(call -> coupons);
        when(member.getItemQuantity(PASS, false)).thenAnswer(call -> passes);
        when(instance.gridCheck(member)).thenAnswer(call -> question);
        when(instance.getLeader()).thenReturn(leader);
        when(instance.getPlayerCount()).thenReturn(3);
        try (var generation = mockStatic(BotGeneration.class)) {
            bot = new KerningPQBot(member);
        }
        bot.setRunning(true);
    }

    @AfterEach
    void clearSeekState() {
        PqActions.clearSeekState(BOT_ID);
    }

    @Test
    void asksBeforeHuntingAndStopsOnThePickupThatMeetsTheQuota() {
        int[] loot = {COUPON};
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            actions.when(() -> PqActions.talkTo(member, NPC_CLOTO)).thenAnswer(call -> {
                assertEquals(-1, question);
                question = 1;
                return true;
            });
            bot.grindTick();
            actions.verify(() -> PqActions.lootSteppedOn(member, loot), never());
            actions.verify(() -> PqActions.seekAndAttack(member, false, loot), never());
            bot.workStage();
            coupons = 9;
            bot.workStage(); // still short; don't try to answer
            bot.grindTick();
            actions.when(() -> PqActions.lootSteppedOn(member, loot)).thenAnswer(call -> {
                coupons++;
                return 1;
            });
            bot.grindTick();
            bot.grindTick(); // monsters may keep spawning, but neither looting nor fighting resumes

            assertEquals(10, coupons);
            actions.verify(() -> PqActions.talkTo(member, NPC_CLOTO));
            actions.verify(() -> PqActions.lootSteppedOn(member, loot), times(2));
            actions.verify(() -> PqActions.seekAndAttack(member, false, loot));
            actions.verify(() -> PqActions.attack(member), never());
        }
    }

    @Test
    void dropsOnlyTheSurplusThenExchangesTheExactCountAndWaitsAfterHandoff() {
        question = 7;
        coupons = 13;
        Point position = new Point(20, 0);
        when(member.getPosition()).thenReturn(position);
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            actions.when(() -> PqActions.dropStack(member, COUPON, 5, position)).thenAnswer(call -> {
                coupons -= 5;
                return true;
            });
            actions.when(() -> PqActions.talkTo(member, NPC_CLOTO)).thenAnswer(call -> {
                assertEquals(8, coupons);
                coupons = 0;
                passes = 1;
                question = 0;
                return true;
            });
            actions.when(() -> PqActions.handItemsToLeader(member, PASS)).thenAnswer(call -> {
                assertEquals(1, passes);
                passes = 0;
                return 1;
            });

            bot.grindTick();
            bot.workStage();
            bot.workStage();
            bot.grindTick();

            actions.verify(() -> PqActions.dropStack(member, COUPON, 5, position));
            actions.verify(() -> PqActions.talkTo(member, NPC_CLOTO));
            actions.verify(() -> PqActions.handItemsToLeader(member, PASS));
            actions.verify(() -> PqActions.lootSteppedOn(member, new int[]{COUPON}), never());
            actions.verify(() -> PqActions.seekAndAttack(member, false, new int[]{COUPON}), never());
        }
    }

    @Test
    void enoughPassesOnTheLeaderAndStageClearBothStopAStillShortMember() {
        question = 1;
        when(leader.getItemQuantity(PASS, false)).thenReturn(2, 3, 0);
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            bot.grindTick(); // exactly enough passes
            bot.grindTick(); // surplus passes
            when(instance.getProperty("1stageclear")).thenReturn("true");
            bot.grindTick(); // still stopped after the leader submits the passes
            actions.verify(() -> PqActions.lootSteppedOn(member, new int[]{COUPON}), never());
            actions.verify(() -> PqActions.seekAndAttack(member, false, new int[]{COUPON}), never());
        }
    }

    @Test
    void walksToReachableCouponsOutsideTheCombatScanWithoutPickingThemUpRemotely() {
        question = 1;
        Point at = new Point(0, 0);
        when(member.getPosition()).thenReturn(at);
        MapItem drop = mock(MapItem.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) 1);
        when(drop.getItem()).thenReturn(item);
        when(drop.getItemId()).thenReturn(COUPON);
        when(drop.getOwnerId()).thenReturn(BOT_ID);
        when(drop.getPosition()).thenReturn(new Point(500, 0));
        MapleMap map = member.getMap();
        when(map.getMapObjectsInRange(eq(at), anyDouble(), eq(List.of(MapObjectType.ITEM))))
                .thenAnswer(call -> {
                    var items = new ArrayList<MapObject>();
                    if (call.<Double>getArgument(1) >= at.distanceSq(drop.getPosition())) {
                        items.add(drop);
                    }
                    return items;
                });
        try (var attack = mockStatic(BotAttackDriver.class); var movement = mockStatic(GCMovement.class)) {
            attack.when(() -> BotAttackDriver.attackReachX(member)).thenReturn(150);
            attack.when(() -> BotAttackDriver.attackReachY(member)).thenReturn(100);
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            movement.when(() -> GCMovement.canPathTo(member, 500, 0)).thenReturn(true);

            bot.grindTick();
            bot.workStage();

            movement.verify(() -> GCMovement.move(eq(member), eq(500), eq(0), any(), any()));
            verify(member, never()).pickupItem(drop);
        }
    }

    @Test
    void combatSkipsTheBeatWhileClotoIsSpendingTheCoupons() {
        question = 1;
        coupons = 10;
        try (var actions = mockActions()) {
            actions.when(() -> PqActions.talkTo(member, NPC_CLOTO)).thenAnswer(call -> {
                coupons = 0; // Cloto spends the coupons before recording the pass in the grid.
                var sweep = new FutureTask<>(() -> {
                    try (var combat = mockActions(); var movement = mockStatic(GCMovement.class)) {
                        movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
                        bot.grindTick();
                        combat.verify(() -> PqActions.lootSteppedOn(member, new int[]{COUPON}), never());
                        combat.verify(() -> PqActions.seekAndAttack(member, false, new int[]{COUPON}), never());
                    }
                    return null;
                });
                Thread.startVirtualThread(sweep);
                sweep.get();
                passes = 1;
                question = 0;
                return true;
            });

            bot.workStage();
            assertEquals(0, question);
        }
    }

    private static MockedStatic<PqActions> mockActions() {
        return mockStatic(PqActions.class, call -> switch (call.getMethod().getName()) {
            case "countItem", "readEimString" -> call.callRealMethod();
            default -> RETURNS_DEFAULTS.answer(call);
        });
    }
}
