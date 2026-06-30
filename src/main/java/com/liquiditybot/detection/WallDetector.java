package com.liquiditybot.detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import com.liquiditybot.book.LiquidityWall;
import com.liquiditybot.book.OrderBook;
import com.liquiditybot.config.Settings;

/**
 * Finds and tracks liquidity walls over time.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Cluster adjacent heavy levels into a single wall.</li>
 *   <li>Require persistence before trusting a wall (anti-spoof).</li>
 *   <li>Detect when a wall is pulled, thinned below threshold, or traded
 *       through ("broken/consumed") - this is what lets the bot move from the
 *       nearest wall to the next one in a stack, in order.</li>
 * </ul>
 */
public class WallDetector {

    /** Notified on wall lifecycle transitions (used by the black box). */
    public interface Listener {
        void onWallConfirmed(LiquidityWall wall, long now);

        void onWallBroken(LiquidityWall wall, long now, String reason);
    }

    private final Settings settings;
    private final double pips;
    private final Listener listener;

    private final List<LiquidityWall> tracked = new ArrayList<>();
    private long idCounter = 1;

    /** A wall kept this long after it was last seen, to ride out depth flicker. */
    private static final long REMOVE_GRACE_MS = 750;

    public WallDetector(Settings settings, double pips, Listener listener) {
        this.settings = settings;
        this.pips = pips;
        this.listener = listener;
    }

    /** Re-scan the book and update the tracked wall set. */
    public void detect(OrderBook book, double midPrice, long now) {
        Integer bestBid = book.bestBidLevel();
        Integer bestAsk = book.bestAskLevel();

        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(cluster(book.bidsSnapshot(), LiquidityWall.Side.BID, midPrice, false));
        candidates.addAll(cluster(book.asksSnapshot(), LiquidityWall.Side.ASK, midPrice, true));

        for (Candidate c : candidates) {
            LiquidityWall match = findMatch(c);
            if (match != null) {
                match.update(c.level, c.price, c.size, now);
            } else {
                tracked.add(new LiquidityWall(idCounter++, c.side, c.level, c.price, c.size, now));
            }
        }

        // Lifecycle pass: confirm, break, expire.
        Iterator<LiquidityWall> it = tracked.iterator();
        while (it.hasNext()) {
            LiquidityWall w = it.next();

            boolean tradedThrough =
                    (w.side == LiquidityWall.Side.ASK && bestBid != null && w.level < bestBid)
                            || (w.side == LiquidityWall.Side.BID && bestAsk != null && w.level > bestAsk);

            boolean thinned = (now - w.lastSeenMs) <= REMOVE_GRACE_MS && w.size < settings.wallMinSize;
            boolean vanished = (now - w.lastSeenMs) > REMOVE_GRACE_MS;

            if (tradedThrough || thinned || vanished) {
                if (w.confirmed) {
                    String reason = tradedThrough ? "traded_through" : (thinned ? "thinned" : "pulled");
                    listener.onWallBroken(w, now, reason);
                }
                it.remove();
                continue;
            }

            if (!w.confirmed && w.size >= settings.wallMinSize
                    && w.ageMs(now) >= settings.wallPersistenceMs) {
                w.confirmed = true;
                listener.onWallConfirmed(w, now);
            }
        }
    }

    private LiquidityWall findMatch(Candidate c) {
        LiquidityWall best = null;
        int bestDist = Integer.MAX_VALUE;
        for (LiquidityWall w : tracked) {
            if (w.side != c.side) {
                continue;
            }
            int d = Math.abs(w.level - c.level);
            if (d <= settings.wallClusterTicks && d < bestDist) {
                bestDist = d;
                best = w;
            }
        }
        return best;
    }

    /** Cluster a sorted side of the book into candidate walls. */
    private List<Candidate> cluster(NavigableMap<Integer, Integer> side, LiquidityWall.Side wallSide,
                                    double midPrice, boolean ascending) {
        List<Candidate> out = new ArrayList<>();
        int clusterStart = Integer.MIN_VALUE;
        int lastLevel = Integer.MIN_VALUE;
        int sum = 0;
        int maxSize = -1;
        int maxLevel = 0;

        for (Map.Entry<Integer, Integer> e : side.entrySet()) {
            int level = e.getKey();
            int size = e.getValue();
            if (clusterStart == Integer.MIN_VALUE) {
                clusterStart = level;
            } else if (level - lastLevel > settings.wallClusterTicks) {
                emit(out, wallSide, sum, maxSize, maxLevel, midPrice);
                sum = 0;
                maxSize = -1;
            }
            sum += size;
            if (size > maxSize) {
                maxSize = size;
                maxLevel = level;
            }
            lastLevel = level;
        }
        emit(out, wallSide, sum, maxSize, maxLevel, midPrice);
        return out;
    }

    private void emit(List<Candidate> out, LiquidityWall.Side side, int sum, int maxSize, int maxLevel,
                      double midPrice) {
        if (sum < settings.wallMinSize || maxSize < 0) {
            return;
        }
        double price = maxLevel * pips;
        if (Math.abs(price - midPrice) > settings.maxWallDistanceDollars) {
            return;
        }
        out.add(new Candidate(side, maxLevel, price, sum));
    }

    /** Confirmed walls of a side, nearest to {@code price} first. */
    public List<LiquidityWall> confirmed(LiquidityWall.Side side, double price) {
        List<LiquidityWall> list = new ArrayList<>();
        for (LiquidityWall w : tracked) {
            if (w.confirmed && w.side == side) {
                list.add(w);
            }
        }
        list.sort(Comparator.comparingDouble(w -> Math.abs(w.price - price)));
        return list;
    }

    /** Is this wall still confirmed and tracked? */
    public boolean isStillActive(long wallId) {
        for (LiquidityWall w : tracked) {
            if (w.id == wallId) {
                return w.confirmed;
            }
        }
        return false;
    }

    public List<LiquidityWall> allConfirmed() {
        List<LiquidityWall> list = new ArrayList<>();
        for (LiquidityWall w : tracked) {
            if (w.confirmed) {
                list.add(w);
            }
        }
        return list;
    }

    private static final class Candidate {
        final LiquidityWall.Side side;
        final int level;
        final double price;
        final int size;

        Candidate(LiquidityWall.Side side, int level, double price, int size) {
            this.side = side;
            this.level = level;
            this.price = price;
            this.size = size;
        }
    }
}
