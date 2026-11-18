package go.model;

import go.ai.AiDifficulty;
import go.ai.AiProfile;
import go.ai.KataGoEngine;
import go.core.Game;
import go.net.Protocol.Frame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** Server-owned, bounded serial GPU queue. KataGo remains a managed native child. */
public final class EmbeddedModelService implements AutoCloseable {
    private final Path home;
    private final Function<AiDifficulty, KataGoEngine> factory;
    private final ThreadPoolExecutor compute = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Long> cancelled = new HashMap<>();
    private final Object lifecycle = new Object();
    private boolean closed;
    private KataGoEngine engine;
    private String profile = "";
    private Job active;

    public EmbeddedModelService(Path home) {
        this(home, difficulty -> KataGoEngine.create(
                KataGoEngine.executable(home), home.resolve("gtp.cfg"), home, difficulty));
    }

    public EmbeddedModelService(Path home, Function<AiDifficulty, KataGoEngine> factory) {
        this.home = home.toAbsolutePath(); this.factory = factory;
    }

    Ticket submit(String type, Map<String, String> fields) throws IOException {
        synchronized (lifecycle) {
            if (closed) throw new IOException("服务端模型服务已停止");
            Ticket ticket = new Ticket();
            try {
                switch (type) {
                    case "MODEL_CATALOG" -> ticket.emit("MODEL_CATALOG", catalog());
                    case "MODEL_CANCEL" -> {
                        String id = fields.getOrDefault("jobId", "");
                        long now = System.nanoTime();
                        cancelled.entrySet().removeIf(e -> now - e.getValue() > TimeUnit.MINUTES.toNanos(10));
                        if (cancelled.size() < 4096) cancelled.put(id, now);
                        Job job = jobs.get(id);
                        if (job != null) cancel(job);
                        ticket.emit("MODEL_CANCELLED", Map.of());
                    }
                    case "MODEL_PREPARE", "MODEL_MOVE", "MODEL_SCORE" -> {
                        String id = fields.getOrDefault("jobId", "");
                        if (!id.matches("[A-Za-z0-9-]{1,80}")) throw new IllegalArgumentException("无效任务编号");
                        if (cancelled.containsKey(id)) throw new IllegalArgumentException("模型请求已取消");
                        Game game = restore(fields);
                        if (!"katago".equals(fields.get("aiMode"))) throw new IllegalArgumentException("请选择模型难度");
                        AiDifficulty difficulty = AiDifficulty.from(fields);
                        if (!difficulty.installed(home)) throw new IllegalArgumentException("所选难度的模型尚未安装");
                        Job job = new Job(id, fields, game, difficulty, type.equals("MODEL_PREPARE"), ticket);
                        job.scoring = type.equals("MODEL_SCORE");
                        if (jobs.putIfAbsent(id, job) != null) throw new IllegalArgumentException("重复任务编号");
                        ticket.job = job;
                        try { compute.execute(job); }
                        catch (RejectedExecutionException e) {
                            jobs.remove(id, job);
                            throw new IllegalArgumentException("服务端模型队列已满，请稍后重试");
                        }
                    }
                    default -> throw new IllegalArgumentException("未知模型请求");
                }
            } catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
            return ticket;
        }
    }

    private Map<String, String> catalog() {
        Map<String, String> fields = new HashMap<>();
        fields.put("reachable", "true"); fields.put("HEURISTIC", "false");
        for (AiProfile p : AiProfile.values()) fields.put(p.name(), Boolean.toString(
                Files.isRegularFile(KataGoEngine.executable(home)) && Files.isRegularFile(p.model(home))
                        && Files.isRegularFile(home.resolve("gtp.cfg"))));
        for (AiDifficulty d : AiDifficulty.values()) fields.put(d.name(), Boolean.toString(d.installed(home)));
        return fields;
    }

    private void cancel(Job job) {
        synchronized (lifecycle) {
            job.cancelled.set(true);
            if (active == job && engine != null) engine.close();
            if (compute.remove(job)) jobs.remove(job.id, job);
            job.ticket.responses.clear();
            job.ticket.emit("MODEL_ERROR", Map.of("message", "模型请求已取消"));
        }
    }

    public final class Ticket implements AutoCloseable {
        private final ArrayBlockingQueue<Frame> responses = new ArrayBlockingQueue<>(4);
        private volatile Job job;
        private boolean finished;
        private void emit(String type, Map<String, String> fields) {
            if (!responses.offer(new Frame(type, 1, fields))) throw new IllegalStateException("模型响应队列已满");
        }
        Frame await(int timeout, Consumer<Frame> progress) throws IOException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            try {
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    Frame response = remaining > 0 ? responses.poll(remaining, TimeUnit.NANOSECONDS) : null;
                    if (response == null) throw new IOException("模型请求超时");
                    if (response.type().equals("MODEL_PROGRESS")) { progress.accept(response); continue; }
                    finished = true;
                    if (response.type().equals("MODEL_ERROR")) throw new IOException(response.get("message"));
                    return response;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IOException("模型请求已取消", e);
            }
        }
        @Override public void close() { if (!finished && job != null) cancel(job); }
    }

    private final class Job implements Runnable {
        final String id, gameId;
        final long revision;
        final Game game;
        final AiDifficulty difficulty;
        final boolean prepare;
        boolean scoring;
        final Ticket ticket;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long queuedAt = System.nanoTime();
        Job(String id, Map<String, String> fields, Game game, AiDifficulty difficulty, boolean prepare, Ticket ticket) {
            this.id = id; this.gameId = fields.getOrDefault("gameId", "");
            revision = Long.parseLong(fields.getOrDefault("revision", ""));
            this.game = game; this.difficulty = difficulty; this.prepare = prepare; this.ticket = ticket;
        }
        Map<String, String> identity() { return Map.of("jobId", id, "gameId", gameId, "revision", Long.toString(revision)); }
        @Override public void run() {
            try {
                KataGoEngine current;
                synchronized (lifecycle) {
                    if (closed || cancelled.get() || System.nanoTime() - queuedAt > TimeUnit.SECONDS.toNanos(60))
                        throw new IOException("模型请求已取消或排队超时");
                    if (engine == null || engine.isClosed() || !profile.equals(difficulty.name())) {
                        if (engine != null) engine.close();
                        engine = factory.apply(difficulty); profile = difficulty.name();
                    }
                    active = this; current = engine;
                }
                current.configureBudget(scoring ? AiProfile.Budget.DEEP : difficulty.budget());
                if (scoring) {
                    KataGoEngine.Assessment assessment = current.assess(game);
                    if (!cancelled.get()) {
                        Map<String, String> fields = new HashMap<>(identity());
                        fields.put("blackLead", Double.toString(assessment.blackLead()));
                        fields.put("dead", java.util.Base64.getEncoder().encodeToString(assessment.dead().toByteArray()));
                        ticket.emit("MODEL_SCORE", fields);
                    }
                } else if (prepare) {
                    current.prepare(game.size(), game.komi());
                    if (!cancelled.get()) ticket.emit("MODEL_READY", identity());
                } else {
                    KataGoEngine.Decision decision = current.choose(game, () -> {
                        if (!cancelled.get()) {
                            Map<String, String> fields = new HashMap<>(identity()); fields.put("stage", "thinking");
                            ticket.emit("MODEL_PROGRESS", fields);
                        }
                    });
                    if (!cancelled.get()) {
                        Map<String, String> fields = new HashMap<>(identity());
                        fields.put("resign", Boolean.toString(decision.resign()));
                        if (!decision.resign()) { fields.put("x", "" + decision.move().x()); fields.put("y", "" + decision.move().y()); }
                        ticket.emit("MODEL_MOVE", fields);
                    }
                }
            } catch (Exception e) {
                if (!cancelled.get()) {
                    System.err.println("Model job failed: " + id + " " + e.getMessage());
                    ticket.emit("MODEL_ERROR", Map.of("message", "模型计算失败，请检查服务端日志后重试"));
                }
            } finally {
                synchronized (lifecycle) { if (active == this) active = null; jobs.remove(id, this); }
            }
        }
    }

    public static Game restore(Map<String, String> fields) {
        int size = Integer.parseInt(fields.getOrDefault("size", ""));
        if (size != 9 && size != 13 && size != 19) throw new IllegalArgumentException("只支持9／13／19路");
        Game game = new Game(size, Double.parseDouble(fields.getOrDefault("komi", "")));
        String history = fields.getOrDefault("moves", "");
        if (!history.isEmpty()) for (String entry : history.split(";")) {
            String[] parts = entry.split(",", -1);
            if (parts.length != 3) throw new IllegalArgumentException("棋谱格式无效");
            int color = Integer.parseInt(parts[0]), x = Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]);
            if (game.phase() == Game.Phase.SCORING) game.resume(color);
            if (x == -1 && y == -1) game.pass(color); else game.play(color, x, y);
        }
        if (game.phase() == Game.Phase.SCORING) game.resume(game.turn());
        if (game.turn() != Integer.parseInt(fields.getOrDefault("turn", ""))) throw new IllegalArgumentException("棋谱行棋方不匹配");
        return game;
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return; closed = true;
            for (Job job : jobs.values()) cancel(job);
            if (engine != null) engine.close();
            compute.shutdownNow(); jobs.clear();
        }
    }
}
