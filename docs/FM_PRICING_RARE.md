# Free Market pricing — rare-gear review

## The problem

Free Market shelves are priced from each item's **WZ `price` field**, scaled by a
job-style multiplier (`multiplyWzPriceByJobStyle`) or, for scrolled gear, by a
"minimum cost to create this stat roll" DP (`getEquipMarketValue`). WZ `price`
is a *shop resale* number, not a market value, so the two disagree with what an
item is actually worth:

| item | WZ price | listed before | should be |
|---|---|---|---|
| 扎昆头盔 Zakum Helmet `1002357` | 500,000 | ~1 m | BOSS 独有, BIS |
| 褐工地手套 Brown Work Gloves `1082149` | 3,000 | ~6 k | BIS 手套 |
| 枫叶爪 Maple Claw `1472030` | 40,000 | ~90 k | 稀有活动武器 |
| ordinary Lv70 weapon | 300,000 | ~0.6–4 m | — |

The most sought-after gear listed for **less than junk**. Three independent causes:

1. **No rare/BIS price** — the curated whitelist (`desirableEquips.yaml`) only
   let those items *reach a shelf*; it never changed their price.
   `processItemIdToFMEquip` even carried a commented-out
   `todo … overwrite the price for BIS rare price`.
2. **WZ sentinel cliff** — items whose WZ price was `0..50` were all snapped to a
   flat `5,000,000`, while `price = 51` stayed `51`.
3. **Narrow coverage** — even after (1)+(2), only the handful of top items were
   touched; the whole second tier of famous Maple / Elemental gear still listed
   at junk prices (a Lv130 Elemental Staff at ~243k).

## What changed

1. **`rareItemPrices.yaml`** — a curated id → 国服 price table (59 entries),
   loaded by `DesirableEquipList`, exposed via `getRarePrice`.
2. **`EquipListGenerator.applyRarePriceOverride(itemId, price)`** — applied on
   both pricing paths, as a **floor** (a higher scrolled valuation is kept).
3. **`desirableEquips.yaml`** — whitelists every priced item that would not
   otherwise reach a shelf, so the override actually bites.
4. **`getWzPrice`** — the flat `5,000,000` sentinel becomes a level-scaled
   estimate `50,000 + reqLevel² × 100` (≈90k at Lv20, ≈1.5m at Lv120): monotonic
   with level, no cliff, and in the same ballpark as priced gear of that level.

## Correctness invariants (verified against the host's own GMS083 WZ data)

Every id in the price table satisfies all of:

- **Tradeable** — `tradeBlock != 1`, not quest/cash. Untradeable gear is filtered
  out of shops *before* the whitelist check, so an override on e.g. the
  untradeable Zakum Helmet `1002357` / Horntail Necklace `1122000` would be dead
  weight. Those are deliberately **not** listed.
- **Reachable** — either in a class YAML pool, in the whitelist, or picked
  naturally by the IIPU level/price bands.
- **Slot is generated** — mace / knuckle / pistol are never drawn by any shop
  job, so those Maple weapons are omitted rather than left as inert entries.

A script over `/workspace/GMS083/gms-server/wz` enforces these; the table has
**0 unreachable entries**.

## Grounding

Real-time 国服 market prices could not be fetched (search engines return only
official portals; the price databases are paywalled / JS-gated). Values are
anchored to (a) the host's own GMS083 WZ data for item identity, level and
stats, and (b) established v83 economy conventions: boss-unique / top-BIS gear
commands tens to hundreds of times ordinary shop gear; common Lv70 gear sits
near 0.5–1m. The table is a plain YAML meant to be tuned.

## Tuning

Edit `rareItemPrices.yaml` (`<id>: <mesos>`) and `desirableEquips.yaml` together.
No code change, no reload hook — read once at startup.

## Tests

`RareEquipPricingTest` (6 cases): overrides load, untradeable ids are absent,
cheap price is lifted, the override is a floor not a ceiling, unlisted items are
untouched, rare outvalues ordinary. Full suite: 1074 tests, 0 failures.
