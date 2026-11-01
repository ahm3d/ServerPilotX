package io.serverpilotx.ui;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.serverpilotx.core.Settings;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;

/** Applies the dark or light stylesheet to every open scene and follows the system preference. */
public final class Theme {
    private static final String APP = Theme.class.getResource("/io/serverpilotx/css/app.css").toExternalForm();
    private static final String DARK = Theme.class.getResource("/io/serverpilotx/css/dark.css").toExternalForm();
    private static final String LIGHT = Theme.class.getResource("/io/serverpilotx/css/light.css").toExternalForm();

    private static final Set<Scene> SCENES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final List<Consumer<Boolean>> LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean dark = true;
    private static boolean listening;

    private Theme() {}

    public static void init() {
        dark = resolve(Settings.theme());
        if (!listening) {
            listening = true;
            try {
                Platform.getPreferences().colorSchemeProperty().addListener((o, a, b) -> {
                    if (Settings.theme() == Settings.Theme.SYSTEM) set(Settings.Theme.SYSTEM);
                });
            } catch (Throwable ignored) { }
        }
    }

    private static boolean resolve(Settings.Theme t) {
        return switch (t) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> {
                try { yield Platform.getPreferences().getColorScheme() == ColorScheme.DARK; }
                catch (Throwable e) { yield true; }
            }
        };
    }

    public static boolean isDark() { return dark; }

    public static void onChange(Consumer<Boolean> l) { LISTENERS.add(l); }

    public static void set(Settings.Theme t) {
        Settings.theme(t);
        dark = resolve(t);
        for (Scene s : SCENES.toArray(new Scene[0])) if (s != null) style(s);
        for (Consumer<Boolean> l : LISTENERS) l.accept(dark);
    }

    public static void toggle() {
        set(dark ? Settings.Theme.LIGHT : Settings.Theme.DARK);
    }

    public static void apply(Scene s) {
        SCENES.add(s);
        style(s);
    }

    private static void style(Scene s) {
        s.getStylesheets().setAll(APP, dark ? DARK : LIGHT);
    }

    public static void apply(Dialog<?> d) {
        d.getDialogPane().getStylesheets().setAll(APP, dark ? DARK : LIGHT);
        d.getDialogPane().sceneProperty().addListener((o, a, s) -> { if (s != null) SCENES.add(s); });
    }
}
