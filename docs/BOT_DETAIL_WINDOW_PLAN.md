# Bot 详情窗口数据补充方案（怪物卡 / 勋章收藏 / 想要购买的道具）

> 状态：**已实现**（`soloMapling.ArtificialPlayer.BotDetailSystem`；`BotDetailWindow` 门面 +
> `BotMonsterBook` / `BotMedalBook` / `BotWishList` / `BotDetailRoll` + 单测；GM `!bot detail` 巡检）。
> 本文件保留为设计档案；实现与规划的差异见文末 §11「实现记录」。
> 目标：玩家在游戏内打开某个 bot 的**角色详情窗口**时，窗口像真人一样显示：怪物卡收集、勋章（称号）收藏、
> 心愿单（想要购买的道具）。
> 约束（用户已确认）：① **出生时预置**；② 勋章 = **佩戴勋章 + 勋章收藏(29xxx任务)**；
> ③ 宿主默认不改（**后续用户放开**，故把唯一的运行期反射替换为宿主 public API，见 §11.2）。
> 交付：**逐个处理、逐个复查、逐个提交**（5 个提交，见 §6）。

---

## 0. 结论先行

1. **「详情列表」= 游戏内角色信息窗口**，由 `CHAR_INFO_REQUEST(0x61)` → `CharInfoRequestHandler`
   → `PacketCreator.charInfo(player)`（`SendOpcode.CHAR_INFO=0x3D`）驱动。该包尾段
   （`PacketCreator.java:2776–2801`）**只**携带这三类数据（**不含逐卡列表**）：

   | 数据 | 包内字段 | 宿主 API | bot 现状 |
   |---|---|---|---|
   | 怪物卡 | `bookLevel / normalCard / specialCard / totalCards / coverMobId` | `chr.getMonsterBook()`（**仅读 4 个计数器**） | 继承 `fmbot` 模板 cid → 空/同质 |
   | 勋章 | 槽 `-49` itemId + **已完成 `>=29000` 任务列表** | `getInventory(EQUIPPED).getItem(-49)` + `getCompletedQuests()` | 佩戴已由 `BotMedalSystem` 投；**收藏列表空缺** |
   | 想要购买的道具 | 心愿单 SN 列表（≤10） | `chr.getCashShop().getWishList()` | 继承模板 cid → 空/同质 |

2. **三类都是纯 `Character` 内存引擎态** → 出生时直写即完成，"打开时补充"由"出生时已就位"达成。
   **无需发包代码、无需新增事件**；怪物卡聚合经宿主新增的 `MonsterBook.setCardCounts` 写入（§11.2）。

3. **逐卡列表（`addMonsterBookInfo`）不参与本窗口**：它只出现在 `getCharInfo`（`SET_FIELD`，**仅真玩家登录时发给本人**）
   与 `openCashShop`，而 bot 的 `BotClient.sendPacket` 是 no-op 且无任何路径对其调用 → **bot 的 `MonsterBook.cards` 映射永不被序列化**。
   故**只写 4 个计数器即可**，**不写 `cards` 映射**（v1 的 cards 填充属过度设计，已删除，见 §10-D1）。

4. **注入点与 `BotMedal` 完全对称**（唯一注入、可同路径复查），实测**共 9 处**（v1 漏计，见 §10-D4）：
   `BotDecorate` 两个重载的**主路径 + beginner 分支共 3 处**、`BotGeneration.loadPersistentBot` 1 处、
   5 处等级/职业/勋章覆写重掷点。

5. **确定性（按 cid 派生，复用 `BotMount.mix` 风格）**：同伴跨重启稳定、重掷幂等。
   **ambient bot 因 `loggedIn=false` 不会被宿主保存 → 只在内存，零库写**（该门控已核实，见 §1.3）。

---

## 1. 现状调研（证据）

### 1.1 详情窗口 = `charInfo` 包，只读 4 个计数器 + 封面

`PacketCreator.charInfo`（`2724`）尾段（`2776–2801`）：

```java
MonsterBook book = chr.getMonsterBook();
p.writeInt(book.getBookLevel());   p.writeInt(book.getNormalCard());
p.writeInt(book.getSpecialCard()); p.writeInt(book.getTotalCards());
p.writeInt(chr.getMonsterBookCover() > 0 ? IIP.getInstance().getCardMobId(chr.getMonsterBookCover()) : 0);

Item medal = chr.getInventory(InventoryType.EQUIPPED).getItem((short) -49);   // 佩戴勋章
p.writeInt(medal != null ? medal.getItemId() : 0);

for (QuestStatus qs : chr.getCompletedQuests())                               // 勋章收藏
    if (qs.getQuest().getId() >= 29000) medalQuests.add(qs.getQuest().getId());
p.writeShort(medalQuests.size()); for (Short s : medalQuests) p.writeShort(s);

p.writeByte(chr.getCashShop().getWishList().size());                          // 心愿单
for (int sn : chr.getCashShop().getWishList()) p.writeInt(sn);
```

- `getBookLevel/NormalCard/SpecialCard/TotalCards` 读的是**私有 int 计数器**（`getTotalCards = special+normal`），
  **不读 `cards` 映射**。
- `getCards()` / `getCardSet()` 的**唯一**消费者是 `PacketCreator.addMonsterBookInfo`，其**唯一**调用链是
  `getCharInfo(SET_FIELD)`←`PlayerLoggedinHandler`（**仅真玩家登录发本人**）。**bot 永不触发**（§3.1）。

### 1.2 三类数据当前从模板 cid 载入

`Character.fromCharactersDO`（`6632`，**任何**载入角色都执行）：

```java
chr.setBookCover(charactersDO.getMonsterbookcover());
chr.setMonsterBook(new MonsterBook(charactersDO.getId()));                  // 按 cid 读 monsterbook 表
chr.setCashShop(new CashShop(acctId, charactersDO.getId(), jobType));       // 按 cid 读 wishlists 表
```

ambient bot 用 `fmbot` 模板 cid 载入 → 全服共享模板那份。**插件当前对这三类零写入**
（全库仅 `BotLevelUpNotice` 读 `getCashShop().isOpened()`）。

**关键豁免（决定 ambient 需覆盖的项）**：`CharacterService.loadCharFromDB` 在 `if (!channelServer) return chr;`
**早退**（`422`），而 `setMonsterBook`/`setCashShop` 在更早的 `fromCharactersDO`（`6694/6789`）内、`setLoggedIn(true)`
与 quest 载入（`458/459`）在**其后**。故：

| 数据 | ambient（`channelServer=false`） | 同伴（`true`） |
|---|---|---|
| MonsterBook | **有**（模板 cid 的那份） → 须覆盖 | 有（自身 cid 的） |
| CashShop 心愿单 | **有**（模板 cid 的那份） → 须覆盖 | 有（自身 cid 的） |
| quests | **空**（未载入） → 直接加 | 有（自身 DB） |
| `loggedIn` | **false**（早退于 `setLoggedIn` 前） → 不被 autosave 保存 | true |

### 1.3 关键约束（已逐条核实）

| # | 事实（证据） | 对方案的影响 |
|---|---|---|
| C1 | `MonsterBook` 的计数器原为 **private 且无 setter**；`addCard(Client,id)` 会 `map.broadcastMessage`+发包，共享 `BotClient.getPlayer()` 常为 null → NPE | **宿主新增 public `MonsterBook.setCardCounts(normal,special)`**（本方案附带，唯一宿主改动，已完成 `c024f68`）；插件直接调用，**无反射** |
| C2 | 封面 `getCardMobId(cover)` 返回 `int`，若 `cover>0` 而 `monstercarddata` 无该行 → **拆箱 NPE，打断该 bot 对所有人的窗口** | **一律 `setBookCover(0)`**（安全，非仅观感） |
| C3 | `updateQuestStatus` 有副作用（`awardQuestPoint`→`gainFame`、`announceUpdateQuest` **发包**） | 勋章收藏**用 `getQuestNAdd(...).setStatus(COMPLETED)` 绕开**（仅 `quests.put`+字段赋值，无包/fame） |
| C4 | `Character.getQuests()` 是 Lombok `@Getter` on **final Map**（**public、活引用**） | 加收藏用 `getQuestNAdd`（synchronized，无需反射）；`clear` 用 `getQuests().remove`（**无需反射**，v1 误称需反射） |
| C5 | `CashShop.addToWishList` 是**追加** | 先 `clearWishList()` 再写 |
| C6 | 宿主每小时 `CharacterAutosaverTask` 遍历 `world.getPlayerStorage().getAllCharacters()`，**仅对 `chr.isLoggedIn()` 保存** | ambient bot 走 `loadCharFromDB(cid, client, **false**)` → **早退于 `setLoggedIn(true)` 之前** → `loggedIn=false` → **不被保存**；同伴走 `true` → 被保存（符合预期） |
| C7 | `saveCharToDB` 写 `monsterbook`(`saveCards` 只写 `cards` 映射)/`queststatus`/`wishlists`(`DELETE`+`INSERT`) | 同伴持久化：勋章收藏/wishlist 真落库；**怪物卡计数器不落库**——靠"每次载入确定性重算"稳定（见 §3.1） |
| C8 | `charInfo` 的勋章收藏过滤**无上界**，凡已完成 `>=29000` 皆入列表 | 我们只投 `29xxx` 真实勋章任务 → 精确 |
| C9 | `QuestStatus(quest, NOT_STARTED)` 构造**不** `registerMobs`；`setStatus(COMPLETED)` 无副作用 | 安全 |

### 1.4 可复用设施

| 用途 | 设施 |
|---|---|
| WZ 单文件扫描 | `BotMedalPool.load()`（`XMLWZData.parse` + `DataTool`） |
| WZ 整包读取 | `DataProviderFactory.getDataProvider(WZFiles.QUEST).getData("Act.img")` + `DataTool.getInt` |
| 确定性 cid 混杂 | `BotMountSystem.BotMount.mix(int cid)` |
| 集合聚合写入 | 宿主 `MonsterBook.setCardCounts(int,int)`（本方案新增的 public API，取代反射） |
| 可佩戴勋章集 | `BotMedalPool.eligibleFor(Character)`（public；已封 reqLevel/reqJob/reqStats/reqPop + 合法性） |
| 佩戴勋章 id | `BotMedal.currentMedalId(Character)`（已有） |

### 1.5 已实测数据源（本服 GMS083）

- **怪物卡**：`Item.wz/Consume/0238.img.xml` = **343** 张（`2380000`–`2388070`），
  普通 `id<2388000` **295**、特殊 `>=2388000` **48**；`String.wz(-zh-CN)/Consume.img.xml` 343 张**全部有中文名**。
- **勋章收藏**：`Quest.wz/Act.img.xml` 中 `29xxx` 且奖励 `114xxxx` 的勋章任务 **28** 个
  （29000→1142001 … 29580→1142084；**29300–29304 为职业专属** `1142009–1142013`）。
- **心愿单**：`Etc.wz/Commodity.img.xml` 8948 条商品，`OnSale=1` **2010** 条；运行时经 `CashItemFactory.getItems()`（`javap` 确认存在）。
- **bot 等级**：`generateBotLevel(tier,10,80)` → 10–80（均值≈48）；beginner `<10`；console bot 不装饰。

---

## 2. 总体设计

新增包 `soloMapling.ArtificialPlayer.BotDetailSystem`（宿主仅新增 1 个 public API，见 §11.2）：

```
BotDetailSystem/
├─ BotDetailWindow.java   门面：ENABLED + apply(bot) / reroll(bot)
├─ BotMonsterBook.java    怪物卡计数器（调 host `setCardCounts`；不碰 cards）
├─ BotMedalBook.java      勋章收藏（Quest.wz 建 29xxx→114 映射；getQuestNAdd 写 COMPLETED）
└─ BotWishList.java       心愿单（CashItemFactory 池；clearWishList+addToWishList）
```

- **门面**：
  ```java
  public static boolean ENABLED = true;                  // 单一开关（同 BotMedal.ENABLED）
  public static void apply(Character bot)  { if(!ENABLED||bot==null) return;
      BotMonsterBook.apply(bot); BotMedalBook.apply(bot); BotWishList.apply(bot); }
  public static void reroll(Character bot) { BotMonsterBook.clear(bot); BotMedalBook.clear(bot);
      BotWishList.clear(bot); apply(bot); }
  ```
- **确定性**：三子项各用**不同盐**的 `mix(cid)` 派生，互不相关。
- **容错（真实外部边界）**：池建失败/空（WZ 或商城未就绪）→ 对应子项 no-op、**不抛异常**、不阻塞 spawn。
- **不做多余空值防御**：`bot` 由 `loadCharFromDB` 保证 `monsterBook/cashShop` 非空（内部不变量）；门面只挡 `bot==null`（同 `BotMedal` 风格）。仅对 **池为空** 这一真实边界做保护。

### 2.1 注入点（实测 9 处，与 `BotMedal` 一一对齐）

| 场景 | 位置 | 调用 |
|---|---|---|
| 装饰·随机路径 | `BotDecorate.setBotVariables(Character bot)` 末尾（`BotMedal.apply` 后） | `apply` |
| 装饰·参数路径·beginner | `setBotVariables(bot,base,min,max,forced)` 的 `BeginnerEquip` 分支（`return` 前） | `apply` |
| 装饰·参数路径·主 | 同上方法主路径末尾（`BotMedal.apply` 后） | `apply` |
| 持久伙伴 | `BotGeneration.loadPersistentBot`（`BotMedal.reroll` 后） | `apply` |
| 等级覆写 | `EnvironmentManager:890`（`BotMedal.reroll` 旁） | `reroll` |
| 等级覆写 | `EnvironmentManager:1163`（`BotMedal.reroll` 旁） | `reroll` |
| 勋章命令 | `ArtificialPlayerCommand:490`（`rerollmedal` 旁） | `reroll` |
| setlevel | `ArtificialPlayerCommand:614`（`BotMedal.reroll` 旁） | `reroll` |
| setjob | `ArtificialPlayerCommand:627`（`BotMedal.reroll` 旁） | `reroll` |

> 9 处全部紧邻既有 `BotMedal.apply/reroll`，改动面极小、复查路径与勋章完全一致。

---

## 3. 子项设计

### 3.1 怪物卡 `BotMonsterBook`

- **池 `load()`**（`onLoad` 启动一次，与 `BotMedalPool.load()` 并列）：扫 `WZFiles.ITEM.getFile()/Consume/0238.img.xml`，
  取 `0238xxxx` 真实卡 id（343），经 `BotHelpers.isUsableItem(id)`（中文名门，与 `BotMedalPool` 一致）。
  日志 `[BotMonsterBook] Loaded N cards`。
- **纯函数 `roll(cid, level, List<Integer> pool)`**：
  - `normal = clamp(scaled(level, 4..40))`；`special = clamp(scaled(level, 0..6))`（低等级少、特殊稀有）；
  - 按 `mix(cid)` 派生顺序**无重复**取样 `normal` 个普通、`special` 个特殊；
  - `bookLevel` **精确复刻宿主 `calculateLevel()`**：`lv=0,e=1; do{lv++; e+=lv*10;}while(normal+special>=e);`（0 张时 =1）；
  - 返回 `{normalCard, specialCard, bookLevel}`（**不含 cards 映射**）。
- **`apply(bot)`**：调 `bot.getMonsterBook().setCardCounts(normal, special)`（宿主 public API，内部重算 bookLevel）。
  **`bot.setBookCover(0)`**（C2 安全）。进程内写、无包、无库写。
- **`clear(bot)`**：计数器归 0、`bookLevel=1`、`cover=0`（供 `reroll`）。
- **持久性（关键修正）**：宿主 `MonsterBook.saveCards` **只写 `cards` 映射**，`loadCards` 又**由该映射反推计数器**
  ——所以"只写计数器"的同伴数据**不会随 `saveCharToDB` 持久化**。因此：
  - **ambient bot**：不落库，每次 spawn 现算，无碍；
  - **同伴**：`loadPersistentBot` **每次载入都调 `apply`**（注入点 #4），确定性 `roll(cid)` → 每次算出**同一份**计数器
    → **跨重启显示稳定**（无需写 `cards` 映射，也无需改 `cards`）。
  - 结论：**不写 `cards` 映射**仍是正确的（窗口不消费它；持久稳定性由"确定性重算"保证，而非 DB）。

### 3.2 勋章收藏 `BotMedalBook`

- **池 `load()`**（启动一次）：`DataProviderFactory.getDataProvider(WZFiles.QUEST).getData("Act.img")`
  → 顶层 `imgdir` 中名 `29xxx` 的 → `child("1")/item/*/id` 为 `114xxxx` → 记 `questId→medalId`（28 条）。
  日志 `[BotMedalBook] Loaded N medal quests`。
- **可投集合 `eligibleQuests(bot)`**：对 28 条映射，用 `medalId` 反查合法性——直接复用
  `BotMedalPool.eligibleFor(bot)`（已封 `reqLevel/reqJob/reqStats/reqPop` 与合法性），把其返回的
  `Medal.id` 集合与映射表求交。实测结果：bot 等级带（≤80）下 **22 条可用**（`29300–29304` reqLevel=180、
  职业专属 → 天然被排除；`29019→1142140` reqLevel=100 → 高等级才可用）。**无需自造 `reqJob` 位逻辑**
  （`BotMedalPool.baseClassBit` 是包级私有，不跨包；`eligibleFor` 才是 public 复用点）。
- **纯函数 `select(cid, level, List<Entry> eligible, int wornMedalId)`**：
  - `k = clamp(scaled(level, 1..eligible.size()))`；
  - **佩戴勋章 `wornMedalId` 命中映射则其任务必入选**（"头顶称号在收藏里"）；
  - 其余按 `mix(cid)` 无重复补齐至 `k`；返回升序 `questId`。
- **`apply(bot)`**：对每个 `q`：`bot.getQuestNAdd(Quest.getInstance(q)).setStatus(COMPLETED)`（C3/C4，无发包/fame）。
- **`clear(bot)`**：`bot.getQuests().remove(q)`（只删本方案可能投的 29xxx；**不碰**其它已完成任务）。
  局限：若同伴本就有此 29xxx 真实完成记录，`clear` 会一并删（可接受，见 §7）。

### 3.3 想要购买的道具 `BotWishList`

- **池**：`CashItemFactory.getItems()` 过滤 `isSelling() && !ItemId.isCashPackage(itemId)` 取 `sn`；
  **运行期惰性取**（不缓存过期快照），空则 no-op。
- **纯函数 `select(cid, level, List<Integer> snPool)`**：`k = clamp(scaled(level, 0..10))`（客户端心愿单容量 10），
  按 `mix(cid)` 无重复取样。
- **`apply(bot)`**：`cs.clearWishList(); sns.forEach(cs::addToWishList);`（C5）。
- **`clear(bot)`**：`bot.getCashShop().clearWishList();`。

---

## 4. 启动接线

`SoloMaplingExtension.onLoad` 末尾（既有 `BotMedalPool.load()` 旁边）追加：
`BotMonsterBook.load(); BotMedalBook.load();`
两处各自 try 内、失败不影响启动（同 `BotMedalPool`）。`BotWishList` 无 `load()`（运行期取池）。

---

## 5. 测试（纯逻辑，无 WZ/服务器；与 `BotMedalPoolTest`/`BotMountDeterminismTest` 同风格）

| 测试 | 覆盖 |
|---|---|
| `BotMonsterBookTest` | `roll` 确定性；计数随等级单调；`normal+special` 与池取样一致；`bookLevel` 与宿主公式一致（含 0/边界）；采样不越界/不重复 |
| `BotMedalBookTest` | `select` 确定性；佩戴勋章任务入选（可映射时）；职业专属按 `jobBit` 过滤；数量随等级；id 均属已知 28 条；不重复 |
| `BotWishListTest` | `select` 确定性；`size<=10`；无重复；id 均属池；数量随等级 |

> 不再保留 v1 的"注入复用性 verification"（空泛、属过度设计，见 §10-D5）。

---

## 6. 提交拆分（逐个处理 / 逐复查 / 逐提交）

| # | 提交 | 内容 | 复查要点 |
|---|---|---|---|
| 0 | `docs(detail): plan for bot detail-window data` | 本方案 | 方案评审 |
| 1 | `feat(bots): preset monster-book counters for bots` | `BotMonsterBook` + 门面骨架 + 9 处注入 + `BotMonsterBookTest` | 计数器/`bookLevel` 公式/`cover=0`/不写 cards/ambient 不落库 |
| 2 | `feat(bots): preset medal collection (29xxx) for bots` | `BotMedalBook` + 测试 | 绕开 `updateQuestStatus`；含佩戴勋章；职业过滤；无包 |
| 3 | `feat(bots): preset cash-shop wishlist for bots` | `BotWishList` + 测试 | 上限 10；先清后写；池过滤可售 |
| 4 | `feat(bots): GM inspect cmds for detail-window data` | `!bot monsterbook\|medals\|wishlist <cid>` | 逐项人工复查 |
| 5 | `docs(detail): record implementation` | 文档转"实现记录" | — |

- 每提交前：`mvn -q -DskipTests package` 通过 + **该子项**单测绿。
- 每提交后：`git diff` + 重读，确认未碰无关文件（AGENTS「交付前重读 diff」）。
- **全程 worktree `/tmp/wt-botdetail`（分支 `feat/bot-detail-window`，基线已刷新到 `optimize/performance` 现tip `82acb5c`）**；
  验证并经用户确认后再合回 `optimize/performance`、推送、清理（AGENTS worktree 规范）。

---

## 7. 风险与备选

| 风险 | 处置 |
|---|---|
| 封面 NPE（`getCardMobId` 拆箱） | **`cover=0`**（C2） |
| ~~反射写 private 计数器~~ | **已移除**：改用宿主 `MonsterBook.setCardCounts`（`c024f68`）。|
| 商城未就绪致池空 | 运行期惰性取池、空则 no-op，下次 spawn 自然补 |
| 同伴 `clear` 误删真实 29xxx 记录 | 低概率（同伴为 bot）；如需可在 `clear` 前只删"本方案投的"（MVP 不做，YAGNI） |
| `bookLevel` 与宿主口径差异 | 精确复刻 `calculateLevel()` 公式 |
| **注入时点并发**：bot 先 `placeBotOnMap` 再 `setBotVariables`，窗口极小内玩家可能读到半写状态 | 与既有 `BotFame/BotMedal/BotEquipStats` **同一模式**（它们也在地图放置后改角色态），故**沿用现有风格**；写值自洽、只影响瞬态显示，不崩溃。计数写入本身经宿主 `setCardCounts` 在 `MonsterBook.lock` 下原子完成 |

---

## 8. 验收

1. 高等级 bot 详情窗口：怪物卡显示非零等级/普通/特殊/总数；勋章栏显示佩戴称号且收藏含若干 29xxx；心愿单含若干道具。
2. 低等级（<10）bot：怪物卡/心愿单接近空、无勋章。
3. ambient bot：进程结束后库中**无**新增 `monsterbook/queststatus/wishlists` 行（C6 门控）；同伴重启后窗口稳定。
4. 三子项单测绿；插件构建通过；**宿主 GMS083 零改动**（`git -C /workspace/GMS083 status` 干净）。

---

## 9. 明确不做（YAGNI）

- 不改宿主 `CharInfoRequestHandler`/`PacketCreator`，不新增 `HostEvent`（用户选"出生时预置"）。
- **不写 bot 的 `MonsterBook.cards` 映射**（窗口不消费；§1.1/§10-D1）。
- 不做封面 / 逐卡列表精修；不覆盖 console bot（与 `BotMedal` 对齐）。
- 不做 LLM/剧情联动。

---

## 10. 审查发现（v1 → v3）

| 编号 | 类型 | 早期版本的问题 | 修正 |
|---|---|---|---|
| D1 | **过度设计** | 花力气反射填 bot 的 `MonsterBook.cards` 映射以"保持 SET_FIELD 一致" | 该映射**永不被序列化**（`addMonsterBookInfo` 仅真玩家登录发本人）；**删除** cards 写入，仅写 4 计数器 |
| D2 | **事实表述错误** | 称"清空 quests 需反射" | `Character.getQuests()` 是 Lombok `@Getter` **public 活 Map** → **无需反射** |
| D3 | **事实表述不完整** | 称"ambient 不写库"但未给依据 | 补 **C6 `isLoggedIn` 门控**实证（`channelServer=false` 早退） |
| D4 | **遗漏** | 注入点写 5 处 | 实测 **9 处**（`BotDecorate` 两个重载的主路径+beginner 分支 = 3，另 6） |
| D5 | **过度设计** | 含"注入复用性 verification"测试项 | 删除（空泛） |
| D6 | **过度设计** | 每子项独立 `ENABLED` 开关矩阵 | 收敛为**单一 `ENABLED`** |
| D7 | **流程违规** | 宿主代码调研大量用 grep/Read，未先走 CodeGraph（AGENTS 要求优先 CodeGraph） | 后续查宿主/插件代码**一律先 `codegraph explore`**；worktree 无索引时回主仓路径查询 |
| D8 | **基线陈旧** | worktree 基线 `5387c28` 已落后 `optimize/performance`(`82acb5c`) | 已刷新 worktree 到现 tip |
| D9 | **正确性** | 职业专属勋章任务未过滤 reqJob | 改**复用 `BotMedalPool.eligibleFor(bot)`**（public）反查合法性；实测 bot 等级带可用 22/28 |
| D10 | **正确性** | 未明确"只写计数器是否够" | 以 §1.1 逐行证据确认窗口只读计数器 |
| D11 | **事实错误** | v2 称 `BotMedalPool.baseClassBit` 为 `public static` 可跨包复用 | 实为**包级私有**；改走 `eligibleFor`（public），不跨包引用包私有成员 |
| D12 | **正确性遗漏** | v2 未对勋章任务做 `reqLevel` 过滤（如 `29019→1142140` reqLevel=100） | 由 `eligibleFor` 统一封顶（等级不足即不入收藏），无需另写 |
| D13 | **并发/稳健性** | 未评估"bot 已在地图、装饰前玩家即读"的瞬态窗口 | 记录为低风险（与 `BotFame/BotMedal` 同模式，非本方案引入）；可选加 `MonsterBook.lock` 加锁，MVP 不做 |

---

## 附：审查方法论

- 宿主代码定位**先走 CodeGraph**（`codegraph explore`），命中文件不再 Read；插件主仓同理（worktree 无索引时回主仓路径查询）。
- 关键 API 用 `javap -p` 对**已编译宿主类**复核可见性（如 `baseClassBit` 实为包私有）。
- 关键行为用**真实数据/运行时小样例实测**（去反射后改为宿主 public API 直调），不靠推断。
- 数据源用脚本对**真实 WZ 文件**统计（卡 343 / 勋章任务 28 / 可投 22 / 商品 2010）。

---

## 11. 实现记录

实现与规划的差异 / 落地细节：

1. **包**：`soloMapling.ArtificialPlayer.BotDetailSystem` = `BotDetailWindow`（门面）+ `BotMonsterBook` /
   `BotMedalBook` / `BotWishList`（三子项）+ `BotDetailRoll`（共享确定性 `mix`/`sample`）。
2. **注入点合计 9 处**（提交 #1 一次接线完成，后续子项复用同一批注入点，无需再加）：
   `BotDecorate.setBotVariables` ×3（随机路径、参数路径主路径、beginner 分支）、`BotGeneration.loadPersistentBot` ×1、
   `EnvironmentManager` ×2、`ArtificialPlayerCommand`（`rerollmedal`/`setlevel`/`setjob`）×3。
3. **怪物卡（#1）**：经宿主 public `MonsterBook.setCardCounts(normal,special)` 写计数（宿主内部重算 `bookLevel`），
   `cover` 恒 0。池扫 `Item.wz/Consume/0238.img.xml` = **343** 张（普通 295 / 特殊 48）。
4. **勋章收藏（#2）**：`Quest.wz/Act.img.xml` 运行时读取实测 = **28** 条映射（脚本 vs 运行时曾出现
   `29509`/`29580` 一处差异，**以运行时读取为准**）。可投集合复用 `BotMedalPool.eligibleFor`；bot 等级带内 **22** 条可用。
   写 `getQuestNAdd(...).setStatus(COMPLETED)`。
5. **心愿单（#3）**：池 = `CashItemFactory.getItems()` 过滤 `isSelling && !isCashPackage`，**按宿主 catalog 的 map
   身份缓存**（GM 重载会换 map → 自动重建），上限 10。
6. **发现的真实缺陷（已修）**：共享 `BotDetailRoll.sample` 在 `k<=0` 时返回不可变 `List.of()`，而心愿单调用处对其
   `Collections.sort` → 等级 10 bot 会抛 `UnsupportedOperationException`。已改为始终返回可变 `ArrayList`（提交 #3）。
7. **GM 巡检（#4）**：`!bot detail <cid>` 打印窗口三类实际数据（读活引擎态，非预设输入）；`!bot rerolldetail <cid>`。
8. **测试**：`BotMonsterBookTest`(4) / `BotMedalBookTest`(6) / `BotWishListTest`(5) 全绿；
   全套 **1147 tests, 0 failures**（去反射后、rebase 到 `optimize/performance` 现 tip 的复测）。
9. **宿主改动**：仅 1 处——`MonsterBook.setCardCounts`（§11.2）；其余宿主工作树不变。

> 注：本节 3/8 于第三轮审查更新（去反射后的最终形态）。

### 11.1 自审（第二次完整审查）发现并修复的真实缺陷

- **BUG-A（高）同伴勋章收藏累积**：`BotGeneration.loadPersistentBot` 原用 `apply`（只增不删），
  而同伴会持久化 quests（`saveCharToDB → queststatus`）。同伴升级并重启后，收藏会**不断累积**而非等于
  当前等级的确定集合。已改为 `reroll`（clear+apply），与相邻的 `BotMedal.reroll` 一致。
  实测佐证：跨等级 12→75 的并集达 21/22，而单等级仅 21。修复见提交 `fix(bots): reroll detail-window data for companions; make wishlist cache atomic`。
- **BUG-B（低）心愿单缓存双 volatile 竞态**：`cachedSource`/`cachedPool` 两个独立 volatile，并发读者可能
  配到"新 source + 旧 pool"。改为单个不可变 `record CachedPool`，一次 volatile 写发布。
- **清理**：把 `getMap()==null` 门收敛到门面 `BotDetailWindow`（对齐 `BotMedal`），移除子项内重复检查。
- **rebase**：工作期间 `optimize/performance` 两次前进（PQ 动态引擎重构 `82acb5c → 39b0bd6`，随后
  PQ 招募点覆盖 `39b0bd6 → cc7cdec`）。首次与 `BotGeneration`/`EnvironmentManager` 有文件交叠（已 rebase 保留）；
  第二次无文件交叠（纯 PQ 路径）。两次 rebase 后 9 处注入点与全部改动完整保留，重建 + 全套测试绿。

### 11.2 宿主改动（用户放开"尽量不改宿主"后）

- 动机：本方案唯一的**运行期反射**是 `BotMonsterBook` 写 `MonsterBook` 的三个私有计数器。用户明确要求"去除 runtime 反射"，
  并放开宿主修改权限。
- 改动（唯一、最小）：`gms-server/.../client/MonsterBook.java` 新增
  `public void setCardCounts(int normalCard, int specialCard)`：设置两计数器并用与 `addCard` **完全相同**的曲线重算
  `bookLevel`。把 `calculateLevel()` 的循环抽成共享的 `private static int levelForTotal(int)`，两条路径同源（无行为变化）。
  - 提交：host `c024f682`（分支 `feat/monsterbook-set-aggregates`）。
  - 无 pack/协议变更；纯新增 public 方法 + 等价重构。
- 插件侧：删除全部 `java.lang.reflect`，改调 `book.setCardCounts(normal, special)`，并删除插件内重复的 `bookLevelFor`
  公式（逻辑归位宿主）。净减 ~65 行。提交：`refactor(bots): preset monster book via host API, drop reflection`。
- 为何不选其它方案：`addCard` 需活客户端且会广播；直接加 `setBookLevel` 会绕过计数一致性；`setCardCounts` 一处封住
  "计数 + 等级"不变式，是最小且正确的宿主 API。
