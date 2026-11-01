package io.serverpilotx.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** The list of configured servers and the one currently being managed. */
public final class Hosts {
    private static final List<Host> HOSTS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Host>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Host active;

    private Hosts() {}

    public static void load() {
        HOSTS.clear();
        HOSTS.add(Host.local());
        try {
            for (Object o : Json.arr(Settings.serversJson())) {
                if (o instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) raw;
                    Object port = m.get("port");
                    HOSTS.add(new Host(Json.str(m, "id"), Json.str(m, "name"), Json.str(m, "address"),
                            Json.str(m, "user"), port instanceof Number n ? n.intValue() : 22, Json.str(m, "identity")));
                }
            }
        } catch (RuntimeException ignored) { }
        String last = Settings.lastServer();
        active = HOSTS.stream().filter(h -> h.id().equals(last)).findFirst().orElse(HOSTS.get(0));
    }

    public static List<Host> all() { return List.copyOf(HOSTS); }

    public static Host active() { return active; }

    public static void setActive(Host h) {
        active = h;
        Settings.lastServer(h.id());
        for (Consumer<Host> l : LISTENERS) l.accept(h);
    }

    public static void onChange(Consumer<Host> l) { LISTENERS.add(l); }

    public static Host add(String name, String address, String user, int port, String identity) {
        Host h = new Host(UUID.randomUUID().toString(), name, address, user, port, identity);
        HOSTS.add(h);
        save();
        return h;
    }

    public static void remove(Host h) {
        if (h.isLocal()) return;
        HOSTS.remove(h);
        save();
        if (active == h) setActive(HOSTS.get(0));
    }

    private static void save() {
        List<Object> l = new ArrayList<>();
        for (Host h : HOSTS) if (!h.isLocal()) l.add(h.toJson());
        Settings.serversJson(Json.write(l));
    }
}
