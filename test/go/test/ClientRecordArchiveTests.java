package go.test;

import go.client.ClientRecordArchive;
import go.core.Game;
import go.core.GameView;
import go.core.RecordStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class ClientRecordArchiveTests {
    private static int checks;

    public static void run() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build/test-output"), "auto-records-");
        Path records = root.resolve("client");
        List<Exception> errors = new CopyOnWriteArrayList<>();
        Game game = new Game(9, 7.5);
        game.play(1, 2, 2);
        game.play(2, 3, 3);
        Map<String, String> data = new HashMap<>(game.snapshot());
        data.put("gameId", "../unsafe-id");
        try (ClientRecordArchive archive = new ClientRecordArchive(records, errors::add)) {
            archive.save(new GameView(data));
            check(!Files.exists(records), "unfinished games do not create local records");
            game.resign(2, "认输");
            data.putAll(game.snapshot());
            GameView ended = new GameView(data);
            for (int i = 0; i < 10; i++) archive.save(ended);
            await(() -> count(records) == 1);
            Path file = only(records);
            GameView loaded = RecordStore.load(file);
            check(
                    loaded.phase.equals("FINISHED")
                            && loaded.text("gameId").equals("../unsafe-id")
                            && loaded.replayBoards().size() == 3,
                    "automatic record contains the final result and replayable moves");
            check(
                    file.getParent().equals(records.toAbsolutePath()),
                    "game identity cannot escape the local record directory");
        }
        Path existing = only(records);
        byte[] before = Files.readAllBytes(existing);
        try (ClientRecordArchive restarted = new ClientRecordArchive(records, errors::add)) {
            restarted.save(new GameView(data));
        }
        await(() -> Arrays.equals(before, read(existing)));
        check(count(records) == 1, "reconnect and repeated final states keep one local record");
        Path invalid = root.resolve("blocked");
        Files.writeString(invalid, "not a directory");
        CountDownLatch failure = new CountDownLatch(1);
        try (ClientRecordArchive blocked =
                new ClientRecordArchive(invalid, e -> failure.countDown())) {
            blocked.save(new GameView(data));
            check(
                    failure.await(3, TimeUnit.SECONDS),
                    "storage failure is reported instead of silently discarded");
            Files.delete(invalid);
            Files.createDirectories(invalid);
            blocked.save(new GameView(data));
            await(() -> count(invalid) == 1);
            check(
                    RecordStore.load(only(invalid)).phase.equals("FINISHED"),
                    "a failed write can retry after storage recovers");
        }
        // Closing immediately drains queued writes rather than interrupting the disk operation.
        Path closing = root.resolve("closing");
        ClientRecordArchive archive = new ClientRecordArchive(closing, errors::add);
        archive.save(new GameView(data));
        archive.close();
        archive.save(new GameView(data));
        await(() -> count(closing) == 1);
        check(
                RecordStore.load(only(closing)).phase.equals("FINISHED") && errors.isEmpty(),
                "window shutdown still completes a queued final record");
        System.out.println("  Automatic client records: " + checks + " checks");
    }

    private static byte[] read(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static long count(Path p) {
        try (var files = Files.list(p)) {
            return files.filter(f -> f.toString().endsWith(".gogame")).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static Path only(Path p) throws Exception {
        try (var files = Files.list(p)) {
            return files.filter(f -> f.toString().endsWith(".gogame"))
                    .findFirst()
                    .orElseThrow()
                    .toAbsolutePath();
        }
    }

    private static void await(BooleanSupplier p) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < end) {
            if (p.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Local record write timed out");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
