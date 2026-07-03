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
    private double stopX;         // x at the stop, edge of the wall zone
    private double adverseMinX;   // most adverse (lowest x) reached since the stop
    private long armedAt = 0;
    private int attemptsUsed = 0;

    // Higher-low confirmation: a first bounce alone can be a blip inside a
    // waterfall. After the bounce we demand a pullback that HOLDS above the
    // adverse extreme (a higher low) and a turn back up off that pullback
    // before firing - that is what separates a correction from a falling market.
    private boolean bounced = false;
    private double bounceHighX;
    private double pullbackLowX;
    private boolean pullingBack = false;

    // Details of the most recent fire, for logging.
    private double lastEntryPrice = Double.NaN;
    private double lastAdverseExtreme = Double.NaN;
    private boolean expiredPending = false;
    private String cancelPending = null;

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
        this.stopX = sign * stopPrice;
        this.adverseMinX = sign * stopPrice;
        this.armedAt = nowMs;
        this.bounced = false;
        this.pullingBack = false;
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

    /** Non-null once, right after the engine self-cancels, with the reason. */
    public String pollCancelled() {
        String c = cancelPending;
        cancelPending = null;
        return c;
    }

    /** External cancel (e.g. an opposite wall appeared): the chance is gone. */
    public void cancel() {
        this.active = false;
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
            bounced = false; // any bounce so far was just a blip in the move
            pullingBack = false;
            // Price left the wall zone entirely: the recovery chance is gone.
            if (stopX - adverseMinX > settings.revengeMaxBeyondStopDollars) {
                active = false;
                cancelPending = "LEFT_ZONE";
            }
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

        if (!bounced) {
            if (correction < settings.revengeReversalDollars) {
                return false; // not corrected enough to trust the turn
            }
            bounced = true;
            bounceHighX = x;
            pullingBack = false;
            return false; // bounce noted; now wait for the higher low
        }

        if (!pullingBack) {
            if (x > bounceHighX) {
                bounceHighX = x;
            } else if (bounceHighX - x >= settings.revengeConfirmDollars) {
                pullingBack = true; // pullback started; track its low
                pullbackLowX = x;
            }
            return false;
        }

        if (x < pullbackLowX) {
            pullbackLowX = x;
            return false;
        }
        if (x - pullbackLowX < settings.revengeConfirmDollars) {
            return false; // not turned back up off the pullback yet
        }
        // pullbackLowX > adverseMinX is guaranteed here (a new low below the
        // extreme resets the whole sequence above), so this is a higher low.

        // Fire once.
        this.lastEntryPrice = price;
        this.lastAdverseExtreme = sign * adverseMinX;
        this.attemptsUsed++;
        this.active = false;
        return true;
    }
}
