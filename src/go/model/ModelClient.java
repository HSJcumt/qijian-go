package go.model;

import go.net.Protocol.Frame;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** In-process adapter. No model sockets, credentials or remote worker. */
public final class ModelClient {
    private final EmbeddedModelService service;

    public ModelClient(EmbeddedModelService service) { this.service = service; }

    public Call call(String type, Map<String, String> fields, int timeoutMillis) {
        return new Call(type, fields, timeoutMillis);
    }

    public Frame request(String type, Map<String, String> fields, int timeoutMillis) throws IOException {
        try (Call call = call(type, fields, timeoutMillis)) { return call.execute(); }
    }

    public final class Call implements AutoCloseable {
        private final String type;
        private final Map<String, String> fields;
        private final int timeout;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile EmbeddedModelService.Ticket ticket;

        private Call(String type, Map<String, String> fields, int timeout) {
            this.type = type; this.fields = Map.copyOf(fields); this.timeout = timeout;
        }

        public Frame execute() throws IOException { return execute(frame -> {}); }

        public Frame execute(Consumer<Frame> progress) throws IOException {
            if (closed.get()) throw new IOException("模型请求已取消");
            EmbeddedModelService.Ticket current = service.submit(type, fields);
            ticket = current;
            try (current) {
                if (closed.get()) throw new IOException("模型请求已取消");
                return current.await(timeout, progress);
            }
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true) && ticket != null) ticket.close();
        }
    }
}
