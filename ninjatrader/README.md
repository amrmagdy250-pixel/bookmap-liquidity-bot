# Liquidity Wall Bot — NinjaTrader 8 port

`LiquidityWallBot.cs` is a single-file NinjaScript strategy that ports the
Bookmap add-on (logic base: v1.2.4) to NinjaTrader 8.

## Install

1. NinjaTrader 8 → **New → NinjaScript Editor**.
2. Right-click the **Strategies** folder → **New Strategy**, name it
   `LiquidityWallBot`, then replace the entire generated file content with the
   content of `LiquidityWallBot.cs` and press **F5** (compile).
   - Alternatively copy the file into
     `Documents\NinjaTrader 8\bin\Custom\Strategies\` and compile.
3. Open a chart of the futures instrument (e.g. GC/MGC), right-click →
   **Strategies…** → add **LiquidityWallBot**.
4. Keep **Enable trading** unchecked at first (shadow mode: simulated fills +
   full JSONL logging, no orders sent).

## Notes

- Requires a data feed with Level-2 depth (the wall engine reads
  `OnMarketDepth`). The OB engine only needs the tape (`OnMarketData`).
- Works on live data and on **Market Replay**; it does not trade on
  historical bar backfill.
- Live TP/SL are real platform bracket orders (`SetProfitTarget` /
  `SetStopLoss`), entries count only after a real fill.
- Both engines share one net position: only one trade may be open at a time.
- Session guard: no new entries 20:30–22:00 UTC (23:30–01:00 Riyadh); memory
  is wiped at resume for the new session. Configurable in the parameters.
- BlackBox log: one JSONL file per UTC day, at
  `Documents\NinjaTrader 8\liquidity-wall-bot\logs\`. The date in the file
  name comes from the data's own timestamps, so in Playback the events are
  written to a file named with the replayed day's date, not today's date.
