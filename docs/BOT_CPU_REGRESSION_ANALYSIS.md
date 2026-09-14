# Bot 数量调整后 CPU 上升分析报告（7% → 20%）

> 分支：`optimize/performance` @ `ebd5dd7`（HEAD，2026-09-14）
> 对照基线：用户报告 **同样 5000+ bot、无真玩家** 的场景，CPU 从约 **7%** 升至约 **20%**（约 2.9×）。
> 环境：2 核 4 线程（4 vCPU）。
> 方法：git 提交逐项审计（含 pickaxe 穷举新增定时器）+ 热路径源码走读 + 隔离微基准（宿主 `FootholdTree` 逐字拷贝 + 复刻寻路/宠物查询形状）。
> 结论先行：**不是 bot 数量变化带来的线性放大**（数量 09-09 已翻倍并被 LOD/节流吸收，7% 基线即在该时点之后测得）。**穷举证据指向唯一的新增全局定时器 —— 09-13 引入、09-14 被移除观察门控的【宠物跟随系统】，它单独就能解释大部分涨幅**；坐骑的 profile 维度扩展是次要项。

---

## 一、bot 数量的真实变化（排除"数量本身"这一因素）

按人口配置（`EnvironmentPopulation.yaml`）逐提交重算：

| 提交 | 日期 | 每频道练级 | ×3 频道 | 城镇驻点 | 漫游 | 合计（不含 FM 硬编码） |
|---|---|--:|--:|--:|--:|--:|
| `5df2f49` 起（每频道全量配额） | 09-09 | 1064 | 3192 | 538 | 272 | **~4002** |
| `ee1d3f9`（补 10 个 hub） | 09-10 | 1608 | 4824 | 2001 | 272 | ~7161 |
| `c3cc97a`（城镇减至 70%） | 09-11 | 1608 | 4824 | 1407 | 272 | **~6567** |

**关键差异点不在数字**：无论 7% 的具体测量点落在哪天（最可能是 `5df2f49` 之后——其提交信息记载"ch1 全量、world 4551 bot"，加上 FM 硬编码 ~800 个即"5000 多"；也可能在 `c3cc97a` 之后），**宠物系统（09-13 引入、09-14 移除观察门控）都落在两次测量之间的窗口内**，且如 §二 的穷举所示，它是该窗口内**唯一新增的全局定时任务**。

而 7%→20% 的观测发生在 **09-13/09-14**。这段时间 bot 总量并未再翻倍（09-11 后还**减了**城镇人数），且用户明确"同样的 bot 数量"。所以问题在于：**新增了"不依赖真玩家观察、也不吃 LOD 节流"的常驻开销**。

---

## 二、按提交定位：9/13–9/14 新增的常驻负载

对 `c3cc97a..HEAD`（105 个提交）逐项审计，找出"无真玩家时仍每 tick 跑"的新增项：

| # | 新增负载 | 提交 | 触发频率 | 每个单位成本 | 无观察时是否豁免 | 4核 CPU 估算 |
|:--:|---|---|---|---|---|---|
| **P1** | **宠物跟随（BotPetFollower）** | `dfa1d56`(09-13) → `fce3449`(09-14) | **全局单 tick，200ms** | 每宠物每 tick **1–3 次宿主 `FootholdTree.findBelow`** | ❌ **不豁免**（`fce3449` 刻意移除） | **单核 2%–16%**（口径见 §3.3） |
| P2 | 坐骑 tick（BotMount.tick） | `b6fe926`(09-13) | 每 bot 每次宏 tick | O(1) 判定 | 半豁免（宏 tick 有 30s/240s 节流，但 profile 维度扩展另计） | 见 §三.2 |
| P3 | 城镇门洞清理（SocialBot.maybeClearDoorway） | `b8de23a`(09-14) | 每 SocialBot 每次宏 tick | 站在门附近时遍历 Portal + 每 portal 一次 bot 索引查询 | 是（宏 tick 30s） | 低 |
| P4 | 聊天/whisper 收件箱 drain | `7479fdc`(09-14) | 每 bot 每次宏 tick | 空队列 ~3ns | 是（宏 tick） | 可忽略 |
| P5 | `BotDeath.adoptIfZeroHp` | `7e21e1d`(09-12) | 每 bot 每次宏 tick | O(1) | 是 | 可忽略 |
| P6 | 升级公告（BotLevelUpNotice.announce） | `d129657`(09-14) | 每次**静默升级** | **全服扫描 getAllCharacters + 逐角色 isBot** | 触发时才跑 | 低（事件稀疏） |

> **决定性的穷举结论**：对 `c3cc97a..HEAD` 全量 pickaxe（`-S scheduleAtFixedRate`、`-S 'newSingleThreadScheduledExecutor'`、新增 `TimerManager`/`POOL` 等），**唯一新增的全局固定频率任务就是 `BotPetFollower`**。P2–P6 全部搭在既有的、已被 LOD/宏 tick 节流的执行器上，无法解释"无真玩家时"的 3 倍上升。

**P1（宠物跟随）是唯一一个「无真玩家也全量跑、且每只宠物每次查询都调用宿主最贵的树查询」的新增项。** 详见下节。

---

## 三、P1 详析：宠物跟随系统（最大嫌疑）

### 3.1 事实链

1. **全局单 tick**：`BotPetFollower.start()` 用 `scheduleAtFixedRate(tick, 200, 200)`（`BotPetConfig.yaml: tick_ms: 200`），**每 200ms 遍历 `TRACKED` 集合**（所有携带宠物的 bot）。**没有按观察分档、没有按地图分组、不受任何 governor 拉伸**。
2. **观察门控在 `fce3449`（09-14 06:05）被移除** —— 这是"7%→20%"的时间线拐点。该提交前后：

   ```java
   // ❌ 之前（dfa1d56..fce3449）：未观察整只宠物直接跳过，成本 ≈ 0
   MapleMap map = chr.getMap();
   if (!GCMovement.isMapObserved(chr.getMapId())) {
       return; // LOD: nobody can see it, so neither the motion nor the packet is worth it
   }

   // ✅ 现在（fce3449 → HEAD）：follow 计算无条件执行，只有发包/说话/拾取被门控
   boolean observed = GCMovement.isMapObserved(chr.getMapId());
   ...
   if (swim) followSwim(chr, pet, idx, config, observed);   // ← 非 observed 也执行
   else      followLand(chr, pet, idx, config, observed);   // ← 非 observed 也执行
   if (observed) maybeSpeak(chr, pet, idx, config);
   ```

   提交说明自己解释了动机（防止进图闪现：玩家进图时 `spawnPlayerMapObject` 带着陈旧宠物坐标 → 下一 tick 被拉回 → 视觉闪烁），但代价是**整个世界的宠物在无人观看时也全量计算位置**。
3. **每条 follow 路径的宿主树查询次数（逐行精确计数，地面路径）**：
   - **移动中**（`moveTowards` 的稳态分支，宠物在追赶主人时常态）：**3 次 findBelow**
     1. `BotPetFollower.java:232` — `followLand` 里算目标点的地面（`groundSnap`）
     2. `BotPetFollower.java:290` — `moveTowards` 里算新位置的地面（`groundSnap`）
     3. `BotPetFollower.java:296` — `moveTowards` 里算新位置的陷洞 id（`BotPetController.footholdId`）
   - **静止死区内**：1 次（仅第 1 处）。
   - **主人攀绳 / 跳跃 / 水中**（`noGravity=true`）：0 次。
   - 平均（含城镇驻点静止、训练 bot 频繁移动）：按 **~2 次 / pet / tick** 估算。

4. **宿主 `FootholdTree.findBelow` 是 O(全树相关节点) + 每次 `LinkedList` 分配 + `Collections.sort`**（`GMS083/.../FootholdTree.java:160-190`：`getRelevants` 递归收集 → `xMatches` 新建链表 → 排序 → 三角函数插值）。插件自己的移动物理早已替换为"按列桶索引"（`findBelowIndexed`），**但宠物系统没有用这个索引，用的是宿主原版**。

### 3.2 微基准（宿主 `FootholdTree`/`Foothold` 逐字拷贝，隔离运行）

```
footholds= 110  findBelow =  1544.5 ns/op
footholds= 330  findBelow =  5343.5 ns/op   ← 中等城镇/猎场
footholds= 550  findBelow = 10282.2 ns/op   ← 大城镇（如魔法密林 1000+）
footholds=1100  findBelow = 21600.2 ns/op   ← 密林类大图
```

对照 bot 侧索引版（同数据、同选择数学）：

```
footholds= 165  indexed =   342.8 ns/op   （16.2× 快）
footholds= 440  indexed =   592.9 ns/op
footholds= 990  indexed =   890.1 ns/op   （24.3× 快）
```

### 3.3 数量级换算（无真玩家稳态）

宠物承载率 `p_max=0.30`，按等级/星级曲线实际抽样（见 `/tmp/petbench/petcalc.py`，20 万次抽样）：

- **每 bot 承载率 ≈ 10.9%**，承载者平均 **1.39 只**；
- **5000 bot ≈ 757 只宠物**（**7 367 bot ≈ 1 116 只**）。

| 每宠查询数 | 宿主查询/秒 | 110 陷洞 | 330 陷洞 | 550 陷洞 | 1100 陷洞 |
|--:|--:|--:|--:|--:|--:|
| ×1（静止为主） | 3 785/s | 0.6% | 2.0% | 3.9% | 8.2% |
| **×2（混合常态）** | **7 570/s** | **1.2%** | **4.0%** | **7.8%** | **16.4%** |
| ×3（移动为主） | 11 355/s | 1.8% | 6.0% | 11.7% | 24.5% |

（上表口径：宠物数 ~757，200ms tick=5Hz，单位是**单核百分比**。地图陷洞数取自真实 WZ：明珠港 338、废弃都市 519、魔法密林 1043。）

> 城镇驻点（静止为主，×1）与训练 bot（移动为主，×3）混合后落在 ×2 一档：**单核 4%–16%**。而宠物多活动的正是**城镇**（驻点 SocialBot 等级高、承载率高），且城镇地图普遍 400–1000+ 陷洞，正好落在最贵的档位；未观察时这些计算**全部照跑**。

**这解释了 7%→20% 里最大的那一块**：假设基线 7% 中宠物为 0，宠物项按上表实测算 ~3–12% 单核（跨核折算后），与观测到的 +13 个百分点量级吻合。若承载率比抽样更高（例如城镇高等级 bot 占比更大），差距还能完全覆盖。

### 3.4 为什么"LOD/节流"救不了它

- `BotPetFollower` 是**独立的全局调度**，不走 `BotTickService` 的 tick wheel（无 governor 拉伸），不走 `GrindTickRegistry`（无观察门控），也不受 `GCMovementDriver` 的 1s/4s 未观察节流影响。
- 宠物 tick 的 **200ms 是硬编码的全局频率**，与 bot 是否被观察、bot 的宏 tick 优先级完全无关。

对比：如果宠物跟随改走"观察门控 + 地图分档"，无真玩家时这部分开销会降到 **~0**。

---

## 四、次要嫌疑（逐项排除或降级）

### 4.1 P2 坐骑（`b6fe926` → `4bff919`）

- `BotMount.tick()` 挂在 `BotSM.tickRunnable` 最前，**每次宏 tick 都跑**。但 `tickRunnable` 本身受未观察 36–48s 节流（训练 bot 深磨 240–480s），所以**每秒总量很小**。
- **真正的放大点**：`BotMovementProfile.fromCharacter` 新增 `mounted()` 判定 → 骑乘时 `+20 speed / +10 jump` → **profile 分桶（5 一格）改变** → 导航图缓存 key `(mapId, speed, jump, snowshoes)` 多出一整维。70+ 级的 bot 中 35% 拥有坐骑，其中有相当比例在**未观察**时也处于骑乘态（骑乘态由 `tickRunnable` 维持，与观察无关）。
  - 后果 1：地图上可能被缓存 **两套图**（骑/不骑），图构建（bake）是重活。
  - 后果 2：`refreshMovementProfile` 每 20s 重算一次（`PROFILE_REFRESH_INTERVAL_MS`），**上下马（战斗时 `cancelForAction` 卸下、静默后重新上马）会让 profile 在 130/140 两桶间来回切换**，每次切换调用 `clearNavigationState`，随后 `warmGraphAsync` 重新加载/烘焙另一套图。
  - 对**无玩家**场景：不骑乘时 `mounted()` 为假、profile 与原一致；但**仍可能**因 `mount()` 后的首次 `fromCharacter` 触发一次图缓存未命中（`peekBestGraph` → 兜底 `peekClosestGraph`）。此效应为**一次性**（图建好后驻留），与 7%→20% 的**稳态**上升关系弱，故列次要。

### 4.2 P3/P4/P5/P6 排除理由

- P3 门洞清理：`onDoorway` 只对**站在门附近**的 bot 深算，普遍 tick 只做 1 次 X 向比较 + 短循环。且宏 tick 已被节流到 30s（未观察）。
- P4 收件箱 drain：空队列守卫 ~3ns，且 `PERFORMANCE_HOTSPOT_ANALYSIS.md` §1.3 已实测。
- P5 零血采纳：O(1) 判定（`down || !zeroedTemplateBot()`），`isBot` 单次 ~20ns。
- P6 升级公告：触发时才全服扫描（5000 角色 × `isBot`），按 09-14 新引入；但**升级事件稀疏**（静默路径 30 kills/min × 抽象 exp），且**计划文档明确"暂不节流"**。

### 4.3 已核查但确认**不是**问题的项

| 项 | 结论 |
|---|---|
| `BotTickService.drive()` 每 100ms 扫 5000 entries | 纯 volatile 读 + CAS，~50k/s，廉价 |
| `GrindTickRegistry.sweep` 250ms | 参与者有早退门控；未观察/非 GRIND 直接 return |
| `ObserverTracker.refresh` 每秒全服扫描 | 5000 角色 × `isBot`（含 `List.copyOf` 分配）≈ 0.1ms/s，0.01% core |
| `BotEquipChecker` 每 2min 全服扫描 | 摊薄 < 0.01% core |
| `GCTravel` 每 300ms 全图 BFS | 实测 route BFS 788 节点 = **107.8 µs/call**；但仅在**旅行中的 bot**（少数）上跑，且 09-14 的 `MAX_HOPS 20→64` 对单次 BFS 成本无影响（仍 788 节点，只是深度上限不再截断） |
| `mapsWithinHopsByDepth(64)`（发现半径 20→64） | 实测 **232 µs/call**；仅在 **DECIDE 相位**（每 10–20 分钟一次/bot）调用。以 4824 训练 bot、每 15 分钟一次计 ≈ 4824/900 ≈ **5.4 次/s × 232µs = 0.13% core**，可忽略 |
| `BotWanderSystem`（400ms/漫游 bot） | **已有 LOD 门控**（`Crafted 6450209`：未观察直接 return），剔除 |

---

## 五、关键结论

### 5.1 直接回答"CPU 为何高了 3 倍"

**决定性证据（pickaxe 全量扫描）**：对"7% 基线"可能的时间点（`5df2f49` 09-09 / `c3cc97a` 09-11）到 HEAD 的全部提交做穷举（`-S scheduleAtFixedRate`、`-S 'newScheduledThreadPool'`、`-S 'newSingleThreadScheduledExecutor'`、`-S 'POOL.scheduleAtFixedRate'`），**唯一新增的全局固定频率调度任务就是 `BotPetFollower`（宠物跟随）**——只命中 `dfa1d56`（09-13 17:10，宠物系统引入）。没有第二个新定时器。

其余新代码要么挂在已有的、**已被 LOD 节流**的宏 tick / 移动 tick 上（坐骑 tick、零血采纳、收件箱 drain、门洞清理），要么只在**事件触发**时才跑（升级公告、聊天桥、坐骑装备）。**唯一一个「新增定时器 + 无观察豁免 + 每次调用走宿主最贵查询」的就是宠物跟随，它几乎单独解释了 7%→20%。**

1. **不是 bot 数量**。数量在 09-09（+2128 bot）与 09-11（城镇 -594）后已稳定；7% 基线就是"每频道全量"（`5df2f49`）之后测得的——`5df2f49` 提交信息明确记载"ch1 2423 全量、lobby 到 4551"时的观测，而 LOD/未观察节流/粗移动当时已齐备。
2. **是 09-13→09-14 的宠物跟随系统**（`dfa1d56` 起，经 `fce3449` 的"每 tick 同步坐标"改动把观察门控移除）：
   - **无观察门控**（`fce3449` 刻意改成每 tick 都算 follow，只门控发包）、**无 LOD 分档**、**200ms 全局硬频率**（`BotPetConfig.yaml: tick_ms: 200`）、**不受 tick wheel governor 拉伸**；
   - 每宠物每 tick **1–3 次宿主 `FootholdTree.findBelow`**（每次 1.5–21.6 µs，随地图陷洞数线性放大）；
   - 按实际承载率推算（等级 10–120 三角分布 + S/A/B/C/D=5/15/40/30/10%）：**约 10.9% 的 bot 带宠物、平均 1.39 只 → 5000 bot ≈ 757 只宠物 → 约 3 800–11 400 次宿主查询/秒**（视移动/静止比例）；
   - 单这一项在 330–1100 陷洞地图上稳态占 **单核 2%–24%**（见 §3.3 表），跨核摊到总 CPU 上与"7%→20%"的差距量级吻合。
3. 坐骑的 **profile 维度扩展**（骑/不骑两套导航图 + `-g:none` 代价）是次要因素：它带来额外的图缓存与偶发重烘焙，但受宏 tick 节流且非每帧路径，不是稳态主因。

### 5.2 最快验证手段（建议在真实服上做）

1. `!botpet disable`（运行时 kill switch，已实现）→ 观察 CPU 是否回落到 ~7–10%。
   - 若回落 → 确认 P1；若不完全回落 → 差额在 P2/P3。
2. `!env perf` 连读两次取 delta：`movement ticks/s`、`macro ticks/s`、`wheel dispatch/s` 与 `throttle x`。
3. 若条件允许，在宠物 tick 处加一个 `LongAdder`（每宠物查询计数与耗时），一小时内即可量化 P1 的确切份额。

### 5.3 修复方向（若要动，按性价比排序）

| 优先级 | 改动 | 说明 |
|:--:|---|---|
| ★★★ | **宠物 follow 计算加 LOD 门控**（未观察只同步坐标、不查树） | 每 tick 仍同步 `pet.setPos`（防闪现），但 `groundSnap`/`footholdId` 只在 `observed` 时算；或未观察降频到 1s/4s。**无观察时成本→~0**，是 7%→20% 的直接回退手段 |
| ★★★ | **把宠物的宿主 `findBelow` 换成 bot 侧 `pointBelowIndexed`** | 插件已有按列桶索引（24× 快），`BotNavGraphProvider` 里现成。**14–24× 常数收益**，还省下 LinkedList 分配与排序 |
| ★★☆ | **坐骑 profile 不进导航图 key**（或骑乘只改速度语义、不改图） | 避免骑/不骑两种 profile 各自烘焙一整套图；或骑乘时取"±5 桶内最近图"已有 `peekBestGraph` 兜底即够，不必新烘焙 |
| ★☆☆ | 宠物 tick 全局频率 200ms → 观察时 200ms / 未观察时 1–4s | 与 bot 移动层同款 LOD 节流 |
| ★☆☆ | `BotLevelUpNotice` 复用 `LodCounts`/缓存真玩家列表 | 若升级事件在高峰期变密再考虑；当前不构成热点 |

---

## 六、附：本报告的定量依据

### 6.1 微基准源码位置

- 宿主树查询成本：`/tmp/hostbench`（`GMS083` 的 `Foothold.java`/`FootholdTree.java` 逐字拷贝 + `Bench.java`）
- 复刻索引版对比：`/tmp/petbench/FootholdBench.java`
- 寻路 BFS：`/tmp/petbench/RouteBench.java`
- 运行：`export JAVA_HOME=/opt/graalvm-jdk-21.0.7+8.1; export PATH="$JAVA_HOME/bin:$PATH"; javac *.java && java -XX:+UseParallelGC Bench`

### 6.2 涉及的代码事实（verbatim 引用位置）

| 事实 | 位置 |
|---|---|
| 宠物 tick 200ms 全局调度 | `BotPetSystem/BotPetConfig.yaml:44`、`BotPetFollower.java:99-101` |
| 观察门控被移除（只留发包） | `BotPetFollower.java:171`（`observed` 变量）、`fce3449` |
| 每宠物 1–3 次宿主 `findBelow` | `BotPetFollower.java:232,290,296,326-330`、`BotPetController.java:190-205` |
| 宿主树 O(N)+排序+分配 | `GMS083/.../FootholdTree.java:105-190` |
| bot 侧索引版（未被宠物使用） | `BotPhysicsEngine.java:2650-2710` |
| 坐骑 profile 加成 | `BotMovementProfile.java:115-118`（`MOUNT_SPEED_BONUS=20`/`MOUNT_JUMP_BONUS=10`） |
| 导航图 key 含 profile 四维 | `BotNavigationGraphProvider.java:110-113`、`GRAPH_VERSION=62` |
| 骑/不骑状态切换 | `BotMount.java:164-181`（`tick`）、`cancelForAction:190-198` |
| 宏 tick 未观察 36-48s / 深磨 240-480s | `BotSM.java:611-614`、`TrainingBot.java:186-191` |
| 移动 tick 未观察 1s / 空闲 4s | `GCMovementDriver.java:37,43` |
| 漫游已有 LOD 门控（对照组） | `BotWanderSystem.java:206-209` |
| 迁移门开放（09-11）+ 3–8h 冷却 | `d15acf2`、`TrainingBot.java:522-575` |
| 发现半径 20→64（09-14） | `b414e0e`、`TrainingMapChooser.java:59` |
| 旅行路线上限 20→64（09-14） | `037ca87`、`GCTravel.java:52` |
| 唯一新增全局定时器 | `git log c3cc97a..HEAD -S scheduleAtFixedRate -- src/main/java` → 仅 `dfa1d56` |

### 6.3 与既有报告的关系

`docs/PERFORMANCE_HOTSPOT_ANALYSIS.md`（`b70b25e`，09-14 10:47）覆盖的是"单次调用写法"的常数级优化，**未包含宠物跟随系统**（宠物系统在其成稿前后仍在快速迭代，且该报告的基准点 `af5c8ef` 早于宠物引入）。本报告是其必要补充：**问题不在"某次调用贵了几十纳秒"，而在"某个新子系统根本不吃 LOD 门控、且每次调用都走宿主最贵的树查询"**。
