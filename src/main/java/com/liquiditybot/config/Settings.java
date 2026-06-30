package com.liquiditybot.config;

import velox.api.layer1.settings.StrategySettingsVersion;

/**
 * All tunable parameters of the strategy. Bookmap persists this object per
 * instrument and exposes it through the add-on settings panel, so every value
 * here can be changed live during replay testing without recompiling.
 *
 * <p>Distances are expressed in <b>price dollars</b> (the price units shown on
 * the chart), e.g. a wall at 4060 with {@code takeProfitDollars = 4.0} means a
 * target 4 price-dollars away. They are converted to ticks at runtime using the
 * instrument tick size, so the same settings work on any instrument.
 */
@StrategySettingsVersion(currentVersion = 1, compatibleVersions = {})
public class Settings {

    // ---- Master switches ----------------------------------------------------

    /**
     * When false the bot runs in "shadow" mode: it detects walls, tracks waves
     * and logs the exact entries/exits it WOULD have taken, but sends no orders.
     * Start every new replay session here, then flip on once behaviour looks right.
     */
    public boolean enableTrading = false;

    /**
     * Magnet mode (the requested "opposite" approach): trade TOWARD the wall,
     * treating it as a price magnet / target, entering on the pull-back wave as
     * price turns back toward it. If set false the bot fades the wall instead
     * (the conventional approach), kept only for comparison during testing.
     */
    public boolean magnetMode = true;

    public boolean drawWallsOnChart = true;

    // ---- Wall detection ------------------------------------------------------

    /** Minimum clustered resting size for a level area to count as a wall. */
    public int wallMinSize = 47;

    /**
     * Hysteresis for breaking a confirmed wall: once trusted, a wall is only
     * considered "thinned/broken" when its size falls below
     * {@code wallMinSize * wallBreakSizeFraction}. This stops a big wall from
     * flickering broken/confirmed (and being re-traded) on small size wobbles.
     */
    public double wallBreakSizeFraction = 0.5;

    /** A wall is kept this long after it was last seen, to ride out depth flicker. */
    public long wallRemoveGraceMs = 1500;

    /** Maximum number of trades taken on a single wall while it stays confirmed. */
    public int maxTradesPerWall = 2;

    /**
     * A wall must also be <b>dominant</b>: at least this many times the average
     * resting size of the levels around it within the scan range. This is what
     * stops an ordinary level on a dense book (e.g. gold) from being mistaken
     * for a wall. Raise it to demand more obvious walls, lower it for subtler ones.
     */
    public double wallDominanceRatio = 3.0;

    /** Adjacent levels within this many ticks are merged into one wall. */
    public int wallClusterTicks = 3;

    /** A wall must persist this long before we trust it (anti-spoof). */
    public long wallPersistenceMs = 1500;

    /** Ignore walls farther than this (price dollars) from current price. */
    public double maxWallDistanceDollars = 30.0;

    // ---- Trade sizing & risk -------------------------------------------------

    public int orderSize = 1;

    /** Take profit distance from entry, in price dollars. */
    public double takeProfitDollars = 4.0;

    /** Stop loss distance from entry, in price dollars (room for oscillation). */
    public double stopLossDollars = 15.0;

    // ---- Wave / anti-trap logic ---------------------------------------------

    /**
     * The pull-back away from the wall must be at least this big (price dollars)
     * before we consider entering. This is the core anti-trap rule: it stops the
     * bot from chasing when price merely brushes the wall by a few dollars.
     */
    public double waveMinAmplitudeDollars = 8.0;

    /** Do not enter closer than this to the wall (enter "from afar"). */
    public double minEntryDistanceFromWallDollars = 6.0;

    /** Do not enter farther than this from the wall (no edge left). */
    public double maxEntryDistanceFromWallDollars = 25.0;

    /** Price must turn back toward the wall by this much to confirm the entry. */
    public double turnConfirmDollars = 1.0;

    // ---- Pacing --------------------------------------------------------------

    /** Cool-down after a trade closes before another may open. */
    public long cooldownMsAfterTrade = 5000;

    /** How long to keep recording price in the black box after a trade closes. */
    public long postTradeTrackingMs = 60000;

    /** Minimum gap between full wall re-scans (throttle). */
    public long detectionThrottleMs = 100;
}
