# Host boundary

## Goal

BeiDou engine code must not `import soloMapling.*`. SoloMapling remains a provided-thin-jar plugin that registers capabilities the host already exposes.

## What the host owns

- `ArtificialCharacters` + `CharacterClassifier` (`extension-api`)
- `TradeParticipantHook` + `TradeParticipants` (`extension-api`)
- `HostHooks.isArtificial` / `HostHooks.trade*` / `HostHooks.publish`
- Gameplay events: `CharacterMapEnteredEvent`, `CharacterChatEvent`, `PartyInviteEvent`, `TradeInviteEvent`
- Simulation APIs: `BotClient`, `BotTier`, movement/combat helpers
- Generic atomic native account/character provisioning, including post-commit
  cache publication and a callback inside the host transaction

The host does not own or interpret Companion profiles, memories, relationships,
knowledge, activity records, or their schema.

## What the plugin owns

- Flyway migrations under `db/migration/solomapling`, tracked independently in
  `flyway_solomapling_schema_history`
- All `bot_*` tables and repositories
- Companion lifecycle, persistence, cognition, and actions
- The metadata callback that inserts `bot_profiles` while the host provisions a
  native account and character

## What the plugin does on load

1. Apply SoloMapling-owned schema migrations; abort loading on migration failure
2. Register dynamic artificial classification: persistent Companion IDs plus
   `id > 20000 || id == 999`
3. `TradeParticipants.register(SoloMaplingTradeParticipantHook)`
4. `HostGameplayEventBridge` — host map/chat events → internal `EventBus`
5. `PlayerChatBridge` / `BotPartyInviteBridge` / `BotTradeInviteBridge`
6. Register GM commands via `HostCommandRegistry`

## Trade (phase 3)

Host publishes `TradeInviteEvent` and consults `TradeParticipantHook`. Plugin owns `BotTradeQueue` + FSM. See [TRADE_DESIGN.md](TRADE_DESIGN.md).
