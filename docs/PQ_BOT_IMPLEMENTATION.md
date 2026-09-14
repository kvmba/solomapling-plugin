# PQ Bot 实施级设计（v3）—— 机制坐实、可照此写代码

> 本文是 [PQ_BOT_PLAN.md](PQ_BOT_PLAN.md)（v1 方案）与
> [PQ_BOT_PLAN_REVIEW.md](PQ_BOT_PLAN_REVIEW.md)（v2 勘误）的**第三层**：
> 把"计划"变成"可实施"。
>
> 前两份文档解决"**该怎么想**"，本文解决"**该怎么写**"。所有结论都在
> `GMS083`（引擎 + 脚本 + WZ）与 `solomapling-plugin`（bot）源码中逐行坐实，
> 每一条都附**可复核的证据路径**。
>
> 类型覆盖见 [PARTY_CONTENT_TAXONOMY.md](PARTY_CONTENT_TAXONOMY.md)。

---

## 第一部分 · 本轮新坐实的 6 个关键机制（前两份文档都不知道）

### 🔴 发现 1：祭坛/音乐盒要求**单叠恰好 N 个**，不是"N 个散落物品"

这是**最容易写错、且写错就 100% 不通关**的一条。

```java
// MapleMap.activateItemReactors:2511 / searchItemReactors:2526
react.getReactItem(evstate).getLeft()  == item.getItemId()
react.getReactItem(evstate).getRight() == item.getQuantity()   // ← 注意：整叠数量必须精确相等
```

- 祭坛 `2006000`：`4001063` × **20**（**一叠** 20 个，`item.getQuantity()==20`）
- 音乐盒 `2008006`：`4001056+day` × **1**

**推论**：
- 现有 OPQBot 用 `botThrowItemQty(..., cloudCount, ...)` 丢**一叠 `cloudCount` 个** ——
  只有 `cloudCount == 20` 的那一次才会触发。**这解释了为什么"偶尔"能通**。
- ✅ **正确做法**：`botThrowItemQty(bot, 4001063, 20, pos)` —— **一次丢一叠 20**，
  且 `pos` 必须落在触发框内（见发现 2）。
- ❌ **错误做法**：丢 20 次、每次 1 个。`getQuantity()` 恒为 1，永不等于 20。

### 🔴 发现 2：祭坛有**两个** event 定义，且 `lt/rb` 会被**后者覆盖**

`2006000.img.xml` 里有两个 event 块：

| 位置 | type | state | 0 | 1 | lt | rb |
|---|---|---|---|---|---|---|
| `imgdir/0/event/0` | 100 | 1 | 4001063 | 20 | (-100,-100) | (100,100) |
| `imgdir/20/event/0` | 100 | 21 | 4001063 | 20 | **(-33,-36)** | **(34,81)** |

`ReactorFactory` 的解析规则（`ReactorFactory.java:128-137`）：

```java
if (!areaSet || loadArea) {   // "only set area of effect for item-triggered reactors once"
    stats.setTL(...); stats.setBR(...);
    areaSet = true;
}
```

`areaSet` 在**第一次**遇到 type==100 时置 true 并写 TL/BR。
`2006000` 没有 `info/activateByTouch` → `loadArea=false` → **只有 `imgdir/0` 的 (-100,-100)/(100,100) 生效**。

**结论**：祭坛触发框 = `x ∈ [377-100, 377+100) = [277, 477)`，`y ∈ [66-100, 66+100) = [-34, 166)`。
👉 **现有 bot 丢在 `(497,143)` —— x 超出 20px，永远不触发。**（v2 已发现，此处给出严格证明）

### 🔴 发现 3：物品落地会**吸附到地面**，触发框必须**同时容得下吸附后的坐标**

```java
// MapleMap.spawnItemDrop:2334
final Point droppos = calcDropPos(pos, pos);

// MapleMap.calcDropPos
Point ret = calcPointBelow(new Point(initial.x, initial.y - 85));  // 向下找 85px 内的地面
```

即：**传入的抛掷点会先下移 85px 再吸附到地面 foothold**。

- 祭坛在 `(377, 66)`；祭坛下方的地面在 `y=143`（foothold `x1=360,x2=450,y=143`）。
- 从 `y=66` 下移 85 → `y=151` → 吸附到最近的 `y=143` 地面 → **落点 `(377,143)`** ✓ 在框内。
- 而 `(497,143)`：虽然 `y=143` 也是地面，但 `x=497 ∉ [277,477)` ✗。

**推论**：**必须在"祭坛脚下 + 框内"找地面**。对祭坛就是 `x ∈ (277,477)` 且地面 `y=143`。

### 🔴 发现 4：`ActivateItemReactor` 有 **5 秒延迟**，且**依赖 ownerClient 有效**

```java
// MapleMap.registerMapSchedule(new ActivateItemReactor(drop, react, owner), 5000);
// MapleMap.ActivateItemReactor.run() → reactor.hitReactor(c) → ReactorScriptManager.act(c, this)
```

且 `searchItemReactors` 里：

```java
Client owner = drop.getOwnerClient();
if (owner != null) { registerMapSchedule(...); }   // ← ownerClient 为 null 则静默不触发
```

而 `MapItem.getOwnerClient()`（`MapItem.java:235`）：

```java
return (ownerClient.isLoggedIn() && !ownerClient.getPlayer().isAwayFromWorld()) ? ownerClient : null;
```

⚠️ **两个坑**：
1. `ownerClient.getPlayer()` —— 若 bot 用**共享 BotClient** 且未绑 player，**这里会 NPE**
   （或者更糟：绑着**另一个 bot**，`isAwayFromWorld` 判断的是错的人）。
2. `BotClient.isLoggedIn()` 恒返回 `true` ✓，`awayFromWorld` 需要 `markPresentInWorld()` ✓（bot 已有）。

**这正是 `BotClientBinding` 必须覆盖的路径**：丢物品 → 5 秒后回调 → 回调里读 `client.getPlayer()`。
**5 秒延迟意味着绑定必须跨越这个窗口，不能只在丢的瞬间绑。**

#### ⚠️⚠️ 由此推出的架构硬约束（本轮最重要的发现）

`BotClientHandler` 里 **每个频道只有一个共享 `Client`**，所有模板 bot 共用它：

```java
// BotClientHandler.java
return BOT_CLIENTS.computeIfAbsent(ch, c -> new BotClient(...));   // 每频道一个，全 bot 共享
```

而 `BotClientBinding.withBoundPlayer` 用的是 `synchronized (client) { client.setPlayer(chr); ... }` ——
**只在同步块内有效**。但 `ActivateItemReactor` 是**5 秒后由定时器线程执行**的，
**那时绑定早已释放** → `c.getPlayer()` 要么是 null，要么是**当时碰巧绑着的另一个 bot**。

**这解释了为什么"丢物品触发"这类机制对 bot 特别脆弱**，也给出三条可行路线：

| 路线 | 做法 | 代价 | 推荐度 |
|---|---|---|:--:|
| **A. 让延迟回调安全** | 丢物品后 bot 原地待命 ≥6 秒，且**不与其他 bot 并发绑定** | 需要全局"绑定令牌"串行化 | ⭐⭐ |
| **B. 绕开延迟回调** | 不依赖 `ActivateItemReactor`，直接调 `ReactorScriptManager.act(client, reactor)` | 丢失 5 秒延迟 + **绕过道具消耗**（违背官方玩法） | ⭐⭐⭐ |
| **C. 给 PQ bot 私有 `BotClient`** | 每个 PQ bot 一个独立 client + `client.setPlayer(bot)` | 每 bot 一个对象；PQ bot 数量少（≤6），开销可忽略 | ⭐⭐⭐⭐⭐ |

#### ✅ 路线 C 是本轮的最优解（新发现）

`BotClientHandler` 目前**每频道一个共享 client**（为省内存），但 **`BotClient` 完全可以多实例**：

```java
public BotClient(int world, int channel) {
    super(Type.CHANNEL, -1, "bot", null, world, channel);   // 只设字段，无全局注册
}
@Override public boolean isLoggedIn()  { return true; }        // 恒定在线
@Override public void updateLoginState(int s) { /* 无 DB 写入、无 SessionCoordinator 注册 */ }
@Override public void sendPacket(Packet p) { /* 无 socket */ }
```

- `getChannelServer()` / `getWorldServer()` 都是 `Server.getInstance().getChannel(world, channel)` —— **按 world/channel 查全局表，不依赖实例身份** ✓
- `updateLoginState` 被 override 成空 → **不会写库、不会注册到 `SessionCoordinator`** ✓
- **先例**：`loadPersistentBot`（companion）已经是"每 companion 一个 client + `setPlayer`"模式 ✓

**做法**：PQ bot 创建时，不走 `BotClientHandler.clientFor(channel)`（共享），而是：

```java
// 伪代码：PQ bot 的私有 client
Client pqClient = new BotClient(GameConstants.WORLD_SCANIA, channel);
Character bot = Character.loadCharFromDB(cid, pqClient, true);
pqClient.setPlayer(bot);          // ← 建立反向边，之后 c.getPlayer() 恒为这个 bot
bot.setClient(pqClient);
```

**收益**（一次性解决 3 个问题）：
1. **5 秒延迟回调天然安全** —— `c.getPlayer()` 恒为该 bot，无需绑定令牌；
2. **`BotClientBinding` 不再是必需品**（仍建议保留用于模板 bot 拾取）；
3. **`getOwnerClient()` 判断正确** —— `isAwayFromWorld` 查的是对的人。

> ⚠️ **注意**：私有 client 的 `channel` 必须与 bot 实际所在频道一致（`getChannelServer()` 按 `this.channel` 路由）。
> PQ bot 数量少（≤6），内存开销可忽略；**不建议**把这个模式推广到上千个环境 bot。

### 🟠 发现 5：存在 `use_enable_stage_skip` 配置，**单人可跳过部分谜题**

`V1.11.3__insert_game_config_stage_skip.sql`：

```sql
'use_enable_stage_skip', 'false', '是否允许跳过副本关卡谜题'
```

只被 **2 个脚本**使用：

| 脚本 | 效果 |
|---|---|
| `scripts/npc/2013001.js:158`（Orbis Eak） | `getPlayerCount()==1` 时，Stage 4 的三平台谜题**直接 `forceHitReactor("stone4",1)` + clearStage** |
| `scripts/npc/9020001.js:71`（KPQ） | 同上，Stage 2/3/4 的绳/台/桶组合谜题**直接判定通过** |

且 KPQ Stage1 有硬门槛：

```js
// 9020001.js:142
if (eim.getPlayerCount() == 1 && !GameConfig.getServerBoolean("use_enable_stage_skip")) {
    cm.sendOk("The mechanisms here require teamwork to solve...");
    return;   // ← 单人且未开跳过 = 卡死
}
```

**战略含义（重要）**：
- 若服务器开了 `use_enable_stage_skip=true`，**很多 PQ 的"协作谜题"单人就能过**，
  bot 的价值从"解谜"降级为"陪打怪/填人数"。
- 若为 `false`（当前默认），**单人卡死** → **bot 必须来**，这正是本方案的价值所在。
- **建议**：`PQSpec` 里加一个 `soloSkipsPuzzles` 字段，运行时读 `GameConfig` 决定要不要派 bot 解谜。

### 🟠 发现 6：Orbis Stage 1 的 `playerEntry` 自带**开场对白 = 教学文本**

```js
// OrbisPQ.js playerEntry
var texttt = "Hi, my name is Eak, ... Please collect #b20#k Magic Clouds and bring them back to me...";
player.getAbstractPlayerInteraction().npcTalk(2013001, texttt);
```

这是**官方玩法说明**，直接给出了正确答案：**收集 20 个云**。
→ 我们的 spec 里 `cloudTarget = 20` 与脚本文本一致，可交叉验证。

---

## 第二部分 · 经典四连的真实机制（照此写 spec）

### 2.1 Orbis PQ（完整 9 关）——证据最全，优先做

#### ✅ 已精确算出的落点（本节最有价值的部分）

`calcDropPos(pos, pos)` 的真实行为：**把 `pos.y - 85` 作为查询点，找"其下最近的地面"**，
返回值 = `(pos.x, 地面 y)`（**x 不变**）。

| 触发点 | 反应堆位置 | 触发框 | 抛掷时传入 | **实际落点** | 是否在框内 |
|---|---|---|---|:--:|:--:|
| **祭坛** `2006000` | `(377, 66)` | `x∈[277,477)` `y∈[-34,166)` | `(377,143)` 或 `(377,66)` | **`(377, 99)`** | ✅ |
| **音乐盒** `2008006` | `(-1706,-240)` | `x∈[-1758,-1666)` `y∈[-304,-161)` | `(-1706,-240)` 或 `(-1706,-127)` | **`(-1706, -172)`** | ✅ |
| （现有 bot） | — | — | `(497,143)` | `(497,143)` | ❌ x 超界 20px |
| （现有 bot） | — | — | `(-1588,-127)` | `(-1588,-127)` | ❌ x 超界 78px |

**推导过程（祭坛）**：
1. `pos=(377,143)` → 查询 `(377, 143-85) = (377, 58)`
2. `x=377` 上的 foothold 有：`y=-1291, -710, -388, -232, 99, 110, 121, 143`
3. `findBelow` 返回**第一个 `y1 >= 58`** 的 → `y=99`（footing `x1=328, x2=426`）
4. 落点 = `(377, 99)` → 在 `y∈[-34,166)` 内 ✅

**推导过程（音乐盒）**：
1. `pos=(-1706,-240)` → 查询 `(-1706, -325)`
2. `x=-1706` 上的 foothold：`y=-907, -511, -172, -161, -150, -127`
3. 第一个 `y1 >= -325` 的 → `y=-172`（footing `x1=-1758, x2=-1660`）
4. 落点 = `(-1706, -172)` → 在 `y∈[-304,-161)` 内 ✅

> 💡 **重要副作用**：由于落点会吸附到 `(377,99)`，**抛掷点传 `(377,66)` 或 `(377,143)` 结果相同**。
> 写 spec 时建议直接传**预期落点**（`(377,99)` / `(-1706,-172)`），
> 语义更清晰，也便于自检工具比对。

#### 9 关全表

| Stage | 地图 | 通关条件（脚本原文） | Bot 需要做什么 |
|:--:|---|---|---|
| **0** | 920010000 | 丢**一叠 20** `4001063` 到祭坛框 | 打云 → 捡 → 攒到 20 → **丢一叠到 `(377,99)`** |
| **1** | 920010000→920010100 | 队长点 Eak → `statusStg0=1` → 传送到塔 | 跟队（`warpEventTeam`） |
| **2** | 920010200 | 打怪集 **30** 个 `4001050` 交 Eak | 打怪 + 地上捡 + 攒 30 |
| **3** | 920010300 | 找**隐藏**的 2nd Statue Piece | 搜地图（`statusStg2`） |
| **4** | 920010400 | 丢**当天星期**的唱片到音乐盒 | `4001056+day`，丢到 `(-1706,-172)` |
| **5** | 920010500 | 三平台**人数组合**谜题 `stage4_0..2` | 站平台（读 `stage4_N` 拿答案） |
| **6** | 920010600 | 打怪 | 打怪 |
| **7** | 920010700 | 打怪 / 捡碎片 | 打怪 |
| **8** | 920010800 | 集齐 **6** 个 `scar1..6` 反应堆 | 打 6 个反应堆（**真脚本**） |
| **9** | 920010100 | 丢 `4001055` 到雕像底座 → 通关 | 丢物品 |

**Stage 8 的 `scar1..6`**：这是**可打的反应堆**（不是物品触发），
用 `hitReactorWithScript` 打；通关判定 `isStatueComplete()` 检查 `getState() >= 1`。
⚠️ 注意 Stage 9 的雕像底座（`4001055`）**也是物品触发型**，同样要用 `forceActivateItemReactor`。

**Stage 4 音乐盒的精确参数**（新坐实）：

| 项 | 值 | 证据 |
|---|---|---|
| 反应堆 | `2008006` @ `(-1706,-240)`，名字 `music` | `Map9/920010400.img.xml` |
| 触发框 | `lt(-52,-64) / rb(40,79)` → `x∈[-1758,-1666)` `y∈[-304,-161)` | `Reactor.wz/2008006.img.xml` |
| 需要的唱片 | `4001056 + day`（day ∈ [0,6]，0=周日） | 见下 |
| 脚本置位 | `act()` → `statusStg3 = "0"` | `reactor/2008006.js` |
| 后续 | 队长点 Eak → `stone3.forceHitReactor(1)` + `statusStg3=2` | `npc/2013001.js:124-129` |

⚠️ **星期映射的坑**：`setEventState(d.getDay())` 中 `getDay()` ∈ [0,6]（0=周日）。
而 WZ 里 7 个 event 块的 `state` 是 **1..7**，物品 `4001056..4001062`。

`MapleMap.activateItemReactors` 比的是 `react.getReactItem(react.getEventState())` —— 用的是 **eventState**（不是 state）。
`evstate = 0`（周日）时，`stats.getReactItem(0, 0)` = `getReactItem(state=0)` → **`imgdir/0` 的第一项** → `4001056` ✓
`evstate = 1`（周一）→ `imgdir/1` → `4001057` ✓
…… 即 **evstate=d.getDay() 直接索引 imgdir，天然对齐**，无需 +1 换算。

👉 **bot 的唱片选择**：`itemId = 4001056 + day`，其中 `day` ∈ [0,6] 来自 `new Date().getDay()`。
（周日=4001056，周六=4001062）

### 2.2 Kerning PQ（5 关）——"组合谜题"型

**Stage 1（答题）**：非队长各拿 1 道题 → 集齐 `答案数` 个 `4001007` 优惠券 → NPC 给 `4001008` 通行证 → **队长手持 `partySize-1` 张**交 NPC。

```js
var stage1Answers = Array(10, 35, 20, 25, 25, 30, 8);
// 例："collect the same number of coupons as the minimum STR for warrior" → 35 个
```

**Stage 2/3/4（组合谜题）**：**随机 combo + 矩形包含判定**！

```js
var stage2Rects = [(-755,-132,4,218), (-721,-340,4,166), (-586,-326,4,150), (-483,-181,4,222)];
var stage2Combos = [[0,1,1,1],[1,0,1,1],[1,1,0,1],[1,1,1,0]];  // 4 绳选 3
// stage3: 5 平台选 3；stage4: 6 桶选 3
```

`rectangleStages()` 逻辑：
1. 随机选一个 combo `c`（首次访问时 `setProperty` 固定下来，**解谜可重试、答案稳定**）
2. 统计每个矩形内的玩家数 `playerPlacement[j]`
3. **逐位比对** `curCombo[j] == playerPlacement[j]` → 全等才通过

👉 **bot 的关键优势**：bot 可以**直接读 `eim.getProperty("stg2Property")` 拿到答案**！
不需要试探。这是 bot 相比真人的**信息优势**——spec 里应暴露 `readEimProperty` 原语。

**Stage 5（Boss 关）**：打 Boss 集 10 张 `4001008` 交 NPC。

### 2.3 Ludi PQ（9 关）——混合型

`minLevel=35, maxLevel=50`，`recruitMap=221024500`，`entryMap=922010100`。
9 个 `statusStgN` 属性，混合了：打怪集物、**打箱子反应堆**、**平台站位**、**答题**等。
（与 Orbis 的重合度最高，`HitReactorTask` / `StandAreaTask` / `HuntCollectTask` 直接复用）

### 2.4 Henesys PQ（2 阶段）——"保护 + 组合"型

| 阶段 | 条件 | 证据 |
|:--:|---|---|
| **种子** | 底部花采种子 → 丢到"新月 footing"上（**6 种花对应 6 个不同 footing**） | `1012112.js` 对白 |
| **月兔** | 保护 Moon Bunny 打完，集 **10** 个 `4001101`（米糕）交 Growlie | `1012114.js:41` |
| **完成** | `cm.gainItem(4001101,-10)` → `clearStage(1,eim)` → `map.killAllMonstersNotFriendly()` + `clearPQ()` | `1012114.js` |

⚠️ `HenesysPQ` 的 `minLevel=10, maxLevel=255` —— **几乎无等级门槛**，
`recruitMap=100000200`（Henesys 城中）→ **非常适合当第一个落地的"简单 PQ"**。

---

## 第三部分 · 阶段 0 三个致命 bug —— 代码级修复设计

> 目标：**不重构**，先让现有 Orbis 能真正通关。改动面 < 100 行。

### 修复 1：反应堆要走**真脚本**（`ReactorScriptManager.act`）

**现状**：`CustomReactor.hitReactor` 只做 `state++` + 广播，**不调 `act()`**。
→ 云不掉碎片（`2002001.js act()` 是 `rm.dropItems()` 的唯一入口）
→ 祭坛不生成 Eak（`2006000.js act()`）

**方案**：在 `CustomReactor` 新增两个方法（分别对应"可打的反应堆"与"物品触发的反应堆"）：

> 前提：PQ bot 已按 **发现 4 · 路线 C** 使用**私有 client**。
> 若沿用共享 client，则下面两处都必须包在 `BotClientBinding.runWithBoundPlayer(...)` 里。

```java
// ① 通用：走 host 的完整状态机（stateSize / act() / searchItemReactors 全自动）
//    云打 4 下触发；祭坛/音乐盒打 1 下即可触发（见下方勘误）
//    ⚠️ 不要硬编码命中次数 —— 循环打到副作用出现为止（掉落 / EIM 属性变化）
public static void hitReactorWithScript(Character bot, int oid) {
    MapleMap map = bot.getMap();
    Reactor reactor = map.getReactorByOid(oid);
    if (reactor == null) return;
    reactor.hitReactor(false, 0, (short) 0, 0, bot.getClient());
}

// ② 物品触发的反应堆：跳过"丢物品 + 5 秒定时器"，直接调 act()
//    仅用于兜底/调试；正常流程应走"丢物品"以符合官方玩法（且消耗道具）
public static void forceActivateItemReactor(Character bot, int oid, int itemId, int qty) {
    MapleMap map = bot.getMap();
    Reactor reactor = map.getReactorByOid(oid);
    if (reactor == null) return;
    Pair<Integer, Integer> need = reactor.getReactItem(reactor.getEventState());
    if (need == null || need.getLeft() != itemId || need.getRight() != qty) return;
    ReactorScriptManager.getInstance().act(bot.getClient(), reactor);
}
```

**为什么这样改最少**：
- 复用 host 的 `Reactor.hitReactor(...)` —— **状态机、`act()`、掉落、`searchItemReactors` 全都自动正确**。
  不用自己复刻状态机（现有 `CustomReactor.hitReactor` 的裸 `state++` **跳过 `stateSize` 校验**，是隐患）。
- 前置条件：**PQ bot 用私有 client**（见发现 4 · 路线 C），这样 `hitReactor` 内部的 `c.getPlayer()` 恒为该 bot，
  且 5 秒延迟回调也安全。

#### ✅ 勘误：`type==100` 的反应堆**也能被"打"触发**（本轮自我纠错）

初稿曾断言"祭坛 `type==100`，打它不推进状态"——**这是错的**。
逐行追踪 `Reactor.hitReactor` 的状态机（`Reactor.java:404-440`）后，真实行为是：

| 反应堆 | type | 命中几下触发 `act()` | 追踪过程 |
|---|:--:|:--:|---|
| 云 `2002001` | 0 | **4 下** | `hit1:0→1, hit2:1→2, hit3:2→3, hit4:3→4`；第 4 下时 `getNextState(4,·)=-1 < 4` → `isInEndState=true` → `act()` |
| 祭坛 `2006000` | **100** | **1 下** | `hit1: state 0→1`；随后 `getNextState(1,·)=-1 < 1` → `isInEndState=true` → 走 `reactorType>=100` 的 else → **`act()`** ✓ |
| 音乐盒 `2008006` | **100** | **1 下** | `hit1: state 0→1`；`getNextState(1,0)=1`，`state(1)==getNextState(1,0)` → 循环型 → **`act()`** ✓ |

**关键代码**（`Reactor.java:412-431`）：

```java
this.state = stats.getNextState(state, b);        // 先改 state
byte nextState = stats.getNextState(state, b);    // 再用【新 state】查下一跳
boolean isInEndState = nextState < this.state;    // 新 state 无数据 → -1 → true
if (isInEndState) {
    ...
    ReactorScriptManager.getInstance().act(c, this);   // ← type>=100 也会走到这里
} else {
    if (state == stats.getNextState(state, b)) {       // 循环型反应堆
        ReactorScriptManager.getInstance().act(c, this);
    }
}
```

**实践结论（两种路线都可行）**：

| 路线 | 做法 | 适用 |
|---|---|---|
| **打**（正规） | `reactor.hitReactor(false, 0, (short)0, 0, client)`；**云打 4 下、祭坛/音乐盒打 1 下** | 所有反应堆 |
| **直接调 `act()`** | `forceActivateItemReactor(...)` | **仅兜底/调试**（绕过道具消耗） |

> ⚠️ **祭坛的正规玩法是"丢一叠 20 个云"**，不是"打祭坛"。
> 打祭坛虽然也能触发 `act()`，但**绕过了物品消耗**，属于"作弊式捷径"。
> **建议正常流程走丢物品**——否则玩家会看到"箱子凭空出现"的违和感，且与官方设计不符。
>
> **音乐盒同理**：正规玩法是按星期丢唱片；直接 `act()` 可绕过。

⚠️ **不要硬编码命中次数**：由 `Reactor.hitReactor` 自行推进状态机，
spec 里应写成"**循环打到生效为止**"（检测 `act()` 的副作用：掉落物 / EIM 属性 / 状态变化），
而不是写死 `hits=4`。

⚠️ **注意 `hitReactor` 内部第一行就取 `c.getPlayer()`**：

```java
Character player = c.getPlayer();
if (GameConfig.getServerBoolean("use_debug") && player.isGM()) { ... }
```

`use_debug` 默认 `false` → 短路，不会 NPE。**但一旦有人开了 debug，bot 打反应堆就会 NPE** →
**仍必须绑 client**（用 `BotClientBinding`）。

⚠️ **`ReactorActionManager.dropItems` 开头是 `Character chr = c.getPlayer(); if (chr == null) return;`**
—— **静默失败**！所以"绑 client"不是可选项，是**必要条件**。

### 修复 2：丢祭坛的落点 + 数量

**改 `OPQConstants`**：

```java
// 旧（错）
public static final Point STAGE_1_DROP_POS = new Point(497, 143);
// 新：祭坛 (377,66) 正下方，吸附后落点 (377,99)，框 x∈[277,477) y∈[-34,166) 内
public static final Point STAGE_1_DROP_POS = new Point(377, 99);
public static final int    CLOUD_REQUIRED  = 20;   // 单叠数量
```

**改 `handleStage1DropItems`**：

```java
// 旧：botThrowItemQty(..., cloudCount, ...)  ← cloudCount 是"捡到的总数"，可能≠20
// 新：只在凑够 20 时丢，且一次一叠 20
if (cloudPiecesLooted >= OPQConstants.CLOUD_REQUIRED) {
    DropCommands.botThrowItemQty(getChr(), OPQConstants.CLOUD_PIECE,
            OPQConstants.CLOUD_REQUIRED, OPQConstants.STAGE_1_DROP_POS);
}
```

> 📌 传 `(377,99)` 是**吸附后的稳定落点**；传 `(377,66)` / `(377,143)` 也会吸附到同一点，
> 但直接传落点语义更清楚、便于自检工具比对。

### 修复 3：`unregisterBot` 接线 + 完成判定切到 EIM

**a) `unregisterBot` 零调用** → 在 `OPQBot` 的"离开 PQ / 走人"路径调用。
最小改动：在 `resetOPQBotState()` 或 bot 被移除时调用一次。

**b) 完成判定**：`isChamberlainSpawned()` 有 NPE（`resolveLeader()` 可能返回 null）：

```java
public boolean isChamberlainSpawned() {
    Character leader = resolveLeader();
    if (leader == null) return false;          // ← 补 NPE 守卫
    return isNpcPresent(leader.getMap(), CHAMBERLAIN_EAK);
}
```

**更好的做法**（v2 已建议）：改读 `eim.getIntProperty("statusStg0") == 1`。
`EventInstanceManager.getIntProperty` 是 public ✓。

---

## 第四部分 · 架构（v3 修正版）

### 4.1 内容规格分层

```java
// 入口机制决定"怎么进" —— 用 sealed interface 表达 5 种
sealed interface PartyContentSpec
        permits EventScriptSpec, CarnivalSpec, ExpeditionSpec, PyramidSpec, DojoSpec {}

// 关卡（仅 EventScript 需要）
record StageSpec(
        int mapId,
        StageGoal goal,                       // 目标类型
        String eimClearProperty,              // 如 "statusStg0" / "2stageclear"
        String eimAnswerProperty,             // 如 "stg2Property"（可读答案！）
        List<Point> dropTargets,              // 触发框内落点
        int itemId, int qty,
        long timeoutMs
) {}

// 关键：把"读 EIM 拿答案"当成一等公民
enum StageGoal { HUNT_COLLECT, DROP_STACK, HIT_REACTORS, STAND_PLATFORMS,
                 TALK_NPC, PROTECT_NPC, KILL_BOSS, FOLLOW }
```

### 4.2 原语补齐（在 v1 的 P3–P12 之上）

v1 的 12 个原语**方向正确**，但缺 3 个本轮发现的：

| 新增原语 | 用途 | 关键实现 |
|---|---|---|
| `DropStackTask(itemId, exactQty, point)` | **精确单叠**丢物品 | `generateCleanItemWithQty(itemId, qty)` + 落点校验在框内 |
| `ReadEimPropertyTask(key)` | **读答案**（组合谜题的作弊式优势） | `eim.getProperty(key)` → 解析 combo 数组 |
| `BindClientTask`（横切） | 5 秒延迟回调 | `BotClientBinding` 需覆盖 `ActivateItemReactor` 的延迟窗口 |

### 4.3 触发框校验工具（强烈建议）

所有"丢物品触发"的失败都是**落点出框**。建议加一个**启动期自检**：

```java
// 开发工具：打印某地图所有 type==100 反应堆的触发框 + 框内地面点
public static void dumpItemReactorBoxes(Character bot) {
    for (Reactor r : bot.getMap().getAllReactors()) {
        if (r.getReactorType() == 100) {
            Rectangle area = r.getArea();
            Pair<Integer,Integer> item = r.getReactItem(r.getEventState());
            // 打印: name / id / area / 需要物品 / 建议落点(框中心吸附地面)
        }
    }
}
```

→ 一次性消灭"坐标靠猜"这类 bug。**这应是阶段 0 的第一个提交。**

---

## 第五部分 · 修正后的阶段计划

| 阶段 | 内容 | 验收 | 工作量 |
|:--:|---|---|---|
| **0a** | 触发框自检工具（§4.3） | 打印出祭坛/音乐盒框 + **正确落点 `(377,99)` / `(-1706,-172)`** | 0.5 天 |
| **0b** | **PQ bot 私有 client**（发现 4 · 路线 C） | `c.getPlayer()` 恒为该 bot；5 秒回调安全 | 0.5 天 |
| **0c** | `CustomReactor` 改走 `Reactor.hitReactor`（真状态机） | 打云→**真的掉** 4001063 | 0.5 天 |
| **0d** | 落点 `(377,99)` + **单叠 20** | 祭坛触发→Eak 出现（日志可见 `mapMessage`） | 0.5 天 |
| **0e** | `unregisterBot` + NPE 守卫 + `debugLogf` 打开 | 通关日志完整 | 0.5 天 |
| **1** | `PQSpec`→`OrbisPQSpec`，EIM 驱动 | Orbis **全 9 关**通关 | 3-5 天 |
| **2** | `EimAccess` + `NPCInteraction` 抽象 | 玩家+bot 通关 Orbis | 2 天 |
| **3** | HenesysPQ（最简单，练手） | 通关 | 2 天 |
| **4** | KerningPQ（组合谜题，用读答案） | 通关 | 3 天 |
| **5** | LudiPQ（9 关混合） | 通关 | 3-5 天 |
| **6** | 横向铺开（Dojo → 其他 event PQ） | — | — |

**关键建议**：
- **阶段 0b（私有 client）应最先做** —— 它是 0c/0d 能生效的前提，
  且能一次性消除"5 秒延迟回调"和"`c.getPlayer()` 为 null"两类问题。
- **先做 HenesysPQ 再做 KerningPQ** —— 尽管 KPQ 更经典，但 Henesys 机制更简单
  （采种子 → 保护月兔 → 交 10 米糕），且 `minLevel=10` 覆盖新人。
- **KPQ 的"读答案"**是 bot 的独特优势，应作为"通用能力"实现（`ReadEimPropertyTask`），
  LudiPQ 的平台谜题也能复用。
- **Orbis 全 9 关是终极验收** —— 它同时覆盖了"打反应堆 4 下""丢单叠 20""按星期选物""跟队换图"
  四类最难机制；通关它之后，其他 PQ 大多是它的子集。

---

## 第六部分 · 风险登记册（本轮新增）

| 风险 | 概率 | 影响 | 缓解 |
|---|:--:|:--:|---|
| **共享 `BotClient` 上的 5 秒延迟回调判错人** | **高** | **高** | **改用私有 client（路线 C）** —— 一次性根治；`BotClientBinding` 作为模板 bot 的兜底 |
| 私有 client 的 `channel` 与 bot 实际频道不一致 | 中 | 高 | 创建时用 `BotChannelRouter` 分配，二者必须相同 |
| `Reactor.hitReactor` 在 `use_debug=true` 时因 `c.getPlayer()==null` NPE | 中 | 高 | 私有 client 已解决；模板 bot 走 `BotClientBinding` |
| `ReactorActionManager.dropItems` 因 `chr==null` **静默不掉落** | 高 | 高 | 同上；并加日志断言"确实掉了 N 个" |
| `calcDropPos` 吸附到**框外**地面 | 中 | 高 | 自检工具预先算出"框内地面点"，写死进 spec |
| 反应堆**命中次数硬编码错**（云 4 下 / 祭坛 1 下 / 音乐盒 1 下） | 中 | 中 | **不要硬编码**：循环打到"副作用出现"为止 |
| `use_enable_stage_skip` 被打开后 bot 逻辑多余 | 低 | 低 | spec 里加 `soloSkipsPuzzles` 开关，运行时判定 |
| 组合谜题 combo 每次随机（未固定时） | 低 | 中 | 读 EIM 属性，不猜 |
| 5 秒窗口内 bot 换图 → 回调落在错误地图 | 低 | 中 | 私有 client 下仍建议丢完原地待命 ≥6 秒 |

---

## 第七部分 · 给 v1/v2 的勘误回填

本文新坐实的结论，**推翻了前两份文档的以下内容**：

| 前文说法 | 本文修正 | 影响 |
|---|---|---|
| v2："把 20 个 4001063 丢进祭坛" | **必须是一叠恰好 20**（`getQuantity()==20`），不是 20 个散落 | 🔴 决定成败 |
| v2：祭坛面积 `±100` | 正确，但**给出了为什么第二块 `(-33,-36)/(34,81)` 不生效**的严格证明 | ✅ 澄清 |
| v2："让 bot 走真脚本路径打祭坛" | **这个方向是对的**（打 1 下就能触发 `act()`）；但**正规玩法是"丢一叠 20 个云"**，打它属于绕过道具消耗的捷径 | 🟠 细化 |
| v2：修 `CustomReactor` 加走真脚本的版本 | 正确，核心是**换成 `Reactor.hitReactor(...)` 走完整状态机**而非裸 `state++` | ✅ 细化 |
| v1：`HitReactorTask(reactorFilter, hits=4)` | "打 4 下"**只对云成立**；祭坛/音乐盒打 **1** 下即可。**不应硬编码次数** | 🟠 纠正 |
| （未提及） | **共享 client 与 5 秒延迟回调的时序竞争** | 🔴 新风险 |
| （未提及） | **`use_enable_stage_skip` 会改变"是否需要 bot"的判断** | 🟠 新维度 |
| （未提及） | **已精确算出祭坛/音乐盒的吸附落点** `(377,99)` / `(-1706,-172)` | ✅ 新成果 |

---

## 附录 · 证据速查（本轮新增）

| 结论 | 证据 |
|---|---|
| 触发要求单叠数量精确相等 | `MapleMap.java:2511`(`activateItemReactors`) / `:2526`(`searchItemReactors`) |
| `lt/rb` 只取第一次遇到的 event 块 | `ReactorFactory.java:128-137` |
| 祭坛框 `(±100,±100)`，第二块 `(-33,-36)/(34,81)` 不生效 | `Reactor.wz/2006000.img.xml` |
| 祭坛需要 `4001063`×20 | 同上 `imgdir/0/event/0` |
| 祭坛在 `(377,66)`，下方地面 `y=143` | `Map9/920010000.img.xml` reactor[0] + foothold `x1=360,x2=450,y=143` |
| **祭坛实际落点 `(377,99)`** | `calcDropPos`: `(377,143-85=58)` → `findBelow` → 首个 `y1>=58` 的 `x=377` 地面 = `y=99`（`x1=328,x2=426`） |
| **音乐盒实际落点 `(-1706,-172)`** | `(-1706,-240-85=-325)` → `findBelow` → `y=-172`（`x1=-1758,x2=-1660`） |
| 云反应堆 `2002001`：type=0，**打 4 下**触发 `act()`（掉 `4001063`） | `Reactor.wz/2002001.img.xml`（4 个 event 块）+ `Reactor.java:404-440` 状态机追踪 |
| 祭坛 `2006000`：type=100，**打 1 下**也触发 `act()`（生成 Eak） | `2006000.img.xml`（仅 2 个 event 块：state 0→1）+ 同上 |
| 音乐盒 `2008006`：type=100，**打 1 下**触发（循环型反应堆分支） | `2008006.img.xml`（7 个 event 块）+ 同上 |
| 云掉 `4001063`（chance=1） | `V1.0.58__reactordrops_insert_data.sql:706` |
| `2008006` 触发框 `(-52,-64)/(40,79)`、需要 `4001056+day` | `Reactor.wz/2008006.img.xml` |
| 音乐盒 `act()` → `statusStg3=0` | `scripts/reactor/2008006.js` |
| 物品落地先下移 85px 再吸附 | `MapleMap.calcDropPos` |
| `ActivateItemReactor` 5 秒延迟 + 依赖 `ownerClient` | `MapleMap.java:2551`、`MapItem.java:235` |
| `use_enable_stage_skip` 存在且只影响 2 个脚本 | `V1.11.3__*.sql`、`scripts/npc/{2013001,9020001}.js` |
| KPQ Stage2/3/4 是**组合谜题**（combo + 矩形） | `scripts/npc/9020001.js:34-88` |
| KPQ Stage1 单人硬卡 | `scripts/npc/9020001.js:142` |
| HenesysPQ `minLevel=10`、采种+保护月兔 | `scripts/event/HenesysPQ.js:28`、`scripts/npc/1012114.js` |
| LudiPQ `35-50`、9 个 statusStg | `scripts/event/LudiPQ.js:28,118-126` |
| Orbis 教学文本明确"collect 20 Magic Clouds" | `scripts/event/OrbisPQ.js` `playerEntry` |
