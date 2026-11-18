package go.client;

import static go.client.AppTheme.FONT;
import static go.client.AppTheme.GREEN;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JButton;
import javax.swing.border.EmptyBorder;

/** Shared rendering for every text action in the client. */
@SuppressWarnings("serial")
final class AppButton extends JButton {
    private final boolean primary;

    AppButton(String text, boolean primary) {
        super(text);
        this.primary = primary;
        setFont(FONT);
        setForeground(primary ? new Color(255, 251, 236) : GREEN);
        setBorder(new EmptyBorder(11, 20, 11, 20));
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight(), radius = getFont().getSize() >= 24 ? 22 : 16;
        boolean hover = isEnabled() && getModel().isRollover(),
                pressed = isEnabled() && getModel().isPressed() && getModel().isArmed();
        int y = pressed ? 3 : 1;
        g.setColor(new Color(27, 58, 42, isEnabled() ? (pressed ? 10 : 24) : 8));
        g.fillRoundRect(2, 4, w - 4, h - 5, radius, radius);
        Color upper, lower, edge;
        if (!isEnabled()) {
            upper = new Color(234, 236, 228);
            lower = new Color(228, 232, 221);
            edge = new Color(184, 195, 175, 130);
        } else if (primary) {
            upper =
                    pressed
                            ? new Color(27, 69, 52)
                            : hover ? new Color(51, 111, 82) : new Color(41, 94, 71);
            lower =
                    pressed
                            ? new Color(26, 65, 49)
                            : hover ? new Color(36, 89, 66) : new Color(29, 74, 56);
            edge = new Color(186, 174, 123, 190);
        } else {
            upper = new Color(255, 253, 244, hover ? 255 : 240);
            lower =
                    pressed
                            ? new Color(223, 230, 210)
                            : hover ? new Color(232, 239, 221) : new Color(241, 242, 228, 240);
            edge = new Color(113, 139, 107, hover ? 190 : 125);
        }
        g.setPaint(new GradientPaint(0, y, upper, 0, Math.max(1, h - 4), lower));
        g.fillRoundRect(1, y, w - 3, h - 5, radius, radius);
        g.setColor(edge);
        g.drawRoundRect(1, y, w - 3, h - 5, radius, radius);
        if (isFocusOwner()) {
            g.setColor(new Color(164, 127, 60));
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(4, 4, w - 9, h - 11, radius - 4, radius - 4);
        }
        g.dispose();
        super.paintComponent(graphics);
    }
}
