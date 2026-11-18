package go.client;

import static go.client.AppTheme.FONT;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** A local record library; paths and folders are never exposed as navigation. */
@SuppressWarnings("serial")
final class RecordFilePanel extends JPanel {
    private final DefaultListModel<Path> entries = new DefaultListModel<>();
    private final JList<Path> files = new JList<>(entries);
    private final JLabel feedback = new JLabel(" ");
    private final JButton submit = new AppButton("打开棋谱", true);
    private final Path directory;
    private final Consumer<Path> chosen;
    private boolean busy = true;

    RecordFilePanel(Path directory, Consumer<Path> chosen) {
        super(new BorderLayout(0, 16));
        this.directory = directory.toAbsolutePath().normalize();
        this.chosen = chosen;
        setOpaque(false);
        setName("recordFilePanel");
        files.setName("recordFiles");
        files.setFont(FONT);
        files.setFixedCellHeight(38);
        files.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        files.setCellRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean selected,
                            boolean focus) {
                        return super.getListCellRendererComponent(
                                list,
                                ((Path) value).getFileName().toString(),
                                index,
                                selected,
                                focus);
                    }
                });
        files.addListSelectionListener(
                e -> {
                    if (!e.getValueIsAdjusting())
                        submit.setEnabled(!busy && files.getSelectedValue() != null);
                });
        files.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                        int index = files.locationToIndex(e.getPoint());
                        if (index >= 0 && files.getCellBounds(index, index).contains(e.getPoint()))
                            accept();
                    }
                });
        files.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "openEntry");
        files.getActionMap()
                .put(
                        "openEntry",
                        new AbstractAction() {
                            @Override
                            public void actionPerformed(ActionEvent e) {
                                RecordFilePanel.this.accept();
                            }
                        });
        add(new JScrollPane(files), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.setOpaque(false);
        feedback.setName("recordFileFeedback");
        feedback.setFont(FONT);
        feedback.setVisible(false);
        bottom.add(feedback, BorderLayout.CENTER);
        submit.setName("recordFileSubmit");
        submit.setFont(FONT);
        submit.addActionListener(e -> accept());
        bottom.add(submit, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
        loadRecords();
    }

    void busy(boolean busy) {
        this.busy = busy;
        files.setEnabled(!busy);
        submit.setEnabled(!busy && files.getSelectedValue() != null);
        message(busy ? "正在读取棋谱…" : "");
    }

    void message(String message) {
        feedback.setText(message);
        feedback.setVisible(!message.isBlank());
        revalidate();
    }

    private void loadRecords() {
        busy(true);
        new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                if (!Files.exists(directory)) return List.of();
                try (var paths = Files.list(directory)) {
                    return paths.filter(
                                    p ->
                                            Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                                                    && p.getFileName()
                                                            .toString()
                                                            .toLowerCase(Locale.ROOT)
                                                            .endsWith(".gogame"))
                            .sorted(
                                    Comparator.comparing(
                                            p -> p.getFileName().toString(),
                                            String.CASE_INSENSITIVE_ORDER))
                            .toList();
                }
            }

            @Override
            protected void done() {
                try {
                    List<Path> found = get();
                    entries.clear();
                    found.forEach(entries::addElement);
                    busy(false);
                    if (found.isEmpty()) message("暂无本地棋谱。");
                } catch (Exception e) {
                    busy(false);
                    message("无法读取本地棋谱，请检查访问权限。");
                }
            }
        }.execute();
    }

    private void accept() {
        if (busy) return;
        Path selected = files.getSelectedValue();
        if (selected == null) return;
        try {
            if (!entries.contains(selected)
                    || !Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)
                    || !selected.toRealPath().getParent().equals(directory.toRealPath())) {
                message("该棋谱已不可用。");
                return;
            }
            chosen.accept(selected);
        } catch (java.io.IOException e) {
            message("该棋谱已不可用。");
        }
    }
}
