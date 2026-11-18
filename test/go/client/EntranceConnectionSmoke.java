package go.client;

import go.server.GameServer;
import go.ai.KataGoEngine;
import go.core.GameView;
import go.model.EmbeddedModelService;
import go.model.ModelClient;
import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Headful integration test of the actual frame and background network callbacks. */
public final class EntranceConnectionSmoke {
    private static GoApp app;
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
    private static Object field(String name) throws Exception {
        Field field = GoApp.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(app);
    }
    private static void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = GoApp.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(app, args);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void await(Callable<Boolean> predicate) throws Exception {
        await(predicate, 12);
    }
    private static void await(Callable<Boolean> predicate, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (edt(predicate)) return;
            Thread.sleep(30);
        }
        throw new AssertionError("Timed out awaiting connection state");
    }
    private static void configure(int port) throws Exception {
        edt(() -> {
            JButton button = (JButton) go.test.ClientEntryTests.find(app, "configureServerButton");
            check(button != null && button.isEnabled(), "entrance offers configuration");
            button.doClick(0);
            ((JTextField) go.test.ClientEntryTests.find(app, "serverHostInput")).setText("127.0.0.1");
            ((JTextField) go.test.ClientEntryTests.find(app, "serverPortInput")).setText("invalid");
            JButton save = (JButton) go.test.ClientEntryTests.find(app, "saveServerAndConnect");
            save.doClick(0);
            check(!((Boolean) field("connecting")), "invalid port never starts a connection");
            ((JTextField) go.test.ClientEntryTests.find(app, "serverPortInput")).setText(Integer.toString(port));
            save.doClick(0);
            check(field("mode").toString().equals("ENTRANCE"), "saving cannot bypass server handshake");
            return null;
        });
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of("build/test-output");
        Files.createDirectories(output);
        Path folder = Files.createTempDirectory(output, "entrance-connection-");
        Path config = folder.resolve("client.properties");
        System.setProperty("go.client.config", config.toString());
        System.setProperty("go.client.profiles", folder.resolve("profiles.properties").toString());
        System.setProperty("go.client.records", folder.resolve("records").toString());
        try {
            app = edt(GoApp::new);
            edt(() -> { invoke("login", new Class<?>[] {String.class}, "入口测试"); return null; });
            int unavailable;
            try (ServerSocket socket = new ServerSocket(0)) { unavailable = socket.getLocalPort(); }
            configure(unavailable);
            await(() -> !(Boolean) field("connecting"));
            edt(() -> {
                check(field("mode").toString().equals("ENTRANCE"), "failed connect stays at entrance");
                JLabel status = (JLabel) go.test.ClientEntryTests.find(app, "entranceConnectionStatus");
                check(status.getText().contains("未连接服务器"), "failure explicitly reports no connection");
                return null;
            });
            // A TCP connection alone must not unlock the game; WELCOME is required.
            try (ServerSocket listener = new ServerSocket(0)) {
                listener.setSoTimeout(5000);
                configure(listener.getLocalPort());
                try (Socket socket = listener.accept()) {
                    Thread.sleep(200);
                    edt(() -> {
                        check(!(Boolean) field("connected") && (Boolean) field("connecting"), "waits for WELCOME");
                        check(field("mode").toString().equals("ENTRANCE"), "TCP socket cannot unlock home");
                        return null;
                    });
                }
            }
            await(() -> !(Boolean) field("connecting"));
            boolean practice = args.length > 0 && args[0].equals("--practice");
            try (EmbeddedModelService service = new EmbeddedModelService(KataGoEngine.home());
                 GameServer server = new GameServer("127.0.0.1", 0, folder.resolve("server-records"),
                         Duration.ofSeconds(2), practice ? new ModelClient(service) : null)) {
                server.start();
                configure(server.port());
                await(() -> (Boolean) field("connected") && field("mode").toString().equals("HOME"));
                ClientProfiles restored = new ClientProfiles(folder.resolve("profiles.properties"));
                restored.load();
                restored.configurePublishedServer(config);
                check(restored.server().port() == server.port(), "edited server survives restart");
                if (practice) {
                    edt(() -> {
                        ((JButton) go.test.ClientEntryTests.find(app, "homePracticeButton")).doClick(0);
                        return null;
                    });
                    await(() -> {
                        JButton start = (JButton) go.test.ClientEntryTests.find(app, "aiNextStep");
                        return start != null && start.isEnabled();
                    });
                    edt(() -> {
                        ((JButton) go.test.ClientEntryTests.find(app, "aiNextStep")).doClick(0);
                        return null;
                    });
                    await(() -> {
                        GameView view = (GameView) field("view");
                        if (view != null && !view.text("aiError").isEmpty())
                            throw new AssertionError(view.text("aiError"));
                        return view != null && !view.flag("aiPreparing")
                                && field("mode").toString().equals("ONLINE");
                    }, 660);
                    edt(() -> {
                        invoke("gameAction", new Class<?>[] {String.class, Point.class}, "MOVE", new Point(3, 3));
                        return null;
                    });
                    await(() -> ((GameView) field("view")).revision == 2, 60);
                    System.out.println("PASS actual packaged client: practice button, difficulty, real GPU preparation, board entry, human move + AI reply");
                    edt(() -> {
                        ((JButton) go.test.ClientEntryTests.find(app, "countButton")).doClick(0);
                        return null;
                    });
                    await(() -> {
                        JButton end = (JButton) go.test.ClientEntryTests.find(app, "endAiGameButton");
                        return end != null && end.isEnabled();
                    }, 660);
                    edt(() -> {
                        check(!((GameView) field("view")).text("assessedLead").isEmpty(), "real engine publishes a score preview");
                        ((JButton) go.test.ClientEntryTests.find(app, "endAiGameButton")).doClick(0);
                        return null;
                    });
                    await(() -> ((GameView) field("view")).phase.equals("FINISHED"));
                    edt(() -> {
                        check(((GameView) field("view")).text("scoreMethod").equals("katago"), "actual UI accepts model score");
                        return null;
                    });
                    System.out.println("PASS actual packaged client: real model score preview and explicit settlement confirmation");
                }
            }
            // An active room retains ONLINE internally for reconnect, while displaying entrance.
            await(() -> !(Boolean) field("connected")
                    && go.test.ClientEntryTests.find(app, "entranceScreen").isVisible());
            System.out.println("PASS entrance: invalid port, refused connection, no WELCOME, saved address, successful login, disconnect");
        } finally {
            if (app != null) edt(() -> { invoke("disposeApp", new Class<?>[0]); return null; });
        }
    }
}
