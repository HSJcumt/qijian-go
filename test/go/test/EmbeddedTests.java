package go.test;

import go.core.Game;
import go.model.EmbeddedModelService;
import go.model.ModelClient;
import go.net.Protocol.Frame;
import go.server.GameServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class EmbeddedTests {
    private static Map<String, String> request(Game game, String id) {
        Map<String, String> fields = new HashMap<>(game.snapshot());
        fields.putAll(Map.of("jobId", id, "gameId", "embedded-game", "aiMode", "katago", "aiDifficulty", "AMATEUR_LOW"));
        return fields;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception { run(); }
    public static void run() throws Exception {
        Path output = Path.of("build/test-output").toAbsolutePath();
        Files.createDirectories(output);
        Path home = ModelWarmupTests.fakeHome(output);
        try (EmbeddedModelService service = new EmbeddedModelService(home,
                d -> KataGoTests.fake("normal", output.resolve("embedded-gtp.txt"), Duration.ofSeconds(5)))) {
            ModelClient model = new ModelClient(service);
            check(model.request("MODEL_CATALOG", Map.of(), 3000).get("AMATEUR_LOW").equals("true"), "embedded availability");
            model.request("MODEL_CANCEL", Map.of("jobId", "cancel-first"), 3000);
            try { model.request("MODEL_MOVE", request(new Game(19, 0), "cancel-first"), 3000); throw new AssertionError("cancelled job accepted"); }
            catch (IOException expected) { }
            try (GameServer server = new GameServer("127.0.0.1", 0, output.resolve("embedded-records"), Duration.ofSeconds(2), model)) {
                server.start();
                try (ArchitectureTests.Peer human = new ArchitectureTests.Peer(server.port());
                     ArchitectureTests.Peer watcher = new ArchitectureTests.Peer(server.port())) {
                    human.hello("two-tier-human"); watcher.hello("two-tier-watcher");
                    human.await(f -> f.type().equals("AI_CATALOG") && f.get("AMATEUR_LOW").equals("true"));
                    human.send("CREATE_AI_ROOM", ArchitectureTests.ai("katago", 1));
                    Frame ready = human.state(f -> f.get("aiPreparing").equals("false") && f.get("phase").equals("PLAYING"));
                    watcher.send("WATCH_ROOM", Map.of("roomId", ready.get("roomId")));
                    human.action("MOVE", ready.number("revision"), Map.of("x", "2", "y", "2"));
                    Frame moved = human.state(f -> f.number("revision") == 2);
                    check(!moved.get("moves").isEmpty(), "real TCP move with embedded inference");
                    human.action("REQUEST_UNDO", 2, Map.of());
                    human.state(f -> f.number("revision") > 2 && f.get("moves").isEmpty());
                }
            }
        }
        // A slow native child must be killed by cancellation; the service then recovers.
        Path trace = output.resolve("embedded-cancel.txt");
        var executor = Executors.newSingleThreadExecutor();
        try (EmbeddedModelService service = new EmbeddedModelService(home,
                d -> KataGoTests.fake("timeout", trace, Duration.ofSeconds(35)))) {
            ModelClient model = new ModelClient(service);
            ModelClient.Call call = model.call("MODEL_MOVE", request(new Game(19, 0), "slow-job"), 40000);
            var future = executor.submit(() -> { try (call) { call.execute(); throw new AssertionError("cancelled call completed"); } catch (IOException expected) { } });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while ((!Files.exists(trace) || !Files.readString(trace).contains("kata-search")) && System.nanoTime() < deadline) Thread.sleep(20);
            check(Files.readString(trace).contains("kata-search"), "owned child started");
            long pid = Long.parseLong(Files.readAllLines(trace).get(0));
            call.close(); future.get(4, TimeUnit.SECONDS);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20);
            check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "cancellation reaps engine child");
            check(model.request("MODEL_CATALOG", Map.of(), 1000).get("reachable").equals("true"), "service remains responsive after cancellation");
        } finally { executor.shutdownNow(); }
        System.out.println("  Embedded model: catalog, pre-cancel, real TCP prepare/move/undo, native child cancellation passed");
    }
}
