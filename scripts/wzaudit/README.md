# WZ movement audits

Run with Java 21, Maven, the host dependencies installed, and the host `wz` tree available at the repository root. The audits use the production geometry loader and physics without a live game server. With no map IDs they enumerate all installed numeric `Map.wz/Map/Map*` maps, including linked maps. A map-load error or a reproduced failure makes the command exit nonzero.

```sh
./scripts/wzaudit/jump_waypoint_scan.sh
./scripts/wzaudit/movement_safety_scan.sh
./scripts/wzaudit/movement_safety_scan.sh 103040200 922010100 922010300 922010800
```

- `jump_waypoint_scan.sh`: scans standing columns on non-dead multi-foothold platforms with jump edges and samples their launch windows. A physically possible direct approach is replayed if the waypoint points away from it. Reports oscillation or stalls, not a successful foldback. Uses base speed and jump stats.
- `movement_safety_scan.sh`: replays precise same-platform stops, usable jump anchors and directional drops from their runways, tests straight down-jumps and dry-map boundary landings, checks platform lips and recovery hops at base and Haste stats, standard outward hit recoil and a second midair impulse, wall-face collisions and valid floors below the camera bounds, and verifies the rescue destination for floors the engine classifies as dead. Neither audit uses a watchdog to hide an approach failure.

The map totals include rooms where a scenario does not apply (e.g. water, no footholds or no jump/drop edges). Reported counts describe the tested scenarios, not every possible route, avatar loadout, or hit timing. The Maven regression suite separately drives the live movement tick through observation, dwell, and resumption on representative WZ maps. Graph caches live in `target/jump-waypoint-audit`; clear that directory when switching WZ datasets.

Historical regression coverage:

| Bug family | Relevant commits | Audit |
| --- | --- | --- |
| Sway on slopes, launch seams or precise stops | `5b7a197`, `2000cca`, `ef07528`, `47da507` | Jump waypoint replay and same-platform stops |
| Invalid walk-off edges or void falls | `5ea0767`, `9782ff0`, `1496a6f`, `9764321` | Compare the actual flight/landing with the planned edge; probe unplanned lip exits |
| Dead-pit entry and recovery | `8545214`, `755ea31`, `afd475d`, `d0ad7e2` | Drop gates, crouch drops, rescue hops, hit recoil and rescue landings |
| Wall- and map-edge tunneling | `7da1d72`, `7ead283`, `235998d`, `5dd1730` | Airborne boundary clamps, wall-face departures and real-map collision regression tests |
