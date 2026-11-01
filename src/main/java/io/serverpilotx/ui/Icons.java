package io.serverpilotx.ui;

import java.util.HashMap;
import java.util.Map;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Line icons drawn on a 24×24 grid and rendered as stroked SVG paths (themable via CSS). */
public final class Icons {
    private static final Map<String, String> P = new HashMap<>();

    static {
        P.put("dashboard", "M3 3h7v9H3z M14 3h7v5h-7z M14 12h7v9h-7z M3 16h7v5H3z");
        P.put("server", "M3 4h18v6H3z M3 14h18v6H3z M7 7h.01 M7 17h.01 M11 7h6 M11 17h6");
        P.put("docker", "M12 2.5l8.5 4.75v9.5L12 21.5l-8.5-4.75v-9.5z M3.5 7.25L12 12l8.5-4.75 M12 12v9.5");
        P.put("globe", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z M2 12h20 M12 2c2.8 2.9 4.2 6.2 4.2 10s-1.4 7.1-4.2 10c-2.8-2.9-4.2-6.2-4.2-10s1.4-7.1 4.2-10z");
        P.put("lock", "M5 11h14v10H5z M8 11V7.5a4 4 0 0 1 8 0V11 M12 15v2");
        P.put("shield", "M12 2.5l8 3v6c0 5-3.4 8.8-8 10.5-4.6-1.7-8-5.5-8-10.5v-6z M8.5 12l2.5 2.5 4.5-5");
        P.put("users", "M9 11a4 4 0 1 0 0-8a4 4 0 1 0 0 8z M2 21v-1a6 6 0 0 1 6-6h2a6 6 0 0 1 6 6v1 M16 3.2a4 4 0 0 1 0 7.6 M22 21v-1a6 6 0 0 0-4-5.6");
        P.put("key", "M7.5 15.5a4.5 4.5 0 1 0 0-9a4.5 4.5 0 1 0 0 9z M12 11h9 M18 11v3 M21 11v2");
        P.put("services", "M4 4h16v4H4z M4 10h16v4H4z M4 16h16v4H4z M7 6h.01 M7 12h.01 M7 18h.01");
        P.put("clock", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z M12 6v6l4 2");
        P.put("cpu", "M6 6h12v12H6z M9 9h6v6H9z M9 2v4 M15 2v4 M9 18v4 M15 18v4 M2 9h4 M2 15h4 M18 9h4 M18 15h4");
        P.put("folder", "M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2.5h8.5A1.5 1.5 0 0 1 21 9v9.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5z");
        P.put("file", "M6 2.5h8l5 5V21.5H6z M14 2.5v5h5");
        P.put("terminal", "M3 4h18v16H3z M7 9l3 3-3 3 M12.5 15h4.5");
        P.put("audit", "M8 3h8v3H8z M6 4.5H4.5V21h15V4.5H18 M8 11h8 M8 15h5");
        P.put("settings", "M4 6h9 M17 6h3 M4 12h3 M11 12h9 M4 18h11 M19 18h1 M15 4v4 M9 10v4 M17 16v4");
        P.put("sun", "M12 7.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9z M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2 12h2 M20 12h2 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4");
        P.put("moon", "M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z");
        P.put("refresh", "M20 11a8 8 0 0 0-14.6-4.5L4 8 M4 4v4h4 M4 13a8 8 0 0 0 14.6 4.5L20 16 M20 20v-4h-4");
        P.put("plus", "M12 5v14 M5 12h14");
        P.put("play", "M7 4.5v15l12-7.5z");
        P.put("stop", "M6 6h12v12H6z");
        P.put("pause", "M8 5v14 M16 5v14");
        P.put("restart", "M4 12a8 8 0 1 0 2.5-5.8 M4 4v5h5");
        P.put("trash", "M4 6.5h16 M9 6.5V4h6v2.5 M6.5 6.5l1 14h9l1-14 M10 10.5v6 M14 10.5v6");
        P.put("edit", "M4 20h4L19 9l-4-4L4 16z M13.5 6.5l4 4");
        P.put("logs", "M5 3h14v18H5z M8.5 8h7 M8.5 12h7 M8.5 16h4");
        P.put("search", "M10.5 3.5a7 7 0 1 0 0 14a7 7 0 1 0 0-14z M15.5 15.5L21 21");
        P.put("download", "M12 3v12 M7 10.5l5 5 5-5 M4 20h16");
        P.put("upload", "M12 16V4 M7 8.5l5-5 5 5 M4 20h16");
        P.put("check", "M5 12.5l4.5 4.5L19 7.5");
        P.put("x", "M6 6l12 12 M18 6L6 18");
        P.put("alert", "M12 3L2 20.5h20z M12 10v4.5 M12 17.5h.01");
        P.put("info", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z M12 11v6 M12 7.5h.01");
        P.put("power", "M12 3v8 M6.3 6.8a8 8 0 1 0 11.4 0");
        P.put("chevron-right", "M9 5l7 7-7 7");
        P.put("chevron-down", "M5 9l7 7 7-7");
        P.put("arrow-up", "M12 20V4 M5.5 10.5L12 4l6.5 6.5");
        P.put("arrow-down", "M12 4v16 M5.5 13.5L12 20l6.5-6.5");
        P.put("layers", "M12 3l9 5-9 5-9-5z M3 13l9 5 9-5 M3 16.5l9 5 9-5");
        P.put("network", "M12 3v5 M12 8a2.5 2.5 0 1 0 0 .01z M5 21v-4h14v4 M12 13v4 M9.5 8h-4.5v5 M14.5 8h4.5v5");
        P.put("database", "M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3z M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6 M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3");
        P.put("package", "M3.5 7.5L12 3l8.5 4.5v9L12 21l-8.5-4.5z M3.5 7.5L12 12l8.5-4.5 M12 12v9 M7.8 5.2l8.5 4.6");
        P.put("rocket", "M12 15l-3-3c1.5-5 4.5-8.5 11-9-.5 6.5-4 9.5-9 11z M9 12l-4.5-.5L7 8.5h4 M12 15l.5 4.5L15.5 17v-4 M6 16c-1.5.5-2.5 2-2.5 4.5 2.5 0 4-1 4.5-2.5");
        P.put("eye", "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7z M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z");
        P.put("link", "M10 14a4.5 4.5 0 0 0 6.4 0l3.2-3.2a4.5 4.5 0 0 0-6.4-6.4L12 5.6 M14 10a4.5 4.5 0 0 0-6.4 0l-3.2 3.2a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2");
        P.put("home", "M3 11l9-7.5 9 7.5 M5.5 9v11.5h13V9 M10 20.5v-6h4v6");
        P.put("up", "M12 19V5 M5 12l7-7 7 7");
        P.put("more", "M5 12h.01 M12 12h.01 M19 12h.01");
        P.put("activity", "M3 12h4l3-8 4 16 3-8h4");
        P.put("command", "M9 6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3z");
        P.put("archive", "M3 4h18v4H3z M5 8v12h14V8 M10 12h4");
        P.put("copy", "M8 8h12v12H8z M16 8V4H4v12h4");
        P.put("exec", "M4 17l6-5-6-5 M12 19h8");
        P.put("chart", "M4 20V4 M4 20h16 M8 16v-5 M12 16V8 M16 16v-3 M20 16V6");
        P.put("bell", "M6 9a6 6 0 0 1 12 0c0 6 2.5 8 2.5 8h-17S6 15 6 9z M10 20.5a2.2 2.2 0 0 0 4 0");
        P.put("wrench", "M14.5 6.5a4 4 0 0 0 5 5L21 13l-8 8-4-4 8-8-1.5-1.5z M3 21l6-6");
        P.put("cert", "M5 3h14v12H5z M8 7h8 M8 10.5h5 M10 15l-1.5 6 3.5-2 3.5 2-1.5-6");
    }

    private Icons() {}

    public static Node of(String name, double size, String... extraClasses) {
        SVGPath p = new SVGPath();
        p.setContent(P.getOrDefault(name, P.get("info")));
        p.getStyleClass().add("icon");
        p.getStyleClass().addAll(extraClasses);
        double s = size / 24.0;
        p.setScaleX(s);
        p.setScaleY(s);
        Group g = new Group(p);
        StackPane box = new StackPane(g);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }

    public static Node of(String name) {
        return of(name, 16);
    }
}
