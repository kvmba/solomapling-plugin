# 主源码真 BUG 审计（2026-10-20）

- 审计对象：`/workspace/solomapling-plugin`（`optimize/performance` @ `ba2a8dd`）下 `src/main/java/**`（Java 源码）与宿主 `/workspace/GMS083/gms-server`（Java 源码）。不含 `src/test/**`、`scripts/**`、`wz/**`。
- 未采信：`src/test/**`、docs、源码注释里"已修/已验证"的自述；注释与代码冲突时以代码 + 宿主行为为准。
- **复核记录（同日）**：本文档的每一条判定已对照 `ba2a8dd` + 宿主 `336f3eb78` 的当前源码逐条独立重推，修订了初版的夸大与误判（见 A-1/A-2/A-3/A-4/A-5 的复核注记）。初版文档文件本体曾发生多字节编码损坏，本版为重写的权威版本。

分四部分：**A 已确认的真 bug**（含复核修订）/ **B 需实测确认**（不作为结论）/ **C 证伪**（经宿主源码核实不成立）/ **D 处理顺序**。

---

## A. 确认的真 bug

### A-1 P1 `seek()` 的同 ledge 过滤对远程/魔法误伤——射程优势被降级为近战行为

**位置**

- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:468-501` `seek()`：`:490` 对**所有 route 无差别**执行 `!m.isBoss() && GCMovement.onDifferentLedge(map, botRegion, mp.x, mp.y)` → `continue`；`nearest == null` 时 `attack()` 在 `:250-252` 直接 `AttackResult.miss(...)`。
- `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovement.java:1149-1173` `onDifferentLedge`（四参/五参两态）+ `peekRegionIdAt`。
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackProfile.java:84-98`：`ranged()`/`rangedAoe()`/`magic()`/`magicAoe()` 明确 `reachX=450`、`reachY=150/200/210`。
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:549-574` `reachBox()`：RANGED/MAGIC 分支用 `profile.reachX/reachY` 重写攻击盒。
- 受影响的 profile：`Route.RANGED/MAGIC`（弓/弩/枪/爪/法系全部）。

**证据链**

1. `BotAttackProfile.ranged()` 等（`:84-98`）远程/魔法 reach 450/150+，`reachBox()`（`:565-568`）对 RANGED/MAGIC 明确以 `profile.reachX/reachY` 重写攻击盒。设计意图是"站远打"，但 `seek()` 在构造候选集时先做 `onDifferentLedge(map, botRegion, mp.x, mp.y)`。
2. `botRegion` 来自 bot **自身像素**的 foothold → region（`BotNavigationGraph.findRegionId`，`:443-457`）；region 是 build 时由 `BotPhysicsEngine.canWalkAcrossFootholds`（`BotPhysicsEngine.java:470-492`）union 出的**走路连通分区**（`BotNavigationGraphProvider.buildGraph` → `buildRegions`）。上下两层平台只要端点不相接（哪怕只差 20px）必然不同 region → `onDifferentLedge == true` → 候选被 `continue` 丢弃。
3. `attack()`（`:249-252`）对 `seek.nearest == null` 直接 miss，远程攻击盒（`x∈[botX-450, botX+450]`、`y∈[botY-150, botY+210]`）本可覆盖的邻层怪被放弃。
4. 宿主契约：`AbstractDealDamageHandler.isWithinAttackBox`（`AbstractDealDamageHandler.java:1018-1052`）只判技能 WZ 攻击框 ∩ 怪物 bbox 的几何相交，`ranged`/`magic` 均无"必须同 foothold"的门槛。插件这层过滤比宿主真实规则更窄。

**确定性反例（纯几何，不依赖测试）**

bot 在 foothold F1（y=0，x∈[0,400]），怪在 foothold F2（y=-20，x=250）。两条 foothold 端点不共享，`canWalkAcrossFootholds(F1,F2)==false`（`BotPhysicsEngine:470-492`），nav graph 把它们分进两个 region → `botRegion = region(F1)`，怪在 F2 → `onDifferentLedge == true` → 候选被 `continue` 丢弃；`seek.nearest == null` → `attack()` 直接 miss；而 RANGED/MAGIC 的攻击盒（450/150~210）本可覆盖该怪。

**影响（复核修订）**

初版称"大面积、稳定表现为 bot 不打怪"，**夸大**。grind AI 的移动选目标不走 `seek()`（`GrindBrain` 用 SpotFinder/MapMobIndex + `GCMovement.move`/`canPathTo` 追怪），追到同层后正常开打。真实表现是：

1. 远程/魔法 bot 明明站在攻击盒能覆盖的相邻平台，却必须跑过去再打——**射程优势被降级为近战行为**，站桩输出与"打完就跑"的玩家观感不成立；
2. 当 chase 不可路径化（地形断链、爬链断裂）时，表现为**永不攻击该怪**。

**结论**：真缺陷，P1（影响全部远程/魔法 bot 的行为观感）。修复方向正确：把 `seek()` 的 ledge 过滤限定到 `Route.CLOSE`，或按攻击盒几何判可达。

---

### A-2 P1→**关闭（不修）** `BotAttackAction.ACTIVE` 无泄漏路径（初版判定作废）

**位置**

- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackAction.java:12-33`

**复核证据链（推翻初版）**

1. `tryRun`（`:18-32`）的 `finally { ACTIVE.remove(bot.getId()); }` 对**异常与 Error 均执行**（JLS 执行语义），正常与异常路径都自清。初版声称的"一次异常即可永久占位"不成立。
2. "removeBotFromServer 无清理入口"（`BotGeneration.removeBotFromServer :534-584` 清了 12+ 子系统唯独没有 BotAttackAction）确实如此，但**不构成缺陷**：`tryRun` 的 finally 已保证无在飞攻击时集合为空，"没有清理入口"只是"不需要清理入口"。
3. "热重载后 `currentBotCount` 归零撞号"不成立：`currentBotCount = new AtomicInteger(100)` 是进程级静态（`BotGeneration:64`），`BOT_BASE_ID=20000`（`SoloMaplingConstants:25`）。标准 Java 插件类与宿主同 classloader，不存在"类重载归零而集合残留"的路径；宿主重启则 JVM 内静态字段与集合**一起**归零，id 不复用，不会撞号。
4. `ACTIVE.add` 与 finally remove 之间的窗口即"在飞攻击"本身，正是互斥语义要保护的对象。

**结论**：初版的三条论据（异常泄漏 / 无清理入口 / 撞号）全部被代码否定。`ACTIVE` 的互斥设计自洽。初版建议的"租约 / 无条件清理"属于过度防御，不采纳。**关闭，不修。**

---

### A-3 P1 `PqActions.hitReactor` 二态返回值契约：调用方把"本拍被拒"读成"本轮结束"

**位置**

- `src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PqActions.java:409-420` `hitReactor`：把三种失败（approach 未到位 / `isClimbing` / `BotAttack.basicSwing` 被动画锁或并发互斥拒绝）压成同一个 `false`。
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiStages.java:921-931` `breakBoxInPlace`：`if (!PqActions.hitReactor(bot, oid)) return;`
- 同型调用点：`src/main/java/soloMapling/ArtificialPlayer/BotTypes/Zakum/ZakumPQBot.java:65`（`if (!hitReactor(...)) break;`）、`src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OrbisStages.java:200`（fire-and-forget，无害）、`src/main/java/soloMapling/ArtificialPlayer/BotTypes/Pirate/PiratePQBot.java:66`（`if (!hitReactor(...)) return false;`）、`OPQBot.handleStage1HitReactor:458`（转 NAVIGATE，可自愈）。

**证据链**

1. `basicSwing`（`BotAttack:57-59`）经 `BotAttackAction.tryRun`，被拒的三种原因（`ACTIVE` 并发互斥 / `runAttackAction` 的动画锁 `attackActionLocked` / debuff `blocksAttack`）在返回值 `false` 上不可区分。
2. `breakBoxInPlace` 的循环 `for (int swings = 0; swings < BOX_SWING_CAP; swings++) { ... if (!hitReactor) return; }`：任何一次拒绝直接退出整个 combo，`BOX_SWING_CAP`（6）用不完。
3. **复核修订（初版算术反例不成立）**：初版称"上一刀把锁打到 now+1000，sleep 只睡 1000+较小抖动，下一刀落在锁窗口内被拒"。实际算术相反：`ATTACK_ACTION_LOCK_MS = 1_000`（`GCMovement:410`），`BOX_SWING_BEAT_MIN_MS = ATTACK_ACTION_LOCK_MS`、beat = 1000 + [0,250) 抖动（`LudiStages:937-944`），`attackActionLocked` 判 `now < until` 严格小于（`GCMovement:476-478`），`blockingSleep` = 裸 `Thread.sleep` 只会睡过头（`BotHelpers:257-265`）。第 N 刀在 `t_N` 落锁至 `t_N+1000`，sleep(1000+j)（j≥0）后醒来时刻 ≥ `t_N+1000+j`，**锁必然已过期**。顺序自打不会被动画锁拒绝。
4. 真实触发路径：**并发互斥**（同 map 其他线程持该 bot 的攻击权，如 combat sweep 与宏 tick 撞拍）与 **debuff `blocksAttack`**（被晕/冻期间进入 combo）、以及 approach 未到位/爬绳中。任何一次发生即截断整轮 combo，箱子留到下一宏 tick（PQ bot 在 quest 内 cadence 为 normal 档 2-6s；`ZakumPQBot`/`PiratePQBot` 还会把整轮 stage 工作让出）。

**结论**：返回值契约错误为真（二态不够用），调用方把 false 当终止为真；但触发频率低于初版暗示（顺序自打不会触发）。修复方向正确：拆三态（命中 / 本拍被拒 / 目标失效），调用方对"本拍被拒"应 `continue` 重试。

---

### A-4 P1→**P3** `hasPendingInvite` 不参与 TTL（OPQ 轮换最多空转一拍，非永久卡死）

**位置**

- `src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyQueue.java:89`（`INVITE_TTL_MS = 180_000L`）、`:91-99`（只有 `getPartyInvite` 做 TTL 淘汰）、`:101-103`（`hasPendingInvite` 只 `containsKey`）。
- 实害调用点：`src/main/java/soloMapling/Environment/OpqLobbyRotation.java:150-175` `pickRetireCandidate`（`:171` 只查 `hasPendingInvite`）、`:206-217` `retire`。

**证据链（含复核修订）**

1. 宿主 `InviteCoordinator.runTimeoutSchedule`（`InviteCoordinator.java:137-154`）在 `eVal > 5` 时 `removeRequest`，即 3 分钟后宿主侧邀请消失；插件 `getPartyInvite` 用 `enqueuedAtMs + 180_000` 同步淘汰，`hasPendingInvite` 只查 key——事实正确。
2. **初版遗漏的自愈路径（推翻"永久卡住"）**：`OpqLobbyRotation.pickRetireCandidate` 的候选资格要求 `opq.getOPQBotState() == RECRUITMENT` 且无 party（`:167-171`），而 OPQBot 处于 RECRUITMENT 时 `handleRecruitment`（`OPQBot.java:315-345`）**每个宏 tick 都执行** `BotPartyLogic.checkPartyQueue` → `hasPendingInvite` + `botAcceptPartyInvite`，后者第一步就是 TTL 化的 `getPartyInvite`（`BotPartyCommands.java:77-96`，entry 过期即 null 并打印 no-pending-invite）。正在被轮换判定的那只 bot 正是每拍都在 drain 自己队列的那只。OPQBot 无 party 时 tick 必走 RECRUITMENT 分发（`handleRecruitment` 唯一 caller），lobby 低频档（约 25-48s 一拍）但**必然到达**。
3. 因此过期条目最多存活"bot 自己下一拍"；实害仅为宿主 30s 超时清掉邀请后、bot 下一拍 drain 前的一次 rotation 空转。`removeOne()` 不会累积卡死，`swaps` 相位短暂停滞后继续。
4. `FollowerBot.pollLeaderInvite`（`:153-161`）、`BotRecruitManager.pollInvites`、`tickPartyQuest`（`PartyQuestBot:302-311`）同样先 `hasPendingInvite` 再 `getPartyInvite`，均能自愈。

**结论**：`hasPendingInvite` 与 TTL 语义不一致为真，但实害降级为秒级窗口的轮换空转，**P3/观察项**。修复仍便宜（`hasPendingInvite` 内部复用 `getPartyInvite` 的 TTL 判定），可顺手做，非必需。

---

### A-5 P2→**不成立（关闭）** `runAttackAction` 在 `st == null` 时绕过 climbing 检查

**位置**：`src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovement.java:413-426`

```java
BotMovementState st = STATES.get(bot.getId());
if (st == null) {
    return attack.get();   // 不查 climbing / attackActionLocked / tickStopped
}
```

**复核证据链（推翻初版）**

1. `climbing` / `attackActionLocked` / `tickStopped` 全部是 **`BotMovementState`（GC 动态控制会话）内部状态**。`GCMovement.disable` → `finishDisable`（`:118-132`）在会话终点 `GCMovementDriver.stop(st)` + `STATES.remove(bot.getId(), st)`，且对空中 bot 延迟到落地（`disableAfterLanding`，`GCMovementDriver:324-339`）。state 不存在 ⇔ bot 不在该引擎管理下，其"是否爬绳"对该引擎无意义。
2. `markAttackAction`（`:443-454`）对 `st == null` 显式 no-op，同语义。
3. 初版所引 "df82d39 移除了 BotAttack 里的 `if (chr == null || GCMovement.isClimbing(chr)) return;`"——`isClimbing` 自身就是 `STATES.get(...) == null → false` 的会话内谓词；旧检查在无 state 的 bot 上同样恒 false，行为等价。接管设计（"由 BotAttackAction 统一"）的前提即"有 state 才有 climbing 概念"，语义自洽。
4. 唯一可构造的差异面：一个**不在 GC 会话中**的 bot 收到攻击请求时不再经过 climbing 拦截——但这类 bot（旧引擎/纯静态摆件）由各自驱动层负责动作合法性，攻击发包仅是外观动作，无可观察实害路径。

**结论**：这是会话语义豁免而非漏洞。初版"补 climbing 检查"的建议会重复防御不可能状态（违反最小改动原则）。**关闭，不修。**

---

### A-6 P2 `BotFlavor.forget` 死代码，`cooldownUntil` 只增不减

**位置**：`src/main/java/soloMapling/ArtificialPlayer/BotFlavorSystem/BotFlavor.java:48`（`cooldownUntil`）、`:96-100`（`forget`）

**证据链**

- 全库 `grep "BotFlavor.forget"`：**零调用点**（已核实 `src/main` 全部匹配仅 `BotMount.forget`/`BotChatter.forget`/`BotPetFollower.forget` 等其他类的同名方法）。
- `BotGeneration.removeBotFromServer`（`:534-584`）显式清理了 `BotChatter.forget`、`BotMount.forget`、`BotAuraState.clearBot`、`BotEnergyCharge.clearBot`、`BotComboOrb.clearBot`、`BotFinalAttack.clearBot`、`BotAttackDriver.clearBot`、`BotContactDamage.clearBot`、`BotDebuffApplier.clearBot` 等，**没有 `BotFlavor`**。
- `forget` 的设计意图（`:95` 注释"when it's torn down / converted, so the map doesn't leak entries"）与实现（无 caller）矛盾，以代码为准。

**影响**：长运行服务器上，每个曾表达过一次（`maybeExpress` 成功 dispatch 一次，`:92` `cooldownUntil.put`）的 bot id 留一条 `Integer→Long`（约 40B），按历史 bot id 数缓慢增长。无行为影响（id 不复用），纯内存卫生问题（P2 小）。

---

### A-7 P2 `DECLINED_UNTIL` 清扫只在 decline 路径执行，接受路径不清

**位置**：`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:61`（`DECLINED_UNTIL`）、`:96-97`（decline 分支 put + sweep）、`:112-115`（`sweepExpiredDeclines`）

**证据链**

1. 键是 `(botId << 32) | playerId`（`:80` `pairKey`），值 `System.currentTimeMillis() + DECLINE_COOLDOWN_MS`（180_000，`:51`）。
2. `sweepExpiredDeclines()`（`:112-115`，`removeIf value <= now`）**唯一调用点**是 `rollPartyAsk` 的 decline 分支（`:97`）。accept 分支（`:90-95`）、`BotRecruitManager.clearHandoffs`（`BotGeneration.removeBotFromServer:573` 只清 `ARMED`/`PENDING_*`/`RETURN_*` 面）都不清 `DECLINED_UNTIL`。
3. 长运行服务器上按 (bot, player) 历史对数缓慢增长。与 A-6 同属"清理面漏一个表"（P2 小）。

---

### A-8 P2 `CLEARED_DOOR_ROOMS` 是 door-room 两张表里唯一没有实例键的

**位置**：`src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiStages.java:410-423`

**证据链**

1. `DOOR_ROOM_CLAIMS` 的值 `DoorClaim(int instance, int room)`，`instance = System.identityHashCode(bot.getMap())`（`:421-423`）；`claimDoorRoom`（`:471-498`）、`roomClaimedByOtherLiveBot`（`:454-464`）都做实例比较。
2. `CLEARED_DOOR_ROOMS` 的值是 `Set<Integer> room`（`:418`），没有实例信息；`clearedRoomsFor`（`:426-428`）、`doorRoomsOutstanding`（`:442-451`）都只按 `botId`；写入点 `markRoomCleared`（`:431-434`）也只按 `botId`。
3. 场景：同一 botId 进入**新实例**（同一玩家重新开跑）时，旧房间集合不会从这条路径清理；唯一清理点是 `releaseRoomState`（`:795-800`）→ `PartyQuestBot.clearRunState()`（`LudiPQBot.releaseRoomState :92-97`），由 `questInstance` 变化触发（`PartyQuestBot:320-326`），正常路径能清。
4. 对照 `PqActions.java:2112-2134`：同类 claim 表用实例键。

**结论级别**：结构不一致（同类表一个带实例键、一个不带），依赖 `clearRunState` 正常触发才不漏；非静态可证"必然发生"的可见 bug，列 P2。修复便宜：给 `CLEARED_DOOR_ROOMS` 加 `instanceOf(bot)` 维度。

---

## B. 需实测确认（不作为结论）

1. **Companion 在 decHP 图 0 HP 的喝药路径**
   - `CompanionSurvivalPolicy.shouldUse`（`:58-66`）允许 `current < maximum`（含 0）；`useNeededPotion`（`CompanionSurvivalController:104-152`）明确无 `isAlive()` gate（`:105-107` 注释即为此设计）。
   - 宿主 `Character.applyHpMpChange`（`Character.java:8433-8501`）对 `hpchange > 0` 在 0 HP 可用（`cannotApplyHp` 只拦 `nextHp <= 0`）；`StatEffect.applyTo` 不拦正向恢复。
   - 剩余不确定点：`safeItemEffect`（`:431-441`）捕 `RuntimeException` 返回 null 后 `:121-123` 静默 `return false`（仅 `log.debug` 一次）。若某类药水的 `getItemEffect` 解析失败，会表现为"有药但永不喝"。建议先加遥测再判定。

2. **`BotAttackAction.ACTIVE` 并发拒真率**
   - finally 对 Error 也执行；A-2 已关闭。唯一存留问题：combat sweep 与宏 tick 同时攻击同一 bot 时一方吃 refused，属预期互斥。无修复项。

3. **`PqActions.walkTo` 是否会打断其他 stage 走位**
   - `walkTo`（`PqActions:129-150`）先 `clearReactorBeat` 并移除 shield（`:137-138`），随后自己 `armStageWalkShield` 并 `GCMovement.move`（`:145-150`）。它清的是**自己调用前的**状态然后重建，"清掉别人的 shield"不成立；但"`:137` 只清不建、反应堆节拍不恢复"是代码事实。是否构成可见问题取决于调用点是否同时依赖 reactor beat，属行为观测项。

## C. 证伪（经宿主源码核实不成立）

1. **"同图私聊回复降级成地图喇叭"**：`BotSM.respondSocial`（`BotSM.java:399-444`）的 `replyChannelType` 直接来自入参 `type`（`:437`），`DirectChatBridge` 把 host 的 `ChatType` 原样塞进 `ChatMessage`（`ChatMessage.java:20-26`，null 仅表示地图聊天），各 `onDirectChat` 实现原样传 `message.getChatType()`。不存在"同图置 null"的代码路径。

2. **"`BotDeath` 排除 companion 导致 0 HP 永久死锁"**：`kill()`（`BotDeath.java:93-114`）与 `needsZeroHpAdoption`（`:189-191`）确实排除 companion，但这是设计（`:98-104` 注释）：companion 的恢复由 `CompanionSurvivalController` 负责（无 isAlive gate），且宿主 `applyHpMpChange` 对正向 delta 在 0 HP 可用（`Character:8433-8457`）。"永久死锁"不成立；剩余风险见 B-1。

3. **"`BotPartyCommands` 的 `synchronized(queue)` 与宿主 `Party.joinParty` 形成 AB-BA"**：`Party.joinParty`（`Party.java:344-378`）只走 `world.updateParty`/自身锁，不回调 `BotPartyQueue`；`BotPartyInviteBridge` 只在 packet 线程 enqueue。宿主从不反向取插件的 queue 锁，不构成 AB-BA。

4. **"`markAttackAction` 写 `attackActionUntilMs` 未加锁"**：写点 `GCMovement:449-452` 在 `synchronized (st)` 内；读点 `GCMovement:471/477`（持同 monitor）、`BotMovementManager:601` 与 `GCMovementDriver:447` 均在 `runScheduledTick` 的 `synchronized(entry)`（`GCMovementDriver:148-165`）内。同一 monitor，无竞争。不成立。

5. **"`InviteCoordinator.answerInvite` 与 `removePartyInvite` 有竞态"**：宿主 `answerInvite`（`InviteCoordinator.java:106-125`）是无锁 `ConcurrentHashMap` 的 `get` + `equals` + `removeRequest`；插件在 `synchronized(queue)` 内 get→answer→remove（`BotPartyCommands:79-111`、`:143-149`）。宿主不取插件锁，无 AB-BA。不成立。

6. **"`BotAttack.basicSwing` 缺少 `markAttackAction`"**：`df82d39` 之后四个 swing 方法发包后都有 `GCMovement.markAttackAction(chr)`（`BotAttack.java:85` 等）。不成立。

7. **"`PqActions.hitReactor` 未校验 `basicSwing`"**：`:417` 已是 `if (!BotAttack.basicSwing(bot)) return false;`。真正的缺陷在调用方把 false 当终止（A-3），不是这里没校验。

## D. 处理顺序（复核修订版）

1. **A-1**：把 `seek()` 的同 ledge 过滤限定在 `Route.CLOSE`（或以攻击盒几何判可达）。唯一影响全部远程/魔法 bot 行为观感的缺陷。
2. **A-3**：`hitReactor` 返回三态（命中 / 本拍被拒 / 目标失效），`breakBoxInPlace`/`Zakum`/`Pirate` 调用方对"本拍被拒" `continue` 重试。
3. **A-6 / A-7 / A-8**（一个 commit 可带齐）：`removeBotFromServer` 补 `BotFlavor.forget(chr.getId())` 一行；`DECLINED_UNTIL` 的 sweep 挂到 accept 分支或 `clearHandoffs`；`CLEARED_DOOR_ROOMS` 加实例键。
4. **A-4**（可选，P3）：`hasPendingInvite` 内部复用 `getPartyInvite` 的 TTL 判定，一行改动。
5. **A-2 / A-5：关闭不修**（初版误判，见各自复核注记；补防御属过度设计）。
6. **B-1**：给 `CompanionSurvivalController` 的 `effect == null` 分支补 `log.warn` 与"无药可喝"遥测，再判定是否需要修。
