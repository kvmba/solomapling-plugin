# 总纲：bot 跨大陆通行 + 船上航行 完整实施方案

> 一份文档说清**全部**：要改什么、按什么顺序、注意什么、坑在哪。
>
> 底层数据 `TRANSPORT_ROUTES.md` · 分层思路 `BOT_TRANSIT_PLAN.md`
> 交通接入 `BOT_TRANSIT_OPTIMAL.md` · 船上行为 `BOT_ONBOARD_PLAN.md`

---

## 第一部分 · 全局认知

### 1.1 四道闸门（bot 跨大陆必须全过）

| # | 闸门 | 代码位置 | 现状 |
|:--:|---|---|---|
| ① | 世界图有没有这条边 | `GCWorldGraph.neighbors()` | 只有传送门+出租车+`BotScriptedWarp`；**船/车/飞机/电梯全无** |
| ② | 目的大陆在白名单吗 | `TrainingRegions.ALLOWED` | 只有**维多利亚岛、天空之城、玩具城** |
| ③ | 内容被版本剪了吗 | `MapleVersionManager.version=55` | v83 的时间神殿(72)、诺特勒斯(62)、圣地(73) 被误剪 |
| ④ | **看门狗会不会误杀** | `GCTravel` 三处 | 设计前提是"一跳 90s 内走完"；**等船必然被杀** |

> ①②③ 是配置问题，④ 是结构问题。**④ 最容易被低估**。

### 1.2 交通工具的真实分类（决定实现方式）

| 类型 | 代表 | 等待机制 | 实现方式 |
|---|---|---|---|
| **无等待** | 出租车、Hak、上海飞机、道具门 | 无 | `TransitEdge(DIRECT)` 或 `BotScriptedWarp` |
| **短等待** | 电梯 ~60s | 事件 `beginTime` | `BotScriptedWarp` + 等待态豁免 |
| **长等待** | 船/火车/小屋/精灵 0–19min | `em.getProperty("entry")` | `TransitEdge(VEHICLE)` + 等待态豁免 |
| **两段式** | 电梯 | 门 → 中间图 → 事件带走 | 需认识中间图 |

### 1.3 必须记住的五个数字

| 数字 | 含义 |
|--:|---|
| **55 → 83** | 版本阈值（服务器真实 v83） |
| **90s** | `HOP_MAX_MS`，每跳上限 |
| **12s / 20s** | `HOP_STUCK_MS` / `SOFT_LOCK_MS` |
| **19min** | 船周期（4 关门 + 5 开船 + 10 航程） |
| **8** | `EventManager.maxLobbys`，Hak 并发上限 |

---

## 第二部分 · 方案核心

### 2.1 一句话设计

> **把 `GCTaxi.TaxiEdge` 泛化成 `TransitEdge`（加 `kind` + `eventName`），
> 所有交通工具复用同一套 executor。**

```java
record TransitEdge(int fromMapId, int npcId, int toMapId,
                   TransitKind kind, String eventName) {}

enum TransitKind { CAB, DIRECT, VEHICLE }
```

**为什么这是最优解**：船/车/飞机/电梯/出租车**结构同构**
（`走到某点 → 停留 → warp`），唯一区别是"停留多久"由什么决定。
且 `GCWorldGraph.neighbors()` 已把 taxi 边并进 BFS，**路由零改动**。

### 2.2 看门狗豁免的最简做法

**三个看门狗一行都不用改** —— 在它们之前 `return`：

```java
// approachAndAct() 开头，HOP_MAX_MS 判定之前
if (trip.waitingKind != null) {
    if (nowMs() - trip.waitStartAtMs > WAIT_MAX_MS) {
        warp(bot, nextHop, "TRANSIT-WAIT-TIMEOUT");  // 25min 兜底
        return;
    }
    if (tryDepart(trip, bot, edge)) trip.waitingKind = null;
    return;   // ← 关键：跳过 HOP_STUCK / SOFT_LOCK / HOP_MAX
}
```

### 2.3 船上航行的额外状态

`GCTravel.tick()` 现在**不知道"船图是中间态"**，会让 bot 一上船就被 warp 掉。
需要：

```java
boolean aboard;                                  // 正在船上
static final long ABOARD_MAX_MS = 20 * 60 * 1000; // 兜底
```

到站检测**不能计时**，要用：
- `em.getProperty("docked") == "true"`，或
- 地图被 `arrived()` 的 `warpEveryone` 换掉（`cur != lastMapId`）

---

## 第三部分 · 改动清单

| # | 文件 | 改动 | 阶段 |
|:--:|---|---|:--:|
| 1 | `MapleVersionManager.java` | `version` 55 → 83 | A |
| 2 | `GCTaxi.java` | `TaxiEdge` → `TransitEdge`（+`kind`/`eventName`）+ 交通表 | A |
| 3 | `GCTravel.java` | `waitingKind` 等待态 + 三看门狗前 return + `tryDepart()` | A |
| 4 | `GCTravel.java` | `aboard` 状态 + 载具地图判定 + 到站检测 | B |
| 5 | 新增 `GCTransitMap.java` | 载具地图表（12 张） | B |
| 6 | `TrainingRegions.java` | 白名单加 5 个窗口 | A |
| 7 | `BotScriptedWarp.java` | 电梯 2 条边（`in00`） | A |
| 8 | `BotChatter.java` | 船上放宽 `CHATTER_RADIUS` 180 → ~500 | C |
| 9 | 新增 `OnBoardDialogue.yaml` | 船上对话包 | C |
| 10 | `GCOnBoard.java`（新） | Balrog 反应：惊呼 + 躲船舱 | C |

**不动**：`GCWorldGraph`（路由）、`GCPortals`、所有 bot 类型。

---

## 第四部分 · 实施步骤

### 阶段 A：让 bot 能过去（交通接入）

| 步 | 内容 | 验证 |
|:--:|---|---|
| A1 | `version` 55→83（先抽样） | `!gcmove route 270000100` |
| A2 | `TransitEdge` 泛化 + **Hak** 两条边 + 武陵白名单 | **bot 第一次走出原大陆** ← 里程碑 |
| A3 | 等待态 + 三看门狗豁免（先只接 DIRECT） | 电梯 60s 不被杀 |
| A4 | 电梯 2 条 `BotScriptedWarp` 边 | bot 到韩国民俗村 |
| A5 | 上海飞机 2 条边 + 上海白名单 | bot 到上海外滩 |
| A6 | `VEHICLE` 支持 + **Boats** 2 条边 + 神木村白名单 | bot 坐船到天空之城 |
| A7 | Trains / Cabin / Genie 六条边 + 白名单 | 玩具城/神木村/阿里安特可达 |

### 阶段 B：让 bot 真的在船上（在场）

| 步 | 内容 | 验证 |
|:--:|---|---|
| B1 | 载具地图表 + `aboard` 状态 + 到站检测 | **bot 上船不再被 warp，待满 10min** ← 里程碑 |
| B2 | 上船启动 `BotWanderSystem`（banded，radius≈400） | 甲板走动，不卡边界 |

### 阶段 C：让船上像真的（行为/社交）

| 步 | 内容 | 验证 |
|:--:|---|---|
| C1 | 船上对话包 + 放宽配对半径 | 同船 bot 聊天 |
| C2 | 甲板 ⇄ 船舱进出（15% 概率待 60–120s） | 有 bot 进船舱 |
| C3 | Balrog 反应（惊呼 + 躲船舱，L2–L3） | 42% 航程有可见反应 |

### 阶段 D：联调

| 步 | 内容 |
|:--:|---|
| D1 | 多段行程（出租车→船→火车）成功率 |
| D2 | 日志观察：无卡候车室 / 无卡下船死路 / 无永久滞留 |

---

## 第五部分 · 全部坑点（按严重程度）

### 🔴 会导致功能完全失效

| 坑 | 说明 | 解法 |
|---|---|---|
| **船图被当普通图** | `tick()` 找不到路 → 底部 `warp()`，航程跳过 | B1：载具地图表 + `aboard` |
| **看门狗杀掉等待** | 12s/20s/90s vs 船 19min，必然误判 | A3：等待态在判定前 return |
| **航程硬编码** | `rideTime` 过 `World.travelRate`，缺配置返回 **0** | 靠 `docked` 属性/地图变化，不计时 |
| **Cabin 终点错** | 落地 `240000110` **无任何出边（死路）** | 终点填 `240000100` |

### 🟠 会导致行为不正确

| 坑 | 说明 | 解法 |
|---|---|---|
| **NPC id 用错** | `npcPos()` 走 WZ 实例，非脚本名。密林售票 `1032007` / 检票 `1032009` 是两人 | 一律以 WZ 为准 |
| **电梯是两段式** | 门 → 中间图 `222020110`/`222020210` → 事件带走，非直达 | 等待态要覆盖中间图 |
| **电梯无 NPC** | 两端 NPC 数 = 0 | 走 `BotScriptedWarp` 按门名 `in00` |
| **Hak 并发上限 8** | `startInstance()`，`maxLobbys=8`，占满拒绝 | 重试/错峰 |
| **Hak 两种模式** | 天空⇄武陵走 `startInstance`；武陵⇄药草镇（slct==2）是纯 `cm.warp` | 区分处理 |
| **`TrainingBot.TRAVEL_TIMEOUT_MS`=120s** | bot 大脑会先于 GCTravel 放弃 | 同步放大（跨大陆 25min） |

### 🟡 需要注意的细节

| 坑 | 说明 |
|---|---|
| 船图无 NPC | 0 个，不能按 NPC 定位，用 VR 中心 |
| 船图无椅子 | 已扫 `200090xxx` 无 `chair`/`seat`，"坐下"不做 |
| `CANNOTJUMPDOWN` | 船图 `fieldLimit=401530` 含此位，禁止跳下 |
| 甲板很宽 | 1630×1500 px，`CHATTER_RADIUS=180` 太窄，需放宽 |
| 同船可能只有 1 个 bot | 聊天需 ≥2 配对，单人退化为表情 |
| 白名单空扫 | 只加白名单不接交通，`TrainingMapFinder` 白扫浪费 |
| 版本 55→83 面大 | 大量未验证地图同时开放，先抽样 |

---

## 第六部分 · 验收标准

| 能力 | 验收 |
|---|---|
| 出租车 | 维多利亚 5 镇互通，停留 2–6s（**已有，回归不破坏**） |
| Hak | 天空之城 ⇄ 武陵，bot 能往返 |
| 电梯 | 玩具城本岛 ⇄ 韩国民俗村 |
| 上海飞机 | 勇士部落 ⇄ 上海外滩 |
| 船 | 魔法密林 ⇄ 天空之城，**待满 10 分钟不被 warp** |
| 火车/小屋/精灵 | 玩具城 / 神木村 / 阿里安特可达 |
| 船上行为 | 甲板走动、看海、不卡边界 |
| 船上社交 | 同船 bot 会聊天（看海/目的地/晕船） |
| Balrog | 42% 航程有惊呼 + 部分躲进船舱 |
| 稳定性 | 日志无卡死、无永久滞留、无裸 warp 泛滥 |

---

## 第七部分 · 明确不做

| 项 | 理由 |
|---|---|
| 票务 / 金币消耗 | bot 是装饰品，无经济系统（沿用出租车"纯移动"定位） |
| 回城卷 | 按范围要求不纳入 |
| 英语村 | 未开放内容，无入口 |
| 新手村单向路 | 只出不进，进去永久滞留 |
| 钓鱼场 / 婚庆 / PQ 副本 | 非交通节点 |
| 万能传送 | GM 菜单，玩家不可用 |
| 船上战斗 | 战斗系统在船上未验证；42% 概率，收益低 |
| 船图当练级图 | `mobCount()<2` 会被过滤，且是过渡态 |
| 坐椅子 | 船图无椅子 |

---

## 附录 · 关键数据速查

**载具落点**（`kind=VEHICLE`）

| 载具 | 候车室（去/回） | 检票 NPC | 到达 | 航程 |
|---|---|---|---|--:|
| `Boats` | `101000301`/`200000112` | `1032009`船员普林 / `2012002`阿霖 | `200000100`/`101000300` | 10min |
| `Trains` | `200000122`/`220000111` | `2041001`俄林 | `220000100`/`200000100` | 5min |
| `Cabin` | `200000132`/`240000111` | `2012022`帕拉斯 / `2082002`哈利 | **`240000100`**/天空 | 5min |
| `Genie` | `200000152`/`260000110` | `2012024`意奈特 / `2102001`斯林 | `260000100`/`200000100` | 5min |

**直达边**（`kind=DIRECT`）：Hak `2090005`（`200000141`⇄`250000100`、武陵⇄药草镇）、
上海 `9310000`/`9310013`（`102000000`⇄`701000000`/`701000100`）

**载具地图表**（12 张，用于 `aboard` 判定）
`200090000/001/010/011`（Boats）、`200090100/110`（Trains）、
`200090200/210`（Cabin）、`200090300/310`（Hak）、`200090400/410`（Genie）

**白名单窗口**
`{230000000,240000000}` 水世界 · `{240000000,250000000}` 神木村 ·
`{250000000,252000000}` 武陵 · `{260000000,262000000}` 阿里安特 ·
`{700000000,710000000}` 中国·上海
