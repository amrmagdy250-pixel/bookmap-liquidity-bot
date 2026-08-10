package com.liquiditybot.book;

/**
 * A resting block of liquidity (a "wall") detected in the order book - a price
 * area holding a large number of contracts. Walls are tracked over time so we
 * can tell a real, persistent wall apart from a flickering / spoofed one, and so
 * we know exactly when one is consumed or pulled (broken).
 */
public class LiquidityWall {

    public enum Side {
        /** Resting bids below price (support). */
        BID,
        /** Resting asks above price (resistance). */
        ASK
    }

    public final long id;
    public final Side side;
    public final long firstSeenMs;

    /** Center level of the wall (tick index). */
    public int level;
    /** Center price of the wall (real price units). */
    public double price;
    /** Total resting size of the clustered wall (contracts). */
    public int size;
    /** Peak size observed for this wall over its lifetime. */
    public int peakSize;
    public long lastSeenMs;
    /** Becomes true once the wall has persisted long enough to be trusted. */
    public boolean confirmed;

    public LiquidityWall(long id, Side side, int level, double price, int size, long now) {
        this.id = id;
        this.side = side;
        this.level = level;
        this.price = price;
        this.size = size;
        this.peakSize = size;
        this.firstSeenMs = now;
        this.lastSeenMs = now;
        this.confirmed = false;
    }

    public void update(int level, double price, int size, long now) {
        this.level = level;
        this.price = price;
        this.size = size;
        this.peakSize = Math.max(this.peakSize, size);
        this.lastSeenMs = now;
    }

    public long ageMs(long now) {
        return now - firstSeenMs;
    }

    @Override
    public String toString() {
        return "Wall#" + id + "{" + side + " @" + price + " size=" + size + " confirmed=" + confirmed + "}";
    }
}
