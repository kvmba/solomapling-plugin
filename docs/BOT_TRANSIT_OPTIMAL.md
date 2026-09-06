# 最优实施方案：让 bot 像玩家一样跨大陆通行

> 目标：bot 能像真实玩家那样坐 **出租车 / 船 / 火车 / 小屋 / 精灵 / 鲸 / 飞机 / 电梯**
> 在各大陆之间往来，而不是走到一半没路就裸 warp。
>
> 本文是**可直接照着改代码**的最优方案。交通数据底稿见 `TRANSPORT_ROUTES.md`，
> 分层说明见 `BOT_TRANSIT_PLAN.md`。

---

## 〇、一句话结论

> **不要新建"交通系统"，而是把 `GCTaxi` 的 `TaxiEdge` 加一个字段泛化成"交通边"，
> 让所有交通工具复用同一套 executor。**
>
> 因为船/车/飞机/电梯/出租车在**结构上完全同构**：
> `走到某个点 → 停留 → warp 到目的地图`。
> 唯一区别是"停留多久"由什么决定。

```
目前：  TaxiEdge(fromMap, npcId, toMap)                 → 只能表达出租车
改后：  TransitEdge(fromMap, npcId, toMap, kind, event) → 出租车/载具/直达 全涵盖
```

**路由零改动**：`GCWorldGraph.neighbors()` 已在把 taxi 边并进 BFS，
泛化后新边自动进入路由，**不需要碰搜索代码**。

---

## 一、改动清单（共 4 个文件）

| # | 文件 | 改动 | 量 |
|:--:|---|---|---|
| 1 | `GCTaxi.java` | `TaxiEdge` → `TransitEdge`（加 `kind` + `eventName`）；新增交通表 | 中 |
| 2 | `GCTravel.java` | 加 `waitingForTransit` 状态 + 看门狗豁免 + 轮询 `entry` | 中 |
| 3 | `TrainingRegions.java` | 白名单加窗口 | 极小 |
| 4 | `MapleVersionManager.java` | `version` 55 → 83 | 极小（1 个数字） |

**不需要动**：`GCWorldGraph`（路由）、`GCPortals`、`BotScriptedWarp`（可选复用）、
所有 bot 类型（`TrainingBot`/`SocialBot`/`TownWandererBot`）。

---

## 二、第一步：泛化交通边（核心）

### 2.1 数据结构

```java
// GCTaxi.java —— 建议类名保持 GCTaxi（改动最小），内部泛化
enum TransitKind {
    CAB,        // 出租车：固定 2–6s 停留（已实现）
    DIRECT,     // NPC 付费直达：短停留（~2s），无时刻表
    VEHICLE     // 船/车/飞机：轮询 em.getProperty("entry") 等开门
}

record TransitEdge(int fromMapId, int npcId, int toMapId,
                   TransitKind kind, String eventName) {
}
```

`eventName` 仅 `VEHICLE` 用（`"Boats"`/`"Trains"`/`"Cabin"`/`"Genie"`/`"AirPlane"`）。

### 2.2 交通表（本次要接的全部边）

```java
// ① 出租车（现有 5 镇互联，Nautilus 因版本门控自动剔除）
//    kind=CAB, eventName=null —— 行为完全不变

// ② NPC 付费直达 / 即时载具：kind=DIRECT
new TransitEdge(200000141, 2090005, 250000100, DIRECT, null), // Hak 天空之城→武陵
new TransitEdge(250000100, 2090005, 200000141, DIRECT, null), // Hak 武陵→天空之城
new TransitEdge(250000100, 2090005, 251000000, DIRECT, null), // Hak 武陵→药草镇
new TransitEdge(102000000, 9310000, 701000000, DIRECT, null), // 上海 勇士部落→外滩
new TransitEdge(701000100, 9310013, 102000000, DIRECT, null), // 上海 外滩→勇士部落

// ③ 时刻表载具：kind=VEHICLE
new TransitEdge(101000301, 1032009, 200000100, VEHICLE, "Boats"),  // 船 密林→天空
new TransitEdge(200000112, 2012002, 101000300, VEHICLE, "Boats"),  // 船 天空→密林码头
new TransitEdge(200000122, 2041001, 220000100, VEHICLE, "Trains"), // 火车 天空→玩具城
new TransitEdge(220000111, 2041001, 200000100, VEHICLE, "Trains"), // 火车 玩具城→天空
new TransitEdge(200000132, 2012022, 240000100, VEHICLE, "Cabin"),  // 小屋 天空→神木村
new TransitEdge(240000111, 2082002, 200000100, VEHICLE, "Cabin"),  // 小屋 神木村→天空
new TransitEdge(200000152, 2012024, 260000100, VEHICLE, "Genie"),  // 精灵 天空→阿里安特
new TransitEdge(260000110, 2102001, 200000100, VEHICLE, "Genie"),  // 精灵 阿里安特→天空
```

> **⚠ Cabin 终点写 `240000100` 不是 `240000110`**
> `Cabin` 事件的落地是 `240000110`，但该图**无任何传送门出边（死路）**；
> 能走进神木村的是 `240000100`（`240000000 → 240000100 → 240000110` 链）。
> 终点填 `240000100`，否则 bot 下船即卡死。

### 2.3 电梯：单独走 `BotScriptedWarp`（不进交通表）

电梯**两端都没有 NPC**（已核实 `222020100`/`222020200` NPC 数 = 0），
所以不能复用 `GCTaxi.npcPos()` 的按 NPC 定位。但它有 `in00` 门：

```java
// BotScriptedWarp.EDGES 加两条（走门名定位，无需 NPC）
new WarpEdge(222020100, "in00", 222020200, 0),  // 太阳塔 2F → 99F
new WarpEdge(222020200, "in00", 222020100, 0),  // 太阳塔 99F → 2F
```

> **电梯的真实链路**（查 `elevator.js` 得出，与原设想不同）：
> 踏 `in00` 门 → 先 warp 到**中间等待图** `222020110`（上行）/`222020210`（下行）
> → 由 `Elevator` 事件在 `beginTime`（60s）后 `warpEveryone` 到对面。
> **不是直接到对面。** 所以接入后 bot 会在等待图停留约 60s，
> 需确保看门狗豁免覆盖（见 §三）。

---

## 三、第二步：等待与看门狗豁免

### 3.1 问题的量级

| 场景 | 需要等多久 | 看门狗是否会误杀 |
|---|--:|:--:|
| 出租车 | 2–6s | 否（`settled` 快速路径提前 return） |
| NPC 直达 / Hak | ~2s | 否 |
| **电梯** | **~60s** | **会**（`HOP_STUCK` 12s） |
| **船/车/飞机** | **0–19min** | **必然** |

### 3.2 改动：给 `Trip` 加等待态

```java
// GCTravel.Trip 新增字段
TransitKind waitingKind;     // null = 不在等
long waitStartAtMs;
static final long WAIT_MAX_MS = 25 * 60 * 1000;   // 25min 兜底（> 船周期 19min）
```

在 `approachAndAct()` 开头，三个看门狗之前统一判断：

```java
if (trip.waitingKind != null) {
    if (nowMs() - trip.waitStartAtMs > WAIT_MAX_MS) {
        warp(bot, nextHop, "TRANSIT-WAIT-TIMEOUT");   // 兜底，防永久滞留
        return;
    }
    if (tryDepart(trip, bot, edge)) {                 // 条件满足就发车
        trip.waitingKind = null;
    }
    return;                                           // ← 关键：跳过三个看门狗
}
```

**三个看门狗一行都不用改** —— 只要在它们之前 `return` 即可。

### 3.3 发车条件 `tryDepart()`

```java
switch (edge.kind()) {
    case CAB, DIRECT -> 停留 2–6s 后发车（DIRECT 可稍短）
    case VEHICLE -> {
        EventManager em = bot.getMap().getChannelServer()
                              .getEventSM().getEventManager(edge.eventName());
        return em != null && "true".equals(em.getProperty("entry"));
    }
}
```

（已用编译探针验证插件可直接引用 `EventManager`，无需 client。）

**必须轮询、不能算时刻**：所有时间过 `em.getTransportationTime()` → `World.travelRate`
（数据库配置），且 `GameConfig.getWorldFloat()` 缺配置时返回 **0**，会让运输时间归零。

### 3.4 同步放大上层超时

`TrainingBot.TRAVEL_TIMEOUT_MS`（120s）也要按行程放大，
否则 bot 大脑会先于 `GCTravel` 放弃。建议改为按"是否跨大陆"区分：
本大陆 120s，跨大陆（含载具）25min。

---

## 四、第三步：白名单与版本

### 4.1 白名单（`TrainingRegions.ALLOWED`）

```java
{100000000, 110000000},   // 维多利亚岛（已有）
{200000000, 201000000},   // 天空之城（已有）
{211000000, 212000000},   // 冰封雪域（已有）
{220000000, 223000000},   // 玩具城（已有）
{230000000, 240000000},   // 水世界   ← 新增（顺带合法化现有绕道）
{240000000, 250000000},   // 神木村   ← 新增（需先接 Cabin）
{250000000, 252000000},   // 武陵     ← 新增（需先接 Hak）
{260000000, 262000000},   // 阿里安特 ← 新增（需先接 Genie）
{700000000, 710000000},   // 中国·上海 ← 新增（需先接上海飞机）
```

### 4.2 版本阈值

```java
// MapleVersionManager.java
public static int version = 83;   // 原 55
```

服务器真实版本就是 v83（`ServerConstants.VERSION = 83`）。
改后时间神殿(72)、诺特勒斯(62)、圣地(73)、塔拉森林(83)、马来西亚(77) 纳入。

> **风险**：这些地图此前从未被 bot 访问。建议先用 `!gcmove route`
> 抽样验证几张新开放图能正常寻路，再改。

---

## 五、实施顺序（每步都可独立验证）

| 步 | 改动 | 验证 |
|:--:|---|---|
| **1** | `version` 55→83（先抽样） | `!gcmove route 270000100` |
| **2** | `TransitEdge` 泛化 + Hak 两条边 + 武陵白名单 | bot 从天空之城走到武陵 |
| **3** | 电梯两条 `BotScriptedWarp` 边 + §三等待态 | bot 从玩具城本岛到韩国民俗村 |
| **4** | 上海两条边 + 上海白名单 | bot 从勇士部落到上海外滩 |
| **5** | `VEHICLE` 支持 + Boats 两条边 + 神木村白名单 | bot 从魔法密林坐船到天空之城 |
| **6** | Trains / Cabin / Genie 六条边 + 对应白名单 | 玩具城、神木村、阿里安特可达 |
| **7** | 多段行程联调 + 日志观察 | 长行程成功率 |

**第 2 步是里程碑**：只需 1 个 record 改造 + 2 条边 + 1 个白名单窗口，
bot 就能**第一次走出原大陆**。

---

## 六、关键约束（踩过的坑）

| 约束 | 说明 |
|---|---|
| **NPC id 以 WZ 为准** | `GCTaxi.npcPos()` 走 `map.getNPCById()`。魔法密林线售票 `1032007`、检票 `1032009`（船员普林）是两人 |
| **Cabin 终点 = `240000100`** | 落地 `240000110` 是死路 |
| **电梯是两段式** | 门 → 中间等待图 `222020110`/`222020210` → 事件带走，非直达 |
| **电梯无 NPC** | 两端 NPC 数 = 0，只能按门名 `in00` 定位 |
| **Hak 有并发上限 8** | 走 `em.startInstance()`，`EventManager.maxLobbys = 8`，占满时 NPC 拒绝 |
| **Hak 不是纯 warp** | 天空之城/武陵 之间走 `startInstance`；武陵⇄药草镇（slct==2）是纯 `cm.warp` |
| **外滩 ↔ 城市广场可走** | `701000000 → 701000100` 有传送门，返程 NPC `9310013` 在广场，可达 |
| **船票/金币不做** | bot 是装饰品无经济系统；沿用出租车"纯移动"定位 |
| **NPC 不在图上** | 地图未加载时 `getNPCById` 返回 null，已有退化逻辑（直接 warp），保留 |

---

## 七、明确不做

回城卷（按要求不纳入）· 英语村（未开放）· 新手村单向路（进去出不来）·
钓鱼场/婚庆/PQ 副本（非交通）· 万能传送（GM 菜单）
