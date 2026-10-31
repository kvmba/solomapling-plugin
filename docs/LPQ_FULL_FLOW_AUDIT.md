# 玩具塔（Ludi PQ / 101 组队任务）全流程 BUG 审计

日期：2026-10-28　分支：optimize/performance-v2 @ 0d0c9f3

## 一、结论总表

| # | 阶段 | 根因 | 后果 | 级别 |
|---|---|---|---|---|
| 1 | 2 | 陷阱箱 2200002 从不打（LudiStages.java:156） | 塔内 11 箱只产 11/15，阶段 2 算术无解 | 硬卡死 |
| 2 | 7 | 三只平台怪在右侧无入边台，打不到 → 其 4001156 掉落无法落入箱子触发框 | 阶段 7 永久 0/3，箱子不触发，Rombard 永不召唤 | 硬卡死 |
| 3 | 9 | fightBoss 只有 seekAndAttack，从不召唤 Alishar | 本局无法结束 | 硬卡死 |
| 4 | 5 | 门房分派表按职业判能力，与跳跃几何不符 | 法师占 501 上不去，队友被挡在外 | 硬卡死 |
| 5 | 5 | 分派表把 501/506 的箱子锁死（不是 doorRoomsOutstanding） | 全队上限 20/24，阶段无法上交 | 死锁 |
| 6 | 3 | 先拆箱再打怪，不等本箱 3 只清完 | 一次放满 32 只 Blocktopus | 行为缺陷（**不修**） |
| 7 | 8 | 公告把计划总数当还差几个 | 玩家照做则 playersOnCombo==6，白跑一次 | 协作缺陷 |
| 8 | 9后 | workStage 返回 true 直接退本 | 放弃奖励关 922011000/922011100 | 流程缺失 |
| 9 | 全局 | 孤儿掉落探测不校验可达性 | 每 6s 一次 8s 空转 | **不修**（见下） |
| 10 | 5 | 隐身只在 tick 头开一次 | 箱内 4 连击整段裸奔 | 潜在致死 |

---

## 二、逐阶段

### 阶段 0 · 大厅 → 进本

- 招募图 221024500、等级 35–50、队伍 5–6 人：PqRecruitPoints 的 LudiPQ 行与 LudiPQ.js 的 getEligibleParty 一致。
- LudiPQBot 构造里 BotGeneration.adoptPrivateClient（LudiPQBot.java:40）是必需的：NPC 脚本用 client.getPlayer() 解析说话人。
- 无 BUG。

### 阶段 1 · 922010100（25 张）

- WZ 实数：9300006 × 10 + 9300005 × 15 = 25 只；drop_data 两者都 100% 掉 4001022，正好 25 张。
- 25 个怪位全部可达（st00 出发 4–38 条边）；NPC 2040036 在 (-157,-192)，2 条边可达。
- 无硬性问题。隐患：mobTime=-1 不刷新，任何一张证物没人捡、3 分钟消失，本阶段永久差 1 张，只能靠第 9 条的孤儿探测兜底，而该探测本身有缺陷。

### 阶段 2 · 922010200 塔 → 922010201 陷阱房 —— 硬卡死 1

根因（LudiStages.java:150-192）：

```java
// "The room's twelfth box, the trap (2200002), is left strictly alone"
int box = nearestOwnedBoxOid(bot, LudiPqData.BOX_STAGE2);   // 只找 2202003
```

breakTowerBoxes 只处理 BOX_STAGE2 = 2202003，从不碰 2200002。

证据链：

| 事实 | 出处 |
|---|---|
| 922010200 只有 11 个 2202003 + 1 个 2200002 | 逐条解析 922010200.img.xml 的 reactor 段 |
| 每个 2202003 必掉 1 张 4001022 | reactordrops (121, 2202003, 4001022, 1, -1)，Randomizer.nextInt(999999) < 1 恒真 |
| 阶段 2 turn-in 要 15 张 | npc/2040037.js 的 cm.haveItem(4001022, 15) |
| 陷阱房有 4 个 2202003 | 922010201.img.xml：(142,-804)、(-133,-1460)、(149,-3113)、(132,-2209) |
| 陷阱箱把全队拉走 | reactor/2200002.js 的 rm.warpMap(922010201)，落到 MapleMap.warpEveryone |
| 塔内没有可打的怪（只有 2 个 NPC） | 922010200.img.xml 的 life 段 |

11 + 4 = 15，陷阱箱是这 15 张里不可省略的一部分；代码注释自己写着 trap room 的四箱是 15 张的一部分，却把箱子标成 left strictly alone，自相矛盾。

后果：只要真人不主动去打陷阱箱，机器人队伍永久停在 11/15，workTrapRoom 那套完整的破箱/拾取/出房逻辑永远不会被触发。这不是慢，是算术无解。

旁证（回归事实）：origin/optimize/performance 上的 99cf7de 已按同一根因修过（新增 BOX_STAGE2_TRAP，塔箱 findAllReactorOids(BOX_STAGE2).isEmpty() 后再弹陷阱箱），但该提交不在当前分支（git merge-base --is-ancestor 99cf7de HEAD 为 false；HEAD 里 BOX_STAGE2_TRAP 已不存在）。属合并遗漏。

可达性核查：陷阱箱本身可达——生产图实测 st00 → (168,-2123) 在全部 5 种档位下均可达。所以是没写，不是写不进去。

### 阶段 3 · 922010300（32 张）—— 行为缺陷 6

- 数量吻合：8 个 2201001 × 3 只 + 8 只预置 9300007 = 32 只 = 32 张。
- 8 个箱位在 3 种档位下全部可达（fails=0）。
- 根因（LudiStages.java:377-397）：

```java
int crate = nearestBoxOid(bot, LudiPqData.BOX_STAGE3);
if (crate >= 0) hitReactorRotate(bot, crate, ...);   // 4 连击，秒刷 3 只
PqActions.seekAndAttack(bot);
```

没有本箱 3 只没清完就别开下一箱的门。机器人绕场把 8 个箱子全打爆，一次放满 24 只（加 8 只预置 = 32）同时在场。

- 后果：Blocktopus PAD 115 / MAD 125，35–50 级队伍被围杀、狂喝药。能清，但死亡率和通关时间显著劣化。正确形状应是 killableMobPresent 为真时不拆新箱。

**处置（复核后）**：不修。需求方确认一次放满 32 只可接受——它不是卡死，只是增加药耗与通关时间。保留原状，避免为“体验优化”改动已验证可跑的清怪循环。

### 阶段 4 · 922010400 + 401–405 门房（6 张）

- 数量吻合：401/402/403 各 1 只 9300008，404 两只 9300014（WZ 同一坐标重复两条，实际两只），405 一只 9300014，共 6 只 = 6 张。
- 可达性：主图 5 个 in01..in05 全部有路（5–6 条边）；五个房间 st00 → out00 全部有路（10 条边），室内 st00 → 怪 1–5 条边。
- 无硬性 BUG。已知瑕疵：claimDoorRoom（:477-504）只在 bot.getMapId() == mine.room() 时保留 claim，在主图上每 tick 都从 roomFirst 重扫，于是多个 bot 同时抢最小号未占房，可能两人走同一扇门。注释已承认 best-effort anti-crowding, not locks。后果是白跑，不是卡死。

### 阶段 5 · 922010500 + 501–506（24 张）—— 硬卡死 4、5

24 张 = 6 个房间 × 4 箱，一个房间都不能少（逐房核对：501–506 每房正好 4 个 2202003）。

#### (a) 分派表用职业代替实际跳跃几何（LudiStages.java:735-745）

```java
if (room == STAGE5_ROOM_FIRST)
    return MovementStylePolicy.hasTeleportSkill(...) || MovementStylePolicy.hasHasteSkill(job);
```

in01（922010500 的 top door）挂在 fh420 y=-1291，其下 fh415/416 y=-1276，再下 fh405/408 y=-1176，要上的台阶是 100px，中间没有 foothold / rope / portal。

生产图实测（st00 (-208,-215) → in01 (-145,-1291)）：

| 档位 | 物理升程 | 结果 |
|---|---|---|
| 110/110（35–50 级战/法/弓基线） | (555x1.10)^2/4000 = 93px | NO PATH |
| 115/115（贼的基线） | 102px | 11 条边 OK |
| 115/123（队伍有轻功，+20 跳） | 117px | 9 条边 OK |
| 140/123、148/123 | 117px | 9 条边 OK |

由此产生两个方向的错误：

1. 法师被判能进，实际进不去。hasTeleportSkill() 只是职业表（MovementStylePolicy.java:60），而导航层根本没有 teleport 边：BotNavigationGraph.EdgeType 只有 WALK / JUMP / DROP / CLIMB / PORTAL；GCMovement.teleport() 只被 EngageBeat（刷怪，EngageBeat.java:211,283）调用，BotNavigationManager 从不使用。法师 jump=110 → 93px < 100px → walkTo 每次都失败，enterDoorRoom 返回 false，bot 留在主图。而 claimDoorRoom（:477-504）每 tick 都会重新扫到 501（stage5RoomAdmits 对法师为 true）并把 claim 重新写回；roomClaimedByOtherLiveBot 又把被活着的 bot 持有的 501 判给其他 bot，于是 501 被一个永远进不去的 bot 长期占位。
2. 有轻功队伍里的非贼被判不能进，实际能进。115/123 实测 9 条边可上，但分派表只认贼/法师，战士弓手海盗全被拒，501 无人 claim。

再叠加 506 只认隐身职业（贼/海盗），5 bot + 1 真人的常见配置很难同时覆盖 501 与 506。

#### (b) doorRoomsOutstanding 与 claimDoorRoom 的判据不一致（:448-457 vs :492-504）

```java
// claimDoorRoom：会跳过 bot 进不去的房间
if (roomFirst == STAGE5_ROOM_FIRST && !stage5RoomAdmits(bot, room)) continue;

// doorRoomsOutstanding：只跳过 cleared / 被别人 claim，从不问我能不能进
for (int room = roomFirst; room <= roomLast; room++) {
    if (cleared.contains(room) || roomClaimedByOtherLiveBot(bot, room)) continue;
    return true;                       // 永远还有活
}
```

**后果（修订）**：这与交货无关。doorRoomsOutstanding 只有一个调用点 LudiPQBot.isFollowingLeader，而 isFollowingLeader 只被 PartyQuestBot.followLeaderIntoNextRoom 使用，作用仅是决定要不要跟着队长穿门。交货走的是完全独立的路径：workStage 在 stage 5 时调 sneakDoorRooms 或 workStage5FromMainRoom，claimDoorRoom 返回 -1 时直接执行 deliverAndPark(bot, 5)，不看 isFollowingLeader。所以原文的“24/24 也交不掉”不成立。

**真正的死锁**：分派表本身。法师被判可进 501（hasTeleportSkill 为真），但其 jump 档位是 levelJumpStat(level)，35-50 级恒为 110，实测 110/110 对 in01 为 NULL，进不去。而 claimDoorRoom 每 tick 把 501 重新写回（roomClaimedByOtherLiveBot 只跳过其他 bot 的 claim，自己的 claim 不挡自己），于是队友始终看到 501 被活着的 bot 占着，能进的贼反而被挤走。501 那四箱因此永远拿不到，全队上限 20/24，2040040.js 的 haveItem(4001022, 24) 永不成立。

doorRoomsOutstanding 的判据不一致是同一个 bug 的**症状**：它让 bot 误以为自己还有活，因而不跟随队长。修顺序上先修分派表（根因），再对齐这个判据（一致性）。
#### (c) 隐身在 tick 头只开一次（潜在致死 10）

sneakDoorRooms（:668-670）每 tick 只在开头 showBuff(hide) 一次；随后 hitReactorRotate(onBeat=false) → breakBoxInPlace 在同一 tick 内连打 4 下（:927-937，BOX_SWING_CAP=6，600–850ms/下）。第一下 BotAttack.basicSwing 就 BotAuraState.cancelHidesForAction，之后约 3 秒是裸体站在 PAD 999 房间里。

逐箱核对当前 WZ：每个箱子离最近守卫都超过 150px（例如 (-83,-630) 距 (-80,-441) dy=189；(92,-1064) 距 (55,-862) dy=202；(-81,-1459)、(-70,-3535) 更远），所以当前碰巧不死。但注释宣称 one box per tick，与代码的 4 连击不符，守卫位置一变就会死。

- 可达性核查：6 房 24 箱全部可达（1–16 条边）；主图 in02..in06 有路，只有 in01 对 110 跳无路（见上）。

### 阶段 6 · 922010600 塔的迷路

- nextRungUp 按 WZ 的 tn 读真实梯级（诱饵 st00 被排除），逻辑正确。
- 生产图实测：st00 → next00 是完整的 33 条边计划（PORTAL 4,7,8,12,15,17,20,23,27,29,34,36,38,41,46 加若干 JUMP/DROP），逐级也都有 1–8 条边。
- 寻路本身无 BUG。climbFloorY（LudiPQBot.java:271-280）只喂给 fallback 的 previousY，而 nextRungUp 用的是实时 y，属冗余但无害。
- 流程瑕疵：若队伍已穿过 next00（6stageclear 置位）而某 bot 还在塔里，workStage 的 cleared 分支会让它去 stageNpcId(6)=2040041（塔底 NPC，坐标 (-139,-252)）等；下一 tick 的 follow 才把它带出。白跑一趟，不卡死。

### 阶段 7 · 922010700（3 张）—— 硬卡死 2

**根因（修订）**：这一阶段不是打箱子阶段。三个 2201002 是 item 型反应器（WZ event type=100，要 1 张 4001156），而它自己的脚本不掉这张卡，卡片来自它召唤的 Rombard（9300010）。真正让箱子触发的是三只平台怪的掉落物。

链路（WZ 逐条实测）：

| 箱子 | 触发框 x / y | 对应怪 | 掉落落点 | 落入框内 |
|---|---|---|---|---|
| (228,-1037) | x[161,266] y[-1053,-1030] | 9300170 (224,-1044) | (224,-1037) | 是 |
| (230,-1263) | x[163,268] y[-1279,-1256] | 9300169 (219,-1276) | (219,-1263) | 是 |
| (230,-1535) | x[163,268] y[-1551,-1528] | 9300171 (228,-1543) | (228,-1535) | 是 |

三只怪各 100% 掉 4001156（drop_data，chance=999999），calcDropPos 把它重新落到同平台，正好在箱子触发框内。于是宿主 MapleMap.activateItemReactors 命中该 type=100 反应器，5 秒后 ActivateItemReactor.run() 调 hitReactor，箱子的 act() 执行 spawnMonster(9300010, 1, -211)。

宿主 NPC 2040042 的台词直接印证本设计：You need ranged personnel here. They must kill the three Ratz, which will trigger something.

**为什么打不到**：三只怪站在右侧 x≈183-265 的窄台上，该台在导航图里没有任何入边。右列 r3/r6/r7 之间只有下行边（DROP 与下降弧），唯一入边来自 y=-4365 的封闭顶台，而该台在 VRTop=-2160 之外。物理侧交叉验证：从 y=-774 向右 simulateWalkOffLanding 一律 null，向左可落 y=-709，即只能从上方掉入。

实测（5 种档位，从 st00 或任一左侧台出发，findPath 全部 null）：

```
110/110  st7boxes=false/false/false
115/115  st7boxes=false/false/false
115/123  st7boxes=false/false/false
140/123  st7boxes=false/false/false
148/123  st7boxes=false/false/false
```

**后果**：机器人走不到那个台，杀不了怪，于是 4001156 不产生，箱子永不触发，Rombard 永不出现，3 张证物为 0。纯近战队伍即使能站到对面也打不出伤害（攻击层的 onAttackableSurface 会拒绝跨平台目标）。本阶段依赖远程职业从左侧台隔空打怪，属于地图设计要求，不是插件缺陷。

**修复**：gatherPasses 的 stage 7 分支改为杀平台怪、等掉落物触发箱子、再杀 Rombard，并明确不拾取 4001156（一旦捡起，箱子就永远不会触发）。reachableOnly 不仅检查怪物脚下的平台，还为远程/魔法寻找可走到、能命中该怪的射击点；当前 AUTO 选择确实包含该怪时才撤回自己的追击订单并驻留。射击范围由攻击层计算，移动层一次遍历可达导航图选择地面点；仅在水平空间允许时留出到达容差，不缩小平台固定的合法 Y 范围。

**攻击层同时要放开一处**：BotAttackDriver.onAttackableSurface 原本对所有攻击都套用近战平台判定（onDifferentLedge），于是远程职业也拒绝这三只跨平台的怪。但投射物本来就能跨越没有 foothold 链的缺口，2040042.js 的台词也明说需要远程。现改为按 route 区分：RANGED 与 MAGIC 豁免平台判定，只保留 reach box 的距离约束；近战仍保留两道。nearestMob 使用同一 route 的平台规则，并优先朝向当前能命中的目标，避免被更近却不在攻击框内的怪抢走朝向。驻留判定和真正挥击共用 AUTO 的 profile、朝向及命中数量上限选择。

**阶段 7 分支必须排在通用 killable 分支之前**：三只平台怪本身就是 killable，若先走 killableMobPresent 分支，就会用无约束的 seekAndAttack 去追它们，reachableOnly 根本执行不到。


**一处必须记住的陷阱**：findAllReactorOids 的过滤条件是 isActive()，而 2201002 有 state 0（type=100）和 state 1（type=999）两个有效状态，isActive() 永远为 true。判断箱子是否还欠卡必须用 getReactorType() == 100，否则阶段 7 永远走不到交货分支。

### 阶段 8 · 922010800（组合箱）

#### 货箱式台阶寻路：正常

这是本轮重点核查项，结论是能正常跳跃寻路：

| 核查项 | 结果 |
|---|---|
| 9 个 CRATE_SPOTS 从 st00 出发，5 种档位（110/110、115/115、115/123、140/123、148/123） | 全部可达，11–13 条边，全为 JUMP 链 |
| 9x8 = 72 组箱到箱切换 | fails = 0 |
| 每个 spot 是否落在对应脚本 area 内 | 是，spot i → area i 一一对应 |
| 台阶间距 | 39–42px，110 跳（93px）足够 |

边链实例（st00 (-190,454) 到 (-217,-220)）：JUMP r39→r37 (87,454)→(138,399)，r37→r35，r35→r33，r33→r31，r31→r29，r29→r27，r27→r25，r25→r23，r23→r21，r21→r19，r19→r16，r16→r10，r10→r5，共 13 条边。

唯一的隐含风险：跳跃边按移动档位分键烘焙。若队伍里轻功贼中途加入/离开，BotMovementProfile.fromCharacter 的 jump 桶变化会触发新图重烘；重烘期间 tryExecuteJump 会以 jump-graph-warmup 为由拒绝起跳（BotNavigationManager.java:523-531）。阶段 8 每一步都是 JUMP，此时会集体停在第一个台阶上直到烘焙完成。不是卡死，但会明显停顿。

#### 协作层 BUG 7

LudiPQBot.java:294-298：

```java
int wanted = LudiStages.takeCrate(getChr(), botIndex());   // 返回 wanted.size() == 5
...
BotMessages.get("pq.ludi.stage8", wanted)                  // 文案：还差{0}个箱子要人站
```

takeCrate 返回的是计划总数 5（LudiStages.java:1122 return wanted.size()），不是还差几个。而 5 个 bot 时 botIndex()=0..4，crateAt 尾优先把 5 个目标箱全部占满（crateAt(wanted, 0..4) = wanted[4..0]）。

- 文案 Stage 8: {0} crates need a body - I am on one, take another! / 中文 还差{0}个箱子要人站——我占了一个，来个人站另一个！ 会让玩家再站一个，于是 playersOnCombo == 6，2040043.js 走 It looks like you haven't found the 5 boxes just yet 分支，组合永远不生成。
- 只有 4 个 bot 时，空出的是 wanted[0]，文案同样不告诉玩家是哪一格（9 选 1）。

补充：首个猜测阶段用 guessCrates()（末 5 格），与真组合相同的概率仅 1/C(9,5) = 1/126；首次对话几乎必然 showWrongEffect，玩家需要再对话一次才会判定，机器人不会告知这一点。

另一个正确性隐患（宿主脚本侧，但由机器人布局触发）：2040043.js 统计 playersOnCombo 时对全实例玩家用 map.getArea(y).contains(pos)，不校验玩家是否在同一张图。LPQ 各阶段共用坐标空间（y≈-176/-145 一带多图都有台子），掉队 bot 的坐标可能被算进 stage 8 箱区，计数/图案被污染。bot 越多、越容易有人掉队，风险越高。

### 阶段 9 · 922010900（Boss）—— 硬卡死 3（根因修订）

**根因**：fightBoss 只有 PqActions.seekAndAttack(bot)，从不召唤。

**补充一：晶体打一次就召唤，不需要投物**。2201003 的 WZ 只有 state 0（type=100，要 1 张 4001022）和 state 1（1x1 空 canvas，无 event）。ReactorFactory 读到 state 0 的 event 后，nextState=1，isInEndState 判据是 nextState < 当前 state（1 < 0 为假），于是走 else 分支，state == getNextState 成立，直接触发 act()。所以武器击打一下就会 spawnMonster(9300012)，投物只是另一条可选路径，不是必要条件。

**补充二：本服务端没有 Ratz 到 Alishar 的联动**。全仓只有一处 spawnMonster(9300012 ...)，就是 scripts/reactor/2201003.js。LudiPQ.js 的 monsterKilled 与 allMonstersDead 都是空函数。但 NPC 2040044 的台词写的是 Kill the Ratz on that platform to reveal the Alishar，与实现矛盾。结论：怪不是召唤入口，晶体才是；NPC 台词在本服务端属于误导文本。

宿主真实机制（五处互证）：

| 事实 | 出处 |
|---|---|
| 晶体 2201003 是 item 型（type=100），要 4001022 x 1 | wz/Reactor.wz/2201003.img.xml 的 event/0 |
| act() 在 922010900 召唤 Alishar | scripts/reactor/2201003.js 的 rm.spawnMonster(9300012, 941, 184) |
| Alishar 掉奖杯 | drop_data (9300012, 4001023, 999999) |
| turn-in 收 1 个奖杯 | npc/2040044.js 的 cm.haveItem(4001023, 1) |
| 无 Ratz 触发链 | LudiPQ.js 的 monsterKilled / allMonstersDead 为空 |

几何：Ratz 9300006 在 (588,-3)，晶体在 (599,0)，两者同在 x 554..636 / y=0 小台；st00 (-171,178) 落在 y=184 的 forbidFallDown 全宽行。

实测（5 种档位）：

```
st9ratz=false  st9crystal=false     （全部档位，findPath 返回 null）
```

边表：晶体台 R3 的入边为 0，与之相关的边全是 R3 到 R6 的下行；184 到 0 没有任何上行边。物理侧交叉验证：从 y=74 台向右走可落 y=184，向左可落 y=120，但从 184 无法上行到 0。

**后果**：

- seekAndAttack 会把 Ratz 当目标，canPathTo 为 false 时 seekPathableTarget 退回最近怪，仍向 (588,0) 发 move，驱动无法规划，4 秒无进度放弃，下一 tick 重来，整局在台下反复原地起意。
- 挥击也打不到：BotAttackDriver.onAttackableSurface 会因 GCMovement.onDifferentLedge 把异层怪排除（Ratz 非 boss，不享受豁免）。
- 晶体召唤本身对机器人是可做的：PqActions.hitReactor 无服务器距离校验，插件侧的 approachReactor 90/70 像素只是姿态门。

**修复**：fightBoss 改为先用 nearestArmedReactorOid 找到仍在等待的晶体（判据是 getReactorType() == 100，不能用 isActive()），仅在当前距离及平台允许时用 hitReactorIfInReach 召唤。这个检查不下发移动、不设置行走保护，也不取消其他生产者的订单；不可达晶体不会阻碍后续 seekAndAttack(bot, true) 转火 Alishar。

### 结算 / 奖励关 · 922011000 → 922011100 —— 流程缺失 8（根因修订）

- workStage 在 stage 9 cleared 时 return stage >= 9（LudiPQBot.java:119），tickPartyQuest 立刻 returnToLobby（PartyQuestBot.java:326-330）。
- 但 LudiPQ.js 的 clearPQ 是 warpEventTeam(922011000) 加 1 分钟奖励关，之后 scheduledTimeout 再送 922011100。
- isInsideQuest 又不含这两张图（LudiPQBot.java:53-55），所以即便被传过去，tickLobby 也会把它送回大厅。
- 后果：机器人 100% 放弃奖励关（922011000 有 36 个 2202004 蓝箱加 NPC 2040045）。玩家少一个队友；若玩家也掉线，实例会被 changedMap 提前 end。

**奖励关的正确玩法（补充）**：这 36 个箱子的脚本是 rm.sprayItems(true, 1, 30, 60, 15)，打破即喷出奖励。奖励归队伍里的真人，**bot 只负责开箱，绝不能拾取**。所以 steppedOnLootIds 在这两张图必须返回空数组，也不得调用任何 loot。

**传送链路已核实**：warpEventTeam 用 getPlayerList()，只含注册在实例里的角色。bot 进入事件地图时，Character.changeMap 会自动调用 mapEim.registerPlayer(this, false)，而宿主 registerPlayer 明确支持 headless bot（HostHooks.isArtificial 分支）。所以 scheduledTimeout 的团队传送会带上 bot，不需要插件自行 registerPlayer。

**修复**：isInsideQuest 扩到 922011100；新增 breakBonusBoxes 只破 2202004 不拾取；workStage 在 stage 9 后不再 return true；steppedOnLootIds 在奖励关返回空。922011100 没有 next00 也没有箱子，bot 只需留在队里等传送。

## 三、跨阶段：追怪 / 移动 打架

### 1. 孤儿掉落探测把 bot 拖向看得见拿不到的点 —— 9

PqActions.sweepOrphanDropOutside（:1773-1816）只过滤 botCanLoot / isFloorLoot，从不做 canPathTo，然后用房间对角线尺度（roomReachPx，塔图约 4600px）把全房间最近的一张通行证当目标 walkForPickup。

- 在 922010700 上，右侧台/封闭台的掉落正好会被它选中；
- 驱动规划失败，MOVE_NO_PROGRESS_MS = 8s 放弃，下一 tick（本地 260px 无命中）再次探测（ORPHAN_PROBE_MIN_MS = 6s 节流），于是形成每 6s 一次的原地起意—放弃循环；
- 期间 GCMovement.isMoving 为真，waitNearStageNpc / handItemsToLeaderAfterStage 的 yieldsToLiveWalk 一律让路。

后果：bot 既不去交货也不去打怪，表现为站在台边反复小步，直到掉落 3 分钟消失（item_expire_time）。这是追怪/移动打架里最实的一条，根因是目标选择没有可达性门。

**处置（复核后）：不修**。理由见第五节。这里保留原分析作为已知代价记录：加可达性过滤会让探测失去覆盖整房间的能力，而实测代价（8s 放弃加 6s 节流）可控，且阶段 7 与 9 的空转痛点已由 reachableOnly 单独解决。

### 2. 追怪优先级压过交付岗

waitNearStageNpc / spreadNearStageNpc 故意不 arm shield（注释：怕整段等待变成战斗黑屏），而 seekAndAttack 的 seek box 是 900 x 3200（SEEK_RANGE_X / SEEK_STACK_RANGE_Y）。于是只要房间还有一只活怪，seekAndAttack 的 move 就一直存在，yieldsToLiveWalk 让公园/交付每 tick 都跳过。

当前各阶段都用 killable 清空作为交付门，逻辑上闭得上；但它把能不能交货绑在能不能把怪杀干净上。阶段 7 恰好杀不干净（箱不可达、Rombard 不召唤），于是和第 1/2 条叠加成永久卡死。

### 3. walkTo 与 reactorBeat 的隐式时序契约

walkTo（:137）第一件事 clearReactorBeat；approachReactor（:520-535）在 onBeat 时又 armReactorBeat。两者混用时，同一 tick 内交付 walk 和箱子 beat 会互相取消。

当前调用顺序（先箱后交货、交货前先判 quiet）使它不致命，但这是隐式契约，没有断言保护；后续任何在箱 work 之后插入 walkTo 的改动都会静默破坏打箱节奏（breakBoxInPlace 的 4 连击会卡在第 1 下）。

### 4. 出口被不可达掉落压住（正确但会放大第 1 条）

exitTrapRoom / exitDoorRoom 用最严格的 standsDownForLiveMove = GCMovement.hasMoveTarget，只要还有活 move 就不出口。注释明说这是故意取舍（宁可等 3 分钟也不孤儿化证物）。所以它不是 bug，但和第 1 条叠加时，一个不可达掉落 = 出口被压 3 分钟。

### 5. 已核实不存在的打架（避免误报）

- seekAndAttack（:1152-1168）与 hitReactor（:521-534）都在 SeekState 锁内串行，不存在追怪和打箱同时改 moveTarget。
- walkTo 的 CountDownLatch 有 WALK_BLOCK_CAP_MS = 1.2s 上限，且 !GCMovement.isMoving 提前跳出（:158-180），不会因一次长走冻死 macro tick。
- nudgeStageTick 走 ExecutorServiceManager.runAsync（:641-649），不会在驱动线程上反向拿 macro 锁。

---

## 四、核实不是 BUG 的项

1. 货箱式台阶（阶段 8）：9 箱位加 72 组互跳、5 种档位全部可规划；台阶 39–42px，110 跳够用。能正常跳跃寻路。
2. 阶段 3 的 8 个箱位：3 种档位全部可达（fails=0）。
3. 阶段 6 的梯级链：st00 → next00 33 条边完整，逐级也有路；h0NN 的 tn 解析正确。
4. 阶段 1 / 4 的 NPC 与门：NPC 与 5 个门房入口/出口全部有路；stageNpcId 公式 2040036..2040044 与 WZ 的 NPC 布局完全对上（含 2040047 出口 NPC 的区分）。
5. 阶段 2 的 11 + 4 个箱子：全部可达（陷阱箱 (168,-2123) 也测过，可达）。所以第 1 条是没写，不是写不进去。
6. hitReactor 的 ±90/±70 IN_POSITION 盒子：枚举全部箱位核对，没有任何箱子在盒子内包含别的台子，不存在隔层打箱。

---

## 五、修复优先级与落地结果（按根因，不做补丁）

| # | 阶段 | 根因动作 | 结果 |
|---|---|---|---|
| 1 | 2 | 补 BOX_STAGE2_TRAP；门条件用 findAllReactorOids(BOX_STAGE2).isEmpty()，不能用 box < 0（队友持有时也为 -1，会把人从箱子旁拉走） | 已修 |
| 2 | 7 | 不是打箱子：改为杀三只平台怪，让 4001156 落进触发框自动武装箱子，再杀 Rombard；明确不拾取 4001156；追怪用 reachableOnly | 已修 |
| 3 | 9 | 先用 getReactorType()==100 找到仍在等待的晶体并 hitReactor 召唤，再 seekAndAttack(bot, true) 补伤害；打不到就等真人 | 已修 |
| 4 | 5 | 分派表保留原判据 hasTeleportSkill 或 hasHasteSkill（已确认符合设计：501 的门槛是室内垂直机动，不是 in01 的台阶）；补进门失败释放 claim | 已修 |
| 5 | 5 | doorRoomsOutstanding 套用与 claimDoorRoom 相同的资格过滤（一致性修复，非交货主因） | 已修 |
| 6 | 3 | 加 killableMobPresent 门，避免一次放满 32 只 | **不修**（需求方确认可接受） |
| 7 | 8 | 公告改为还差 5 减已在箱上的机器人数；为 0 时用独立文案明确告知玩家不要上箱 | 已修 |
| 8 | 9后 | isInsideQuest 扩到 922011100；新增 breakBonusBoxes 只破 36 箱不拾取；workStage 不再在 stage 9 后 return true | 已修 |
| 9 | 全局 | sweepOrphanDropOutside 加 canPathTo 过滤 | **不修**（见下） |
| 10 | 5 | 隐身由物理线程单独在接触伤害检查前更新（110px 守卫行带），宏节拍不再启停；安全箱台和离图后取消自身维护的隐身 | 已修 |
| 11 | 9 + 全局 | 追击只在 `!canReach` 时前进，而 reach box 是对称的——距离 0 也算"能打"，于是远程职业一路走到 boss 身上；给 projectile kit 定 standoff（射程一半）并在被逼近时后撤，近战仍 0 | 已修 |

### 第 11 条：远程职业贴脸（根因）

**现象**：玩具塔 101 boss 阶段，弓/弩/镖/法师像近战一样走到 Alishar 身边开火，被接触伤害打死。

**根因链**（三处叠加，都在 PQ 战斗层）：

1. `BotAttackProfile.ranged()/magic()` 只给 `reachX=450`（上限），`BotAttackDriver.reachBox` 把它当成以 bot 为中心的 ±450 矩形——**射程没有下限**，距离 0 也"合法命中"。
2. `PqActions.seekAndAttack` 的唯一停止条件是 `inReach`，而它此前**只在 `reachableOnly` 阶段（LPQ 7/9）且 `isSettled` 时**才生效；其余所有 `seekAndAttack` 调用方（Kerning 5、BossRush、Papulatus、Dojo、Pyramid…）每拍都继续 `moveTracking` 走到怪物脚下，实测 HUNTER 停在 `dx=1`。
3. boss 命中会走 `BotContactDamage.applyDamage → beginKnockback` 把 bot 打成 `inAir`，`GCMovement.isSettled` 立刻为 false，于是"已到位"分支被跳过，落回追击段——**每次挨打的击退都被翻译成"走回 boss 脚下"**。实测 `canReach=true` 却下发了 `moveTarget=(941,184)`（boss 的像素）。

**修复**：

- `BotAttackDriver.standoffX`：近战 0，投射类 `reachX/2`（与刷怪层 `EngageBeat.kiteIfTooClose` 同一条规则）。
- `PqActions.seekAndAttack`：`inReach` 即停（不再限 `reachableOnly`）；空中（跳跃/击退）保持既有落点订单、绝不重新瞄准怪物像素；目标进入 comfort band 时按 bot 自身 x 后撤一步（不跨层、不越过台边）。
- 进度计时不再把 `inReach` 当"进展"（那些拍已提前返回）——否则一个原地互射的目标永远不会被放弃。

**验证**（真实 922010900 + 9300012）：修复前静态 boss 停在 `dx=1`、移动 boss 时 `dx=0`；修复后 500 拍全程 `tightest ≥ standoff-40`（225-40）。全量 1784 通过。

### 后续追加的两条（阶段 5）

| # | 阶段 | 根因动作 | 结果 |
|---|---|---|---|
| 12 | 5 | 接触伤害的防穿透扫掠盒不再跨过非连续位移：无观测区间与任何传送都作废"上一像素"锚点（原实现让隐身中的飞侠/海盗在 189px 外的安全箱台上吃到守卫 999×0.5 的一击，约 500 血） | 已修 |
| 13 | 5 | 没有 隐身术/橡木伪装 的职业不得把守卫层当目标：在追击的"目标筛选"处排除，而不是下单后再取消 | 已修 |

### 第 13 条的几何依据

WZ life 表：922010500 的两只 9300013 站在 y=58 的平台（脚点 y=54/55），其下 y=90..662 是同一条底井。
六个子房门只有 922010506 的 out00/out01 落回塔底（主图 in06, y=251）；501-505 的出口落在各自的上层门（y=-1292..-525）。
队伍实际用到的位置都在上面：关卡 NPC 台 y=-225、下一关门 y=-216、各子房门 y=-525..-1292。

为什么这仍然需要一道硬门：从塔底回 next00 在烘焙图上只有一条路，强制经过守卫层
（region 29>30>32>28>33>27，28 就是守卫台），备用路线都是单向下落。所以"下去"等于"回不来"。

原实现只挡了 922010506 的准入，主图那层没有任何限制。而主图上层平台本身有一条直线 DROP 落到守卫层
（region 27->30，落点 y=267），追击/拾取/送货/跟随队长任一条指令都能走到这里。

**修复位置的取舍（实测）**：先试过"下单后再取消"（`GCMovement.stop`），端到端仿真显示无效——
每拍只退一步、下一拍又下单，bot 仍然一路蹭到守卫旁边。原因是取消发生在 `super.grindTick()`
之后，而下单方是同一个 beat 的下一拍。真正有效的是在**追击选目标时**就排除
（`PqActions` 的候选循环里调 `refusesFloor`），这样订单根本不会产生。
把规则放在 `isPathable` 里也不够：它返回 false 后仍会退化到 `nearest` 盲走分支。


### 为什么第 9 条不修

孤儿掉落探测的设计目标是覆盖整个房间（含纵向跨层），而加可达性过滤会与这个目标冲突：实测 PqOrphanProbeTest 的“探测覆盖房间远端”用例在 y=130 到 y=542 之间必失败（这段在 922010100 上确实不可达，但探测本就该看见它）。代价也可控：driver 8 秒无进展放弃，探针 6 秒节流，不是高频空转。真正的空转痛点（阶段 7 与 9 的不可达怪）已由 reachableOnly 精确解决。

### 第 10 条的量化依据

506 的四个箱子台在 y=-630 / -1064 / -1459 / -3535，七只无敌怪分四行在 y=-441 / -862 / -1266 / -2497，箱台到最近守卫约 190px。所以隐身带必须窄于 190px，否则每个箱台都算“穿越”，隐身等于全程常开（即被替换掉的旧行为）。取 110px：箱台上不隐身，守卫行上隐身。启停必须由移动物理线程在接触伤害检查前执行；只在宏节拍检查位置会漏过整次穿越，保留宏/物理双写则会由旧位置样本错误取消新隐身。

## 六、复现方法

1. 可达性（本文所有 NO PATH / N 条边的取法）：

```java
MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010700);   // 真实 WZ
BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, new BotMovementProfile(110, 110));
Point from = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());
Point to   = BotPhysicsEngine.findGroundPoint(map, new Point(228, -1037));
int fr = g.findRegionId(map, from), tr = g.findRegionId(map, to);
List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(g, map, from, fr, tr, to);
// path == null 即不可达
```

2. WZ 实数：直接解析 wz/Map.wz/Map/Map9/9220107xx.img.xml 的 reactor / life / foothold / ladderRope 段。

3. 掉落：V1.0.51__drop_data_insert_old_data.sql（9300005/9300006/9300007/9300008/9300010/9300012/9300014 → 4001022/4001023）、V1.0.58__reactordrops_insert_data.sql（2202003/2201001 → 4001022）。

4. 脚本：scripts/npc/2040036..2040044.js（各阶段数量）、scripts/portal/lpq0..7.js（阶段门）、scripts/reactor/2200002|2201001|2201002|2201003.js（副作用）。
