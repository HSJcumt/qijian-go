package go.test;

import go.core.GameView;
import go.model.EmbeddedModelService;
import go.model.ModelClient;
import go.net.Protocol.Frame;
import go.server.GameServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Optional real TCP + installed GPU model test, separate from deterministic regression tests. */
public final class IntegratedGpuSmoke {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("build/test-output");
        Files.createDirectories(output);
        try (EmbeddedModelService service = new EmbeddedModelService(Path.of("katago"));
                GameServer server = new GameServer("127.0.0.1", 0,
                        Files.createTempDirectory(output, "gpu-records-"), Duration.ofSeconds(2),
                        new ModelClient(service))) {
            server.start();
            try (ArchitectureTests.Peer human = new ArchitectureTests.Peer(server.port());
                    ArchitectureTests.Peer watcher = new ArchitectureTests.Peer(server.port())) {
                String stamp = UUID.randomUUID().toString().substring(0, 6);
                human.hello("GPU玩家-" + stamp);
                watcher.hello("GPU观众-" + stamp);
                var heartbeat = Executors.newSingleThreadScheduledExecutor();
                try {
                heartbeat.scheduleAtFixedRate(() -> {
                    try {
                        human.send("PING", Map.of());
                        watcher.send("PING", Map.of());
                    } catch (Exception ignored) { }
                }, 5, 5, TimeUnit.SECONDS);
                human.await(f -> f.type().equals("AI_CATALOG")
                        && f.get("AMATEUR_LOW").equals("true"));
                human.send("CREATE_AI_ROOM", ArchitectureTests.ai("katago", 2));
                Frame opened = human.await(f -> f.type().equals("STATE")
                        && f.number("revision") == 1, 360000);
                watcher.send("WATCH_ROOM", Map.of("roomId", opened.get("roomId")));
                Frame seen = watcher.state(f -> f.get("role").equals("0"));
                if (!seen.get("board").equals(opened.get("board")))
                    throw new AssertionError("Spectator board differs");
                human.action("PASS", 1, Map.of());
                Frame reply = human.await(f -> f.type().equals("STATE")
                        && f.number("revision") >= 3, 360000);
                new GameView(reply.fields()).replayBoards();
                human.action("RESIGN", reply.number("revision"), Map.of());
                Frame done = human.state(f -> f.get("phase").equals("FINISHED"));
                if (!done.get("winner").equals("1")) throw new AssertionError("Wrong winner");
                System.out.println("PASS integrated server + real GPU: AI opening, pass/reply, spectator, settlement");
                } finally { heartbeat.shutdownNow(); }
            }
        }
    }
}
