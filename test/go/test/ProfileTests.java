package go.test;

import go.client.Avatars;
import go.client.ClientProfiles;
import go.client.ProfilePanel;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

public final class ProfileTests {
    private static int checks;

    public static void run() throws Exception {
        Path output = Path.of("build/test-output");
        Files.createDirectories(output);
        Path file =
                Files.createTempDirectory(output, "player-profile-").resolve("client.properties");
        Files.writeString(file, "players=1\nplayer.0=old-player\nlastPlayer=old-player\n");
        ClientProfiles profiles = new ClientProfiles(file);
        profiles.load();
        profiles.login("old-player");
        String id = profiles.playerId("old-player");
        check(profiles.avatar("old-player") == 0, "legacy profiles receive a default avatar");
        check(
                profiles.stats("old-player", false).winRate().equals("0.0%"),
                "empty statistics have a defined zero win rate");
        profiles.rememberSession(profiles.server(), "old-player", "keep-session");
        Map<String, ClientProfiles.Result> games =
                Map.of(
                        "human-win",
                        new ClientProfiles.Result(false, 1),
                        "human-loss",
                        new ClientProfiles.Result(false, 2),
                        "human-draw",
                        new ClientProfiles.Result(false, 0),
                        "ai-win",
                        new ClientProfiles.Result(true, 1),
                        "ai-loss",
                        new ClientProfiles.Result(true, 2));
        profiles.recordResults("old-player", games);
        check(
                !profiles.recordResults("old-player", games),
                "repeated results are not counted twice");
        check(
                profiles.stats("old-player", false).games() == 3
                        && profiles.stats("old-player", false).winRate().equals("33.3%"),
                "draws count as games but not wins");
        check(
                profiles.stats("old-player", true).games() == 2
                        && profiles.stats("old-player", true).winRate().equals("50.0%"),
                "human and AI statistics remain separate");
        profiles.edit("old-player", "新昵称", 4);
        ClientProfiles loaded = new ClientProfiles(file);
        loaded.load();
        check(
                loaded.playerId("新昵称").equals(id) && loaded.avatar("新昵称") == 4,
                "renaming keeps player identity and the selected avatar across restart");
        check(
                loaded.stats("新昵称", false).games() == 3 && loaded.stats("新昵称", true).games() == 2,
                "renaming preserves all match results");
        check(
                loaded.sessionToken(loaded.server(), "新昵称").equals("keep-session")
                        && loaded.sessionToken(loaded.server(), "old-player").isEmpty(),
                "renaming migrates reconnection credentials");
        check(
                loaded.aliases("新昵称").contains("old-player"),
                "old names remain available for legacy history matching");
        loaded.login("another-player");
        try {
            loaded.edit("新昵称", "another-player", 0);
            throw new AssertionError("merged two players");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        try {
            loaded.edit("新昵称", "新昵称", 5);
            throw new AssertionError("accepted sixth avatar");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        check(
                loaded.stats("another-player", false).games() == 0,
                "new players never inherit other players' statistics");
        loaded.edit("新昵称", "第二次改名", 2);
        loaded.load();
        check(
                loaded.playerId("第二次改名").equals(id) && loaded.aliases("第二次改名").contains("新昵称"),
                "successive nickname changes preserve identity");
        AtomicReference<ProfilePanel.Edit> saved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () -> {
                    ProfilePanel panel =
                            new ProfilePanel(
                                    "第二次改名",
                                    2,
                                    loaded.stats("第二次改名", false),
                                    loaded.stats("第二次改名", true),
                                    saved::set,
                                    () -> {});
                    ((JToggleButton) ClientEntryTests.find(panel, "avatarChoice1")).doClick();
                    ((JTextField) ClientEntryTests.find(panel, "profileNickname")).setText("新显示名");
                    ((JButton) ClientEntryTests.find(panel, "saveProfileButton")).doClick();
                    check(
                            saved.get().nickname().equals("新显示名") && saved.get().avatar() == 1,
                            "the editor submits both avatar and nickname");
                    check(
                            ((JLabel) ClientEntryTests.find(panel, "humanStats"))
                                            .getText()
                                            .contains("33.3%")
                                    && ((JLabel) ClientEntryTests.find(panel, "machineStats"))
                                            .getText()
                                            .contains("50.0%"),
                            "both statistic cards show the correct rate");
                    panel.saving(true);
                    check(
                            !ClientEntryTests.find(panel, "profileNickname").isEnabled()
                                    && !ClientEntryTests.find(panel, "avatarChoice0").isEnabled(),
                            "duplicate saves are blocked while a server rename is pending");
                    panel.saving(false);
                    Set<Integer> images = new HashSet<>();
                    for (int i = 0; i < 5; i++) {
                        BufferedImage icon = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g = icon.createGraphics();
                        Avatars.icon(i, 48).paintIcon(panel, g, 0, 0);
                        g.dispose();
                        check(
                                icon.getRGB(0, 0) == 0 && (icon.getRGB(24, 24) >>> 24) > 0,
                                "avatar is circular and contains the supplied image");
                        images.add(Arrays.hashCode(icon.getRGB(0, 0, 48, 48, null, 0, 48)));
                    }
                    check(
                            images.size() == 5,
                            "all five original portraits are distinct and available");
                    panel.setSize(panel.getPreferredSize());
                    layout(panel);
                    BufferedImage preview =
                            new BufferedImage(
                                    panel.getWidth(),
                                    panel.getHeight(),
                                    BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = preview.createGraphics();
                    panel.printAll(g);
                    g.dispose();
                    try {
                        ImageIO.write(
                                preview, "png", output.resolve("player-profile.png").toFile());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
        System.out.println(
                "  Player profiles: "
                        + checks
                        + " checks (avatars, rename, persistence, separate rates, deduplication and"
                        + " editor).");
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container c) layout(c);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
