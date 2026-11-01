package io.serverpilotx.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;

/**
 * A toolbar row: nodes before the spacer are left-aligned, nodes after it right-aligned.
 * When the row does not fit, it wraps like a flow layout instead of squeezing buttons into "…".
 */
public final class ToolBarPane extends Pane {
    public static final String SPACER = "spx-spacer";
    private final double gap = 8;
    private final double vgap = 8;

    public ToolBarPane(Node... nodes) {
        getChildren().addAll(nodes);
    }

    private List<Node> items() {
        List<Node> l = new ArrayList<>();
        for (Node n : getManagedChildren()) if (!n.getStyleClass().contains(SPACER)) l.add(n);
        return l;
    }

    private int splitIndex() {
        int i = 0;
        for (Node n : getManagedChildren()) {
            if (n.getStyleClass().contains(SPACER)) return i;
            i++;
        }
        return -1;
    }

    private static double pw(Node n) {
        double p = n.prefWidth(-1);
        double m = n.minWidth(-1);
        return Math.max(p, m);
    }

    private double singleRowWidth() {
        List<Node> it = items();
        double w = 0;
        for (Node n : it) w += pw(n);
        return w + gap * Math.max(0, it.size() - 1) + (splitIndex() >= 0 ? gap * 2 : 0);
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computePrefWidth(double height) {
        return singleRowWidth() + snappedLeftInset() + snappedRightInset();
    }

    @Override
    protected double computeMinWidth(double height) {
        double m = 0;
        for (Node n : items()) m = Math.max(m, pw(n));
        return m + snappedLeftInset() + snappedRightInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        return layoutAll(width < 0 ? singleRowWidth() : width - snappedLeftInset() - snappedRightInset(), false) + snappedTopInset() + snappedBottomInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        layoutAll(getWidth() - snappedLeftInset() - snappedRightInset(), true);
    }

    private double layoutAll(double width, boolean apply) {
        List<Node> it = items();
        double x0 = snappedLeftInset(), y0 = snappedTopInset();
        if (it.isEmpty()) return 0;
        double rowH = 0;
        for (Node n : it) rowH = Math.max(rowH, n.prefHeight(-1));

        if (singleRowWidth() <= width + 0.5) {
            if (!apply) return rowH;
            int split = splitIndex();
            double fixed = singleRowWidth();
            double extra = Math.max(0, width - fixed);
            // grow nodes marked with HBox.hgrow when there is room
            List<Node> growers = new ArrayList<>();
            for (Node n : it) if (HBox.getHgrow(n) == Priority.ALWAYS || HBox.getHgrow(n) == Priority.SOMETIMES) growers.add(n);
            double add = growers.isEmpty() ? 0 : extra / growers.size();
            double usedExtra = add * growers.size();
            double x = x0;
            for (int i = 0; i < it.size(); i++) {
                Node n = it.get(i);
                if (split >= 0 && i == split) x += Math.max(gap * 2, extra - usedExtra + gap * 2);
                double w = pw(n) + (growers.contains(n) ? add : 0);
                double h = n.prefHeight(w);
                layoutInArea(n, x, y0, w, rowH, 0, javafx.geometry.HPos.LEFT, VPos.CENTER);
                n.resize(w, Math.min(h, rowH));
                x += w + gap;
            }
            return rowH;
        }

        double x = 0, y = 0, lineH = 0;
        for (Node n : it) {
            double w = Math.min(pw(n), width);
            double h = n.prefHeight(w);
            if (x > 0 && x + w > width) {
                x = 0;
                y += lineH + vgap;
                lineH = 0;
            }
            if (apply) {
                n.resizeRelocate(x0 + x, y0 + y, w, h);
            }
            x += w + gap;
            lineH = Math.max(lineH, h);
        }
        return y + lineH;
    }
}
