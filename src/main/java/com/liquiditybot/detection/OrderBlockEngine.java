package com.liquiditybot.detection;

import com.liquiditybot.config.Settings;
import com.liquiditybot.trade.TradeSide;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Order-block engine, fully independent of the wall/wave strategy.
 *
 * <p>Bookmap's raw tape tells us exactly where real orders EXECUTED and who the
 * aggressor was. An order block is found purely from that data - no candle
 * bodies, no ICT concepts:
 *
 * <ol>
 *   <li><b>Accumulation:</b> a narrow price zone absorbs a large executed
 *       volume within a short window, with the aggressor delta clearly leaning
 *       one way (institutions filling a position).</li>
 *   <li><b>Displacement:</b> price then leaves the zone impulsively in the
 *       direction of that delta. The zone is now a confirmed order block:
 *       bullish if the move was up, bearish if down.</li>
 *   <li><b>Revisit:</b> when price later returns into the zone, the engine
 *       fires an entry in the direction of the original displacement (long off
 *       a bullish block below, short off a bearish block above).</li>
 * </ol>
 *
 * <p>A block dies when it is traded, when price violates it (goes through the
 * far side), or when it gets too old.
 */
public class OrderBlockEngine {

    public static final class Block {
        public final long id;
        public final double low;        // zone bounds (real prices)
        public final double high;
        public final double volume;     // executed contracts inside the zone window
        public final double delta;      // aggressorBuy - aggressorSell inside the window
        public final long formedAt;
        public TradeSide side;          // set at confirmation (LONG = bullish block)
        public boolean confirmed = false;
        public boolean leftZone = false; // price moved away after confirmation
        public boolean reconfirm = false; // formed on a recently violated level
        public int tradesTaken = 0;
        long lastFastSkipMs = 0;

        Block(long id, double low, double high, double volume, double delta, long formedAt) {
            this.id = id;
            this.low = low;
            this.high = high;
            this.volume = volume;
            this.delta = delta;
            this.formedAt = formedAt;
        }

        public double center() {
            return (low + high) / 2.0;
        }
    }

    public static final class EntrySignal {
        public final Block block;
        public final double price;

        EntrySignal(Block block, double price) {
            this.block = block;
            this.price = price;
        }
    }

    /** Listener for diagnostics (block lifecycle events). */
    public interface Listener {
        void onZoneCandidate(Block b, long now);
        void onZoneRejected(double low, double high, double volume, double delta,
                            String reason, long now);
        void onBlockConfirmed(Block b, long now);
        void onBlockDead(Block b, long now, String reason);
        void onEntrySkipped(Block b, double price, double approachMove, long now);
    }

    private static final class Exec {
        final double price;
        final int size;
        final int deltaSign; // +1 buy aggressor, -1 sell aggressor
        final long time;

        Exec(double price, int size, int deltaSign, long time) {
            this.price = price;
            this.size = size;
            this.deltaSign = deltaSign;
            this.time = time;
        }
    }

    private final Settings settings;
    private final double pips;
    private Listener listener;

    private final ArrayDeque<Exec> recentExecs = new ArrayDeque<>();
    private final List<Block> blocks = new ArrayList<>();
    private long blockSeq = 0;
    private long lastZoneScanMs = 0;

    // Rejection log dedupe: last rejected level and when, so a zone failing the
    // same check on every scan is reported once per window instead of spamming.
    private long lastRejectLevel = Long.MIN_VALUE;
    private long lastRejectMs = 0;

    // Approach-speed filter: recent price path used to measure how fast price
    // came into a zone.
    private final ArrayDeque<double[]> pricePath = new ArrayDeque<>(); // {price, time}

    // Re-confirmation memory: violated block areas as {low, high, expiry}.
    private final List<double[]> penalizedAreas = new ArrayList<>();

    public OrderBlockEngine(Settings settings, double pips) {
        this.settings = settings;
        this.pips = pips;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public List<Block> activeBlocks() {
        return blocks;
    }

    /** Feed every executed trade from the tape. */
    public void onExecution(double price, int size, boolean isBidAggressor, long nowMs) {
        if (!settings.obEnabled) {
            return;
        }
        recentExecs.addLast(new Exec(price, size, isBidAggressor ? 1 : -1, nowMs));
        long horizon = nowMs - settings.obZoneWindowMs;
        while (!recentExecs.isEmpty() && recentExecs.peekFirst().time < horizon) {
            recentExecs.removeFirst();
        }
        if (nowMs - lastZoneScanMs >= settings.obScanThrottleMs) {
            lastZoneScanMs = nowMs;
            scanForZone(nowMs);
        }
    }

    /**
     * Feed every price update. Handles confirmation (displacement), expiry,
     * invalidation and revisit entries. Returns a signal when a block is hit.
     */
    public EntrySignal onPrice(double price, long nowMs) {
        if (!settings.obEnabled) {
            return null;
        }
        recordPricePath(price, nowMs);
        EntrySignal signal = null;
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();

            if (nowMs - b.formedAt > settings.obMaxAgeMs) {
                notifyDead(b, nowMs, "EXPIRED");
                it.remove();
                continue;
            }

            if (!b.confirmed) {
                // Waiting for displacement away from the zone in the delta direction.
                if (nowMs - b.formedAt > settings.obDisplacementWindowMs) {
                    notifyDead(b, nowMs, "NO_DISPLACEMENT");
                    it.remove();
                    continue;
                }
                // A block on a recently violated level must prove itself with a
                // bigger displacement (flexible re-confirmation, not a ban).
                double disp = b.reconfirm
                        ? settings.obDisplacementDollars * settings.obReconfirmDisplacementMult
                        : settings.obDisplacementDollars;
                boolean up = price >= b.high + disp;
                boolean down = price <= b.low - disp;
                if (up && b.delta > 0) {
                    b.side = TradeSide.LONG;
                    b.confirmed = true;
                    b.leftZone = true;
                    if (listener != null) {
                        listener.onBlockConfirmed(b, nowMs);
                    }
                } else if (down && b.delta < 0) {
                    b.side = TradeSide.SHORT;
                    b.confirmed = true;
                    b.leftZone = true;
                    if (listener != null) {
                        listener.onBlockConfirmed(b, nowMs);
                    }
                } else if (up || down) {
                    // Displaced against its own delta: not an order block.
                    notifyDead(b, nowMs, "DELTA_MISMATCH");
                    it.remove();
                }
                continue;
            }

            // Confirmed block: invalidation when price goes through the far side.
            boolean violated = b.side == TradeSide.LONG
                    ? price < b.low - settings.obInvalidationDollars
                    : price > b.high + settings.obInvalidationDollars;
            if (violated) {
                penalize(b, nowMs);
                notifyDead(b, nowMs, "VIOLATED");
                it.remove();
                continue;
            }

            if (b.tradesTaken >= settings.obMaxTradesPerBlock) {
                continue;
            }

            // Revisit: price back inside the zone (within tolerance).
            boolean inside = price >= b.low - settings.obEntryToleranceDollars
                    && price <= b.high + settings.obEntryToleranceDollars;
            if (b.leftZone && inside && signal == null) {
                if (isFastApproach(b, price, nowMs)) {
                    if (listener != null && nowMs - b.lastFastSkipMs >= settings.obApproachWindowMs) {
                        b.lastFastSkipMs = nowMs;
                        listener.onEntrySkipped(b, price, approachMove(nowMs), nowMs);
                    }
                    continue;
                }
                signal = new EntrySignal(b, price);
            }
        }
        return signal;
    }

    /** Mark a block as traded (called when the entry actually fires). */
    public void markTraded(Block b) {
        b.tradesTaken++;
        b.leftZone = false; // must leave and return again for another trade
    }

    // --- zone building ---------------------------------------------------------

    private void scanForZone(long nowMs) {
        if (recentExecs.isEmpty()) {
            return;
        }
        // Bucket the recent tape into levels, then find the heaviest zone of
        // obZoneTicks around any level.
        Map<Long, double[]> perLevel = new HashMap<>(); // level -> {vol, delta}
        for (Exec e : recentExecs) {
            long level = Math.round(e.price / pips);
            double[] agg = perLevel.computeIfAbsent(level, k -> new double[2]);
            agg[0] += e.size;
            agg[1] += e.size * e.deltaSign;
        }
        long bestLevel = 0;
        double bestVol = 0;
        double bestDelta = 0;
        for (Map.Entry<Long, double[]> c : perLevel.entrySet()) {
            double vol = 0;
            double delta = 0;
            for (long l = c.getKey() - settings.obZoneTicks; l <= c.getKey() + settings.obZoneTicks; l++) {
                double[] agg = perLevel.get(l);
                if (agg != null) {
                    vol += agg[0];
                    delta += agg[1];
                }
            }
            if (vol > bestVol) {
                bestVol = vol;
                bestDelta = delta;
                bestLevel = c.getKey();
            }
        }
        if (bestVol < settings.obMinZoneVolume) {
            return;
        }
        double low = (bestLevel - settings.obZoneTicks) * pips;
        double high = (bestLevel + settings.obZoneTicks) * pips;
        boolean reconfirm = settings.obReconfirmEnabled && isPenalized(low, high, nowMs);
        double minRatio = reconfirm ? settings.obReconfirmDeltaRatio : settings.obMinDeltaRatio;
        if (Math.abs(bestDelta) < bestVol * minRatio) {
            // Volume heavy but two-sided: absorption fight, not a clean block.
            rejectOnce(bestLevel, low, high, bestVol, bestDelta,
                    reconfirm ? "RECONFIRM_DELTA" : "DELTA_TWO_SIDED", nowMs);
            return;
        }

        // Don't stack duplicates on the same area.
        for (Block b : blocks) {
            if (low <= b.high && high >= b.low) {
                return;
            }
        }
        if (blocks.size() >= settings.obMaxActiveBlocks) {
            return;
        }
        Block b = new Block(++blockSeq, low, high, bestVol, bestDelta, nowMs);
        b.reconfirm = reconfirm;
        blocks.add(b);
        if (listener != null) {
            listener.onZoneCandidate(b, nowMs);
        }
        // The forming tape is consumed so the same prints don't build a second zone.
        recentExecs.clear();
    }

    private void rejectOnce(long level, double low, double high, double volume, double delta,
                            String reason, long nowMs) {
        if (listener == null) {
            return;
        }
        if (level == lastRejectLevel && nowMs - lastRejectMs < settings.obZoneWindowMs) {
            return;
        }
        lastRejectLevel = level;
        lastRejectMs = nowMs;
        listener.onZoneRejected(low, high, volume, delta, reason, nowMs);
    }

    private void notifyDead(Block b, long nowMs, String reason) {
        if (listener != null) {
            listener.onBlockDead(b, nowMs, reason);
        }
    }

    // --- approach-speed filter ---------------------------------------------------

    private void recordPricePath(double price, long nowMs) {
        pricePath.addLast(new double[]{price, nowMs});
        long horizon = nowMs - settings.obApproachWindowMs;
        while (!pricePath.isEmpty() && pricePath.peekFirst()[1] < horizon) {
            pricePath.removeFirst();
        }
    }

    /** Net move over the lookback window (signed, current minus oldest). */
    private double approachMove(long nowMs) {
        if (pricePath.size() < 2) {
            return 0;
        }
        return pricePath.peekLast()[0] - pricePath.peekFirst()[0];
    }

    /**
     * True when price reached the block by slicing toward it faster than the
     * configured limit: a long block hit by a fast drop (or a short block hit
     * by a fast rally) is a waterfall, not a controlled retest.
     */
    private boolean isFastApproach(Block b, double price, long nowMs) {
        if (!settings.obApproachFilterEnabled) {
            return false;
        }
        double move = approachMove(nowMs);
        return b.side == TradeSide.LONG
                ? move <= -settings.obMaxApproachDollars
                : move >= settings.obMaxApproachDollars;
    }

    // --- re-confirmation memory ----------------------------------------------------

    private void penalize(Block b, long nowMs) {
        if (!settings.obReconfirmEnabled) {
            return;
        }
        penalizedAreas.add(new double[]{b.low, b.high, nowMs + settings.obReconfirmMemoryMs});
    }

    private boolean isPenalized(double low, double high, long nowMs) {
        boolean hit = false;
        Iterator<double[]> it = penalizedAreas.iterator();
        while (it.hasNext()) {
            double[] a = it.next();
            if (nowMs > a[2]) {
                it.remove();
            } else if (low <= a[1] && high >= a[0]) {
                hit = true;
            }
        }
        return hit;
    }
}
