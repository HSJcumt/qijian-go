package go.client;

import go.ai.AiDifficulty;
import go.test.ClientEntryTests;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

public final class AiSetupTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        Map<String, String> all = new HashMap<>();
        for (AiDifficulty d : AiDifficulty.values()) all.put(d.name(), "true");
        SwingUtilities.invokeAndWait(
                () -> {
                    AtomicReference<AiSetupPanel.Selection> accepted = new AtomicReference<>();
                    AtomicBoolean online = new AtomicBoolean(true);
                    AiSetupPanel panel = new AiSetupPanel(null, all, online::get, accepted::set);
                    check(
                            find(panel, "algorithmHeuristic") == null
                                    && find(panel, "algorithmMachine") == null,
                            "algorithm choice is removed");
                    check(
                            find(panel, "modelSMALL") == null && find(panel, "budgetQUICK") == null,
                            "parameter and inference choices are removed");
                    for (AiDifficulty d : AiDifficulty.values()) {
                        click(panel, "difficulty" + d.name());
                        check(accepted.get() == null, "selection alone cannot create a room");
                        click(panel, "aiNextStep");
                        check(
                                accepted.get().difficulty() == d
                                        && accepted.get().profile() == d.model()
                                        && accepted.get().budget() == d.budget(),
                                "difficulty determines model and budget");
                        check(
                                accepted.get().size() == 19 && accepted.get().color() == 1,
                                "every preset uses a model on 19 lines");
                        accepted.set(null);
                    }
                    panel.catalog(Map.of());
                    check(!panel.primaryButton().isEnabled(), "missing model disables start");
                    click(panel, "aiNextStep");
                    check(accepted.get() == null, "missing model cannot start");
                    panel.catalog(all);
                    online.set(false);
                    click(panel, "aiNextStep");
                    check(accepted.get() == null, "disconnect blocks start");
                    online.set(true);
                    panel.catalog(all);
                    AiSetupPanel restored =
                            new AiSetupPanel(
                                    new AiSetupPanel.Selection(AiDifficulty.AMATEUR_MID, 19, 1),
                                    all,
                                    () -> true,
                                    accepted::set);
                    check(
                            ((JToggleButton) find(restored, "difficultyAMATEUR_MID")).isSelected(),
                            "previous difficulty is restored");
                    render(panel, "ai-difficulties.png", 1100, 650);
                    render(restored, "ai-difficulties-minimum.png", 900, 630);
                });
        System.out.println("  Five difficulty picker: " + checks + " checks.");
    }

    private static JComponent find(Container p, String name) {
        return ClientEntryTests.find(p, name);
    }

    private static void click(Container p, String name) {
        ((AbstractButton) find(p, name)).doClick(0);
    }

    private static void render(JPanel p, String name, int w, int h) {
        p.setSize(w, h);
        layout(p);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        p.printAll(g);
        g.dispose();
        try {
            Files.createDirectories(Path.of("build/test-output"));
            ImageIO.write(im, "png", Path.of("build/test-output", name).toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void layout(Container p) {
        p.doLayout();
        for (Component c : p.getComponents()) if (c instanceof Container child) layout(child);
    }

    private static void check(boolean b, String message) {
        checks++;
        if (!b) throw new AssertionError(message);
    }
}
