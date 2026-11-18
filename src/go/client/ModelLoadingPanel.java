package go.client;

import static go.client.AppTheme.BG;
import static go.client.AppTheme.INK;
import static go.client.AppTheme.label;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.event.HierarchyEvent;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/** Visible only while the server loads and warms the selected model. */
@SuppressWarnings("serial")
final class ModelLoadingPanel extends JPanel {
    private final JLabel title = text("正在准备围棋对手", 26),
            detail = text("正在加载模型并准备首次计算…", 15),
            elapsed = text("已等待 0 秒", 13);
    private final JProgressBar progress = new JProgressBar();
    private final JButton retry = new AppButton("重新加载", true);
    private final Timer timer;
    private String gameId = "";
    private long started;

    ModelLoadingPanel(Runnable retryAction, Runnable cancelAction) {
        super(new GridBagLayout());
        setName("modelLoadingScreen");
        setBackground(BG);
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(20, 20, 20, 20));
        var url = ModelLoadingPanel.class.getResource("assets/go-stones-around-board.gif");
        if (url == null) throw new IllegalStateException("缺少围棋加载动画");
        ImageIcon icon = new ImageIcon(url);
        JLabel animation =
                new JLabel(
                        new ImageIcon(
                                icon.getImage().getScaledInstance(240, 240, Image.SCALE_DEFAULT)));
        animation.setName("modelLoadingAnimation");
        animation.setAlignmentX(.5f);
        content.add(animation);
        content.add(Box.createVerticalStrut(18));
        content.add(title);
        content.add(Box.createVerticalStrut(14));
        content.add(detail);
        content.add(Box.createVerticalStrut(10));
        JLabel firstRun = text("首次运行需显卡调优，可能等待数分钟，请勿反复重试。", 13);
        firstRun.setName("modelFirstRunHint");
        content.add(firstRun);
        content.add(Box.createVerticalStrut(16));
        progress.setName("modelLoadingProgress");
        progress.setIndeterminate(true);
        progress.setForeground(new Color(32, 91, 69));
        progress.setMaximumSize(new Dimension(350, 6));
        progress.setAlignmentX(.5f);
        content.add(progress);
        content.add(Box.createVerticalStrut(12));
        content.add(elapsed);
        content.add(Box.createVerticalStrut(20));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        actions.setOpaque(false);
        retry.setName("retryModelLoading");
        retry.addActionListener(e -> retryAction.run());
        JButton cancel = new AppButton("取消，返回大厅", false);
        cancel.setName("cancelModelLoading");
        cancel.addActionListener(e -> cancelAction.run());
        actions.add(retry);
        actions.add(cancel);
        content.add(actions);
        add(content);
        timer =
                new Timer(
                        200,
                        e ->
                                elapsed.setText(
                                        "已等待 "
                                                + Math.max(
                                                        0,
                                                        (System.nanoTime() - started)
                                                                / 1_000_000_000L)
                                                + " 秒"));
        addHierarchyListener(
                e -> {
                    if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                        if (isShowing()) timer.start();
                        else timer.stop();
                    }
                });
    }

    void state(String id, String error, boolean paused, boolean player) {
        if (!gameId.equals(id)) {
            gameId = id;
            started = System.nanoTime();
            elapsed.setText("已等待 0 秒");
        }
        boolean failed = !error.isEmpty();
        title.setText(failed ? "模型准备未完成" : paused ? "连接恢复后继续准备" : "正在准备围棋对手");
        detail.setText(failed ? error : paused ? "对局尚未开始，请等待棋手重新连接。" : "正在加载模型并准备首次计算，完成后自动进入棋盘。");
        retry.setVisible(failed && player);
        progress.setIndeterminate(!failed && !paused);
        elapsed.setVisible(!failed);
    }

    private static JLabel text(String value, int size) {
        JLabel label = label(value, size, INK);
        label.setAlignmentX(.5f);
        return label;
    }
}
