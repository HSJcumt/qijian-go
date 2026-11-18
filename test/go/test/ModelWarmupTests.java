package go.test;

import go.ai.AiProfile;
import go.ai.KataGoEngine;
import go.core.Game;
import go.model.ModelClient;
import go.model.EmbeddedModelService;
import go.net.Protocol.Frame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real model RPC and subprocess, with controlled GTP instead of a GPU dependency. */
public final class ModelWarmupTests {
    private static int checks;

    static Path fakeHome(Path output) throws Exception {
        Path home = Files.createTempDirectory(output, "warm-model-");
        Files.createDirectories(home.resolve("engine"));
        Files.createDirectories(home.resolve("models"));
        Files.writeString(KataGoEngine.executable(home), "fake test executable");
        Files.writeString(home.resolve("gtp.cfg"), "test");
        for (AiProfile profile : AiProfile.values())
            Files.writeString(profile.model(home), "test weights");
        Files.writeString(home.resolve("models/b18c384nbt-humanv0.bin.gz"), "test human weights");
        return home;
    }

    public static void run() throws Exception {
        Path output = Path.of("build/test-output").toAbsolutePath();
        Files.createDirectories(output);
        Path home = fakeHome(output), trace = output.resolve("model-warm-reuse.txt");
        AtomicInteger starts = new AtomicInteger();
        String key = "model-warmup-test-key-at-least-32-characters";
        try (EmbeddedModelService node =
                new EmbeddedModelService(
                        home,
                        difficulty -> {
                            starts.incrementAndGet();
                            return KataGoTests.fake("normal", trace, Duration.ofSeconds(5));
                        })) {
            ModelClient client = new ModelClient(node);
            Game game = new Game(19, 7.5);
            Frame ready =
                    client.request(
                            "MODEL_PREPARE", request(game, "warm-1", "game-a", "QUICK"), 5000);
            check(
                    ready.type().equals("MODEL_READY") && game.moves().isEmpty(),
                    "prepare finishes separately from move generation");
            game.play(1, 2, 2);
            List<Frame> stages = new ArrayList<>();
            try (ModelClient.Call call =
                    client.call("MODEL_MOVE", request(game, "move-1", "game-a", "QUICK"), 5000)) {
                Frame move = call.execute(stages::add);
                game.play(2, move.integer("x"), move.integer("y"));
            }
            check(
                    stages.size() == 1 && stages.get(0).get("stage").equals("thinking"),
                    "model emits actual search-start progress before the final move");
            check(starts.get() == 1, "first white move reuses the prepared model");
            client.request(
                    "MODEL_PREPARE", request(new Game(19, 7.5), "warm-2", "game-b", "DEEP"), 5000);
            check(
                    starts.get() == 1
                            && !Files.readString(trace).contains("kata-set-param maxTime 8"),
                    "preset ignores a forged legacy inference budget and reuses its engine");
            check(
                    Files.readString(trace)
                                    .lines()
                                    .filter(s -> s.contains("protocol_version"))
                                    .count()
                            == 1,
                    "no hidden GPU restart between load and play");
        }
        System.out.println(
                "  Model preparation: "
                        + checks
                        + " checks (separate ready event, progress and process reuse)");
    }

    private static Map<String, String> request(Game game, String job, String room, String budget) {
        Map<String, String> fields = new HashMap<>(game.snapshot());
        fields.putAll(
                Map.of(
                        "jobId",
                        job,
                        "gameId",
                        room,
                        "aiMode",
                        "katago",
                        "aiModel",
                        "SMALL",
                        "aiBudget",
                        budget));
        return fields;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
