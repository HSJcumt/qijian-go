package go.ai;

import go.core.Game;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.BitSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Persistent GPU process; loading, warmup and per-move search are separate operations. */
public final class KataGoEngine implements AutoCloseable {
    public record Decision(Game.Move move, boolean resign) {}
    public record Assessment(double blackLead, BitSet dead) {
        public Assessment { dead = (BitSet) dead.clone(); }
        @Override public BitSet dead() { return (BitSet) dead.clone(); }
    }

    /** Neutral adjudication, independent of the selected human-strength move preset. */
    public synchronized Assessment assess(Game game) throws IOException, InterruptedException {
        try {
            synchronizeBoard(game);
            // After two passes, AREA with friendlyPassOk=false would count unremoved dead stones.
            command("kata-set-rule friendlyPassOk true", commandTimeout);
            try {
                double lead = parseScore(command("final_score", startupTimeout));
                BitSet dead = new BitSet();
                String vertices = command("final_status_list dead", startupTimeout).strip();
                int[] board = game.board();
                if (!vertices.isEmpty()) for (String vertex : vertices.split("\\s+")) {
                    Game.Move move = parseVertex(vertex, 1, game.size());
                    if (move.pass()) throw new IOException("死子坐标无效");
                    int index = move.y() * game.size() + move.x();
                    if (board[index] == 0) throw new IOException("模型将空点标为死子");
                    dead.set(index);
                }
                return new Assessment(lead, dead);
            } finally { command("kata-set-rule friendlyPassOk false", commandTimeout); }
        } catch (IOException | InterruptedException e) { close(); throw e; }
    }

    public static double parseScore(String answer) throws IOException {
        String value = answer.strip().toUpperCase(Locale.ROOT);
        if (value.equals("0") || value.equals("DRAW") || value.equals("JIGO")) return 0;
        if (!value.matches("[BW]\\+[0-9]+(?:\\.[0-9]+)?")) throw new IOException("模型计分结果无效：" + answer);
        double margin = Double.parseDouble(value.substring(2));
        if (!Double.isFinite(margin) || margin > 1000) throw new IOException("模型分差越界");
        return value.charAt(0) == 'B' ? margin : -margin;
    }

    private static final String COLUMNS = "ABCDEFGHJKLMNOPQRST";
    private static final String EOF = "\u0000EOF";
    // First launch may tune both the main network and HumanSL on the local GPU.
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(10);
    private final List<String> launch;
    private final Path directory;
    private final Duration startupTimeout;
    private Duration commandTimeout;
    private AiProfile.Budget activeBudget;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>(4096);
    private final StringBuilder diagnostics = new StringBuilder();
    private final Object lifecycle = new Object();
    private final List<Game.Move> history = new ArrayList<>();
    private volatile Process process;
    private volatile boolean closed;
    private BufferedWriter input;
    private int sequence, size;
    private double komi;

    public KataGoEngine(
            List<String> launch, Path directory, Duration startupTimeout, Duration commandTimeout) {
        this.launch = List.copyOf(launch);
        this.directory = directory.toAbsolutePath();
        this.startupTimeout = startupTimeout;
        this.commandTimeout = commandTimeout;
    }

    public static KataGoEngine create(
            Path executable, Path model, Path config, Path home, AiProfile.Budget budget) {
        KataGoEngine engine =
                new KataGoEngine(
                        List.of(
                                executable.toAbsolutePath().toString(),
                                "gtp",
                                "-model",
                                model.toAbsolutePath().toString(),
                                "-config",
                                config.toAbsolutePath().toString(),
                                "-override-config",
                                "maxVisits=" + budget.visits + ",maxTime=" + budget.seconds),
                        home,
                        STARTUP_TIMEOUT,
                        Duration.ofSeconds(budget.seconds + 30L));
        engine.activeBudget = budget;
        return engine;
    }

    public static KataGoEngine create(
            Path executable, Path config, Path home, AiDifficulty difficulty) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                executable.toAbsolutePath().toString(),
                                "gtp",
                                "-model",
                                difficulty.model().model(home).toAbsolutePath().toString(),
                                "-config",
                                config.toAbsolutePath().toString(),
                                "-override-config",
                                difficulty.overrides()));
        if (difficulty.humanLike()) {
            args.add("-human-model");
            args.add(difficulty.humanModel(home).toAbsolutePath().toString());
        }
        KataGoEngine engine =
                new KataGoEngine(
                        args,
                        home,
                        STARTUP_TIMEOUT,
                        Duration.ofSeconds(difficulty.budget().seconds + 30L));
        engine.activeBudget = difficulty.budget();
        return engine;
    }

    public static Path home() {
        return Path.of(System.getProperty("go.katago.home", "katago")).toAbsolutePath().normalize();
    }

    public static Path executable(Path home) {
        String custom = System.getProperty("go.katago.executable");
        return custom == null
                ? home.resolve("engine")
                        .resolve(
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "katago.exe"
                                        : "katago")
                : Path.of(custom);
    }

    public static void validateFiles(Path executable, Path model, Path config) {
        if (!Files.isRegularFile(executable))
            throw new IllegalArgumentException(
                    "未找到 KataGo 引擎。请先运行“安装KataGo模型.bat”。\n" + executable);
        if (!Files.isRegularFile(model))
            throw new IllegalArgumentException("所选模型尚未安装。请先运行“安装KataGo模型.bat”。\n" + model);
        if (!Files.isRegularFile(config))
            throw new IllegalArgumentException("缺少 KataGo 配置文件：" + config);
    }

    public synchronized Decision choose(Game game) throws IOException, InterruptedException {
        return choose(game, () -> {});
    }

    public synchronized Decision choose(Game game, Runnable searchStarted)
            throws IOException, InterruptedException {
        if (game.phase() != Game.Phase.PLAYING) throw new IllegalArgumentException("当前不是行棋阶段");
        try {
            synchronizeBoard(game);
            searchStarted.run();
            // Search does not modify the engine board: the game remains authoritative.
            String answer = command("kata-search " + color(game.turn()), commandTimeout).strip();
            if (answer.equalsIgnoreCase("resign")) return new Decision(null, true);
            Game.Move move = parseVertex(answer, game.turn(), size);
            Game trial = game.copy();
            try {
                if (move.pass()) trial.pass(move.color());
                else trial.play(move.color(), move.x(), move.y());
            } catch (IllegalArgumentException e) {
                throw new IOException("KataGo 返回不合法落子：" + answer, e);
            }
            return new Decision(move, false);
        } catch (IOException | InterruptedException e) {
            close();
            throw e;
        }
    }

    public synchronized void prepare(int boardSize, double boardKomi)
            throws IOException, InterruptedException {
        try {
            synchronizeBoard(new Game(boardSize, boardKomi));
            // One NN evaluation initializes lazy GPU resources without playing a move.
            command("kata-raw-nn B 0", startupTimeout);
        } catch (IOException | InterruptedException e) {
            close();
            throw e;
        }
    }

    public synchronized void configureBudget(AiProfile.Budget budget)
            throws IOException, InterruptedException {
        if (activeBudget == budget) return;
        try {
            if (process == null) start();
            command("kata-set-param maxVisits " + budget.visits, commandTimeout);
            command("kata-set-param maxTime " + budget.seconds, commandTimeout);
            activeBudget = budget;
            commandTimeout = Duration.ofSeconds(budget.seconds + 30L);
        } catch (IOException | InterruptedException e) {
            close();
            throw e;
        }
    }

    private void synchronizeBoard(Game game) throws IOException, InterruptedException {
        if (process == null) start();
        List<Game.Move> moves = game.moves();
        boolean reset =
                size != game.size()
                        || Double.compare(komi, game.komi()) != 0
                        || moves.size() < history.size()
                        || !moves.subList(0, history.size()).equals(history);
        if (reset) {
            command("boardsize " + game.size(), startupTimeout);
            command("clear_board", commandTimeout);
            command("komi " + game.komi(), commandTimeout);
            size = game.size();
            komi = game.komi();
            history.clear();
        }
        for (int i = history.size(); i < moves.size(); i++) {
            Game.Move move = moves.get(i);
            command("play " + color(move.color()) + " " + vertex(move, size), commandTimeout);
            history.add(move);
        }
    }

    private void start() throws IOException, InterruptedException {
        synchronized (lifecycle) {
            if (closed || Thread.currentThread().isInterrupted())
                throw new InterruptedException("KataGo 已取消");
            process = new ProcessBuilder(launch).directory(directory.toFile()).start();
            input =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream(), StandardCharsets.UTF_8));
            Process owned = process;
            daemon("katago-stdout", () -> readOutput(owned));
            daemon("katago-stderr", () -> readErrors(owned));
        }
        if (!command("protocol_version", startupTimeout).strip().equals("2"))
            throw new IOException("KataGo 不支持 GTP 2");
    }

    private void readOutput(Process owned) {
        try (BufferedReader reader = owned.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!lines.offer(line)) {
                    owned.destroyForcibly();
                    break;
                }
            }
        } catch (IOException ignored) {
        } finally {
            lines.offer(EOF);
        }
    }

    private void readErrors(Process owned) {
        try (BufferedReader reader = owned.errorReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null)
                synchronized (diagnostics) {
                    diagnostics.append(line).append('\n');
                    if (diagnostics.length() > 4000)
                        diagnostics.delete(0, diagnostics.length() - 4000);
                }
        } catch (IOException ignored) {
        }
    }

    private String command(String text, Duration timeout) throws IOException, InterruptedException {
        if (closed || Thread.currentThread().isInterrupted())
            throw new InterruptedException("KataGo 已取消");
        int id = ++sequence;
        input.write(id + " " + text + "\n");
        input.flush();
        long until = System.nanoTime() + timeout.toNanos();
        String header = null;
        StringBuilder body = new StringBuilder();
        boolean success = false;
        while (true) {
            long remaining = until - System.nanoTime();
            String line = remaining <= 0 ? null : lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null) throw failure("KataGo 响应超时；首次显卡调优可能较慢，请查看 katago/logs。");
            if (line.equals(EOF)) throw failure("KataGo 已退出，请检查模型、显卡驱动和 katago/logs。");
            if (header == null) {
                if (line.isBlank()) continue;
                if (!(line.startsWith("=") || line.startsWith("?"))) throw failure("KataGo 协议响应无效");
                success = line.charAt(0) == '=';
                int end = 1;
                while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
                if (!line.substring(1, end).equals(Integer.toString(id)))
                    throw failure("KataGo 响应编号不匹配");
                header = line;
                body.append(line.substring(end).strip());
            } else if (line.isBlank()) {
                if (!success) throw failure("KataGo 拒绝命令：" + text + "\n" + body);
                return body.toString();
            } else {
                if (body.length() > 0) body.append('\n');
                body.append(line);
            }
        }
    }

    private IOException failure(String message) {
        synchronized (diagnostics) {
            return new IOException(
                    message
                            + (diagnostics.isEmpty()
                                    ? ""
                                    : "\n"
                                            + diagnostics.substring(
                                                    Math.max(0, diagnostics.length() - 1500))));
        }
    }

    public static String vertex(Game.Move move, int size) {
        if (move.pass()) return "pass";
        if (move.x() < 0 || move.x() >= size || move.y() < 0 || move.y() >= size || size > 19)
            throw new IllegalArgumentException("落子坐标越界");
        return "" + COLUMNS.charAt(move.x()) + (size - move.y());
    }

    public static Game.Move parseVertex(String vertex, int color, int size) throws IOException {
        if (vertex.equalsIgnoreCase("pass")) return new Game.Move(color, -1, -1);
        try {
            String value = vertex.toUpperCase(Locale.ROOT);
            int x = COLUMNS.indexOf(value.charAt(0)), row = Integer.parseInt(value.substring(1));
            if (x < 0
                    || x >= size
                    || row < 1
                    || row > size
                    || !value.substring(1).equals(Integer.toString(row)))
                throw new IllegalArgumentException();
            return new Game.Move(color, x, size - row);
        } catch (RuntimeException e) {
            throw new IOException("KataGo 返回无效坐标：" + vertex, e);
        }
    }

    private static String color(int color) {
        return color == 1 ? "B" : "W";
    }

    private static void daemon(String name, Runnable action) {
        Thread t = new Thread(action, name);
        t.setDaemon(true);
        t.start();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
            Process owned = process;
            if (owned != null) owned.destroyForcibly();
            lines.offer(EOF);
        }
    }
}
