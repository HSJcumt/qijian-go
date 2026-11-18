package go.client;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;

/** The five supplied portraits, bundled into the client for all launch locations. */
public final class Avatars {
    private static final BufferedImage[] IMAGES = new BufferedImage[5];
    private static final Map<String, Icon> ICONS = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < IMAGES.length; i++) {
            var url = Avatars.class.getResource("assets/avatars/avatar-" + (i + 1) + ".png");
            if (url == null) throw new IllegalStateException("缺少头像资源：" + (i + 1));
            try {
                IMAGES[i] = ImageIO.read(url);
            } catch (IOException e) {
                throw new IllegalStateException("无法读取头像资源。", e);
            }
        }
    }

    private Avatars() {}

    public static Icon icon(int index, int size) {
        if (index < 0 || index >= 5 || size < 1) throw new IllegalArgumentException("头像选项无效。");
        return ICONS.computeIfAbsent(
                index + ":" + size,
                key -> {
                    BufferedImage result =
                            new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = result.createGraphics();
                    g.setRenderingHint(
                            RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(Color.WHITE);
                    g.fill(new Ellipse2D.Double(0, 0, size, size));
                    g.setComposite(AlphaComposite.SrcIn);
                    g.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    BufferedImage source = IMAGES[index];
                    double scale =
                            Math.max(
                                    (double) size / source.getWidth(),
                                    (double) size / source.getHeight());
                    int w = (int) Math.ceil(source.getWidth() * scale),
                            h = (int) Math.ceil(source.getHeight() * scale);
                    g.drawImage(source, (size - w) / 2, (size - h) / 2, w, h, null);
                    g.dispose();
                    return new ImageIcon(result);
                });
    }

    public static JButton button(String name, Runnable open) {
        JButton button = new JButton(icon(0, 40));
        button.setName(name);
        button.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        button.setContentAreaFilled(false);
        button.setFocusPainted(true);
        button.setToolTipText("玩家资料 · 修改头像、昵称及查看战绩");
        button.getAccessibleContext().setAccessibleName("玩家资料");
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> open.run());
        return button;
    }
}
