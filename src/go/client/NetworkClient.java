package go.client;

import go.net.Protocol;
import go.net.Protocol.Frame;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Background networking; callers marshal callbacks onto Swing's event dispatch thread. */
public final class NetworkClient implements AutoCloseable {
    private final Socket socket = new Socket();
    private final BlockingQueue<Frame> outgoing = new ArrayBlockingQueue<>(128);
    private final ExecutorService io =
            Executors.newFixedThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "go-client-io");
                        t.setDaemon(true);
                        return t;
                    });
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "go-client-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<Frame> receive;
    private final Consumer<String> lost;

    public NetworkClient(String host, int port, Consumer<Frame> receive, Consumer<String> lost)
            throws IOException {
        this.receive = receive;
        this.lost = lost;
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(20000);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    public void start(String nickname, String token, String profileId, List<String> aliases) {
        io.execute(this::writeLoop);
        io.execute(this::readLoop);
        send(
                "HELLO",
                Map.of(
                        "nickname",
                        nickname,
                        "token",
                        token,
                        "profileId",
                        profileId,
                        "aliases",
                        String.join("\n", aliases)));
        timer.scheduleAtFixedRate(
                () -> {
                    if (!closed.get() && !outgoing.offer(new Frame("PING", Map.of())))
                        fail("发送队列已满");
                },
                5,
                5,
                TimeUnit.SECONDS);
    }

    public long send(String type, Map<String, String> fields) {
        if (closed.get()) throw new IllegalStateException("连接已断开，请重新连接");
        long request = sequence.incrementAndGet();
        if (!outgoing.offer(new Frame(type, request, fields))) {
            fail("发送队列已满");
            throw new IllegalStateException("连接繁忙，请重新连接");
        }
        return request;
    }

    private void readLoop() {
        try {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            while (!closed.get()) receive.accept(Protocol.read(input));
        } catch (IOException | RuntimeException e) {
            if (!closed.get()) fail("连接中断，可在 60 秒内重新连接恢复对局");
        }
    }

    private void writeLoop() {
        try {
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            while (!closed.get()) {
                Frame frame = outgoing.poll(1, TimeUnit.SECONDS);
                if (frame != null) Protocol.write(output, frame);
            }
        } catch (IOException e) {
            if (!closed.get()) fail("发送失败，请重新连接");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fail(String message) {
        if (closed.compareAndSet(false, true)) {
            cleanup();
            lost.accept(message);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) cleanup();
    }

    private void cleanup() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        timer.shutdownNow();
        io.shutdownNow();
    }
}
