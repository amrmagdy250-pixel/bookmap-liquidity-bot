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
 * <p>The maths is done in a transformed coordinate {@code x = sign * price} so
 * "toward the wall" is always the increasing direction, regardless of whether
 * the wall is above (long) or below (short).
 */
public class WaveTracker {

    public static final class EntrySignal {
        public final TradeSide side;
        public final double entryPrice;
        public final double wallPrice;
        public final double waveAmplitude;
        public final double distanceFromWall;

        EntrySignal(TradeSide side, double entryPrice, double wallPrice,
                    double waveAmplitude, double distanceFromWall) {
            this.side = side;
            this.entryPrice = entryPrice;
            this.wallPrice = wallPrice;
            this.waveAmplitude = waveAmplitude;
            this.distanceFromWall = distanceFromWall;
        }
    }

    private final Settings settings;

    private boolean active = false;
    private TradeSide side;
    private int sign;
    private double wallX;

    // Extremes in transformed coordinates.
    private double swingMaxX;   // closest approach toward the wall this leg
    private double troughX;     // farthest pull-back away from the wall
    private boolean fired;      // already signalled for the current trough

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
    public EntrySignal onPrice(double price) {
        if (!active) {
            return null;
        }
        double x = sign * price;

        if (x > swingMaxX) {
            // New closest approach toward the wall: a fresh leg begins, so the
            // pull-back reference resets and we re-arm for the next wave.
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

        if (amplitudeOk && distanceOk && turned) {
            fired = true;
            return new EntrySignal(side, price, sign * wallX, amplitude, distanceFromWall);
        }
        return null;
    }

    private static double sign0(double v) {
        return Math.max(0.0, v);
    }
}
