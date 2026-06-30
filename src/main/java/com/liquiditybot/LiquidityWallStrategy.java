package com.liquiditybot;

import java.awt.Color;
import java.awt.GridLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import com.liquiditybot.blackbox.BlackBox;
import com.liquiditybot.book.LiquidityWall;
import com.liquiditybot.book.OrderBook;
import com.liquiditybot.config.Settings;
import com.liquiditybot.detection.WallDetector;
import com.liquiditybot.detection.WaveTracker;
import com.liquiditybot.indicators.StatsIndicators;
import com.liquiditybot.trade.TradeManager;
import com.liquiditybot.trade.TradeSide;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.BalanceInfo;
import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.OrderInfoUpdate;
import velox.api.layer1.data.StatusInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BalanceListener;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.OrdersListener;
import velox.api.layer1.simplified.PositionListener;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.gui.StrategyPanel;

/**
 * Liquidity Wall Bot - a Bookmap (Simplified API) add-on.
 *
 * <p>Strategy in one line: treat a large, persistent resting wall as a price
 * <b>magnet/target</b> and enter TOWARD it - but only after price makes a real
 * wave away from the wall, entering at the far turning point so the move back
 * toward the wall is the edge and the wide stop absorbs the noise. The opposite
 * of the usual "wait at the wall and fade it" play.
 *
 * <p>See {@code docs/STRATEGY.md} for the full description and the data flow.
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Liquidity Wall Bot")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class LiquidityWallStrategy implements
        CustomModule,
        DepthDataListener,
        TradeDataListener,
        BboListener,
        TimeListener,
        OrdersListener,
        PositionListener,
        BalanceListener,
        CustomSettingsPanelProvider {

    private Api api;
    private Settings settings;
    private String alias;
    private double pips;

    private BlackBox blackBox;
    private OrderBook book;
    private WallDetector detector;
    private WaveTracker waveTracker;
    private TradeManager tradeManager;
    private StatsIndicators stats;
    private Indicator activeWallIndicator;

    private long nowMs = 0;
    private long lastDetectMs = 0;
    private long activeTargetId = -1;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.api = api;
        this.alias = alias;
        this.pips = info.pips > 0 ? info.pips : 1.0;
        this.settings = api.getSettings(Settings.class);

        this.blackBox = new BlackBox(alias);
        this.book = new OrderBook(pips);
        this.stats = new StatsIndicators(api);
        this.tradeManager = new TradeManager(api, alias, settings, blackBox, stats, info.multiplier);
        this.waveTracker = new WaveTracker(settings);
        this.detector = new WallDetector(settings, pips, new WallDetector.Listener() {
            @Override
            public void onWallConfirmed(LiquidityWall wall, long now) {
                blackBox.log(now, "WALL_CONFIRMED", "wallId", wall.id, "side", wall.side,
                        "price", wall.price, "size", wall.size, "ageMs", wall.ageMs(now));
            }

            @Override
            public void onWallBroken(LiquidityWall wall, long now, String reason) {
                blackBox.log(now, "WALL_BROKEN", "wallId", wall.id, "side", wall.side,
                        "price", wall.price, "peakSize", wall.peakSize, "reason", reason);
            }
        });

        this.activeWallIndicator = api.registerIndicator("Active wall target", GraphType.PRIMARY);
        this.activeWallIndicator.setColor(new Color(255, 140, 0));
        this.activeWallIndicator.setWidth(2);

        blackBox.log(nowMs, "INIT", "alias", alias, "pips", pips, "multiplier", info.multiplier,
                "enableTrading", settings.enableTrading, "magnetMode", settings.magnetMode,
                "takeProfit", settings.takeProfitDollars, "stopLoss", settings.stopLossDollars,
                "wallMinSize", settings.wallMinSize, "waveMinAmplitude", settings.waveMinAmplitudeDollars);
        Log.info("[LiquidityWallBot] initialized on " + alias + " (trading="
                + settings.enableTrading + ", blackbox=" + blackBox.getJsonPath() + ")");
    }

    @Override
    public void stop() {
        if (blackBox != null) {
            blackBox.log(nowMs, "STOP", "alias", alias, "wins", stats.getWins(),
                    "losses", stats.getLosses(), "winRatePct", stats.getWinRatePct(),
                    "netPnlDollars", stats.getNetPnlDollars());
            blackBox.close();
        }
    }

    // --- market data ---------------------------------------------------------

    @Override
    public void onTimestamp(long t) {
        this.nowMs = toMs(t);
        if (tradeManager != null) {
            tradeManager.setNow(nowMs);
        }
    }

    @Override
    public void onDepth(boolean isBid, int price, int size) {
        book.onDepth(isBid, price, size);
        maybeDetect();
    }

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        book.onBbo(bidPrice, askPrice);
        Double mid = book.midPrice();
        if (mid != null) {
            maybeDetect();
            onPriceUpdate(mid);
        }
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        stats.onTrade(size, tradeInfo.isBidAggressor);
        onPriceUpdate(price);
    }

    private void maybeDetect() {
        Double mid = book.midPrice();
        if (mid == null) {
            return;
        }
        if (nowMs - lastDetectMs < settings.detectionThrottleMs) {
            return;
        }
        lastDetectMs = nowMs;
        detector.detect(book, mid, nowMs);
        if (tradeManager.isFlat()) {
            ensureTarget(mid);
        }
    }

    /** Core decision tick. */
    private void onPriceUpdate(double price) {
        tradeManager.setNow(nowMs);
        tradeManager.onPrice(price);

        if (!tradeManager.isFlat()) {
            return;
        }
        ensureTarget(price);

        if (activeTargetId != -1) {
            activeWallIndicator.addPoint(findWall(activeTargetId) != null
                    ? findWall(activeTargetId).price : price);
        }

        if (waveTracker.isArmed() && tradeManager.canEnter()) {
            WaveTracker.EntrySignal sig = waveTracker.onPrice(price);
            if (sig != null) {
                LiquidityWall wall = findWall(activeTargetId);
                if (wall != null) {
                    tradeManager.onEntrySignal(sig, wall);
                }
            }
        }
    }

    /**
     * Pick / keep the active target wall. We keep the current target while it is
     * still confirmed; only when it breaks do we move to the next-nearest one -
     * this gives the requested "nearest first, then the next when the first is
     * gone" behaviour for stacked walls.
     */
    private void ensureTarget(double price) {
        if (activeTargetId != -1 && detector.isStillActive(activeTargetId)) {
            return;
        }
        if (activeTargetId != -1) {
            blackBox.log(nowMs, "TARGET_LOST", "wallId", activeTargetId);
            activeTargetId = -1;
            waveTracker.disarm();
            tradeManager.clearActiveWallIfFlat();
        }

        LiquidityWall nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (LiquidityWall w : detector.allConfirmed()) {
            double d = Math.abs(w.price - price);
            if (d <= settings.maxWallDistanceDollars && d < bestDist) {
                bestDist = d;
                nearest = w;
            }
        }
        if (nearest == null) {
            return;
        }

        TradeSide side = mapSide(nearest.side);
        activeTargetId = nearest.id;
        waveTracker.arm(side, nearest.price, price);
        blackBox.log(nowMs, "TARGET_SET", "wallId", nearest.id, "wallSide", nearest.side,
                "wallPrice", nearest.price, "wallSize", nearest.size, "tradeSide", side,
                "distance", Math.round(bestDist * 100.0) / 100.0);
    }

    /** Magnet mode trades toward the wall; fade mode trades away from it. */
    private TradeSide mapSide(LiquidityWall.Side wallSide) {
        boolean aboveIsLong = settings.magnetMode;
        if (wallSide == LiquidityWall.Side.ASK) {
            return aboveIsLong ? TradeSide.LONG : TradeSide.SHORT;
        } else {
            return aboveIsLong ? TradeSide.SHORT : TradeSide.LONG;
        }
    }

    private LiquidityWall findWall(long id) {
        for (LiquidityWall w : detector.allConfirmed()) {
            if (w.id == id) {
                return w;
            }
        }
        return null;
    }

    // --- order / account callbacks ------------------------------------------

    @Override
    public void onOrderExecuted(ExecutionInfo executionInfo) {
        tradeManager.onOrderExecuted(executionInfo);
        blackBox.log(nowMs, "ORDER_EXECUTED", "orderId", executionInfo.orderId,
                "size", executionInfo.size, "price", executionInfo.price,
                "simulated", executionInfo.isSimulated);
    }

    @Override
    public void onOrderUpdated(OrderInfoUpdate orderInfoUpdate) {
        blackBox.log(nowMs, "ORDER_UPDATE", "orderId", orderInfoUpdate.orderId,
                "status", orderInfoUpdate.status, "filled", orderInfoUpdate.filled,
                "unfilled", orderInfoUpdate.unfilled);
    }

    @Override
    public void onPositionUpdate(StatusInfo statusInfo) {
        tradeManager.onPositionUpdate(statusInfo);
    }

    @Override
    public void onBalance(BalanceInfo balanceInfo) {
        // not used by the strategy; recorded for completeness
        if (!balanceInfo.balancesInCurrency.isEmpty()) {
            blackBox.log(nowMs, "BALANCE",
                    "balance", balanceInfo.balancesInCurrency.get(0).balance);
        }
    }

    private static long toMs(long t) {
        // Bookmap times are nanoseconds since epoch; be tolerant of other scales.
        if (t > 100_000_000_000_000_000L) {
            return t / 1_000_000L; // nanos
        }
        if (t > 100_000_000_000_000L) {
            return t / 1_000L; // micros
        }
        return t; // millis
    }

    // --- settings UI ---------------------------------------------------------

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        if (settings == null) {
            // Panel may be requested while the module is not loaded; show defaults.
            settings = new Settings();
        }
        StrategyPanel main = new StrategyPanel("Liquidity Wall Bot");
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));

        JCheckBox tradingBox = new JCheckBox("Enable live/simulated trading (off = shadow logging only)",
                settings.enableTrading);
        tradingBox.addActionListener(e -> {
            settings.enableTrading = tradingBox.isSelected();
            persist();
        });

        JCheckBox magnetBox = new JCheckBox("Magnet mode (trade toward the wall)", settings.magnetMode);
        magnetBox.addActionListener(e -> {
            settings.magnetMode = magnetBox.isSelected();
            persist();
        });

        main.add(tradingBox);
        main.add(magnetBox);
        main.add(grid(
                spinner("Take profit ($)", settings.takeProfitDollars, 0.25, 1000, 0.25,
                        v -> settings.takeProfitDollars = v),
                spinner("Stop loss ($)", settings.stopLossDollars, 0.25, 1000, 0.25,
                        v -> settings.stopLossDollars = v),
                spinner("Wall min size", settings.wallMinSize, 1, 100000, 1,
                        v -> settings.wallMinSize = (int) v),
                spinner("Wall dominance (x avg)", settings.wallDominanceRatio, 1, 100, 0.5,
                        v -> settings.wallDominanceRatio = v),
                spinner("Wall cluster (ticks)", settings.wallClusterTicks, 0, 50, 1,
                        v -> settings.wallClusterTicks = (int) v),
                spinner("Wall persistence (ms)", settings.wallPersistenceMs, 0, 60000, 100,
                        v -> settings.wallPersistenceMs = (long) v),
                spinner("Max wall distance ($)", settings.maxWallDistanceDollars, 1, 10000, 1,
                        v -> settings.maxWallDistanceDollars = v),
                spinner("Wave min amplitude ($)", settings.waveMinAmplitudeDollars, 0, 1000, 0.5,
                        v -> settings.waveMinAmplitudeDollars = v),
                spinner("Min entry dist from wall ($)", settings.minEntryDistanceFromWallDollars, 0, 1000, 0.5,
                        v -> settings.minEntryDistanceFromWallDollars = v),
                spinner("Max entry dist from wall ($)", settings.maxEntryDistanceFromWallDollars, 0, 1000, 0.5,
                        v -> settings.maxEntryDistanceFromWallDollars = v),
                spinner("Turn confirm ($)", settings.turnConfirmDollars, 0, 100, 0.25,
                        v -> settings.turnConfirmDollars = v),
                spinner("Order size", settings.orderSize, 1, 1000, 1,
                        v -> settings.orderSize = (int) v),
                spinner("Cooldown after trade (ms)", settings.cooldownMsAfterTrade, 0, 600000, 500,
                        v -> settings.cooldownMsAfterTrade = (long) v)));

        JButton reload = new JButton("Apply & reload");
        reload.addActionListener(e -> api.reload());
        main.add(reload);

        return new StrategyPanel[] {main};
    }

    private void persist() {
        if (api != null) {
            api.setSettings(settings);
        }
    }

    private JPanel grid(JComponent... rows) {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        for (JComponent c : rows) {
            p.add(c);
        }
        return p;
    }

    private interface DoubleSetter {
        void set(double v);
    }

    private JComponent spinner(String label, double value, double min, double max, double step,
                               DoubleSetter setter) {
        JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
        JSpinner sp = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        sp.addChangeListener(e -> {
            setter.set(((Number) sp.getValue()).doubleValue());
            persist();
        });
        row.add(new JLabel(label));
        row.add(sp);
        return row;
    }
}
