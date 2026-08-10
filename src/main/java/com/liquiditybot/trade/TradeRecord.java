package com.liquiditybot.trade;

/** Immutable summary of one completed trade, kept for stats and diagnosis. */
public class TradeRecord {
    public final long tradeId;
    public final TradeSide side;
    public final double entryPrice;
    public final double exitPrice;
    public final long entryTime;
    public final long exitTime;
    public final double wallPrice;
    public final double pnlPrice;
    public final double pnlDollars;
    public final boolean win;
    public final String exitReason;
    public final boolean shadow;

    public TradeRecord(long tradeId, TradeSide side, double entryPrice, double exitPrice,
                       long entryTime, long exitTime, double wallPrice, double pnlPrice,
                       double pnlDollars, boolean win, String exitReason, boolean shadow) {
        this.tradeId = tradeId;
        this.side = side;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.entryTime = entryTime;
        this.exitTime = exitTime;
        this.wallPrice = wallPrice;
        this.pnlPrice = pnlPrice;
        this.pnlDollars = pnlDollars;
        this.win = win;
        this.exitReason = exitReason;
        this.shadow = shadow;
    }
}
