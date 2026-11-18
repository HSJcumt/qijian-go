package go.client;

import static go.client.AppTheme.FONT;
import static go.client.AppTheme.GREEN;

import java.awt.Font;
import java.awt.event.HierarchyEvent;
import java.util.Locale;
import java.util.function.LongSupplier;

import javax.swing.JLabel;
import javax.swing.Timer;

/** Countdown starts from server-reported search time, excluding queueing and model load. */
@SuppressWarnings("serial")
final class ThinkingCountdown extends JLabel {
    private final LongSupplier clock;
    private final Timer timer;
    private long deadline;
    private long lastReported = -1;
    private String jobId = "";
    private boolean counting;

    ThinkingCountdown() {
        this(System::nanoTime);
    }

    ThinkingCountdown(LongSupplier clock) {
        this.clock = clock;
        setName("aiThinkingCountdown");
        setFont(FONT.deriveFont(Font.BOLD, 15f));
        setForeground(GREEN);
        timer = new Timer(100, e -> tick());
        addHierarchyListener(
                e -> {
                    if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) syncTimer();
                });
    }

    void state(boolean thinking, String stage, String job, long remainingMillis) {
        counting = thinking && stage.equals("thinking") && remainingMillis >= 0;
        if (counting) {
            if (!jobId.equals(job) || lastReported != remainingMillis)
                deadline = clock.getAsLong() + remainingMillis * 1_000_000L;
            jobId = job;
            lastReported = remainingMillis;
            tick();
        } else {
            jobId = "";
            lastReported = -1;
            setText(!thinking ? " " : stage.equals("queued") ? "等待 AI 开始计算…" : "AI 正在思考…");
        }
        syncTimer();
    }

    void tick() {
        if (!counting) return;
        long remaining = Math.max(0, deadline - clock.getAsLong());
        setText(
                remaining > 0
                        ? String.format(Locale.ROOT, "AI 思考剩余约 %.1f 秒", remaining / 1_000_000_000.0)
                        : "AI 正在完成落子…");
    }

    private void syncTimer() {
        if (counting && isShowing()) timer.start();
        else timer.stop();
    }
}
