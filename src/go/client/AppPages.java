package go.client;

import static go.client.AppTheme.BG;
import static go.client.AppTheme.INK;
import static go.client.AppTheme.label;

import java.awt.BorderLayout;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/** In-window navigation. Nested prompts retain the original form and its input. */
@SuppressWarnings("serial")
final class AppPages extends JPanel {
    private final Deque<Page> history = new ArrayDeque<>();
    private final Runnable returned;

    AppPages(Runnable returned) {
        super(new BorderLayout());
        this.returned = returned;
        setName("operationPages");
    }

    boolean active() {
        return !history.isEmpty();
    }

    boolean contains(Page page) {
        return history.contains(page);
    }

    void push(Page page) {
        history.addLast(page);
        render();
    }

    void remove(Page page) {
        if (page != null && history.remove(page)) render();
    }

    void back() {
        if (active()) history.getLast().back.doClick(0);
    }

    private void render() {
        removeAll();
        if (active()) add(history.getLast(), BorderLayout.CENTER);
        else returned.run();
        revalidate();
        repaint();
    }

    static final class Page extends JPanel {
        private final JButton back = new AppButton("返回", false);

        Page(String name, String title, JComponent body, Runnable previous) {
            super(new BorderLayout(0, 16));
            setName(name);
            setBackground(BG);
            setBorder(new EmptyBorder(22, 28, 24, 28));
            JPanel header = new JPanel(new BorderLayout(20, 0));
            header.setOpaque(false);
            back.setName("pageBack");
            back.addActionListener(e -> previous.run());
            header.add(back, BorderLayout.WEST);
            JLabel heading = label("棋间  /  " + title, 23, INK);
            header.add(heading, BorderLayout.CENTER);
            add(header, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
        }

        void busy(boolean busy) {
            back.setEnabled(!busy);
        }
    }
}
