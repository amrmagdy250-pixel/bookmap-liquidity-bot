package com.liquiditybot.detection;

import com.liquiditybot.config.Settings;
import com.liquiditybot.trade.TradeSide;

/**
 * Post-stop recovery ("revenge") logic, kept as a separate engine with a single
 * responsibility: after a trade is stopped out, watch the adverse excursion and
 * fire exactly one recovery entry when price starts correcting back.
 *
 * <p>The idea: a stop-out usually happens in the middle of a sharp move against
 * us. Rather than immediately re-entering (and getting run over again), we let
 * that move exhaust, note its extreme, and only re-enter in the <b>same
 * direction as the original target wall</b> once price has turned back toward
 * that direction by a confirmation amount. The correction is the edge.
 *
 * <p>All maths is done in a transformed coordinate {@code x = sign * price} so
 * "the original profitable direction" is always increasing x, and the adverse
 * direction (where the stop pushed us) is always decreasing x - regardless of
 * whether the stopped trade was long or short.
 */
public class RevengeEngine {

    private final Settings settings;

    private boolean active = false;
    private TradeSide side;
    private int sign;
    private double entryX;        // x at the stopped trade's entry (start of the journey)
    private double adverseMinX;   // most adverse (lowest x) reached since the stop
    private long armedAt = 0;
    private int attemptsUsed = 0;

    // Details of the most recent fire, for logging.
    private double lastEntryPrice = Double.NaN;
    private double lastAdverseExtreme = Double.NaN;
    private boolean expiredPending = false;

    public RevengeEngine(Settings settings) {
        this.settings = settings;
    }

    /**
     * Arm the engine right after a stop-out. {@code side} is the side of the
     * stopped trade (== the side toward the target wall); the recovery trade will
     * be taken on the same side.
     */
    public void arm(TradeSide side, double entryPrice, double stopPrice, long nowMs) {
        if (!settings.revengeEnabled || attemptsRemaining() <= 0) {
            this.active = false;
            return;
        }
        this.active = true;
        this.side = side;
        this.sign = side.sign;
        this.entryX = sign * entryPrice;
        this.adverseMinX = sign * stopPrice;
        this.armedAt = nowMs;
    }

    /** Fully reset (e.g. after a genuine, non-stop trade closes). */
    public void reset() {
        this.active = false;
        this.attemptsUsed = 0;
    }

    public void disarm() {
        this.active = false;
    }

    public boolean isArmed() {
        return active;
    }

    public TradeSide side() {
        return side;
    }

    public double lastEntryPrice() {
        return lastEntryPrice;
    }

    public double lastAdverseExtreme() {
        return lastAdverseExtreme;
    }

    /** True once, right after the search window lapses, so it can be logged. */
    public boolean pollExpired() {
        boolean e = expiredPending;
        expiredPending = false;
        return e;
    }

    private int attemptsRemaining() {
        return Math.max(0, settings.revengeMaxAttempts - attemptsUsed);
    }

    /**
     * Feed every price update while flat. Returns true exactly once when a
     * recovery entry should be opened at {@code price}; the strategy then calls
     * {@link #lastEntryPrice()} / {@link #side()} to place it.
     */
    public boolean onPrice(double price, long nowMs) {
        if (!active) {
            return false;
        }
        if (nowMs - armedAt > settings.revengeWindowMs) {
            active = false; // window elapsed without a clean correction
            expiredPending = true;
            return false;
        }

        double x = sign * price;
        if (x < adverseMinX) {
            adverseMinX = x; // the adverse move is still extending
            return false;
        }

        // The adverse journey is measured from the stopped trade's ENTRY: the
        // stop itself already sits deep inside that journey, so the engine is
        // effective immediately even when the stop price turns out to be the
        // extreme and price snaps straight back.
        double excursion = entryX - adverseMinX;
        double correction = x - adverseMinX;            // how far it has turned back
        if (excursion < settings.revengeMinExcursionDollars) {
            return false; // not a real journey to correct from yet
        }
        if (correction < settings.revengeReversalDollars) {
            return false; // not corrected enough to trust the turn
        }

        // Fire once.
        this.lastEntryPrice = price;
        this.lastAdverseExtreme = sign * adverseMinX;
        this.attemptsUsed++;
        this.active = false;
        return true;
    }
}
