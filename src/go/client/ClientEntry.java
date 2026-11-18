package go.client;

import static go.client.AppTheme.ENTRY_MUTED;
import static go.client.AppTheme.FONT;
import static go.client.AppTheme.GREEN;
import static go.client.AppTheme.INK;
import static go.client.AppTheme.label;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Entry screens contain no socket operations. */
@SuppressWarnings("serial")
public final class ClientEntry {

    private ClientEntry() {}

    /** Both entry pages share the original artwork, scaled without distortion. */
    private static class VisualPanel extends JPanel {
        private static final BufferedImage VISUAL = loadVisual();

        VisualPanel(LayoutManager layout) {
            super(layout);
            setBackground(Color.WHITE);
        }

        private static BufferedImage loadVisual() {
            try {
                Path custom = Path.of("assets", "home-visual.png");
                BufferedImage image;
                if (Files.isRegularFile(custom)) image = ImageIO.read(custom.toFile());
                else {
                    var resource = ClientEntry.class.getResource("assets/home-visual.png");
                    if (resource == null) throw new IOException("缺少主视觉图");
                    image = ImageIO.read(resource);
                }
                if (image == null) throw new IOException("主视觉图格式无效");
                return image;
            } catch (IOException e) {
                throw new IllegalStateException("无法读取主视觉图。", e);
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            double scale =
                    Math.max(
                            (double) getWidth() / VISUAL.getWidth(),
                            (double) getHeight() / VISUAL.getHeight());
            int width = (int) Math.ceil(VISUAL.getWidth() * scale),
                    height = (int) Math.ceil(VISUAL.getHeight() * scale);
            g.drawImage(
                    VISUAL, getWidth() - width, (getHeight() - height) / 2, width, height, null);
            g.dispose();
        }
    }

    public static final class Entrance extends VisualPanel {
        private final JLabel status = label("未连接服务器 · 点击屏幕连接并进入游戏", 16, GREEN);
        private final JLabel endpoint = label("", 13, ENTRY_MUTED);
        private final JButton configure;
        private boolean busy;

        public Entrance(Runnable enter) {
            this(enter, () -> {});
        }

        public Entrance(Runnable enter, Runnable configureServer) {
            super(new BorderLayout());
            setName("entranceScreen");
            status.setName("entranceConnectionStatus");
            endpoint.setName("entranceServerAddress");
            configure = ClientEntry.action("配置服务器地址", false);
            configure.setName("configureServerButton");
            configure.addActionListener(e -> configureServer.run());
            JPanel settings = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
            settings.setOpaque(false);
            settings.setBorder(new EmptyBorder(24, 24, 0, 28));
            settings.add(endpoint);
            settings.add(configure);
            add(settings, BorderLayout.NORTH);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            status.setHorizontalAlignment(SwingConstants.CENTER);
            status.setBorder(new EmptyBorder(20, 20, 38, 20));
            add(status, BorderLayout.SOUTH);
            java.awt.event.MouseAdapter click =
                    new java.awt.event.MouseAdapter() {
                        @Override
                        public void mouseClicked(java.awt.event.MouseEvent e) {
                            if (SwingUtilities.isLeftMouseButton(e) && !busy) enter.run();
                        }
                    };
            addMouseListener(click);
            status.addMouseListener(click);
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ENTER"), "enterGame");
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("SPACE"), "enterGame");
            getActionMap()
                    .put(
                            "enterGame",
                            new AbstractAction() {
                                @Override
                                public void actionPerformed(java.awt.event.ActionEvent e) {
                                    if (!busy) enter.run();
                                }
                            });
        }

        public void status(String message, boolean busy) {
            this.busy = busy;
            configure.setEnabled(!busy);
            status.setText(message.isEmpty() ? "未连接服务器 · 点击屏幕连接并进入游戏" : message);
        }

        public void server(ClientProfiles.Server server) { endpoint.setText(server.endpoint()); }
    }

    public static JPanel login(
            List<String> players, String lastPlayer, Consumer<String> submit, JLabel feedback) {
        JPanel page = new VisualPanel(new GridBagLayout());
        page.setBackground(Color.WHITE);
        page.setName("loginScreen");
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setPreferredSize(new Dimension(390, players.isEmpty() ? 370 : 500));
        JLabel title = label("棋间", 36, INK), subtitle = label("以你的名字，开启一局。", 15, ENTRY_MUTED);
        JTextField editor = new JTextField(lastPlayer);
        editor.setName("playerNameInput");
        editor.setFont(FONT);
        editor.setMaximumSize(new Dimension(390, 46));
        editor.setPreferredSize(new Dimension(390, 46));
        JList<String> names = new JList<>(players.toArray(String[]::new));
        names.setName("playerNames");
        names.setFont(FONT);
        names.setFixedCellHeight(32);
        names.setVisibleRowCount(3);
        names.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        names.setSelectedValue(lastPlayer, true);
        names.addListSelectionListener(
                e -> {
                    if (!e.getValueIsAdjusting() && names.getSelectedValue() != null)
                        editor.setText(names.getSelectedValue());
                });
        JButton enter = action("登录", true);
        enter.setName("loginButton");
        enter.setMaximumSize(new Dimension(390, 46));
        Runnable login = () -> submit.accept(editor.getText());
        enter.addActionListener(e -> login.run());
        editor.addActionListener(e -> login.run());
        editor.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            private void changed() {
                                enter.setText(
                                        players.contains(editor.getText().strip())
                                                ? "登录"
                                                : "新建玩家并登录");
                                feedback.setText(" ");
                            }

                            public void insertUpdate(DocumentEvent e) {
                                changed();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                changed();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                changed();
                            }
                        });
        enter.setText(players.contains(lastPlayer) ? "登录" : "新建玩家并登录");
        feedback.setFont(FONT.deriveFont(12f));
        feedback.setForeground(new Color(161, 77, 54));
        feedback.setName("loginFeedback");
        add(form, title, 15);
        add(form, subtitle, 32);
        add(form, label("玩家名称", 13, INK), 10);
        add(form, editor, 10);
        if (!players.isEmpty()) {
            JScrollPane saved = new JScrollPane(names);
            saved.setMaximumSize(new Dimension(390, 100));
            saved.setPreferredSize(new Dimension(390, 100));
            add(form, saved, 10);
        }
        add(form, label("选择已有名称，或输入新名称创建玩家", 12, ENTRY_MUTED), 20);
        add(form, enter, 12);
        add(form, feedback, 0);
        GridBagConstraints position = new GridBagConstraints();
        position.weightx = 1;
        position.weighty = 1;
        position.anchor = GridBagConstraints.WEST;
        position.insets = new Insets(40, 80, 40, 40);
        page.add(form, position);
        return page;
    }

    public record OnlinePlayer(String name, String roomId, boolean playing) {}

    public static final class Home extends VisualPanel {
        private final JButton avatar;
        private final JPanel players = new JPanel();
        private final JButton empty;
        private final Consumer<OnlinePlayer> invite, watch;
        private String playerName = "";
        private List<OnlinePlayer> online = List.of();

        public Home(
                Runnable retry,
                Runnable editProfile,
                Runnable practice,
                Runnable replay,
                Runnable exit,
                Consumer<OnlinePlayer> invite,
                Consumer<OnlinePlayer> watch) {
            super(new BorderLayout(0, 22));
            setName("homeScreen");
            setBackground(Color.WHITE);
            setBorder(new EmptyBorder(34, 46, 30, 46));
            this.invite = invite;
            this.watch = watch;
            avatar = Avatars.button("homeProfileButton", editProfile);
            avatar.setBorder(
                    new javax.swing.border.AbstractBorder() {
                        @Override
                        public Insets getBorderInsets(Component c) {
                            return new Insets(7, 7, 7, 7);
                        }

                        @Override
                        public void paintBorder(
                                Component c, Graphics graphics, int x, int y, int w, int h) {
                            Graphics2D g = (Graphics2D) graphics.create();
                            g.setRenderingHint(
                                    RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                            g.setColor(new Color(250, 247, 234, 215));
                            g.setStroke(new BasicStroke(3));
                            g.drawOval(x + 3, y + 3, w - 7, h - 7);
                            g.setColor(new Color(137, 151, 122, 160));
                            g.setStroke(new BasicStroke(1));
                            g.drawOval(x + 1, y + 1, w - 3, h - 3);
                            g.dispose();
                        }
                    });
            JPanel top = transparent(new BorderLayout());
            JLabel heading = label("在线玩家列表", 26, INK);
            heading.setFont(new Font("SimSun", Font.BOLD, 26));
            top.add(heading, BorderLayout.WEST);
            top.add(avatar, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            players.setOpaque(false);
            players.setLayout(new BoxLayout(players, BoxLayout.Y_AXIS));
            empty = link("正在连接…", 14, retry);
            empty.setName("homeConnectionStatus");
            JScrollPane scroll =
                    new JScrollPane(players) {
                        @Override
                        protected void paintComponent(Graphics graphics) {
                            Graphics2D g = (Graphics2D) graphics.create();
                            g.setRenderingHint(
                                    RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                            g.setColor(new Color(30, 55, 40, 18));
                            g.fillRoundRect(1, 4, getWidth() - 2, getHeight() - 5, 22, 22);
                            g.setPaint(
                                    new GradientPaint(
                                            0,
                                            0,
                                            new Color(255, 253, 245, 225),
                                            0,
                                            getHeight(),
                                            new Color(250, 249, 240, 190)));
                            g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 6, 22, 22);
                            g.setColor(new Color(151, 165, 138, 130));
                            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 6, 22, 22);
                            g.dispose();
                            super.paintComponent(graphics);
                        }
                    };
            scroll.setName("onlinePlayers");
            scroll.setBorder(new EmptyBorder(12, 12, 16, 12));
            scroll.setPreferredSize(new Dimension(400, 520));
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(7, 0));
            scroll.getVerticalScrollBar().setOpaque(false);
            scroll.getVerticalScrollBar()
                    .setUI(
                            new javax.swing.plaf.basic.BasicScrollBarUI() {
                                @Override
                                protected void paintTrack(Graphics g, JComponent c, Rectangle r) {}

                                @Override
                                protected void paintThumb(
                                        Graphics graphics, JComponent c, Rectangle r) {
                                    Graphics2D g = (Graphics2D) graphics.create();
                                    g.setRenderingHint(
                                            RenderingHints.KEY_ANTIALIASING,
                                            RenderingHints.VALUE_ANTIALIAS_ON);
                                    g.setColor(new Color(72, 108, 83, 100));
                                    g.fillRoundRect(r.x, r.y, r.width, r.height, 7, 7);
                                    g.dispose();
                                }

                                @Override
                                protected JButton createDecreaseButton(int orientation) {
                                    return hiddenArrow();
                                }

                                @Override
                                protected JButton createIncreaseButton(int orientation) {
                                    return hiddenArrow();
                                }

                                private JButton hiddenArrow() {
                                    JButton b = new JButton();
                                    b.setPreferredSize(new Dimension(0, 0));
                                    return b;
                                }
                            });
            JPanel body = transparent(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = .43;
            c.weighty = 1;
            c.fill = GridBagConstraints.BOTH;
            body.add(scroll, c);
            JPanel choices = transparent(new GridBagLayout());
            JPanel menu = transparent(new GridLayout(2, 1, 0, 22));
            JButton ai = homeAction("人机练习", 28, true, practice),
                    records = homeAction("棋谱回放", 28, false, replay);
            ai.setPreferredSize(new Dimension(258, 78));
            records.setPreferredSize(new Dimension(258, 78));
            ai.setName("homePracticeButton");
            records.setName("homeReplayButton");
            menu.add(ai);
            menu.add(records);
            GridBagConstraints m = new GridBagConstraints();
            m.weighty = 1;
            m.anchor = GridBagConstraints.NORTH;
            m.insets = new Insets(26, 0, 0, 0);
            choices.add(menu, m);
            c.gridx = 1;
            c.weightx = .57;
            c.insets = new Insets(0, 42, 0, 0);
            body.add(choices, c);
            add(body, BorderLayout.CENTER);
            JPanel bottom = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            JButton quit = link("退出游戏", 18, exit);
            quit.setName("homeExitButton");
            bottom.add(quit);
            add(bottom, BorderLayout.SOUTH);
            rebuildPlayers();
        }

        public void player(String name) {
            playerName = name;
            rebuildPlayers();
        }

        public void player(String name, int selected) {
            player(name);
            avatar.setIcon(Avatars.icon(selected, 40));
            avatar.setToolTipText(name + " · 玩家资料");
        }

        public void status(String message, boolean busy) {
            empty.setText(message.isEmpty() ? "暂无其他在线玩家" : message);
            empty.setEnabled(!busy);
        }

        public void players(List<OnlinePlayer> value) {
            online = List.copyOf(value);
            rebuildPlayers();
        }

        private void rebuildPlayers() {
            players.removeAll();
            for (OnlinePlayer p : online) {
                if (p.name().equals(playerName)) continue;
                JPanel row = transparent(new BorderLayout(12, 0));
                row.setBorder(
                        new javax.swing.border.CompoundBorder(
                                BorderFactory.createMatteBorder(
                                        0, 0, 1, 0, new Color(135, 156, 127, 35)),
                                new EmptyBorder(15, 10, 15, 8)));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));
                JLabel name = label(p.name(), 16, INK);
                name.setIcon(new PersonIcon());
                name.setIconTextGap(12);
                row.add(name, BorderLayout.CENTER);
                JButton action =
                        link(
                                p.playing() ? "对局中观战" : "邀请",
                                15,
                                () -> {
                                    if (p.playing()) watch.accept(p);
                                    else invite.accept(p);
                                });
                action.setName("playerAction:" + p.name());
                action.setEnabled(p.playing() || p.roomId().isEmpty());
                row.add(action, BorderLayout.EAST);
                players.add(row);
            }
            if (players.getComponentCount() == 0) {
                empty.setAlignmentX(Component.CENTER_ALIGNMENT);
                players.add(Box.createVerticalStrut(24));
                players.add(empty);
            }
            players.revalidate();
            players.repaint();
        }

        private static JPanel transparent(LayoutManager layout) {
            JPanel p = new JPanel(layout);
            p.setOpaque(false);
            return p;
        }

        private static JButton link(String title, int size, Runnable callback) {
            return homeAction(title, size, false, callback);
        }
    }

    private static JButton homeAction(String title, int size, boolean primary, Runnable callback) {
        JButton button = new AppButton(title, primary);
        button.setFont(FONT.deriveFont((float) size));
        if (size >= 24) button.setBorder(new EmptyBorder(18, 32, 18, 32));
        button.addActionListener(e -> callback.run());
        return button;
    }

    private static final class PersonIcon implements Icon {
        public int getIconWidth() {
            return 38;
        }

        public int getIconHeight() {
            return 38;
        }

        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(238, 242, 234));
            g.fillOval(0, 0, 38, 38);
            g.setColor(ENTRY_MUTED);
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(14, 7, 10, 10);
            g.drawArc(8, 20, 22, 22, 0, 180);
            g.dispose();
        }
    }

    private static void add(JPanel parent, JComponent child, int bottom) {
        child.setAlignmentX(Component.LEFT_ALIGNMENT);
        parent.add(child);
        parent.add(Box.createVerticalStrut(bottom));
    }

    private static JButton action(String title, boolean primary) {
        return new AppButton(title, primary);
    }
}
