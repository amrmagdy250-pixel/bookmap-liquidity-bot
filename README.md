# Liquidity Wall Bot — Bookmap add-on

A Bookmap trading add-on (Bookmap **Simplified API**, Java) that trades **toward**
liquidity walls instead of fading them.

Most strategies wait for price to reach a liquidity wall and trade the rejection.
This bot does the opposite: it treats a large, persistent wall as a price
**magnet / target** and enters *toward* it — but only after price makes a real
**wave** away from the wall, entering at the far turning point so the move back
toward the wall is the edge and a wide stop absorbs the noise.

> ⚠️ Always test in **simulation / replay** first. Start with trading disabled
> (shadow mode) — the bot logs every entry/exit it *would* take without sending
> orders.

---

## What Bookmap needs (and file types)

- Add-ons are written in **Java** against the Bookmap API and compiled to a
  **`.jar`** file (`build/libs/liquidity-wall-bot.jar`).
- Load it in Bookmap: **Settings → Configure API plugins → Add**, pick the jar,
  then enable **“Liquidity Wall Bot”** with the checkbox.
- The add-on reads the live order book (DOM/COB) and trades, and sends orders
  through the API. Bookmap **replay** supports simulated order execution, so it
  is ideal for the testing week.

## Project layout

```
bookmap-liquidity-bot/
├── build.gradle / settings.gradle / gradlew   # build (Gradle, Java 17)
├── src/main/java/com/liquiditybot/
│   ├── LiquidityWallStrategy.java   # add-on entry point + settings UI
│   ├── book/         OrderBook, LiquidityWall          # the order book + a wall
│   ├── detection/    WallDetector, WaveTracker         # find walls + anti-trap timing
│   ├── trade/        TradeManager, TradeRecord, TradeSide
│   ├── indicators/   StatsIndicators                   # CVD, win/loss, win-rate
│   ├── blackbox/     BlackBox                          # records everything
│   └── config/       Settings                          # all tunable parameters
├── config/strategy.reference.properties   # documented defaults (reference only)
├── docs/STRATEGY.md                        # full strategy write-up
└── build/libs/liquidity-wall-bot.jar       # ← the file you load into Bookmap
```

The compiled jar lives on its own under `build/libs/`; the Java sources live
under `src/main/java/...`, each concern in its own folder.

## Build

You do **not** need Gradle installed — use the bundled wrapper:

```bash
./gradlew jar          # Linux/Mac
gradlew.bat jar        # Windows
```

The jar appears at `build/libs/liquidity-wall-bot.jar`.

## Instrument: Gold (GC)

The bot reads tick size and contract multiplier from Bookmap at runtime, so
nothing is hard-coded. For **GC** (tick size `0.10`, `$100` per 1.00 point):

| Setting          | Price distance | Ticks | ≈ $ per contract |
|------------------|----------------|-------|------------------|
| Take profit `$4` | 4.00           | 40    | ~$400            |
| Stop loss `$15`  | 15.00          | 150   | ~$1,500          |

(“$4 / $15” are **price distances** on the chart, as confirmed — not P&L.)

## Settings (live-tunable in the add-on panel)

| Setting | Default | Meaning |
|---|---|---|
| Enable trading | **off** | Off = shadow logging only (no orders). |
| Magnet mode | on | Trade toward the wall (the “opposite” approach). |
| Take profit ($) | 4.0 | Target distance from entry. |
| Stop loss ($) | 15.0 | Stop distance from entry. |
| Wall min size | 40 | Min clustered contracts to count as a wall. |
| Wall cluster (ticks) | 3 | Merge adjacent heavy levels into one wall. |
| Wall persistence (ms) | 1500 | How long a wall must hold before trusted. |
| Max wall distance ($) | 30 | Ignore walls farther than this. |
| Wave min amplitude ($) | 8 | Required pull-back size before entering (**anti-trap**). |
| Min entry dist from wall ($) | 6 | Don’t enter closer than this to the wall. |
| Max entry dist from wall ($) | 25 | Don’t enter farther than this. |
| Turn confirm ($) | 1 | Price must turn back this much to trigger. |
| Order size | 1 | Contracts per trade. |
| Cooldown after trade (ms) | 5000 | Wait before the next trade. |

## On-chart output

- **Active wall target** (price chart) — the wall the bot is currently working.
- Bottom panel indicators: **CVD**, **Winning trades**, **Losing trades**,
  **Win rate %**, **Net P&L ($)**, **Position**.

## Black box

Everything is recorded to:

```
~/.liquidity-wall-bot/logs/blackbox-<alias>-<timestamp>.jsonl   (machine-readable)
~/.liquidity-wall-bot/logs/blackbox-<alias>-<timestamp>.log     (human-readable)
```

It captures wall lifecycle, wave/target transitions, every entry/exit **with the
reason**, order/position updates, and **where price went for 60s after each trade
closed** — so any bad trade can be diagnosed instead of guessed at.

See [`docs/STRATEGY.md`](docs/STRATEGY.md) for the full logic.
