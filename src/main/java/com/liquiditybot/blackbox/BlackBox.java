package com.liquiditybot.blackbox;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The "black box" recorder.
 *
 * <p>Everything the bot sees and does is written here so that any trade (good or
 * bad) can be reconstructed and diagnosed afterwards: wall lifecycle, wave state
 * transitions, entry/exit decisions WITH the reason, order/position updates and
 * - importantly - where price went AFTER a trade closed.
 *
 * <p>Writing happens on a dedicated background thread so the market-data thread
 * is never blocked by disk I/O. Output is line-delimited JSON (.jsonl) which is
 * trivial to grep/replay, plus a human readable .log mirror.
 */
public class BlackBox {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final BlockingQueue<String[]> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile boolean running = true;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final String safeAlias;
    private Writer jsonWriter;
    private Writer textWriter;
    private Path jsonPath;
    private String openDay;

    public BlackBox(String alias) {
        this.safeAlias = alias == null ? "unknown" : alias.replaceAll("[^a-zA-Z0-9._-]", "_");
        openWritersForToday();
        this.worker = new Thread(this::drainLoop, "liquidity-bot-blackbox");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * One file per UTC day, opened in append mode: every reload/restart during
     * the same day keeps writing to the same file, and a fresh file starts
     * automatically when the day rolls over.
     */
    private void openWritersForToday() {
        Path dir = resolveLogDir();
        String day = DAY.format(Instant.now());
        try {
            Files.createDirectories(dir);
            this.jsonPath = dir.resolve("blackbox-" + safeAlias + "-" + day + ".jsonl");
            Path textPath = dir.resolve("blackbox-" + safeAlias + "-" + day + ".log");
            this.jsonWriter = new BufferedWriter(Files.newBufferedWriter(jsonPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            this.textWriter = new BufferedWriter(Files.newBufferedWriter(textPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            this.openDay = day;
        } catch (IOException e) {
            // Logging must never crash the bot. Fall back to no-op writers.
            this.jsonWriter = null;
            this.textWriter = null;
            this.openDay = day;
        }
    }

    private void rotateIfNewDay() {
        String day = DAY.format(Instant.now());
        if (day.equals(openDay)) {
            return;
        }
        flush();
        closeQuietly(jsonWriter);
        closeQuietly(textWriter);
        openWritersForToday();
    }

    private static Path resolveLogDir() {
        // Prefer a stable, user-writable location.
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".liquidity-wall-bot", "logs");
    }

    /** Absolute path to the JSONL black-box file (for surfacing to the user). */
    public String getJsonPath() {
        return jsonPath == null ? "(disabled)" : jsonPath.toAbsolutePath().toString();
    }

    /**
     * Record one event.
     *
     * @param dataTimeMs market data time in millis (from Bookmap's clock; works in replay)
     * @param type       short machine-readable event type, e.g. "ENTRY", "WALL_CONFIRMED"
     * @param fields     alternating key, value pairs describing the event
     */
    public void log(long dataTimeMs, String type, Object... fields) {
        if (!running) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataTime", dataTimeMs);
        m.put("type", type);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            m.put(String.valueOf(fields[i]), fields[i + 1]);
        }
        String wall = TS.format(Instant.now());
        queue.offer(new String[] {toJson(m), wall + " [" + type + "] " + toText(m)});
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            try {
                String[] line = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (line == null) {
                    flush();
                    continue;
                }
                rotateIfNewDay();
                if (jsonWriter != null) {
                    jsonWriter.write(line[0]);
                    jsonWriter.write('\n');
                }
                if (textWriter != null) {
                    textWriter.write(line[1]);
                    textWriter.write('\n');
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                // give up on this line, keep running
            }
        }
        flush();
        closeQuietly(jsonWriter);
        closeQuietly(textWriter);
    }

    private void flush() {
        try {
            if (jsonWriter != null) {
                jsonWriter.flush();
            }
            if (textWriter != null) {
                textWriter.flush();
            }
        } catch (IOException ignored) {
            // ignored
        }
    }

    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- tiny dependency-free serializers (avoid pulling a json lib) ---

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String toText(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if ("type".equals(e.getKey())) {
                continue;
            }
            if (!first) {
                sb.append(' ');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void closeQuietly(Writer w) {
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
                // ignored
            }
        }
    }
}
