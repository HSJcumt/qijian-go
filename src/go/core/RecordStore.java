package go.core;

import go.net.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned native format: a bounded protocol frame, written using atomic replacement. */
public final class RecordStore {
    private RecordStore() {}

    public static void save(Path destination, Map<String, String> snapshot) throws IOException {
        new GameView(snapshot);
        Path target = destination.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".go-", ".tmp");
        try {
            Map<String, String> data = new LinkedHashMap<>(snapshot);
            data.put("savedAt", Instant.now().toString());
            data.put("format", "1");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                Protocol.write(out, new Protocol.Frame("RECORD", data));
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static GameView load(Path source) throws IOException {
        if (Files.size(source) > Protocol.MAX_FRAME + 4L) throw new IOException("棋谱文件过大");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source))) {
            Protocol.Frame frame = Protocol.read(in);
            if (!frame.type().equals("RECORD")
                    || !frame.get("format").equals("1")
                    || in.read() != -1) throw new IOException("不是受支持的围棋棋谱");
            try {
                GameView view = new GameView(frame.fields());
                view.replayBoards();
                return view;
            } catch (IllegalArgumentException e) {
                throw new IOException("棋谱内容无效：" + e.getMessage(), e);
            }
        }
    }
}
