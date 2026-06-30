package com.liquiditybot.trade;

/** Direction of a trade. */
public enum TradeSide {
    LONG(true, +1),
    SHORT(false, -1);

    /** true if the entry order is a buy. */
    public final boolean isBuy;
    /** +1 for long, -1 for short. Multiply price moves by this to get signed P&L. */
    public final int sign;

    TradeSide(boolean isBuy, int sign) {
        this.isBuy = isBuy;
        this.sign = sign;
    }

    public TradeSide opposite() {
        return this == LONG ? SHORT : LONG;
    }
}
