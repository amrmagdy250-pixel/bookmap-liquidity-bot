package com.liquiditybot.book;

import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A local mirror of the limit order book (the COB / DOM) rebuilt from Bookmap
 * depth updates.
 *
 * <p>Bookmap reports prices as integer "levels" (tick indices). Multiply a level
 * by {@code pips} (the instrument tick size) to get a real price.
 */
public class OrderBook {

    private final NavigableMap<Integer, Integer> bids = new TreeMap<>();
    private final NavigableMap<Integer, Integer> asks = new TreeMap<>();
    private final double pips;

    private int bestBidLevel = Integer.MIN_VALUE;
    private int bestAskLevel = Integer.MAX_VALUE;
    private boolean bboKnown = false;

    public OrderBook(double pips) {
        this.pips = pips;
    }

    /** Apply a depth update. size == 0 removes the level. */
    public synchronized void onDepth(boolean isBid, int level, int size) {
        NavigableMap<Integer, Integer> side = isBid ? bids : asks;
        if (size <= 0) {
            side.remove(level);
        } else {
            side.put(level, size);
        }
    }

    public synchronized void onBbo(int bidLevel, int askLevel) {
        this.bestBidLevel = bidLevel;
        this.bestAskLevel = askLevel;
        this.bboKnown = true;
    }

    public double pips() {
        return pips;
    }

    public double levelToPrice(int level) {
        return level * pips;
    }

    public int priceToLevel(double price) {
        return (int) Math.round(price / pips);
    }

    /** Mid price in real price units, or null if not enough data yet. */
    public synchronized Double midPrice() {
        if (bboKnown && bestBidLevel != Integer.MIN_VALUE && bestAskLevel != Integer.MAX_VALUE) {
            return ((bestBidLevel + bestAskLevel) / 2.0) * pips;
        }
        if (!bids.isEmpty() && !asks.isEmpty()) {
            return ((bids.lastKey() + asks.firstKey()) / 2.0) * pips;
        }
        return null;
    }

    public synchronized Integer bestBidLevel() {
        if (bboKnown && bestBidLevel != Integer.MIN_VALUE) {
            return bestBidLevel;
        }
        return bids.isEmpty() ? null : bids.lastKey();
    }

    public synchronized Integer bestAskLevel() {
        if (bboKnown && bestAskLevel != Integer.MAX_VALUE) {
            return bestAskLevel;
        }
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /** Defensive copy of the bid side (level -> size). */
    public synchronized NavigableMap<Integer, Integer> bidsSnapshot() {
        return new TreeMap<>(bids);
    }

    /** Defensive copy of the ask side (level -> size). */
    public synchronized NavigableMap<Integer, Integer> asksSnapshot() {
        return new TreeMap<>(asks);
    }
}
