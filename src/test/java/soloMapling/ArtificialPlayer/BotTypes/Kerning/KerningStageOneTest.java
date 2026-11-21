package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.manager.ServerManager;
import org.gms.net.server.world.Party;
import org.gms.property.ServiceProperty;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.life.Monster;
import org.gms.server.life.NPC;
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
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.Environment.BotMessages;
import soloMapling.server.BotTickService;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPqData.*;
import static soloMapling.ArtificialPlayer.PartyQuest.PqActions.LootBeat.*;

class KerningStageOneTest {
    private static final int BOT_ID = 880197;
    private static final Point CLOTO_POSITION = new Point(-1000, 0);
    private static final Point CLOTO_STAND = new Point(-956, 0);
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
        MapleMap stage = mock(MapleMap.class);
        when(stage.getId()).thenReturn(STAGE_1);
        when(member.getMap()).thenReturn(stage);
        NPC cloto = mock(NPC.class);
        when(cloto.getPosition()).thenReturn(CLOTO_POSITION);
        when(member.getMap().getNPCById(NPC_CLOTO)).thenReturn(cloto);
        when(member.getParty()).thenReturn(mock(Party.class));
        when(member.getItemQuantity(COUPON, false)).thenAnswer(call -> coupons);
        when(member.getItemQuantity(PASS, false)).thenAnswer(call -> passes);
        when(instance.gridCheck(member)).thenAnswer(call -> question);
        doAnswer(call -> {
            question = call.getArgument(1);
            return null;
        }).when(instance).gridInsert(eq(member), anyInt());
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
    void announcesAfterTheNpcPauseAndReportsOnlySuccessfulPickups() {
        int[] loot = {COUPON};
        List<String> lines = new ArrayList<>();
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class);
             var chat = mockStatic(SocialCommands.class); var ticks = mockStatic(BotTickService.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            chat.when(() -> SocialCommands.BotFullChat(eq(member), anyString())).thenAnswer(call -> {
                lines.add(call.getArgument(1));
                return null;
            });

            movement.when(GCMovement::movementNowMs).thenReturn(100L);
            bot.workStage(); // reached Cloto; the pause starts here
            movement.when(GCMovement::movementNowMs).thenReturn(2_099L);
            bot.workStage();
            bot.grindTick();
            assertEquals(-1, question);
            assertTrue(lines.isEmpty());
            actions.verify(() -> PqActions.collectPartyMonsterLoot(member, loot), never());
            actions.verify(() -> PqActions.seekAndAttack(member), never());

            ticks.clearInvocations();
            movement.when(GCMovement::movementNowMs).thenReturn(5_100L);
            bot.workStage();
            int target = QUESTION_ANSWERS[question - 1];
            assertEquals(1, lines.size());
            assertInCollectionPool(lines.getFirst(), "start", target);

            bot.grindTick(); // knowing the answer does not let the combat sweep leave yet
            actions.verify(() -> PqActions.collectPartyMonsterLoot(member, loot), never());
            actions.verify(() -> PqActions.seekAndAttack(member), never());
            ticks.verify(() -> BotTickService.requestEarlierTick(eq(BOT_ID), any(Runnable.class),
                    longThat(delay -> delay >= 600 && delay <= 3_000)));

            movement.when(GCMovement::movementNowMs).thenReturn(8_100L);
            coupons = target - 2;
            bot.workStage(); // inventory isn't a pickup event
            actions.when(() -> PqActions.collectPartyMonsterLoot(member, loot)).thenReturn(NONE);
            bot.grindTick(); // no pickup on this beat
            assertEquals(1, lines.size());
            actions.when(() -> PqActions.collectPartyMonsterLoot(member, loot)).thenAnswer(call -> {
                coupons++;
                return PICKED_UP;
            });
            bot.grindTick();
            assertEquals(2, lines.size());
            assertInCollectionPool(lines.get(1), "remaining", 1);
            bot.grindTick();
            assertEquals(3, lines.size());
            assertInCollectionPool(lines.get(2), "done");
            bot.grindTick(); // monsters may keep spawning, but neither looting nor fighting resumes
            bot.workStage(); // returning for the exchange must not repeat the completion line

            assertEquals(target, coupons);
            assertEquals(3, lines.size());
            actions.verify(() -> PqActions.talkTo(member, NPC_CLOTO));
            actions.verify(() -> PqActions.collectPartyMonsterLoot(member, loot), times(3));
            actions.verify(() -> PqActions.seekAndAttack(member));
            actions.verify(() -> PqActions.attack(member), never());
        }
    }

    @Test
    void eachMemberUsesItsOwnDepartureDeadline() {
        Character teammate = mock(Character.class);
        MapleMap room = member.getMap();
        Party party = member.getParty();
        when(teammate.getId()).thenReturn(BOT_ID + 1);
        when(teammate.getMap()).thenReturn(room);
        when(teammate.getMapId()).thenReturn(STAGE_1);
        when(teammate.getEventInstance()).thenReturn(instance);
        when(teammate.getParty()).thenReturn(party);
        when(instance.gridCheck(teammate)).thenReturn(-1);
        var first = new KerningStages.CouponRun(instance);
        var second = new KerningStages.CouponRun(instance);
        var delay = new java.util.concurrent.atomic.AtomicLong();
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class);
             var chat = mockStatic(SocialCommands.class)) {
            movement.when(GCMovement::movementNowMs).thenReturn(100L);
            first.work(member, delay::set);
            long firstClaim = 100L + delay.get();
            movement.when(GCMovement::movementNowMs).thenReturn(firstClaim);
            first.work(member, delay::set);
            long firstDeparture = firstClaim + delay.get();

            movement.when(GCMovement::movementNowMs).thenReturn(firstDeparture - 1);
            assertFalse(first.canCollect());
            second.work(teammate, delay::set);
            long secondClaim = firstDeparture - 1 + delay.get();
            movement.when(GCMovement::movementNowMs).thenReturn(secondClaim);
            second.work(teammate, delay::set);
            assertTrue(first.canCollect());
            assertFalse(second.canCollect());
            movement.when(GCMovement::movementNowMs).thenReturn(secondClaim + delay.get());
            assertTrue(second.canCollect());
        }
    }

    @Test
    void theInstanceKeepsCountsDistinctAcrossReentryAndExchange() {
        var grid = new HashMap<Character, Integer>();
        var reservation = new AtomicReference<>();
        when(instance.gridCheck(any(Character.class))).thenAnswer(call -> grid.getOrDefault(call.getArgument(0), -1));
        doAnswer(call -> {
            grid.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(instance).gridInsert(any(Character.class), anyInt());
        when(instance.getObjectProperty(anyString())).thenAnswer(call -> reservation.get());
        doAnswer(call -> {
            reservation.set(call.getArgument(1));
            return null;
        }).when(instance).setObjectProperty(anyString(), any());
        when(instance.getPlayerCount()).thenReturn(4);

        var targets = new HashSet<Integer>();
        MapleMap room = member.getMap();
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class);
             var chat = mockStatic(SocialCommands.class)) {
            for (int i = 0; i < 3; i++) {
                Character teammate = mock(Character.class);
                when(teammate.getId()).thenReturn(BOT_ID + i);
                when(teammate.getEventInstance()).thenReturn(instance);
                when(teammate.getMap()).thenReturn(room);
                when(teammate.getMapId()).thenReturn(STAGE_1);
                var run = new KerningStages.CouponRun(instance);
                movement.when(GCMovement::movementNowMs).thenReturn(0L);
                run.work(teammate, ignored -> {});
                movement.when(GCMovement::movementNowMs).thenReturn(5_000L);
                run.work(teammate, ignored -> {});

                int assigned = grid.get(teammate);
                assertTrue(targets.add(QUESTION_ANSWERS[assigned - 1]), "duplicate bot quota");
                if (i == 0) {
                    grid.remove(teammate); // The host clears the grid entry on unregister.
                    var reentry = new KerningStages.CouponRun(instance);
                    reentry.work(teammate, ignored -> {});
                    movement.when(GCMovement::movementNowMs).thenReturn(10_000L);
                    reentry.work(teammate, ignored -> {});
                    assertEquals(assigned, grid.get(teammate));
                }
                instance.gridInsert(teammate, 0); // Cloto no longer stores the question after exchange.
            }
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
            actions.when(() -> PqActions.handItemsToLeaderAfterStage(member, PASS, NPC_CLOTO)).thenAnswer(call -> {
                if (passes == 0) return 0;
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
            actions.verify(() -> PqActions.handItemsToLeaderAfterStage(member, PASS, NPC_CLOTO), times(2));
            actions.verify(() -> PqActions.waitNearStageNpc(member, NPC_CLOTO), times(2));
            actions.verify(() -> PqActions.handItemsToLeader(member, PASS), never());
            actions.verify(() -> PqActions.collectPartyMonsterLoot(member, new int[]{COUPON}), never());
            actions.verify(() -> PqActions.seekAndAttack(member), never());
        }
    }

    @Test
    void enoughPassesOnTheLeaderAndStageClearBothStopAStillShortMember() {
        question = 1;
        when(leader.getItemQuantity(PASS, false)).thenReturn(2);
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            bot.grindTick(); // exactly enough passes
            bot.workStage();
            when(leader.getItemQuantity(PASS, false)).thenReturn(3);
            bot.grindTick(); // surplus passes
            bot.workStage();
            when(instance.getProperty("1stageclear")).thenReturn("true");
            when(leader.getItemQuantity(PASS, false)).thenReturn(0);
            bot.grindTick(); // still stopped after the leader submits the passes
            bot.workStage();
            actions.verify(() -> PqActions.waitNearStageNpc(member, NPC_CLOTO), times(3));
            actions.verify(() -> PqActions.collectPartyMonsterLoot(member, new int[]{COUPON}), never());
            actions.verify(() -> PqActions.seekAndAttack(member), never());
        }
    }

    @Test
    void onlyCountsTimeActuallyStandingAtCloto() {
        when(member.getPosition()).thenReturn(new Point(500, 0));
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class)) {
            movement.when(GCMovement::isVirtualClock).thenReturn(true);
            movement.when(() -> GCMovement.groundPointBelow(member.getMap(), CLOTO_STAND.x, 0))
                    .thenReturn(CLOTO_STAND);
            actions.when(() -> PqActions.walkTo(member, CLOTO_STAND)).thenCallRealMethod();

            bot.workStage();

            movement.verify(() -> GCMovement.move(eq(member), eq(CLOTO_STAND.x), eq(0), any(), any()));
            verify(instance, never()).gridInsert(eq(member), anyInt());
            when(member.getPosition()).thenReturn(CLOTO_STAND);
            movement.when(GCMovement::movementNowMs).thenReturn(20_000L);
            bot.workStage();
            verify(instance, never()).gridInsert(eq(member), anyInt());
            when(member.getPosition()).thenReturn(new Point(500, 0));
            bot.workStage(); // knocked away, so a fresh wait is needed
            when(member.getPosition()).thenReturn(CLOTO_STAND);
            movement.when(GCMovement::movementNowMs).thenReturn(30_000L);
            bot.workStage();
            verify(instance, never()).gridInsert(eq(member), anyInt());
            movement.when(GCMovement::movementNowMs).thenReturn(35_000L);
            bot.workStage();
            verify(instance).gridInsert(eq(member), anyInt());
            actions.verify(() -> PqActions.talkTo(member, NPC_CLOTO), never());
        }
    }

    @Test
    void aNewInstanceStartsAFreshNpcPause() {
        EventInstanceManager nextRun = mock(EventInstanceManager.class);
        when(nextRun.getLeader()).thenReturn(leader);
        when(nextRun.getPlayerCount()).thenReturn(3);
        when(nextRun.gridCheck(member)).thenReturn(-1);
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class)) {
            movement.when(GCMovement::movementNowMs).thenReturn(0L);
            bot.workStage();
            when(member.getEventInstance()).thenReturn(nextRun);
            movement.when(GCMovement::movementNowMs).thenReturn(5_000L);
            bot.workStage();
            verify(instance, never()).gridInsert(eq(member), anyInt());
            verify(nextRun, never()).gridInsert(eq(member), anyInt());

            movement.when(GCMovement::movementNowMs).thenReturn(10_000L);
            bot.workStage();
            verify(nextRun).gridInsert(eq(member), anyInt());
        }
    }

    @Test
    void returnsToClotoBeforeDroppingSurplusOrExchangingCoupons() {
        question = 7;
        coupons = 13;
        when(member.getPosition()).thenReturn(new Point(500, 0));
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class)) {
            movement.when(GCMovement::isVirtualClock).thenReturn(true);
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            actions.when(() -> PqActions.walkTo(member, CLOTO_POSITION)).thenCallRealMethod();
            actions.when(() -> PqActions.dropStack(member, COUPON, 5, CLOTO_POSITION)).thenAnswer(call -> {
                coupons -= 5;
                return true;
            });
            actions.when(() -> PqActions.talkTo(member, NPC_CLOTO)).thenAnswer(call -> {
                assertEquals(CLOTO_POSITION, member.getPosition());
                assertEquals(8, coupons);
                coupons = 0;
                passes = 1;
                question = 0;
                return true;
            });

            bot.workStage();
            bot.grindTick();

            movement.verify(() -> GCMovement.move(eq(member), eq(-1000), eq(0), any(), any()));
            actions.verify(() -> PqActions.dropStack(eq(member), eq(COUPON), anyInt(), any()), never());
            actions.verify(() -> PqActions.talkTo(member, NPC_CLOTO), never());
            actions.verify(() -> PqActions.seekAndAttack(member), never());
            when(member.getPosition()).thenReturn(CLOTO_POSITION);
            bot.workStage();
            actions.verify(() -> PqActions.dropStack(member, COUPON, 5, CLOTO_POSITION));
            actions.verify(() -> PqActions.talkTo(member, NPC_CLOTO));
        }
    }

    @Test
    void deliversPassesOnlyAtClotoEvenWhileTheLeaderIsStillHunting() {
        question = 0;
        passes = 1;
        when(member.getPosition()).thenReturn(new Point(500, 0));
        MapleMap room = member.getMap();
        when(leader.getMap()).thenReturn(room);
        when(leader.getPosition()).thenReturn(new Point(600, 0));
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class)) {
            actions.when(() -> PqActions.partyLeader(member)).thenReturn(leader);
            actions.when(() -> PqActions.handItemsToLeaderAfterStage(member, PASS, NPC_CLOTO))
                    .thenCallRealMethod();
            actions.when(() -> PqActions.giveItemAt(member, leader, PASS, 1, CLOTO_POSITION))
                    .thenAnswer(call -> {
                        assertEquals(CLOTO_POSITION, member.getPosition());
                        passes = 0;
                        return null;
                    });

            bot.workStage();

            assertEquals(1, passes);
            actions.verify(() -> PqActions.walkTo(member, CLOTO_POSITION));
            actions.verify(() -> PqActions.giveItemAt(any(), any(), anyInt(), anyInt(), any()), never());
            when(member.getPosition()).thenReturn(CLOTO_POSITION);
            bot.workStage();
            assertEquals(0, passes);
            actions.verify(() -> PqActions.giveItemAt(member, leader, PASS, 1, CLOTO_POSITION));
            actions.verify(() -> PqActions.handItemsToLeader(member, PASS), never());
        }
    }

    @Test
    void aMemberThatAlreadyDeliveredReturnsToWaitAtClotoInsteadOfRemainingAmongMonsters() {
        question = 0;
        when(member.getPosition()).thenReturn(new Point(500, 0));
        try (var actions = mockActions(); var movement = mockStatic(GCMovement.class)) {
            movement.when(GCMovement::isVirtualClock).thenReturn(true);
            actions.when(() -> PqActions.walkTo(member, CLOTO_POSITION)).thenCallRealMethod();
            actions.when(() -> PqActions.waitNearStageNpc(member, NPC_CLOTO)).thenCallRealMethod();

            bot.workStage();

            movement.verify(() -> GCMovement.move(eq(member), eq(-1000), eq(0), any(), any()));
            actions.verify(() -> PqActions.recoverUngatheredHandoffs(member, PASS));
            actions.verify(() -> PqActions.talkTo(member, NPC_CLOTO), never());
        }
    }

    @Test
    void walksToThePartysReachableCouponBeforeAttackingAndPicksItUpOnlyInReach() {
        question = 1;
        when(member.getPosition()).thenReturn(new Point());
        when(member.getPartyId()).thenReturn(37);
        when(leader.getId()).thenReturn(BOT_ID + 1);
        when(leader.getPartyId()).thenReturn(37);
        Character outsider = mock(Character.class);
        when(outsider.getId()).thenReturn(BOT_ID + 2);
        when(outsider.getPartyId()).thenReturn(38);
        Point destination = new Point(500, 0);
        MapItem partyDrop = couponDrop(leader, destination);
        MapItem foreignDrop = couponDrop(outsider, new Point(10, 0));
        MapItem unreachableDrop = couponDrop(member, new Point(90, -80));
        MapItem addressedDrop = couponDrop(leader, new Point(20, 0));
        addressedDrop.lockItem();
        try {
            addressedDrop.setPermanentOwnerLocked(true);
        } finally {
            addressedDrop.unlockItem();
        }
        stubFloorCoupons(foreignDrop, unreachableDrop, addressedDrop, partyDrop);

        try (var attack = mockStatic(BotAttackDriver.class); var movement = mockStatic(GCMovement.class);
             var chat = mockStatic(SocialCommands.class)) {
            attack.when(() -> BotAttackDriver.attackReachX(member)).thenReturn(150);
            attack.when(() -> BotAttackDriver.attackReachY(member)).thenReturn(100);
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            movement.when(() -> GCMovement.canPathTo(member, 500, 0)).thenReturn(true);
            long dropTime = System.currentTimeMillis() - 1_000;
            partyDrop.setDropTime(dropTime);
            foreignDrop.setDropTime(dropTime);

            bot.grindTick();
            movement.when(() -> GCMovement.moveTargetOf(member)).thenReturn(destination);
            bot.grindTick(); // the live pickup walk must not restart or let combat in

            movement.verify(() -> GCMovement.move(eq(member), eq(500), eq(0), any(), any()));
            verify(member, never()).pickupItem(any());
            attack.verify(() -> BotAttackDriver.botAttack(member), never());

            when(member.getPosition()).thenReturn(destination);
            bot.grindTick();
            verify(member).pickupItem(partyDrop);
            attack.verify(() -> BotAttackDriver.botAttack(member), never());

            bot.grindTick(); // only unavailable coupons remain, even with the old goal still live
            attack.verify(() -> BotAttackDriver.botAttack(member));
            assertEquals(1, coupons);
        }
    }

    @Test
    void picksUpTheSettledCouponBeforeTheCloserNewDropAndWaitsThroughCooldown() {
        question = 1;
        when(member.getPosition()).thenReturn(new Point());
        MapItem settled = couponDrop(member, new Point(20, 0));
        MapItem newDrop = couponDrop(member, new Point());
        newDrop.setDropTime(Long.MAX_VALUE);
        stubFloorCoupons(settled, newDrop);

        try (var attack = mockStatic(BotAttackDriver.class); var movement = mockStatic(GCMovement.class);
             var chat = mockStatic(SocialCommands.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            bot.grindTick();
            verify(member).pickupItem(settled);
            bot.grindTick(); // wait for the drop to settle instead of attacking
            newDrop.setDropTime(0);
            bot.grindTick(); // the drop settled, but virtual pickup time has not advanced
            verify(member, never()).pickupItem(newDrop);

            movement.when(GCMovement::movementNowMs).thenReturn(250L);
            bot.grindTick();
            verify(member).pickupItem(newDrop);
            attack.verifyNoInteractions();
        }
    }

    @Test
    void doesNotPickUpACouponFromTheRoomTheMemberJustLeft() {
        question = 1;
        when(member.getPosition()).thenReturn(new Point());
        MapItem drop = couponDrop(member, new Point());
        MapleMap nextRoom = mock(MapleMap.class);
        when(member.getMap().getMapObjectsInRange(any(Point.class), anyDouble(), eq(List.of(MapObjectType.ITEM))))
                .thenAnswer(call -> {
                    when(member.getMap()).thenReturn(nextRoom);
                    return new ArrayList<>(List.of(drop));
                });
        try (var attack = mockStatic(BotAttackDriver.class); var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.hasGraph(member)).thenReturn(true);
            bot.grindTick();
            verify(member, never()).pickupItem(any());
            attack.verifyNoInteractions();
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
                        combat.verify(() -> PqActions.collectPartyMonsterLoot(member, new int[]{COUPON}), never());
                        combat.verify(() -> PqActions.seekAndAttack(member), never());
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

    private static MapItem couponDrop(Character owner, Point at) {
        return new MapItem(new Item(COUPON, (short) 0, (short) 1), at,
                mock(Monster.class), owner, owner.getClient(), (byte) 1, false);
    }

    private void stubFloorCoupons(MapItem... drops) {
        when(member.getMap().getMapObjectsInRange(any(Point.class), anyDouble(), eq(List.of(MapObjectType.ITEM))))
                .thenAnswer(call -> {
                    Point center = call.getArgument(0);
                    double radiusSq = call.getArgument(1);
                    var items = new ArrayList<MapObject>();
                    for (MapItem drop : drops) {
                        if (!drop.isPickedUp() && center.distanceSq(drop.getPosition()) <= radiusSq) items.add(drop);
                    }
                    return items;
                });
        doAnswer(call -> {
            MapItem drop = call.getArgument(0);
            drop.setPickedUp(true);
            coupons++;
            return null;
        }).when(member).pickupItem(any(MapItem.class));
    }

    private static MockedStatic<PqActions> mockActions() {
        return mockStatic(PqActions.class, call -> switch (call.getMethod().getName()) {
            case "countItem", "readEimString", "walkToNpc", "deliverAndWait" -> call.callRealMethod();
            case "walkTo" -> call.getMethod().getParameterCount() == 2 ? true : call.callRealMethod();
            default -> java.lang.reflect.Modifier.isPrivate(call.getMethod().getModifiers())
                    ? call.callRealMethod() : RETURNS_DEFAULTS.answer(call);
        });
    }

    private static void assertInCollectionPool(String actual, String beat, Object... args) {
        for (int i = 1; i <= 20; i++) {
            if (actual.equals(BotMessages.get("pq.collection." + beat + "." + i, args))) {
                return;
            }
        }
        throw new AssertionError("Unexpected " + beat + " line: " + actual);
    }
}
