package go.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

public final class PageNavigationTests {
    private static int checks;

    public static void run() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    AtomicInteger returned = new AtomicInteger();
                    AppPages pages = new AppPages(returned::incrementAndGet);
                    JTextField input = new JTextField("未保存的内容");
                    AppPages.Page[] editor = new AppPages.Page[1], prompt = new AppPages.Page[1];
                    editor[0] =
                            new AppPages.Page("editor", "编辑", input, () -> pages.remove(editor[0]));
                    pages.push(editor[0]);
                    prompt[0] =
                            new AppPages.Page(
                                    "prompt",
                                    "确认",
                                    new JLabel("确认？"),
                                    () -> pages.remove(prompt[0]));
                    pages.push(prompt[0]);
                    pages.back();
                    check(
                            pages.contains(editor[0])
                                    && !pages.contains(prompt[0])
                                    && input.getText().equals("未保存的内容"),
                            "back from nested prompt retains original form and text");
                    editor[0].busy(true);
                    pages.back();
                    check(
                            pages.active() && returned.get() == 0,
                            "busy page cannot be left by Escape or header back");
                    editor[0].busy(false);
                    pages.back();
                    check(
                            !pages.active() && returned.get() == 1,
                            "leaving last operation returns to the main page once");
                    JLabel feedback = new JLabel();
                    JPanel login =
                            ClientEntry.login(
                                    java.util.List.of("已有棋友"), "已有棋友", name -> {}, feedback);
                    check(
                            go.test.ClientEntryTests.find(login, "playerNames") instanceof JList<?>,
                            "existing players are an inline list rather than a floating menu");
                });
        // The runtime should have one main frame and no APIs capable of opening operation dialogs.
        try (var sources = Files.list(Path.of("src/go/client"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                check(
                        !text.contains("JDialog")
                                && !text.contains("JOptionPane")
                                && !text.contains("JFileChooser"),
                        "no operation window API in " + source.getFileName());
            }
        }
        System.out.println("  In-window navigation: " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
