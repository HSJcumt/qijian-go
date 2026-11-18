package go.server;

import go.ai.AiDifficulty;
import go.core.Game;
import go.core.GameView;
import go.core.RecordStore;
import go.model.ModelClient;
import go.model.EmbeddedModelService;
import go.model.ServiceConfig;
import go.net.Protocol;
import go.net.Protocol.Frame;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Socket I/O is concurrent; all membership and game mutations run on one ordered dispatcher. */
public final class GameServer implements AutoCloseable {
    private final ServerSocket listener;
    private final Path records;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor();
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Set<Session> connections = ConcurrentHashMap.newKeySet();
    private final Map<String, Player> players = new HashMap<>();
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int roomSequence = 1000;
    private final long reconnectNanos;
    private final long reconnectSeconds;
    private final ModelClient models;
    private final ThreadPoolExecutor modelRequests =
            new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
    private Map<String, String> modelCatalog = Map.of("reachable", "false", "HEURISTIC", "false");
    private boolean catalogLoading;
    private long catalogChecked;

    public GameServer(String bind, int port, Path records) throws IOException {
        this(bind, port, records, Duration.ofSeconds(60));
    }

    public GameServer(String bind, int port, Path records, Duration reconnectGrace)
            throws IOException {
        this(bind, port, records, reconnectGrace, null);
    }

    public GameServer(
            String bind, int port, Path records, Duration reconnectGrace, ModelClient models)
            throws IOException {
        if (reconnectGrace.compareTo(Duration.ofSeconds(1)) < 0
                || reconnectGrace.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Reconnect grace must be between 1 and 300 seconds");
        reconnectNanos = reconnectGrace.toNanos();
        reconnectSeconds = reconnectGrace.toSeconds();
        this.records = records;
        this.models = models;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(bind, port), 100);
    }

    public int port() {
        return listener.getLocalPort();
    }

    public void start() {
        io.execute(this::acceptLoop);
        timer.scheduleAtFixedRate(() -> dispatch(this::maintenance), 1, 1, TimeUnit.SECONDS);
        System.out.println("GO server listening on " + listener.getLocalSocketAddress());
    }

    private void dispatch(Runnable task) {
        if (!closed.get())
            try {
                dispatcher.execute(task);
            } catch (RejectedExecutionException ignored) {
            }
    }

    private void acceptLoop() {
        while (!closed.get())
            try {
                Socket socket = listener.accept();
                if (connections.size() >= 128) {
                    socket.close();
                    continue;
                }
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(35000);
                Session session = new Session(socket);
                connections.add(session);
                io.execute(session::writeLoop);
                io.execute(session::readLoop);
            } catch (IOException e) {
                if (!closed.get()) System.err.println("Accept failed: " + e.getMessage());
            }
    }

    private void handle(Session session, Frame message) {
        if (session.closed.get()) return;
        try {
            if (message.type().equals("PING")) {
                session.send(new Frame("PONG", message.requestId(), Map.of()));
                return;
            }
            if (message.requestId() <= session.lastRequest)
                throw new IllegalArgumentException("请求已处理或编号无效");
            session.lastRequest = message.requestId();
            if (message.type().equals("HELLO")) {
                hello(session, message);
                return;
            }
            Player player = session.player;
            if (player == null) throw new IllegalArgumentException("请先进入大厅");
            switch (message.type()) {
                case "LIST_ROOMS" -> sendRooms(session);
                case "INVITE_PLAYER" -> invite(player, message);
                case "AI_CATALOG" -> refreshCatalog(session);
                case "UPDATE_PROFILE" -> updateProfile(player, message);
                case "PLAYER_HISTORY" -> history(session);
                case "CREATE_ROOM" -> create(player, message);
                case "CREATE_AI_ROOM" -> createAI(player, message);
                case "AI_RETRY" -> retryAI(player, message);
                case "JOIN_ROOM" -> join(player, message.get("roomId"), false);
                case "WATCH_ROOM" -> join(player, message.get("roomId"), true);
                case "LEAVE_ROOM" -> {
                    leave(player, true);
                    session.send(new Frame("LEFT", Map.of()));
                }
                case "SYNC" -> {
                    if (player.room != null) sendState(player.room, player);
                }
                case "READY" -> ready(player);
                case "REQUEST_UNDO", "RESPOND_UNDO", "CANCEL_UNDO" -> undo(player, message);
                case "MOVE",
                                "PASS",
                                "RESIGN",
                                "MARK_DEAD",
                                "CONFIRM_SCORE",
                                "RESUME_PLAY",
                                "REQUEST_SCORE",
                                "RESPOND_SCORE",
                                "CANCEL_SCORE",
                                "ANALYZE_SCORE", "END_AI_GAME" ->
                        action(player, message);
                default -> throw new IllegalArgumentException("未知消息类型");
            }
        } catch (IllegalArgumentException e) {
            session.send(
                    new Frame(
                            "ERROR",
                            message.requestId(),
                            Map.of("message", e.getMessage() == null ? "请求参数无效" : e.getMessage())));
            if (session.player != null && session.player.room != null)
                sendState(session.player.room, session.player);
        } catch (RuntimeException e) {
            System.err.println("Request failed: " + e);
            session.send(
                    new Frame("ERROR", message.requestId(), Map.of("message", "服务器处理异常，请重新同步")));
        }
    }

    private void hello(Session session, Frame message) {
        if (session.player != null) throw new IllegalArgumentException("已经进入大厅");
        String profileId = message.get("profileId");
        if (!profileId.isEmpty()) UUID.fromString(profileId);
        Set<String> aliases = new LinkedHashSet<>();
        if (message.get("aliases").length() > 2048) throw new IllegalArgumentException("曾用昵称数量过多");
        for (String alias : message.get("aliases").split("\n"))
            if (!alias.isEmpty()) aliases.add(clean(alias, 16, "曾用昵称"));
        String token = message.get("token");
        Player player;
        if (!token.isEmpty()) {
            player = players.get(token);
            if (player == null || player.session != null)
                throw new IllegalArgumentException("重连凭证已失效或该用户仍在线，请重新连接");
            if (player.room != null
                    && player.role != 0
                    && System.nanoTime() - player.disconnectedAt >= reconnectNanos) {
                leave(player, false);
                players.remove(token);
                throw new IllegalArgumentException("重连时间已过，请重新连接");
            }
        } else {
            String name = clean(message.get("nickname"), 16, "昵称");
            if (players.values().stream().anyMatch(p -> p.name.equals(name)))
                throw new IllegalArgumentException("该昵称正在使用，请换一个昵称");
            if (players.size() >= 256) throw new IllegalArgumentException("服务器用户数量已满");
            player = new Player(name);
            players.put(player.token, player);
        }
        if (!profileId.isEmpty()) {
            if (!player.profileId.isEmpty() && !player.profileId.equals(profileId))
                throw new IllegalArgumentException("重连玩家资料不匹配");
            player.profileId = profileId;
        }
        player.aliases.addAll(aliases);
        player.aliases.add(player.name);
        session.player = player;
        player.session = session;
        session.send(
                new Frame(
                        "WELCOME",
                        Map.of(
                                "token",
                                player.token,
                                "nickname",
                                player.name,
                                "profileEditing",
                                "true",
                                "playerHistory",
                                "true",
                                "undo",
                                "true")));
        sendRooms(session);
        refreshCatalog(session);
        if (player.room != null) {
            scheduleAI(player.room);
            broadcast(player.room);
        }
        broadcastRooms();
    }

    private void updateProfile(Player player, Frame message) {
        String name = clean(message.get("nickname"), 16, "昵称");
        if (players.values().stream().anyMatch(p -> p != player && p.name.equals(name)))
            throw new IllegalArgumentException("该昵称正在使用，请换一个昵称");
        player.aliases.add(player.name);
        player.aliases.add(name);
        player.name = name;
        if (player.room != null && player.room.game != null) {
            if (player.room.black == player) player.room.blackName = name;
            if (player.room.white == player) player.room.whiteName = name;
        }
        player.session.send(
                new Frame("PROFILE_UPDATED", message.requestId(), Map.of("nickname", name)));
        if (player.room != null) broadcast(player.room);
        broadcastRooms();
    }

    /** Disk work stays off the game dispatcher; historical records remain the source of results. */
    private void history(Session session) {
        if (session.historyPending) return;
        session.historyPending = true;
        String id = session.player.profileId;
        Set<String> aliases = Set.copyOf(session.player.aliases);
        disk.execute(
                () -> {
                    List<String> rows = new ArrayList<>();
                    try {
                        if (Files.isDirectory(records))
                            try (var paths = Files.list(records)) {
                                for (Path path :
                                        paths.filter(
                                                        p ->
                                                                p.getFileName()
                                                                        .toString()
                                                                        .endsWith(".gogame"))
                                                .toList()) {
                                    try {
                                        GameView game = RecordStore.load(path);
                                        if (!game.phase.equals("FINISHED")
                                                || game.text("gameId").isEmpty()) continue;
                                        int role =
                                                owns(game, "black", id, aliases)
                                                        ? 1
                                                        : owns(game, "white", id, aliases) ? 2 : 0;
                                        if (role == 0) continue;
                                        int winner = game.integer("winner");
                                        rows.add(
                                                game.text("gameId")
                                                        + "\t"
                                                        + !game.text("aiMode").isEmpty()
                                                        + "\t"
                                                        + (winner == 0
                                                                ? 0
                                                                : winner == role ? 1 : 2));
                                    } catch (IOException | IllegalArgumentException ignored) {
                                        /* One damaged record cannot hide valid results. */
                                    }
                                }
                            }
                        dispatch(
                                () -> {
                                    session.historyPending = false;
                                    for (int i = 0; i < rows.size(); i += 200)
                                        session.send(
                                                new Frame(
                                                        "PLAYER_HISTORY",
                                                        Map.of(
                                                                "results",
                                                                String.join(
                                                                        "\n",
                                                                        rows.subList(
                                                                                i,
                                                                                Math.min(
                                                                                        rows.size(),
                                                                                        i
                                                                                                + 200))))));
                                    session.send(
                                            new Frame(
                                                    "PLAYER_HISTORY",
                                                    Map.of("results", "", "complete", "true")));
                                });
                    } catch (IOException e) {
                        dispatch(
                                () -> {
                                    session.historyPending = false;
                                    session.send(
                                            new Frame(
                                                    "HISTORY_ERROR",
                                                    Map.of("message", "历史战绩暂时无法读取，请稍后重试")));
                                });
                    }
                });
    }

    private static boolean owns(GameView game, String color, String id, Set<String> aliases) {
        String recorded = game.text(color + "ProfileId");
        return recorded.isEmpty()
                ? aliases.contains(game.text(color))
                : !id.isEmpty() && recorded.equals(id);
    }

    private void invite(Player player, Frame message) {
        Player target =
                players.values().stream()
                        .filter(
                                p ->
                                        p.name.equals(message.get("nickname"))
                                                && p != player
                                                && online(p))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("该玩家已离线"));
        if (target.room != null) throw new IllegalArgumentException("该玩家正在房间中");
        if (player.room != null) throw new IllegalArgumentException("请先退出当前房间");
        create(
                player,
                new Frame("CREATE_ROOM", Map.of("title", player.name + "的棋室", "size", "19")));
        target.session.send(
                new Frame("INVITATION", Map.of("from", player.name, "roomId", player.room.id)));
    }

    private void create(Player player, Frame message) {
        if (player.room != null) throw new IllegalArgumentException("请先退出当前房间");
        if (rooms.size() >= 64) throw new IllegalArgumentException("房间数量已满");
        int size = message.integer("size");
        if (size != 9 && size != 13 && size != 19)
            throw new IllegalArgumentException("只支持 9、13、19 路棋盘");
        Room room = new Room("" + ++roomSequence, clean(message.get("title"), 24, "房间名称"), size);
        rooms.put(room.id, room);
        room.black = player;
        player.role = 1;
        player.room = room;
        broadcast(room);
        broadcastRooms();
    }

    private void refreshCatalog(Session session) {
        if (session != null) session.send(new Frame("AI_CATALOG", modelCatalog));
        if (models == null || catalogLoading) return;
        catalogLoading = true;
        catalogChecked = System.nanoTime();
        io.execute(
                () -> {
                    Map<String, String> result;
                    try {
                        Frame response = models.request("MODEL_CATALOG", Map.of(), 3000);
                        if (!response.type().equals("MODEL_CATALOG"))
                            throw new IOException("Invalid catalog response");
                        Map<String, String> filtered = new HashMap<>();
                        for (String field :
                                List.of("reachable", "HEURISTIC", "SMALL", "MEDIUM", "LARGE"))
                            filtered.put(
                                    field,
                                    Boolean.toString(Boolean.parseBoolean(response.get(field))));
                        for (AiDifficulty difficulty : AiDifficulty.values())
                            filtered.put(
                                    difficulty.name(),
                                    Boolean.toString(
                                            Boolean.parseBoolean(response.get(difficulty.name()))));
                        result = Map.copyOf(filtered);
                    } catch (IOException e) {
                        result = Map.of("reachable", "false", "HEURISTIC", "false");
                    }
                    Map<String, String> updated = result;
                    dispatch(
                            () -> {
                                catalogLoading = false;
                                modelCatalog = updated;
                                for (Player p : players.values())
                                    if (p.session != null)
                                        p.session.send(new Frame("AI_CATALOG", modelCatalog));
                            });
                });
    }

    private void createAI(Player player, Frame message) {
        if (player.room != null) throw new IllegalArgumentException("请先退出当前房间");
        if (rooms.size() >= 64) throw new IllegalArgumentException("房间数量已满");
        String mode = message.get("aiMode");
        if (!mode.equals("katago")) throw new IllegalArgumentException("启发式对手已移除，请选择模型难度");
        AiDifficulty difficulty = AiDifficulty.from(message.fields());
        if (models == null
                || !Boolean.parseBoolean(modelCatalog.getOrDefault(difficulty.name(), "false")))
            throw new IllegalArgumentException("所选 AI 尚未就绪，请刷新模型列表或联系服务器管理员");
        int size = message.integer("size"), color = message.integer("color");
        if ((size != 9 && size != 13 && size != 19) || (color != 1 && color != 2))
            throw new IllegalArgumentException("棋盘或执棋方无效");
        Game game = new Game(size);
        Room room = new Room("" + ++roomSequence, clean(message.get("title"), 24, "房间名称"), size);
        room.aiMode = mode;
        room.aiDifficulty = difficulty;
        room.aiReady = false;
        Player bot = new Player(difficulty.toString(), true);
        player.role = color;
        player.room = room;
        player.ready = true;
        bot.role = 3 - color;
        bot.room = room;
        bot.ready = true;
        room.black = color == 1 ? player : bot;
        room.white = color == 2 ? player : bot;
        room.blackName = room.black.name;
        room.whiteName = room.white.name;
        room.game = game;
        room.blackProfileId = room.black.profileId;
        room.whiteProfileId = room.white.profileId;
        rooms.put(room.id, room);
        scheduleAI(room);
        broadcast(room);
        broadcastRooms();
    }

    private boolean paused(Room room) {
        return room.game != null
                && room.game.phase() != Game.Phase.FINISHED
                && (!online(room.black) || !online(room.white));
    }

    private boolean botTurn(Room room) {
        if (room.game == null) return false;
        Player current = room.game.turn() == 1 ? room.black : room.white;
        return current != null && current.bot;
    }

    private void retryAI(Player player, Frame message) {
        Room room = requireRoom(player);
        if (player.role == 0
                || room.aiMode.isEmpty()
                || (room.aiReady && !botTurn(room))
                || room.aiError.isEmpty()) throw new IllegalArgumentException("当前无需重试 AI");
        if (message.number("revision") != room.game.revision())
            throw new IllegalArgumentException("棋局已更新");
        scheduleAI(room);
        broadcast(room);
    }

    private void scheduleAI(Room room) {
        if (models == null
                || room.aiMode.isEmpty()
                || room.game == null
                || room.game.phase() != Game.Phase.PLAYING
                || paused(room)
                || !room.aiJob.isEmpty()) return;
        boolean preparing = !room.aiReady;
        if (!preparing && !botTurn(room)) return;
        String jobId = UUID.randomUUID().toString();
        long revision = room.game.revision();
        Map<String, String> request = new HashMap<>(room.game.snapshot());
        request.put("jobId", jobId);
        request.put("gameId", room.gameId);
        request.put("aiMode", room.aiMode);
        request.put("aiModel", room.aiDifficulty.model().name());
        request.put("aiBudget", room.aiDifficulty.budget().name());
        request.put("aiDifficulty", room.aiDifficulty.name());
        ModelClient.Call call =
                // Allow first-run GPU tuning plus queue time; cancellation still stops promptly.
                models.call(preparing ? "MODEL_PREPARE" : "MODEL_MOVE", request, 720000);
        room.aiJob = jobId;
        room.aiCall = call;
        room.aiError = "";
        room.aiStage = preparing ? "loading" : "queued";
        room.aiThinkStarted = 0;
        try {
            room.aiTask =
                    modelRequests.submit(() -> executeAI(room, jobId, revision, preparing, call));
        } catch (RejectedExecutionException e) {
            call.close();
            room.aiJob = "";
            room.aiCall = null;
            room.aiStage = "";
            room.aiError = "AI 请求过多，请稍后重试。";
        }
    }

    /** Runs off the dispatcher; only queued callbacks may touch room state. */
    private void executeAI(
            Room room, String jobId, long revision, boolean preparing, ModelClient.Call call) {
        Frame result = null;
        String failure = "";
        try (call) {
            result =
                    call.execute(
                            progress ->
                                    dispatch(
                                            () ->
                                                    updateAIProgress(
                                                            room, jobId, revision, preparing,
                                                            progress)));
        } catch (IOException e) {
            failure = "服务端模型暂不可用或计算失败，请重试。";
        }
        Frame answer = result;
        String error = failure;
        dispatch(() -> completeAI(room, jobId, revision, preparing, answer, error));
    }

    private void updateAIProgress(
            Room room, String jobId, long revision, boolean preparing, Frame progress) {
        if (preparing
                || !room.aiJob.equals(jobId)
                || room.game.revision() != revision
                || !progress.get("jobId").equals(jobId)
                || !progress.get("gameId").equals(room.gameId)
                || !progress.get("stage").equals("thinking")
                || room.aiThinkStarted != 0) {
            return;
        }
        room.aiStage = "thinking";
        room.aiThinkStarted = System.nanoTime();
        broadcast(room);
    }

    /** Recheck ownership and revision after asynchronous work before applying any result. */
    private void completeAI(
            Room room, String jobId, long revision, boolean preparing, Frame answer, String error) {
        if (!room.aiJob.equals(jobId)) return;
        room.aiJob = "";
        room.aiCall = null;
        room.aiTask = null;
        room.aiStage = "";
        room.aiThinkStarted = 0;
        if (rooms.get(room.id) != room
                || room.game == null
                || room.game.revision() != revision
                || room.game.phase() != Game.Phase.PLAYING
                || paused(room)
                || (!preparing && !botTurn(room))) {
            return;
        }
        try {
            if (!error.isEmpty()) throw new IllegalArgumentException(error);
            if (answer == null
                    || !answer.type().equals(preparing ? "MODEL_READY" : "MODEL_MOVE")
                    || !answer.get("jobId").equals(jobId)
                    || !answer.get("gameId").equals(room.gameId)
                    || answer.number("revision") != revision) {
                throw new IllegalArgumentException("服务端模型响应与当前棋局不匹配，请重试。");
            }
            if (preparing) {
                room.aiReady = true;
                scheduleAI(room);
            } else {
                applyAIMove(room.game, answer);
            }
        } catch (IllegalArgumentException e) {
            room.aiError = e.getMessage();
        }
        broadcast(room);
        archive(room);
        broadcastRooms();
    }

    private void applyAIMove(Game game, Frame answer) {
        int color = game.turn();
        if (answer.get("resign").equals("true")) {
            game.resign(color, "认输（AI）");
            return;
        }
        int x = answer.integer("x"), y = answer.integer("y");
        if (x == -1 && y == -1) game.pass(color);
        else game.play(color, x, y);
    }

    private void cancelAI(Room room) {
        room.scoreBusy = false;
        String job = room.aiJob;
        room.aiJob = "";
        room.aiStage = "";
        room.aiThinkStarted = 0;
        if (room.aiCall != null) {
            room.aiCall.close();
            room.aiCall = null;
        }
        if (room.aiTask != null) {
            room.aiTask.cancel(true);
            room.aiTask = null;
        }
        if (!job.isEmpty() && models != null && !io.isShutdown())
            io.execute(
                    () -> {
                        try {
                            models.request("MODEL_CANCEL", Map.of("jobId", job), 3000);
                        } catch (IOException ignored) {
                        }
                    });
    }

    private void join(Player player, String id, boolean watch) {
        if (player.room != null) throw new IllegalArgumentException("请先退出当前房间");
        Room room = rooms.get(id);
        if (room == null) throw new IllegalArgumentException("房间已关闭");
        if (watch) {
            if (room.watchers.size() >= 40) throw new IllegalArgumentException("观战人数已满");
            room.watchers.add(player);
            player.role = 0;
        } else {
            if (room.game != null) throw new IllegalArgumentException("本局已经开始，请选择观战");
            if (room.black == null) {
                room.black = player;
                player.role = 1;
            } else if (room.white == null) {
                room.white = player;
                player.role = 2;
            } else throw new IllegalArgumentException("棋手席位已满，请选择观战");
        }
        player.room = room;
        player.ready = false;
        broadcast(room);
        broadcastRooms();
    }

    private void ready(Player player) {
        Room room = requireRoom(player);
        if (player.role == 0 || room.game != null) throw new IllegalArgumentException("当前不能准备");
        player.ready = !player.ready;
        if (room.black != null
                && room.white != null
                && room.black.ready
                && room.white.ready
                && online(room.black)
                && online(room.white)) {
            room.game = new Game(room.size);
            room.blackName = room.black.name;
            room.whiteName = room.white.name;
            room.blackProfileId = room.black.profileId;
            room.whiteProfileId = room.white.profileId;
        }
        broadcast(room);
        broadcastRooms();
    }

    private void clearUndo(Room room, String message) {
        room.undoRequestedBy = 0;
        room.undoRequestId = "";
        room.undoDeadline = 0;
        room.undoMessage = message;
    }

    private void undo(Player player, Frame message) {
        Room room = requireRoom(player);
        Game game = room.game;
        if (player.role == 0) throw new IllegalArgumentException("观战者不能悔棋");
        if (game == null || game.phase() == Game.Phase.FINISHED)
            throw new IllegalArgumentException("当前不能悔棋");
        if (!room.aiMode.isEmpty() && !room.aiReady) throw new IllegalArgumentException("模型尚未准备完成");
        if (paused(room)) throw new IllegalArgumentException("棋手掉线，重连后才能悔棋");
        if (message.number("revision") != game.revision())
            throw new IllegalArgumentException("棋局已更新，请重新操作");
        if (room.scoreRequestedBy != 0) throw new IllegalArgumentException("请先处理结算申请");
        if (room.undoRequestedBy != 0 && System.nanoTime() >= room.undoDeadline) {
            clearUndo(room, "悔棋请求已超时");
            broadcast(room);
        }
        if (message.type().equals("REQUEST_UNDO")) {
            if (room.undoRequestedBy != 0) throw new IllegalArgumentException("请先处理当前悔棋请求");
            if (!game.canUndo(player.role)) throw new IllegalArgumentException("还没有可撤回的己方落子");
            if (!room.aiMode.isEmpty()) {
                cancelAI(room);
                game.undo(player.role);
                room.aiError = "";
                clearUndo(room, "已撤回到你上次落子前");
            } else {
                room.undoRequestedBy = player.role;
                room.undoRequestId = UUID.randomUUID().toString();
                room.undoDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                room.undoMessage = "";
            }
        } else {
            if (room.undoRequestedBy == 0
                    || !room.undoRequestId.equals(message.get("undoRequestId")))
                throw new IllegalArgumentException("悔棋请求已失效");
            if (message.type().equals("CANCEL_UNDO")) {
                if (player.role != room.undoRequestedBy)
                    throw new IllegalArgumentException("只能取消自己的悔棋请求");
                clearUndo(room, "悔棋请求已取消");
            } else {
                if (player.role == room.undoRequestedBy)
                    throw new IllegalArgumentException("不能批准自己的悔棋请求");
                if (!Set.of("true", "false").contains(message.get("accept")))
                    throw new IllegalArgumentException("悔棋答复无效");
                if (message.get("accept").equals("true")) {
                    game.undo(room.undoRequestedBy);
                    clearUndo(room, "双方同意，已撤回到申请方上次落子前");
                } else clearUndo(room, "对方拒绝了悔棋请求");
            }
        }
        broadcast(room);
    }

    private void action(Player player, Frame message) {
        Room room = requireRoom(player);
        if (player.role == 0) throw new IllegalArgumentException("观战者不能操作棋局");
        if (room.game == null) throw new IllegalArgumentException("请等待双方准备");
        Game game = room.game;
        if (room.aiMode.equals("katago") && !room.aiReady)
            throw new IllegalArgumentException("模型正在准备，完成后才能开始对局。");
        if (message.number("revision") != game.revision())
            throw new IllegalArgumentException("棋局已更新，请根据最新棋盘操作");
        if (paused(room))
            throw new IllegalArgumentException("对方掉线，等待重连，最多 " + reconnectSeconds + " 秒");
        if (room.scoreBusy && !message.type().equals("RESIGN"))
            throw new IllegalArgumentException("正在分析死活，请稍后操作");
        if (!room.aiMode.isEmpty() && message.type().equals("MARK_DEAD"))
            throw new IllegalArgumentException("人机模式由 KataGo 判断死活，请点击点目分析");
        if (room.undoRequestedBy != 0 && !message.type().equals("RESIGN"))
            throw new IllegalArgumentException("请先处理悔棋请求，再继续对局");
        if (room.scoreRequestedBy != 0
                && !Set.of("RESPOND_SCORE", "CANCEL_SCORE", "RESIGN").contains(message.type()))
            throw new IllegalArgumentException("请先处理结算申请");
        clearUndo(room, "");
        switch (message.type()) {
            case "MOVE" -> game.play(player.role, message.integer("x"), message.integer("y"));
            case "PASS" -> game.pass(player.role);
            case "RESIGN" -> {
                clearScore(room, "");
                game.resign(player.role, "认输");
            }
            case "REQUEST_SCORE" -> {
                if (!room.aiMode.isEmpty() || game.phase() != Game.Phase.PLAYING)
                    throw new IllegalArgumentException("当前不能申请真人结算");
                room.scoreRequestedBy = player.role;
                room.scoreRequestId = UUID.randomUUID().toString();
                room.scoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                room.scoreMessage = "";
            }
            case "CANCEL_SCORE", "RESPOND_SCORE" -> {
                if (room.scoreRequestedBy == 0
                        || !room.scoreRequestId.equals(message.get("scoreRequestId")))
                    throw new IllegalArgumentException("结算申请已失效");
                if (System.nanoTime() >= room.scoreDeadline) {
                    clearScore(room, "结算申请已超时，继续对局");
                    break;
                }
                if (message.type().equals("CANCEL_SCORE")) {
                    if (player.role != room.scoreRequestedBy)
                        throw new IllegalArgumentException("只能取消自己的申请");
                    clearScore(room, "已取消结算申请，继续对局");
                } else {
                    if (player.role == room.scoreRequestedBy)
                        throw new IllegalArgumentException("请等待对方同意");
                    if (!Set.of("true", "false").contains(message.get("accept")))
                        throw new IllegalArgumentException("结算答复无效");
                    if (message.get("accept").equals("true")) {
                        game.beginScoring(player.role);
                        clearScore(room, "");
                    } else clearScore(room, "对方不同意结算，继续对局");
                }
            }
            case "ANALYZE_SCORE" -> {
                if (room.aiMode.isEmpty()) throw new IllegalArgumentException("真人对局请双方确认死子后结算");
                analyzeScore(room);
            }
            case "END_AI_GAME" -> {
                if (room.aiMode.isEmpty()) throw new IllegalArgumentException("真人对局需对方同意结算");
                acceptScore(room);
            }
            case "MARK_DEAD" ->
                    game.markDead(player.role, message.integer("x"), message.integer("y"));
            case "CONFIRM_SCORE" -> {
                if (!room.aiMode.isEmpty()) acceptScore(room);
                else game.confirm(player.role);
            }
            case "RESUME_PLAY" -> game.resume(player.role);
            default -> throw new IllegalArgumentException("不支持的操作");
        }
        if (game.phase() == Game.Phase.FINISHED) cancelAI(room);
        else scheduleAI(room);
        broadcast(room);
        archive(room);
        broadcastRooms();
    }

    private void clearScore(Room room, String message) {
        room.scoreRequestedBy = 0;
        room.scoreRequestId = "";
        room.scoreDeadline = 0;
        room.scoreMessage = message;
    }

    private void acceptScore(Room room) {
        if (room.scoreRevision != room.game.revision() || room.scorePreview.isEmpty())
            throw new IllegalArgumentException("请先点目分析当前棋盘，再确认结算");
        room.game.adjudicate(Double.parseDouble(room.scorePreview.get("blackLead")),
                java.util.BitSet.valueOf(java.util.Base64.getDecoder().decode(room.scorePreview.get("dead"))));
    }

    private void analyzeScore(Room room) {
        if (models == null || !room.aiJob.isEmpty() || room.game.phase() == Game.Phase.FINISHED)
            throw new IllegalArgumentException("模型忙碌或不可用，请稍后重新点目");
        long revision = room.game.revision();
        String job = UUID.randomUUID().toString();
        Map<String, String> fields = new HashMap<>(room.game.snapshot());
        fields.putAll(Map.of("jobId", job, "gameId", room.gameId, "aiMode", "katago",
                "aiDifficulty", room.aiDifficulty.name()));
        ModelClient.Call call = models.call("MODEL_SCORE", fields, 720000);
        room.aiJob = job;
        room.aiCall = call;
        room.scoreBusy = true;
        room.scoreError = "";
        room.scorePreview = Map.of();
        room.scoreRevision = -1;
        try {
            room.aiTask = modelRequests.submit(() -> {
                Frame answer = null;
                try (call) { answer = call.execute(); }
                catch (IOException e) { System.err.println("Score analysis failed: " + e.getMessage()); }
                Frame scored = answer;
                dispatch(() -> {
                    if (!room.aiJob.equals(job)) return;
                    room.aiJob = ""; room.aiCall = null; room.aiTask = null; room.scoreBusy = false;
                    if (rooms.get(room.id) != room || room.game.revision() != revision
                            || room.game.phase() == Game.Phase.FINISHED || paused(room)) return;
                    try {
                        if (scored == null || !scored.type().equals("MODEL_SCORE")
                                || !scored.get("jobId").equals(job) || !scored.get("gameId").equals(room.gameId)
                                || scored.number("revision") != revision) throw new IllegalArgumentException("模型计分失败，请重新点目");
                        double lead = Double.parseDouble(scored.get("blackLead"));
                        java.util.BitSet dead = java.util.BitSet.valueOf(java.util.Base64.getDecoder().decode(scored.get("dead")));
                        room.game.copy().adjudicate(lead, dead); // Validate before publishing any result.
                        room.scorePreview = Map.of("blackLead", Double.toString(lead), "dead", scored.get("dead"));
                        room.scoreRevision = revision;
                    } catch (IllegalArgumentException e) { room.scoreError = "模型计分失败，请重新点目；未结束对局。"; }
                    broadcast(room);
                });
            });
        } catch (RejectedExecutionException e) {
            cancelAI(room);
            room.scoreError = "模型队列已满，请稍后重新点目";
        }
    }

    private Room requireRoom(Player player) {
        if (player.room == null) throw new IllegalArgumentException("尚未加入房间");
        return player.room;
    }

    private void leave(Player player, boolean explicit) {
        Room room = player.room;
        if (room == null) return;
        if (player.role != 0) {
            clearUndo(room, "悔棋请求因棋手退出而取消");
            clearScore(room, "结算申请因棋手退出而取消");
        }
        if (player.role != 0
                && room.aiMode.equals("katago")
                && !room.aiReady
                && room.game != null) {
            cancelAI(room);
            room.game = null;
        }
        if (player.role != 0 && room.game != null && room.game.phase() != Game.Phase.FINISHED) {
            cancelAI(room);
            room.game.resign(player.role, explicit ? "退出对局" : "重连超时");
            archive(room);
        }
        if (room.black == player) room.black = null;
        if (room.white == player) room.white = null;
        room.watchers.remove(player);
        player.room = null;
        player.role = 0;
        player.ready = false;
        if (!room.aiMode.isEmpty()
                && ((room.black == null || room.black.bot)
                        && (room.white == null || room.white.bot))) {
            if (room.black != null && room.black.bot) room.black = null;
            if (room.white != null && room.white.bot) room.white = null;
        }
        if (room.game == null) {
            if (room.black != null) room.black.ready = false;
            if (room.white != null) room.white.ready = false;
        }
        if (room.black == null && room.white == null && room.watchers.isEmpty())
            rooms.remove(room.id);
        else broadcast(room);
        broadcastRooms();
    }

    private Map<String, String> state(Room room) {
        Map<String, String> data =
                new LinkedHashMap<>(
                        (room.game == null ? new Game(room.size) : room.game).snapshot());
        if (room.game == null) data.put("phase", "WAITING");
        data.put("roomId", room.id);
        data.put("title", room.title);
        data.put("gameId", room.gameId);
        data.put("black", room.game == null ? name(room.black) : room.blackName);
        data.put("white", room.game == null ? name(room.white) : room.whiteName);
        data.put(
                "blackProfileId",
                room.game == null
                        ? room.black == null ? "" : room.black.profileId
                        : room.blackProfileId);
        data.put(
                "whiteProfileId",
                room.game == null
                        ? room.white == null ? "" : room.white.profileId
                        : room.whiteProfileId);
        data.put("blackOnline", "" + online(room.black));
        data.put("whiteOnline", "" + online(room.white));
        data.put("blackReady", "" + (room.black != null && room.black.ready));
        data.put("whiteReady", "" + (room.white != null && room.white.ready));
        data.put("watchers", "" + room.watchers.size());
        data.put("scoreRequestedBy", "" + room.scoreRequestedBy);
        data.put("scoreRequestId", room.scoreRequestId);
        data.put("scoreMessage", room.scoreMessage);
        data.put("undoRequestedBy", "" + room.undoRequestedBy);
        data.put("undoRequestId", room.undoRequestId);
        data.put("undoMessage", room.undoMessage);
        data.put("canUndo", "" + (room.game != null && room.game.canUndo(room.game.turn())));
        data.put("aiMode", room.aiMode);
        data.put("aiThinking", "" + (room.aiReady && !room.aiJob.isEmpty() && !room.scoreBusy));
        data.put("scoreAnalyzing", Boolean.toString(room.scoreBusy));
        data.put("scoreError", room.scoreError);
        if (room.game != null && room.scoreRevision == room.game.revision()) {
            data.put("assessedLead", room.scorePreview.getOrDefault("blackLead", ""));
            data.put("assessedDead", room.scorePreview.getOrDefault("dead", ""));
        }
        data.put("aiError", room.aiError);
        data.put(
                "aiPreparing",
                ""
                        + (!room.aiReady
                                && room.aiMode.equals("katago")
                                && room.game != null
                                && room.game.phase() == Game.Phase.PLAYING));
        data.put("aiStage", room.aiStage);
        data.put("aiJobId", room.aiJob);
        if (room.aiThinkStarted > 0)
            data.put(
                    "aiRemainingMillis",
                    Long.toString(
                            Math.max(
                                    0,
                                    room.aiDifficulty.budget().seconds * 1000L
                                            - TimeUnit.NANOSECONDS.toMillis(
                                                    System.nanoTime() - room.aiThinkStarted))));
        if (!room.aiMode.isEmpty()) {
            data.put("aiModel", room.aiDifficulty.model().name());
            data.put("aiBudget", room.aiDifficulty.budget().name());
            data.put("aiRank", room.aiDifficulty.toString());
            data.put("aiDifficulty", room.aiDifficulty.name());
        }
        boolean paused = paused(room);
        data.put("paused", "" + paused);
        if (paused) {
            long elapsed = 0;
            for (Player p : new Player[] {room.black, room.white})
                if (p != null && !p.bot && p.session == null)
                    elapsed = Math.max(elapsed, System.nanoTime() - p.disconnectedAt);
            data.put(
                    "reconnectSeconds",
                    "" + Math.max(0, reconnectSeconds - TimeUnit.NANOSECONDS.toSeconds(elapsed)));
        }
        return data;
    }

    private void sendState(Room room, Player player) {
        if (player.session == null) return;
        Map<String, String> data = state(room);
        data.put("role", "" + player.role);
        data.put("canUndo", "" + (room.game != null && room.game.canUndo(player.role)));
        player.session.send(new Frame("STATE", data));
    }

    private void broadcast(Room room) {
        if (room.black != null) sendState(room, room.black);
        if (room.white != null) sendState(room, room.white);
        for (Player p : room.watchers) sendState(room, p);
    }

    private void sendRooms(Session session) {
        StringBuilder list = new StringBuilder();
        for (Room room : rooms.values()) {
            list.append(room.id)
                    .append('\t')
                    .append(room.title)
                    .append('\t')
                    .append(room.size)
                    .append('\t')
                    .append(0)
                    .append('\t')
                    .append(
                            room.game == null
                                    ? "等待中"
                                    : room.game.phase() == Game.Phase.FINISHED ? "已结束" : "对局中")
                    .append('\t')
                    .append(name(room.black))
                    .append('\t')
                    .append(name(room.white))
                    .append('\t')
                    .append(room.watchers.size())
                    .append('\n');
        }
        StringBuilder roster = new StringBuilder();
        for (Player p : players.values())
            if (!p.bot && online(p)) {
                Room room = p.room;
                boolean playing =
                        room != null
                                && room.game != null
                                && room.game.phase() != Game.Phase.FINISHED
                                && p.role != 0;
                roster.append(p.name)
                        .append('\t')
                        .append(room == null ? "" : room.id)
                        .append('\t')
                        .append(playing)
                        .append('\n');
            }
        session.send(
                new Frame(
                        "ROOM_LIST",
                        Map.of("rooms", list.toString(), "players", roster.toString())));
    }

    private void broadcastRooms() {
        for (Player player : players.values())
            if (player.session != null) sendRooms(player.session);
    }

    private static String name(Player p) {
        return p == null ? "" : p.name;
    }

    private static boolean online(Player p) {
        return p != null && (p.bot || (p.session != null && !p.session.closed.get()));
    }

    private void archive(Room room) {
        if (room.game == null || room.game.phase() != Game.Phase.FINISHED || room.archived) return;
        room.archived = true;
        Map<String, String> snapshot = state(room);
        for (Player player : new Player[] {room.black, room.white})
            if (player != null && !player.bot && player.session != null) {
                int winner = Integer.parseInt(snapshot.get("winner"));
                Map<String, String> result = new LinkedHashMap<>(snapshot);
                result.put("role", "" + player.role);
                result.put("machine", "" + !room.aiMode.isEmpty());
                result.put("outcome", "" + (winner == 0 ? 0 : winner == player.role ? 1 : 2));
                player.session.send(new Frame("GAME_RESULT", result));
            }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        disk.execute(
                () -> {
                    try {
                        RecordStore.save(
                                records.resolve(
                                        stamp
                                                + "-"
                                                + room.id
                                                + "-"
                                                + room.gameId.substring(0, 8)
                                                + ".gogame"),
                                snapshot);
                    } catch (IOException | IllegalArgumentException e) {
                        System.err.println("Record save failed: " + e.getMessage());
                    }
                });
    }

    private void disconnected(Session session) {
        connections.remove(session);
        Player player = session.player;
        if (player == null || player.session != session) return;
        player.session = null;
        player.disconnectedAt = System.nanoTime();
        if (player.room != null) {
            if (player.role != 0) {
                clearUndo(player.room, "悔棋请求因棋手掉线而取消");
                clearScore(player.room, "结算申请因棋手掉线而取消");
            }
            if (player.role == 0) leave(player, false);
            else {
                cancelAI(player.room);
                broadcast(player.room);
            }
        }
        broadcastRooms();
    }

    private void maintenance() {
        long now = System.nanoTime();
        for (Room room : rooms.values())
            if (room.undoRequestedBy != 0 && now >= room.undoDeadline) {
                clearUndo(room, "悔棋请求已超时");
                broadcast(room);
            }
        for (Room room : rooms.values())
            if (room.scoreRequestedBy != 0 && now >= room.scoreDeadline) {
                clearScore(room, "结算申请已超时，继续对局");
                broadcast(room);
            }
        if (now - catalogChecked > TimeUnit.SECONDS.toNanos(15)) refreshCatalog(null);
        for (Session session : connections)
            if (now - session.lastReceived > TimeUnit.SECONDS.toNanos(35)) session.close();
        for (Player player : new ArrayList<>(players.values())) {
            if (player.session == null && now - player.disconnectedAt >= reconnectNanos) {
                leave(player, false);
                players.remove(player.token);
            }
        }
        for (Room room : rooms.values())
            if (room.game != null
                    && room.game.phase() != Game.Phase.FINISHED
                    && (!online(room.black) || !online(room.white))) broadcast(room);
    }

    private static String clean(String value, int max, String label) {
        String text = value.strip();
        if (text.isEmpty() || text.length() > max || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + "应为 1～" + max + " 个字符，不能包含控制字符");
        return text;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        for (Session session : connections) session.close();
        timer.shutdownNow();
        dispatcher.execute(
                () -> {
                    for (Room room : rooms.values()) cancelAI(room);
                });
        dispatcher.shutdown();
        try {
            dispatcher.awaitTermination(3, TimeUnit.SECONDS);
            modelRequests.shutdownNow();
            io.shutdown();
            disk.shutdown();
            disk.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Session {
        final Socket socket;
        final BlockingQueue<Frame> outgoing = new ArrayBlockingQueue<>(256);
        final AtomicBoolean closed = new AtomicBoolean();
        volatile long lastReceived = System.nanoTime();
        long lastRequest;
        boolean historyPending;
        Player player;

        Session(Socket socket) {
            this.socket = socket;
        }

        void send(Frame frame) {
            if (!closed.get() && !outgoing.offer(frame)) close();
        }

        void readLoop() {
            try {
                InputStream input = new BufferedInputStream(socket.getInputStream());
                while (!closed.get()) {
                    Frame frame = Protocol.read(input);
                    lastReceived = System.nanoTime();
                    dispatch(() -> handle(this, frame));
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        void writeLoop() {
            try {
                OutputStream output = new BufferedOutputStream(socket.getOutputStream());
                while (!closed.get()) {
                    Frame frame = outgoing.poll(1, TimeUnit.SECONDS);
                    if (frame != null) Protocol.write(output, frame);
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                close();
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            dispatch(() -> disconnected(this));
        }
    }

    private static final class Player {
        String name, profileId = "";
        final String token = UUID.randomUUID().toString();
        final Set<String> aliases = new LinkedHashSet<>();
        Session session;
        Room room;
        int role;
        boolean ready;
        final boolean bot;
        long disconnectedAt;

        Player(String name) {
            this(name, false);
        }

        Player(String name, boolean bot) {
            this.name = name;
            this.bot = bot;
        }
    }

    private static final class Room {
        final String id, title, gameId = UUID.randomUUID().toString();
        final int size;
        Player black, white;
        String blackName = "", whiteName = "", blackProfileId = "", whiteProfileId = "";
        final Set<Player> watchers = new LinkedHashSet<>();
        Game game;
        boolean archived;
        String aiMode = "", aiJob = "", aiError = "";
        String aiStage = "";
        boolean aiReady = true;
        long aiThinkStarted;
        AiDifficulty aiDifficulty = AiDifficulty.AMATEUR_LOW;
        int scoreRequestedBy;
        String scoreRequestId = "", scoreMessage = "";
        boolean scoreBusy;
        String scoreError = "";
        long scoreRevision = -1;
        Map<String, String> scorePreview = Map.of();
        long scoreDeadline;
        int undoRequestedBy;
        String undoRequestId = "", undoMessage = "";
        long undoDeadline;
        ModelClient.Call aiCall;
        Future<?> aiTask;

        Room(String id, String title, int size) {
            this.id = id;
            this.title = title;
            this.size = size;
        }
    }

    public static void main(String[] args) throws Exception {
        Properties config =
                ServiceConfig.load(
                        System.getProperty("go.server.config", "config/server.properties"));
        int port =
                args.length > 0
                        ? Integer.parseInt(args[0])
                        : Integer.parseInt(config.getProperty("listen.port", "9528"));
        String bind = args.length > 1 ? args[1] : config.getProperty("listen.host", "0.0.0.0");
        Path modelHome = Path.of(System.getProperty("go.katago.home", config.getProperty("katago.home", "katago"))).toAbsolutePath();
        EmbeddedModelService modelService = new EmbeddedModelService(modelHome);
        try {
        GameServer server =
                new GameServer(
                        bind,
                        port,
                        Path.of("records", "server"),
                        Duration.ofSeconds(60),
                        new ModelClient(modelService));
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    modelService.close();
                                    server.close();
                                }));
        server.start();
        } catch (Exception e) {
            modelService.close();
            throw e;
        }
    }
}
