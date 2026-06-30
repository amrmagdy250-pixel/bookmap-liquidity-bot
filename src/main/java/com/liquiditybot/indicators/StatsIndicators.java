package com.liquiditybot.indicators;

import java.awt.Color;

import com.liquiditybot.trade.TradeRecord;

import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.Indicator;

/**
 * On-chart indicators requested by the user:
 * <ul>
 *   <li>CVD (cumulative volume delta)</li>
 *   <li>winning-trades counter</li>
 *   <li>losing-trades counter</li>
 *   <li>win-rate %</li>
 *   <li>net P&amp;L (dollars) and current position, as useful extras</li>
 * </ul>
 * All are drawn in the bottom panel so they don't clutter the price chart.
 */
public class StatsIndicators {

    private final Indicator cvd;
    private final Indicator wins;
    private final Indicator losses;
    private final Indicator winRate;
    private final Indicator netPnl;
    private final Indicator position;

    private double cvdValue = 0;
    private int winCount = 0;
    private int lossCount = 0;
    private double netPnlDollars = 0;

    public StatsIndicators(Api api) {
        cvd = api.registerIndicator("CVD (cumulative delta)", GraphType.BOTTOM);
        wins = api.registerIndicator("Winning trades", GraphType.BOTTOM);
        losses = api.registerIndicator("Losing trades", GraphType.BOTTOM);
        winRate = api.registerIndicator("Win rate %", GraphType.BOTTOM);
        netPnl = api.registerIndicator("Net P&L ($)", GraphType.BOTTOM);
        position = api.registerIndicator("Position", GraphType.BOTTOM);

        cvd.setColor(new Color(80, 160, 255));
        wins.setColor(new Color(60, 200, 90));
        losses.setColor(new Color(220, 70, 70));
        winRate.setColor(new Color(230, 200, 60));
        netPnl.setColor(new Color(180, 120, 220));
        position.setColor(Color.WHITE);

        // seed so lines exist from the start
        cvd.addPoint(0);
        wins.addPoint(0);
        losses.addPoint(0);
        winRate.addPoint(0);
        netPnl.addPoint(0);
        position.addPoint(0);
    }

    /** Update CVD from a print. isBuyAggressor == TradeInfo.isBidAggressor. */
    public void onTrade(int size, boolean isBuyAggressor) {
        cvdValue += isBuyAggressor ? size : -size;
        cvd.addPoint(cvdValue);
    }

    public void recordTrade(TradeRecord rec, long now) {
        if (rec.win) {
            winCount++;
        } else {
            lossCount++;
        }
        netPnlDollars += rec.pnlDollars;
        wins.addPoint(winCount);
        losses.addPoint(lossCount);
        winRate.addPoint(getWinRatePct());
        netPnl.addPoint(netPnlDollars);
    }

    public void updatePosition(int pos, long now) {
        position.addPoint(pos);
    }

    public int getWins() {
        return winCount;
    }

    public int getLosses() {
        return lossCount;
    }

    public double getWinRatePct() {
        int total = winCount + lossCount;
        return total == 0 ? 0.0 : (100.0 * winCount / total);
    }

    public double getNetPnlDollars() {
        return netPnlDollars;
    }
}
