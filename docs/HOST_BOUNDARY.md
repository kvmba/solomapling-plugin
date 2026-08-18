# Host boundary (phases 1–2)

## Goal

BeiDou engine code must not `import soloMapling.*`. SoloMapling remains a provided-thin-jar plugin that registers capabilities the host already exposes.

## What the host owns

- `ArtificialCharacters` + `CharacterClassifier` (`extension-api`)
- `HostHooks.isArtificial` / `HostHooks.publish`
- Gameplay events: `CharacterMapEnteredEvent`, `CharacterChatEvent`, `PartyInviteEvent`
- Simulation APIs: `BotClient`, `BotTier`, `PendingTradeInvites`, movement/combat helpers

## What the plugin does on load

1. `ArtificialCharacters.register(id -> id > 20000 || id == 999)`
2. `HostGameplayEventBridge` — host map/chat events → internal `EventBus`
3. `PlayerChatBridge` / `BotPartyInviteBridge` — existing bot input paths
4. Register GM commands via `HostCommandRegistry`

## Trade (still transitional)

Trade still uses `HostHooks.isArtificial` and `PendingTradeInvites` (host capability). See [TRADE_DESIGN.md](TRADE_DESIGN.md) for the phase-3 proposal.
