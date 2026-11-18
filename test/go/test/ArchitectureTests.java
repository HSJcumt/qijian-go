package go.test;

import go.core.Game;
import go.model.ModelClient;
import go.net.Protocol;
import go.net.Protocol.Frame;
import go.server.GameServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class ArchitectureTests {
    static Map<String, String> ai(String mode, int color) {
        return Map.of("title", "双端架构测试", "size", "19", "komi", "7.5",
                "color", Integer.toString(color), "aiMode", mode,
                "aiDifficulty", "AMATEUR_LOW");
    }
    static final class Peer implements AutoCloseable {
        final Socket socket;
        final BlockingQueue<Frame> incoming = new LinkedBlockingQueue<>();
        long sequence;

        Peer(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(380000);
            Thread reader =
                    new Thread(
                            () -> {
                                try {
                                    while (!socket.isClosed())
                                        incoming.add(Protocol.read(socket.getInputStream()));
                                } catch (IOException ignored) {
                                }
                            });
            reader.setDaemon(true);
            reader.start();
        }

        Frame hello(String name) throws Exception {
            send("HELLO", Map.of("nickname", name));
            return await(f -> f.type().equals("WELCOME"));
        }

        synchronized void send(String type, Map<String, String> fields) throws Exception {
            Protocol.write(socket.getOutputStream(), new Frame(type, ++sequence, fields));
        }

        void action(String type, long revision, Map<String, String> extra) throws Exception {
            Map<String, String> fields = new HashMap<>(extra);
            fields.put("revision", "" + revision);
            send(type, fields);
        }

        Frame state(Predicate<Frame> match) throws Exception {
            return await(f -> f.type().equals("STATE") && match.test(f));
        }

        Frame await(Predicate<Frame> match) throws Exception {
            return await(match, 6000);
        }

        Frame await(Predicate<Frame> match, long timeout) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            Frame last = null;
            while (System.nanoTime() < deadline) {
                Frame f = incoming.poll(100, TimeUnit.MILLISECONDS);
                if (f != null) {
                    last = f;
                    if (match.test(f)) return f;
                }
            }
            throw new AssertionError(
                    "Timed out; last="
                            + (last == null ? "none" : last.type() + " " + last.fields()));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

}
