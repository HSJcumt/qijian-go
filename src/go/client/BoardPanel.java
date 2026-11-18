package go.client;

import go.core.Game;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.BitSet;
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Scalable Java2D board: grid intersections, star points, hover, last move and dead groups. */
@SuppressWarnings("serial")
public final class BoardPanel extends JPanel {
    private int size = 19, ghostColor = 1;
    private int[] cells = new int[361];
    private BitSet dead = new BitSet();
    private Game.Move last;
    private Point hover;
    private boolean interactive;
    private Consumer<Point> click = p -> {};
    private double left, top, step;
    private static final String COLUMNS = "ABCDEFGHJKLMNOPQRST";

    public BoardPanel() {
        setOpaque(false);
        setPreferredSize(new Dimension(690, 690));
        setMinimumSize(new Dimension(300, 300));
        getAccessibleContext().setAccessibleName("围棋棋盘");
        MouseAdapter mouse =
                new MouseAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent e) {
                        hover = locate(e.getPoint());
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = null;
                        repaint();
                    }

                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (!interactive || !SwingUtilities.isLeftMouseButton(e)) return;
                        Point point = locate(e.getPoint());
                        if (point != null) click.accept(point);
                    }
                };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void onClick(Consumer<Point> listener) {
        click = listener;
    }

    public void position(
            int size,
            int[] cells,
            BitSet dead,
            Game.Move last,
            int ghostColor,
            boolean interactive) {
        this.size = size;
        this.cells = cells.clone();
        this.dead = (BitSet) dead.clone();
        this.last = last;
        this.ghostColor = ghostColor;
        this.interactive = interactive;
        setCursor(
                Cursor.getPredefinedCursor(
                        interactive ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    private Point locate(Point mouse) {
        if (step <= 0) return null;
        int x = (int) Math.round((mouse.x - left) / step),
                y = (int) Math.round((mouse.y - top) / step);
        if (x < 0
                || y < 0
                || x >= size
                || y >= size
                || Math.abs(mouse.x - left - x * step) > step * .46
                || Math.abs(mouse.y - top - y * step) > step * .46) return null;
        return new Point(x, y);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double side = Math.max(100, Math.min(getWidth(), getHeight()) - 24);
        double bx = (getWidth() - side) / 2, by = (getHeight() - side) / 2;
        g.setColor(new Color(42, 34, 17, 25));
        g.fill(new RoundRectangle2D.Double(bx + 2, by + 6, side, side, 20, 20));
        g.setPaint(
                new GradientPaint(
                        (float) bx,
                        (float) by,
                        new Color(226, 192, 136),
                        (float) (bx + side),
                        (float) (by + side),
                        new Color(201, 158, 99)));
        g.fill(new RoundRectangle2D.Double(bx, by, side, side, 18, 18));
        Shape oldClip = g.getClip();
        g.clip(new RoundRectangle2D.Double(bx, by, side, side, 18, 18));
        for (int i = 0; i < side; i += 5) {
            g.setColor(new Color(105, 72, 26, i % 3 == 0 ? 12 : 6));
            g.draw(new Line2D.Double(bx, by + i, bx + side, by + i + Math.sin(i * .27) * 3));
        }
        g.setClip(oldClip);
        double margin = Math.max(30, side * .062), grid = side - 2 * margin;
        step = grid / (size - 1);
        left = bx + margin;
        top = by + margin;
        g.setStroke(new BasicStroke(1));
        g.setColor(new Color(83, 62, 36, 185));
        for (int i = 0; i < size; i++) {
            double p = i * step;
            g.draw(new Line2D.Double(left + p, top, left + p, top + grid));
            g.draw(new Line2D.Double(left, top + p, left + grid, top + p));
        }
        g.setFont(
                new Font("SansSerif", Font.PLAIN, Math.max(10, Math.min(13, (int) (step * .44)))));
        FontMetrics metrics = g.getFontMetrics();
        for (int i = 0; i < size; i++) {
            String col = "" + COLUMNS.charAt(i), row = "" + (size - i);
            float cx = (float) (left + step * i), cy = (float) (top + step * i);
            g.drawString(col, cx - metrics.stringWidth(col) / 2f, (float) (top - margin * .40));
            g.drawString(
                    col, cx - metrics.stringWidth(col) / 2f, (float) (top + grid + margin * .65));
            g.drawString(
                    row,
                    (float) (left - margin * .60 - metrics.stringWidth(row) / 2f),
                    cy + metrics.getAscent() / 2f - 2);
            g.drawString(
                    row,
                    (float) (left + grid + margin * .58 - metrics.stringWidth(row) / 2f),
                    cy + metrics.getAscent() / 2f - 2);
        }
        int[] stars =
                size == 19
                        ? new int[] {3, 9, 15}
                        : size == 13 ? new int[] {3, 6, 9} : new int[] {2, 4, 6};
        if (size == 9 || size == 13 || size == 19)
            for (int y : stars)
                for (int x : stars) {
                    if (size == 9 && ((x == 4) != (y == 4))) continue;
                    double r = Math.max(2.1, step * .09);
                    g.fill(
                            new Ellipse2D.Double(
                                    left + x * step - r, top + y * step - r, r * 2, r * 2));
                }
        double diameter = step * .91;
        for (int i = 0; i < cells.length; i++)
            if (cells[i] != 0) {
                double cx = left + (i % size) * step, cy = top + (i / size) * step;
                stone(g, cx, cy, diameter, cells[i]);
                if (dead.get(i)) {
                    double r = diameter * .22;
                    g.setColor(new Color(202, 73, 56));
                    g.setStroke(new BasicStroke(Math.max(2, (float) (diameter * .08))));
                    g.draw(new Line2D.Double(cx - r, cy - r, cx + r, cy + r));
                    g.draw(new Line2D.Double(cx - r, cy + r, cx + r, cy - r));
                }
            }
        if (last != null && !last.pass()) {
            double r = diameter * .15, cx = left + last.x() * step, cy = top + last.y() * step;
            g.setColor(new Color(210, 146, 60));
            g.setStroke(new BasicStroke(2));
            g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        }
        if (interactive && hover != null && cells[hover.y * size + hover.x] == 0) {
            g.setComposite(AlphaComposite.SrcOver.derive(.38f));
            stone(g, left + hover.x * step, top + hover.y * step, diameter, ghostColor);
        }
        g.dispose();
    }

    private static void stone(Graphics2D g, double x, double y, double diameter, int color) {
        float radius = (float) (diameter / 2);
        g.setColor(new Color(45, 31, 16, 55));
        g.fill(new Ellipse2D.Double(x - radius + 1, y - radius + 3, diameter, diameter));
        Point2D center = new Point2D.Double(x - radius * .25, y - radius * .35);
        Color[] colors =
                color == 1
                        ? new Color[] {
                            new Color(83, 85, 85), new Color(28, 31, 30), new Color(13, 16, 15)
                        }
                        : new Color[] {
                            Color.WHITE, new Color(246, 245, 236), new Color(206, 205, 195)
                        };
        g.setPaint(
                new RadialGradientPaint(
                        center, Math.max(1, radius * 1.5f), new float[] {0f, .65f, 1f}, colors));
        g.fill(new Ellipse2D.Double(x - radius, y - radius, diameter, diameter));
        g.setColor(color == 1 ? new Color(8, 20, 15, 125) : new Color(133, 119, 96, 95));
        g.setStroke(new BasicStroke(.7f));
        g.draw(new Ellipse2D.Double(x - radius, y - radius, diameter, diameter));
    }
}
