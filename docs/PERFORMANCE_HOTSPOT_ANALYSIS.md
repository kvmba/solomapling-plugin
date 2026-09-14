# SoloMapling 插件 热点性能分析报告

> 分支：`optimize/performance` · 状态：**分析完成；低风险项已实施（见 §4 实施记录），高风险项已评估放弃（见 §3）**
> 基准环境：GraalVM JDK 21.0.7，4 vCPU，`-XX:+UseParallelGC`，每组取多次中位数；数值为量级参考，非绝对。
> 基准代码：`/tmp/perfbench/Bench*.java`（隔离复刻插件写法，不引用插件类）。
> 初版分析基于 HEAD `af5c8ef`；其后 `7479fdc`（定向聊天）与 `d129657`（升级公告）将文中"新改动"落库，分析结论不变。

本报告分两部分：
1. **热点测绘** —— 插件真正的热路径在哪里，每条路径的调用频率与既有优化。
2. **写法对比实测** —— 对若干可替换的写法做了 A/B 基准，给出“哪种更好”的结论与量化倍数。**重点覆盖本次分支上的新改动**。

---

## 0. 热路径总览（频率 × 单次成本）

| # | 热路径 | 触发频率 | 单次量级 | 评价 |
|---|---|---|---|---|
| H1 | `BotTickService.drive()` 扫描 `ENTRIES.values()` | 每 **100ms** 一次 | O(N) 遍历，N=在场 bot 数（1200+） | 已用“单驱动 + 虚拟线程派发”替代 per-bot 线程，设计良好 |
| H2 | `GCMovementDriver` 每-bot 移动 tick | 观察中 **20Hz/bot**；未观察 1Hz / 4Hz(空闲) | 每 tick 一个 `synchronized(entry)` | LOD 分级已把未观察 bot 降频 ~16×，显著 |
| H3 | `GrindTickRegistry.sweep()` 战斗扫描 | 每 **250ms** 一次 | 遍历 participants，逐个 `grindTick()` | 共享单 ticker，参与者自门控；已带 overrun 告警 |
| H4 | `BotSM` 宏 tick（`updateState`） | 每 bot 2–6s（观察）/ 36–48s（未观察） | 状态机一步 | 已用“tick wheel + waitFor 不 sleep”去掉阻塞 |
| H5 | LOD 观察者 `ObserverTracker.refresh()` | 每 **1s** | 全服真玩家扫描 + HALO | 见 §2.5，用 `isBot` 遍历 |
| H6 | **新** `DirectChatBridge.onEvent` 定向聊天 | 每次被 @ 的私聊/组队/公会/好友/联盟行 | ~50–90ns/行 | 新改动，见 §2.1，写得很轻 |
| H7 | 事件总线 `EventBus.publish` + `EventStore.add` | 每次聊天/进图事件 | RW 写锁 ~20ns | 见 §2.4 |
| H8 | `BotAttackDriver` 目标扫描（观察中战斗） | 4Hz/观察中 grinder | 见 §2.3 | 有一处可省一半 |
| H9 | 路径 A* `BotNavigationManager.runSearch` | 每次重规划（移动中偶发） | 每次 `new` 5 个容器 | 见 §2.6 |

**总体判断**：该插件早期已经做过一轮系统优化（代码中 “Fable Phase 0–5” 注释），核心热点（线程模型、LOD 分级、宏 tick wheel、战斗共享 ticker）都已处理得当，**没有明显灾难级热点**。剩下的问题是**若干“逐次调用”的写法在小对象分配、全服扫描、时间读取、重复计算上偏贵**，属于可观的常数级优化空间。

---

## 1. 写法 A/B 实测（测量方法说明）

| 记号 | 含义 |
|---|---|
| A / B | 两种写法，单位 ns/op（每次操作纳秒） |
| B/A | B 相对 A 的倍数；**<1 表示 B 更快**（首选 B） |
| 环境 | 4 vCPU，GraalVM 21，中位数，含预热 |

### 1.1 `System.currentTimeMillis()` 逐次读 vs 提升到循环外

| A：每次迭代读时钟 | B：循环外读一次 |
|---:|---:|
| **23.7 ns** | **0.02 ns** |

**结论**：单次 `currentTimeMillis` ≈ **24ns**，在百万级/秒的 tick 里若逐次读会成为可观开销。
插件里 `BotSM.isWaiting()`、`forced()`、`BotContactDamage`、`checkMainPlayersOnMap` 等**每次 tick 都会读 1–3 次**。
**建议**：在单次 tick 内把 `now` 读一次并向下传参（插件在多数热方法里已这么做，个别处如 `ObserverTracker.forced()` 可再收敛）。

### 1.2 计数器：`LongAdder` vs `AtomicLong`（4 线程竞争）

| A：`LongAdder.increment` | B：`AtomicLong.incrementAndGet` |
|---:|---:|
| **18.3 ns** | **82.5 ns** |

**结论**：`LongAdder` 在高竞争下比 `AtomicLong` 快 **4.5×**。
**插件现状**：`BotPerfStats.MACRO_TICKS/MOVEMENT_TICKS/...` 全部用 `LongAdder` —— **写法正确**，无需改。

### 1.3 收件箱排空形状（新改动 `drainDirectChat`）

| A：`!q.isEmpty()` 再 `poll`（插件现写法） | B：仅 `poll() != null` |
|---:|---:|
| 有 8 条：**160 ns** | 有 8 条：**202 ns** |
| 队列空（常态）：**2.95 ns** | 队列空：**2.62 ns** |

**结论**：**队列空是绝对常态**（绝大多数 bot 没有定向消息）。空队列下两种写法都 ~2.6–3ns，差异可忽略；有消息时插件现写法（`isEmpty()` 守卫 + `poll`）反而略快。
**判定**：`drainDirectChat` 的现写法（`!directInbox.isEmpty()` 守卫 + `poll` + `null` 兜底）是**合理且安全的**，无需改。每 tick 成本 ≈ **3ns**，可忽略。

> 注意：计划文档 §3.4 的示例代码是 `onDirectChat(directInbox.poll())`（无 null 检查）；实际实现已补上 `poll()` 的 null 兜底与上限 `MAX_DIRECT_CHAT_PER_TICK=8`，比文档写法更稳。

### 1.4 `Set.contains`：不可变集 vs `HashSet`

| 场景 | A | B | B/A |
|---|---:|---:|---:|
| 5 元素**命中为主**（`ObserverTracker.fullMaps`） | 不可变 `Set.copyOf`：**4.60 ns** | `HashSet`：**2.60 ns** | **0.56×** |

**结论**：在**热路径的 `contains` 查询**上，朴素 `HashSet` 比 `Set.copyOf` 的不可变实现快 **~1.8×**（不可变集多一层 `SharedSecrets`/边界探测）。
**插件现状冲突点**：
- `ObserverTracker.fullMaps`/`haloMaps` 用 `Set.copyOf(...)`，被 `isFull()`/`isHalo()` **在移动 tick 里逐次查询**（H2）。这里改用普通 `HashSet` 快照会更快。
- 但 `TierDwell.active` 也是 `Set.copyOf`，被 `isActive` 查询。
- **权衡**：不可变快照的价值是“安全发布 / 无锁读”。若追求极致可读性能，可改为“构建后 `Collections.unmodifiableSet(new HashSet<>(...))` 或直接可读快照 + 已发布语义”。差异 ~2ns/查询，若每 tick 每 bot 查一次、2000 bot × 4Hz ≈ 8000 次/秒 → 仅省 ~16µs/秒，**收益很小**。
**判定**：**不值得改**。保持现状。

### 1.5 每-bot 移动 tick 的 `synchronized(entry)`

| A：`synchronized(lock){work}` | B：无锁 |
|---:|---:|
| **21.2 ns** | **0.32 ns** |

**结论**：无竞争下 `synchronized` 进出 ≈ **21ns**（GCMovementDriver 每 bot 每 tick 一次）。
**插件现状**：`GCMovementDriver.runScheduledTick` 在 `synchronized(entry)` 内跑整段 tick。这是为保护 GreenCat 遗留的**非 volatile 物理字段**（注释明确说明），属于**必要的正确性代价**，不建议去掉。20Hz × 观察中 bot 数量（通常个位数到几十）→ 可忽略。

### 1.6 `isBot(int)`：`List.copyOf(classifiers()).isEmpty()` 逐次调用（**关键**）

这是本次要重点指出的**写法问题**。`BotHelpers.isBot`：

```java
if (!ArtificialCharacters.classifiers().isEmpty()) { ... }   // classifiers() = List.copyOf(COWList)
```

| 场景 | A：现写法（每次 `List.copyOf`） | B：缓存 flag | B/A |
|---|---:|---:|---:|
| 单次 `isBot` | **12.4–20.6 ns** | **1.6–2.2 ns** | **~0.12×（快 6–9×）** |
| **扫描 2500 个角色**调 `isBot` | **39.5–60.8 µs/次扫描** | **8.9–15.2 µs/次扫描** | **~0.23×（快 4×）** |

**结论**：`ArtificialCharacters.classifiers()` 每次返回 **`List.copyOf(COWList)`（每次分配一个新 List 只为判空）**，使 `isBot` 单次贵 ~8×。

**谁在放大它**：`isBot` 被 **56 处**调用，多处位于**全服/全图扫描的循环体内**：
- `ObserverTracker.refresh()`（**每秒**扫描全服所有角色，H5）
- `LodMetrics.load()`（诊断）
- `BotLevelUpNotice.announce()`（**本次新增**，每次静默升级扫描全服）
- `ConversationManager`、`BotChatter`、`BotMapEntryResponder`、`BotBuffRequestHandler`、`CompanionBot`、`TrainingBot`、`FollowerBot`、`DropGameSpectatorSystem`、`BlackjackDealerBot` 等（**按图扫描**，每 tick / 每次交互触发）

**量化影响**：以 2500 角色全服扫描为例，`ObserverTracker` **每秒**做一次 → 现写法约 **40–60µs/秒**，缓存 flag 后约 **9–15µs/秒**。单看不致命，但在**聊天/进图事件处理线程**上、以及新增的 `BotLevelUpNotice.announce`（升级瞬间遍历全服）里，同样的 4× 会被叠加放大。

**推荐写法（最优解，最小改动）**：在 `ArtificialCharacters` 里维护一个 `volatile boolean hasClassifiers`（`register/unregister/clear` 时更新），`isBot` 读该 flag 而非 `List.copyOf(...).isEmpty()`：

```java
// ArtificialCharacters
private static volatile boolean HAS = false;   // register/unregister/clear 同步更新
public static boolean hasClassifiers() { return HAS; }

// BotHelpers.isBot
if (ArtificialCharacters.hasClassifiers()) { return ArtificialCharacters.isArtificial(id); }
```

**收益**：`isBot` 快 6–9×；每次全服扫描省 ~25–45µs；对每秒/每事件扫描的路径是纯赚。**风险极低**（只是把“每次判空”换成“变更时维护的 flag”）。

### 1.7 字符串：`String.format` vs 拼接（日志/告警）

| A：`String.format` | B：`+` 拼接 |
|---:|---:|
| **698 ns** | **85 ns** |

**结论**：`String.format` 比拼接慢 **~8×**（每次 ~700ns + 一次 varargs 数组 + Formatter）。
**插件现状**：热路径**正确**地避开了 `String.format`，仅用于**低频处**（`BotPerfStats.report()`、`BotTickService` 的 governor 告警——每 5s 才触发一次、`LodMetrics` 诊断）。
**判定**：**无需改**（这些位置频率极低，用 `String.format` 换可读性是划算的）。仅提醒：**不要**把 `String.format` 引入任何按 tick / 每帧路径。

### 1.8 战斗选目标：全排序 vs 只取最近（**可省一半**）

`BotAttackDriver.mobsInReach` 把盒内所有怪 `sort` 一遍；而 `nearestMob` 用“min 追踪”不排序。

| 场景 | A：盒内全排序 | B：min 追踪（只要最近） | B/A |
|---|---:|---:|---:|
| 50 怪 | **147.6 ns** | **85.4 ns** | **0.58×** |
| 200 怪 | **552.3 ns** | **365.3 ns** | **0.66×** |

**结论**：只需要最近目标时，**min 追踪比全排序快 ~1.5–1.7×**。
**插件放大点**：`BotAttackDriver` 在一次攻击决策里**调用 `mobsInReach` 两次**（先判 `size() >= 2` 决定是否 AoE，再取实际目标列表），即**扫描+排序跑了两遍**。
**推荐**：一次算出盒内列表，复用于“个数判断 + 取前 N”，可**直接砍掉一次扫描+排序**（等价把 §1.8 的 2× 省回，叠加 §1.8 自身的 1.5×）。属于小但稳妥的热路径优化。

### 1.9 RNG：`new Random()` vs 共享 vs `ThreadLocalRandom`（**注意新改动**）

| A：`new Random()` | B：共享 `Random` | C：`ThreadLocalRandom` |
|---:|---:|---:|
| **34.8 ns** | **11.3 ns** | **3.33 ns** |

**结论**：`new Random()` 逐次构造最差（34.8ns）；共享 `Random` 次之；`ThreadLocalRandom.current()` 最快（3.3ns，且无竞争）。
**插件现状**：多数热路径已用 `ThreadLocalRandom`（`BotPotionSim`、`BotDeath`、`BotMedal`、`BotChatter`、`BotBuffRequestHandler`、`BotContactDamage`）。但仍存在**逐次 `new Random()`**：
- `BotCustomization.java:129`、`BotHelpers.java:319` —— 逐次 `new Random()`（低/中频，建议改 `ThreadLocalRandom`）。
- `TownPresenceSampler`/`TownChatterLines` 用静态共享 `Random` —— 可，但多线程共享 `Random` 有内部 CAS 竞争；若并发频繁建议 `ThreadLocalRandom`。
**判定**：**低风险改进**——把热路径上的 `new Random()` 统一为 `ThreadLocalRandom.current()`。

### 1.10 `List.copyOf` vs `HashSet` 重建快照（LOD 每秒）

| A：`Set.copyOf(src)` | B：`new HashSet<>(src)` |
|---:|---:|
| **151 ns** | **78 ns** |

**结论**：重建小快照时 `new HashSet` 比 `Set.copyOf` 快 ~2×。`ObserverTracker.refresh()` **每秒**为 full/halo 重建快照，但都是小集合（几十个 mapId），差异 ~百 ns/秒，**可忽略**。

### 1.11 A* 前沿：`PriorityQueue` vs `sort`

| A：`PriorityQueue` push+poll 512 | B：`clone`+`Arrays.sort` 512 |
|---:|---:|
| **28075 ns** | **9295 ns** |

**结论**：一次性排序 512 个元素比“逐个入堆再逐个出堆”快 ~3×。但 A* 前沿是**动态**的（边扩展边入队），`PriorityQueue` 是对的数据结构，**不应替换**。此处仅供参照：**不要**把 A* 换成“批量排序”。
**插件现状**：`BotNavigationManager.runSearch` 用 `PriorityQueue` + 每调用 `new` 5 个容器（`PriorityQueue`、3 个 `HashMap`、1 个 `ArrayList`）。路径重规划非每-tick（移动中偶发），可接受；若想再压，可**复用线程本地容器**，收益有限。

### 1.12 新定向聊天路径的单次成本

| 场景 | 成本 |
|---|---:|
| 完整新路径（`GameEvent` 分配 + `isBot` + `getBotById` + `ChatMessage` 分配 + `CLQ.add`） | **90.9 ns/行** |
| 仅入队（隔离桥/事件开销） | **16.2 ns/行** |

**结论**：本次新增的 `DirectChatBridge` 每条定向消息 **~90ns**，且跑在**发消息玩家的包线程**上，只做“一次查表 + 一次入队”，**符合计划 §6.7 的“必须极轻”要求** —— **写得好**。
其中固定的 `isBot(sender)`（~20ns）与 `GameEvent`/`ChatMessage` 两次对象分配占了主要部分。若统一了 `isBot`（§1.6），单条可再省 ~15–18ns。

### 1.13 时间轮派发：虚拟线程 vs 调度池

| A：`virtualThread.submit` | B：`scheduledPool.execute` |
|---:|---:|
| **481 ns** | **649 ns** |

**结论**：派发任务时虚拟线程提交略快于调度池。`BotTickService` 每 100ms 把到期 tick 派发到 `ExecutorServiceManager.runAsync`（虚拟线程执行器）—— **写法正确**（也让含 `Thread.sleep` 的 bot 类型不互相阻塞）。

---

## 2. 结合“本次新改动”的重点结论

本次分支未提交改动 = **定向聊天频道**（私聊/组队/公会/好友/联盟收+回）+ **静默升级公告**。逐项评述：

### 2.1 `DirectChatBridge` / 每-bot `directInbox`（H6）— **性能达标 ✅**
- 桥在包线程只做 `isBot(sender)` 过滤 + `getBotById`（CHM O(1)）+ `new ChatMessage` + `CLQ.add`，**~90ns/行**，无发包、无遍历（§1.12）。
- 收件箱用 `ConcurrentLinkedQueue` + tick 内**限流 8 条**排空，空队列守卫 **~3ns/tick**（§1.3）。
- **唯一可优化点**：`isBot(sender)` 走 §1.6 的贵写法。因为 `DirectChatBridge` 与 `PlayerChatBridge` 都在包线程调用 `isBot`，统一 flag 化后单条定向消息可再省 ~15–18ns。**优先级低**（聊天行不密集），但属“顺手可做”。

### 2.2 `BotLevelUpNotice.announce`（新增，未跟踪文件）— **注意全服扫描 ⚠️**
```java
for (Character player : bot.getWorldServer().getPlayerStorage().getAllCharacters()) {
    if (BotHelpers.isBot(player)) continue;   // ← 每个角色一次贵 isBot
    ...
}
```
- 每次静默升级**遍历全服所有角色**（可达数千），**逐角色**调 `isBot`。按 §1.6，2500 角色的单次扫描约 **40–60µs**，flag 化后 **9–15µs**。
- 触发频率：升级事件（不算密集），且**计划文档明确“暂不做频率节流”**。风险可控，但这是**最该受益于 §1.6 优化**的新增点。
- **另注意**：本方法在**升级瞬间同步执行**（若由宏 tick / 战斗 tick 触发，则在该线程内完成全服扫描 + N 次 `dropMessage`）。建议：如未来升级事件变密，考虑移出 tick 线程或按地图去重。

### 2.3 `HostGameplayEventBridge` / `GameEvent` 扩展 — **中性**
- 新增 5 个 `EventType`、`GameEvent` 加了 2 个可空字段与构造重载。`GameEvent` 构造里 `playerName/map/world/channel` 每次仍会 `getName()/getMap()` 读取，但这是**原有行为**，字段+2 不改变成本。
- `EventBus.publish` 先 `eventStore.add`（RW 写锁 ~20ns，§1.11/§2.4）再遍历订阅者（COW 列表，5 个子者 ~5ns）。
- **潜在**：`MultiChatHandler` 对**每个 bot 收件人逐条发布** → 一次公会广播可能产生很多事件，每条都要过 `EventStore.add` 的**排他写锁**（多个玩家包线程会串行化）。单条仅 ~20ns，一般无碍；若公会/联盟里 bot 极多且聊天频繁，才需关注。**当前不构成热点**。

### 2.4 `EventStore` 写锁（H7）
- `add`：`ReentrantReadWriteLock` 写锁 + 环形写入 ≈ **19.5ns**（无竞争）；纯环形写 ≈ 12.6ns。**可接受**，不建议为 7ns 去改并发结构（正确性 > 微优化）。

### 2.5 `ObserverTracker.refresh`（H5）— **每秒一次，受益于 §1.6**
- 每秒扫描全服真玩家，内部逐角色 `BotHelpers.isBot(chr)`。**这是 §1.6 优化最大的固定受益者**（每秒节省 ~25–45µs，虽然本身也不大）。
- HALO 段用 `GCWorldGraph.portalNeighbors` + 距离平方门控，避免全图扇出 —— **设计良好**。

### 2.6 战斗扫描（H3/H8）
- `GrindTickRegistry.sweep` 每 250ms 遍历 participants；每个 `grindTick` 有**早退门控**（未观察/未在 GRIND 直接 return）。注释里自认“**UNTESTED：请用 `!env perf` 验证 250ms 扫描成本，若上升回退 500**”—— 建议**实测**（本环境无法起服，见 §5）。
- `BotAttackDriver.mobsInReach` **一次决策调用两次**（§1.8）—— 已修复（见 §4）。

---

## 3. 优化建议清单（按性价比排序，含最终裁决）

| 优先级 | 位置 | 改法 | 预期收益 | 风险 | **裁决** |
|:--:|---|---|---|---|---|
| ★★★ | `BotHelpers.isBot` + `ArtificialCharacters.classifiers()` | 缓存 `hasClassifiers` flag | `isBot` 快 **6–9×**；全服扫描快 **4×** | ~~极低~~ → **中**（见下） | **放弃**（风险重估：干净修法在宿主仓、跨仓；插件侧缓存在卸载/重载后存在时序风险；收益仅 µs 级） |
| ★★☆ | `BotAttackDriver` | 一次算出盒内目标列表复用（避免两次 `mobsInReach`） | 每次攻击省一次扫描+排序（**~2×**） | 低 | **已实施** ✅ |
| ★★☆ | `BotAttackDriver.mobsInReach` | 只需前 N 时用**部分选择**代替全排序 | 选目标快 **~1.5×**（§1.8） | 低 | 未实施（收益随 mob 数，风险虽低但改动面大，暂搁） |
| ★☆☆ | 热路径上的 `new Random()`（`SoloMaplingUtilities`×2、`BotCustomization`、`BotHelpers`、`FMBot`） | 改 `ThreadLocalRandom.current()` | 单次 **~10×**（34.8→3.3ns），与既有 `1a13233` 同模式 | 极低 | **已实施** ✅ |
| ★☆☆ | `ObserverTracker.forced()` / `BotSM.isWaiting()` 等 | tick 内只读一次 `now` 并传参 | 每 tick 省 ~24ns × 次数 | 极低 | 未实施（收益微小、触碰面广） |
| ☆☆☆ | `ObserverTracker.fullMaps` 快照 | `Set.copyOf` → 可读 `HashSet` | 每次查询 ~2ns | 微 | 不做 |
| — | `String.format` / `LongAdder` / 虚拟线程派发 / LOD 分级 | **保持现状** | 写法已最优 | — | 保持 |

**`isBot` 缓存为何最终放弃（风险重估）**：
1. 干净修法在**宿主仓** `extension-api/ArtificialCharacters`（维护 `volatile hasClassifiers`）——属跨仓协调改动；仅改插件侧会在宿主 `clear()`/热重载后**缓存过期**：插件仍按 flag 走 `isArtificial`，而宿主由 `clear()` 清掉分类器后必然全判 `false`——语义漂移出现在**升级/卸载时序**上。
2. 插件侧无条件直接走 `ArtificialCharacters.isArtificial(id)`（去掉 `isEmpty()` 判空）则当分类器未注册时错误回落到 `id > 20000`——实际**等价于现状**，但把"宿主未注册"从旧路径变成走 COW 空列表遍历，仅省下一次 `List.copyOf`，收益 ~10ns 却加重对宿主注册状态的隐式依赖。
3. 收益本身只是 µs 级/秒（§1.6），与上述时序风险不成比例。**结论：不做，等宿主后续提供 `hasClassifiers()` API 再说。**

**明确不建议改动**（避免过度优化）：`GCMovementDriver` 的 `synchronized(entry)`（物理字段安全所需）、A* 的 `PriorityQueue`、`EventStore` 的写锁、低频日志里的 `String.format`。

---

## 4. 实施记录（本轮落地）

| 文件 | 改动 | 等价性论证 |
|---|---|---|
| `BotAttackDriver.java` | AUTO 分支把 `mobsInReach(packAttack)` 的扫描结果在`≥2`成 AoE 时缓存，复用为最终目标列表 | 同一 tick、同一线程、同 `(bot, profile, weapon, facingLeft)` 的只读扫描 → 结果必然一致；原来两次调用本来就返回同样的列表 |
| `SoloMaplingUtilities.java` | `generateRandomNumber(int,int)`、`getRandomNumber(List)` 改 `ThreadLocalRandom` | 同分布（均匀 `[x,y]` / `[0,size)`），API 签名不变；与同文件既有 `rollChanceInverse` 写法一致 |
| `BotCustomization.java` | `pickWeighted` 改 `ThreadLocalRandom` | 同分布（`[0,total)`） |
| `BotHelpers.java` | `getRandomizedPointXAxis` 改 `ThreadLocalRandom` | 同分布（`[minX,maxX]`） |
| `FMBot.java` | `shouldPurchaseItem` 改 `ThreadLocalRandom` | 同分布（`[0,1)`）；`deckCut` 的 `Random` 参数保留（需要可复现的 shuffle） |

验证：`mvn compile` 通过；全量测试 **848 run, 0 failures, 0 errors, 1 skipped**（改动前后一致）。

---

## 5. 未能完成的实测（需运行中的服务器）

1. **`!env perf` 的真实读数**：`GrindTickRegistry.sweep` 的 last/max ms、tick wheel 的 dispatch/s 与 lag、macro/movement ticks 速率。这是验证 H1/H2/H3 是否真的成为瓶颈的**唯一权威手段**。
2. **D 组 250ms vs 500ms 的战斗扫描成本对比**（代码注释里的 UNTESTED 项）。
3. **`isBot` 优化前后**在 `ObserverTracker`（每秒）与 `BotLevelUpNotice`（升级时）上的**实测 p99**。
4. **多玩家并发聊天**下 `EventStore.add` 写锁的实际竞争情况（本条仅推测无热点）。

> 建议在真实服上开 `!env perf` 连读两次（间隔数秒）取速率，再对照本报告 §0/§1 判断优先级。

---

## 附录 · 基准源码与命令

- 基准文件：`/tmp/perfbench/Bench.java`~`Bench5.java`、`Pq.java`（Harness 用 `volatile SINK` 防死代码消除，取中位数）。
- 运行：`export JAVA_HOME=/opt/graalvm-jdk-21.0.7+8.1; export PATH="$JAVA_HOME/bin:$PATH"; javac BenchN.java && java -XX:+UseParallelGC BenchN`
- 关键结论速记：`currentTimeMillis≈24ns`、`LongAdder 4.5×>AtomicLong`、`String.format 8×>concat`、`new Random 10×>TLR`、`isBot(List.copyOf) ~8×>flag`、`全排序 1.5–1.7×>min追踪`、`synchronized≈21ns`。
