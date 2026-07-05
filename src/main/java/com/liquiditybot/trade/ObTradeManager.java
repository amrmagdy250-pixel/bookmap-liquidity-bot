package com.liquiditybot.trade;

import com.liquiditybot.blackbox.BlackBox;
import com.liquiditybot.config.Settings;
import com.liquiditybot.detection.OrderBlockEngine;

import velox.api.layer1.data.SimpleOrderSendParametersBuilder;
import velox.api.layer1.simplified.Api;

/**
 * Trade lifecycle for the order-block engine, fully independent of the main
 * {@link TradeManager}: the OB engine holds its own position with its own
 * TP/SL, so an OB trade and a wall trade can be open at the same time.
 *
 * <p>TP/SL are self-managed by watching price (identical in replay and live).
 * In shadow mode the fill is simulated at the signal price; in live mode a
 * market order is sent and the fill is assumed at the signal price - the
 * position bookkeeping of the main manager is never touched.
 */
public class ObTradeManager {

    private final Api api;
    private final String alias;
    private final Settings settings;
    private final BlackBox blackBox;
    private final double multiplier;

    private boolean open = false;
    private boolean shadow;
    private long tradeSeq = 0;
    private long currentTradeId;
    private TradeSide side;
    private double entryPrice;
    private double tpPrice;
    private double slPrice;
    private long blockId;

    private int wins = 0;
    private int losses = 0;
    private double netPnlDollars = 0;
    private long cooldownUntil = 0;
    private long nowMs = 0;

    public ObTradeManager(Api api, String alias, Settings settings, BlackBox blackBox,
                          double multiplier) {
        this.api = api;
        this.alias = alias;
        this.settings = settings;
        this.blackBox = blackBox;
        this.multiplier = multiplier;
    }

    public void setNow(long nowMs) {
        this.nowMs = nowMs;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean canEnter() {
        return !open && nowMs >= cooldownUntil;
    }

    public double getEntryPrice() {
        return entryPrice;
    }

    public double getTakeProfitPrice() {
        return tpPrice;
    }

    public double getStopLossPrice() {
        return slPrice;
    }

    public boolean openTrade(OrderBlockEngine.Block block, double price) {
        if (!canEnter()) {
            return false;
        }
        this.shadow = !settings.enableTrading;
        this.currentTradeId = ++tradeSeq;
        this.side = block.side;
        this.blockId = block.id;
        this.entryPrice = price;
        if (side == TradeSide.LONG) {
            this.tpPrice = price + settings.obTakeProfitDollars;
            this.slPrice = price - settings.obStopLossDollars;
        } else {
            this.tpPrice = price - settings.obTakeProfitDollars;
            this.slPrice = price + settings.obStopLossDollars;
        }
        this.open = true;

        blackBox.log(nowMs, "OB_ENTRY",
                "obTradeId", currentTradeId,
                "blockId", blockId,
                "side", side,
                "entryPrice", round(entryPrice),
                "takeProfit", round(tpPrice),
                "stopLoss", round(slPrice),
                "blockLow", round(block.low),
                "blockHigh", round(block.high),
                "blockVolume", block.volume,
                "blockDelta", block.delta,
                "shadow", shadow);

        if (!shadow) {
            api.sendOrder(new SimpleOrderSendParametersBuilder(alias, side.isBuy,
                    settings.obOrderSize).build());
        }
        return true;
    }

    /** Feed every price update; drives the self-managed TP/SL. */
    public void onPrice(double price) {
        if (!open) {
            return;
        }
        String reason = null;
        if (side == TradeSide.LONG) {
            if (price >= tpPrice) {
                reason = "TAKE_PROFIT";
            } else if (price <= slPrice) {
                reason = "STOP_LOSS";
            }
        } else {
            if (price <= tpPrice) {
                reason = "TAKE_PROFIT";
            } else if (price >= slPrice) {
                reason = "STOP_LOSS";
            }
        }
        if (reason != null) {
            close(price, reason);
        }
    }

    private void close(double exitPrice, String reason) {
        double pnlPrice = (exitPrice - entryPrice) * side.sign;
        double pnlDollars = pnlPrice * multiplier * settings.obOrderSize;
        if (pnlPrice > 0) {
            wins++;
        } else {
            losses++;
        }
        netPnlDollars += pnlDollars;

        blackBox.log(nowMs, "OB_TRADE_CLOSED",
                "obTradeId", currentTradeId,
                "blockId", blockId,
                "side", side,
                "entryPrice", round(entryPrice),
                "exitPrice", round(exitPrice),
                "reason", reason,
                "pnlPrice", round(pnlPrice),
                "pnlDollars", round(pnlDollars),
                "obWins", wins,
                "obLosses", losses,
                "obNetPnlDollars", round(netPnlDollars),
                "shadow", shadow);

        if (!shadow) {
            api.sendOrder(new SimpleOrderSendParametersBuilder(alias, side.opposite().isBuy,
                    settings.obOrderSize).build());
        }
        this.open = false;
        this.cooldownUntil = nowMs + settings.obCooldownMs;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
