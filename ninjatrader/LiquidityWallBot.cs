#region Using declarations
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.ComponentModel.DataAnnotations;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using NinjaTrader.Cbi;
using NinjaTrader.Data;
using NinjaTrader.NinjaScript;
#endregion

// Liquidity Wall Bot - NinjaTrader 8 port of the Bookmap add-on (logic base: v1.2.4).
//
// Two independent engines share ONE unified position (net position safety):
//   1. Liquidity-wall magnet strategy: raw L2 depth -> dominant walls -> wave
//      pull-back entry toward the wall, with spoof / re-entry / revenge logic.
//   2. Raw-execution order-block strategy: tape volume/delta accumulation ->
//      displacement confirmation -> revisit entry, with flexible re-confirmation
//      memory for violated levels (no permanent blacklist).
//
// NinjaTrader-specific hardening (vs. the Bookmap version):
//   - Aggressor delta computed locally from trade price vs. current bid/ask
//     (no dependency on an ambiguous isBidAggressor flag).
//   - Live TP/SL are REAL platform bracket orders (SetProfitTarget/SetStopLoss),
//     not tick-watching + market orders.
//   - Entries are only considered open after a REAL fill (OnExecutionUpdate);
//     P&L is logged from actual execution prices.
//   - Only one engine may hold a trade at a time (single net position per
//     instrument), so the two engines can never fight each other.
//
// Session guard: no new entries from SessionPauseUtc (default 20:30 UTC =
// 23:30 Riyadh); at SessionResumeUtc (default 22:00 UTC = 01:00 Riyadh) all
// engine memory is wiped for the new session. Open trades keep their TP/SL.
//
// BlackBox log: ONE JSONL file per UTC day (append across restarts), stored in
// <Documents>\NinjaTrader 8\liquidity-wall-bot\logs\.
//
// Shadow mode: with EnableTrading=false the bot simulates fills/exits/P&L
// internally and sends no orders - identical evaluation workflow to Bookmap.

namespace NinjaTrader.NinjaScript.Strategies
{
    public class LiquidityWallBot : Strategy
    {
        // =====================================================================
        // Shared primitives
        // =====================================================================

        private enum BotSide { Long = 1, Short = -1 }

        private static int SignOf(BotSide s) { return s == BotSide.Long ? 1 : -1; }
        private static BotSide Opposite(BotSide s) { return s == BotSide.Long ? BotSide.Short : BotSide.Long; }

        private static double Round2(double v) { return Math.Round(v * 100.0) / 100.0; }

        // =====================================================================
        // BlackBox: one JSONL file per UTC day, append mode
        // =====================================================================

        private sealed class BlackBox
        {
            private readonly string dir;
            private readonly string safeAlias;
            private StreamWriter writer;
            private string openDay;
            private bool opened;
            private readonly List<string> pending = new List<string>();

            public BlackBox(string alias)
            {
                safeAlias = System.Text.RegularExpressions.Regex.Replace(
                    alias ?? "unknown", "[^a-zA-Z0-9._-]", "_");
                dir = Path.Combine(Core.Globals.UserDataDir, "liquidity-wall-bot", "logs");
            }

            private static string DayKey(DateTime utc)
            {
                return utc.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
            }

            private void OpenForDay(string day)
            {
                try
                {
                    Directory.CreateDirectory(dir);
                    string path = Path.Combine(dir, "blackbox-" + safeAlias + "-" + day + ".jsonl");
                    writer = new StreamWriter(path, true, Encoding.UTF8) { AutoFlush = true };
                    openDay = day;
                }
                catch (Exception)
                {
                    writer = null;
                    openDay = day;
                }
            }

            // Startup/shutdown records (INIT, SETTINGS, STOP) carry a wall-clock
            // timestamp. They are buffered until the first data-driven record so
            // that in Playback they land in the replayed day's file instead of
            // creating an extra file named with today's date.
            public void LogStartup(long nowMs, string type, params object[] kv)
            {
                string line = Serialize(nowMs, type, kv);
                lock (this)
                {
                    if (opened && writer != null)
                    {
                        try { writer.WriteLine(line); } catch (Exception) { }
                    }
                    else
                    {
                        pending.Add(line);
                    }
                }
            }

            public void Log(long nowMs, string type, params object[] kv)
            {
                string day = DayKey(DateTimeOffset.FromUnixTimeMilliseconds(nowMs).UtcDateTime);
                string line = Serialize(nowMs, type, kv);
                lock (this)
                {
                    if (!opened || day != openDay)
                    {
                        if (opened) { CloseWriter(); }
                        OpenForDay(day);
                        opened = true;
                        FlushPending();
                    }
                    if (writer == null)
                    {
                        return;
                    }
                    try { writer.WriteLine(line); } catch (Exception) { }
                }
            }

            private void FlushPending()
            {
                if (writer == null) { pending.Clear(); return; }
                foreach (string p in pending)
                {
                    try { writer.WriteLine(p); } catch (Exception) { }
                }
                pending.Clear();
            }

            private static string Serialize(long nowMs, string type, object[] kv)
            {
                var sb = new StringBuilder(160);
                sb.Append("{\"dataTime\":").Append(nowMs)
                  .Append(",\"type\":\"").Append(type).Append('"');
                for (int i = 0; i + 1 < kv.Length; i += 2)
                {
                    sb.Append(",\"").Append(kv[i]).Append("\":");
                    AppendValue(sb, kv[i + 1]);
                }
                sb.Append('}');
                return sb.ToString();
            }

            private static void AppendValue(StringBuilder sb, object v)
            {
                if (v == null) { sb.Append("null"); return; }
                if (v is bool) { sb.Append(((bool)v) ? "true" : "false"); return; }
                if (v is double)
                {
                    double d = (double)v;
                    if (double.IsNaN(d) || double.IsInfinity(d)) { sb.Append("null"); return; }
                    sb.Append(d.ToString("0.####", CultureInfo.InvariantCulture));
                    return;
                }
                if (v is int || v is long)
                {
                    sb.Append(Convert.ToInt64(v).ToString(CultureInfo.InvariantCulture));
                    return;
                }
                sb.Append('"').Append(v.ToString().Replace("\"", "'")).Append('"');
            }

            public void Close()
            {
                lock (this)
                {
                    if (!opened && pending.Count > 0)
                    {
                        // No data ever arrived (e.g. strategy enabled without a
                        // feed): keep the startup records in a wall-clock file.
                        OpenForDay(DayKey(DateTime.UtcNow));
                        opened = true;
                        FlushPending();
                    }
                    CloseWriter();
                }
            }

            private void CloseWriter()
            {
                try { if (writer != null) { writer.Flush(); writer.Dispose(); } }
                catch (Exception) { }
                writer = null;
            }
        }

        // =====================================================================
        // Order book (from OnMarketDepth)
        // =====================================================================

        private sealed class BookSide
        {
            public readonly SortedDictionary<int, long> Levels = new SortedDictionary<int, long>();

            public void Set(int level, long size)
            {
                if (size <= 0) { Levels.Remove(level); }
                else { Levels[level] = size; }
            }

            public void Clear() { Levels.Clear(); }
        }

        private sealed class WallInfo
        {
            public readonly long Id;
            public readonly bool IsAsk;          // true = resistance side (asks)
            public int Level;
            public double Price;
            public long Size;
            public long FirstSeenMs;
            public long LastSeenMs;
            public bool Confirmed;

            public WallInfo(long id, bool isAsk, int level, double price, long size, long now)
            {
                Id = id; IsAsk = isAsk; Level = level; Price = price; Size = size;
                FirstSeenMs = now; LastSeenMs = now;
            }

            public void Update(int level, double price, long size, long now)
            {
                Level = level; Price = price; Size = size; LastSeenMs = now;
            }

            public long AgeMs(long now) { return now - FirstSeenMs; }
        }

        // =====================================================================
        // Swing (pivot) memory - zig-zag pivots for the smart peak filter
        // =====================================================================

        private sealed class SwingMemory
        {
            private struct Pivot { public long Time; public double Price; public bool High; }

            private readonly LiquidityWallBot o;
            private readonly Queue<Pivot> pivots = new Queue<Pivot>();
            private bool started;
            private int dir;
            private double legExtreme;

            public SwingMemory(LiquidityWallBot owner) { o = owner; }

            public void Reset()
            {
                pivots.Clear();
                started = false;
                dir = 0;
                legExtreme = 0;
            }

            public void OnPrice(double price, long nowMs)
            {
                if (!started) { started = true; legExtreme = price; dir = 0; return; }
                double rev = Math.Max(0.01, o.PivotReversalDollars);

                if (dir >= 0 && price > legExtreme) { legExtreme = price; }
                else if (dir <= 0 && price < legExtreme) { legExtreme = price; }

                if (dir >= 0 && legExtreme - price >= rev)
                {
                    if (dir > 0) { pivots.Enqueue(new Pivot { Time = nowMs, Price = legExtreme, High = true }); }
                    dir = -1; legExtreme = price;
                }
                else if (dir <= 0 && price - legExtreme >= rev)
                {
                    if (dir < 0) { pivots.Enqueue(new Pivot { Time = nowMs, Price = legExtreme, High = false }); }
                    dir = 1; legExtreme = price;
                }
                else if (dir == 0)
                {
                    dir = price >= legExtreme ? 1 : -1;
                }
                Prune(nowMs);
            }

            public double RecentHigh(long nowMs)
            {
                Prune(nowMs);
                double max = double.NaN;
                foreach (var p in pivots)
                {
                    if (p.High && (double.IsNaN(max) || p.Price > max)) { max = p.Price; }
                }
                return max;
            }

            public double RecentLow(long nowMs)
            {
                Prune(nowMs);
                double min = double.NaN;
                foreach (var p in pivots)
                {
                    if (!p.High && (double.IsNaN(min) || p.Price < min)) { min = p.Price; }
                }
                return min;
            }

            private void Prune(long nowMs)
            {
                while (pivots.Count > 0 && nowMs - pivots.Peek().Time > o.PeakLookbackMs)
                {
                    pivots.Dequeue();
                }
            }
        }

        // =====================================================================
        // Wave tracker - WHEN to enter toward the active wall
        // =====================================================================

        private sealed class WaveSignal
        {
            public BotSide Side;
            public double EntryPrice;
            public double WallPrice;
            public double WaveAmplitude;
            public double DistanceFromWall;
            public double PeakPrice;
            public double RecentExtreme;
        }

        private sealed class WaveTracker
        {
            private readonly LiquidityWallBot o;
            private readonly SwingMemory swings;

            private bool active;
            private BotSide side;
            private int sign;
            private double wallX;
            private double swingMaxX;
            private double troughX;
            private bool fired;

            public string LastSkipReason;
            public double LastSkipPeak = double.NaN;
            public double LastSkipExtreme = double.NaN;

            public WaveTracker(LiquidityWallBot owner, SwingMemory swings)
            {
                o = owner;
                this.swings = swings;
            }

            public bool IsArmed { get { return active; } }

            public void Arm(BotSide side, double wallPrice, double currentPrice)
            {
                double newWallX = SignOf(side) * wallPrice;
                if (active && this.side == side
                        && Math.Abs(wallX - newWallX) <= o.WaveResumeToleranceDollars)
                {
                    wallX = newWallX;
                    return;
                }
                active = true;
                this.side = side;
                sign = SignOf(side);
                wallX = newWallX;
                double x = sign * currentPrice;
                swingMaxX = x;
                troughX = x;
                fired = false;
            }

            public void Disarm() { active = false; }

            public WaveSignal OnPrice(double price, long nowMs)
            {
                if (!active) { return null; }
                double x = sign * price;

                if (x > swingMaxX)
                {
                    swingMaxX = x;
                    troughX = x;
                    fired = false;
                    return null;
                }
                if (x < troughX)
                {
                    troughX = x;
                    fired = false;
                }
                if (fired) { return null; }

                double amplitude = swingMaxX - troughX;
                double distanceFromWall = wallX - troughX;
                bool turned = x >= troughX + Math.Max(0.0, o.TurnConfirmDollars);

                bool amplitudeOk = amplitude >= o.WaveMinAmplitudeDollars;
                bool distanceOk = distanceFromWall >= o.MinEntryDistanceFromWallDollars
                        && distanceFromWall <= o.MaxEntryDistanceFromWallDollars;

                if (!(amplitudeOk && distanceOk && turned)) { return null; }

                double peakPrice = sign * troughX;
                double recentExtreme = side == BotSide.Short
                        ? swings.RecentHigh(nowMs)
                        : swings.RecentLow(nowMs);

                if (o.PeakFilterEnabled)
                {
                    if (double.IsNaN(recentExtreme))
                    {
                        if (!o.PeakNoHistoryEntryEnabled)
                        {
                            fired = true;
                            LastSkipReason = "NO_PEAK_HISTORY";
                            LastSkipPeak = peakPrice;
                            LastSkipExtreme = double.NaN;
                            return null;
                        }
                        bool strongTurn = x >= troughX + Math.Max(0.0,
                                o.TurnConfirmDollars + o.PeakNoHistoryExtraConfirmDollars);
                        if (!strongTurn) { return null; }
                    }
                    else
                    {
                        double breakout = sign * (recentExtreme - peakPrice);
                        if (breakout > o.PeakBreakoutToleranceDollars)
                        {
                            fired = true;
                            LastSkipReason = "BREAKOUT_PEAK";
                            LastSkipPeak = peakPrice;
                            LastSkipExtreme = recentExtreme;
                            return null;
                        }
                    }
                }

                fired = true;
                LastSkipReason = null;
                return new WaveSignal
                {
                    Side = side,
                    EntryPrice = price,
                    WallPrice = sign * wallX,
                    WaveAmplitude = amplitude,
                    DistanceFromWall = distanceFromWall,
                    PeakPrice = peakPrice,
                    RecentExtreme = recentExtreme
                };
            }
        }

        // =====================================================================
        // Revenge engine - one recovery entry after a stop-out
        // =====================================================================

        private sealed class RevengeEngine
        {
            private readonly LiquidityWallBot o;

            private bool active;
            private BotSide side;
            private int sign;
            private double entryX;
            private double stopX;
            private double adverseMinX;
            private long armedAt;
            private int attemptsUsed;

            private bool bounced;
            private double bounceHighX;
            private double pullbackLowX;
            private bool pullingBack;

            public double LastEntryPrice = double.NaN;
            public double LastAdverseExtreme = double.NaN;
            private bool expiredPending;
            private string cancelPending;

            public RevengeEngine(LiquidityWallBot owner) { o = owner; }

            public bool IsArmed { get { return active; } }
            public BotSide Side { get { return side; } }

            public void Arm(BotSide side, double entryPrice, double stopPrice, long nowMs)
            {
                if (!o.RevengeEnabled || o.RevengeMaxAttempts - attemptsUsed <= 0)
                {
                    active = false;
                    return;
                }
                active = true;
                this.side = side;
                sign = SignOf(side);
                entryX = sign * entryPrice;
                stopX = sign * stopPrice;
                adverseMinX = sign * stopPrice;
                armedAt = nowMs;
                bounced = false;
                pullingBack = false;
            }

            public void Reset() { active = false; attemptsUsed = 0; }
            public void Cancel() { active = false; }

            public bool PollExpired()
            {
                bool e = expiredPending;
                expiredPending = false;
                return e;
            }

            public string PollCancelled()
            {
                string c = cancelPending;
                cancelPending = null;
                return c;
            }

            public bool OnPrice(double price, long nowMs)
            {
                if (!active) { return false; }
                if (nowMs - armedAt > o.RevengeWindowMs)
                {
                    active = false;
                    expiredPending = true;
                    return false;
                }

                double x = sign * price;
                if (x < adverseMinX)
                {
                    adverseMinX = x;
                    bounced = false;
                    pullingBack = false;
                    if (stopX - adverseMinX > o.RevengeMaxBeyondStopDollars)
                    {
                        active = false;
                        cancelPending = "LEFT_ZONE";
                    }
                    return false;
                }

                double excursion = entryX - adverseMinX;
                double correction = x - adverseMinX;
                if (excursion < o.RevengeMinExcursionDollars) { return false; }

                if (!bounced)
                {
                    if (correction < o.RevengeReversalDollars) { return false; }
                    bounced = true;
                    bounceHighX = x;
                    pullingBack = false;
                    return false;
                }

                if (!pullingBack)
                {
                    if (x > bounceHighX) { bounceHighX = x; }
                    else if (bounceHighX - x >= o.RevengeConfirmDollars)
                    {
                        pullingBack = true;
                        pullbackLowX = x;
                    }
                    return false;
                }

                if (x < pullbackLowX) { pullbackLowX = x; return false; }
                if (x - pullbackLowX < o.RevengeConfirmDollars) { return false; }

                LastEntryPrice = price;
                LastAdverseExtreme = sign * adverseMinX;
                attemptsUsed++;
                active = false;
                return true;
            }
        }

        // =====================================================================
        // Order-block engine (raw tape) - v1.2.4 logic
        // =====================================================================

        private sealed class ObBlock
        {
            public long Id;
            public double Low;
            public double High;
            public double Volume;
            public double Delta;
            public long FormedAt;
            public BotSide Side;
            public bool Confirmed;
            public bool LeftZone;
            public bool Reconfirm;
            public int TradesTaken;
            public long LastFastSkipMs;
            public long LastDeferLogMs;
            public bool InZone;
            public long TouchMs;
            public double WorstExtreme;
            public long LastCounterMs;

            public double Center() { return (Low + High) / 2.0; }
        }

        private sealed class ObSignal
        {
            public ObBlock Block;
            public BotSide Side;
            public double Price;
            public string Mode;
            public long Time;
            public double BigPrintVolume;
            public double BigPrintDelta;
        }

        private struct ObExec
        {
            public double Price;
            public long Size;
            public int DeltaSign;
            public long Time;
        }

        private sealed class OrderBlockEngine
        {
            private readonly LiquidityWallBot o;

            private readonly Queue<ObExec> recentExecs = new Queue<ObExec>();
            private readonly List<ObBlock> blocks = new List<ObBlock>();
            private long blockSeq;
            private long lastZoneScanMs;

            private long lastRejectLevel = long.MinValue;
            private long lastRejectMs;

            private readonly Queue<KeyValuePair<long, double>> pricePath =
                    new Queue<KeyValuePair<long, double>>(); // time -> price

            private readonly List<double[]> penalizedAreas = new List<double[]>(); // {low, high, expiry}

            // OB Big Print (Order Flow Trade Detector equivalent): cluster of
            // executions with a large total volume and dominant one-sided delta.
            private readonly Queue<ObExec> bigPrintWindow = new Queue<ObExec>();
            private long lastBigPrintMs;
            private ObSignal pendingBigPrint;

            public OrderBlockEngine(LiquidityWallBot owner) { o = owner; }

            public List<ObBlock> ActiveBlocks { get { return blocks; } }

            public void Reset()
            {
                recentExecs.Clear();
                blocks.Clear();
                pricePath.Clear();
                penalizedAreas.Clear();
                bigPrintWindow.Clear();
                pendingBigPrint = null;
                lastBigPrintMs = 0;
                lastRejectLevel = long.MinValue;
            }

            public void OnExecution(double price, long size, bool buyAggressor, long nowMs)
            {
                if (!o.ObEnabled) { return; }
                recentExecs.Enqueue(new ObExec
                {
                    Price = price,
                    Size = size,
                    DeltaSign = buyAggressor ? 1 : -1,
                    Time = nowMs
                });
                long horizon = nowMs - o.ObZoneWindowMs;
                while (recentExecs.Count > 0 && recentExecs.Peek().Time < horizon)
                {
                    recentExecs.Dequeue();
                }
                if (nowMs - lastZoneScanMs >= o.ObScanThrottleMs)
                {
                    lastZoneScanMs = nowMs;
                    ScanForZone(nowMs);
                }
                TrackBigPrint(price, size, buyAggressor, nowMs);
            }

            public ObSignal OnPrice(double price, long nowMs)
            {
                if (!o.ObEnabled) { return null; }
                RecordPricePath(price, nowMs);
                ObSignal signal = null;
                for (int i = blocks.Count - 1; i >= 0; i--)
                {
                    ObBlock b = blocks[i];

                    if (nowMs - b.FormedAt > o.ObMaxAgeMs)
                    {
                        o.LogBlockDead(b, nowMs, "EXPIRED");
                        blocks.RemoveAt(i);
                        continue;
                    }

                    if (!b.Confirmed)
                    {
                        if (nowMs - b.FormedAt > o.ObDisplacementWindowMs)
                        {
                            o.LogBlockDead(b, nowMs, "NO_DISPLACEMENT");
                            blocks.RemoveAt(i);
                            continue;
                        }
                        double disp = b.Reconfirm
                                ? o.ObDisplacementDollars * o.ObReconfirmDisplacementMult
                                : o.ObDisplacementDollars;
                        bool up = price >= b.High + disp;
                        bool down = price <= b.Low - disp;
                        if (up && b.Delta > 0)
                        {
                            b.Side = BotSide.Long;
                            b.Confirmed = true;
                            b.LeftZone = true;
                            o.LogBlockConfirmed(b, nowMs);
                        }
                        else if (down && b.Delta < 0)
                        {
                            b.Side = BotSide.Short;
                            b.Confirmed = true;
                            b.LeftZone = true;
                            o.LogBlockConfirmed(b, nowMs);
                        }
                        else if (up || down)
                        {
                            o.LogBlockDead(b, nowMs, "DELTA_MISMATCH");
                            blocks.RemoveAt(i);
                        }
                        continue;
                    }

                    bool violated = b.Side == BotSide.Long
                            ? price < b.Low - o.ObInvalidationDollars
                            : price > b.High + o.ObInvalidationDollars;
                    if (violated)
                    {
                        Penalize(b, nowMs);
                        o.LogBlockDead(b, nowMs, "VIOLATED");
                        blocks.RemoveAt(i);
                        continue;
                    }

                    if (b.TradesTaken >= o.ObMaxTradesPerBlock) { continue; }

                    bool inside = price >= b.Low - o.ObEntryToleranceDollars
                            && price <= b.High + o.ObEntryToleranceDollars;
                    if (b.LeftZone && signal == null)
                    {
                        if (inside)
                        {
                            if (!b.InZone)
                            {
                                b.InZone = true;
                                b.TouchMs = nowMs;
                                b.WorstExtreme = price;
                            }
                            else if (b.Side == BotSide.Long
                                    ? price < b.WorstExtreme
                                    : price > b.WorstExtreme)
                            {
                                b.WorstExtreme = price;
                            }
                            if (Guarded(b, price, nowMs)) { continue; }
                            if (!o.ObRetestConfirmEnabled)
                            {
                                signal = new ObSignal { Block = b, Side = b.Side, Price = price, Mode = "TOUCH", Time = nowMs };
                                continue;
                            }
                            // Dwell confirmation: price held inside the zone, stopped
                            // making new adverse extremes, and rebounded toward profit.
                            double mult = b.Reconfirm ? o.ObRetestReconfirmMult : 1.0;
                            bool dwelled = nowMs - b.TouchMs >= (long)(o.ObRetestDwellMs * mult);
                            bool rebounded = b.Side == BotSide.Long
                                    ? price >= b.WorstExtreme + o.ObRetestReboundDollars * mult
                                    : price <= b.WorstExtreme - o.ObRetestReboundDollars * mult;
                            if (dwelled && rebounded)
                            {
                                signal = new ObSignal { Block = b, Side = b.Side, Price = price, Mode = "RETEST_DWELL", Time = nowMs };
                            }
                        }
                        else if (b.InZone)
                        {
                            b.InZone = false;
                            if (!o.ObRetestConfirmEnabled) { continue; }
                            // Fast bounce: price touched the zone and left it on the
                            // profit side - the rejection itself is the confirmation.
                            bool profitExit = b.Side == BotSide.Long
                                    ? price > b.High + o.ObEntryToleranceDollars
                                    : price < b.Low - o.ObEntryToleranceDollars;
                            if (!profitExit) { continue; }
                            if (Guarded(b, price, nowMs)) { continue; }
                            signal = new ObSignal { Block = b, Side = b.Side, Price = price, Mode = "FAST_BOUNCE", Time = nowMs };
                        }
                    }
                }
                if (signal == null)
                {
                    signal = TryBigPrint(price, nowMs);
                }
                return signal;
            }

            private bool Guarded(ObBlock b, double price, long nowMs)
            {
                if (o.ObCounterTrendGuardEnabled)
                {
                    if (o.IsCounterTrend(b.Side))
                    {
                        // Adaptive regime guard: while recent drift runs hard against
                        // the block's side, defer the entry. The block stays alive and
                        // trades normally once the drift fades or turns.
                        b.LastCounterMs = nowMs;
                        if (nowMs - b.LastDeferLogMs >= 30000)
                        {
                            b.LastDeferLogMs = nowMs;
                            o.LogObEntryDeferred(b, price, nowMs, "COUNTER_TREND_DRIFT");
                        }
                        return true;
                    }
                    if (b.LastCounterMs > 0 && nowMs - b.LastCounterMs < o.ObDriftCalmMs)
                    {
                        // The drift only just eased below the threshold; require it
                        // to stay calm before entering, so a momentary dip in a
                        // running move cannot slip a knife-edge entry through.
                        if (nowMs - b.LastDeferLogMs >= 30000)
                        {
                            b.LastDeferLogMs = nowMs;
                            o.LogObEntryDeferred(b, price, nowMs, "DRIFT_CALM_WAIT");
                        }
                        return true;
                    }
                }
                if (IsFastApproach(b))
                {
                    if (nowMs - b.LastFastSkipMs >= o.ObApproachWindowMs)
                    {
                        b.LastFastSkipMs = nowMs;
                        o.LogObEntrySkipped(b, price, ApproachMove(), nowMs);
                    }
                    return true;
                }
                return false;
            }

            public void MarkTraded(ObBlock b)
            {
                b.TradesTaken++;
                b.LeftZone = false;
                b.InZone = false;
            }

            private void ScanForZone(long nowMs)
            {
                if (recentExecs.Count == 0) { return; }
                var perLevel = new Dictionary<long, double[]>(); // level -> {vol, delta}
                foreach (var e in recentExecs)
                {
                    long level = (long)Math.Round(e.Price / o.tickSize);
                    double[] agg;
                    if (!perLevel.TryGetValue(level, out agg))
                    {
                        agg = new double[2];
                        perLevel[level] = agg;
                    }
                    agg[0] += e.Size;
                    agg[1] += e.Size * e.DeltaSign;
                }
                long bestLevel = 0;
                double bestVol = 0;
                double bestDelta = 0;
                foreach (var c in perLevel)
                {
                    double vol = 0;
                    double delta = 0;
                    for (long l = c.Key - o.ObZoneTicks; l <= c.Key + o.ObZoneTicks; l++)
                    {
                        double[] agg;
                        if (perLevel.TryGetValue(l, out agg))
                        {
                            vol += agg[0];
                            delta += agg[1];
                        }
                    }
                    if (vol > bestVol)
                    {
                        bestVol = vol;
                        bestDelta = delta;
                        bestLevel = c.Key;
                    }
                }
                if (bestVol < o.ObMinZoneVolume) { return; }
                double low = (bestLevel - o.ObZoneTicks) * o.tickSize;
                double high = (bestLevel + o.ObZoneTicks) * o.tickSize;
                bool reconfirm = o.ObReconfirmEnabled && IsPenalized(low, high, nowMs);
                double minRatio = reconfirm ? o.ObReconfirmDeltaRatio : o.ObMinDeltaRatio;
                if (Math.Abs(bestDelta) < bestVol * minRatio)
                {
                    RejectOnce(bestLevel, low, high, bestVol, bestDelta,
                            reconfirm ? "RECONFIRM_DELTA" : "DELTA_TWO_SIDED", nowMs);
                    return;
                }

                foreach (ObBlock b0 in blocks)
                {
                    if (low <= b0.High && high >= b0.Low) { return; }
                }
                if (blocks.Count >= o.ObMaxActiveBlocks) { return; }
                var b1 = new ObBlock
                {
                    Id = ++blockSeq,
                    Low = low,
                    High = high,
                    Volume = bestVol,
                    Delta = bestDelta,
                    FormedAt = nowMs,
                    Reconfirm = reconfirm
                };
                blocks.Add(b1);
                o.LogZoneCandidate(b1, nowMs);
                recentExecs.Clear();
            }

            private void RejectOnce(long level, double low, double high, double volume,
                    double delta, string reason, long nowMs)
            {
                if (level == lastRejectLevel && nowMs - lastRejectMs < o.ObZoneWindowMs) { return; }
                lastRejectLevel = level;
                lastRejectMs = nowMs;
                o.LogZoneRejected(low, high, volume, delta, reason, nowMs);
            }

            private void RecordPricePath(double price, long nowMs)
            {
                pricePath.Enqueue(new KeyValuePair<long, double>(nowMs, price));
                long horizon = nowMs - o.ObApproachWindowMs;
                while (pricePath.Count > 0 && pricePath.Peek().Key < horizon)
                {
                    pricePath.Dequeue();
                }
            }

            private double ApproachMove()
            {
                if (pricePath.Count < 2) { return 0; }
                return pricePath.Last().Value - pricePath.Peek().Value;
            }

            private bool IsFastApproach(ObBlock b)
            {
                if (!o.ObApproachFilterEnabled) { return false; }
                double move = ApproachMove();
                return b.Side == BotSide.Long
                        ? move <= -o.ObMaxApproachDollars
                        : move >= o.ObMaxApproachDollars;
            }

            private void Penalize(ObBlock b, long nowMs)
            {
                if (!o.ObReconfirmEnabled) { return; }
                penalizedAreas.Add(new double[] { b.Low, b.High, nowMs + o.ObReconfirmMemoryMs });
            }

            private bool IsPenalized(double low, double high, long nowMs)
            {
                bool hit = false;
                for (int i = penalizedAreas.Count - 1; i >= 0; i--)
                {
                    double[] a = penalizedAreas[i];
                    if (nowMs > a[2]) { penalizedAreas.RemoveAt(i); }
                    else if (low <= a[1] && high >= a[0]) { hit = true; }
                }
                return hit;
            }

            /** Big Print detector. Adds incoming executions to a short window and,
             *  when the window reaches a minimum total volume with a dominant
             *  one-sided delta, raises an OB signal with no block (entryMode BIGPRINT).
             *  This mirrors the "Order Flow Trade Detector" large-circle events. */
            private void TrackBigPrint(double price, long size, bool buyAggressor, long nowMs)
            {
                if (!o.ObBigPrintEnabled) { return; }
                bigPrintWindow.Enqueue(new ObExec
                {
                    Price = price,
                    Size = size,
                    DeltaSign = buyAggressor ? 1 : -1,
                    Time = nowMs
                });
                long horizon = nowMs - o.ObBigPrintWindowMs;
                while (bigPrintWindow.Count > 0 && bigPrintWindow.Peek().Time < horizon)
                {
                    bigPrintWindow.Dequeue();
                }
                if (pendingBigPrint != null) { return; }
                if (nowMs - lastBigPrintMs < o.ObBigPrintCooldownMs) { return; }

                double volume = 0;
                double delta = 0;
                double lastPrice = price;
                foreach (var e in bigPrintWindow)
                {
                    volume += e.Size;
                    delta += e.Size * e.DeltaSign;
                    lastPrice = e.Price;
                }
                if (volume < o.ObBigPrintMinVolume)
                {
                    if (volume >= o.ObBigPrintMinVolume * 0.5)
                    {
                        o.blackBox.Log(nowMs, "BIGPRINT_SKIP",
                                "reason", "VOLUME_LOW", "volume", volume, "delta", delta,
                                "price", Round2(lastPrice));
                    }
                    return;
                }
                double ratio = Math.Abs(delta) / volume;
                if (ratio < o.ObBigPrintMinDeltaRatio)
                {
                    o.blackBox.Log(nowMs, "BIGPRINT_SKIP",
                            "reason", "DELTA_RATIO", "volume", volume, "delta", delta,
                            "ratio", Round2(ratio), "price", Round2(lastPrice));
                    return;
                }

                BotSide side = delta > 0 ? BotSide.Long : BotSide.Short;
                pendingBigPrint = new ObSignal
                {
                    Block = null,
                    Side = side,
                    Price = lastPrice,
                    Mode = "BIGPRINT",
                    Time = nowMs,
                    BigPrintVolume = volume,
                    BigPrintDelta = delta
                };
                lastBigPrintMs = nowMs;
                o.blackBox.Log(nowMs, "BIGPRINT_CANDIDATE",
                        "side", side.ToString().ToUpperInvariant(),
                        "price", Round2(lastPrice),
                        "volume", volume,
                        "delta", delta,
                        "ratio", Round2(ratio));
            }

            private void RejectBigPrint(string reason, double price, long nowMs)
            {
                if (o.blackBox == null) { return; }
                o.blackBox.Log(nowMs, "BIGPRINT_REJECTED",
                        "side", pendingBigPrint.Side.ToString().ToUpperInvariant(),
                        "price", Round2(pendingBigPrint.Price),
                        "lastPrice", Round2(price),
                        "volume", pendingBigPrint.BigPrintVolume,
                        "delta", pendingBigPrint.BigPrintDelta,
                        "reason", reason);
                pendingBigPrint = null;
            }

            private ObSignal TryBigPrint(double price, long nowMs)
            {
                if (!o.ObBigPrintEnabled || pendingBigPrint == null) { return null; }
                if (nowMs - pendingBigPrint.Time > o.ObBigPrintWindowMs)
                {
                    RejectBigPrint("STALE", price, nowMs);
                    return null;
                }
                if (Math.Abs(price - pendingBigPrint.Price) > o.ObBigPrintMaxDistanceDollars)
                {
                    RejectBigPrint("TOO_FAR", price, nowMs);
                    return null;
                }
                if (o.ObCounterTrendGuardEnabled && o.IsCounterTrend(pendingBigPrint.Side))
                {
                    RejectBigPrint("COUNTER_TREND", price, nowMs);
                    return null;
                }

                // Enter only on a tick that continues in the print's direction,
                // never on an immediate pullback that would put us on the wrong side.
                BotSide side = pendingBigPrint.Side;
                bool favorable = side == BotSide.Long
                        ? price >= pendingBigPrint.Price
                        : price <= pendingBigPrint.Price;
                if (!favorable) { return null; }

                ObSignal s = pendingBigPrint;
                pendingBigPrint = null;
                return s;
            }
        }

        // =====================================================================
        // Strategy state
        // =====================================================================

        private const string SignalWall = "WALL";
        private const string SignalOb = "OB";
        private const string SignalRevenge = "REVENGE";

        private BlackBox blackBox;
        private BookSide bids;
        private BookSide asks;
        private readonly List<WallInfo> walls = new List<WallInfo>();
        private long wallSeq = 1;

        private SwingMemory swings;
        private WaveTracker waveTracker;
        private RevengeEngine revengeEngine;
        private OrderBlockEngine obEngine;

        private double tickSize;
        private double pointValue;

        private double currentBid = double.NaN;
        private double currentAsk = double.NaN;
        private double lastTradePrice = double.NaN;

        private long nowMs;
        private long lastDetectMs;
        private long activeTargetId = -1;

        private readonly Dictionary<long, int> tradesPerWall = new Dictionary<long, int>();
        private readonly Dictionary<long, Queue<long>> pullHistory = new Dictionary<long, Queue<long>>();
        private readonly HashSet<long> spoofLoggedWalls = new HashSet<long>();
        private readonly Dictionary<long, double[]> lastEntryExtremeByLevel = new Dictionary<long, double[]>();

        // --- unified position (one open trade across BOTH engines) ---
        private enum TradeState { Flat, Entering, Open }
        private string openEngine;               // SignalWall / SignalOb / SignalRevenge, or null
        private string pendingObEntryMode;       // TOUCH / FAST_BOUNCE / RETEST_DWELL / BIGPRINT
        private double obBigPrintVolume;         // set by BIGPRINT signals (block == null)
        private double obBigPrintDelta;
        private TradeState tradeState = TradeState.Flat;
        private bool tradeIsShadow;
        private long tradeSeq;
        private long currentTradeId;
        private BotSide tradeSide;
        private double tradeEntryPrice;
        private double tradeTp;
        private double tradeSl;
        private double tradeWallPrice;
        private ObBlock tradeBlock;
        private bool tradeIsRevenge;
        private double tradeFavorableExtreme;
        private bool obTrailingLocked;
        private bool obTrailingActive;
        private long wallCooldownUntil;
        private long obCooldownUntil;

        // stats
        private int wallWins, wallLosses;
        private double wallNetPnl;
        private int obWins, obLosses;
        private double obNetPnl;

        // per-session (day) stats for DAY_SUMMARY
        private int dayWallWins, dayWallLosses, dayObWins, dayObLosses;
        private double dayWallPnl, dayObPnl;
        private readonly Dictionary<string, double[]> dayModeStats =
                new Dictionary<string, double[]>(); // entryMode -> {count, wins, pnl}

        // session guard
        private bool sessionPaused;
        private string lastResetDay;

        // =====================================================================
        // Parameters
        // =====================================================================

        [NinjaScriptProperty]
        [Display(Name = "Enable trading (off = shadow mode)", GroupName = "1. General", Order = 0)]
        public bool EnableTrading { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "Wall strategy enabled", GroupName = "1. General", Order = 1)]
        public bool WallStrategyEnabled { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "OB engine enabled", GroupName = "1. General", Order = 2)]
        public bool ObEnabled { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "Magnet mode (trade toward wall)", GroupName = "1. General", Order = 3)]
        public bool MagnetMode { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "Order size (wall/revenge)", GroupName = "1. General", Order = 4)]
        public int OrderSizeContracts { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "Take profit ($)", GroupName = "1. General", Order = 5)]
        public double TakeProfitDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "Stop loss ($)", GroupName = "1. General", Order = 6)]
        public double StopLossDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Cooldown after trade (ms)", GroupName = "1. General", Order = 7)]
        public long CooldownMsAfterTrade { get; set; }

        // --- session guard ---
        [NinjaScriptProperty]
        [Range(0, 1439)]
        [Display(Name = "Session pause (UTC minutes, 1230=20:30)", GroupName = "2. Session", Order = 0)]
        public int SessionPauseUtcMinutes { get; set; }

        [NinjaScriptProperty]
        [Range(0, 1439)]
        [Display(Name = "Session resume+reset (UTC minutes, 1320=22:00)", GroupName = "2. Session", Order = 1)]
        public int SessionResumeUtcMinutes { get; set; }

        // --- walls ---
        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "Wall min size", GroupName = "3. Walls", Order = 0)]
        public int WallMinSize { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, 1.0)]
        [Display(Name = "Wall break size fraction", GroupName = "3. Walls", Order = 1)]
        public double WallBreakSizeFraction { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Wall remove grace (ms)", GroupName = "3. Walls", Order = 2)]
        public long WallRemoveGraceMs { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "Max trades per wall", GroupName = "3. Walls", Order = 3)]
        public int MaxTradesPerWall { get; set; }

        [NinjaScriptProperty]
        [Range(1.0, double.MaxValue)]
        [Display(Name = "Wall dominance ratio", GroupName = "3. Walls", Order = 4)]
        public double WallDominanceRatio { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "Wall neighborhood (ticks)", GroupName = "3. Walls", Order = 5)]
        public int WallNeighborhoodTicks { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "Wall cluster (ticks)", GroupName = "3. Walls", Order = 6)]
        public int WallClusterTicks { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Wall persistence (ms)", GroupName = "3. Walls", Order = 7)]
        public long WallPersistenceMs { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "Max wall distance ($)", GroupName = "3. Walls", Order = 8)]
        public double MaxWallDistanceDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Detection throttle (ms)", GroupName = "3. Walls", Order = 9)]
        public long DetectionThrottleMs { get; set; }

        // --- wave ---
        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "Wave min amplitude ($)", GroupName = "4. Wave", Order = 0)]
        public double WaveMinAmplitudeDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Min entry distance from wall ($)", GroupName = "4. Wave", Order = 1)]
        public double MinEntryDistanceFromWallDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "Max entry distance from wall ($)", GroupName = "4. Wave", Order = 2)]
        public double MaxEntryDistanceFromWallDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Turn confirm ($)", GroupName = "4. Wave", Order = 3)]
        public double TurnConfirmDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Wave resume tolerance ($)", GroupName = "4. Wave", Order = 4)]
        public double WaveResumeToleranceDollars { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "Peak filter enabled", GroupName = "4. Wave", Order = 5)]
        public bool PeakFilterEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Peak lookback (ms)", GroupName = "4. Wave", Order = 6)]
        public long PeakLookbackMs { get; set; }

        [NinjaScriptProperty]
        [Range(0.01, double.MaxValue)]
        [Display(Name = "Pivot reversal ($)", GroupName = "4. Wave", Order = 7)]
        public double PivotReversalDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Peak breakout tolerance ($)", GroupName = "4. Wave", Order = 8)]
        public double PeakBreakoutToleranceDollars { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "No-history entry enabled", GroupName = "4. Wave", Order = 9)]
        public bool PeakNoHistoryEntryEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "No-history extra confirm ($)", GroupName = "4. Wave", Order = 10)]
        public double PeakNoHistoryExtraConfirmDollars { get; set; }

        // --- spoof / re-entry ---
        [NinjaScriptProperty]
        [Display(Name = "Spoof filter enabled", GroupName = "5. Spoof & Re-entry", Order = 0)]
        public bool SpoofFilterEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "Spoof pull count", GroupName = "5. Spoof & Re-entry", Order = 1)]
        public int SpoofPullCount { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Spoof window (ms)", GroupName = "5. Spoof & Re-entry", Order = 2)]
        public long SpoofWindowMs { get; set; }

        [NinjaScriptProperty]
        [Range(0, int.MaxValue)]
        [Display(Name = "Spoof tolerance (ticks)", GroupName = "5. Spoof & Re-entry", Order = 3)]
        public int SpoofToleranceTicks { get; set; }

        [NinjaScriptProperty]
        [Range(0, int.MaxValue)]
        [Display(Name = "Spoof exempt wall size", GroupName = "5. Spoof & Re-entry", Order = 4)]
        public int SpoofExemptWallSize { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "Re-entry deeper-extreme guard", GroupName = "5. Spoof & Re-entry", Order = 5)]
        public bool ReentryDeeperExtremeEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Re-entry min extreme advance ($)", GroupName = "5. Spoof & Re-entry", Order = 6)]
        public double ReentryMinExtremeAdvanceDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Re-entry memory (ms)", GroupName = "5. Spoof & Re-entry", Order = 7)]
        public long ReentryMemoryMs { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Re-entry cooldown (ms)", GroupName = "5. Spoof & Re-entry", Order = 8)]
        public long ReentryCooldownMs { get; set; }

        // --- revenge ---
        [NinjaScriptProperty]
        [Display(Name = "Revenge enabled", GroupName = "6. Revenge", Order = 0)]
        public bool RevengeEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(0, int.MaxValue)]
        [Display(Name = "Revenge max attempts", GroupName = "6. Revenge", Order = 1)]
        public int RevengeMaxAttempts { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Revenge min excursion ($)", GroupName = "6. Revenge", Order = 2)]
        public double RevengeMinExcursionDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Revenge reversal ($)", GroupName = "6. Revenge", Order = 3)]
        public double RevengeReversalDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Revenge window (ms)", GroupName = "6. Revenge", Order = 4)]
        public long RevengeWindowMs { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Revenge confirm ($)", GroupName = "6. Revenge", Order = 5)]
        public double RevengeConfirmDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Revenge max beyond stop ($)", GroupName = "6. Revenge", Order = 6)]
        public double RevengeMaxBeyondStopDollars { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "Cancel revenge on opposite wall", GroupName = "6. Revenge", Order = 7)]
        public bool RevengeCancelOnOppositeWall { get; set; }

        // --- order blocks ---
        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "OB min zone volume", GroupName = "7. Order Blocks", Order = 0)]
        public int ObMinZoneVolume { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "OB zone half-width (ticks)", GroupName = "7. Order Blocks", Order = 1)]
        public int ObZoneTicks { get; set; }

        [NinjaScriptProperty]
        [Range(1000, long.MaxValue)]
        [Display(Name = "OB zone window (ms)", GroupName = "7. Order Blocks", Order = 2)]
        public long ObZoneWindowMs { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, 1.0)]
        [Display(Name = "OB min delta ratio", GroupName = "7. Order Blocks", Order = 3)]
        public double ObMinDeltaRatio { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "OB displacement ($)", GroupName = "7. Order Blocks", Order = 4)]
        public double ObDisplacementDollars { get; set; }

        [NinjaScriptProperty]
        [Range(1000, long.MaxValue)]
        [Display(Name = "OB displacement window (ms)", GroupName = "7. Order Blocks", Order = 5)]
        public long ObDisplacementWindowMs { get; set; }

        [NinjaScriptProperty]
        [Range(1000, long.MaxValue)]
        [Display(Name = "OB block max age (ms)", GroupName = "7. Order Blocks", Order = 6)]
        public long ObMaxAgeMs { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "OB entry tolerance ($)", GroupName = "7. Order Blocks", Order = 7)]
        public double ObEntryToleranceDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "OB invalidation ($)", GroupName = "7. Order Blocks", Order = 8)]
        public double ObInvalidationDollars { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "OB max trades per block", GroupName = "7. Order Blocks", Order = 9)]
        public int ObMaxTradesPerBlock { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "OB max active blocks", GroupName = "7. Order Blocks", Order = 10)]
        public int ObMaxActiveBlocks { get; set; }

        [NinjaScriptProperty]
        [Range(100, long.MaxValue)]
        [Display(Name = "OB scan throttle (ms)", GroupName = "7. Order Blocks", Order = 11)]
        public long ObScanThrottleMs { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "OB order size", GroupName = "7. Order Blocks", Order = 12)]
        public int ObOrderSize { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "OB take profit ($)", GroupName = "7. Order Blocks", Order = 13)]
        public double ObTakeProfitDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "OB stop loss ($)", GroupName = "7. Order Blocks", Order = 14)]
        public double ObStopLossDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "OB cooldown (ms)", GroupName = "7. Order Blocks", Order = 15)]
        public long ObCooldownMs { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "OB approach filter enabled", GroupName = "7. Order Blocks", Order = 16)]
        public bool ObApproachFilterEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "OB max approach ($)", GroupName = "7. Order Blocks", Order = 17)]
        public double ObMaxApproachDollars { get; set; }

        [NinjaScriptProperty]
        [Range(1000, long.MaxValue)]
        [Display(Name = "OB approach window (ms)", GroupName = "7. Order Blocks", Order = 18)]
        public long ObApproachWindowMs { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "OB re-confirmation enabled", GroupName = "7. Order Blocks", Order = 19)]
        public bool ObReconfirmEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "OB reconfirm memory (ms)", GroupName = "7. Order Blocks", Order = 20)]
        public long ObReconfirmMemoryMs { get; set; }

        [NinjaScriptProperty]
        [Range(1.0, double.MaxValue)]
        [Display(Name = "OB reconfirm displacement mult", GroupName = "7. Order Blocks", Order = 21)]
        public double ObReconfirmDisplacementMult { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, 1.0)]
        [Display(Name = "OB reconfirm delta ratio", GroupName = "7. Order Blocks", Order = 22)]
        public double ObReconfirmDeltaRatio { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "OB retest confirm enabled", GroupName = "7. Order Blocks", Order = 23)]
        public bool ObRetestConfirmEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "OB retest dwell (ms)", GroupName = "7. Order Blocks", Order = 24)]
        public long ObRetestDwellMs { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "OB retest rebound ($)", GroupName = "7. Order Blocks", Order = 25)]
        public double ObRetestReboundDollars { get; set; }

        [NinjaScriptProperty]
        [Range(1.0, double.MaxValue)]
        [Display(Name = "OB retest reconfirm mult", GroupName = "7. Order Blocks", Order = 26)]
        public double ObRetestReconfirmMult { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "OB counter-trend guard enabled", GroupName = "8. Market Regime", Order = 0)]
        public bool ObCounterTrendGuardEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "Counter-trend drift threshold ($)", GroupName = "8. Market Regime", Order = 1)]
        public double ObCounterTrendDriftDollars { get; set; }

        [NinjaScriptProperty]
        [Range(60000, long.MaxValue)]
        [Display(Name = "Regime window (ms)", GroupName = "8. Market Regime", Order = 2)]
        public long RegimeWindowMs { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Drift calm confirm (ms)", GroupName = "8. Market Regime", Order = 3)]
        public long ObDriftCalmMs { get; set; }

        [NinjaScriptProperty]
        [Display(Name = "OB trailing enabled", GroupName = "9. OB Trailing Stop", Order = 0)]
        public bool ObTrailingEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "OB trailing lock ($)", GroupName = "9. OB Trailing Stop", Order = 1)]
        public double ObTrailingLockDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "OB trailing lock buffer ($)", GroupName = "9. OB Trailing Stop", Order = 2)]
        public double ObTrailingLockBufferDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "OB trailing kick-in ($)", GroupName = "9. OB Trailing Stop", Order = 3)]
        public double ObTrailingKickInDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "OB trailing distance ($)", GroupName = "9. OB Trailing Stop", Order = 4)]
        public double ObTrailingDistanceDollars { get; set; }

        [NinjaScriptProperty]
        [Range(0.1, double.MaxValue)]
        [Display(Name = "OB trailing profit cap ($)", GroupName = "9. OB Trailing Stop", Order = 5)]
        public double ObTrailingProfitCapDollars { get; set; }

        // --- OB big print (Order Flow Trade Detector equivalent) ---
        [NinjaScriptProperty]
        [Display(Name = "OB big print enabled", GroupName = "10. OB Big Print", Order = 0)]
        public bool ObBigPrintEnabled { get; set; }

        [NinjaScriptProperty]
        [Range(1, int.MaxValue)]
        [Display(Name = "Big print min volume", GroupName = "10. OB Big Print", Order = 1)]
        public int ObBigPrintMinVolume { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, 1.0)]
        [Display(Name = "Big print min delta ratio", GroupName = "10. OB Big Print", Order = 2)]
        public double ObBigPrintMinDeltaRatio { get; set; }

        [NinjaScriptProperty]
        [Range(100, long.MaxValue)]
        [Display(Name = "Big print window (ms)", GroupName = "10. OB Big Print", Order = 3)]
        public long ObBigPrintWindowMs { get; set; }

        [NinjaScriptProperty]
        [Range(0, long.MaxValue)]
        [Display(Name = "Big print cooldown (ms)", GroupName = "10. OB Big Print", Order = 4)]
        public long ObBigPrintCooldownMs { get; set; }

        [NinjaScriptProperty]
        [Range(0.0, double.MaxValue)]
        [Display(Name = "Big print max entry distance ($)", GroupName = "10. OB Big Print", Order = 5)]
        public double ObBigPrintMaxDistanceDollars { get; set; }

        // =====================================================================
        // Lifecycle
        // =====================================================================

        protected override void OnStateChange()
        {
            if (State == State.SetDefaults)
            {
                Description = "Liquidity Wall Bot - wall magnet + raw order-block engines (Bookmap port, base v1.2.4)";
                Name = "LiquidityWallBot";
                Calculate = Calculate.OnEachTick;
                EntriesPerDirection = 1;
                EntryHandling = EntryHandling.AllEntries;
                IsExitOnSessionCloseStrategy = false;
                IsUnmanaged = false;
                IsInstantiatedOnEachOptimizationIteration = false;
                BarsRequiredToTrade = 0;

                EnableTrading = false;
                WallStrategyEnabled = true;
                ObEnabled = true;
                MagnetMode = true;
                OrderSizeContracts = 1;
                TakeProfitDollars = 4.0;
                StopLossDollars = 15.0;
                CooldownMsAfterTrade = 5000;

                SessionPauseUtcMinutes = 20 * 60 + 30;   // 20:30 UTC = 23:30 Riyadh
                SessionResumeUtcMinutes = 22 * 60;       // 22:00 UTC = 01:00 Riyadh

                WallMinSize = 47;
                WallBreakSizeFraction = 0.5;
                WallRemoveGraceMs = 1500;
                MaxTradesPerWall = 2;
                WallDominanceRatio = 3.0;
                WallNeighborhoodTicks = 50;
                WallClusterTicks = 3;
                WallPersistenceMs = 1500;
                MaxWallDistanceDollars = 30.0;
                DetectionThrottleMs = 100;

                WaveMinAmplitudeDollars = 8.0;
                MinEntryDistanceFromWallDollars = 6.0;
                MaxEntryDistanceFromWallDollars = 25.0;
                TurnConfirmDollars = 1.0;
                WaveResumeToleranceDollars = 1.0;
                PeakFilterEnabled = true;
                PeakLookbackMs = 900000;
                PivotReversalDollars = 3.0;
                PeakBreakoutToleranceDollars = 0.5;
                PeakNoHistoryEntryEnabled = true;
                PeakNoHistoryExtraConfirmDollars = 1.0;

                SpoofFilterEnabled = true;
                SpoofPullCount = 2;
                SpoofWindowMs = 600000;
                SpoofToleranceTicks = 5;
                SpoofExemptWallSize = 100;
                ReentryDeeperExtremeEnabled = true;
                ReentryMinExtremeAdvanceDollars = 1.0;
                ReentryMemoryMs = 3600000;
                ReentryCooldownMs = 720000;

                RevengeEnabled = true;
                RevengeMaxAttempts = 1;
                RevengeMinExcursionDollars = 3.0;
                RevengeReversalDollars = 2.5;
                RevengeWindowMs = 1200000;
                RevengeConfirmDollars = 1.0;
                RevengeMaxBeyondStopDollars = 5.0;
                RevengeCancelOnOppositeWall = true;

                ObMinZoneVolume = 150;
                ObZoneTicks = 10;
                ObZoneWindowMs = 60000;
                ObMinDeltaRatio = 0.3;
                ObDisplacementDollars = 5.0;
                ObDisplacementWindowMs = 300000;
                ObMaxAgeMs = 3600000;
                ObEntryToleranceDollars = 0.5;
                ObInvalidationDollars = 2.0;
                ObMaxTradesPerBlock = 1;
                ObMaxActiveBlocks = 10;
                ObScanThrottleMs = 2000;
                ObOrderSize = 1;
                ObTakeProfitDollars = 4.0;
                ObStopLossDollars = 8.0;
                ObCooldownMs = 60000;
                ObApproachFilterEnabled = false; // measured: hurt winners without cutting losers
                ObMaxApproachDollars = 3.0;
                ObApproachWindowMs = 30000;
                ObReconfirmEnabled = true;
                ObReconfirmMemoryMs = 7200000;
                ObReconfirmDisplacementMult = 1.5;
                ObReconfirmDeltaRatio = 0.45;

                ObRetestConfirmEnabled = true;
                ObRetestDwellMs = 15000;
                ObRetestReboundDollars = 0.75;
                ObRetestReconfirmMult = 2.0;

                ObCounterTrendGuardEnabled = true;
                ObCounterTrendDriftDollars = 3.0;
                RegimeWindowMs = 600000;
                ObDriftCalmMs = 60000;

                ObTrailingEnabled = true;
                ObTrailingLockDollars = 2.0;
                ObTrailingLockBufferDollars = 0.5;
                ObTrailingKickInDollars = 6.0;
                ObTrailingDistanceDollars = 2.0;
                ObTrailingProfitCapDollars = 50.0;

                ObBigPrintEnabled = false;
                ObBigPrintMinVolume = 100;
                ObBigPrintMinDeltaRatio = 0.70;
                ObBigPrintWindowMs = 1000;
                ObBigPrintCooldownMs = 60000;
                ObBigPrintMaxDistanceDollars = 1.0;
            }
            else if (State == State.DataLoaded)
            {
                tickSize = Instrument.MasterInstrument.TickSize;
                pointValue = Instrument.MasterInstrument.PointValue;

                bids = new BookSide();
                asks = new BookSide();
                swings = new SwingMemory(this);
                waveTracker = new WaveTracker(this, swings);
                revengeEngine = new RevengeEngine(this);
                obEngine = new OrderBlockEngine(this);
                blackBox = new BlackBox(Instrument.FullName);

                // Platform-side bracket orders per entry signal (live mode).
                SetProfitTarget(SignalWall, CalculationMode.Ticks, TakeProfitDollars / tickSize);
                SetStopLoss(SignalWall, CalculationMode.Ticks, StopLossDollars / tickSize, false);
                SetProfitTarget(SignalRevenge, CalculationMode.Ticks, TakeProfitDollars / tickSize);
                SetStopLoss(SignalRevenge, CalculationMode.Ticks, StopLossDollars / tickSize, false);
                SetProfitTarget(SignalOb, CalculationMode.Ticks, ObTakeProfitDollars / tickSize);
                SetStopLoss(SignalOb, CalculationMode.Ticks, ObStopLossDollars / tickSize, false);

                nowMs = ToMs(DateTime.UtcNow);
                blackBox.LogStartup(nowMs, "INIT",
                        "platform", "NinjaTrader8",
                        "instrument", Instrument.FullName,
                        "tickSize", tickSize,
                        "pointValue", pointValue,
                        "enableTrading", EnableTrading,
                        "wallStrategyEnabled", WallStrategyEnabled,
                        "obEnabled", ObEnabled,
                        "sessionPauseUtcMin", SessionPauseUtcMinutes,
                        "sessionResumeUtcMin", SessionResumeUtcMinutes);
                LogSettings();
            }
            else if (State == State.Terminated)
            {
                if (blackBox != null)
                {
                    LogDaySummary();
                    blackBox.LogStartup(ToMs(DateTime.UtcNow), "STOP");
                    blackBox.Close();
                    blackBox = null;
                }
            }
        }

        private static long ToMs(DateTime utc)
        {
            return new DateTimeOffset(DateTime.SpecifyKind(utc, DateTimeKind.Utc)).ToUnixTimeMilliseconds();
        }

        private long EventMs(DateTime eventTime)
        {
            // NinjaTrader event times are in the local machine time zone.
            DateTime local = DateTime.SpecifyKind(eventTime, DateTimeKind.Local);
            return new DateTimeOffset(local).ToUnixTimeMilliseconds();
        }

        private void LogSettings()
        {
            blackBox.LogStartup(nowMs, "SETTINGS",
                    "magnetMode", MagnetMode,
                    "wallMinSize", WallMinSize,
                    "wallDominanceRatio", WallDominanceRatio,
                    "wallPersistenceMs", WallPersistenceMs,
                    "maxWallDistance", MaxWallDistanceDollars,
                    "orderSize", OrderSizeContracts,
                    "takeProfit", TakeProfitDollars,
                    "stopLoss", StopLossDollars,
                    "waveMinAmplitude", WaveMinAmplitudeDollars,
                    "minEntryDistance", MinEntryDistanceFromWallDollars,
                    "maxEntryDistance", MaxEntryDistanceFromWallDollars,
                    "turnConfirm", TurnConfirmDollars,
                    "peakFilterEnabled", PeakFilterEnabled,
                    "spoofFilterEnabled", SpoofFilterEnabled,
                    "reentryGuardEnabled", ReentryDeeperExtremeEnabled,
                    "revengeEnabled", RevengeEnabled);
            blackBox.LogStartup(nowMs, "OB_SETTINGS",
                    "obEnabled", ObEnabled,
                    "obMinZoneVolume", ObMinZoneVolume,
                    "obZoneTicks", ObZoneTicks,
                    "obZoneWindowMs", ObZoneWindowMs,
                    "obMinDeltaRatio", ObMinDeltaRatio,
                    "obDisplacement", ObDisplacementDollars,
                    "obDisplacementWindowMs", ObDisplacementWindowMs,
                    "obMaxAgeMs", ObMaxAgeMs,
                    "obEntryTolerance", ObEntryToleranceDollars,
                    "obInvalidation", ObInvalidationDollars,
                    "obMaxTradesPerBlock", ObMaxTradesPerBlock,
                    "obMaxActiveBlocks", ObMaxActiveBlocks,
                    "obOrderSize", ObOrderSize,
                    "obTakeProfit", ObTakeProfitDollars,
                    "obStopLoss", ObStopLossDollars,
                    "obCooldownMs", ObCooldownMs,
                    "obApproachFilterEnabled", ObApproachFilterEnabled,
                    "obReconfirmEnabled", ObReconfirmEnabled,
                    "obReconfirmMemoryMs", ObReconfirmMemoryMs,
                    "obReconfirmDisplacementMult", ObReconfirmDisplacementMult,
                    "obReconfirmDeltaRatio", ObReconfirmDeltaRatio,
                    "obRetestConfirmEnabled", ObRetestConfirmEnabled,
                    "obRetestDwellMs", ObRetestDwellMs,
                    "obRetestRebound", ObRetestReboundDollars,
                    "obRetestReconfirmMult", ObRetestReconfirmMult,
                    "obCounterTrendGuardEnabled", ObCounterTrendGuardEnabled,
                    "obCounterTrendDrift", ObCounterTrendDriftDollars,
                    "regimeWindowMs", RegimeWindowMs,
                    "obDriftCalmMs", ObDriftCalmMs,
                    "obTrailingEnabled", ObTrailingEnabled,
                    "obTrailingLock", ObTrailingLockDollars,
                    "obTrailingLockBuffer", ObTrailingLockBufferDollars,
                    "obTrailingKickIn", ObTrailingKickInDollars,
                    "obTrailingDistance", ObTrailingDistanceDollars,
                    "obTrailingProfitCap", ObTrailingProfitCapDollars);
            blackBox.LogStartup(nowMs, "OB_BIGPRINT_SETTINGS",
                    "obBigPrintEnabled", ObBigPrintEnabled,
                    "obBigPrintMinVolume", ObBigPrintMinVolume,
                    "obBigPrintMinDeltaRatio", ObBigPrintMinDeltaRatio,
                    "obBigPrintWindowMs", ObBigPrintWindowMs,
                    "obBigPrintCooldownMs", ObBigPrintCooldownMs,
                    "obBigPrintMaxDistance", ObBigPrintMaxDistanceDollars);
        }

        // =====================================================================
        // Market data feeds
        // =====================================================================

        protected override void OnBarUpdate()
        {
            // Intentionally empty: the bot is tick-driven, not bar-driven.
        }

        protected override void OnMarketData(MarketDataEventArgs e)
        {
            if (State == State.Historical && Connection.PlaybackConnection == null)
            {
                // Historical bar backfill has no depth/tape; the bot runs on
                // live data and on Playback (Market Replay) data, which can
                // arrive while the strategy is still in the Historical state.
                return;
            }
            if (e.MarketDataType == MarketDataType.Bid)
            {
                currentBid = e.Price;
                return;
            }
            if (e.MarketDataType == MarketDataType.Ask)
            {
                currentAsk = e.Price;
                return;
            }
            if (e.MarketDataType != MarketDataType.Last)
            {
                return;
            }

            nowMs = EventMs(e.Time);
            SessionGuardTick();

            lastTradePrice = e.Price;
            RegimeTick(e.Price);

            // Aggressor from the trade price itself: at/above the ask = buyer
            // lifted the offer; at/below the bid = seller hit the bid. Trades
            // between the quotes (rare) are classified by nearest side.
            bool buyAggressor;
            if (!double.IsNaN(currentAsk) && e.Price >= currentAsk) { buyAggressor = true; }
            else if (!double.IsNaN(currentBid) && e.Price <= currentBid) { buyAggressor = false; }
            else if (!double.IsNaN(currentAsk) && !double.IsNaN(currentBid))
            {
                buyAggressor = (e.Price - currentBid) >= (currentAsk - e.Price);
            }
            else { buyAggressor = true; }

            obEngine.OnExecution(e.Price, e.Volume, buyAggressor, nowMs);
            OnPriceUpdate(e.Price);
        }

        protected override void OnMarketDepth(MarketDepthEventArgs e)
        {
            nowMs = EventMs(e.Time);
            BookSide side = e.MarketDataType == MarketDataType.Ask ? asks : bids;
            int level = (int)Math.Round(e.Price / tickSize);
            if (e.Operation == Operation.Remove) { side.Set(level, 0); }
            else { side.Set(level, e.Volume); }

            MaybeDetect();
            double mid = MidPrice();
            if (!double.IsNaN(mid))
            {
                OnPriceUpdate(!double.IsNaN(lastTradePrice) ? lastTradePrice : mid);
            }
        }

        private double MidPrice()
        {
            if (!double.IsNaN(currentBid) && !double.IsNaN(currentAsk))
            {
                return (currentBid + currentAsk) / 2.0;
            }
            return lastTradePrice;
        }

        // =====================================================================
        // Session guard: UTC pause + new-day memory reset
        // =====================================================================

        private void SessionGuardTick()
        {
            DateTime utc = DateTimeOffset.FromUnixTimeMilliseconds(nowMs).UtcDateTime;
            int minuteOfDay = utc.Hour * 60 + utc.Minute;

            bool inPause = SessionPauseUtcMinutes <= SessionResumeUtcMinutes
                    ? (minuteOfDay >= SessionPauseUtcMinutes && minuteOfDay < SessionResumeUtcMinutes)
                    : (minuteOfDay >= SessionPauseUtcMinutes || minuteOfDay < SessionResumeUtcMinutes);

            if (inPause && !sessionPaused)
            {
                sessionPaused = true;
                blackBox.Log(nowMs, "SESSION_TRADING_PAUSED",
                        "untilUtcMin", SessionResumeUtcMinutes);
            }
            else if (!inPause && sessionPaused)
            {
                sessionPaused = false;
                string day = utc.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
                if (day != lastResetDay)
                {
                    lastResetDay = day;
                    LogDaySummary();
                    ResetMemoryForNewSession();
                    blackBox.Log(nowMs, "SESSION_RESET", "day", day);
                }
            }
        }

        private void LogDaySummary()
        {
            int trades = dayWallWins + dayWallLosses + dayObWins + dayObLosses;
            if (trades == 0) { return; }
            blackBox.Log(nowMs, "DAY_SUMMARY",
                    "trades", trades,
                    "wins", dayWallWins + dayObWins,
                    "losses", dayWallLosses + dayObLosses,
                    "netPnlDollars", Round2(dayWallPnl + dayObPnl),
                    "wallTrades", dayWallWins + dayWallLosses,
                    "wallWins", dayWallWins,
                    "wallPnlDollars", Round2(dayWallPnl),
                    "obTrades", dayObWins + dayObLosses,
                    "obWins", dayObWins,
                    "obPnlDollars", Round2(dayObPnl));
            foreach (var kv in dayModeStats)
            {
                blackBox.Log(nowMs, "DAY_SUMMARY_MODE",
                        "entryMode", kv.Key,
                        "trades", (int)kv.Value[0],
                        "wins", (int)kv.Value[1],
                        "pnlDollars", Round2(kv.Value[2]));
            }
            dayWallWins = 0; dayWallLosses = 0; dayObWins = 0; dayObLosses = 0;
            dayWallPnl = 0; dayObPnl = 0;
            dayModeStats.Clear();
        }

        private void ResetMemoryForNewSession()
        {
            walls.Clear();
            tradesPerWall.Clear();
            pullHistory.Clear();
            spoofLoggedWalls.Clear();
            lastEntryExtremeByLevel.Clear();
            swings.Reset();
            waveTracker.Disarm();
            revengeEngine.Reset();
            obEngine.Reset();
            activeTargetId = -1;
        }

        private bool EntriesAllowed()
        {
            return !sessionPaused;
        }

        // =====================================================================
        // Market regime: rolling drift/range over the last RegimeWindowMs
        // =====================================================================

        private readonly List<long> regimeTimes = new List<long>();
        private readonly List<double> regimePrices = new List<double>();
        private long lastRegimeSampleMs;
        private long lastRegimeLogMs;

        private void RegimeTick(double price)
        {
            if (nowMs - lastRegimeSampleMs < 1000) { return; }
            lastRegimeSampleMs = nowMs;
            regimeTimes.Add(nowMs);
            regimePrices.Add(price);
            long horizon = nowMs - RegimeWindowMs;
            int drop = 0;
            while (drop < regimeTimes.Count && regimeTimes[drop] < horizon) { drop++; }
            if (drop > 0)
            {
                regimeTimes.RemoveRange(0, drop);
                regimePrices.RemoveRange(0, drop);
            }
            if (nowMs - lastRegimeLogMs >= 300000 && regimePrices.Count >= 2)
            {
                lastRegimeLogMs = nowMs;
                blackBox.Log(nowMs, "REGIME",
                        "drift", RegimeDrift(),
                        "range", RegimeRange(),
                        "samples", regimePrices.Count);
            }
        }

        private double RegimeDrift()
        {
            if (regimePrices.Count < 2) { return 0; }
            return regimePrices[regimePrices.Count - 1] - regimePrices[0];
        }

        private double RegimeRange()
        {
            if (regimePrices.Count < 2) { return 0; }
            double min = double.MaxValue, max = double.MinValue;
            for (int i = 0; i < regimePrices.Count; i++)
            {
                if (regimePrices[i] < min) { min = regimePrices[i]; }
                if (regimePrices[i] > max) { max = regimePrices[i]; }
            }
            return max - min;
        }

        private bool IsCounterTrend(BotSide side)
        {
            double drift = RegimeDrift();
            return side == BotSide.Long
                    ? drift <= -ObCounterTrendDriftDollars
                    : drift >= ObCounterTrendDriftDollars;
        }

        private void LogObEntryDeferred(ObBlock b, double price, long now, string reason)
        {
            blackBox.Log(now, "OB_ENTRY_DEFERRED",
                    "blockId", b.Id,
                    "side", b.Side.ToString(),
                    "price", price,
                    "drift", RegimeDrift(),
                    "range", RegimeRange(),
                    "reason", reason);
        }

        // =====================================================================
        // Wall detection (from the depth book)
        // =====================================================================

        private void MaybeDetect()
        {
            double mid = MidPrice();
            if (double.IsNaN(mid)) { return; }
            if (nowMs - lastDetectMs < DetectionThrottleMs) { return; }
            lastDetectMs = nowMs;
            DetectWalls(mid);
            if (WallStrategyEnabled && tradeState == TradeState.Flat)
            {
                EnsureTarget(mid);
            }
        }

        private void DetectWalls(double midPrice)
        {
            int? bestBid = bids.Levels.Count > 0 ? (int?)bids.Levels.Keys.Max() : null;
            int? bestAsk = asks.Levels.Count > 0 ? (int?)asks.Levels.Keys.Min() : null;

            var candidates = new List<WallInfo>();
            FindWalls(bids.Levels, false, midPrice, candidates);
            FindWalls(asks.Levels, true, midPrice, candidates);

            foreach (WallInfo c in candidates)
            {
                WallInfo match = null;
                int bestDist = int.MaxValue;
                foreach (WallInfo w in walls)
                {
                    if (w.IsAsk != c.IsAsk) { continue; }
                    int d = Math.Abs(w.Level - c.Level);
                    if (d <= WallClusterTicks && d < bestDist)
                    {
                        bestDist = d;
                        match = w;
                    }
                }
                if (match != null) { match.Update(c.Level, c.Price, c.Size, nowMs); }
                else { walls.Add(new WallInfo(wallSeq++, c.IsAsk, c.Level, c.Price, c.Size, nowMs)); }
            }

            for (int i = walls.Count - 1; i >= 0; i--)
            {
                WallInfo w = walls[i];
                bool tradedThrough =
                        (w.IsAsk && bestBid.HasValue && w.Level < bestBid.Value)
                        || (!w.IsAsk && bestAsk.HasValue && w.Level > bestAsk.Value);
                double thinFloor = w.Confirmed
                        ? WallMinSize * WallBreakSizeFraction
                        : WallMinSize;
                bool thinned = (nowMs - w.LastSeenMs) <= WallRemoveGraceMs && w.Size < thinFloor;
                bool vanished = (nowMs - w.LastSeenMs) > WallRemoveGraceMs;

                if (tradedThrough || thinned || vanished)
                {
                    if (w.Confirmed)
                    {
                        string reason = tradedThrough ? "traded_through" : (thinned ? "thinned" : "pulled");
                        blackBox.Log(nowMs, "WALL_BROKEN", "wallId", w.Id,
                                "side", w.IsAsk ? "ASK" : "BID", "price", Round2(w.Price),
                                "size", w.Size, "reason", reason);
                        if (reason == "pulled") { RecordPull(w.Price); }
                    }
                    walls.RemoveAt(i);
                    continue;
                }
                if (!w.Confirmed && w.Size >= WallMinSize && w.AgeMs(nowMs) >= WallPersistenceMs)
                {
                    w.Confirmed = true;
                    blackBox.Log(nowMs, "WALL_CONFIRMED", "wallId", w.Id,
                            "side", w.IsAsk ? "ASK" : "BID", "price", Round2(w.Price), "size", w.Size);
                }
            }
        }

        private void FindWalls(SortedDictionary<int, long> side, bool isAsk, double midPrice,
                List<WallInfo> outList)
        {
            if (side.Count == 0) { return; }
            int rangeTicks = Math.Max(1, (int)Math.Ceiling(MaxWallDistanceDollars / tickSize));
            int midLevel = (int)Math.Round(midPrice / tickSize);
            int lo = midLevel - rangeTicks;
            int hi = midLevel + rangeTicks;

            var levels = new List<int[]>();
            foreach (var e in side)
            {
                if (e.Key >= lo && e.Key <= hi)
                {
                    levels.Add(new int[] { e.Key, (int)Math.Min(int.MaxValue, e.Value) });
                }
            }
            if (levels.Count == 0) { return; }

            int i = 0;
            while (i < levels.Count)
            {
                int[] lv = levels[i];
                double dominanceFloor = LocalAverage(levels, i) * WallDominanceRatio;
                bool dominant = lv[1] >= WallMinSize && lv[1] >= dominanceFloor;
                if (!dominant) { i++; continue; }

                long sumSize = lv[1];
                int maxSize = lv[1];
                int maxLevel = lv[0];
                int lastLevel = lv[0];
                int j = i + 1;
                while (j < levels.Count
                        && levels[j][0] - lastLevel <= WallClusterTicks
                        && levels[j][1] >= dominanceFloor)
                {
                    int[] n = levels[j];
                    sumSize += n[1];
                    if (n[1] > maxSize) { maxSize = n[1]; maxLevel = n[0]; }
                    lastLevel = n[0];
                    j++;
                }
                double price = maxLevel * tickSize;
                if (Math.Abs(price - midPrice) <= MaxWallDistanceDollars)
                {
                    outList.Add(new WallInfo(0, isAsk, maxLevel, price, sumSize, nowMs));
                }
                i = j;
            }
        }

        private double LocalAverage(List<int[]> levels, int idx)
        {
            int center = levels[idx][0];
            long sum = 0;
            int count = 0;
            for (int k = 0; k < levels.Count; k++)
            {
                if (k == idx) { continue; }
                if (Math.Abs(levels[k][0] - center) <= WallNeighborhoodTicks)
                {
                    sum += levels[k][1];
                    count++;
                }
            }
            return count == 0 ? 0.0 : (double)sum / count;
        }

        private WallInfo FindWall(long id)
        {
            foreach (WallInfo w in walls)
            {
                if (w.Id == id && w.Confirmed) { return w; }
            }
            return null;
        }

        private bool WallStillActive(long id)
        {
            return FindWall(id) != null;
        }

        private List<WallInfo> AllConfirmed()
        {
            var list = new List<WallInfo>();
            foreach (WallInfo w in walls)
            {
                if (w.Confirmed) { list.Add(w); }
            }
            return list;
        }

        // =====================================================================
        // Core decision tick
        // =====================================================================

        private void OnPriceUpdate(double price)
        {
            SessionGuardTick();
            swings.OnPrice(price, nowMs);

            UpdateObTrailing(price);

            // Shadow-mode TP/SL management (live mode is bracket-managed).
            ShadowManage(price);

            // Order-block engine (+ BIGPRINT signals from the tape).
            ObSignal obSig = obEngine.OnPrice(price, nowMs);
            if (obSig != null && CanEnter(SignalOb))
            {
                pendingObEntryMode = obSig.Mode;
                obBigPrintVolume = obSig.BigPrintVolume;
                obBigPrintDelta = obSig.BigPrintDelta;
                if (OpenTrade(SignalOb, obSig.Side, price, ObOrderSize,
                        ObTakeProfitDollars, ObStopLossDollars, obSig.Block, double.NaN, false))
                {
                    if (obSig.Block != null)
                    {
                        obEngine.MarkTraded(obSig.Block);
                    }
                }
                obBigPrintVolume = 0;
                obBigPrintDelta = 0;
            }

            if (!WallStrategyEnabled || tradeState != TradeState.Flat) { return; }

            // Revenge gets first refusal after a stop-out.
            if (revengeEngine.IsArmed && CanEnter(SignalRevenge))
            {
                if (RevengeCancelOnOppositeWall)
                {
                    foreach (WallInfo w in AllConfirmed())
                    {
                        if (MapSide(w.IsAsk) != revengeEngine.Side
                                && Math.Abs(w.Price - price) <= MaxWallDistanceDollars)
                        {
                            revengeEngine.Cancel();
                            blackBox.Log(nowMs, "REVENGE_CANCELLED", "reason", "OPPOSITE_WALL",
                                    "wallId", w.Id, "wallSide", w.IsAsk ? "ASK" : "BID",
                                    "wallPrice", Round2(w.Price));
                            break;
                        }
                    }
                }
            }
            if (revengeEngine.IsArmed && CanEnter(SignalRevenge))
            {
                if (revengeEngine.OnPrice(price, nowMs))
                {
                    OpenTrade(SignalRevenge, revengeEngine.Side, revengeEngine.LastEntryPrice,
                            OrderSizeContracts, TakeProfitDollars, StopLossDollars,
                            null, double.NaN, true);
                    return;
                }
                if (revengeEngine.PollExpired())
                {
                    blackBox.Log(nowMs, "REVENGE_WINDOW_EXPIRED");
                }
                string cancelled = revengeEngine.PollCancelled();
                if (cancelled != null)
                {
                    blackBox.Log(nowMs, "REVENGE_CANCELLED", "reason", cancelled);
                }
            }

            EnsureTarget(price);

            if (waveTracker.IsArmed && CanEnter(SignalWall))
            {
                WaveSignal sig = waveTracker.OnPrice(price, nowMs);
                if (sig != null)
                {
                    WallInfo wall = FindWall(activeTargetId);
                    int taken;
                    tradesPerWall.TryGetValue(activeTargetId, out taken);
                    if (wall != null && taken < MaxTradesPerWall)
                    {
                        string blockReason = ReentryBlockReason(sig, wall);
                        if (blockReason != null)
                        {
                            blackBox.Log(nowMs, "ENTRY_SKIPPED", "wallId", wall.Id,
                                    "reason", blockReason,
                                    "peakPrice", Round2(sig.PeakPrice),
                                    "prevEntryExtreme", PreviousEntryExtreme(sig, wall));
                        }
                        else if (OpenTrade(SignalWall, sig.Side, sig.EntryPrice, OrderSizeContracts,
                                TakeProfitDollars, StopLossDollars, null, sig.WallPrice, false))
                        {
                            int cur;
                            tradesPerWall.TryGetValue(wall.Id, out cur);
                            tradesPerWall[wall.Id] = cur + 1;
                            RememberEntryExtreme(sig, wall);
                            blackBox.Log(nowMs, "ENTRY_SIGNAL",
                                    "tradeId", currentTradeId,
                                    "side", sig.Side.ToString().ToUpperInvariant(),
                                    "signalPrice", Round2(sig.EntryPrice),
                                    "wallPrice", Round2(sig.WallPrice),
                                    "waveAmplitude", Round2(sig.WaveAmplitude),
                                    "distanceFromWall", Round2(sig.DistanceFromWall),
                                    "peakPrice", Round2(sig.PeakPrice),
                                    "recentExtreme", Round2(sig.RecentExtreme),
                                    "shadow", tradeIsShadow);
                        }
                    }
                }
                else if (waveTracker.LastSkipReason != null)
                {
                    blackBox.Log(nowMs, "ENTRY_SKIPPED", "wallId", activeTargetId,
                            "reason", waveTracker.LastSkipReason,
                            "peakPrice", Round2(waveTracker.LastSkipPeak),
                            "recentExtreme", Round2(waveTracker.LastSkipExtreme));
                    waveTracker.LastSkipReason = null;
                }
            }
        }

        private void EnsureTarget(double price)
        {
            if (activeTargetId != -1 && WallStillActive(activeTargetId)) { return; }
            if (activeTargetId != -1)
            {
                blackBox.Log(nowMs, "TARGET_LOST", "wallId", activeTargetId);
                activeTargetId = -1;
                waveTracker.Disarm();
            }

            var deadIds = tradesPerWall.Keys.Where(id => FindWall(id) == null).ToList();
            foreach (long id in deadIds) { tradesPerWall.Remove(id); }
            spoofLoggedWalls.RemoveWhere(id => FindWall(id) == null);

            WallInfo nearest = null;
            double bestDist = double.MaxValue;
            foreach (WallInfo w in AllConfirmed())
            {
                int taken;
                tradesPerWall.TryGetValue(w.Id, out taken);
                if (taken >= MaxTradesPerWall) { continue; }
                if (SpoofFilterEnabled && w.Size < SpoofExemptWallSize && IsSuspectedSpoof(w.Price))
                {
                    if (spoofLoggedWalls.Add(w.Id))
                    {
                        blackBox.Log(nowMs, "TARGET_SKIPPED_SPOOF", "wallId", w.Id,
                                "side", w.IsAsk ? "ASK" : "BID",
                                "price", Round2(w.Price), "size", w.Size);
                    }
                    continue;
                }
                double d = Math.Abs(w.Price - price);
                if (d <= MaxWallDistanceDollars && d < bestDist)
                {
                    bestDist = d;
                    nearest = w;
                }
            }
            if (nearest == null) { return; }

            BotSide side = MapSide(nearest.IsAsk);
            activeTargetId = nearest.Id;
            waveTracker.Arm(side, nearest.Price, price);
            blackBox.Log(nowMs, "TARGET_SET", "wallId", nearest.Id,
                    "wallSide", nearest.IsAsk ? "ASK" : "BID",
                    "wallPrice", Round2(nearest.Price), "wallSize", nearest.Size,
                    "tradeSide", side.ToString().ToUpperInvariant(),
                    "distance", Round2(bestDist));
        }

        private BotSide MapSide(bool isAskWall)
        {
            bool aboveIsLong = MagnetMode;
            if (isAskWall) { return aboveIsLong ? BotSide.Long : BotSide.Short; }
            return aboveIsLong ? BotSide.Short : BotSide.Long;
        }

        // --- spoof filter ---

        private void RecordPull(double price)
        {
            long level = (long)Math.Round(price / tickSize);
            Queue<long> times;
            if (!pullHistory.TryGetValue(level, out times))
            {
                times = new Queue<long>();
                pullHistory[level] = times;
            }
            times.Enqueue(nowMs);
            while (times.Count > 0 && nowMs - times.Peek() > SpoofWindowMs)
            {
                times.Dequeue();
            }
        }

        private bool IsSuspectedSpoof(double price)
        {
            long level = (long)Math.Round(price / tickSize);
            int count = 0;
            for (long l = level - SpoofToleranceTicks; l <= level + SpoofToleranceTicks; l++)
            {
                Queue<long> times;
                if (!pullHistory.TryGetValue(l, out times)) { continue; }
                foreach (long t in times)
                {
                    if (nowMs - t <= SpoofWindowMs) { count++; }
                }
            }
            return count >= SpoofPullCount;
        }

        // --- same-zone re-entry guard ---

        private string ReentryBlockReason(WaveSignal sig, WallInfo wall)
        {
            if (!ReentryDeeperExtremeEnabled) { return null; }
            double[] prev;
            if (!lastEntryExtremeByLevel.TryGetValue((long)Math.Round(wall.Price / tickSize), out prev))
            {
                return null;
            }
            if (nowMs - (long)prev[1] > ReentryMemoryMs) { return null; }
            if (nowMs - (long)prev[1] < ReentryCooldownMs) { return "SAME_WALL_COOLDOWN"; }
            double newX = SignOf(sig.Side) * sig.PeakPrice;
            if (newX > prev[0] - ReentryMinExtremeAdvanceDollars) { return "SAME_ZONE_REENTRY"; }
            return null;
        }

        private double PreviousEntryExtreme(WaveSignal sig, WallInfo wall)
        {
            double[] prev;
            if (!lastEntryExtremeByLevel.TryGetValue((long)Math.Round(wall.Price / tickSize), out prev))
            {
                return double.NaN;
            }
            return SignOf(sig.Side) * prev[0];
        }

        private void RememberEntryExtreme(WaveSignal sig, WallInfo wall)
        {
            lastEntryExtremeByLevel[(long)Math.Round(wall.Price / tickSize)] =
                    new double[] { SignOf(sig.Side) * sig.PeakPrice, nowMs };
            var stale = lastEntryExtremeByLevel
                    .Where(kv => nowMs - (long)kv.Value[1] > ReentryMemoryMs)
                    .Select(kv => kv.Key).ToList();
            foreach (long k in stale) { lastEntryExtremeByLevel.Remove(k); }
        }

        // =====================================================================
        // Unified trade management (single net position across both engines)
        // =====================================================================

        private bool CanEnter(string engine)
        {
            if (!EntriesAllowed()) { return false; }
            if (tradeState != TradeState.Flat) { return false; }
            long cooldown = engine == SignalOb ? obCooldownUntil : wallCooldownUntil;
            if (nowMs < cooldown) { return false; }
            if (EnableTrading && Position.MarketPosition != MarketPosition.Flat) { return false; }
            return true;
        }

        private bool OpenTrade(string engine, BotSide side, double signalPrice, int qty,
                double tpDollars, double slDollars, ObBlock block, double wallPrice, bool isRevenge)
        {
            tradeIsShadow = !EnableTrading;
            currentTradeId = ++tradeSeq;
            openEngine = engine;
            tradeSide = side;
            tradeBlock = block;
            tradeWallPrice = wallPrice;
            tradeIsRevenge = isRevenge;

            if (engine == SignalOb)
            {
                int blockId = block != null ? (int)block.Id : -1;
                double blockLow = block != null ? block.Low : 0;
                double blockHigh = block != null ? block.High : 0;
                double blockVolume = block != null ? block.Volume : obBigPrintVolume;
                double blockDelta = block != null ? block.Delta : obBigPrintDelta;
                bool reconfirm = block != null && block.Reconfirm;
                blackBox.Log(nowMs, "OB_ENTRY",
                        "obTradeId", currentTradeId,
                        "blockId", blockId,
                        "entryMode", pendingObEntryMode,
                        "side", side.ToString().ToUpperInvariant(),
                        "entryPrice", Round2(signalPrice),
                        "takeProfit", Round2(signalPrice + SignOf(side) * tpDollars),
                        "stopLoss", Round2(signalPrice - SignOf(side) * slDollars),
                        "blockLow", Round2(blockLow),
                        "blockHigh", Round2(blockHigh),
                        "blockVolume", blockVolume,
                        "blockDelta", blockDelta,
                        "reconfirm", reconfirm,
                        "shadow", tradeIsShadow);
            }
            else if (isRevenge)
            {
                blackBox.Log(nowMs, "REVENGE_ENTRY_SIGNAL",
                        "tradeId", currentTradeId,
                        "side", side.ToString().ToUpperInvariant(),
                        "signalPrice", Round2(signalPrice),
                        "adverseExtreme", Round2(revengeEngine.LastAdverseExtreme),
                        "shadow", tradeIsShadow);
            }

            if (tradeIsShadow)
            {
                // Shadow: simulated fill at the signal price, TP/SL watched on ticks.
                tradeEntryPrice = signalPrice;
                tradeTp = signalPrice + SignOf(side) * tpDollars;
                tradeSl = signalPrice - SignOf(side) * slDollars;
                tradeFavorableExtreme = signalPrice;
                obTrailingLocked = false;
                obTrailingActive = false;
                tradeState = TradeState.Open;
                blackBox.Log(nowMs, "ENTRY_FILLED",
                        "tradeId", currentTradeId,
                        "engine", engine,
                        "side", side.ToString().ToUpperInvariant(),
                        "entryPrice", Round2(tradeEntryPrice),
                        "takeProfit", Round2(tradeTp),
                        "stopLoss", Round2(tradeSl),
                        "shadow", true);
                return true;
            }

            // Live: market entry with platform-side bracket (set per signal name).
            tradeState = TradeState.Entering;
            SetProfitTarget(engine, CalculationMode.Ticks, tpDollars / tickSize);
            SetStopLoss(engine, CalculationMode.Ticks, slDollars / tickSize, false);
            if (side == BotSide.Long) { EnterLong(qty, engine); }
            else { EnterShort(qty, engine); }
            return true;
        }

        // =====================================================================
        // Position / trade lifecycle helpers
        // =====================================================================

        /** OB trailing stop: two-tier lock. Tier 1 moves the stop to breakeven
         *  (+ small buffer) once the trade is in profit. Tier 2 starts trailing
         *  at a fixed distance from the most favorable price once a bigger move
         *  is reached. Both tiers only ever tighten the stop, never loosen it. */
        private void UpdateObTrailing(double price)
        {
            if (!ObTrailingEnabled || tradeState != TradeState.Open || openEngine != SignalOb)
                return;
            if (double.IsNaN(tradeEntryPrice) || double.IsNaN(tradeSl))
                return;

            if (tradeSide == BotSide.Long)
                tradeFavorableExtreme = Math.Max(tradeFavorableExtreme, price);
            else
                tradeFavorableExtreme = Math.Min(tradeFavorableExtreme, price);

            double profit = (tradeFavorableExtreme - tradeEntryPrice) * SignOf(tradeSide);
            if (profit < 0) { profit = 0; }
            double newStop = tradeSl;

            // Tier 1: lock at breakeven + buffer once initial profit reached.
            if (profit >= ObTrailingLockDollars && !obTrailingLocked && !obTrailingActive)
            {
                newStop = tradeEntryPrice + SignOf(tradeSide) * ObTrailingLockBufferDollars;
            }

            // Tier 2: trail at fixed distance from the favorable extreme.
            if (profit >= ObTrailingKickInDollars)
            {
                double trailStop = tradeFavorableExtreme - SignOf(tradeSide) * ObTrailingDistanceDollars;
                if (tradeSide == BotSide.Long)
                    newStop = Math.Max(newStop, trailStop);
                else
                    newStop = Math.Min(newStop, trailStop);
            }

            bool canMove = tradeSide == BotSide.Long
                    ? newStop > tradeSl + tickSize * 0.5
                    : newStop < tradeSl - tickSize * 0.5;
            if (!canMove) { return; }

            tradeSl = Round2(newStop);

            if (profit >= ObTrailingKickInDollars && !obTrailingActive)
            {
                obTrailingActive = true;
                tradeTp = tradeEntryPrice + SignOf(tradeSide) * ObTrailingProfitCapDollars;
                if (!tradeIsShadow)
                {
                    SetProfitTarget(SignalOb, CalculationMode.Price, tradeTp);
                }
                blackBox.Log(nowMs, "OB_TRAILING",
                        "obTradeId", currentTradeId,
                        "blockId", tradeBlock != null ? tradeBlock.Id : -1,
                        "stage", "ACTIVE",
                        "stop", tradeSl,
                        "extreme", Round2(tradeFavorableExtreme),
                        "profit", Round2(profit));
            }
            else if (profit >= ObTrailingLockDollars && !obTrailingLocked)
            {
                obTrailingLocked = true;
                blackBox.Log(nowMs, "OB_TRAILING",
                        "obTradeId", currentTradeId,
                        "blockId", tradeBlock != null ? tradeBlock.Id : -1,
                        "stage", "LOCK",
                        "stop", tradeSl,
                        "extreme", Round2(tradeFavorableExtreme),
                        "profit", Round2(profit));
            }

            if (!tradeIsShadow)
            {
                SetStopLoss(SignalOb, CalculationMode.Price, tradeSl, false);
            }
        }

        /** Shadow-mode TP/SL watcher; live exits are handled by real bracket orders. */
        private void ShadowManage(double price)
        {
            if (!tradeIsShadow || tradeState != TradeState.Open) { return; }
            string reason = null;
            if (tradeSide == BotSide.Long)
            {
                if (price >= tradeTp) { reason = "TAKE_PROFIT"; }
                else if (price <= tradeSl) { reason = "STOP_LOSS"; }
            }
            else
            {
                if (price <= tradeTp) { reason = "TAKE_PROFIT"; }
                else if (price >= tradeSl) { reason = "STOP_LOSS"; }
            }
            if (reason != null)
            {
                CloseTrade(price, reason);
            }
        }

        private void CloseTrade(double exitPrice, string reason)
        {
            double pnlPrice = (exitPrice - tradeEntryPrice) * SignOf(tradeSide);
            int qty = openEngine == SignalOb ? ObOrderSize : OrderSizeContracts;
            double pnlDollars = pnlPrice * pointValue * qty;
            bool win = pnlPrice > 0;

            if (openEngine == SignalOb)
            {
                if (win) { obWins++; } else { obLosses++; }
                obNetPnl += pnlDollars;
                if (win) { dayObWins++; } else { dayObLosses++; }
                dayObPnl += pnlDollars;
                string mode = pendingObEntryMode ?? "UNKNOWN";
                double[] ms;
                if (!dayModeStats.TryGetValue(mode, out ms))
                {
                    ms = new double[3];
                    dayModeStats[mode] = ms;
                }
                ms[0]++;
                if (win) { ms[1]++; }
                ms[2] += pnlDollars;
                blackBox.Log(nowMs, "OB_TRADE_CLOSED",
                        "obTradeId", currentTradeId,
                        "blockId", tradeBlock != null ? tradeBlock.Id : -1,
                        "side", tradeSide.ToString().ToUpperInvariant(),
                        "entryPrice", Round2(tradeEntryPrice),
                        "exitPrice", Round2(exitPrice),
                        "reason", reason,
                        "pnlPrice", Round2(pnlPrice),
                        "pnlDollars", Round2(pnlDollars),
                        "obWins", obWins,
                        "obLosses", obLosses,
                        "obNetPnlDollars", Round2(obNetPnl),
                        "shadow", tradeIsShadow);
                obCooldownUntil = nowMs + ObCooldownMs;
            }
            else
            {
                if (win) { wallWins++; } else { wallLosses++; }
                wallNetPnl += pnlDollars;
                if (win) { dayWallWins++; } else { dayWallLosses++; }
                dayWallPnl += pnlDollars;
                blackBox.Log(nowMs, "TRADE_CLOSED",
                        "tradeId", currentTradeId,
                        "engine", openEngine,
                        "side", tradeSide.ToString().ToUpperInvariant(),
                        "entryPrice", Round2(tradeEntryPrice),
                        "exitPrice", Round2(exitPrice),
                        "wallPrice", Round2(tradeWallPrice),
                        "reason", reason,
                        "pnlPrice", Round2(pnlPrice),
                        "pnlDollars", Round2(pnlDollars),
                        "win", win,
                        "wins", wallWins,
                        "losses", wallLosses,
                        "netPnlDollars", Round2(wallNetPnl),
                        "revenge", tradeIsRevenge,
                        "shadow", tradeIsShadow);
                wallCooldownUntil = nowMs + CooldownMsAfterTrade;

                // Revenge wiring: a stop-out arms the engine, a win clears it.
                if (reason == "STOP_LOSS")
                {
                    if (!tradeIsRevenge) { revengeEngine.Reset(); }
                    revengeEngine.Arm(tradeSide, tradeEntryPrice, exitPrice, nowMs);
                    if (revengeEngine.IsArmed)
                    {
                        blackBox.Log(nowMs, "REVENGE_ARMED",
                                "side", tradeSide.ToString().ToUpperInvariant(),
                                "entryPrice", Round2(tradeEntryPrice),
                                "stopPrice", Round2(exitPrice));
                    }
                }
                else
                {
                    revengeEngine.Reset();
                }
            }

            tradeState = TradeState.Flat;
            openEngine = null;
            tradeBlock = null;
        }

        // =====================================================================
        // Live order/fill callbacks
        // =====================================================================

        protected override void OnExecutionUpdate(Execution execution, string executionId,
                double price, int quantity, MarketPosition marketPosition,
                string orderId, DateTime time)
        {
            if (tradeIsShadow || execution.Order == null) { return; }
            nowMs = EventMs(time);
            string name = execution.Order.Name;

            if (tradeState == TradeState.Entering
                    && (name == SignalWall || name == SignalOb || name == SignalRevenge)
                    && execution.Order.OrderState == OrderState.Filled)
            {
                // Real fill: NOW the trade is open, at the actual fill price.
                tradeEntryPrice = execution.Order.AverageFillPrice;
                double tp = openEngine == SignalOb ? ObTakeProfitDollars : TakeProfitDollars;
                double sl = openEngine == SignalOb ? ObStopLossDollars : StopLossDollars;
                tradeTp = tradeEntryPrice + SignOf(tradeSide) * tp;
                tradeSl = tradeEntryPrice - SignOf(tradeSide) * sl;
                tradeFavorableExtreme = tradeEntryPrice;
                obTrailingLocked = false;
                obTrailingActive = false;
                tradeState = TradeState.Open;
                blackBox.Log(nowMs, "ENTRY_FILLED",
                        "tradeId", currentTradeId,
                        "engine", openEngine,
                        "side", tradeSide.ToString().ToUpperInvariant(),
                        "entryPrice", Round2(tradeEntryPrice),
                        "takeProfit", Round2(tradeTp),
                        "stopLoss", Round2(tradeSl),
                        "shadow", false);
                return;
            }

            if (tradeState == TradeState.Open
                    && (name == "Profit target" || name == "Stop loss")
                    && execution.Order.OrderState == OrderState.Filled)
            {
                string reason = name == "Profit target" ? "TAKE_PROFIT" : "STOP_LOSS";
                CloseTrade(execution.Order.AverageFillPrice, reason);
            }
        }

        protected override void OnOrderUpdate(Order order, double limitPrice, double stopPrice,
                int quantity, int filled, double averageFillPrice,
                OrderState orderState, DateTime time, ErrorCode error, string comment)
        {
            if (tradeIsShadow) { return; }
            if (orderState == OrderState.Rejected)
            {
                nowMs = EventMs(time);
                blackBox.Log(nowMs, "ORDER_REJECTED",
                        "orderName", order.Name,
                        "error", error.ToString(),
                        "comment", comment ?? "");
                if (tradeState == TradeState.Entering
                        && (order.Name == SignalWall || order.Name == SignalOb
                            || order.Name == SignalRevenge))
                {
                    // Entry rejected: back to flat, nothing was opened.
                    tradeState = TradeState.Flat;
                    openEngine = null;
                    tradeBlock = null;
                }
            }
        }

        // =====================================================================
        // OB logging hooks (called from the engine)
        // =====================================================================

        private void LogZoneCandidate(ObBlock b, long now)
        {
            blackBox.Log(now, "OB_ZONE_CANDIDATE",
                    "blockId", b.Id,
                    "low", Round2(b.Low), "high", Round2(b.High),
                    "volume", b.Volume, "delta", b.Delta,
                    "reconfirm", b.Reconfirm);
        }

        private void LogZoneRejected(double low, double high, double volume, double delta,
                string reason, long now)
        {
            blackBox.Log(now, "OB_ZONE_REJECTED",
                    "low", Round2(low), "high", Round2(high),
                    "volume", volume, "delta", delta, "reason", reason);
        }

        private void LogBlockConfirmed(ObBlock b, long now)
        {
            blackBox.Log(now, "OB_BLOCK_CONFIRMED",
                    "blockId", b.Id,
                    "side", b.Side.ToString().ToUpperInvariant(),
                    "low", Round2(b.Low), "high", Round2(b.High),
                    "volume", b.Volume, "delta", b.Delta,
                    "reconfirm", b.Reconfirm);
        }

        private void LogBlockDead(ObBlock b, long now, string reason)
        {
            blackBox.Log(now, "OB_BLOCK_DEAD",
                    "blockId", b.Id,
                    "side", b.Confirmed ? b.Side.ToString().ToUpperInvariant() : "UNCONFIRMED",
                    "low", Round2(b.Low), "high", Round2(b.High),
                    "reason", reason);
        }

        private void LogObEntrySkipped(ObBlock b, double price, double approachMove, long now)
        {
            blackBox.Log(now, "OB_ENTRY_SKIPPED",
                    "blockId", b.Id,
                    "side", b.Side.ToString().ToUpperInvariant(),
                    "price", Round2(price),
                    "reason", "FAST_APPROACH",
                    "approachMove", Round2(approachMove));
        }
    }
}
