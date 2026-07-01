package com.liquiditybot.detection;

import java.util.ArrayDeque;
import java.util.Deque;

import com.liquiditybot.config.Settings;
import com.liquiditybot.trade.TradeSide;

/**
 * Decides WHEN to enter toward an active wall.
 *
 * <p>This is the heart of the "don't fall into the trap" idea. Price routinely
 * pokes toward a wall by a few dollars, then snaps 10-20 dollars away, then
 * returns. Entering the moment price nears the wall gets stopped out repeatedly.
 *
 * <p>Instead we wait for a genuine <b>wave</b>: price must pull away from the
 * wall by at least {@code waveMinAmplitudeDollars}, and we enter only at the far
 * turning point of that wave - as price turns back toward the wall - and only if
 * that turning point sits a sensible distance from the wall. This way the
 * oscillation becomes the entry edge instead of a hazard, and the wide stop
 * comfortably covers the remaining noise.
 *
 * <p><b>Smart peak filter (sniper):</b> on top of that, we look at the recent
 * peaks/troughs. We refuse to enter into a fresh runaway breakout (a brand-new
 * extreme with nothing beyond it), because that is momentum against us and is
 * exactly what produced the single stop-out. We only fire when price turns at or
 * before an <i>established</i> recent extreme, where the pull back toward the
 * wall is far more likely.
 *
 * <p>The maths is done in a transformed coordinate {@code x = sign * price} so
 * "toward the wall" is always the increasing direction, regardless of whether
 * the wall is above (long) or below (short). In that space the far turning point
 * (the "peak" we sell/buy at) is always the <b>minimum</b> x of the leg.
 */
public class WaveTracker {

    public static final class EntrySignal {
        public final TradeSide side;
        public final double entryPrice;
        public final double wallPrice;
        public final double waveAmplitude;
        public final double distanceFromWall;
        public final double peakPrice;         // the extreme price we turned at
        public final double recentExtreme;     // best comparable recent extreme (NaN if none)

        EntrySignal(TradeSide side, double entryPrice, double wallPrice,
                    double waveAmplitude, double distanceFromWall,
                    double peakPrice, double recentExtreme) {
            this.side = side;
            this.entryPrice = entryPrice;
            this.wallPrice = wallPrice;
            this.waveAmplitude = waveAmplitude;
            this.distanceFromWall = distanceFromWall;
            this.peakPrice = peakPrice;
            this.recentExtreme = recentExtreme;
        }
    }

    /** Reason the most recent turn was rejected, for diagnostics. */
    public String lastSkipReason = null;

    private final Settings settings;

    private boolean active = false;
    private TradeSide side;
    private int sign;
    private double wallX;

    // Extremes in transformed coordinates.
    private double swingMaxX;   // closest approach toward the wall this leg
    private double troughX;     // farthest pull-back away from the wall (the peak)
    private boolean fired;      // already signalled for the current trough

    // Recent finalised peaks (the away-extreme of each completed leg), stored as
    // transformed-x with their data-time, for the "established extreme" check.
    private final Deque<long[]> recentPeaks = new ArrayDeque<>(); // [timeMs, doubleToLongBits(x)]

    public WaveTracker(Settings settings) {
        this.settings = settings;
    }

    /** Start (or restart) watching a wall. */
    public void arm(TradeSide side, double wallPrice, double currentPrice) {
        this.active = true;
        this.side = side;
        this.sign = side.sign;
        this.wallX = sign * wallPrice;
        double x = sign * currentPrice;
        this.swingMaxX = x;
        this.troughX = x;
        this.fired = false;
    }

    public void disarm() {
        this.active = false;
    }

    public boolean isArmed() {
        return active;
    }

    /**
     * Feed a new price. Returns an {@link EntrySignal} when all conditions line
     * up, otherwise null.
     */
    public EntrySignal onPrice(double price, long nowMs) {
        if (!active) {
            return null;
        }
        double x = sign * price;

        if (x > swingMaxX) {
            // New closest approach toward the wall: the previous leg's far point
            // is now a finalised peak, and a fresh leg begins.
            recordPeak(nowMs, troughX);
            swingMaxX = x;
            troughX = x;
            fired = false;
            return null;
        }

        if (x < troughX) {
            troughX = x;
            fired = false; // deeper pull-back: allow a new entry at the new extreme
        }

        if (fired) {
            return null;
        }

        double amplitude = swingMaxX - troughX;          // size of the pull-back
        double distanceFromWall = wallX - troughX;        // how far the trough is from the wall
        boolean turned = x >= troughX + sign0(settings.turnConfirmDollars);

        boolean amplitudeOk = amplitude >= settings.waveMinAmplitudeDollars;
        boolean distanceOk = distanceFromWall >= settings.minEntryDistanceFromWallDollars
                && distanceFromWall <= settings.maxEntryDistanceFromWallDollars;

        if (!(amplitudeOk && distanceOk && turned)) {
            return null;
        }

        // --- smart peak filter ------------------------------------------------
        double recentExtremeX = recentExtremeX(nowMs);
        double recentExtremePrice = Double.isNaN(recentExtremeX) ? Double.NaN : sign * recentExtremeX;
        if (settings.peakFilterEnabled && !Double.isNaN(recentExtremeX)) {
            // A runaway breakout means this leg pushed the away-extreme well past
            // every comparable recent extreme (troughX below the recent minimum x
            // by more than the tolerance). That is momentum against us -> skip.
            double breakout = recentExtremeX - troughX; // > 0 means new, more-extreme peak
            if (breakout > settings.peakBreakoutToleranceDollars) {
                fired = true; // don't re-fire on this same trough
                lastSkipReason = "BREAKOUT_PEAK";
                return null;
            }
        }

        fired = true;
        lastSkipReason = null;
        double peakPrice = sign * troughX;
        return new EntrySignal(side, price, sign * wallX, amplitude, distanceFromWall,
                peakPrice, recentExtremePrice);
    }

    /** Record a finalised leg peak and prune old ones. */
    private void recordPeak(long nowMs, double peakX) {
        recentPeaks.addLast(new long[] {nowMs, Double.doubleToLongBits(peakX)});
        pruneOld(nowMs);
    }

    /**
     * The most "away from wall" extreme among recent finalised peaks, in x space
     * (i.e. the minimum x). Returns NaN when there is no history yet.
     */
    private double recentExtremeX(long nowMs) {
        pruneOld(nowMs);
        double min = Double.NaN;
        for (long[] e : recentPeaks) {
            double x = Double.longBitsToDouble(e[1]);
            if (Double.isNaN(min) || x < min) {
                min = x;
            }
        }
        return min;
    }

    private void pruneOld(long nowMs) {
        while (!recentPeaks.isEmpty()
                && nowMs - recentPeaks.peekFirst()[0] > settings.peakLookbackMs) {
            recentPeaks.removeFirst();
        }
    }

    private static double sign0(double v) {
        return Math.max(0.0, v);
    }
}
