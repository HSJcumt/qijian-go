package go.client;

import go.test.ClientEntryTests;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

public final class AiTimingTests {
    private static int checks;

    public static void run() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    AtomicLong clock = new AtomicLong();
                    ThinkingCountdown label = new ThinkingCountdown(clock::get);
                    label.state(true, "queued", "a", -1);
                    check(
                            label.getText().contains("等待"),
                            "queueing does not consume search countdown");
                    clock.set(50_000_000_000L);
                    label.state(true, "thinking", "a", 3000);
                    check(
                            label.getText().contains("3.0"),
                            "countdown starts on actual search event");
                    clock.addAndGet(1_200_000_000L);
                    label.tick();
                    check(
                            label.getText().contains("1.8"),
                            "countdown decreases with monotonic elapsed time");
                    label.state(true, "thinking", "a", 3000);
                    check(
                            label.getText().contains("1.8"),
                            "repainting same snapshot cannot reset countdown");
                    clock.addAndGet(3_000_000_000L);
                    label.tick();
                    check(
                            label.getText().contains("完成落子") && !label.getText().contains("-"),
                            "budget exhaustion never displays negative time");
                    label.state(true, "thinking", "b", 1000);
                    check(label.getText().contains("1.0"), "next search gets its own countdown");
                    label.state(false, "", "", -1);
                    check(
                            label.getText().isBlank(),
                            "human turns and finished games clear the countdown");
                    ModelLoadingPanel loading = new ModelLoadingPanel(() -> {}, () -> {});
                    loading.state("test", "", false, true);
                    check(
                            ((JProgressBar) ClientEntryTests.find(loading, "modelLoadingProgress"))
                                    .isIndeterminate(),
                            "loading animation is indeterminate rather than invented percentage");
                    loading.state("test", "加载失败，请重试。", false, true);
                    check(
                            ClientEntryTests.find(loading, "retryModelLoading").isVisible(),
                            "load failures expose a retry without opening the board");
                    loading.setSize(1000, 650);
                    layout(loading);
                    BufferedImage image = new BufferedImage(1000, 650, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    loading.printAll(g);
                    g.dispose();
                    try {
                        ImageIO.write(
                                image,
                                "png",
                                Path.of("build/test-output/model-loading-error.png").toFile());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
        System.out.println("  AI loading and countdown UI: " + checks + " checks");
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container p) layout(p);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
