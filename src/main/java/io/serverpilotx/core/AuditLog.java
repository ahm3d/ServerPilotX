package io.serverpilotx.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Append-only audit trail of every change made through the app (JSON lines). */
public final class AuditLog {
    public record Entry(long ts, String user, String server, String action, String target, String detail, boolean ok) {}

    private static final Path FILE = Settings.dataDir().resolve("audit.jsonl");
    private static final List<Consumer<Entry>> LISTENERS = new CopyOnWriteArrayList<>();

    private AuditLog() {}

    public static void onEntry(Consumer<Entry> l) { LISTENERS.add(l); }

    public static synchronized void log(String action, String target, String detail, boolean ok) {
        Host h = Hosts.active();
        Entry e = new Entry(System.currentTimeMillis(), System.getProperty("user.name"),
                h == null ? "local" : h.name(), action, target == null ? "" : target,
                detail == null ? "" : detail, ok);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", e.ts());
        m.put("user", e.user());
        m.put("server", e.server());
        m.put("action", e.action());
        m.put("target", e.target());
        m.put("detail", e.detail());
        m.put("ok", e.ok());
        try {
            Files.writeString(FILE, Json.write(m) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
        for (Consumer<Entry> l : LISTENERS) l.accept(e);
    }

    public static List<Entry> readAll() {
        List<Entry> out = new ArrayList<>();
        if (!Files.exists(FILE)) return out;
        try {
            for (Map<String, Object> m : Json.lines(Files.readString(FILE))) {
                Object ts = m.get("ts");
                out.add(new Entry(ts instanceof Number n ? n.longValue() : 0, Json.str(m, "user"), Json.str(m, "server"),
                        Json.str(m, "action"), Json.str(m, "target"), Json.str(m, "detail"),
                        Boolean.TRUE.equals(m.get("ok"))));
            }
        } catch (IOException ignored) { }
        return out;
    }

    public static Path file() { return FILE; }
}
