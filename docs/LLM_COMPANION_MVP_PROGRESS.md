# LLM Companion MVP Progress

This file is the durable handoff for continuing after a context reset. Update it
after every meaningful implementation or verification step.

## Current status

Last updated: 2026-08-21

- Overall phase: Phase 4 acceptance plus equipment/relationship extension in progress.
- Plugin worktree created and active.
- Host worktree created from the beta test-server branch.
- Architecture plan persisted in `docs/LLM_COMPANION_MVP_PLAN.md`.
- Host reactor tests and the complete plugin test suite pass.
- Dynamic persistent-companion roster and native load/save lifecycle foundation
  implemented in the plugin.
- Deterministic Explorer career paths, automatic AP/SP allocation, and native
  backpack/potion management are deployed.
- Automated live verification covers real shop purchases, HP/MP potion use,
  durable inventory/vitals, and persistent identity across repeated restarts.
- Player-visible acceptance now covers actor-isolated memory, normal chat,
  party invitation acceptance, cross-map following, autonomous joint training,
  HP potion use, and party EXP for both the companion and human player.
- The remaining live acceptance scenario is an away-from-town supply run that
  returns the companion to the player.
- Implementation commits are present on the feature branch.
- Companion inventory/equipment perception, deterministic gear growth,
  drop-backed upgrade goals, persona/relationship-gated owner-only gifts,
  relationship growth, and proactive conversation cooldowns are implemented.
- BeiDou exposes authoritative item pickup/gift-drop and reverse monster-drop
  capabilities without importing plugin code.
- Host and plugin test suites pass, and the updated artifacts are deployed to
  the beta runtime. Startup confirmed both new host capabilities are available.

## Worktrees

- Plugin:
  `/Users/zmzeng12/Code/fork/worktrees/solomapling-llm-companion`
  - Branch: `feat/llm-companion-mvp`
  - Base at creation: plugin `master` commit `0981dac`
- Host:
  `/Users/zmzeng12/Code/fork/worktrees/beidou-llm-companion`
  - Branch: `feat/llm-companion-mvp-host`
  - Base at creation: beta commit `cf12ada3f`

Do not implement in the original checkouts. They contain runtime/user state:

- Original plugin checkout has unrelated untracked `.vscode/`.
- Original beta checkout has a modified `application.yml` and untracked
  `log4j2-debug.xml`.

## Decisions already made

- Use the beta repository as the test/deployment host, while keeping it rebased
  on master.
- Persistent companions use real BeiDou account and character rows.
- Keep a two-tier population:
  - many ephemeral script/YAML ambient bots;
  - initially three persistent LLM-guided companions.
- Keep engine interaction in the plugin JVM. The LLM is a low-frequency
  high-level planner; deterministic FSMs execute actions.
- Use MySQL for profiles, memories, relationships, knowledge, and activity.
- Use bounded aggregate offline progression rather than frame-by-frame
  simulation.
- MVP excludes full boss tactics, autonomous market speculation, quest
  reasoning, and cash-shop purchasing.

## Repository facts

- Current plugin version is `0.4.0-SNAPSHOT`.
- The beta runtime previously deployed `0.3.1-SNAPSHOT`; source and deployed jar
  were out of sync.
- Existing LLM support is limited to SocialBot free-form chat and stores only an
  in-memory sliding window.
- Ambient bot creation clones the `fmbot` template and overwrites the ID with a
  value above 20000.
- Persistent loading must instead call
  `Character.loadCharFromDB(characterId, botClient, true)`.
- `Character.saveCharToDB` ignores characters whose `loggedIn` flag is false;
  loading with `channelServer=true` sets the flag.
- Artificial classification currently uses
  `id > 20000 || id == 999`; persistent IDs require a dynamic roster.
- Host event dispatch is synchronous. Event listeners may enqueue work but must
  never call the LLM or perform slow database work inline.

## Phase checklist

### Phase 0: baseline

- [x] Build and test host modules in the host worktree.
- [x] Build and test plugin 0.4.0 against the host artifacts.
- [x] Record baseline failures before changing behavior.

### Phase 1: persistence

- [x] Add plugin-owned Flyway migration for profile, relationship, memory,
      knowledge, and activity tables.
- [x] Add plugin persistence records and repositories.
- [x] Add dynamic `CompanionRoster`.
- [x] Add persistent character load/spawn/save/despawn.
- [x] Add controlled provisioning command.
- [x] Add persistence tests.

### Phase 2: cognition and action

- [x] Add stable persona model.
- [x] Add perception/knowledge restrictions.
- [x] Add memory ranking and decay.
- [x] Add memory persistence.
- [x] Add memory consolidation.
- [x] Generalize `SocialLlmService` behind `LlmClient`.
- [x] Add structured action schema and parser.
- [x] Add disclosure-bounded companion planner orchestration.
- [x] Add engine-independent action validation.
- [x] Add engine action executor.
- [x] Add `CompanionBot` and attention triggers.

### Phase 3: life simulation

- [x] Add routine scheduler.
- [x] Add online spawn/despawn coordinator.
- [x] Add bounded offline progression.
- [x] Add softly weighted encounter director.
- [x] Add deterministic career advancement and AP/SP allocation.

### Phase 4: verification

- [x] Add `!companion` diagnostics.
- [x] Run unit tests.
- [x] Run live MySQL and game integration tests.
- [x] Deploy the plugin jar to the beta runtime.
- [x] Verify persistent identity across two restarts.
- [x] Verify chat, party, follow, and joint training.
- [x] Verify LLM timeout and invalid-action safety.
- [x] Verify gear, inventory disclosure, gift authorization, relationship, and
      proactive-attention policies with automated tests.
- [x] Deploy the equipment/relationship extension and confirm host capabilities.
- [ ] Live-verify inventory Q&A, owner-only gift pickup, NPC equipment purchase,
      dropped-equipment pickup/equip, and proactive conversation.

## Next actions

1. Log in beside Luna and ask for her hats, then request a known backpack cap.
2. Give Luna enough mesos to preserve potion stock and buy a level-appropriate
   NPC shop upgrade; she currently has only 9 mesos.
3. Live-verify a monster equipment drop enters her backpack and is auto-equipped.
4. Raise/use a post-level-40 companion to verify the suggested item, monster or
   boss, and map against the live drop table.
5. Exercise the remaining away-from-town supply-return scenario and observe a
   relationship-driven proactive conversation after its cooldown.
6. Record final acceptance results without storing credentials.

## Acceptance issues found

- [Fixed locally; live retest pending] Potion-shop routing used a small city/map
  whitelist. Regional routes now also cover Korean Folk Town, Aquarium, Leafre,
  Mu Lung, Herb Town, Ariant, Magatia, Ellin Forest, Singapore, Malaysia, New
  Leaf City, and Japan.
- [Fixed locally; live retest pending] The companion combat ticker continued
  issuing movement while a supply or equipment run owned movement. Combat now
  yields to either controller.
- [Fixed locally; live retest pending] Companion combat intentionally does not
  consume visible MP. MP potions no longer trigger restocking, get purchased,
  or get consumed by the survival loop; supply demand uses HP stock and
  inventory pressure only.
- [Fixed locally; live retest pending] Supply and equipment movement previously
  prevented queued player turns from advancing. Player turns now advance before
  movement-owning controllers, and REST/GOODBYE cancel those activities.

## Verification log

Append dated entries here. Include the command, result, and any actionable
failure. Do not paste secrets or large logs.

- 2026-08-20: Worktrees and durable planning files created. Baseline builds not
  yet run.
- 2026-08-20: `mvn -pl extension-api,gms-server -am install -DskipTests`
  succeeded in the host worktree (66.7 seconds).
- 2026-08-20: `mvn test` succeeded in the plugin worktree. 22 tests passed,
  including three new `CompanionRosterTest` cases. Javac reported only the
  existing unchecked-operation warning in `InPacketReader`; tests also exposed
  the existing multiple-SLF4J-provider warning.
- 2026-08-20: Added host migration
  `V1.11.6__create_bot_companion_tables.sql`. Host compile/package and
  `git diff --check` pass. The migration has not yet been executed against a
  live MySQL instance.
- 2026-08-20: Added deterministic persona rendering and pure memory scoring,
  decay, matching, and stable top-K selection. Re-ran `mvn test` after aligning
  persona seeds with the schema's numeric seed; all 33 tests passed.
- 2026-08-20: Added schema-matched JDBC repositories for profiles, memories,
  relationships, and activity logs, including reversible tag encoding and
  atomic relationship interaction increments. All 51 tests passed; live MySQL
  integration remains pending.
- 2026-08-20: Added strict versioned action JSON parsing and target-specific,
  engine-independent authorization. The parser rejects unknown/duplicate
  fields, unsupported actions, bad IDs, oversized output, and excessive action
  counts. Added deterministic routine scheduling and allow-list-only encounter
  selection. Full suite reached 65 passing tests before the offline settlement
  follow-up.
- 2026-08-20: Fixed offline settlement to consume the complete observed interval
  while crediting only the capped duration, preventing repeated cap-sized claims
  against one long absence. The full 65-test suite passes.
- 2026-08-20: Added provider-neutral `LlmClient`, request/message models, and a
  DeepSeek adapter while retaining the SocialBot facade and YAML fallback.
  Fake-client success, empty, failure, and timeout coverage brings the suite to
  69 passing tests.
- 2026-08-20: Added typed companion planner orchestration with strict context
  allowlists for target relationships, actor-bound memories, and known maps.
  Player input is bounded and control-normalized. The suite reached 84 passing
  tests after disclosure-boundary regressions were added.
- 2026-08-20: Added the allowlist-only engine action executor. Follow uses
  `GCMovement` without replacing the companion state machine; training requires
  a companion-owned capability interface.
- 2026-08-20: Added schema-matched knowledge persistence and `PerceptionPolicy`.
  Planner authority now derives only from the current map, same-map characters,
  and enabled map knowledge owned by that companion. The suite reached 100
  passing tests.
- 2026-08-20: Added the first `CompanionBot`/turn-coordinator integration and
  Dispatcher routing. Review fixes prevent conversation state from blocking
  actions, restrict continuation to same-map humans, deduplicate reply/SAY, and
  bound queued input. Final compilation is temporarily blocked while the
  parallel host atomic-provisioning API is being added and installed.
- 2026-08-20: Added a host-neutral atomic character-provisioning capability and
  BeiDou implementation. Account, native character child rows, and
  `bot_profiles` share one JDBC transaction; the character cache is updated only
  after commit. Host compile/package/tests and 113 plugin tests pass. A real
  MySQL provisioning smoke test remains pending.
- 2026-08-20: Added deterministic memory consolidation with effective
  (non-persisted) decay, stable source-aware summary keys, and retry-safe
  summary-before-source-archive ordering. The full 117-test plugin suite passes.
- 2026-08-20: Completed opt-in companion lifecycle reconciliation, bounded
  offline checkpoints, persistent spawn/despawn, and shutdown saving. Failed
  attach cannot leave an online profile; reward checkpointing prefers a bounded
  duplicate-on-crash window over permanent loss. All 129 plugin tests pass.
- 2026-08-20: Expanded `!companion` with bounded
  `spawn/despawn/status/schedule/save/think/memories` operations. Lifecycle
  access is registered only while the coordinator is live; manual think remains
  same-map and asynchronous, and memory diagnostics truncate content.
- 2026-08-20: Local MySQL is reachable, but an unauthenticated smoke probe was
  correctly denied. No database mutation was attempted; migration/provisioning
  end-to-end verification still requires the test server's configured
  credentials.
- 2026-08-20: Integrated party invite/accept execution and companion-owned joint
  combat on the shared 250 ms grind ticker. Cross-map authority is restricted
  to accepting the exact pending inviter; follow and training remain same-map.
  Inactive companions perform no training, invite, planning, or combat work,
  and ticker startup is retry-safe after scheduler failure. Independent
  `mvn test` verification passed all 147 tests; `git diff --check` also passed.
- 2026-08-20: The live beta schema already contained unrelated migrations
  `1.11.6` and `1.11.7`, so the companion migration was renumbered to `1.11.8`
  before execution. Flyway applied `1.11.8` successfully and all five companion
  tables are present. Host and plugin packaging both pass. Runtime deployment
  still requires restarting the currently active beta server with the new host
  and plugin artifacts.
- 2026-08-20: Live provisioning created native account, character, and profile
  rows atomically (`luna`, cid 5), but a post-commit login-cache exception made
  the GM command falsely report failure. Cache publication is now best-effort
  after the durable commit so callers cannot be encouraged to create
  duplicates; failures are logged and repaired by restart. Companion command
  database/unexpected failures now include server-side stack traces. The new
  host regression tests and all 147 plugin tests pass, and both artifacts
  package successfully.
- 2026-08-21: Runtime combat validation found that headless artificial
  characters could become monster controllers, freezing mobs because they
  cannot emit `MOVE_LIFE`. Artificial characters are now excluded. Visible
  real players remain preferred, with a hidden real GM used only as fallback
  so a map containing a hidden tester and bots still has a movement-capable
  controller. The host's existing `TakeDamageHandler` intentionally skips HP
  deduction for hidden GMs, so player collision-damage validation must be run
  after toggling `!hide` off.
- 2026-08-21: Party EXP resolution now treats `Party.getMembers()` IDs as
  authoritative and resolves the live recipients from the current map,
  avoiding stale `PartyCharacter` object references after companion reload.
  The complete host test suite passes (6 tests), and the updated host artifact
  packages successfully. Runtime monster movement, companion contact damage,
  and party EXP still require validation after deployment.
- 2026-08-21: Moved the five `bot_*` table definitions out of the BeiDou host
  migration set and into SoloMapling's isolated
  `db/migration/solomapling` location. The plugin now runs Flyway before
  registering hooks or commands and records its own history in
  `flyway_solomapling_schema_history`. It baselines the already non-empty host
  database at plugin version `0`, then applies the idempotent plugin `V1`, so
  both existing and fresh Companion installations are adopted safely. BeiDou
  retains only generic native provisioning, transaction callback, cache, and
  artificial-character responsibilities.
- 2026-08-21: Added persona-seeded Explorer career paths with automatic
  advancement at levels 10/30/70/120. A GM-selected first or second job is
  preserved, later jobs stay on that branch, and online reconciliation
  checkpoints every career/AP/SP mutation. Offline EXP is granted in
  level-bounded chunks so advancement caps do not discard the remaining
  settlement. Unspent AP goes to the class primary stat; SP fills available
  skills along the current lineage without bypassing fourth-job mastery caps.
  The host tests pass (4 tests), all 162 plugin tests pass, and
  plugin packaging plus `git diff --check` pass.
- 2026-08-21: Built and deployed the career-enabled host/plugin stack and
  gracefully restarted the beta runtime. API and login ports are listening.
  Luna loaded as job 100 at level 19 and automatically spent 20 AP and 28 SP.
  Live kill diagnostics also confirmed party EXP increasing for both Luna and
  the human player. Character `ming` (cid 4, account `zmzeng13`) was explicitly
  confirmed and updated to GM level 4 for continued testing.
- 2026-08-21: Added deterministic survival and backpack management for
  persistent companions. Companions independently use HP/MP potions, restock
  below 12 to a target of 60, purchase from real NPC shop catalogs with real
  mesos, and sell bounded ordinary loot only under inventory pressure. Cash,
  quest, owned, logged, untradeable, rechargeable, and restorative items are
  protected from sale. Persistent companions now receive dedicated bound
  `BotClient` instances. BeiDou shop purchases deduct mesos only after item
  insertion succeeds. Companion skill MP remains cosmetic by product decision;
  players cannot observe a headless companion's MP.
- 2026-08-21: Live Luna supply test started and completed on map `101000002`.
  The real shop transaction bought 97 items and left 51 mesos. Two subsequent
  graceful restarts loaded the same persistent profile without another supply
  run, confirming that the purchased stock survived both restarts.
- 2026-08-21: Live low-vitals test set Luna to HP 50 / MP 1 while the server was
  offline. After startup, the survival loop consumed two item `2010001`
  potions (HP 50 -> 150 -> 250) and one item `2010003` potion (MP 1 -> 25).
  A graceful save persisted HP 250, MP 25, and remaining quantities 58 and 36;
  the following restart did not repeat potion use or restocking.
- 2026-08-21: Final static verification passed `git diff --check` in both
  worktrees. `mvn -pl gms-server -am test` passed all 4 host tests, and the
  complete plugin `mvn test` suite passed all 169 tests. The runtime is online
  with API port 8686 and login port 8484 listening. Remaining acceptance is
  intentionally limited to player-visible interaction and an away-from-town
  return-to-player supply scenario.
- 2026-08-21: Player-visible acceptance with `ming` confirmed actor-isolated
  memory, normal chat, party invitation acceptance, cross-map following,
  autonomous attacks, HP potion use, and party EXP for both Luna and the human
  player. A crowded-map defect kept CompanionBot contesting CAMP spots already
  used by TrainingBots; companion training now forces ROAM, and live retest
  confirmed immediate autonomous combat. Skill MP deduction was intentionally
  removed because companion MP is not player-visible.
- 2026-08-21: Replaced generic AP/SP filling with persisted
  `v083-classic-v1` career builds, ordinary-build AP formulas, and ordered SP
  milestones for all 12 Explorer branches. The clean plugin suite passed all
  182 tests and the host suite passed all 5 tests. Live pre-application
  diagnostics adopted Luna as `dark-knight-spear` at job 100/level 25, reported
  no AP delta and no blocked SP milestone, and remained unchanged after a
  graceful restart. The verification also found and corrected BeiDou's stale
  Warrior active-skill constants (`1001004`/`1001005`) against the v83 WZ.
- 2026-08-21: Added host-owned authoritative bot pickup, original-instance
  owner-only gift drops, and bounded reverse `drop_data` lookup. Added Companion
  inventory/equipment facts, a strict `DROP_GIFT` action, deterministic
  persona/relationship gift authorization, relationship increments, and
  anti-spam proactive conversations. Added level 0-40 NPC equipment shopping
  with potion-meso reserve, post-level-40 dynamic drop goals, weapon-first
  scoring, and native auto-equip. Complete host and plugin suites pass and
  `git diff --check` is clean in both worktrees.
- 2026-08-21: Packaged and deployed both artifacts and gracefully restarted the
  beta runtime. API/login ports returned online and plugin startup logged
  `itemActions=true monsterDrops=true`. Luna loaded at level 30, then correctly
  prioritized her existing potion supply run; with only 9 mesos she could not
  live-test equipment purchasing. Inventory dialogue, gift pickup, gear
  purchase/drop/equip, and proactive conversation still require a logged-in
  player/GM session.
- 2026-08-21: Live acceptance confirmed grounded equipped/backpack inventory
  answers. A low-HP-potion test started a supply run from map `100040103` to
  `100000102`, but active joint-training combat movement overrode the route and
  the run timed out. Testing also confirmed that the potion-shop route whitelist
  does not cover the `600000000` region. Because companion MP is intentionally
  cosmetic and is not consumed, MP potion stock and purchases are not useful
  supply criteria and must be removed from the supply loop.
- 2026-08-21: Implemented the supply/control fixes locally. Combat now pauses
  for supply and gear movement, player turns remain responsive during those
  runs, REST/GOODBYE cancel active movement owners, MP is excluded from potion
  use and restocking, and the regional potion-shop route table covers the
  additional verified towns including map `600000000`. `mvn test` passed all
  204 tests and `git diff --check` passed. Deployment and live retest remain.
- 2026-08-21: Replaced the companion's visible 20-second stuck teleport with
  target/route reselection. Position repair now requires at least 90 seconds
  without combat progress and an unobserved map, so an observed player never
  sees a companion teleport into combat. The full suite passed all 207 tests.
