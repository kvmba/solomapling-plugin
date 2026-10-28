# 玩具塔（Ludi PQ / 101 组队任务）全流程 BUG 审计

日期：2026-10-28　分支：`optimize/performance-v2` @ `0d0c9f3`

## 一、结论总表

| # | 阶段 | 根因 | 后果 | 级别 |
|---|---|---|---|---|
| 1 | 2 | 陷阱箱 2200002 从不打（LudiStages.java:156） | 塔内 11 箱只产 11/15，阶段 2 算术无解 | 硬卡死 |
| 2 | 7 | 三个产证箱在右侧单向列，导航图无路可达 | 阶段 7 永久 0/3，Rombard 永不召唤 | 硬卡死 |
| 3 | 9 | fightBoss 只有 seekAndAttack，从不召唤 Alishar | 本局无法结束 | 硬卡死 |
| 4 | 5 | 门房分派表按职业判能力，与跳跃几何不符 | 法师占 501 上不去且不释放 | 硬卡死 |
| 5 | 5 | doorRoomsOutstanding 不套用分派表 | 24/24 也交不掉 | 死锁 |
| 6 | 3 | 先拆箱再打怪，不等本箱 3 只清完 | 一次放满 32 只 Blocktopus | 行为缺陷 |
| 7 | 8 | 公告把计划总数当还差几个 | 玩家照做则 playersOnCombo==6 | 协作缺陷 |
| 8 | 9后 | workStage 返回 true 直接退本 | 放弃奖励关 922011000/922011100 | 流程缺失 |
| 9 | 全局 | 孤儿掉落探测不校验可达性 | bot 被拖在原地 | 移动打架 |
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

#### (b) doorRoomsOutstanding 不套用分派表（:448-457 vs :492-504）

```java
// claimDoorRoom：会跳过 bot 进不去的房间
if (roomFirst == STAGE5_ROOM_FIRST && !stage5RoomAdmits(bot, room)) continue;

// doorRoomsOutstanding：只跳过 cleared / 被别人 claim，从不问我能不能进
for (int room = roomFirst; room <= roomLast; room++) {
    if (cleared.contains(room) || roomClaimedByOtherLiveBot(bot, room)) continue;
    return true;                       // 永远还有活
}
```

后果：只要 501/506 存在没人有资格 claim 的房间，isFollowingLeader() 永远返回 false，deliverAndPark 永远进不去。即便机器人兜里揣着 20/24 张，没有任何 bot 会走到 NPC 交货。这是有货交不掉，比货不够更隐蔽，也是本阶段真正的死锁点。

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

根因：gatherPasses 的 stage 7 分支（LudiStages.java:113-119）走 nearestOwnedBoxOid(BOX_STAGE7) + hitReactorRotate，而 hitReactorRotate → approachReactor → approachUnder 只在站在箱子脚下 ≤90px 横 / ≤70px 竖时返回 IN_POSITION 才挥击。

真实几何（逐条解析 922010700.img.xml）：

| 元素 | 位置 |
|---|---|
| 3 个 2201002 产证箱 | (228,-1037)、(230,-1263)、(230,-1535)，全在右侧 x≈183–265 窄台 |
| 4 条绳 | x=-205 (-988..-721)、x=-171 (-707..-272)、x=-196 (-1261..-994)、x=-227 (-1467..-1292)，全在左侧 |
| 右台与左台的关系 | 同高两台之间横跨 366px（如 -1263 左右两段），无绳、无斜跬、无传送门 |

生产图的边表更直接：右列 r3 / r6 / r7 之间只有 DROP 和下降弧（r3→r6 DROP、r6→r7 DROP、r7→r9 DROP/JUMP），进入右列的唯一入边来自 y=-4365 的封闭顶台（r1→r3 JUMP、r2→r4 JUMP），而该台远在 VRTop=-2160 之外。

实测（5 种档位，从 st00 或任一左侧台出发）：

```
110/110  st7boxes=false/false/false
115/115  st7boxes=false/false/false
115/123  st7boxes=false/false/false
140/123  st7boxes=false/false/false
148/123  st7boxes=false/false/false
```

findPath 全部返回 null。

后果：approachReactor 永远给不出 IN_POSITION，hitReactorRotate 三次轮换后返回 false，gatherPasses 落到交货分支，于是 Rombard 永不召唤，3 张证物永远拿不到。预置的 9300169/170/171 只掉 4001156（drop_data 明确），完全不顶用。阶段 7 变成等真人。

一个容易误判的点：宿主 ReactorHitHandler 对 oid 无任何距离校验（只读 oid/charPos/stance/skillid 直接 hitReactor），所以打不到完全是插件自己 APPROACH_X=90 / APPROACH_Y=70 的盒子决定的。这也是为什么本阶段不是偶尔打空，而是一次都不打。

未确认项（不作结论）：真人能否用 400px 射程的弓从左侧台隔空打中箱子，未在实机验证；官方地图本意是否为远程点箱，未查证。无论哪种，机器人的 hitReactorRotate 都不会去打。

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

### 阶段 9 · 922010900（Boss）—— 硬卡死 3

根因（LudiStages.java:1135-1137）：

```java
public static void fightBoss(Character bot) {
    PqActions.seekAndAttack(bot);
}
```

只有追击，没有任何召唤动作。

宿主真实机制（三处互证）：

| 事实 | 出处 |
|---|---|
| 晶体 2201003 是 item 型（type=100），要 4001022 x 1 | wz/Reactor.wz/2201003.img.xml 的 event/0 {type:100, state:1, 0:4001022, 1:1} |
| act() 在 922010900 召唤 Alishar | scripts/reactor/2201003.js 的 rm.spawnMonster(9300012, 941, 184) |
| Alishar 掉奖杯 | drop_data (9300012, 4001023, 999999) |
| turn-in 收 1 个奖杯 | npc/2040044.js 的 cm.haveItem(4001023, 1) |

几何：Ratz 9300006 在 (588,-3)，即晶体所在的 x 554..636 / y=0 小台；st00 (-171,178) 落在 y=184 的 forbidFallDown 全宽行。

实测（5 种档位）：

```
st9ratz=false  st9crystal=false     （全部档位）
```

边表：r3 (y=0) 只有 →r6 DROP/JUMP（向下），r6 → r5 JUMP（120）、r5 → r4 JUMP（74），而 r4 只能回 r5/r6。184 → 0 无任何上行边；y=572 行（r7）连一条边都没有。

后果：

- seekAndAttack 会把 Ratz 当目标，canPathTo 为 false，seekPathableTarget 退回最近怪，仍向 (588,0) 发 move，驱动无法规划，RETARGET_TIMEOUT_MS=4s 无进度放弃，下一 tick 重来。整局在台下反复原地起意。
- 而且它连挥击都打不到：BotAttackDriver.mobsInReach 的 onAttackableSurface 会因 GCMovement.onDifferentLedge 把异层怪排除（Ratz 非 boss，不吃 BOSS_Y_PAD 豁免）。
- 晶体召唤本身对机器人是可做但没写：PqActions.hitReactor 对距离无校验，feedReactor 的整套投掷逻辑在 OPQ 已有现成实现，LPQ 没有调用。

### 结算 / 奖励关 · 922011000 → 922011100 —— 流程缺失 8

- workStage 在 stage 9 cleared 时 return stage >= 9（LudiPQBot.java:119），tickPartyQuest 立刻 returnToLobby（PartyQuestBot.java:326-330）。
- 但 LudiPQ.js 的 clearPQ 是 warpEventTeam(922011000) 加 1 分钟奖励关，之后 scheduledTimeout 再送 922011100。
- isInsideQuest 又不含这两张图（LudiPQBot.java:53-55），所以即便被传过去，tickLobby 也会把它送回大厅。
- 后果：机器人 100% 放弃奖励关（922011000 有 36 个 2202004 蓝箱加 NPC 2040045）。玩家少一个队友；若玩家也掉线，实例会被 changedMap 提前 end。

---

## 三、跨阶段：追怪 / 移动 打架

### 1. 孤儿掉落探测把 bot 拖向看得见拿不到的点 —— 9

PqActions.sweepOrphanDropOutside（:1773-1816）只过滤 botCanLoot / isFloorLoot，从不做 canPathTo，然后用房间对角线尺度（roomReachPx，塔图约 4600px）把全房间最近的一张通行证当目标 walkForPickup。

- 在 922010700 上，右侧台/封闭台的掉落正好会被它选中；
- 驱动规划失败，MOVE_NO_PROGRESS_MS = 8s 放弃，下一 tick（本地 260px 无命中）再次探测（ORPHAN_PROBE_MIN_MS = 6s 节流），于是形成每 6s 一次的原地起意—放弃循环；
- 期间 GCMovement.isMoving 为真，waitNearStageNpc / handItemsToLeaderAfterStage 的 yieldsToLiveWalk 一律让路。

后果：bot 既不去交货也不去打怪，表现为站在台边反复小步，直到掉落 3 分钟消失（item_expire_time）。这是追怪/移动打架里最实的一条，根因是目标选择没有可达性门。

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

## 五、修复优先级（按根因，不做补丁）

1. 阶段 2：补 BOX_STAGE2_TRAP，findAllReactorOids(BOX_STAGE2).isEmpty() 后再弹陷阱箱（99cf7de 已有实现，直接移植/合并）。
2. 阶段 7：先确认这张图的设计意图（远程点箱 or 缺一条上行边）；机器人在箱子不可达时必须停止空转并上报玩家，而不是静默停在原地。
3. 阶段 9：fightBoss 改为先 feedReactor/hitReactor(2201003) 召唤 9300012，再 seekAndAttack；Ratz 不应作为 chase 目标（异层且无路）。
4. 阶段 5：
   - 分派表改为基于 BotMovementProfile.fromCharacter(bot).totalJumpStat() 的实际升程判定（队伍轻功已建模，见 HasteJumpGrantTest）；不要用 hasTeleportSkill（导航层无 teleport 边）。
   - doorRoomsOutstanding 必须套用与 claimDoorRoom 相同的本 bot 是否有资格过滤。
   - 进门失败（enterDoorRoom 走不到加 walkTo 无路）要释放 claim 并记一次失败，避免永久占位。
5. 阶段 3：加 killableMobPresent 为真时不拆新箱的门。
6. 阶段 8：公告改为还差 5 - 已在箱上的机器人数 个，并指出空位序号（wanted[0]）；机器人数 = 5 时文案必须是你别上箱。
7. 阶段 9 后：识别 922011000 / 922011100，至少把 isInsideQuest 扩到奖励关，或在 returnToLobby 前等 clearPQ 的团队传送。
8. 全局：sweepOrphanDropOutside 加 GCMovement.canPathTo 过滤；breakBoxInPlace 每次挥击前重开隐身（阶段 5）。

---

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
