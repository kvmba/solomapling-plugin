# Bot 名字 / 职业一致性优化方案（职业驱动命名）

> 状态：**已实现**（方案 C 落地；`BotNamePool` + `FMShopDescGen.getRandomCharacterIGN(int)` +
> `BotGeneration.createBotOn` 单次掷骰 + 伴生体按种子命名；新增/扩展 4 个测试，全量 1162 用例通过）。
> 本文件保留为设计档案，实现与规划的差异见文末「实现记录」。
> 目标：消除"名字里的职业词"与"bot 实际职业"不一致的观感问题——bot 顶着"圣骑士/大主教/
> 魔法师"之类 IGN，实际却是个飞侠/弓手。
> 约束（用户已确认）：采用**职业驱动命名（方案 C）**，内容全保留、代码根治、对未来新增条目自动生效。

---

## 0. 结论先行

1. **问题真实且量大**：zh-CN 名字池 10000 条里 **3561 条（35.6%）含"会读成职业"的词**，
   约占三分之一。名字与职业是**两条互不相干的随机**，必然大面积错配。
2. **根因是抽取时机**：名字在 `BotGeneration.setBotStats()` 里先抽，职业在
   `BotDecorate.setBotVariables()` 里后定，二者无关联；而多数 spawn 路径其实**早已知道 baseClass**
   （`EnvironmentManager` 先 `rollBaseClass()` 再传入 `createBot`），信息白白丢弃。
3. **根治手段 = 给池子按职业类别打标签**，抽名时只在 `中立 ∪ 该 bot 职业类别` 中取。
   名字永不与职业冲突；内容一条不删；新增条目按同一规则自动归类。
4. **伴生体（companion）同一套**：名字来自同一池，职业由 `personaSeed` 决定，因此同样受益——
   只需按 `CareerBuild` 推导职业类别即可。
5. **店铺店主名不受约束**（`getRandomShopOwnerIGN`）：店主没有职业语义，保持原样。

---

## 1. 现状调研（证据）

### 1.1 名字从哪来

| 环节 | 代码 | 说明 |
|---|---|---|
| 抽名 | `FMShopDescGen.getRandomCharacterIGN()` | `FMShopDescGen.java:343`，顺序取一条、登记、返回 |
| 池来源 | `loadAndShuffleNames()` | `FMShopDescGen.java:379`，读 `randomRealMaplestoryIGNs.txt`，`Collections.shuffle` 一次 |
| 本地化 | `resolveFilePath("ign")` | `FMShopDescGen.java:397`，`FMNameDesc-zh-CN/` 优先，`FMNameDesc/` 兜底 |
| 注入点 | `BotGeneration.setBotStats()` | `BotGeneration.java:443` → `onDemandBot.setName(getRandomCharacterIGN())` |
| 伴生体注入 | `SoloMaplingExtension` 传给 `CompanionIntakeService` 的 `NameSource` | `SoloMaplingExtension.java:292`，即 `FMShopDescGen::getRandomCharacterIGN` |

池在 `getRandomIGN()`（`FMShopDescGen.java:366`）里按语言惰性重建；`namePool` + `namePoolIndex`
顺序发号，发完整个池再重洗。**没有任何"名字携带语义"的概念**——纯粹一条条取。

### 1.2 职业从哪来

| 路径 | 代码 | 说明 |
|---|---|---|
| 普通 ambient bot | `BotDecorate.setBotVariables(bot)` | `BotDecorate.java:315`，`selectJobByLevel(level)` → `selectJobForClass(rollBaseClass(), level)` |
| 带 baseClass 的 cohort | `BotDecorate.setBotVariables(bot, baseClass, min, max, forcedJobId)` | `BotDecorate.java:368`，`forcedJobId>0` 钉死；否则 `selectJobForClass(baseClass, level)` |
| baseClass 掷骰 | `BotDecorate.rollBaseClass()` | `BotDecorate.java:142`：飞侠30% 法师24% 战士23% 弓手17% 海盗6% |
| 伴生体 | `CompanionCareerBuild.fromSeed(personaSeed)` | 独立于名字；`HostRuntimeCompanionProvisioner.java:88` 写入 `career_build` |

### 1.3 名字 = X，职业 = Y，随机错配

- `EnvironmentManager.spawnScatteredTrainingBots`（`EnvironmentManager.java:411`）与
  `spawnTownCohort`（`:496`）：**先** `rollBaseClass()`，**再** `createBot(..., baseClass, ...)`。
- `BotGeneration.createBotOn`（`:214`）：`setBotStats(bot)`（抽名，`:443`）→ `setBotVariables(bot, baseClass, ...)`（定职，`:259`）。
- 即：**职业类别的掷骰结果在抽名前就已确定，却没传给抽名函数**——这是最小改动的着力点。

---

## 2. 量化（zh-CN 池，10000 条）

按 v83 五个职业类别 + post-v83 风味词 + 纯中立分别统计（一行可命多词）：

| 职业类别 | 命中行数 | 主要词（命中） |
|---|---|---|
| 战士系 | 760 | 骑士253 战士246 圣骑士145 狂战士154 勇士132 战神124 龙骑士28 |
| 法师系 | 720 | 法师287 主教286 魔法师162 大主教159 牧师78 冰雷41 火毒28 |
| 飞侠系 | 551 | 飞侠269 暗影者157 刺客87 忍者38 |
| 海盗系 | 459 | 海盗176 海盗王158 机械师157 船长126 |
| 弓手系 | 392 | 弓箭手166 射手131 弓手90 游侠5 |
| **含职业词合计（union）** | **3561（35.6%）** | |
| post-v83 风味词 | ~679 | 恶魔347 天使205 恶魔猎156 龙神127 大天使123 |
| 纯中立 | ~6439（64%） | 怪物/梗/食物/形容词，如 `酸辣粉119`、`搬砖工通宵`、`摸鱼王干饭` |

**关键噪声**（决定了分类实现不能简单"子串命中"）：

- `勇士` 有地名语义：`Noob勇士部落`（MapleStory 的 *Warriors' Village*）。
- `天使/恶魔` 在 v83 无对应职业（v83 无 4 转天使/恶魔），是"外观风味"，不宜当职业断言。
- 大量条目是 `X的Y` / `X动作Y`：`鲨鱼的骑士`、`搬砖工通宵`、`战神干饭`——判断要认角色词本身。

英文池 1636 条里仅 78 条（4.8%）命中，且多为真实 IGN（`HermitBoyo`、`PriestsHealz`），
观感不刺眼，**列为低优先级**（同一机制天然覆盖，无需额外适配）。

---

## 3. 方案设计（C：职业驱动命名）

### 3.1 角色词表（单一事实源）

新增一张**角色词 → 职业类别**映射，作为分类依据。职业类别用现成的 v83 基类标号，复用既有常量语义：

| 职业类别 | 建议标识 | 词表（子串匹配，长词优先） |
|---|---|---|
| warrior | `1` | 圣骑士 骑士 剑客 狂战士 龙骑士 龙骑 黑骑士 英雄 战士 勇士 战神 |
| magician | `2` | 魔法师 法师 大主教 主教 牧师 祭司 冰雷 火毒 |
| bowman | `3` | 弓箭手 弓手 射手 游侠 神射手 狙击手 |
| thief | `4` | 飞侠 刺客 暗影者 标飞 刀飞 忍者 |
| pirate | `5` | 海盗王 海盗 船长 拳霸 拳手 机械师 准将 |
| （中立/风味） | `0` | 其余全部；**显式**把 龙神 恶魔猎 恶魔 大天使 天使 归 0（v83 无此职业） |

> 与 `BotDecorate.rollBaseClass()` 的 1..5 编号一致，便于与现有 baseClass 贯通。
> 表放在**内容/池侧**（`FMShopDescGen` 或新 `BotNamePool`），而非 `BotDecorate`，
> 让 `BotDecorate` 不反向依赖名字池。

### 3.2 抽名 API

在 `FMShopDescGen` 增加：

```
getRandomCharacterIGN(int category)   // category: 0=中立, 1..5=v83 职业类别
```

语义：返回"中立 或 属于 category"的第一个尚未发放的名字；`category == 0` 时不做筛选，返回任意名字
（保持店主名与遗留调用的原分布）。原无参方法保留，内部转调 `getRandomCharacterIGN(0)`。

实现要点（最终落地版）：
- 不预分类、不分桶：抽取时用 `BotNamePool.categoryOf` 现场判定，配"前移游标 + 命中即交换到游标位置"，
  保证任一名字每轮最多发放一次（跨职业类别亦然）。
- 抽取仍 `synchronized`，与既有 `namePoolIndex` 机制一致，池按语言惰性重建（`namePoolLanguage` 守卫照旧）。

### 3.3 职业类别来源（把 baseClass 提前）

| 调用方 | 现在 | 改为 |
|---|---|---|
| `EnvironmentManager` cohort | 已算出 `baseClass` 再传 `createBot` | 抽名用同一 `baseClass` |
| `BotGeneration.createBot(pos,map)` 无 class | `baseClass=0` → 现随机 | 在 `createBotOn` 内**先** `rollBaseClass()`（仅当 baseClass≤0 且 forcedJobId≤0 时），再抽名、再定职 |
| GM `!spawn` / `trainhere` | 已传 baseClass / forcedJobId | forcedJobId>0 时用其职业类别；否则用 baseClass |
| 伴生体 | 名字来自 shared `NameSource` | 抽名前按 `CompanionCareerBuild.fromSeed(seed).firstJobId()/100` 得职业类别 |

`BotGeneration.createBotOn` 内部顺序调整为：
1. 解析 `int category = BotNamePool.nameCategoryFor(baseClass, minLevel, forcedJobId)`；
2. `setBotStats(bot, category)` → 抽名；
3. `setBotVariables(bot, baseClass, min, max, forcedJobId)` → 定职（**用同一次掷出的 baseClass**）。

为避免"抽名用的 baseClass"和"定职用的 baseClass"再分叉，`createBotOn` 里解析一次
`effectiveBaseClass`，两条路径共用；`baseClass<=0` 时的 `rollBaseClass()` 从
`BotDecorate` 上提到此，或提供一个 `setBotVariables(bot, category)` 重载让内部同用。

### 3.4 伴生体职业类别

`CompanionCareerBuild` → `firstJobId()/100` 即 1..5；`CompanionIntakeService` 的
`NameSource` 改为 `int -> String` 的签名（按职业类别取），或在 intake 侧先算 job 再传 category。
注意：provision 时**职业已由 seed 固定**（`career_build`），职业类别可直接推得，无需改 provision 流程。

---

## 4. 生命周期与一致性

- **重掷点**：GM `!env language reload`（换语言）→ 池重建，职业类别分桶随之重建；已生成 bot 名字不变（本就如此）。
- **重洗点**：某职业类别桶被抽空 → 只重洗该桶（或整个池后重建分桶），不影响其它桶。
- **fallback**：某职业类别桶为空（理论上不会，因为每职业类别都有词）→ 回退到中立桶，绝不抛异常。

---

## 5. 测试

沿用现有两个测试文件，扩而非换：

1. `BotIgnWordListTest`：
   - 保留全部宽度/字符/唯一性/不可见字符断言（分类不应改变这些约束）。
   - **新增**：池内任意"含职业类别词"的名字，其 `EnumSet` 与词表一致（例如含"法师"→ magician）。
   - 词表里的每个词，池内至少命中一次（防止词表写错、永远空桶）。
2. `BotNamePoolConcurrencyTest`：
   - 现有"16 线程唯一"用例改为**按职业类别并发**：同 category 抽出的名字，要么中立、要么属于该 category。
   - 新增断言：**抽出的名字不得与该 category 冲突**（含 warrior 词的名字绝不出现在 magician category 抽取中）。
3. 新增 `BotNameJobCoherenceTest`（或并入上者）：覆盖 `nameCategoryFor` 的边界——
   `baseClass=0`+`forcedJobId=0`（走随机）、`forcedJobId>0`（职业类别取自 forced job）、
   `baseClass=5`（pirate）等。
4. 现有 `CompanionProvisioningInputTest` 不动（名字合法性不受影响）。

---

## 6. 落地步骤（worktree 内，逐个提交、逐个复查）

1. **内容/常量**：把角色词→职业类别表落到单一位置（建议新 `BotNamePool.java` 或 `FMShopDescGen` 常量），
   含"post-v83 风味词显式归中立"。
2. **分桶 + API**：`FMShopDescGen` 加载时分类，新增 `getRandomCharacterIGN(int category)`；
   原无参方法转调 `category=0`。
3. **贯通 baseClass**：`BotGeneration.createBotOn` 解析 `effectiveBaseClass` 并传入 `setBotStats`；
   `setBotVariables` 复用同一值；`EnvironmentManager` / GM 命令 / `BotTypeManager` 无需改（已传 baseClass）。
4. **伴生体**：`CompanionIntakeService.NameSource` 支持按职业类别抽名；provision 流程从 `career_build` 取职业类别。
5. **测试**：按 §5 扩/增；跑 `mvn -DskipTests=false` 相关用例。
6. **文档**：更新 `FMNameDesc-zh-CN/README.md`（说明角色词分类规则）与 `RELEASE_NOTES.md`。

---

## 7. 风险与取舍

| 风险 | 处理 |
|---|---|
| 词表误分类（如 `勇士部落` 地名） | 分类用"角色词表 + 长词优先 + 显式例外表"；`勇士部落`/`天使`/`恶魔` 等进中立例外；测试锁定 |
| 职业类别桶耗尽后重洗导致分布抖动 | 只重洗单桶；中立桶体量最大，先耗尽概率低 |
| 与现有"顺序不重名"语义冲突 | 分桶后每桶独立 index，仍保证进程内每桶不重名 |
| 英文池 | 同机制天然覆盖，但词表以中文为主；英文命中率低（4.8%），v1 可只处理中文词表 |
| 最小改动原则 | 不改名字合法性校验、不改 shard/选角逻辑，只在"抽名"与"定职"之间加一层一致性 |

---

## 8. 备选方案（未选）

- **A 内容层清洗**：删/改 3561 条职业词条目。零代码、companion 一并治愈；但砍掉 35% 内容
  与"状态+角色"设计意图，且靠人肉维护、易回归。→ 仅作过渡。
- **B 内容层只留怪物/梗**：介于 A 与全保之间，仍需人肉维护。
- **D 名字驱动职业**：由名字声明的职业类别覆盖职业。会与 training cohort / forced-job /
  companion 的既定职业打架，不如 C 干净。→ 弃。

---

## 9. 决策与实现记录

决策（由实现方裁定，原两处待确认项）：

- 词表职业类别归属按 §3.1 采纳：`战神/勇士` 归 warrior，`天使/恶魔/龙神` 归中立（v83 无这些职业），
  `勇士部落`（Perion）显式归中立。落地在 `BotNamePool`，配 `BotNamePoolTest` 锁定。
- `category=0`（无参 `getRandomCharacterIGN()`）的语义与规划不同：**实现为返回任意名字**，而非
  "只从中立抽"。原因是该重载被店主名与遗留测试调用，保持其原分布最稳妥；而所有 bot 真实出生路径都
  会传入具体职业类别（或在无职业时也走 `getRandomCharacterIGN(int)`），不存在"给未知职业的 bot 抽到断言
  职业的名字"的路径。店主名（`getRandomShopOwnerIGN`）同样走 `NEUTRAL`，不受约束。

实现记录（与规划的差异）：

- **未用"分桶"**：规划的每职业类别一桶会让中立名被多个职业类别各自枚举，导致同一中立名被发放给多个 bot，
  破坏原池"一轮内全局唯一"。改为**单条乱序列表 + 前移游标扫描**：抽名时从游标起找第一个
  "中立或本职业类别"的名字，交换到游标位置并前移。游标只前进，因此任一名字每轮最多发放一次，
  全局唯一性对所有职业类别混合抽取仍然成立（`BotNamePoolConcurrencyTest.categoryDrawsNeverContradictTheCategory`
  断言 640 次混合抽取无重复）。
- **单次掷骰**：`BotGeneration.createBotOn` 把 baseClass 决策上提一次（`rollBaseClass()` 或调用方传入），
  名字职业类别与 `setBotVariables` 的定职共用同一值。原本 `baseClass<=0` 走 `setBotVariables(bot)`
  会二次掷骰，是名字/职业错配的隐藏来源。无职业路径的等级带仍是默认 10..80（等价）。
- **新手边界**：`selectJobForClass` 对 `level < 10` 返回 0（新手），而配置里存在 `level_lo: 1` 的
  训练 cohort（彩虹岛）。此时基础职业已掷出却没有职业，名字不能再按该基础职业的职业类别抽，否则新手会
  顶着职业名。`BotNamePool.nameCategoryFor(baseClass, minLevel, forcedJobId)` 把该判定收敛为一个纯函数：
  `minLevel < 10` 且无强制职业 → 中立；强制职业优先（它无论等级都属该角色）。
- **伴生体**：为消除"预测职业类别"与"实际职业"因两次随机种子而不一致的竞态，intake 改为
  **先取自种子 → 推导职业类别 → 显式把同一种子传给 provision**（`CompanionProvisioningService.nextPersonaSeed()`）。
  顺带修掉了原先"名字与种子由两个独立随机流决定、互不相关"的默认行为。
- **测试**：`BotNamePoolTest`（分类边界/映射/新手带/强制职业）、`BotIgnWordListTest`（职业类别词池内非空 +
  地名归中立）、`BotNamePoolConcurrencyTest.categoryDrawsNeverContradictTheCategory`（并发下不冲突 + 混合抽取仍唯一）、
  `CompanionIntakeServiceTest`（适配 `NameSource.next(int)`）。

- **已知边界（可接受）**：某职业类别的"可选项"（中立 + 本职业类别）会比整池更早耗尽——因为中立名被所有职业类别共用。
  当某职业类别在整池被抽空前先耗尽可选项时，`FMShopDescGen` 只能重建整池，导致在池尾（实测 ~9998/10000）
  出现一次重复，而非像原始单池那样恰好等到 10000 才重洗。这是"单池共享 + 职业类别过滤 + 全程全局唯一"三者
  不可兼得时，选择"拒绝发放冲突名"的必然代价。真实规模下（环境约 3000 bot、单职业类别可选项 ≈7400~7800）
  远不触及，观测无差异。

## 10. 后续（未做）

- 英文池（1636 条，仅 4.8% 命中职业词）未单独适配词表；同机制天然覆盖中文词，英文命中大多是
  真实 IGN，观感不刺眼，暂不处理。
- 店铺店主名（`getRandomShopOwnerIGN`）无职业语义，保持不受约束（抽 `NEUTRAL`）。
