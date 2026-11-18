package go.test;

import go.ai.AiProfile;
import go.ai.KataGoEngine;
import go.core.Game;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real child-process tests for GTP framing, history, failures, timeout and cancellation. */
public final class KataGoTests {
    private static int checks;

    public static void run() throws Exception {
        for (int size : new int[] {9, 13, 19})
            for (int x = 0; x < size; x++)
                for (int y = 0; y < size; y++) {
                    Game.Move move = new Game.Move(2, x, y);
                    check(
                            KataGoEngine.parseVertex(KataGoEngine.vertex(move, size), 2, size)
                                    .equals(move),
                            "coordinate round trip");
                }
        for (String invalid :
                new String[] {"I4", "A0", "T20", "Z1", "D04", "", "resign", "D4 junk"}) {
            try {
                KataGoEngine.parseVertex(invalid, 1, 19);
                throw new AssertionError("Accepted invalid vertex");
            } catch (IOException expected) {
                checks++;
            }
        }
        Path output = Path.of("build/test-output").toAbsolutePath();
        Files.createDirectories(output);
        Path warmTrace = output.resolve("fake-katago-warmup.txt");
        try (KataGoEngine warm = fake("normal", warmTrace, Duration.ofSeconds(3))) {
            warm.prepare(19, 7.5);
            check(
                    Files.readString(warmTrace).contains("kata-raw-nn B 0")
                            && !Files.readString(warmTrace).contains("kata-search"),
                    "warmup evaluates the network without generating a move");
            warm.configureBudget(AiProfile.Budget.DEEP);
            warm.configureBudget(AiProfile.Budget.QUICK);
            Game first = new Game(19, 7.5);
            first.play(1, 2, 2);
            warm.choose(first);
            String commands = Files.readString(warmTrace);
            check(
                    commands.lines().filter(s -> s.contains("protocol_version")).count() == 1,
                    "warmup and budget changes reuse one process");
            check(
                    commands.contains("kata-set-param maxVisits 1600")
                            && commands.contains("kata-set-param maxTime 1"),
                    "runtime budgets preserve selected limits");
            check(
                    first.moves().size() == 1,
                    "prepared engine never plays ahead on the authoritative game");
        }
        Path trace = output.resolve("fake-katago-history.txt");
        Files.writeString(trace, "");
        try (KataGoEngine engine = fake("normal", trace, Duration.ofSeconds(3))) {
            Game game = new Game(19, 6.5);
            game.play(1, 2, 2);
            Game.Move reply = engine.choose(game).move();
            check(reply.equals(new Game.Move(2, 3, 15)), "GTP row orientation");
            game.play(2, reply.x(), reply.y());
            game.pass(1);
            Game.Move next = engine.choose(game).move();
            check(next.equals(new Game.Move(2, 3, 14)), "persistent process returns next move");
            String commands = Files.readString(trace);
            check(
                    commands.contains("boardsize 19") && commands.contains("komi 6.5"),
                    "board size and komi synchronized");
            check(
                    commands.contains("play B C17")
                            && commands.contains("play W D4")
                            && commands.contains("play B pass"),
                    "full history includes AI move and pass");
            check(
                    commands.lines().filter(s -> s.contains("play B C17")).count() == 1,
                    "incremental sync does not replay old moves twice");
            Game branched = new Game(9, 0.5);
            branched.play(1, 0, 0);
            engine.choose(branched);
            check(Files.readString(trace).contains("boardsize 9"), "new game rebuilds state");
        }
        for (String behavior : new String[] {"pass", "resign"})
            try (KataGoEngine engine =
                    fake(
                            behavior,
                            output.resolve("fake-" + behavior + ".txt"),
                            Duration.ofSeconds(3))) {
                KataGoEngine.Decision decision = engine.choose(new Game(19, 7.5));
                check(
                        behavior.equals("pass")
                                ? decision.move().pass() && !decision.resign()
                                : decision.resign(),
                        behavior + " is preserved");
            }
        for (String behavior :
                new String[] {"error", "badid", "invalid", "crash", "timeout", "occupied", "ko"}) {
            Path file = output.resolve("fake-" + behavior + ".txt");
            Files.writeString(file, "");
            try (KataGoEngine engine =
                    fake(
                            behavior,
                            file,
                            behavior.equals("timeout")
                                    ? Duration.ofMillis(200)
                                    : Duration.ofSeconds(3))) {
                Game game = new Game(19, 7.5);
                if (behavior.equals("occupied")) game.play(1, 3, 15);
                if (behavior.equals("ko")) {
                    game = new Game(5, 0);
                    game.play(1, 0, 1);
                    game.play(2, 1, 1);
                    game.play(1, 1, 0);
                    game.play(2, 0, 2);
                    game.play(1, 2, 1);
                    game.play(2, 2, 2);
                    game.pass(1);
                    game.play(2, 1, 3);
                    game.play(1, 1, 2);
                }
                try {
                    engine.choose(game);
                    throw new AssertionError("Expected " + behavior + " rejection");
                } catch (IOException expected) {
                    check(engine.isClosed(), behavior + " closes unusable process");
                }
                check(
                        game.moves().size()
                                == (behavior.equals("occupied")
                                        ? 1
                                        : behavior.equals("ko") ? 9 : 0),
                        "failed analysis never changes game");
            }
        }
        Path cancellation = output.resolve("fake-cancel.txt");
        Files.writeString(cancellation, "");
        KataGoEngine engine = fake("timeout", cancellation, Duration.ofSeconds(30));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task =
                    worker.submit(
                            () -> {
                                try {
                                    engine.choose(new Game(19, 7.5));
                                } catch (IOException | InterruptedException expected) {
                                }
                            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.readString(cancellation).contains("kata-search")
                    && System.nanoTime() < deadline) Thread.sleep(20);
            check(
                    Files.readString(cancellation).contains("kata-search"),
                    "slow child has started searching");
            long pid = Long.parseLong(Files.readAllLines(cancellation).get(0));
            engine.close();
            task.get(3, TimeUnit.SECONDS);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                    && System.nanoTime() < deadline) Thread.sleep(20);
            check(
                    !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "cancellation kills owned child process");
        } finally {
            engine.close();
            worker.shutdownNow();
        }
        System.out.println(
                "  KataGo adapter: "
                        + checks
                        + " checks (coordinates, history, ko, pass, resign, timeout, crash,"
                        + " cancellation)");
    }

    static KataGoEngine fake(String behavior, Path trace, Duration timeout) {
        String classPath =
                String.join(
                        File.pathSeparator,
                        Arrays.stream(
                                        System.getProperty("java.class.path")
                                                .split(
                                                        java.util.regex.Pattern.quote(
                                                                File.pathSeparator)))
                                .map(s -> Path.of(s).toAbsolutePath().toString())
                                .toList());
        Path java =
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new KataGoEngine(
                List.of(
                        java.toString(),
                        "-cp",
                        classPath,
                        KataGoTests.class.getName(),
                        behavior,
                        trace.toString()),
                Path.of("."),
                Duration.ofSeconds(5),
                timeout);
    }

    public static void main(String[] args) throws Exception {
        String behavior = args[0];
        Path trace = Path.of(args[1]);
        Files.writeString(trace, ProcessHandle.current().pid() + "\n");
        BufferedReader input =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        int searches = 0;
        while ((line = input.readLine()) != null) {
            Files.writeString(trace, line + "\n", StandardOpenOption.APPEND);
            int split = line.indexOf(' ');
            String id = line.substring(0, split), command = line.substring(split + 1);
            String body = command.equals("protocol_version") ? "2" : "";
            String prefix = "=";
            if (command.equals("final_score")) body = behavior.equals("score-failure") ? "B+NaN" : "W+12.5";
            if (command.equals("final_status_list dead") && behavior.equals("score-dead")) body = "A19";
            if (behavior.equals("warm-slow") && command.startsWith("kata-raw-nn"))
                Thread.sleep(1200);
            if (command.startsWith("kata-search")) {
                searches++;
                if (behavior.equals("warm-slow")) Thread.sleep(700);
                if (behavior.equals("timeout")) Thread.sleep(30000);
                if (behavior.equals("crash")) return;
                if (behavior.equals("error")) {
                    prefix = "?";
                    body = "unsupported search";
                } else if (behavior.equals("badid")) {
                    id = "99999";
                    body = "D4";
                } else
                    body =
                            switch (behavior) {
                                case "pass" -> "pass";
                                case "resign" -> "resign";
                                case "invalid" -> "I4";
                                case "ko" -> "B4";
                                default -> searches == 1 ? "D4" : "D5";
                            };
            }
            System.err.println("diagnostic output must never be parsed as GTP");
            System.out.print(prefix + id + " " + body + "\r\n\r\n");
            System.out.flush();
        }
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
