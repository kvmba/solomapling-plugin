# 新增能力：让 bot 在船上「真的航行」（走路 / 社交 / 聊天 / 看海）

> 目标：bot 不只是"warp 过去"，而是**买票上船 → 在甲板/船舱待满 10 分钟航程
> → 期间走动、看海、与其他 bot 聊天、遇到 Balrog 袭击时有反应 → 到站自然下船**。
>
> 本文只做规划，不含实现。前置依赖：`BOT_TRANSIT_OPTIMAL.md`（先把船接进世界图）。

---

## 〇、先看清：船旅程的真实结构

以 `Boats`（魔法密林 ⇄ 天空之城）为例，实测：

```
候车室 101000301  ──(entry=true 开门)──▶  甲板 200090000
                                            │  in00 / out00
                                            ▼
                                         船舱 200090001
                                            │
                          ◀── 航程 10 分钟（Boats.rideTime）──▶
                                            │
                                     arrived() 事件
                                            ▼
                              全员 warp → 200000100（天空之城）
```

| 环节 | 数据 |
|---|---|
| 等待开门 | `entry` 属性，最坏 4(关门)+5(开船) min |
| 航程 | **10 min**（`Boats.rideTime`），火车/小屋/精灵 5 min，飞机 1 min |
| 甲板 | `200090000`（去魔法密林）/ `200090010`（去天空之城） |
| 船舱 | `200090001` / `200090011`，与甲板经 `in00`/`out00` 互通 |
| 终点 | `arrived()` 里 `warpEveryone` 到目的站 |

### 关键实测数据

| 项 | 结果 | 含义 |
|---|---|---|
| **能走路吗** | **能**。船图有 39–75 个 foothold 块 | 可漫游，不是空气墙 |
| **活动范围** | `VRLeft=-1000, VRTop=-850, VRRight=630, VRBottom=650` | 约 1630×1500 px 的可走矩形 |
| **有 NPC 吗** | **0 个** | 没有售票员/船员可交互 |
| **静态怪** | **0 个**（船图 life 段为空） | 不会自动遇怪 |
| **动态怪** | **有**：42% 概率 Balrog 袭击，刷 `8150000` 赤色巴洛古 ×2 | 天然的战斗/骚动场景 |
| **`fieldLimit`** | `401530` = MOVEMENTSKILLS + DOOR + CANNOTMIGRATE + CANNOTVIPROCK + **CANNOTJUMPDOWN** | 禁止跳下、禁止换频道/商城、禁止开门 |
| **`fieldType`** | `2` | 载具类地图 |
| **音乐** | `Bgm04/UponTheSky`；Balrog 时切 `Bgm04/ArabPirate` | 可作氛围判断 |

---

## 一、能力分层

```
B4  事件反应层   Balrog 袭击：惊慌走位 / 聊天惊呼 / 躲进船舱
     ↑
B3  社交层       船上对话（看海、目的地、晕船…）、成对聊天、表情
     ↑
B2  行为层       甲板↔船舱走动、靠栏杆看海、找位置坐下/站定
     ↑
B1  在场层       上船后真的停留满航程（不提前 warp 走）
```

**B1 是前提**：现在 bot 一上船就会被 `GCTravel` 的 `finish()` 判定"已到达"
（因为 `bot.getMapId() == trip.destMapId` 不成立时会继续跳；但船图是中间态，
需要显式支持"路过地图不停留"→"船图要停留"）。

---

## 二、B1 · 在场层（让 bot 真的待满航程）

### 问题

`GCTravel.tick()` 的逻辑是：**只要没到目的地图，就继续找下一跳**。
bot 一进船图（`200090000`），`cur != trip.destMapId`，会立刻尝试
`findPortalTo(200090000 → 200000100)` —— 船图**没有**通往目的站的传送门
（出边只有 `200090001` 船舱 和 `999999999`），于是走到底部的
`warp(bot, nextHop, "no walkable portal...")` —— **裸 warp 掉，航程被跳过**。

### 方案：认识"载具地图"

加一个载具地图表（或按 WZ 的 `fieldType == 2` 判定），
`GCTravel` 看到当前图是载具图时：

1. **不寻路**（没有路可寻）
2. **进入"在船上"状态**，复用 §三的等待态豁免看门狗
3. 持续轮询**离船条件**：
   - 首选：`em.getProperty("docked") == "true"`（到站）
   - 或：检测地图被 `arrived()` 的 `warpEveryone` 换掉（`cur != lastMapId`）
4. 到站后 `finish(trip, true)`

> **不要用固定 10 分钟计时**。航程过 `getTransportationTime()` → `World.travelRate`
> （数据库配置，缺配置时为 0）。必须靠**事件属性或地图变化**判断到站。

### 需要的新状态

```java
// GCTravel.Trip 新增
boolean aboard;              // 正在船上
long aboardAtMs;
static final long ABOARD_MAX_MS = 20 * 60 * 1000;  // 兜底 20min（> 最长航程 10min）
```

---

## 三、B2 · 行为层（在船上做什么）

### 可复用的现有能力（不用重写）

| 能力 | 类 | 接口 |
|---|---|---|
| 全图漫游 | `BotWanderSystem` | `start(bot)` / `start(bot, anchorX, radius)` / `stop(bot)` |
| 定点占位 | `TownStation` | `claimSpot(bot)` / `relocate(bot, anchor)` / `releaseSpot(bot)` |
| 闲聊 | `BotChatter` | `maybeStartChatter(bot)`（自带 10% 概率、30–90s 冷却） |
| 表情 | `GCMovement` | `turnAround` / `duck` / `jumpInPlace` / `face` |
| 小动作 | `GCFidget` | 自动 fidget（需在 GC 控制下） |

> **注意**：`TownStation` 是按城镇设计的（`TownPresence.yaml` 配置城镇占位点），
> 船图不在配置里。建议**用 `BotWanderSystem.start(bot, anchorX, radius)` 的带状模式**，
> 把 bot 限制在甲板中段，避免走到船头/船尾的边界外。

### 建议的行为脚本

```
上船瞬间
  └ 走到甲板中后段（anchor = VR 中心偏后，radius ≈ 400px）
      ├ 60% 停留看海（面向船舷，周期性 turnAround / duck）
      ├ 25% 走动（BotWanderSystem 小半径漫游）
      └ 15% 进船舱坐一会儿（经 in00 → 200090001，待 60–120s 再出来）

每 30–90s（BotChatter 自带节奏）
  └ 触发一次船上对话或表情
```

### 新增强化（可选）

- **靠栏杆偏好**：让 bot 倾向停在 VR 左右边缘（模拟扶栏看海）
  —— 可在 `BotWanderSystem` 的 banded 模式上加一个"边缘偏好"权重
- ~~坐下~~：**船图无椅子**（已扫 `200090xxx` 全部船图，无 `chair`/`seat` 字段），
  此项不做；改为"靠栏杆站定"

---

## 四、B3 · 社交层（船上聊天）

### 现状

`BotChatter` 已有完整的成对聊天机制（10% 概率、180px 半径内配对、
30–90s 冷却、25% 概率带表情）。**船上不需要新机制，只需要新台词**。

### 建议：新增「船上对话包」

在 `BotDialoguePack/` 新增 `OnBoardDialogue.yaml`（或复用 `TownChatterDialogue.yaml`
加一个 `onBoard` 分类）：

| 触发情境 | 台词方向 |
|---|---|
| 刚上船 | 「终于上船了」「找个位置吧」 |
| 航行中 | 「海好大啊」「还要多久到？」「有点晃…」 |
| 看海 | 「那边是不是有鱼？」「云好低」 |
| 目的地 | 「去天空之城做生意」「回魔法密林」 |
| 快到了 | 「看到港口了」「准备下船」 |
| **Balrog 袭击** | 「什么声音？！」「巴洛古！」「快进船舱！」 |

### 配对半径要放大

`CHATTER_RADIUS = 180`（px）是按城镇街道设计的。
甲板有 1630×1500 px，**建议船上放宽到 400–600**，
否则同船 bot 很难凑够对话距离。

---

## 五、B4 · 事件反应层（Balrog 袭击）

### 事件结构（`Boats.js` 实测）

```
开船后 3min + rand(0~1min)  → approach()  42% 概率触发
   ├ setProperty("haveBalrog","true")
   ├ broadcastEnemyShip(true)         ← 客户端显示敌船
   └ musicChange("Bgm04/ArabPirate")  ← 音乐切换
5s 后 → invasion()
   └ 在甲板刷 8150000 赤色巴洛古 ×2（各方向）
```

### bot 可以怎么反应（由简到繁）

| 等级 | 行为 | 实现 |
|:--:|---|---|
| L0 | **不反应**（现状） | — |
| L1 | 停下当前动作，面向怪物 | 轮询 `haveBalrog` 后 `face()` |
| L2 | 聊天惊呼 | 触发 `OnBoardDialogue` 的「巴洛古！」分类 |
| L3 | 躲进船舱 | `in00` → `200090001`，待到 `haveBalrog=false` 再出来 |
| L4 | 参与战斗 | 需 bot 有战斗能力 + 船图准入，**复杂，建议不做** |

**建议做到 L2–L3**：读 `em.getProperty("haveBalrog")`，
为 true 时触发惊呼台词 + 部分 bot 躲进船舱，为 false 后返回甲板。

---

## 六、改动清单

| # | 文件 | 改动 | 层 |
|:--:|---|---|:--:|
| 1 | `GCTravel.java` | 载具地图判定 + `aboard` 状态 + 到站检测 | B1 |
| 2 | `GCTravel.java` | 复用 §三等待态豁免（已有 `waitingForTransit`） | B1 |
| 3 | 新增 `GCTransitMap.java`（或常量表） | 载具地图表：`200090000/001/010/011`、`200090100/110`、`200090200/210`、`200090300/310`、`200090400/410` | B1 |
| 4 | `BotWanderSystem.java` | 可选：banded 模式加"边缘偏好" | B2 |
| 5 | 新增 `OnBoardDialogue.yaml` | 船上对话包 | B3 |
| 6 | `BotChatter.java` | 船上放宽 `CHATTER_RADIUS` | B3 |
| 7 | 新增 `GCOnBoard.java`（或并入 GCTravel） | Balrog 反应：惊呼 + 躲船舱 | B4 |

**不需要动**：`GCWorldGraph`（路由）、`GCPortals`、bot 类型、坐椅子（船图无椅待确认）。

---

## 七、实施顺序

| 步 | 内容 | 验证 |
|:--:|---|---|
| **1** | 载具地图表 + `aboard` 状态 + 到站检测 | bot 上船后**不再被裸 warp**，能待满 10min |
| **2** | 上船后启动 `BotWanderSystem`（带状） | bot 在甲板走动，不卡边界 |
| **3** | 船上对话包 + 放宽配对半径 | 同船 bot 会聊天 |
| **4** | 甲板 ⇄ 船舱进出 | 部分 bot 会进船舱待一会儿 |
| **5** | Balrog 反应（惊呼 + 躲船舱） | 42% 概率的航程里有可见反应 |

**第 1 步是里程碑**：只要不做它，后面全部无效——
bot 现在一上船就会被 `warp()` 掉，根本看不到航程。

---

## 八、风险与约束

| 风险 | 说明 | 缓解 |
|---|---|---|
| **航程不可硬编码** | `rideTime` 过 `World.travelRate`，缺配置为 0 | 靠 `docked` 属性或地图变化判断到站，不计时 |
| **船图无 NPC** | 0 个 NPC | 不能复用 `GCTaxi.npcPos()`；占用坐标/VR 中心 |
| **CANNOTJUMPDOWN** | `fieldLimit` 含此位 | bot 不能跳下甲板；寻路需尊重该限制 |
| **多个 bot 挤一起** | 甲板 1630×1500 也有限 | 用 banded radius 分散；或按上船顺序错开 anchor |
| **走到船边界外** | VR 外会触发 OOB 恢复（现有机制会 teleport 回来） | 限制 radius 在 VR 内；现有 `tickFallOffMapRecovery` 兜底 |
| **Balrog 战斗** | bot 在船上被怪打死？ | bot 是装饰品无死亡；但建议 L4 不做战斗 |
| **同船 bot 数量** | 一次航班可能只有 1 个 bot | 聊天配对需 ≥2；单人时退化为自言自语/表情 |
| **`CANNOTMIGRATE`** | 船上不能换频道/商城 | bot 无此需求，忽略 |

---

## 九、明确不做

| 项 | 理由 |
|---|---|
| 船上战斗（L4） | bot 战斗系统在船上未验证；且 42% 概率，收益低 |
| 船票/金币 | bot 无经济系统（沿用"纯移动"定位） |
| 船图作为练级图 | `TrainingMapFinder` 会因 `mobCount()<2` 过滤掉，且船是过渡态 |
| 椅子/道具交互 | **船图无椅子**（已扫全部 `200090xxx`，无 `chair`/`seat`），不做 |
| 甲板钓鱼等玩法 | 非交通范畴 |
