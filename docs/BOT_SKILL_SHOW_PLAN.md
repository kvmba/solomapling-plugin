# GM 技能观测表演（Skill Showcase）方案

> 状态：设计稿（待评审，未实现）。
> 目标：GM 一条命令开启后，4 转 bot 逐个到 GM 所在房间，把自己的职业体系技能逐个「预告 → 表演」，全部演完退场，下一个 4 转职业接力。

## 1. 需求还原

| 项 | 约定 |
|---|---|
| 触发 | GM 命令开启 / 关闭（本图） |
| 演员 | 每个职业体系出 **1 只 bot**，固定为该体系 **4 转终职**、**等级 180** |
| 登场顺序 | 12 个冒险家 4 转终职：英雄 / 圣骑士 / 黑骑士 / 火毒魔导师 / 冰雷魔导师 / 主教 / 神射手 / 箭神 / 隐士 / 侠盗 / 冲锋队长 / 船长 |
| 表演内容 | 该 bot **插件侧会使用的全部技能**（沿 1→4 转职业谱系：增益、攻击、召唤兽） |
| 节奏 | 每个技能表演前：**同图气泡**报出技能**中文名** → **3 秒**后表演 **1 次** |
| 换场 | 本职业全部技能演完 → **退场（移除）** → 下一个职业登场 |
| 结束 | 12 个职业演完自动结束；GM 可随时停止 |

## 2. 现有构件盘点（全部复用，零新发明）

| 能力 | 现成构件 | 说明 |
|---|---|---|
| 按职业生成 bot（指定 job + 等级 + 出生点） | `BotGeneration.createBot(pos, map, baseClass, minLevel, maxLevel, forcedJobId)` | `!bot trainhere` 同款用法：`createBot(pos, map, niche, 180, 180, jobId)`，装饰（装备/NX/称号）按等级与职业一次性做好 |
| bot 退场 | `BotGeneration.removeBotFromServer(bot)` | `removeBotsOnMyMap` 同款 |
| 攻击技能清单 | `BotAttackConfig.resolve(job, weapon)` → `JobAttacks{single, aoe, ultimate}` | 每个 `Job` 条目含 1~3 个 `BotAttackProfile`（`skillId` / `altSkillId`） |
| 增益技能清单 | `BotBuffConfig.buffsForJob(job)` | **谱系并集、低转在前**，正是「它会使用的增益」 |
| 召唤兽清单 | `BotSummonTable.summonsForJobId(jobId)` | 11 只已注册召唤兽，按谱系归属 |
| 增益表演（施法动画 + 光环） | `BotBuffDriver.castSkill(bot, skillId)` | GM 测试入口，绕过周期调度 |
| 攻击表演（广播攻击包，无需目标） | `BotCommandsPack.BotAttack.skillSwing / rangedSwing / magicSwing(chr, skillId)` | 与真实挥击同包（`CLOSE_RANGE_ATTACK` / `RANGED_ATTACK` / 魔法包），站桩空挥即可被同图玩家看到 |
| morph 门控（超级变身 / 海盗船） | `BotAuraState.isAttackEnablerSkill(skillId)` + `showBuff` | 门控技表演前先演 enabler 光环即可解锁（与实战同一判定） |
| 同图气泡（仅气泡、不进聊天框） | `SocialCommands.BotChatbubble(chr, text)` | `getChatText(..., 1)` 纯气泡帧 |
| 技能中文名 | `String.wz/Skill.img.xml`（经 `DataProviderFactory.getDataProvider(WZFiles.STRING)`） | `BotPlaceNames` 同款读法，宿主 WZ 是中文即为中文；启动懒加载 memoize 成 `skillId → name` |
| 延时编排 | `MethodScheduler.runAfterDelay(task, delayMs)` | 全插件既有的节拍原语 |
| 召唤兽生成/驱动 | `BotSummonSystem`（`BotSummonFollower` 服务端代模拟移动/攻击） | 见 §5.3 需要一个小的定制口子 |

## 3. 总体设计

### 3.1 组件

```
SkillShowCommand (soloMapling.command)          —— !bot skillshow start|stop|status
        │
SkillShowController（单例，同时只允许一场）      —— 队列 + 状态机 + 节拍调度
        │
        ├─ JobQueue: [112,122,132,212,222,232,312,322,412,422,512,522]
        ├─ 当前 bot（无 FSM 的站桩 bot 实体）
        └─ 当前表演序列 SkillStep[]（对该 bot 一次性展开）
```

- **不需要新增 BotType / BotSM 子类**：表演 bot 由 Controller 外部驱动。`createBot` 后**不**调用 `manuallyStartBot`，bot 没有任何 FSM，天然站桩不动（地图入口 responder 走 `CharacterStorage.getBotById` 为 null 时是 no-op，不会干扰它）。
- 全程只有一个 Controller 内部循环（500ms tick 的状态机，或等价的 `runAfterDelay` 步进链），不占任何 bot tick 预算。

### 3.2 状态机

```
IDLE ──start──▶ SPAWN_JOB ──▶ BUILD_STEPS ──▶ ANNOUNCE(skill) ──▶ PERFORM(skill) ──┐
   ▲                              ▲                                                │
   │                              └──── 还有下一个 skill ◀──────────────────────────┘
   │                                        │ 没有
   └──stop/全部完成◀── DESPAWN_JOB ── 队列还有 ? ── 是 → 下一个 SPAWN_JOB
```

- `SPAWN_JOB`：在 GM 当前位置（同 foothold）`createBot(..., 180, 180, jobId)`，等待 `BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS`（7s，既有常量）落地站稳。
- `BUILD_STEPS`：对该 job 一次性展开 §4 的表演序列（增益 → 攻击 → 召唤兽）。
- `ANNOUNCE`：`BotChatbubble(bot, "接下来表演：<技能中文名>")`，等 3s。
- `PERFORM`：按技能类别调 §5 的表演函数，等 ~1.2s（动作播放）进下一个。
- `DESPAWN_JOB`：`removeBotFromServer(bot)`（自动释放召唤兽、`BotBuffDriver.clearBot` 等既有清理随退场路径生效）。

### 3.3 容错（每步前置自检）

每个节拍先校验：bot 仍在线、仍在原地图、GM 未发 `stop`、地图未卸载。任一不满足 → 清理当前 bot、回到 IDLE。表演中途服务器重启无需持久化（纯内存娱乐功能，重开即可）。

## 4. 表演序列构建（单职业）

对终职 `job`（如 `Job.HERO`）：

1. **增益段**：`BotBuffConfig.buffsForJob(job)` 顺序逐个演（低转在前，天然有「从 1 转到 4 转」的叙事感）。
   - 跳过 `Crusader.COMBO`（斗气球环是 `BotComboOrb` 专属线格式，通用光环帧会打碎观察者的球环——与 `BotBuffDriver` 周期扫描同一豁免规则）；
   - 疾驰 / 隐身术等状态绑定 aura 可以照演（GM 路径 `castSkill` 本就可达，`BotAuraState.onAuraShown` 自会登记）。
2. **攻击段**：沿谱系枚举 `BotAttackConfig.BY_JOB` 中本谱系每个 `Job` 条目的 `single / aoe / ultimate` 三个 profile，按 `skillId` 去重（`altSkillId` 武器变体不重复演，只演主形态），低转在前。
3. **召唤兽段**：`BotSummonTable.summonsForJobId(jobId)` 逐只：召唤 → 演出 ~5s（跟随/环绕/攻击由 follower 驱动，攻击型若有怪会真打）→ 移除，再召唤下一只。
4. **门控前置**：步骤 2 中命中 `BotAuraState.isAttackEnablerSkill(skillId)` 的技能（如冲锋队长的 潜龙崩击/迅捷全过程、船长的 战舰炮击/鱼雷），其 enabler（超级变身 5121003 / 海盗船 5221006）已在步骤 1 演过且时长覆盖整场（`durationOf` 按等级换算，180 级海盗船 ≈142s > 单场表演时长），无需额外处理；若未来节奏拉长导致 aura 到期，则在表演该技前先 `castSkill(enabler)` 补一次（一行守卫）。

预计单职业 ≈10~16 个技能，(3s 预告 + ~1.2s 表演)/个 ≈ **50~80s**；12 职业 ≈ **12~15 分钟**。节奏常量集中在 Controller 顶部，可调。

## 5. 每类技能的表演函数

| 类别 | 表演 | 观众看到 |
|---|---|---|
| 增益 | `BotBuffDriver.castSkill(bot, skillId)` | 施法动作 + 持久光环（派对技会真实给到附近玩家，既有行为） |
| 近战攻击 | `BotAttack.skillSwing(bot, skillId)` | `CLOSE_RANGE_ATTACK` 挥击（skillId 精确等于预告的那个） |
| 远程攻击 | `BotAttack.rangedSwing(bot, skillId)` | 弓/弩/爪射击 |
| 魔法攻击 | `BotAttack.magicSwing(bot, skillId)` | 法杖施法 + 弹道 |
| 召唤兽 | 新增 `BotSummonSystem.showSummon(bot, skillId)`（薄封装：内部走 follower 既有 `register`/生成路径，返回句柄供 Controller 定时移除） | 真实召唤实体 + MOVE_SUMMON 移动 + 攻击帧 |

> 选型说明：**空挥（cosmetic broadcast）而非 `BotAttackDriver.botAttack`**。driver 的 AUTO/forced 槽位自选技能，无法保证「预告 A 演 A」的一一对应；而空挥用的就是实战同款发包函数，观众看到的动作与 bot 实战时完全一致，且不依赖地图有怪。观测「实战里各技能怎么用」由既有 `!bot spawnatk` / `trainhere` 承担，二者互补。

## 6. 命令设计

```
!bot skillshow            —— 在本图开启（再输入一次 = 停止并清理）
!bot skillshow status     —— 当前进度（第 N/12 个职业、正在演什么）
!bot skillshow job <id>   —— （可选）只演指定职业，调试用
```

挂在既有 `ArtificialPlayerCommand` 的字符串分派里（`spawn` / `trainhere` 同款前置分支），不新增 Command 类，GM 权限沿用命令本身的等级。

## 7. 边界与守护

| 场景 | 行为 |
|---|---|
| 重复 start | 拒绝并提示当前进度（Controller 单例互斥） |
| GM 中途换图 | 表演继续在原图进行（bot 与图绑定）；GM 回来接着看 |
| 中途 stop | 移除当前 bot、清空队列，回到 IDLE |
| 地图卸载 / bot 被意外移除 | 每节拍自检失败 → 清理退场 |
| 技能 id 在 WZ 查不到名字 | 气泡回退显示 skill id（`BotPlaceNames` 同款 never-null 姿势） |
| 生日 sp | 无持久化、无落库：表演 bot 是一次性环境实体，退场即焚 |

## 8. 需要新增的代码（预估 ~350 行）

| 文件 | 内容 |
|---|---|
| `ArtificialPlayer/SkillShowSystem/SkillShowController.java` | 队列、状态机、节拍、容错自检 |
| `ArtificialPlayer/SkillShowSystem/BotSkillNames.java` | `String.wz/Skill.img` 懒加载 memoize（`BotPlaceNames` 模式） |
| `BotSummonSystem` | 新增 `showSummon(bot, skillId)` 薄封装 + 配套移除 |
| `command/ArtificialPlayerCommand.java` | `skillshow` 分派（~20 行） |

不改任何既有注册表与驱动逻辑。

## 9. 测试要点

- `SkillShowSequenceTest`：对 12 终职各断言 —— 步骤非空、无重复 skillId、enabler 门控技之前必有其 enabler 的增益步骤、COMBO 不出现。
- `BotSkillNamesTest`：已知 id（5121003 / 1121008 …）解析出名字；缺 WZ 时回退 id 字符串。
- 既有全量 `mvn test` 回归（召唤/光环封包测试不动）。

## 10. 开放问题（实现前需拍板）

1. **要不要真怪陪练**：当前方案空挥。若希望攻击技带真实伤害/掉落观感，可在表演期在 bot 脚下刷 3~5 只低级木桩怪（宿主 `LifeFactory` + `Map.spawnMonsterOnGroundToAdd`），每职业退场时清场。默认不做，待定。
2. **登场顺序**：默认按 job id 升序（战→法→弓→贼→海盗）。若要「按职业分支分组报幕」，加一段开场气泡即可，不影响结构。
3. **单场时长**：默认 3s 预告节奏；若嫌 12~15 分钟太长，可给 `skillshow` 加 `fast` 参数把预告缩到 1s。
