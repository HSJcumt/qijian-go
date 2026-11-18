package go.test;

import go.ai.KataGoEngine;
import go.core.Game;
import go.core.RecordStore;
import go.model.EmbeddedModelService;
import go.model.ModelClient;
import go.net.Protocol.Frame;
import go.server.GameServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public final class ScoringTests {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception { run(); }
    public static void run() throws Exception {
        Path output = Path.of("build/test-output").toAbsolutePath();
        Files.createDirectories(output);
        for (String invalid : new String[] {"", "B+NaN", "W+Infinity", "B+R", "B+-2", "B+1001", "junk"}) {
            try { KataGoEngine.parseScore(invalid); throw new AssertionError("Invalid score accepted"); }
            catch (IOException expected) { }
        }
        check(KataGoEngine.parseScore("B+3.5") == 3.5 && KataGoEngine.parseScore("W+2") == -2
                && KataGoEngine.parseScore("0") == 0, "score direction and ties");
        Game game = new Game(19);
        game.play(1, 0, 0);
        check(game.score().black() > game.score().white(), "old raw board algorithm incorrectly favors black here");
        try (KataGoEngine engine = KataGoTests.fake("score-dead", output.resolve("scoring-gtp.txt"), Duration.ofSeconds(3))) {
            KataGoEngine.Assessment assessment = engine.assess(game);
            check(assessment.blackLead() == -12.5 && assessment.dead().get(0), "neural score and dead stone parsed");
            check(game.phase() == Game.Phase.PLAYING && game.revision() == 1, "preview does not finish or alter board");
            game.adjudicate(assessment.blackLead(), assessment.dead());
            check(game.winner() == 2 && game.phase() == Game.Phase.FINISHED, "accepted model assessment overrides raw flood-fill winner");
            Path record = output.resolve("model-scored.gogame");
            RecordStore.save(record, game.snapshot());
            check(RecordStore.load(record).integer("winner") == 2
                    && RecordStore.load(record).text("scoreMethod").equals("katago"), "adjudication survives archive");
        }
        String commands = Files.readString(output.resolve("scoring-gtp.txt"));
        check(commands.contains("final_score") && commands.contains("final_status_list dead")
                && commands.contains("kata-set-rule friendlyPassOk true")
                && commands.contains("kata-set-rule friendlyPassOk false"), "dead-stone-friendly rule restored after assessment");
        Path home = ModelWarmupTests.fakeHome(output);
        for (String behavior : new String[] {"normal", "score-failure"}) {
            try (EmbeddedModelService service = new EmbeddedModelService(home,
                    d -> KataGoTests.fake(behavior, output.resolve("score-server-" + behavior + ".txt"), Duration.ofSeconds(3)));
                 GameServer server = new GameServer("127.0.0.1", 0,
                         Files.createTempDirectory(output, "score-records-"), Duration.ofSeconds(2), new ModelClient(service))) {
                server.start();
                try (ArchitectureTests.Peer human = new ArchitectureTests.Peer(server.port());
                     ArchitectureTests.Peer watcher = new ArchitectureTests.Peer(server.port())) {
                    human.hello("scoring-human"); watcher.hello("scoring-watcher");
                    human.await(f -> f.type().equals("AI_CATALOG") && f.get("AMATEUR_LOW").equals("true"));
                    human.send("CREATE_AI_ROOM", ArchitectureTests.ai("katago", 1));
                    Frame ready = human.state(f -> f.get("aiPreparing").equals("false"));
                    human.action("END_AI_GAME", ready.number("revision"), Map.of());
                    human.await(f -> f.type().equals("ERROR"));
                    human.action("ANALYZE_SCORE", ready.number("revision"), Map.of());
                    Frame scored = human.state(f -> behavior.equals("normal") ? !f.get("assessedLead").isEmpty() : !f.get("scoreError").isEmpty());
                    check(scored.get("phase").equals("PLAYING"), "analysis alone cannot end game");
                    if (behavior.equals("score-failure")) {
                        human.action("END_AI_GAME", scored.number("revision"), Map.of());
                        human.await(f -> f.type().equals("ERROR"));
                        continue;
                    }
                    human.action("MOVE", scored.number("revision"), Map.of("x", "2", "y", "2"));
                    Frame moved = human.state(f -> f.number("revision") == 2);
                    human.action("END_AI_GAME", 2, Map.of());
                    human.await(f -> f.type().equals("ERROR"));
                    human.action("ANALYZE_SCORE", 2, Map.of());
                    human.state(f -> !f.get("assessedLead").isEmpty());
                    watcher.send("WATCH_ROOM", Map.of("roomId", moved.get("roomId")));
                    watcher.state(f -> f.get("role").equals("0"));
                    watcher.action("END_AI_GAME", 2, Map.of());
                    watcher.await(f -> f.type().equals("ERROR"));
                    human.action("END_AI_GAME", 2, Map.of());
                    Frame done = human.state(f -> f.get("phase").equals("FINISHED"));
                    check(done.get("winner").equals("2") && done.get("scoreMethod").equals("katago"), "server finalizes the accepted neural winner");
                }
            }
        }
        try (GameServer server = new GameServer("127.0.0.1", 0,
                Files.createTempDirectory(output, "human-score-records-"), Duration.ofSeconds(2))) {
            server.start();
            try (ArchitectureTests.Peer black = new ArchitectureTests.Peer(server.port());
                 ArchitectureTests.Peer white = new ArchitectureTests.Peer(server.port())) {
                black.hello("score-black"); white.hello("score-white");
                black.send("CREATE_ROOM", Map.of("title", "结算确认", "size", "9"));
                Frame waiting = black.state(f -> f.get("phase").equals("WAITING"));
                white.send("JOIN_ROOM", Map.of("roomId", waiting.get("roomId")));
                white.state(f -> f.get("role").equals("2"));
                black.send("READY", Map.of()); white.send("READY", Map.of());
                Frame playing = black.state(f -> f.get("phase").equals("PLAYING"));
                black.action("REQUEST_SCORE", playing.number("revision"), Map.of());
                Frame requested = white.state(f -> !f.get("scoreRequestId").isEmpty());
                white.action("RESPOND_SCORE", requested.number("revision"),
                        Map.of("scoreRequestId", requested.get("scoreRequestId"), "accept", "true"));
                Frame reviewing = black.state(f -> f.get("phase").equals("SCORING"));
                check(reviewing.get("confirmed").equals("0"), "accepting request enters review without declaring winner");
                black.action("CONFIRM_SCORE", reviewing.number("revision"), Map.of());
                Frame first = white.state(f -> f.get("confirmed").equals("1"));
                check(first.get("phase").equals("SCORING"), "one confirmation cannot finish human game");
                white.action("CONFIRM_SCORE", first.number("revision"), Map.of());
                black.state(f -> f.get("phase").equals("FINISHED"));
            }
        }
        System.out.println("  Scoring: dead stones, neural winner, archived result, stale preview, spectator rejection, model failure, human review passed");
    }
}
