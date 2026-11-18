package go.client;

import java.awt.Color;
import java.awt.Font;

import javax.swing.JLabel;

/** Shared client palette and typography. Component-specific paint details stay in their views. */
final class AppTheme {
    static final Color BG = new Color(244, 245, 239);
    static final Color INK = new Color(35, 53, 45);
    static final Color MUTED = new Color(109, 120, 110);
    static final Color ENTRY_MUTED = new Color(126, 137, 128);
    static final Color GREEN = new Color(32, 91, 69);
    static final Color LINE = new Color(223, 228, 217);
    static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 14);

    private AppTheme() {}

    static JLabel label(String text, int size, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(FONT.deriveFont((float) size));
        label.setForeground(color);
        return label;
    }
}
