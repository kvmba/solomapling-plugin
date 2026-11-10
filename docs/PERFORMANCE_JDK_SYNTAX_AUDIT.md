# JDK API 语法级开销审计（复核版）

> 分支：`optimize/performance-v2` · 环境：GraalVM JDK 21.0.7，4 vCPU，`-XX:+UseParallelGC`
> 基准源码：`/tmp/perfjdk/Bench*.java`
> 方法论：JFR 采样真实生产 tick → 逐项隔离微基准 → **只改有实测收益的**

## 0. 本轮结论

全仓 889 个 Java 文件逐类扫过 `String.format` / `stream()` / 装箱 / `enum.values()` / `new Random()` / `Files.*` / 宿主锁访问器后，**真正值得改的只有 1 处**：

| 位置 | 问题 | 实测收益 |
|---|---|---|
| `BotMobHitboxProvider` | 每次 `getMobBounds()` 都调 `WZFiles.MOB.getFile()`（Spring `getBean` + `Path.of` + `Files.exists` stat 系统调用） | 单 bot **1.927 → 0.085 ms / 60s（省 95.6%）**；100 个同图 bot ≈ 省 **184 ms / 60s** |

---

## 1. 第一版结论的错误与更正

> 本节保留错误记录，避免后人重蹈。

### 错误 1：把"逐秒复查"当成必需（过度设计）

第一版方案给 `ensureCurrentMobRoot()` 加 `ROOT_RECHECK_MS = 1000` 的节流。

**错在哪**：前提是"WZ 根目录可能在运行时变化，需要定期探测"。经查证：

- WZ 文件在服务运行期间**不会增删**（用户明确指出）
- `WZFiles.getFile()` 依赖的 `ServiceProperty.language` 是 Spring `@ConfigurationProperties` 启动时绑定，**运行时无写入入口**（`setLanguage` 的调用点全是账号语言，无关）

既然根目录恒定，**每秒复查就是纯粹浪费**。正确做法是**解析一次、永久缓存**，代码也更短（少一个常量、少一个时间字段）。

### 错误 2：把"6 只候选怪 × 20Hz"当成真实频率（收益夸大 60 倍+）

第一版声称"235ms / 1000s"，隐含假设**每个 tick 都扫 6 只怪**。

**错在哪**：漏掉了 `tickMobDamage` 的两道门控：

```java
if (!GCMovement.isMapObserved(bot.getMapId())) return;   // 未观察地图直接跳过
...
if (entry.mobHitCooldownMs > 0) { ... return; }           // 受伤后 2.5s 无敌帧内跳过
```

且 `getMobBounds` 只在 `getMapObjectsInRect` 命中**敌对候选怪**后才逐个调用。按真实门控重测（`BenchFinal`）：

```
A (原版)   1.927 ms / 60s of one observed bot
B (修复后) 0.085 ms / 60s of one observed bot
节省       1.843 ms (95.6%)，折合 0.031 ms/s
```

**真实量级是 ms/分钟，不是 ms/秒。** 方向正确，但第一版的绝对值虚高了 1.5~2 个数量级。

### 错误 3：`getPosition()` 测量用单态调用点（方法论缺陷）

第一版测"`getPosition()` 防御性拷贝被 JIT 消除"时，用的是**单一实现类**（单态调用点），而宿主实际在 `MapleMap.getMapObjectsInRect` 里遍历 `List<MapObject>`，**8+ 种实现的多态调用点**。

重测（`BenchMega`）：

| 场景 | ns/op |
|---|---:|
| 单态 `getPosition().x` | 0.56 |
| **多态 `getPosition().x`** | **1.73 ~ 1.88** |
| 拷贝真正逃逸 | 9.4 ~ 10.6 |

即使多态也只慢到 ~1.8ns，**仍然没有分配**。结论（不该改）不变，但原始数据引错了。

---

## 2. 修复内容

`BotMobHitboxProvider`：

```java
private static volatile Path mobRoot = null;              // 原 cachedMobRoot
private static final Object MOB_ROOT_LOCK = new Object(); // 独立锁

private static Path mobRoot() {          // 原 ensureCurrentMobRoot()
    Path root = mobRoot;
    if (root != null) return root;        // 常态：一次 volatile 读
    synchronized (MOB_ROOT_LOCK) {
        if (mobRoot == null) mobRoot = WZFiles.MOB.getFile();
        return mobRoot;
    }
}
```

三处要点：

1. **`mobRoot()` 在 `computeIfAbsent` 之前调用**。映射函数 `loadMobBounds` 也读这个值；`ConcurrentHashMap` 明确禁止在映射函数中再进入它自己的更新路径，先解析可让两把锁完全不重叠。
2. **改用独立锁 `MOB_ROOT_LOCK`**，不再 `synchronized (boundsByMobId)`。实测当前 JDK 不会自死锁（bin 锁 vs 对象监视器），但那是依赖实现细节的脆弱写法。
3. **删除 `boundsByMobId.clear()`**。原逻辑靠它响应根目录变化；既然根目录恒定，该分支不可能触发，缓存也就不需要失效。

`resolveMobFile()` 里第二处 `WZFiles.MOB.getFile()` 同样改为 `mobRoot()`（该路径只在 cache miss 时执行，每怪种一次）。

---

## 3. 被排除的写法（实测无收益，故不改）

| 写法 | 实测 | 判定 |
|---|---|---|
| `getPosition()` 防御性拷贝 | 0.56（单态）/ 1.8（多态）ns；无分配 | JIT 逃逸分析处理，改无用 |
| `String.format` | 282 ns/op | 98 处全在命令/日志/烘焙路径，无 tick 循环 |
| `enum.values()` | 9.6 ns | 50 处全在冷却≥15s 或低频路径 |
| `new Random()` | 46 ns vs TLR 2.6 | 64 处多为 `static final`（构造一次）或建号路径 |
| `new GraphCacheKey(...)` + `CHM.get` | 3.80 vs 复用 3.79 ns | record 分配被标量替换 |
| `Set.copyOf.contains` | 2.35 ns（换 HashSet 反而 31.9） | 已是最优 |
| `PriorityQueue` + `comparingInt` | 16 节点快 3×、256 节点慢 1.16× | 噪声 > 信号 |
| `SearchState`（含 `Point` 的 A* key） | get 9.53 vs 5.92 ns | 相对 ms 级 A*，收益 <0.1% |
| `String.split(regex)` | 45.7 ns | 无 tick 循环内的调用 |

宿主锁访问器也逐点核对过：`getHp()` 17ns、`getBuffedValue()` 140ns，均已在早退判断前或按 `HOST_SYNC_INTERVAL_MS` / 20s profile 刷新节流，**改动收益小于风险**。

---

## 4. 验证

```
mvn compile  : 通过
mvn test     : 2140 run, 0 failures, 0 errors, 1 skipped   BUILD SUCCESS
```

> **测试命令注意**：不要用 `-Dtest='GCMoveSystem.*Test'` 这类通配过滤做整体验证。它会绕过 `pom.xml` 里的 surefire fork 隔离，把 `AllPqMapsAuditTest`（198 张真实 WZ 地图烘焙）与 `GroundSwayTest`（数百万次物理探测）塞进同一 JVM —— 单类就要 136s+，叠加 Mockito 深桩污染后可达数十分钟。这类过滤还会让 `StairFullLoopSwayTest` / `EosLadderTopClampTest` 因缺少前序测试初始化的 Spring 上下文而失败（**基线同样失败，与本改动无关**，已用干净 worktree 对拍确认）。整体验证请直接 `mvn test`。

worktree 需补 `wz -> /workspace/GMS083/gms-server/wz` 符号链接（该链接未被 git 跟踪）。

---

## 5. 文件变更

| 文件 | 变化 |
|---|---|
| `BotMobHitboxProvider.java` | +27 / −14 |

---

## 附录 · 基准命令

```bash
export JAVA_HOME=/opt/graalvm-jdk-21.0.7+8.1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp/perfjdk && javac -d . Bench*.java
java -XX:+UseParallelGC -cp . BenchFinal     # 最终 A/B
java -XX:+UseParallelGC -cp . BenchMega      # 单态 vs 多态 getPosition
```

**关键数字**：`getFile()` **≈1.9µs/次**、`Files.exists` **1.5–1.7µs**、`getPosition()` 拷贝 **0（已消除）**、`String.format` 282ns、`enum.values()` 9.6ns、`new Random()` 46ns、`Set.copyOf.contains` 2.35ns。
