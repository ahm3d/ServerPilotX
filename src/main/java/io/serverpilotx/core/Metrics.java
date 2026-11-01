package io.serverpilotx.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Samples /proc on the active host at a fixed interval. Keeps a live in-memory buffer and
 * stores one averaged point per minute on disk, so the dashboard can show 1h / 24h / 7d / 30d.
 */
public final class Metrics {

    public record Sample(long time, double cpu, double[] cores, long memUsed, long memTotal, long swapUsed,
                         long swapTotal, long diskUsed, long diskTotal, double load1, double load5, double load15,
                         double rxBps, double txBps, double readBps, double writeBps, long uptimeSec, double tempC) {
        public double memPct() { return memTotal == 0 ? 0 : 100.0 * memUsed / memTotal; }
        public double diskPct() { return diskTotal == 0 ? 0 : 100.0 * diskUsed / diskTotal; }
        public double swapPct() { return swapTotal == 0 ? 0 : 100.0 * swapUsed / swapTotal; }
    }

    /** One point in a history series. */
    public record Point(long time, double cpu, double mem, double disk, double load, double rx, double tx) {}

    private static final String SCRIPT = "cat /proc/stat; echo ===; cat /proc/meminfo; echo ===; cat /proc/loadavg;"
            + " echo ===; cat /proc/net/dev; echo ===; cat /proc/uptime; echo ===; df -P -B1 / | tail -n 1;"
            + " echo ===; cat /proc/diskstats; echo ===; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null; true";

    private static final Metrics INSTANCE = new Metrics();

    public static Metrics get() { return INSTANCE; }

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spx-metrics");
        t.setDaemon(true);
        return t;
    });
    private final List<Consumer<Sample>> listeners = new CopyOnWriteArrayList<>();
    private final Deque<Sample> live = new ArrayDeque<>();
    private ScheduledFuture<?> task;
    private Host host;
    private volatile Sample last;
    private volatile String error;

    // previous counters for deltas
    private long[] prevCpu;
    private long[][] prevCores;
    private long prevRx, prevTx, prevRd, prevWr, prevTime;

    // minute aggregation
    private long minuteKey = -1;
    private int mCount;
    private double mCpu, mMem, mDisk, mLoad, mRx, mTx;

    private Metrics() {}

    public void onSample(Consumer<Sample> l) { listeners.add(l); }

    public Sample last() { return last; }

    public String error() { return error; }

    public synchronized void start(Host h) {
        if (task != null) task.cancel(false);
        host = h;
        synchronized (live) { live.clear(); }
        prevCpu = null;
        prevCores = null;
        prevTime = 0;
        last = null;
        minuteKey = -1;
        mCount = 0;
        exec.execute(this::prune);
        task = exec.scheduleWithFixedDelay(this::tick, 0, Settings.sampleSeconds(), TimeUnit.SECONDS);
    }

    private void tick() {
        Host h = host;
        if (h == null) return;
        try {
            CmdResult r = h.sh(SCRIPT);
            if (!r.ok() && r.out().isBlank()) {
                error = r.text();
                return;
            }
            Sample s = parse(r.out());
            error = null;
            if (s == null) return; // first sample only primes the counters
            last = s;
            synchronized (live) {
                live.addLast(s);
                while (live.size() > 3600) live.removeFirst();
            }
            aggregate(h, s);
            for (Consumer<Sample> l : listeners) l.accept(s);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
    }

    private Sample parse(String out) {
        String[] parts = out.split("===\\R?");
        if (parts.length < 7) return null;
        long now = System.currentTimeMillis();

        // CPU
        long[] total = null;
        List<long[]> cores = new ArrayList<>();
        for (String line : parts[0].split("\\R")) {
            if (line.startsWith("cpu ")) total = cpuFields(line);
            else if (line.startsWith("cpu")) cores.add(cpuFields(line));
        }
        double cpu = 0;
        double[] corePct = new double[cores.size()];
        boolean primed = prevCpu != null;
        if (primed && total != null) {
            cpu = pct(prevCpu, total);
            for (int i = 0; i < cores.size() && prevCores != null && i < prevCores.length; i++) {
                corePct[i] = pct(prevCores[i], cores.get(i));
            }
        }
        prevCpu = total;
        prevCores = cores.toArray(new long[0][]);

        // memory
        long memTotal = 0, memAvail = 0, swapTotal = 0, swapFree = 0;
        for (String line : parts[1].split("\\R")) {
            String[] f = line.split("\\s+");
            if (f.length < 2) continue;
            long kb = parseLong(f[1]) * 1024;
            switch (f[0]) {
                case "MemTotal:" -> memTotal = kb;
                case "MemAvailable:" -> memAvail = kb;
                case "SwapTotal:" -> swapTotal = kb;
                case "SwapFree:" -> swapFree = kb;
                default -> { }
            }
        }

        String[] la = parts[2].strip().split("\\s+");
        double l1 = parseD(la, 0), l5 = parseD(la, 1), l15 = parseD(la, 2);

        // network (all interfaces except loopback and virtual bridges)
        long rx = 0, tx = 0;
        for (String line : parts[3].split("\\R")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String ifn = line.substring(0, c).strip();
            if (ifn.equals("lo") || ifn.startsWith("veth") || ifn.startsWith("docker") || ifn.startsWith("br-")) continue;
            String[] f = line.substring(c + 1).strip().split("\\s+");
            if (f.length < 9) continue;
            rx += parseLong(f[0]);
            tx += parseLong(f[8]);
        }

        long uptime = (long) parseD(parts[4].strip().split("\\s+"), 0);

        String[] df = parts[5].strip().split("\\s+");
        long diskTotal = df.length > 3 ? parseLong(df[1]) : 0;
        long diskUsed = df.length > 3 ? parseLong(df[2]) : 0;

        long rd = 0, wr = 0;
        for (String line : parts[6].split("\\R")) {
            String[] f = line.strip().split("\\s+");
            if (f.length < 10) continue;
            String dev = f[2];
            if (dev.matches("(sd[a-z]+|vd[a-z]+|xvd[a-z]+|hd[a-z]+|nvme\\d+n\\d+|mmcblk\\d+)")) {
                rd += parseLong(f[5]) * 512;
                wr += parseLong(f[9]) * 512;
            }
        }

        double temp = Double.NaN;
        if (parts.length > 7 && !parts[7].isBlank()) {
            try { temp = Long.parseLong(parts[7].strip().split("\\R")[0]) / 1000.0; } catch (Exception ignored) { }
        }

        double secs = prevTime == 0 ? 0 : (now - prevTime) / 1000.0;
        double rxBps = secs > 0 ? Math.max(0, (rx - prevRx) / secs) : 0;
        double txBps = secs > 0 ? Math.max(0, (tx - prevTx) / secs) : 0;
        double rdBps = secs > 0 ? Math.max(0, (rd - prevRd) / secs) : 0;
        double wrBps = secs > 0 ? Math.max(0, (wr - prevWr) / secs) : 0;
        prevRx = rx; prevTx = tx; prevRd = rd; prevWr = wr; prevTime = now;

        if (!primed) return null;
        return new Sample(now, cpu, corePct, memTotal - memAvail, memTotal, swapTotal - swapFree, swapTotal,
                diskUsed, diskTotal, l1, l5, l15, rxBps, txBps, rdBps, wrBps, uptime, temp);
    }

    private static long[] cpuFields(String line) {
        String[] f = line.strip().split("\\s+");
        long[] v = new long[f.length - 1];
        for (int i = 1; i < f.length; i++) v[i - 1] = parseLong(f[i]);
        return v;
    }

    private static double pct(long[] a, long[] b) {
        if (a == null || b == null) return 0;
        long ta = 0, tb = 0;
        for (int i = 0; i < Math.min(8, Math.min(a.length, b.length)); i++) { ta += a[i]; tb += b[i]; }
        long idleA = a[3] + (a.length > 4 ? a[4] : 0);
        long idleB = b[3] + (b.length > 4 ? b[4] : 0);
        long dt = tb - ta;
        if (dt <= 0) return 0;
        return Math.max(0, Math.min(100, 100.0 * (dt - (idleB - idleA)) / dt));
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.strip()); } catch (Exception e) { return 0; }
    }

    private static double parseD(String[] f, int i) {
        try { return Double.parseDouble(f[i]); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------- history

    private Path file(Host h) {
        Path dir = Settings.dataDir().resolve("metrics");
        try { Files.createDirectories(dir); } catch (IOException ignored) { }
        return dir.resolve(h.id().replaceAll("[^A-Za-z0-9_-]", "_") + ".csv");
    }

    private void aggregate(Host h, Sample s) {
        long key = s.time() / 60_000;
        if (minuteKey != -1 && key != minuteKey && mCount > 0) {
            String line = String.format(Locale.ROOT, "%d,%.2f,%.2f,%.2f,%.2f,%.0f,%.0f%n", minuteKey * 60,
                    mCpu / mCount, mMem / mCount, mDisk / mCount, mLoad / mCount, mRx / mCount, mTx / mCount);
            try {
                Files.writeString(file(h), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
            mCount = 0;
            mCpu = mMem = mDisk = mLoad = mRx = mTx = 0;
        }
        minuteKey = key;
        mCount++;
        mCpu += s.cpu();
        mMem += s.memPct();
        mDisk += s.diskPct();
        mLoad += s.load1();
        mRx += s.rxBps();
        mTx += s.txBps();
    }

    private void prune() {
        Host h = host;
        if (h == null) return;
        Path f = file(h);
        if (!Files.exists(f)) return;
        long cutoff = System.currentTimeMillis() / 1000 - Settings.retentionDays() * 86400L;
        try {
            List<String> keep = new ArrayList<>();
            for (String line : Files.readAllLines(f)) {
                int c = line.indexOf(',');
                if (c > 0 && parseLong(line.substring(0, c)) >= cutoff) keep.add(line);
            }
            Files.write(f, keep);
        } catch (IOException ignored) { }
    }

    /** History for the last {@code seconds}, downsampled to at most {@code maxPoints}. */
    public List<Point> history(long seconds, int maxPoints) {
        long from = System.currentTimeMillis() - seconds * 1000;
        List<Point> pts = new ArrayList<>();
        long liveStart;
        synchronized (live) {
            liveStart = live.isEmpty() ? Long.MAX_VALUE : live.peekFirst().time();
        }
        Host h = host;
        if (h != null && from < liveStart) {
            Path f = file(h);
            if (Files.exists(f)) {
                try {
                    for (String line : Files.readAllLines(f)) {
                        String[] v = line.split(",");
                        if (v.length < 7) continue;
                        long t = parseLong(v[0]) * 1000;
                        if (t < from || t >= liveStart) continue;
                        pts.add(new Point(t, parseD(v, 1), parseD(v, 2), parseD(v, 3), parseD(v, 4), parseD(v, 5), parseD(v, 6)));
                    }
                } catch (IOException ignored) { }
            }
        }
        synchronized (live) {
            for (Sample s : live) {
                if (s.time() >= from) {
                    pts.add(new Point(s.time(), s.cpu(), s.memPct(), s.diskPct(), s.load1(), s.rxBps(), s.txBps()));
                }
            }
        }
        if (pts.size() <= maxPoints) return pts;
        List<Point> out = new ArrayList<>();
        double per = (double) pts.size() / maxPoints;
        for (int b = 0; b < maxPoints; b++) {
            int a = (int) (b * per), e = (int) Math.min(pts.size(), (b + 1) * per);
            if (e <= a) continue;
            double c = 0, m = 0, d = 0, l = 0, rx = 0, tx = 0;
            for (int i = a; i < e; i++) {
                Point p = pts.get(i);
                c += p.cpu(); m += p.mem(); d += p.disk(); l += p.load(); rx += p.rx(); tx += p.tx();
            }
            int n = e - a;
            out.add(new Point(pts.get(a).time(), c / n, m / n, d / n, l / n, rx / n, tx / n));
        }
        return out;
    }

    // ------------------------------------------------------------- format

    public static String bytes(double b) {
        String[] u = {"B", "KB", "MB", "GB", "TB", "PB"};
        int i = 0;
        while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
        return String.format(Locale.ROOT, i == 0 ? "%.0f %s" : "%.1f %s", b, u[i]);
    }

    public static String rate(double bps) { return bytes(bps) + "/s"; }

    public static String uptime(long s) {
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }
}
