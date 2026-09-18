# Free Market pricing — rare-gear review

## The problem

Free Market shelves are priced from each item's **WZ `price` field**, scaled by a
job-style multiplier (`multiplyWzPriceByJobStyle`) or, for scrolled gear, by a
"minimum cost to create this stat roll" DP (`getEquipMarketValue`). WZ `price`
is a *shop resale* number, not a market value, so the two do not agree with what
the item is actually worth:

| item | WZ price | listed before | should be |
|---|---|---|---|
| 扎昆头盔 Zakum Helmet `1002357` | 500,000 | ~1 m | BOSS 独有, BIS |
| 褐工地手套 Brown Work Gloves `1082149` | 3,000 | ~6 k | BIS 手套 |
| 枫叶爪 Maple Claw `1472030` | 40,000 | ~90 k | 稀有活动武器 |
| ordinary Lv70 weapon | 300,000 | ~0.6–4 m | — |

The most sought-after gear listed for **less than junk**. Two independent causes:

1. **No rare/BIS price** — the curated whitelist (`desirableEquips.yaml`) only
   let those items *reach a shelf* (bypass the level-band / price-floor filters);
   it never changed their price. `processItemIdToFMEquip` even carried a
   commented-out `todo … overwrite the price for BIS rare price`.
2. **WZ sentinel cliff** — items whose WZ price was `0..50` (event rewards, boss
   drops, half-finished entries) were all snapped to a flat `5,000,000`, while
   `price = 51` stayed `51`. A 100,000x discontinuity, unrelated to real value.

## What changed

1. **`rareItemPrices.yaml`** — a curated id → 国服 price table (16 entries).
   Loaded by `DesirableEquipList`, exposed via `getRarePrice`.
2. **`EquipListGenerator.applyRarePriceOverride(itemId, price)`** — applied on
   both pricing paths. It is a **floor**, so a genuinely higher scrolled
   valuation is preserved.
3. **`desirableEquips.yaml`** — every priced item is also whitelisted, so it can
   actually reach a shelf (the override is otherwise unreachable).
4. **`getWzPrice`** — the flat `5,000,000` sentinel is replaced by a
   level-scaled estimate `50,000 + reqLevel² × 300` (≈1.7 m at Lv70, ≈4.4 m at
   Lv120). Monotonic with level, no cliff.

## Grounding

Real-time 国服 market prices could not be fetched (search engines returned only
official portals; the price databases are paywalled/JS-gated). Values are instead
anchored to (a) the host's own **GMS083 WZ data**
(`/workspace/GMS083/gms-server/wz`) for item identity, level and stats, and
(b) established v83 economy conventions: boss-unique / top-BIS gear commands
tens to hundreds of times ordinary shop gear; common Lv70 gear sits near 0.5–1 m.
The table is a plain YAML meant to be tuned.

## Tuning

Edit `src/main/java/soloMapling/itemPool/itemConfig/rareItemPrices.yaml`
(`<id>: <mesos>`) and `desirableEquips.yaml` together. No code change, no reload
hook — read once at startup (like the other itemConfig files).

## Tests

`RareEquipPricingTest` (5 cases): overrides load, cheap price is lifted, the
override is a floor not a ceiling, unlisted items are untouched, rare outvalues
ordinary. Full suite: 1073 tests, 0 failures.
