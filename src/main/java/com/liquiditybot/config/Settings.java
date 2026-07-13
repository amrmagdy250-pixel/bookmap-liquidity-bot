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

    /**
     * Master switch for the wall/wave (liquidity) strategy. When off, no wall
     * targets are taken and no wall/revenge trades fire - the OB engine (which
     * has its own switch) can then be tested in isolation, or vice versa.
     */
    public boolean wallStrategyEnabled = true;

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

    /**
     * Dominance is judged against the average resting size of the levels within
     * this many ticks around the candidate (excluding the candidate itself).
     * A local window keeps one thin far-away region from diluting the average
     * and letting an ordinary level pass as a "wall".
     */
    public int wallNeighborhoodTicks = 50;

    /** Adjacent levels within this many ticks are merged into one wall. */
    public int wallClusterTicks = 3;

    /** A wall must persist this long before we trust it (anti-spoof). */
    public long wallPersistenceMs = 1500;

    /** Ignore walls farther than this (price dollars) from current price. */
    public double maxWallDistanceDollars = 30.0;

    /**
     * Switch the active target to a newly confirmed wall only when it is nearer
     * than the current one by at least this much (price dollars) - keeps stacked
     * walls worked nearest-first without flip-flopping between similar levels.
     */
    public double wallRetargetImprovementDollars = 2.0;

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

    /**
     * Require a real BOTTOM, not just the first turn: after the initial turn
     * off the trough, price must pull back by {@code waveBottomPullbackDollars}
     * WITHOUT taking the trough out (a higher low) and then turn toward the
     * wall again. A sideways drift keeps nudging new troughs and never builds
     * this structure, so it can no longer produce an entry.
     */
    public boolean waveBottomConfirmEnabled = true;

    /** Pull-back from the bounce top needed to start the higher-low test ($). */
    public double waveBottomPullbackDollars = 0.75;

    /**
     * Re-targeting a wall on the same side within this many dollars of the
     * previous target resumes the wave state instead of resetting it. A big
     * wall whose size breathes around the confirm threshold flickers
     * broken/re-confirmed; without this the accumulated pull-back would be
     * erased on every flicker and the entry could never fire.
     */
    public double waveResumeToleranceDollars = 1.0;

    // ---- Smart peak filter ("sniper") ---------------------------------------

    /**
     * When on, the bot behaves like a sniper: before entering short it checks
     * the recent price peaks and refuses to sell into a fresh runaway breakout
     * (a brand-new high with nothing above it), which is exactly what caused the
     * one losing trade. It only enters when price is turning at or under an
     * established recent extreme, where the move back toward the wall is likely.
     */
    public boolean peakFilterEnabled = true;

    /** How far back (ms) to look at recent peaks/troughs when judging quality. */
    public long peakLookbackMs = 900000;

    /**
     * A swing pivot (a peak or a trough) is only recorded once price reverses
     * from an extreme by at least this many dollars. Bigger = only major peaks
     * are remembered; smaller = every little wiggle counts as a peak.
     */
    public double pivotReversalDollars = 3.0;

    /**
     * How far beyond the recent extreme still counts as "the same level" rather
     * than a breakout. A turn within this many dollars of the last comparable
     * peak is treated as an established extreme (good entry); a turn that pushes
     * more than this past it is a runaway breakout (skipped as risky).
     */
    public double peakBreakoutToleranceDollars = 0.5;

    /**
     * When no pivot history exists yet in the lookback window (session start,
     * quiet stretches), allow the entry anyway instead of standing aside -
     * but demand a stronger turn back toward the wall (see below) so quality
     * is preserved without history to lean on.
     */
    public boolean peakNoHistoryEntryEnabled = true;

    /**
     * Extra turn confirmation ($, on top of the normal turn confirm) required
     * for a no-history entry. The deeper turn substitutes for the missing
     * established-extreme check.
     */
    public double peakNoHistoryExtraConfirmDollars = 1.0;

    // ---- Spoof-wall filter ---------------------------------------------------

    /**
     * A level whose wall keeps confirming and getting <b>pulled</b> over and over
     * is being painted by a spoofer, not defended. Once a level accumulates
     * enough pulls in the recent window it is blacklisted as a target until the
     * pulls age out.
     */
    public boolean spoofFilterEnabled = true;

    /** Number of "pulled" breaks at (or near) a level to flag it as spoofed. */
    public int spoofPullCount = 2;

    /** Pulls older than this (ms) no longer count against a level. */
    public long spoofWindowMs = 600000;

    /** Pulls within this many ticks of a level count as the same level. */
    public int spoofToleranceTicks = 5;

    /**
     * Walls at least this big are never treated as spoofed: a genuinely strong
     * wall naturally breathes (contracts added and removed) and may flicker
     * below the confirm threshold without being fake. Its pulls are not
     * recorded and it is never blacklisted as a target.
     */
    public int spoofExemptWallSize = 100;

    // ---- Same-zone re-entry guard --------------------------------------------

    /**
     * A second trade on the same wall may not start from the same turning point
     * as the previous one: price must first travel to a <b>deeper</b> extreme
     * (lower trough for longs toward an ask wall, higher peak for shorts toward
     * a bid wall). If the market only re-offers the exact same zone, it is
     * usually because it failed to reach the wall the first time - re-entering
     * there just repeats the failed trade.
     */
    public boolean reentryDeeperExtremeEnabled = true;

    /** The new extreme must beat the previous entry's extreme by at least this ($). */
    public double reentryMinExtremeAdvanceDollars = 1.0;

    /** Forget a previous entry's extreme after this long (ms). */
    public long reentryMemoryMs = 3600000;

    /**
     * Hard cooldown per wall: after a trade is taken toward a wall, no new trade
     * toward a wall at that price is allowed for this long (ms), regardless of
     * how deep the new extreme is. Default 12 minutes.
     */
    public long reentryCooldownMs = 720000;

    /**
     * After the first bounce, the pullback must hold above the adverse extreme
     * (higher low) and price must turn back up off it by this much ($) before
     * the recovery entry fires. This separates a genuine correction from a
     * one-tick blip inside a still-falling market.
     */
    public double revengeConfirmDollars = 1.0;

    /**
     * If the adverse move runs this far ($) past the original stop, price has
     * left the wall zone the trade was built around - the recovery chance is
     * considered dead and the engine cancels itself (logged as LEFT_ZONE).
     */
    public double revengeMaxBeyondStopDollars = 5.0;

    /**
     * While waiting for the recovery, a confirmed wall on the opposite side
     * (i.e. one whose magnet direction contradicts the revenge side) within
     * targeting range means the market structure has flipped: cancel the
     * recovery (logged as OPPOSITE_WALL).
     */
    public boolean revengeCancelOnOppositeWall = true;

    // ---- Revenge engine (post-stop recovery) --------------------------------

    /**
     * A separate "revenge" logic: after a trade is stopped out, price is usually
     * in the middle of an adverse excursion. This engine waits for that excursion
     * to exhaust and price to start <b>correcting back</b>, then takes one
     * recovery trade in the <b>same direction</b> as the original target wall
     * (i.e. the same side as the stopped trade) to make back the loss. It is a
     * self-contained module and does not touch the normal wall/wave entry path.
     */
    public boolean revengeEnabled = true;

    /** Maximum recovery trades allowed for a single stop-out event. */
    public int revengeMaxAttempts = 1;

    /**
     * The adverse journey - measured from the stopped trade's ENTRY price to
     * the worst extreme reached - must be at least this big (price dollars)
     * before a recovery is considered. A stop-out already implies a journey of
     * the full stop distance, so with default sizing this is met immediately;
     * raise it above the stop distance to demand extra follow-through.
     */
    public double revengeMinExcursionDollars = 3.0;

    /**
     * Once the adverse extreme is in, price must correct back toward the original
     * direction by at least this many dollars to trigger the recovery entry.
     */
    public double revengeReversalDollars = 2.5;

    /** Give up looking for a recovery this long (ms) after the stop-out. */
    public long revengeWindowMs = 1200000;

    // ---- Order-block engine (independent module) ------------------------------

    /**
     * A completely independent engine that trades order blocks found from the
     * raw executed tape: a narrow zone that absorbs a large executed volume with
     * a one-sided aggressor delta, followed by an impulsive displacement away.
     * When price revisits the zone, it enters in the displacement direction.
     * It runs in parallel with the wall/wave strategy and holds its own
     * position with its own TP/SL - either engine can be switched on or off
     * without affecting the other.
     */
    public boolean obEnabled = true;

    /** Minimum executed contracts inside the zone window to qualify as a block. */
    public int obMinZoneVolume = 150;

    /** Zone half-width in ticks (the block spans +/- this many ticks). */
    public int obZoneTicks = 10;

    /** The zone volume must accumulate within this window (ms). */
    public long obZoneWindowMs = 60000;

    /**
     * The aggressor delta inside the zone must be at least this fraction of the
     * total volume (one-sided flow). Below it the zone is a two-sided fight,
     * not a clean institutional block.
     */
    public double obMinDeltaRatio = 0.3;

    /** Price must leave the zone by at least this ($) to confirm the block. */
    public double obDisplacementDollars = 5.0;

    /** The displacement must happen within this long (ms) after the zone forms. */
    public long obDisplacementWindowMs = 300000;

    /** Confirmed blocks older than this (ms) are forgotten. */
    public long obMaxAgeMs = 3600000;

    /** Revisit entry triggers within this ($) of the zone edge. */
    public double obEntryToleranceDollars = 0.5;

    /** Price through the far side of the block by this ($) invalidates it. */
    public double obInvalidationDollars = 2.0;

    /** Maximum trades taken off a single block. */
    public int obMaxTradesPerBlock = 1;

    /** Maximum blocks tracked at once. */
    public int obMaxActiveBlocks = 10;

    /** How often (ms) the recent tape is scanned for a new zone. */
    public long obScanThrottleMs = 2000;

    /** OB trade sizing and targets, independent of the main strategy's. */
    public int obOrderSize = 1;

    public double obTakeProfitDollars = 4.0;

    public double obStopLossDollars = 8.0;

    /** Cool-down after an OB trade closes before another OB trade may open. */
    public long obCooldownMs = 60000;

    /**
     * Approach-speed (waterfall) filter: when price reaches a block after
     * moving toward it by more than this ($) within the lookback window, the
     * revisit is momentum slicing through the zone, not a controlled retest -
     * the entry is skipped until the approach slows down. Replay showed this
     * net-move check delays winners more than it prevents losses, so it is
     * OFF by default and kept only as an optional extra.
     */
    public boolean obApproachFilterEnabled = false;

    /** Max move toward the zone ($) within the lookback before entries pause. */
    public double obMaxApproachDollars = 3.0;

    /** Lookback window (ms) used to measure the approach speed. */
    public long obApproachWindowMs = 30000;

    /**
     * Retest confirmation: the FIRST touch of a block never enters. Price must
     * hold inside/near the zone for {@code obRetestDwellMs}, stop making new
     * adverse extremes, and turn back off that extreme by
     * {@code obRetestReversalDollars} before the entry fires. A waterfall that
     * slices the zone keeps extending the extreme (or violates the block) and
     * never confirms; a controlled retest does. Blocks flagged
     * {@code reconfirm} (formed on a recently violated level) must prove a
     * dwell and reversal {@code obReconfirmRetestMult} times stronger.
     */
    public boolean obRetestConfirmEnabled = true;

    /** Minimum time (ms) price must spend in the retest before an entry. */
    public long obRetestDwellMs = 15000;

    /** Turn off the retest's adverse extreme required to enter ($). */
    public double obRetestReversalDollars = 0.75;

    /** Dwell/reversal multiplier applied to reconfirm (violated-level) blocks. */
    public double obReconfirmRetestMult = 2.0;

    /**
     * Flexible re-confirmation instead of a blind blacklist: a level where a
     * confirmed block was violated is remembered for a while, and a new block
     * forming there is NOT banned - it just must prove itself harder (bigger
     * displacement and a cleaner one-sided delta) before it may be traded.
     */
    public boolean obReconfirmEnabled = true;

    /** How long (ms) a violated level keeps demanding the stricter proof. */
    public long obReconfirmMemoryMs = 7200000;

    /** Displacement multiplier demanded from a block on a violated level. */
    public double obReconfirmDisplacementMult = 1.5;

    /** Min delta ratio demanded from a block on a violated level. */
    public double obReconfirmDeltaRatio = 0.45;

    /**
     * Cut an open OB trade early the moment its own block is violated (price
     * through the far side by {@code obInvalidationDollars}) instead of riding
     * the full stop loss: once the zone has failed, the trade's premise is
     * gone and the remaining stop distance is pure hope.
     */
    public boolean obExitOnViolation = true;

    // ---- Session guard (daily cutoff + memory flush) ---------------------------

    /**
     * Daily trading cutoff on a fixed UTC clock (independent of the machine's
     * timezone, so moving Bookmap to any VPS changes nothing). From the cutoff
     * until the resume time no new trades fire (open trades keep their normal
     * TP/SL management), and at the resume every engine's memory (blocks,
     * penalties, waves, pivots, spoof/re-entry history, revenge state) is
     * wiped so the new day starts clean.
     */
    public boolean sessionGuardEnabled = true;

    /** Cutoff, minutes after midnight UTC. 1230 = 20:30 UTC = 23:30 Riyadh. */
    public int sessionCutoffUtcMinutes = 1230;

    /** Resume, minutes after midnight UTC. 1320 = 22:00 UTC = 01:00 Riyadh. */
    public int sessionResumeUtcMinutes = 1320;

    // ---- Pacing --------------------------------------------------------------

    /** Cool-down after a trade closes before another may open. */
    public long cooldownMsAfterTrade = 5000;

    /** How long to keep recording price in the black box after a trade closes. */
    public long postTradeTrackingMs = 60000;

    /** Minimum gap between full wall re-scans (throttle). */
    public long detectionThrottleMs = 100;
}
