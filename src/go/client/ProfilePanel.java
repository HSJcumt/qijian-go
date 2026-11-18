package go.client;

import static go.client.AppTheme.FONT;
import static go.client.AppTheme.GREEN;
import static go.client.AppTheme.INK;
import static go.client.AppTheme.MUTED;
import static go.client.AppTheme.label;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/** Editable profile and separately counted human/AI results. No networking in the view. */
@SuppressWarnings("serial")
public final class ProfilePanel extends JPanel {
    public record Edit(String nickname, int avatar) {}

    private final JTextField nickname;
    private final JToggleButton[] choices = new JToggleButton[5];
    private final JLabel human = new JLabel(), machine = new JLabel(), feedback = new JLabel(" ");
    private final JButton save = new AppButton("保存修改", true), cancel = new AppButton("取消", false);
    private int avatar;

    public ProfilePanel(
            String name,
            int selected,
            ClientProfiles.Stats humanStats,
            ClientProfiles.Stats machineStats,
            Consumer<Edit> submit,
            Runnable close) {
        setName("profilePanel");
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(Color.WHITE);
        setBorder(new EmptyBorder(26, 28, 24, 28));
        setPreferredSize(new Dimension(590, 520));
        avatar = selected;
        nickname = new JTextField(name);
        nickname.setName("profileNickname");
        nickname.setFont(FONT);
        nickname.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        nickname.setPreferredSize(new Dimension(500, 42));
        nickname.setBorder(
                new CompoundBorder(
                        new LineBorder(new Color(214, 222, 211)), new EmptyBorder(8, 10, 8, 10)));
        addLine(label("玩家资料", 25, INK), 16);
        addLine(label("选择头像", 13, MUTED), 10);
        JPanel portraits = new JPanel(new GridLayout(1, 5, 12, 0));
        portraits.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < choices.length; i++) {
            int index = i;
            JToggleButton b = new JToggleButton(Avatars.icon(i, 72));
            b.setName("avatarChoice" + i);
            b.setToolTipText("头像 " + (i + 1));
            b.getAccessibleContext().setAccessibleName("头像 " + (i + 1));
            b.setContentAreaFilled(false);
            b.setFocusPainted(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(
                    e -> {
                        avatar = index;
                        refreshSelection();
                    });
            group.add(b);
            choices[i] = b;
            portraits.add(b);
        }
        refreshSelection();
        portraits.setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
        addLine(portraits, 18);
        addLine(label("昵称", 13, MUTED), 8);
        addLine(nickname, 22);
        JPanel statistics = new JPanel(new GridLayout(1, 2, 14, 0));
        statistics.setOpaque(false);
        human.setName("humanStats");
        machine.setName("machineStats");
        human.setFont(FONT.deriveFont(13f));
        machine.setFont(FONT.deriveFont(13f));
        human.setBorder(new LineBorder(new Color(231, 237, 225)));
        machine.setBorder(new LineBorder(new Color(231, 237, 225)));
        statistics.add(human);
        statistics.add(machine);
        statistics.setMaximumSize(new Dimension(Integer.MAX_VALUE, 85));
        addLine(statistics, 10);
        stats(humanStats, machineStats);
        addLine(label("仅统计已结束的对局；和棋计入总局数，观战与回放不计入。", 11, MUTED), 14);
        feedback.setName("profileFeedback");
        feedback.setFont(FONT.deriveFont(12f));
        feedback.setForeground(new Color(161, 77, 54));
        addLine(feedback, 8);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        save.setName("saveProfileButton");
        cancel.setName("cancelProfileButton");
        save.setFont(FONT);
        cancel.setFont(FONT);
        cancel.addActionListener(e -> close.run());
        Runnable commit =
                () -> {
                    try {
                        submit.accept(
                                new Edit(ClientProfiles.validPlayer(nickname.getText()), avatar));
                    } catch (IllegalArgumentException e) {
                        message(e.getMessage());
                    }
                };
        save.addActionListener(e -> commit.run());
        nickname.addActionListener(
                e -> {
                    if (save.isEnabled()) commit.run();
                });
        actions.add(cancel);
        actions.add(save);
        addLine(actions, 0);
    }

    private void refreshSelection() {
        for (int i = 0; i < choices.length; i++)
            if (choices[i] != null) {
                choices[i].setSelected(i == avatar);
                choices[i].setBorder(
                        new CompoundBorder(
                                new LineBorder(
                                        i == avatar ? GREEN : new Color(231, 237, 225),
                                        i == avatar ? 3 : 1,
                                        true),
                                new EmptyBorder(
                                        i == avatar ? 5 : 7,
                                        i == avatar ? 5 : 7,
                                        i == avatar ? 5 : 7,
                                        i == avatar ? 5 : 7)));
            }
    }

    public void stats(ClientProfiles.Stats humanStats, ClientProfiles.Stats machineStats) {
        human.setText(statText("真人对战", humanStats));
        machine.setText(statText("人机对战", machineStats));
    }

    private static String statText(String title, ClientProfiles.Stats stats) {
        return "<html><div style='padding:8px 10px'><font color='#6d786e'>"
                + title
                + "</font><br><font size='5' color='#235335'>"
                + stats.games()
                + " 局</font>　胜率 "
                + stats.winRate()
                + "<br>"
                + stats.wins()
                + " 胜 / "
                + (stats.games() - stats.wins() - stats.draws())
                + " 负 / "
                + stats.draws()
                + " 和</div></html>";
    }

    public void saving(boolean busy) {
        nickname.setEnabled(!busy);
        for (JToggleButton b : choices) b.setEnabled(!busy);
        save.setEnabled(!busy);
        cancel.setEnabled(!busy);
        save.setText(busy ? "正在保存…" : "保存修改");
    }

    public void message(String text) {
        feedback.setText(text);
    }

    private void addLine(JComponent component, int gap) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(component);
        add(Box.createVerticalStrut(gap));
    }
}
