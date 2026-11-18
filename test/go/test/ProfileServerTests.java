package go.test;

import go.core.Game;
import go.core.RecordStore;
import go.model.ModelClient;
import go.model.EmbeddedModelService;
import go.net.Protocol.Frame;
import go.server.GameServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Real TCP result ownership, live rename, history recovery and backwards-compatible records. */
@SuppressWarnings("try")
public final class ProfileServerTests {
    private static int checks;

    public static void run() throws Exception {
        Path records = Files.createTempDirectory(Path.of("build/test-output"), "profile-server-");
        String id = UUID.randomUUID().toString(),
                otherId = UUID.randomUUID().toString(),
                key = "profile-test-key-32-characters-minimum";
        try (EmbeddedModelService node =
                new EmbeddedModelService(
                        ModelWarmupTests.fakeHome(records),
                        difficulty ->
                                KataGoTests.fake(
                                        "normal",
                                        records.resolve("profile-model.txt"),
                                        Duration.ofSeconds(5)))) {
            ModelClient model = new ModelClient(node);
            try (GameServer server =
                    new GameServer("127.0.0.1", 0, records, Duration.ofSeconds(1), model)) {
                server.start();
                try (ArchitectureTests.Peer first = new ArchitectureTests.Peer(server.port());
                        ArchitectureTests.Peer second = new ArchitectureTests.Peer(server.port());
                        ArchitectureTests.Peer watcher =
                                new ArchitectureTests.Peer(server.port())) {
                    hello(first, "profile-a", id, "profile-a");
                    hello(second, "profile-b", otherId, "profile-b");
                    watcher.hello("profile-watcher");
                    first.send("CREATE_ROOM", Map.of("title", "资料测试", "size", "9", "komi", "7.5"));
                    Frame waiting = first.state(f -> f.get("phase").equals("WAITING"));
                    String room = waiting.get("roomId"), game = waiting.get("gameId");
                    first.send("UPDATE_PROFILE", Map.of("nickname", "profile-b"));
                    first.await(f -> f.type().equals("ERROR"));
                    checks++;
                    first.send("UPDATE_PROFILE", Map.of("nickname", "profile-renamed"));
                    check(
                            first.await(f -> f.type().equals("PROFILE_UPDATED"))
                                    .get("nickname")
                                    .equals("profile-renamed"),
                            "online rename is acknowledged without leaving the room");
                    second.send("JOIN_ROOM", Map.of("roomId", room));
                    second.state(f -> f.get("role").equals("2"));
                    watcher.send("WATCH_ROOM", Map.of("roomId", room));
                    watcher.state(f -> f.get("role").equals("0"));
                    first.send("READY", Map.of());
                    second.send("READY", Map.of());
                    Frame start =
                            first.state(
                                    f ->
                                            f.get("phase").equals("PLAYING")
                                                    && !f.get("aiPreparing").equals("true"));
                    check(
                            start.get("blackProfileId").equals(id)
                                    && start.get("black").equals("profile-renamed"),
                            "stable player identity is included in the authoritative game");
                    first.action("RESIGN", start.number("revision"), Map.of());
                    Frame result = first.await(f -> f.type().equals("GAME_RESULT"));
                    check(
                            result.get("gameId").equals(game)
                                    && result.get("outcome").equals("2")
                                    && result.get("machine").equals("false"),
                            "human loser receives a classified final result");
                    check(
                            second.await(f -> f.type().equals("GAME_RESULT"))
                                    .get("outcome")
                                    .equals("1"),
                            "human winner receives the same match with the opposite result");
                    check(history(watcher).isEmpty(), "spectators do not inherit a match result");
                    check(history(first).size() == 1, "history contains each human game once");
                    first.send("LEAVE_ROOM", Map.of());
                    first.await(f -> f.type().equals("LEFT"));
                    first.send("AI_CATALOG", Map.of());
                    first.await(
                            f ->
                                    f.type().equals("AI_CATALOG")
                                            && f.get("AMATEUR_LOW").equals("true"));
                    first.send("CREATE_AI_ROOM", ArchitectureTests.ai("katago", 1));
                    first.state(
                            f ->
                                    f.get("phase").equals("PLAYING")
                                            && !f.get("aiPreparing").equals("true"));
                    first.send("LEAVE_ROOM", Map.of());
                    Frame exited = first.await(f -> f.type().equals("GAME_RESULT"));
                    check(
                            exited.get("machine").equals("true")
                                    && exited.get("outcome").equals("2"),
                            "leaving an active AI game counts as a loss before the seat is"
                                    + " removed");
                    first.await(f -> f.type().equals("LEFT"));
                    check(history(first).size() == 2, "AI and human history both remain available");
                }
            }
            // A server restart must still recover results after another local nickname change.
            try (GameServer server =
                    new GameServer("127.0.0.1", 0, records, Duration.ofSeconds(1), model)) {
                server.start();
                try (ArchitectureTests.Peer player = new ArchitectureTests.Peer(server.port());
                        ArchitectureTests.Peer stranger =
                                new ArchitectureTests.Peer(server.port())) {
                    hello(player, "renamed-again", id, "profile-a\nprofile-renamed\nrenamed-again");
                    check(
                            history(player).size() == 2,
                            "stable identity recovers both game types after server restart and"
                                    + " rename");
                    hello(
                            stranger,
                            "profile-renamed",
                            UUID.randomUUID().toString(),
                            "profile-renamed");
                    check(
                            history(stranger).isEmpty(),
                            "reusing a nickname cannot claim games already recorded with a"
                                    + " different identity");
                    Game legacy = new Game(9, 7.5);
                    legacy.resign(1, "legacy");
                    Map<String, String> record = new HashMap<>(legacy.snapshot());
                    record.putAll(
                            Map.of(
                                    "gameId",
                                    "legacy-match",
                                    "black",
                                    "profile-a",
                                    "white",
                                    "legacy-opponent",
                                    "aiMode",
                                    ""));
                    RecordStore.save(records.resolve("legacy.gogame"), record);
                    check(
                            history(player).containsKey("legacy-match"),
                            "old records without identity fields are matched through the player's"
                                    + " previous names");
                    player.send("AI_CATALOG", Map.of());
                    player.await(
                            f ->
                                    f.type().equals("AI_CATALOG")
                                            && f.get("AMATEUR_LOW").equals("true"));
                    player.send("CREATE_AI_ROOM", ArchitectureTests.ai("katago", 1));
                    Frame active =
                            player.state(
                                    f ->
                                            f.get("phase").equals("PLAYING")
                                                    && !f.get("aiPreparing").equals("true"));
                    player.close();
                    Thread.sleep(2200);
                    try (ArchitectureTests.Peer returned =
                            new ArchitectureTests.Peer(server.port())) {
                        hello(returned, "after-disconnect", id, "renamed-again\nprofile-a");
                        check(
                                history(returned)
                                        .getOrDefault(active.get("gameId"), "")
                                        .endsWith("true\t2"),
                                "an expired disconnected AI match is recovered as a loss from"
                                        + " server history");
                    }
                }
            }
        }
        System.out.println(
                "  Profile server: "
                        + checks
                        + " checks (live nickname, wins/losses, spectators, leave, server restart"
                        + " and legacy history).");
    }

    private static void hello(ArchitectureTests.Peer peer, String name, String id, String aliases)
            throws Exception {
        peer.send("HELLO", Map.of("nickname", name, "profileId", id, "aliases", aliases));
        check(
                peer.await(f -> f.type().equals("WELCOME")).get("profileEditing").equals("true"),
                "server advertises live profile editing");
    }

    private static Map<String, String> history(ArchitectureTests.Peer peer) throws Exception {
        peer.send("PLAYER_HISTORY", Map.of());
        Map<String, String> results = new HashMap<>();
        while (true) {
            Frame frame = peer.await(f -> f.type().equals("PLAYER_HISTORY"));
            for (String row : frame.get("results").split("\n"))
                if (!row.isEmpty()) results.put(row.split("\t")[0], row);
            if (frame.get("complete").equals("true")) return results;
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
