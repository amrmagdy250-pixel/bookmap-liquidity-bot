package com.liquiditybot.detection;

import java.util.ArrayDeque;
import java.util.Deque;

import com.liquiditybot.config.Settings;

/**
 * Continuous swing (pivot) memory fed on every price tick, independent of any
 * wall or trade. It records confirmed swing highs and lows using a simple
 * zig-zag: an extreme becomes a pivot only once price reverses away from it by
 * {@code pivotReversalDollars}. Recent pivots (within {@code peakLookbackMs})
 * are kept so the strategy can judge whether a fresh turn is happening at an
 * <i>established</i> peak/trough or is a runaway breakout into new territory.
 *
 * <p>Keeping this separate from {@link WaveTracker} is deliberate: the wave
 * tracker is armed/re-armed as the target wall changes, whereas peak history
 * must survive across those changes so it is always populated.
 */
public class SwingMemory {

    private static final class Pivot {
        final long time;
        final double price;
        final boolean high;

        Pivot(long time, double price, boolean high) {
            this.time = time;
            this.price = price;
            this.high = high;
        }
    }

    private final Settings settings;
    private final Deque<Pivot> pivots = new ArrayDeque<>();

    private boolean started = false;
    private int dir = 0;              // +1 rising leg, -1 falling leg, 0 unknown
    private double legExtreme;        // running high (dir>0) or low (dir<0)

    public SwingMemory(Settings settings) {
        this.settings = settings;
    }

    /** Feed one price sample. */
    public void onPrice(double price, long nowMs) {
        if (!started) {
            started = true;
            legExtreme = price;
            dir = 0;
            return;
        }
        double rev = Math.max(0.01, settings.pivotReversalDollars);

        if (dir >= 0 && price > legExtreme) {
            legExtreme = price;                 // extend the rising leg
        } else if (dir <= 0 && price < legExtreme) {
            legExtreme = price;                 // extend the falling leg
        }

        if (dir >= 0 && legExtreme - price >= rev) {
            // reversed down from a high -> the high is a confirmed pivot high
            if (dir > 0) {
                addPivot(nowMs, legExtreme, true);
            }
            dir = -1;
            legExtreme = price;
        } else if (dir <= 0 && price - legExtreme >= rev) {
            // reversed up from a low -> the low is a confirmed pivot low
            if (dir < 0) {
                addPivot(nowMs, legExtreme, false);
            }
            dir = 1;
            legExtreme = price;
        } else if (dir == 0) {
            dir = price >= legExtreme ? 1 : -1;
        }
        prune(nowMs);
    }

    /** Highest confirmed pivot high in the look-back window, or NaN if none. */
    public double recentHigh(long nowMs) {
        prune(nowMs);
        double max = Double.NaN;
        for (Pivot p : pivots) {
            if (p.high && (Double.isNaN(max) || p.price > max)) {
                max = p.price;
            }
        }
        return max;
    }

    /** Lowest confirmed pivot low in the look-back window, or NaN if none. */
    public double recentLow(long nowMs) {
        prune(nowMs);
        double min = Double.NaN;
        for (Pivot p : pivots) {
            if (!p.high && (Double.isNaN(min) || p.price < min)) {
                min = p.price;
            }
        }
        return min;
    }

    /** Wipe all pivots and leg state for a fresh session. */
    public void reset() {
        pivots.clear();
        started = false;
        dir = 0;
        legExtreme = 0;
    }

    private void addPivot(long nowMs, double price, boolean high) {
        pivots.addLast(new Pivot(nowMs, price, high));
    }

    private void prune(long nowMs) {
        while (!pivots.isEmpty() && nowMs - pivots.peekFirst().time > settings.peakLookbackMs) {
            pivots.removeFirst();
        }
    }
}
