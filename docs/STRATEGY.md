# Strategy — Liquidity Wall Bot

## The idea (in plain terms)

A **liquidity wall** is a price area holding an unusually large number of resting
contracts in the order book (e.g. ~47 contracts sitting at 4060). The common
play is to *wait at the wall and fade it*. This bot does the **opposite**: it
treats the wall as a **magnet / target** and trades **toward** it.

The danger with trading toward a wall is the **trap**: price often approaches the
wall by $5–6, then snaps $10–20 away, then comes back. Entering the moment price
nears the wall gets stopped out repeatedly. The fix is to **wait for the wave**
and enter at the *far* turning point, so the oscillation becomes the edge.

## Step by step

1. **Detect walls** (`WallDetector`)
   - Cluster adjacent heavy levels (within `wallClusterTicks`) into one wall.
   - A cluster is a wall only if its total size ≥ `wallMinSize`.
   - A wall is **confirmed** only after it persists ≥ `wallPersistenceMs`
     (filters spoofing / flicker).
   - A confirmed wall is **broken** when it is pulled, thinned below the
     threshold, or traded through. This is logged.

2. **Pick the target** (`LiquidityWallStrategy.ensureTarget`)
   - The **nearest** confirmed wall within `maxWallDistanceDollars` is the active
     target.
   - The bot keeps that target until it breaks; only then does it move to the
     **next-nearest** wall. This gives the requested behaviour for stacked walls
     (up to 5 on top of each other): work them **nearest → farthest, in order**,
     never jumping ahead and never entering randomly among them.
   - Trade direction (magnet mode): wall **above** → **LONG**, wall **below** →
     **SHORT**.

3. **Wait for the wave, then enter** (`WaveTracker`)
   - Track the closest approach toward the wall, and the deepest pull-back away
     from it.
   - Enter only when **all** hold:
     - pull-back amplitude ≥ `waveMinAmplitudeDollars` (a real wave, not a brush);
     - the turning point is `minEntryDistanceFromWallDollars … maxEntryDistanceFromWallDollars`
       from the wall (enter *from afar*, but only with edge left);
     - price has turned back toward the wall by `turnConfirmDollars`.
   - Entry is taken at that far turning point — so a single fake-out toward the
     wall never triggers a chase, and the `stopLossDollars` ($15) comfortably
     covers the remaining noise.

4. **Manage the trade** (`TradeManager`)
   - One position at a time.
   - Take-profit (`takeProfitDollars`, $4) and stop-loss (`stopLossDollars`, $15)
     are watched in-bot and closed with market orders — identical, deterministic
     behaviour in replay regardless of broker bracket support.
   - After a close: a cool-down (`cooldownMsAfterTrade`), then the target is
     re-evaluated.

5. **Record everything** (`BlackBox`)
   - Wall lifecycle, target changes, wave→entry decisions with the numbers,
     order/position updates, the close with its reason and P&L, and **where price
     travelled for `postTradeTrackingMs` (60s) after the trade** — so a losing
     trade can be diagnosed rather than guessed at.

## Why distances are in “dollars”, not ticks

All distances are configured in **price dollars** (chart price units) and
converted to ticks at runtime using the instrument tick size (`info.pips`). So
the same settings behave correctly on any instrument. For **Gold (GC)** with a
`0.10` tick and `$100`/point multiplier: `$4` ≈ 40 ticks ≈ $400/contract,
`$15` ≈ 150 ticks ≈ $1,500/contract.

## Data flow

```
Bookmap ──depth──▶ OrderBook ──┐
        ──bbo────▶            │ (throttled) ▶ WallDetector ▶ confirmed walls
        ──trade──▶ Stats(CVD) │                                   │
                              ▼                                   ▼
                          price tick ───────────────▶ ensureTarget ▶ WaveTracker
                                                                      │ entry signal
                                                                      ▼
                                                               TradeManager ▶ orders
                                                                      │
                                                              StatsIndicators + BlackBox
```

## Tuning notes for the replay week

- Start with **trading disabled** (shadow mode) and watch the black box +
  Winning/Losing indicators to judge entries before sending any orders.
- If the bot enters too eagerly into traps → raise `waveMinAmplitudeDollars` and
  `minEntryDistanceFromWallDollars`.
- If it rarely enters → lower `waveMinAmplitudeDollars` / widen the entry-distance
  band.
- If it chases walls that aren’t real → raise `wallMinSize` and
  `wallPersistenceMs`.
- Compare `magnetMode` on vs off on the same replay segment to validate the
  “opposite” thesis with numbers.
