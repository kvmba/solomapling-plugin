# Bot 升级公告方案（参考宿主机机制）

> 状态：**已实现**。目标：让 bot 升级也发出和真玩家一致的系统公告。
> 判定与文案完全复用宿主既有的一套（配置开关 + 同一 I18n 模板 + 同一提示类型），
> 按用户要求：**升级后即发，暂不做频率节流**。
>
> 实现文件：`soloMapling/ArtificialPlayer/BotFlavorSystem/BotLevelUpNotice.java`（新增），
> 调用点 `TrainingBot.java`（`accrueAbstractExp`）与 `SoloGrindController.java`（`accrueSimulatedExperience`）各 1 行。
> **不动宿主 GMS083**；投递端只发真玩家。

---

## 0. 结论先行

1. **宿主贴给真玩家的公告逻辑在 `Character.gainExpInternal`**，条件与文案是：
   - 开关：`GameConfig.getServerBoolean("use_announce_global_level_up")`
   - 例外：`!isGM()`
   - 文案：`I18nUtil.getMessage("Character.levelUp.globalNotice", 名字, 地图名, 等级)`
   - 投递：遍历 `getWorldServer().getPlayerStorage().getAllCharacters()`，对每个 `player.dropMessage(6, msg)`（跳过正在商城开着的玩家），并 `log.info(msg)`。

2. **bot 升级分两条性质完全不同的路径**：
   - **真实路径**（观察中的实打实击杀、伴侣离线结算）：走宿主的 `Character.gainExp()` → `gainExpInternal`，**因此只要开关打开，这些 bot 升级本来就已经被宿主公告了**（宿主那段循环没有 `isArtificial` 过滤，bot 也收得到、也会被写日志）。
   - **静默路径**（未观察时的抽象刷怪/伴侣独自刷怪）：插件自己 `setLevel/setExp` 直接赋值，**完全绕过 `gainExp`**，所以宿主永远公告不到 —— 这是本次真正要补的缺口。

3. **因此改动落在插件侧、只补静默路径**：在 `TrainingBot.accrueAbstractExp` 与 `SoloGrindController.accrueSimulatedExperience` 的“升级了”分支后，按宿主的同款判定+文案发一条世界公告。**不动宿主 GMS083，不新增依赖，两处调用 + 一个 helper。**

---

## 1. 宿主现状（真玩家怎么发）

`gms-server/src/main/java/org/gms/client/Character.java` — `gainExpInternal`（每个升级 tick）：

```java
while (exp.get() >= ExpTable.getExpNeededForLevel(level)) {
    levelUp(true);

    String msg = I18nUtil.getMessage("Character.levelUp.globalNotice", getName(), getMap().getMapName(), getLevel());
    if (GameConfig.getServerBoolean("use_announce_global_level_up") && !isGM()) {
        for (Character player : getWorldServer().getPlayerStorage().getAllCharacters()) {
            // 如果玩家在商城，将会以弹窗的形式发送，一堆弹窗会把玩家逼疯！
            if (player.getCashShop().isOpened()) {
                continue;
            }
            player.dropMessage(6, msg);
        }
        log.info(msg);
    }
    ...
}
```

| 事实 | 证据（宿主） |
|---|---|
| 提示类型 `6` = 聊天窗黄/浅蓝字（非弹窗） | `PacketCreator.serverNotice` 注释；`dropMessage(6,msg)` → `serverNotice(6,msg)` |
| 配置项 `use_announce_global_level_up`，默认 **false** | `db/migration/V1.7.0__create_game_config.sql:98` |
| 文案模板 | `i18n/message_zh_CN.properties:4` → `[升级信息] 玩家 {0} 在地图 <{1}>  升到了 {2} 级` |
| 世界级广播（满级彩蛋另走一条） | `Character.java:6147` `getWorldServer().broadcastPacket(PacketCreator.serverNotice(6, LEVEL_200))` |

---

## 2. bot 升级路径盘点（谁已经发了 / 谁没发）

bot 是注册进宿主 `PlayerStorage` 与频道存储的 `Character`（`BotGeneration.addBotToServer`，`gmLevel=0`）。

| # | 路径 | 代码位置 | 是否走宿主 `gainExp` | 开关开时是否已被宿主公告 |
|:--:|---|---|:--:|:--:|
| 1 | 观察中真实击杀（TrainingBot/Companion 的实打实战斗） | `BotAttackEffects.applyDamageAndLoot` → `map.killMonster` → `Monster.distributeExperience` → `Character.gainExp` | 是 | **已公告**（宿主的循环未过滤 bot） |
| 2 | 伴侣离线结算升级 | `HostCompanionRuntimeAdapter.grantOfflineExperience` → `character.gainExp(grant,false,false)` | 是 | **已公告** |
| 3 | **未观察的抽象刷怪升级** | `TrainingBot.accrueAbstractExp`（`setLevel/setExp` 直赋） | **否** | **未公告** ← 缺口 |
| 4 | **伴侣独自刷怪（无人观察）升级** | `SoloGrindController.accrueSimulatedExperience`（`setLevel/setExp` 直赋） | **否** | **未公告** ← 缺口 |
| 5 | 出生/测试期直接 setLevel（装饰、环境、教程、GM 命令） | `BotDecorate`、`EnvironmentManager`、`TutorialBot`、`ArtificialPlayerCommand` | 否 | 非“升级事件”，**不发**（正确） |

关键点：路径 3、4 之所以静默，是插件**刻意**为省 LOD 成本而用算术直赋等级（见 `TrainingBot` 类注释“applied silently (no packets)”）。它们不产生任何发包，因此宿主完全无感。要在“升级后即发公告”，只需在这两处补发。

---

## 3. 方案

### 3.1 复用宿主的判定与文案（不另造一套）

新增一个薄 helper（实际实现）：`soloMapling/ArtificialPlayer/BotFlavorSystem/BotLevelUpNotice.java`

```java
public final class BotLevelUpNotice {
    private BotLevelUpNotice() {}

    /** Announce bot's current level to every real player, if the host switch allows it. */
    public static void announce(Character bot) {
        if (bot == null || bot.getMap() == null || bot.isGM()) {
            return; // matches the host: GM level-ups are not announced
        }
        if (!GameConfig.getServerBoolean("use_announce_global_level_up")) {
            return; // same switch the host uses for real players
        }
        String msg = I18nUtil.getMessage("Character.levelUp.globalNotice",
                bot.getName(), bot.getMap().getMapName(), bot.getLevel());
        for (Character player : bot.getWorldServer().getPlayerStorage().getAllCharacters()) {
            if (BotHelpers.isBot(player)) {
                continue; // real players only; a bot would just drop the packet
            }
            if (player.getCashShop().isOpened()) {
                continue; // matches the host: a popup in the cash shop would spam
            }
            player.dropMessage(6, msg);
        }
        log.info(msg);
    }
}
```

设计取舍（对齐 AGENTS.md 的“最小改动 / 复用既有抽象”）：

- **复用宿主**：`GameConfig`（开关）、`I18nUtil` + `Character.levelUp.globalNotice`（文案）、`dropMessage(6)`（类型）、世界存储遍历（投递）——全部是宿主现成的，插件以 `provided` 依赖直接调用（插件已在用 `Server` / `PacketCreator`）。
- **不新增配置项、不改宿主**：判定“是否公告”沿用宿主同一个 `use_announce_global_level_up`，语义一致（开关一开，真玩家和 bot 一起公告；一关，一起静默）。若运营想让二者独立，再单开一个插件 flag（YAGNI，暂不做）。
- **不重写文案**：`{0}=名字 {1}=地图 {2}=等级`，bot 的名字是随机 IGN、地图取 `getMap().getMapName()`，呈现与真玩家完全同款。

### 3.2 调用点（只在两处静默路径补一行）

**A. `TrainingBot.accrueAbstractExp`**（约 1333 行 `if (level > startLevel)` 分支内）：

```java
chr.setLevel(level);
chr.setExp((int) Math.min(exp, Integer.MAX_VALUE));
if (level > startLevel) {
    // 保留现有：内部事件（供附近 bot 道贺）
    EventBus.getInstance().publish(EventFactory.createLevelUpEvent(chr));
    // 新增：与真玩家同款的世界公告
    BotLevelUpNotice.announce(chr);
}
```

**B. `SoloGrindController.accrueSimulatedExperience`**（约 399 行 `if (level > startLevel)` 分支内）：

```java
companion.setLevel(level);
companion.setExp((int) Math.min(exp, Integer.MAX_VALUE));
if (level > startLevel) {
    log.info("Companion solo grind level up cid={} from={} to={} map={}",
            companion.getId(), startLevel, level, companion.getMapId());
    // 新增：同款世界公告
    BotLevelUpNotice.announce(companion);
}
```

两处都是“已经判定升级了，紧跟一句公告”，不改变任何数值/发包语义。

### 3.3 观察路径无需改动

- 路径 1（观察中击杀）、路径 2（伴侣离线结算）走宿主 `gainExpInternal`：开关打开时**已经公告**，插件不再重复发 → 天然无重复。
- 与 `sayContext("LevelUp")`（图内“ding!”聊天气泡）互不冲突：一个是图内气泡，一个是世界频道提示，真玩家也是两者都有。

### 3.4 投递对象：**只发真玩家**

宿主原循环遍历的是 `getWorldServer().getPlayerStorage().getAllCharacters()` —— 这是**全服所有角色，含 bot**（bot 由 `BotGeneration.addBotToServer` 注册进同一份 `PlayerStorage`）。所以：

- **宿主原行为**：公告会投递给 bot。但 **bot 的 `BotClient.sendPacket` 是空实现**（`BotClient.java:39`），`dropMessage → sendPacket` 到 bot 即丢弃，对真玩家**零观感影响**，也不会 NPE（bot 同样 `setCashShop`）。
- **代价**：世界存储 ≈2559 个角色（~1253 是环境 bot、伴侣另计），每次公告要遍历全量。若 bot 升级频繁，这是 N 倍无谓开销 —— 这正是「只发真玩家」的性能动机。

因此本次新增的 helper **在投递端过滤掉 bot**，只对真玩家 `dropMessage`：

```java
for (Character player : bot.getWorldServer().getPlayerStorage().getAllCharacters()) {
    if (BotHelpers.isBot(player)) {
        continue; // 只发真玩家：bot 收到也是 no-op，跳过省掉遍历/发包
    }
    if (player.getCashShop().isOpened()) {
        continue;
    }
    player.dropMessage(6, msg);
}
```

**但要注意覆盖范围**：这种「只发真玩家」的过滤只作用于**插件自己新增的静默路径公告**。宿主那条路径（观察中击杀、离线结算）的公告由 **GMS083 的 `Character.gainExpInternal` 发出**，插件无法过滤，仍会投递给 bot（效果上仍是 no-op）。

**最终决定（本次实现）**：**不动宿主**。宿主路径送出的 bot 接收者是零观感、零副作用的空 `sendPacket`，为它改一行宿主不划算；插件新增的 helper 则一律只发真玩家。若日后确有必要全路径严格只发真玩家，再评估在 `Character.java:3096` 循环内加 `if (HostHooks.isArtificial(player)) continue;`（1 行、最低风险）。

### 3.5 持久化 bot「上线瞬间」会不会发公告？（离线结算路径）

**会，但只在边界情况下发生。** 完整链路已核实：

```
上线瞬间 CompanionLifecycleCoordinator.spawnInternal(...)   // 每 60s reconcile 一次，到点上线
  → settle(profile, persistedLevel, now)                     // 算离线时长 → exp/meso
  → runtime.load(profile)                                    // ← bot 此刻已 addBotToServer + addPlayer 进地图
  → runtime.applyProgression(loaded, settlement)             // HostCompanionRuntimeAdapter:205
      → grantOfflineExperience(...)
          → character.gainExp(grant, false, false)           // ← 走宿主 gainExp！
          → gainExpInternal → while(exp>=need){ levelUp; 世界公告 }
```

要点：

| # | 事实 | 证据 |
|:--:|---|---|
| 1 | 离线结算**走宿主 `gainExp`**，不是直赋等级 | `HostCompanionRuntimeAdapter.java:255` `character.gainExp(grant,false,false)` |
| 2 | `show=false` **只抑制经验飘字**，**不抑制** while 里的世界公告 | 宿主 `gainExpInternal`：`announceExpGain` 在 `if(show)` 内；世界公告在 while 内、独立于 `show` |
| 3 | 结算发生在 `load()` **之后**，故公告发出时 bot 已在地图+世界存储，真玩家能正常收到 | `CompanionLifecycleCoordinator.java:299-302` 顺序 |
| 4 | 触发条件是**跨过等级线**，而结算单次上限 = 该级所需 **20%**（且 24h、硬顶 25000） | `SETTLEMENT_LEVEL_FRACTION=0.2`、`OfflineProgressionPolicy.conservativeDefaults()` |
| 5 | 所以**很难**上线即公告：只有结算前 exp 已 ≥80% 满格时才可能跨 1 级 | 由 4 推出 |

**结论**：持久化 bot 上线瞬间**一般情况下不发公告**（20% 上限吃不掉一整级）；**只有当它离线前 exp 已接近满格时才会发一条**。而这条**本来就由宿主发**，与本次插件改动无关 —— 也就是说：

- 若你**不改**插件静默路径 → 这条路径的公告仍会随宿主开关出现（边界情况）。
- 若你**要禁止**“上线瞬间公告” → 需要在 `grantOfflineExperience` 里让结算**不触发公告**，有两种最小做法：
  - (a) 结算期间临时 `allowExpGain` 类旁路 —— 不存在现成开关，需自己包一层；
  - (b) 更干净：给 `grantOfflineExperience` 一个"静默结算"通道（直接算好最终 level/exp 再落库，绕过 `gainExp` 的世界公告副作用）。但这会顺带绕过 `levelUp` 的其他副作用（SP/HP-MP/满级彩蛋），**需要评估**，不是 1 行。
- 若你**接受**它发（本就是真玩家的同款行为）→ 什么都不用做。

**最终决定（本次实现）**：**接受它发，不拦**。理由：(1) 这条链路走宿主 `gainExp`，本是「真玩家同款行为」，语义上无可厚非；(2) 20% 上限使其极少触发（仅离线前 ≥80% 满格时跨 1 级）；(3) 拦住它需要改宿主结算路径或绕过 `gainExp`，会顺带绕过 `levelUp` 的 SP/HP-MP/满级彩蛋等副作用，风险与收益不成比例；(4) 用户要求尽量不动宿主。故持久化 bot「上线瞬间公告」维持宿主原样（边界情况可能出现，属预期）。

---

## 4. 风险与注意（实现后复核）

| 项 | 说明 | 处理 |
|---|---|---|
| **重复公告** | 唯一风险是“静默路径 + 观察路径”同时命中的 bot。已核实：`doGrind` 里抽象累积有 `!isMapObserved` 门控，观察时走真实击杀、不累积 → 互斥，不重复。 | 无需处理，测试确认 |
| **GM 例外** | 宿主 `!isGM()`。bot `gmLevel=0`；`TutorialBot` 临时设过 6。 | helper 内 `bot.isGM()` 直接 return，与宿主一致 |
| **商城弹窗** | 宿主跳过 `getCashShop().isOpened()`。 | helper 复刻同一判断 |
| **多级跳** | 慢 tick 下一次累积可能跨多级（抽象循环 + 离线结算都可能跨级）；宿主是逐级发多遍，本实现是“最终级发一次”。 | 已知差异；跨级在同一 tick 内对真玩家呈现为“只报最终等级”，可接受；如需逐级可后续细化 |
| **文案本地化** | `I18nUtil.getMessage` 在非客户端线程（bot tick 无 ThreadLocal client）下回退到服务端语言，`Language.fromLang(0)` → 服务端语言，安全。 | 复用宿主 key，中文/英文随宿主的 `message_zh_CN/en_US.properties` 一起走 |
| **异常安全** | 调用点在 bot 的 tick 内，`BotSM.tickRunnable` 外层 `catch (Exception)` 兜底，异常不会打死 bot 或停掉调度。 | 无需额外 try/catch |
| **线程安全** | bot tick 线程通过 `Character.dropMessage → Client.sendPacket`，其 `announcerLock` 保证写 Netty channel 安全；与宿主在 `gainExpInternal` 里广播同一模式。 | 无需处理 |
| **频率/刷屏** | 未观察训练 bot ≈997 只，早期低等级每 1–5 分钟升一级，开关一开可能每分钟数百条世界公告。 | 用户已明确**本期不考虑频率**；如需节流，后续在 helper 里加一个全局令牌桶/最小间隔即可（不扩散到调用点） |
| **边界（HOST_BOUNDARY）** | 规则是“宿主不得 import 插件包”；反向（插件 import `org.gms.*`）本就允许且在用。 | 本实现纯插件侧，符合边界 |

---

## 5. 验证

1. 编译：`mvn -pl solomapling-plugin -am -q compile` → **BUILD SUCCESS**（JDK 21 / Maven 按 AGENTS.md 前置）。
2. 测试：`mvn -q test -Dtest='TrainingBot*,SoloGrind*,Companion*' -Dsurefire.failIfNoSpecifiedTests=false` → **EXIT=0**。
3. 人工验证（部署后）：
   - 打开 `use_announce_global_level_up`，进一个有不少未观察 TrainingBot 的图（或离图后原地），等其抽象升级 → 真玩家应收到 `[升级信息] 玩家 <名字> 在地图 <地图> 升到了 <等级> 级`。
   - 同图观察一只 bot 真实击杀升级 → 只出现**一条**（宿主发的），确认无重复。
   - 关闭开关 → 两类都静默。
   - 确认公告不会出现在 bot 自己的聊天/日志里（只投递真玩家）。

## 6. 备选方案（已评估，不采纳）

- **改宿主 `gainExpInternal` 加 `isArtificial` 分支**：只能“让 bot 不公告”，无法覆盖绕过 `gainExp` 的静默路径；且需要动 GMS083、加宿主侧 bot 专用分支，违背“宿主不 import 插件”的边界取向。**不采纳。**
- **把静默路径改成调用 `gainExp`**：会在未观察时仍产生逐级升级发包/特效，破坏“unobserved = 零发包”的 LOD 设计。**不采纳。**

---

### 附：本次核实的关键文件/行

| 侧 | 文件:行 | 内容 |
|---|---|---|
| 宿主 | `Character.java:3091-3104` | 真玩家世界公告循环（判定+文案+投递） |
| 宿主 | `Character.java:6146-6147` | 满级彩蛋世界广播（对照） |
| 宿主 | `i18n/message_zh_CN.properties:4` | 公告文案模板 |
| 宿主 | `db/migration/V1.7.0__create_game_config.sql:98` | 开关 `use_announce_global_level_up`（默认 false） |
| 宿主 | `Character.java:5736` | `getWorldServer()` |
| 宿主 | `World.java:567` / `PlayerStorage.java:98` | `getPlayerStorage()` / `getAllCharacters()` |
| 宿主 | `Monster.java:659-747` | 击杀经验分配 → `gainExp`（bot 观察路径入口） |
| 插件 | `TrainingBot.java:1313-1338` | 抽象升级（静默，缺口 A） |
| 插件 | `SoloGrindController.java:376-404` | 伴侣独自刷怪升级（静默，缺口 B） |
| 插件 | `HostCompanionRuntimeAdapter.java:243-257` | 伴侣离线结算（走 `gainExp`，不由本次改） |
| 插件 | `BotGeneration.java:474-484` | bot 注册进世界/频道存储（`gmLevel=0`） |
