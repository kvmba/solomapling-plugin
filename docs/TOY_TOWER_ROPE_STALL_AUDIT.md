# 玩具塔（Eos Tower 1-100 层）bot 绳索攀爬卡住 — 审计报告

日期：2026-10-01　分支：optimize/performance @ db81e6c

## 报告的 BUG

> 本 bot 插件修复死坑等操作后，出现了在玩具塔 1-100 层大量 bot 在绳索攀爬卡住的 BUG。

## 证据链

### 1. 背景：玩具塔 cohorts 是新投放

`EnvironmentPopulation.yaml:134-156`：2026-09-20 前后新增了
`221020100..221024400` 共 21 层 × 5-7 只/层的 TrainingBot cohorts。
这些层是**纯梯子井**：每层 5-13 条 l=1 梯子（x 固定，跨 ~200-330px），
mob 站在每一行台架上，bot 的 hunt 目标天然跨层 → 大量跨层攀爬动作。

### 2. 死坑守卫的修正史（"修复死坑等操作"所指）

- `c147946` plugin-side dead-pit guard（决策侧拒绝）
- `8545214` 死坑不变量移到 LANDING（touch-down 即传送救援）
- `755ea31` **回退了 landing 救援**：probe 的逃逸模型（103px 跳高、140px 跨距、
  绳 ±60、portal ±30）比导航图真实边模型窄得多，在 LPQ 塔上一张图错杀
  7-18 个平台 → 每次起跳被拉回。改成只留决策侧守卫 + `tickUnstuck` 死坑自愈。

实测 WZ：**`22102*` 有 88 个 pit 报告**（`scripts/wzaudit/dead_pit_scan.py`），
即 DeadPitGuard 的 probe 在这些层大量判"死" —— 与 755ea31 在 LPQ 塔上
观察到的过度误杀一致。这就是"修复死坑后出现绳索卡住"的因果链。

### 3. 现存三个可实证的卡绳通道

**A. 顶部无落脚梯（28/394 条）的永久顶部钳制**
`221020000 -156,221020100 x=-3,221020300 x=7,221021700 x=-5…` 共 28 条梯子
顶部 24/20/8px 容差带内没有落脚台（`findTopExitLanding` 返回 null）。
`resolveClimbBoundary`（BotPhysicsEngine:2024）此时**钳制在 firstClimbableY 并 HOLD**——
注释里写明是"防振荡"的旧决定。挂机者正卡在这些梯顶。

**B. mid-rope 精确锚的 5px 步进网格差**
mid-rope jump-off 锚点每 30px 一个（`ropeAnchorYs`）。climbStepPerTick=5。
若 `bottomY - anchorY` 不被 5 整除，攀爬网格可能永远差 1px 够不到锚点；
`shouldSnapToClimbTarget` 依赖 `navPreciseTarget`，而 `shouldUsePreciseTarget`
在 `inAir` 时直接返回 false → 落地重抓绳后第一 tick 是非精确状态，
`shouldHoldClimbIdle` 在 `!grinding && |dy|<STOP_DIST` 时 HOLD。
与 A 叠加即"反复 wriggle 后不动"。

**C. 长梯攀爬中误触发 recovery dismount（爬/掉乒乓）**
`ClimbRecovery.CLIMB_STALL_MS=1200`（250ms tick）+ `CLIMB_PROGRESS_EPS=6`。
Eos 梯长 200-330px ≈ 40-66 步 = 2-3.3s 攀爬。攀爬速度 100px/s 对 250ms tick
只有 25px/tick… 实际 5px/tick*5 tick = 25px/beat > 6px EPS，正常攀爬不会误判。
但 **grind break 的绳息 (resting=true)** 与攀爬恢复互相争夺：
`shouldHoldClimbIdle` 中 `resting` 优先于 precise，	break 后 dismountRope(chr,0)
直线跳下 → 在无落脚的梯（A 类）跳下 = 掉回地图底。观测上就是"大量 bot 挂在绳上"。

### 4. 排除项（已实测排除）

- 顶层 step-off 落点低于 rope.topY 的 0 例（DOWN_TOL 方向安全）。
- 执行模拟（`EosTowerRopeHangReproTest`、`EosTowerGrindRopeHangReproTest`，
  已加入仓库作为可重跑 repro）在 221020400/221024400 单发起跳→到达目标 OK；
  连续 240 beats 跨层 re-issue 也未复现永久卡死 —— 说明单腿攀爬路径整体健康，
  卡住需要**组合态**（A 类梯 + break/恢复 + 非精确窗口）。

## 修复建议（按优先级）

1. **A 类梯：resolveClimbBoundary 顶部钳制 > 2s 后改为继续 fall**（或直接
   把 `findTopExitLanding` 的 DOWN_TOL 提到 30 并把 `TOP_EXIT_X_TOL` 放宽到
   12），两者都需用 `EosTowerRopeHangReproTest` 追加 case 钉住。
2. **rope break 落点检查**：`GrindBreakRoutine.tickRest` 的 `dismountRope(chr,0)`
   前用 `simulateRopeJumpLanding` 验证直落有落脚；没有就先爬到梯底再 dismount。
3. **把 `findRope` 的 ROPE_END_MARGIN 从 24 提到 40**（避开 A 类梯顶带），
   RestSpotFinder 已有 `inReach`/`highBound`/`lowBound` 结构，一处改动。

## 复现/回归资产

- `src/test/.../EosTowerRopeHangReproTest.java`（3 个单腿 sim）
- `src/test/.../EosTowerGrindRopeHangReproTest.java`（240-beat grind loop sim）
- `scripts/wzaudit/dead_pit_scan.py`（22102* = 88 pit 报告的原始证据）
