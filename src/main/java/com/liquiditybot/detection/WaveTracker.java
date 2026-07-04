package com.liquiditybot.detection;

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
    public double lastSkipPeak = Double.NaN;
    public double lastSkipExtreme = Double.NaN;

    private final Settings settings;
    private final SwingMemory swings;

    private boolean active = false;
    private TradeSide side;
    private int sign;
    private double wallX;

    // Extremes in transformed coordinates.
    private double swingMaxX;   // closest approach toward the wall this leg
    private double troughX;     // farthest pull-back away from the wall (the peak)
    private boolean fired;      // already signalled for the current trough

    public WaveTracker(Settings settings, SwingMemory swings) {
        this.settings = settings;
        this.swings = swings;
    }

    /**
     * Start (or restart) watching a wall. If the new target is effectively the
     * same wall as the one we were just tracking (same side, near-same price),
     * the wave state is resumed instead of reset - a wall that flickers
     * broken/re-confirmed (its size breathing around the threshold) must not
     * keep erasing the pull-back progress accumulated toward it.
     */
    public void arm(TradeSide side, double wallPrice, double currentPrice) {
        double newWallX = side.sign * wallPrice;
        if (this.side == side
                && Math.abs(this.wallX - newWallX) <= settings.waveResumeToleranceDollars) {
            this.wallX = newWallX;
            this.active = true;
            return;
        }
        this.active = true;
        this.side = side;
        this.sign = side.sign;
        this.wallX = newWallX;
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
            // New closest approach toward the wall: a fresh leg begins.
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
        double peakPrice = sign * troughX;                 // the extreme we turned at
        // Established recent extreme in the "away from wall" direction: for a
        // short (wall below) that is the highest recent pivot high; for a long it
        // is the lowest recent pivot low.
        double recentExtreme = side == TradeSide.SHORT
                ? swings.recentHigh(nowMs)
                : swings.recentLow(nowMs);

        if (settings.peakFilterEnabled) {
            if (Double.isNaN(recentExtreme)) {
                // No confirmed pivots in the lookback window: we cannot tell an
                // established extreme from a runaway move. Either stand aside,
                // or (when allowed) demand a deeper turn back toward the wall
                // as a substitute for the missing history.
                if (!settings.peakNoHistoryEntryEnabled) {
                    fired = true;
                    lastSkipReason = "NO_PEAK_HISTORY";
                    lastSkipPeak = peakPrice;
                    lastSkipExtreme = Double.NaN;
                    return null;
                }
                boolean strongTurn = x >= troughX + sign0(settings.turnConfirmDollars
                        + settings.peakNoHistoryExtraConfirmDollars);
                if (!strongTurn) {
                    // Not fired: keep watching this trough until the deeper
                    // turn confirms (or a deeper trough resets the leg).
                    return null;
                }
            } else {
                // A runaway breakout means this peak pushed past every comparable
                // recent extreme by more than the tolerance (short: a new high; long:
                // a new low). That is momentum against us -> skip.
                // Away-from-wall is the decreasing-x direction, so the peak is more
                // extreme than the reference when its x is LOWER: sign*(extreme-peak).
                double breakout = sign * (recentExtreme - peakPrice); // >0 => new, more-extreme peak
                if (breakout > settings.peakBreakoutToleranceDollars) {
                    fired = true; // don't re-fire on this same trough
                    lastSkipReason = "BREAKOUT_PEAK";
                    lastSkipPeak = peakPrice;
                    lastSkipExtreme = recentExtreme;
                    return null;
                }
            }
        }

        fired = true;
        lastSkipReason = null;
        return new EntrySignal(side, price, sign * wallX, amplitude, distanceFromWall,
                peakPrice, recentExtreme);
    }

    private static double sign0(double v) {
        return Math.max(0.0, v);
    }
}
