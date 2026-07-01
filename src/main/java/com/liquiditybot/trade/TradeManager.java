package com.liquiditybot.trade;

import com.liquiditybot.blackbox.BlackBox;
import com.liquiditybot.book.LiquidityWall;
import com.liquiditybot.config.Settings;
import com.liquiditybot.detection.WaveTracker;
import com.liquiditybot.indicators.StatsIndicators;

import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.SimpleOrderSendParametersBuilder;
import velox.api.layer1.data.StatusInfo;
import velox.api.layer1.simplified.Api;

/**
 * Owns the single open position and its lifecycle: entry, self-managed
 * take-profit / stop-loss, and book-keeping. Exactly one trade may be live at a
 * time. Take-profit and stop-loss are managed in-bot (by watching price and
 * sending closing market orders) so behaviour is identical and deterministic in
 * replay regardless of broker bracket support.
 *
 * <p>When {@link Settings#enableTrading} is false the manager runs in "shadow"
 * mode: it simulates the fill, the exit and the P&amp;L so the strategy can be
 * fully evaluated on replay without sending a single order.
 */
public class TradeManager {

    private enum State { FLAT, ENTERING, OPEN, EXITING }

    private final Api api;
    private final String alias;
    private final Settings settings;
    private final BlackBox blackBox;
    private final StatsIndicators stats;
    private final double multiplier;
    private final double pips;

    private State state = State.FLAT;
    private boolean shadow;

    private long tradeSeq = 0;
    private long currentTradeId;
    private TradeSide side;
    private double entryPrice;
    private double tpPrice;
    private double slPrice;
    private long entryTime;
    private double wallPrice;
    private long activeWallId = -1;

    private double lastExecPrice = Double.NaN;
    private double exitFillPrice = Double.NaN;
    private String exitReason = "";

    private long cooldownUntil = 0;
    private long nowMs = 0;
    private double lastPrice = Double.NaN;

    // Post-trade price tracking (the black box keeps watching where price went).
    private long postTrackTradeId = -1;
    private long postTrackUntil = 0;
    private long lastPostSampleMs = 0;

    public TradeManager(Api api, String alias, Settings settings, BlackBox blackBox,
                        StatsIndicators stats, double multiplier, double pips) {
        this.api = api;
        this.alias = alias;
        this.settings = settings;
        this.blackBox = blackBox;
        this.stats = stats;
        this.multiplier = multiplier;
        this.pips = pips;
    }

    public void setNow(long nowMs) {
        this.nowMs = nowMs;
    }

    public boolean isFlat() {
        return state == State.FLAT;
    }

    public boolean canEnter() {
        return state == State.FLAT && nowMs >= cooldownUntil;
    }

    public long activeWallId() {
        return activeWallId;
    }

    /** True while a position is live (used to draw the entry/TP/SL lines). */
    public boolean isOpen() {
        return state == State.OPEN;
    }

    /** Entry / take-profit / stop-loss levels in real price dollars (only valid while open). */
    public double getEntryPrice() {
        return entryPrice;
    }

    public double getTakeProfitPrice() {
        return tpPrice;
    }

    public double getStopLossPrice() {
        return slPrice;
    }

    /** Act on an entry signal produced by the wave tracker. Returns true if a trade was opened/requested. */
    public boolean onEntrySignal(WaveTracker.EntrySignal sig, LiquidityWall wall) {
        if (!canEnter()) {
            return false;
        }
        this.shadow = !settings.enableTrading;
        this.currentTradeId = ++tradeSeq;
        this.side = sig.side;
        this.wallPrice = sig.wallPrice;
        this.activeWallId = wall.id;
        this.entryTime = nowMs;

        blackBox.log(nowMs, "ENTRY_SIGNAL",
                "tradeId", currentTradeId,
                "side", side,
                "signalPrice", round(sig.entryPrice),
                "wallPrice", round(sig.wallPrice),
                "waveAmplitude", round(sig.waveAmplitude),
                "distanceFromWall", round(sig.distanceFromWall),
                "peakPrice", round(sig.peakPrice),
                "recentExtreme", round(sig.recentExtreme),
                "shadow", shadow);

        if (shadow) {
            openPosition(sig.entryPrice);
        } else {
            state = State.ENTERING;
            api.sendOrder(new SimpleOrderSendParametersBuilder(alias, side.isBuy, settings.orderSize).build());
        }
        return true;
    }

    private void openPosition(double fillPrice) {
        this.entryPrice = fillPrice;
        if (side == TradeSide.LONG) {
            this.tpPrice = entryPrice + settings.takeProfitDollars;
            this.slPrice = entryPrice - settings.stopLossDollars;
        } else {
            this.tpPrice = entryPrice - settings.takeProfitDollars;
            this.slPrice = entryPrice + settings.stopLossDollars;
        }
        this.state = State.OPEN;
        blackBox.log(nowMs, "ENTRY_FILLED",
                "tradeId", currentTradeId,
                "side", side,
                "entryPrice", round(entryPrice),
                "takeProfit", round(tpPrice),
                "stopLoss", round(slPrice),
                "shadow", shadow);
    }

    private void requestExit(String reason, double refPrice) {
        this.exitReason = reason;
        blackBox.log(nowMs, "EXIT_SIGNAL",
                "tradeId", currentTradeId,
                "reason", reason,
                "refPrice", round(refPrice));
        if (shadow) {
            closePosition(refPrice);
        } else {
            this.exitFillPrice = Double.NaN;
            this.state = State.EXITING;
            api.sendOrder(new SimpleOrderSendParametersBuilder(alias, side.opposite().isBuy,
                    settings.orderSize).build());
        }
    }

    private void closePosition(double exitPrice) {
        double pnlPrice = (exitPrice - entryPrice) * side.sign;
        double pnlDollars = pnlPrice * multiplier * settings.orderSize;
        boolean win = pnlPrice > 0;

        TradeRecord rec = new TradeRecord(currentTradeId, side, entryPrice, exitPrice, entryTime,
                nowMs, wallPrice, pnlPrice, pnlDollars, win, exitReason, shadow);

        stats.recordTrade(rec, nowMs);

        blackBox.log(nowMs, "TRADE_CLOSED",
                "tradeId", currentTradeId,
                "side", side,
                "entryPrice", round(entryPrice),
                "exitPrice", round(exitPrice),
                "wallPrice", round(wallPrice),
                "reason", exitReason,
                "pnlPrice", round(pnlPrice),
                "pnlDollars", round(pnlDollars),
                "win", win,
                "wins", stats.getWins(),
                "losses", stats.getLosses(),
                "winRatePct", round(stats.getWinRatePct()),
                "shadow", shadow);

        // Start post-trade observation: keep recording where price travels.
        this.postTrackTradeId = currentTradeId;
        this.postTrackUntil = nowMs + settings.postTradeTrackingMs;
        this.lastPostSampleMs = 0;

        this.state = State.FLAT;
        this.cooldownUntil = nowMs + settings.cooldownMsAfterTrade;
        this.activeWallId = -1;
    }

    /** Feed every price update; drives TP/SL and post-trade tracking. */
    public void onPrice(double price) {
        this.lastPrice = price;

        if (state == State.OPEN) {
            if (side == TradeSide.LONG) {
                if (price >= tpPrice) {
                    requestExit("TAKE_PROFIT", price);
                } else if (price <= slPrice) {
                    requestExit("STOP_LOSS", price);
                }
            } else {
                if (price <= tpPrice) {
                    requestExit("TAKE_PROFIT", price);
                } else if (price >= slPrice) {
                    requestExit("STOP_LOSS", price);
                }
            }
        }

        // Post-trade tracking (sample ~once per second of data time).
        if (postTrackTradeId != -1 && nowMs <= postTrackUntil) {
            if (nowMs - lastPostSampleMs >= 1000) {
                lastPostSampleMs = nowMs;
                blackBox.log(nowMs, "POST_TRADE_PRICE",
                        "tradeId", postTrackTradeId,
                        "price", round(price));
            }
        } else if (postTrackTradeId != -1 && nowMs > postTrackUntil) {
            postTrackTradeId = -1;
        }
    }

    public void onOrderExecuted(ExecutionInfo ei) {
        // Bookmap fill prices are tick indices; convert to real price units.
        this.lastExecPrice = ei.price * pips;
        if (state == State.EXITING) {
            this.exitFillPrice = ei.price * pips;
        }
    }

    public void onPositionUpdate(StatusInfo si) {
        int pos = si.position;
        if (state == State.ENTERING && pos != 0) {
            double fill = !Double.isNaN(si.averagePrice) && si.averagePrice > 0
                    ? si.averagePrice * pips : lastExecPrice;
            openPosition(fill);
        } else if (state == State.EXITING && pos == 0) {
            double exit = !Double.isNaN(exitFillPrice) ? exitFillPrice
                    : (!Double.isNaN(lastExecPrice) ? lastExecPrice : lastPrice);
            closePosition(exit);
        }
        stats.updatePosition(pos, nowMs);
    }

    /** If the wall we were trading vanished while flat/waiting, drop the link. */
    public void clearActiveWallIfFlat() {
        if (state == State.FLAT) {
            activeWallId = -1;
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
