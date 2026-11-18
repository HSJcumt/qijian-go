package go.test;

import go.client.ClientEntry;
import go.client.ClientProfiles;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Local profiles, click-to-enter actions and bundled artwork; works headlessly. */
public final class ClientEntryTests {
    private static int checks;

    public static void run() throws Exception {
        Path output = Path.of("build/test-output");
        Files.createDirectories(output);
        Path folder = Files.createTempDirectory(output, "client-profiles-");
        Path file = folder.resolve("client.properties");
        ClientProfiles profiles = new ClientProfiles(file);
        profiles.load();
        check(
                profiles.players().isEmpty() && !Files.exists(file),
                "first launch has no invented player");
        check(profiles.server().port() == 9528, "default server uses the integrated server port");
        check(profiles.login("  棋友甲  ").equals("棋友甲"), "new Unicode name is normalized");
        profiles.login("棋友乙");
        profiles.login("棋友甲");
        ClientProfiles loaded = new ClientProfiles(file);
        loaded.load();
        check(
                loaded.players().equals(List.of("棋友甲", "棋友乙")) && loaded.lastPlayer().equals("棋友甲"),
                "existing names and last login survive restart without duplicates");
        byte[] before = Files.readAllBytes(file);
        for (String invalid : List.of("", " ", "abcdefghijklmnopq", "棋友\n甲")) {
            try {
                profiles.login(invalid);
                throw new AssertionError("invalid player accepted");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
        check(
                java.util.Arrays.equals(before, Files.readAllBytes(file)),
                "invalid login cannot change saved names");
        profiles.selectServer(new ClientProfiles.Server("远程棋室", "localhost", 12345));
        profiles.selectServer(new ClientProfiles.Server("远程棋室更名", "LOCALHOST", 12345));
        loaded.load();
        check(
                loaded.servers().size() == 2
                        && loaded.server().name().equals("远程棋室更名")
                        && loaded.server().port() == 12345,
                "server selection persists and the same endpoint is updated");
        check(loaded.players().size() == 2, "changing servers preserves player names");
        ClientProfiles.Server local = profiles.servers().get(0), remote = profiles.server();
        profiles.rememberSession(local, "棋友甲", "session-local");
        profiles.rememberSession(remote, "棋友甲", "session-remote");
        loaded.load();
        check(
                loaded.sessionToken(local, "棋友甲").equals("session-local")
                        && loaded.sessionToken(remote, "棋友甲").equals("session-remote"),
                "returning to a saved server retains its own session after restart");
        check(
                loaded.sessionToken(remote, "棋友乙").isEmpty(),
                "session tokens never cross player identities");
        profiles.rememberSession(remote, "棋友甲", "");
        loaded.load();
        check(
                loaded.sessionToken(remote, "棋友甲").isEmpty()
                        && !loaded.sessionToken(local, "棋友甲").isEmpty(),
                "expiring one server session preserves the others");
        try {
            new ClientProfiles.Server("错误", "http://localhost", 9527);
            throw new AssertionError("URL accepted as host");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        try {
            new ClientProfiles.Server("错误", "localhost", 65536);
            throw new AssertionError("invalid port accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        ClientProfiles unwritable = new ClientProfiles(folder);
        try {
            unwritable.login("保存失败");
            throw new AssertionError("directory replaced by player data");
        } catch (java.io.IOException expected) {
            check(unwritable.players().isEmpty(), "failed save does not create a phantom player");
        }
        Path corrupt = folder.resolve("corrupt.properties");
        Files.writeString(corrupt, "players=bad\n");
        try {
            new ClientProfiles(corrupt).load();
            throw new AssertionError("corrupt profile accepted");
        } catch (java.io.IOException expected) {
            checks++;
        }

        Path publicConfig = folder.resolve("client-public.properties");
        Files.writeString(publicConfig, "server.host=game.example.com\nserver.port=19427\n");
        loaded.configurePublishedServer(publicConfig);
        check(
                loaded.server().host().equals("game.example.com")
                        && loaded.server().port() == 19427,
                "published server address overrides saved local endpoints without changing player"
                        + " identities");
        loaded.rememberSession(loaded.server(), "棋友甲", "published-session");
        loaded.edit("棋友甲", "公开昵称", 0);
        check(
                loaded.sessionToken(loaded.server(), "公开昵称").equals("published-session"),
                "renaming on a published endpoint retains its reconnect token");

        AtomicInteger entered = new AtomicInteger(), profileOpened = new AtomicInteger();
        AtomicReference<String> submitted = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () -> {
                    AtomicInteger clicks = new AtomicInteger();
                    ClientEntry.Entrance entrance =
                            new ClientEntry.Entrance(clicks::incrementAndGet);
                    click(entrance);
                    check(clicks.get() == 1, "main visual responds to click-to-enter");
                    entrance.status("连接中", true);
                    click(entrance);
                    check(clicks.get() == 1, "repeated entry clicks cannot duplicate a connection");
                    entrance.status("未连接服务器", false);
                    entrance.getActionMap()
                            .get("enterGame")
                            .actionPerformed(new ActionEvent(entrance, 0, "enter"));
                    check(clicks.get() == 2, "failed connection can be retried by keyboard");
                    render(entrance, output.resolve("client-entrance.png"));
                    JPanel login =
                            ClientEntry.login(
                                    List.of("棋友甲"), "棋友甲", submitted::set, new JLabel(" "));
                    JTextField input = (JTextField) find(login, "playerNameInput");
                    JButton button = (JButton) find(login, "loginButton");
                    button.doClick();
                    check("棋友甲".equals(submitted.get()), "saved player can log in with the button");
                    input.setText("新棋友");
                    check(
                            button.getText().contains("新建"),
                            "new name displays the create-player action");
                    input.postActionEvent();
                    check("新棋友".equals(submitted.get()), "Enter submits a new name");
                    AtomicInteger practice = new AtomicInteger(),
                            replay = new AtomicInteger(),
                            exit = new AtomicInteger(),
                            invitations = new AtomicInteger(),
                            watches = new AtomicInteger();
                    ClientEntry.Home home =
                            new ClientEntry.Home(
                                    entered::incrementAndGet,
                                    profileOpened::incrementAndGet,
                                    practice::incrementAndGet,
                                    replay::incrementAndGet,
                                    exit::incrementAndGet,
                                    p -> invitations.incrementAndGet(),
                                    p -> watches.incrementAndGet());
                    home.player("棋友甲");
                    check(
                            find(home, "homeServerButton") == null,
                            "home no longer offers a server button");
                    ((JButton) find(home, "homeProfileButton")).doClick();
                    check(
                            profileOpened.get() == 1 && entered.get() == 0,
                            "profile button remains available without entering the game");
                    check(
                            find(home, "homeVisual") == null,
                            "home has no extra hero artwork or click-to-enter overlay");
                    ((JButton) find(home, "homePracticeButton")).doClick();
                    ((JButton) find(home, "homeReplayButton")).doClick();
                    ((JButton) find(home, "homeExitButton")).doClick();
                    check(
                            practice.get() == 1 && replay.get() == 1 && exit.get() == 1,
                            "home actions invoke practice, replay and exit");
                    home.status("连接中", true);
                    ((JButton) find(home, "homeConnectionStatus")).doClick();
                    check(entered.get() == 0, "connection retry is disabled while connecting");
                    home.status("重试", false);
                    ((JButton) find(home, "homeConnectionStatus")).doClick();
                    check(entered.get() == 1, "failed connection can be retried from the list");
                    home.players(
                            List.of(
                                    new ClientEntry.OnlinePlayer("棋友甲", "", false),
                                    new ClientEntry.OnlinePlayer("棋友乙", "", false),
                                    new ClientEntry.OnlinePlayer("棋友丙", "42", true)));
                    check(
                            find(home, "playerAction:棋友甲") == null,
                            "online list excludes the current player");
                    ((JButton) find(home, "playerAction:棋友乙")).doClick();
                    ((JButton) find(home, "playerAction:棋友丙")).doClick();
                    check(
                            invitations.get() == 1 && watches.get() == 1,
                            "idle players can be invited and active games watched");
                    render(login, output.resolve("client-login.png"));
                    render(home, output.resolve("client-home.png"));
                });
        BufferedImage loginPreview = ImageIO.read(output.resolve("client-login.png").toFile());
        BufferedImage homePreview = ImageIO.read(output.resolve("client-home.png").toFile());
        check(
                loginPreview.getRGB(900, 600) == homePreview.getRGB(900, 600),
                "login and home share the same original background");
        check(
                (homePreview.getRGB(900, 600) & 0xffffff) != 0xffffff,
                "the board artwork remains visible behind the home actions");
        System.out.println(
                "  Client entry: "
                        + checks
                        + " checks; previews in build/test-output/client-*.png");
    }

    public static JComponent find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JComponent jc && name.equals(child.getName())) return jc;
            if (child instanceof Container container) {
                JComponent nested = find(container, name);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    public static void click(JComponent c) {
        c.dispatchEvent(
                new MouseEvent(
                        c,
                        MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(),
                        0,
                        100,
                        100,
                        1,
                        false,
                        MouseEvent.BUTTON1));
    }

    private static void render(JPanel panel, Path file) {
        panel.setSize(1100, 760);
        layout(panel);
        BufferedImage image = new BufferedImage(1100, 760, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        panel.printAll(g);
        g.dispose();
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container c) layout(c);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
