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
        candidates.addAll(findWalls(book.bidsSnapshot(), LiquidityWall.Side.BID, midPrice));
        candidates.addAll(findWalls(book.asksSnapshot(), LiquidityWall.Side.ASK, midPrice));

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

            // A trusted wall only breaks once it thins well below the entry
            // threshold (hysteresis); an unconfirmed one breaks at the threshold.
            double thinFloor = w.confirmed
                    ? settings.wallMinSize * settings.wallBreakSizeFraction
                    : settings.wallMinSize;
            boolean thinned = (now - w.lastSeenMs) <= settings.wallRemoveGraceMs && w.size < thinFloor;
            boolean vanished = (now - w.lastSeenMs) > settings.wallRemoveGraceMs;

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

    /**
     * Find <b>dominant</b> walls on one side of the book within the scan range.
     *
     * <p>A wall is NOT just any heavy area: a single ordinary level on a dense
     * book (like gold) is meaningless. We only treat a level as a wall when it
     * both clears an absolute floor ({@code wallMinSize}) AND stands out from its
     * surroundings by at least {@code wallDominanceRatio}x the average resting
     * size of the levels around it. Adjacent dominant levels (within
     * {@code wallClusterTicks}) are merged into one wall and their sizes summed.
     *
     * <p>This is the fix for the previous behaviour, which summed the entire
     * contiguous book into one giant "wall" and therefore always found a target
     * on both sides - causing entries on ordinary noise.
     */
    private List<Candidate> findWalls(NavigableMap<Integer, Integer> side, LiquidityWall.Side wallSide,
                                      double midPrice) {
        List<Candidate> out = new ArrayList<>();
        if (side.isEmpty()) {
            return out;
        }

        int rangeTicks = Math.max(1, (int) Math.ceil(settings.maxWallDistanceDollars / pips));
        int midLevel = (int) Math.round(midPrice / pips);
        int lo = midLevel - rangeTicks;
        int hi = midLevel + rangeTicks;

        List<int[]> levels = new ArrayList<>(); // [level, size]
        for (Map.Entry<Integer, Integer> e : side.subMap(lo, true, hi, true).entrySet()) {
            levels.add(new int[] {e.getKey(), e.getValue()});
        }
        if (levels.isEmpty()) {
            return out;
        }

        int i = 0;
        while (i < levels.size()) {
            int[] lv = levels.get(i);
            double dominanceFloor = localAverage(levels, i) * settings.wallDominanceRatio;
            boolean dominant = lv[1] >= settings.wallMinSize && lv[1] >= dominanceFloor;
            if (!dominant) {
                i++;
                continue;
            }
            // Merge a run of adjacent dominant levels into a single wall.
            int sumSize = lv[1];
            int maxSize = lv[1];
            int maxLevel = lv[0];
            int lastLevel = lv[0];
            int j = i + 1;
            while (j < levels.size()
                    && levels.get(j)[0] - lastLevel <= settings.wallClusterTicks
                    && levels.get(j)[1] >= dominanceFloor) {
                int[] n = levels.get(j);
                sumSize += n[1];
                if (n[1] > maxSize) {
                    maxSize = n[1];
                    maxLevel = n[0];
                }
                lastLevel = n[0];
                j++;
            }
            double price = maxLevel * pips;
            if (Math.abs(price - midPrice) <= settings.maxWallDistanceDollars) {
                out.add(new Candidate(wallSide, maxLevel, price, sumSize));
            }
            i = j;
        }
        return out;
    }

    /**
     * Average resting size of the levels within {@code wallNeighborhoodTicks} of
     * the candidate at {@code idx}, excluding the candidate itself. Judging
     * dominance against the immediate neighborhood (instead of the whole scan
     * range) stops an ordinary level surrounded by similar sizes from passing
     * just because far-away sparse levels dilute the average.
     */
    private double localAverage(List<int[]> levels, int idx) {
        int center = levels.get(idx)[0];
        long sum = 0;
        int count = 0;
        for (int k = 0; k < levels.size(); k++) {
            if (k == idx) {
                continue;
            }
            if (Math.abs(levels.get(k)[0] - center) <= settings.wallNeighborhoodTicks) {
                sum += levels.get(k)[1];
                count++;
            }
        }
        return count == 0 ? 0.0 : (double) sum / count;
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

    /** Drop all tracked walls for a fresh session (they re-detect from the live book). */
    public void resetForNewSession() {
        tracked.clear();
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
