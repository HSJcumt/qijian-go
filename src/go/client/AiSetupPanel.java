package go.client;

import static go.client.AppTheme.BG;
import static go.client.AppTheme.FONT;
import static go.client.AppTheme.GREEN;
import static go.client.AppTheme.INK;
import static go.client.AppTheme.LINE;
import static go.client.AppTheme.MUTED;
import static go.client.AppTheme.label;

import go.ai.AiDifficulty;
import go.ai.AiProfile;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;

/** One-step difficulty selection. Models and search settings belong to the preset. */
@SuppressWarnings("serial")
final class AiSetupPanel extends JPanel {
    record Selection(AiDifficulty difficulty, int size, int color) {
        AiProfile profile() {
            return difficulty.model();
        }

        AiProfile.Budget budget() {
            return difficulty.budget();
        }
    }

    private static final Map<String, BufferedImage> IMAGES = new HashMap<>();
    private final BooleanSupplier connected;
    private Map<String, String> catalog;
    private AiDifficulty difficulty = AiDifficulty.AMATEUR_LOW;
    private final List<ChoiceCard> cards = new ArrayList<>();
    private final JButton primary = action("开始对弈");

    AiSetupPanel(
            Selection previous,
            Map<String, String> catalog,
            BooleanSupplier connected,
            Consumer<Selection> accept) {
        super(new BorderLayout(0, 22));
        this.catalog = Map.copyOf(catalog);
        this.connected = connected;
        if (previous != null) difficulty = previous.difficulty();
        setName("aiSetupPanel");
        setBackground(BG);
        setBorder(new EmptyBorder(24, 24, 22, 24));
        setPreferredSize(new Dimension(1000, 630));
        JPanel heading = plain(new GridLayout(0, 1, 0, 10));
        heading.add(label("选择对手难度", 29, INK));
        heading.add(label("从稳扎稳打到挑战极限，选择适合你的一局。", 15, MUTED));
        add(heading, BorderLayout.NORTH);
        JPanel grid = plain(new GridLayout(1, 5, 12, 0));
        grid.setName("aiChoiceCards");
        String[] images = {
            "model-small", "model-medium", "model-large", "think-standard", "think-deep"
        };
        for (AiDifficulty d : AiDifficulty.values()) {
            ChoiceCard card =
                    new ChoiceCard(images[d.ordinal()], d.toString(), d.caption(), d.modelLabel());
            card.setName("difficulty" + d.name());
            card.addActionListener(
                    e -> {
                        difficulty = d;
                        update();
                    });
            cards.add(card);
            grid.add(card);
        }
        add(grid, BorderLayout.CENTER);
        JPanel actions = plain(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        primary.setName("aiNextStep");
        primary.addActionListener(
                e -> {
                    if (available(difficulty)) accept.accept(new Selection(difficulty, 19, 1));
                });
        actions.add(primary);
        add(actions, BorderLayout.SOUTH);
        update();
    }

    JButton primaryButton() {
        return primary;
    }

    void catalog(Map<String, String> fields) {
        catalog = Map.copyOf(fields);
        update();
    }

    private boolean available(AiDifficulty d) {
        return connected.getAsBoolean()
                && Boolean.parseBoolean(catalog.getOrDefault(d.name(), "false"));
    }

    private void update() {
        for (AiDifficulty d : AiDifficulty.values()) {
            ChoiceCard c = cards.get(d.ordinal());
            c.setEnabled(available(d));
            c.setSelected(d == difficulty);
        }
        primary.setEnabled(available(difficulty));
        repaint();
    }

    private static JPanel plain(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        return p;
    }

    private static JButton action(String text) {
        return new AppButton(text, true);
    }

    private static BufferedImage image(String name) {
        return IMAGES.computeIfAbsent(
                name,
                key -> {
                    var url = AiSetupPanel.class.getResource("assets/ai/" + key + ".png");
                    if (url == null)
                        throw new IllegalStateException("Missing AI illustration: " + key);
                    try {
                        BufferedImage img = ImageIO.read(url);
                        if (img == null) throw new IOException("Unsupported image");
                        return img;
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
    }

    private static final class ChoiceCard extends JToggleButton {
        private final BufferedImage illustration;
        private final String heading, caption, detail;

        ChoiceCard(String name, String heading, String caption, String detail) {
            super(heading);
            this.heading = heading;
            this.caption = caption;
            this.detail = detail;
            illustration = image(name);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(heading + " · " + caption + " · " + detail);
            getAccessibleContext().setAccessibleDescription(caption + "，" + detail);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth(), h = getHeight();
            Shape box = new RoundRectangle2D.Double(2, 2, w - 4, h - 4, 20, 20);
            g.setColor(isSelected() ? new Color(235, 241, 232) : Color.WHITE);
            g.fill(box);
            int imageSide = Math.max(1, Math.min(w - 24, h - 120));
            int x = (w - imageSide) / 2, y = 12;
            g.drawImage(illustration, x, y, imageSide, imageSide, null);
            if (!isEnabled()) {
                g.setColor(new Color(244, 245, 239, 155));
                g.fill(box);
            }
            int textY = y + imageSide + 25;
            g.setColor(isEnabled() ? INK : MUTED);
            g.setFont(FONT.deriveFont(Font.BOLD, 20f));
            centered(g, heading, w, textY);
            g.setFont(FONT);
            centered(g, caption, w, textY + 27);
            g.setFont(FONT.deriveFont(12f));
            g.setColor(MUTED);
            centered(g, isEnabled() ? detail : "暂不可用", w, textY + 51);
            g.setStroke(new BasicStroke(isSelected() || hasFocus() ? 2.4f : 1f));
            g.setColor(
                    isSelected() || hasFocus()
                            ? GREEN
                            : getModel().isRollover() && isEnabled()
                                    ? new Color(137, 167, 140)
                                    : LINE);
            g.draw(box);
            if (isSelected()) {
                g.setColor(GREEN);
                g.fillOval(w - 30, 14, 14, 14);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.7f));
                g.drawLine(w - 27, 21, w - 24, 24);
                g.drawLine(w - 24, 24, w - 19, 18);
            }
            g.dispose();
        }

        private static void centered(Graphics2D g, String text, int width, int y) {
            g.drawString(text, (width - g.getFontMetrics().stringWidth(text)) / 2, y);
        }
    }
}
