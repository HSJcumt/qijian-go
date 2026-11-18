package go.client;

import go.core.GameView;
import go.core.RecordStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Each completed live game is written once locally, including during client shutdown. */
public final class ClientRecordArchive implements AutoCloseable {
    private boolean closed;
    private final Path directory;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Consumer<IOException> failure;
    // Non-daemon: a completed game's pending write must survive closing the window.
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "go-record-archive"));

    public ClientRecordArchive(Path directory, Consumer<IOException> failure) {
        this.directory = directory.toAbsolutePath();
        this.failure = failure;
    }

    public Path directory() {
        return directory;
    }

    public synchronized void save(GameView completed) {
        if (closed) return;
        String id = completed.text("gameId");
        if (!completed.phase.equals("FINISHED") || id.isEmpty() || !queued.add(id)) return;
        writer.execute(
                () -> {
                    Path target =
                            directory.resolve(
                                    "棋间-"
                                            + UUID.nameUUIDFromBytes(
                                                    id.getBytes(StandardCharsets.UTF_8))
                                            + ".gogame");
                    try {
                        if (Files.isRegularFile(target)) {
                            try {
                                if (RecordStore.load(target).text("gameId").equals(id)) return;
                            } catch (IOException ignored) {
                                /* Replace an incomplete or damaged local copy. */
                            }
                        }
                        RecordStore.save(target, completed.fields);
                    } catch (IOException | RuntimeException e) {
                        queued.remove(id);
                        failure.accept(
                                new IOException("棋谱自动保存失败：" + target + "；" + e.getMessage(), e));
                    }
                });
    }

    @Override
    public synchronized void close() {
        closed = true;
        writer.shutdown();
    }
}
