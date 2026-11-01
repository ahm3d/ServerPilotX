package io.serverpilotx.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

/** Animated ring gauge (0–100 %) whose color shifts green → amber → red. */
public final class Gauge extends StackPane {
    private final Arc value = new Arc();
    private final Label text = new Label("–");
    private final DoubleProperty shown = new SimpleDoubleProperty(0);
    private final Color base;
    private Timeline anim;

    public Gauge(double size, Color base) {
        this.base = base;
        double r = size / 2 - 7;
        Arc track = new Arc(0, 0, r, r, 90, -360);
        track.setType(ArcType.OPEN);
        track.setStrokeWidth(8);
        track.getStyleClass().add("gauge-track");

        value.setRadiusX(r);
        value.setRadiusY(r);
        value.setStartAngle(90);
        value.setLength(0);
        value.setType(ArcType.OPEN);
        value.setFill(null);
        value.setStrokeWidth(8);
        value.setStrokeLineCap(StrokeLineCap.ROUND);
        value.setStroke(base);

        text.getStyleClass().add("gauge-value");
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        getChildren().addAll(track, value, text);

        shown.addListener((o, a, b) -> {
            double v = b.doubleValue();
            value.setLength(-3.6 * Math.max(0.5, v));
            text.setText(Math.round(v) + "%");
            value.setStroke(colorFor(v));
        });
    }

    private Color colorFor(double v) {
        if (v >= 90) return Color.web("#f05252");
        if (v >= 75) return Color.web("#f5a524");
        return base;
    }

    public void set(double pct) {
        double v = Math.max(0, Math.min(100, pct));
        if (anim != null) anim.stop();
        anim = new Timeline(new KeyFrame(Duration.millis(450), new KeyValue(shown, v)));
        anim.play();
    }
}
