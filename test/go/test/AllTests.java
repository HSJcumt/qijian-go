package go.test;

import go.client.BoardPanel;
import go.core.Game;
import go.core.GameView;
import go.core.RecordStore;
import go.net.Protocol;
import go.net.Protocol.Frame;
import go.server.GameServer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Dependency-free regression tests against the real rules and real TCP server. */
@SuppressWarnings("try")
public final class AllTests {
    private static int checks;
    private static final Path OUTPUT = Path.of("build", "test-output");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        run("captures, liberties, suicide and transactional rejection", AllTests::rules);
        run("ko, pass, scoring agreement and resume", AllTests::koAndScoring);
        run("fragmented and coalesced frames; malformed input", AllTests::protocol);
        run("record round trip, replay captures and corrupt records", AllTests::records);
        run("automatic client records and shutdown persistence", ClientRecordArchiveTests::run);
        run("AI capture, legal self-play and 19x19 response", AllTests::ai);
        run("KataGo GTP subprocess integration", KataGoTests::run);
        run("model load, warmup and cached inference budgets", ModelWarmupTests::run);
        run("client artwork, player profiles and home actions", ClientEntryTests::run);
        run("illustrated AI difficulty selection", go.client.AiSetupTests::run);
        run("loading animation and actual-search countdown", go.client.AiTimingTests::run);
        run("page navigation and absence of operation windows", go.client.PageNavigationTests::run);
        run("player avatars, nickname and durable statistics", ProfileTests::run);
        run("real TCP: profile edits and finished-game history", ProfileServerTests::run);
        run(
                "real TCP: players, spectator, stale commands, reconnect and scoring",
                AllTests::network);
        run("real TCP: home roster, invitations and watching", AllTests::homeRoster);
        run("real TCP: disconnect expiry and forfeit", AllTests::expiry);
        run("embedded model, cancellation and real TCP AI", EmbeddedTests::run);
        run("model adjudication and revision-bound settlement", ScoringTests::run);
        run("headless Java2D board rendering", AllTests::boardRendering);
        System.out.println(
                "PASS: "
                        + checks
                        + " core checks plus adapter, architecture, undo, entry, page navigation"
                        + " and AI timing checks plus embedded model integration checks.");
    }

    private static void homeRoster() throws Exception {
        try (GameServer server =
                new GameServer(
                        "127.0.0.1",
                        0,
                        OUTPUT.resolve("home-records"),
                        Duration.ofSeconds(2),
                        null)) {
            server.start();
            try (Peer a = new Peer(server.port());
                    Peer b = new Peer(server.port());
                    Peer spectator = new Peer(server.port())) {
                a.hello("home-a", "");
                b.hello("home-b", "");
                spectator.hello("home-watcher", "");
                a.send("LIST_ROOMS", Map.of());
                Frame roster =
                        a.await(
                                f ->
                                        f.type().equals("ROOM_LIST")
                                                && f.get("players").contains("home-b\t\tfalse")
                                                && f.get("players").contains("home-watcher"));
                check(
                        roster.get("players").contains("home-watcher"),
                        "idle online users appear without requiring a room");
                a.send("INVITE_PLAYER", Map.of("nickname", "missing"));
                a.await(f -> f.type().equals("ERROR"));
                a.send("INVITE_PLAYER", Map.of("nickname", "home-b"));
                Frame invitation = b.await(f -> f.type().equals("INVITATION"));
                check(invitation.get("from").equals("home-a"), "invitation names the real sender");
                b.send("JOIN_ROOM", Map.of("roomId", invitation.get("roomId")));
                b.state(f -> f.get("role").equals("2"));
                a.send("READY", Map.of());
                b.send("READY", Map.of());
                a.state(f -> f.get("phase").equals("PLAYING"));
                Frame active =
                        spectator.await(
                                f ->
                                        f.type().equals("ROOM_LIST")
                                                && f.get("players")
                                                        .contains(
                                                                "home-a\t"
                                                                        + invitation.get("roomId")
                                                                        + "\ttrue"));
                check(
                        active.get("players")
                                .contains("home-b\t" + invitation.get("roomId") + "\ttrue"),
                        "both active players offer watching");
                spectator.send("WATCH_ROOM", Map.of("roomId", invitation.get("roomId")));
                check(
                        spectator
                                .state(f -> f.get("role").equals("0"))
                                .get("phase")
                                .equals("PLAYING"),
                        "home watch action opens the selected live game");
            }
        }
    }

    private static void rules() throws Exception {
        Game game = new Game(5, 7.5);
        game.play(1, 1, 1);
        long revision = game.revision();
        int[] before = game.board();
        fails(() -> game.play(1, 2, 2));
        fails(() -> game.play(2, 1, 1));
        fails(() -> game.play(2, -1, 0));
        check(
                Arrays.equals(before, game.board())
                        && game.revision() == revision
                        && game.turn() == 2,
                "rejections must not mutate game");
        int[] board = new int[25];
        board[0] = 2;
        board[1] = 1;
        Game.Trial capture = Game.simulate(board, 5, 1, 0, 1);
        check(capture.captured() == 1 && capture.board()[0] == 0, "corner capture");
        check(board[0] == 2 && board[5] == 0, "simulation cannot mutate its input");
        int[] suicide = new int[25];
        suicide[1] = 1;
        suicide[5] = 1;
        fails(() -> Game.simulate(suicide, 5, 2, 0, 0));
        int[] simultaneous = new int[25];
        simultaneous[0] = simultaneous[2] = simultaneous[10] = 1;
        simultaneous[1] = simultaneous[5] = 2;
        Game.Trial two = Game.simulate(simultaneous, 5, 1, 1, 1);
        check(
                two.captured() == 2 && two.board()[1] == 0 && two.board()[5] == 0,
                "simultaneous neighboring captures");
        int[] shared = new int[25];
        shared[0] = shared[1] = shared[5] = 1;
        check(Game.group(shared, 5, 0).liberties().size() == 3, "shared liberties count only once");
        int[] filled = new int[25];
        Arrays.fill(filled, 2);
        filled[0] = 0;
        filled[1] = 1;
        Game.Trial captureFirst = Game.simulate(filled, 5, 1, 0, 0);
        check(captureFirst.captured() == 23, "remove opponents before suicide check");
        fails(() -> new Game(30, 7.5));
        fails(() -> new Game(9, Double.NaN));
    }

    private static Game koPosition() {
        Game game = new Game(5, 0);
        game.play(1, 0, 1);
        game.play(2, 1, 1);
        game.play(1, 1, 0);
        game.play(2, 0, 2);
        game.play(1, 2, 1);
        game.play(2, 2, 2);
        game.pass(1);
        game.play(2, 1, 3);
        return game;
    }

    private static void koAndScoring() throws Exception {
        Game ko = koPosition();
        ko.play(1, 1, 2);
        check(ko.board()[6] == 0, "ko capture removes stone");
        long version = ko.revision();
        fails(() -> ko.play(2, 1, 1));
        check(ko.revision() == version && ko.board()[11] == 1, "ko rejection is atomic");
        ko.play(2, 4, 4);
        ko.play(1, 4, 0);
        ko.play(2, 1, 1);
        check(ko.board()[11] == 0 && ko.board()[6] == 2, "recapture after intervening moves");
        Game game = new Game(5, 7.5);
        game.play(1, 0, 0);
        game.play(2, 4, 4);
        game.pass(1);
        game.pass(2);
        check(game.phase() == Game.Phase.SCORING, "two passes enter scoring");
        fails(() -> game.play(1, 2, 2));
        Game.Score neutral = game.score();
        check(neutral.black() == 1 && neutral.white() == 8.5, "shared empty region is neutral");
        game.confirm(1);
        game.markDead(2, 4, 4);
        check(
                new GameView(game.snapshot()).confirmed == 0,
                "dead-group changes invalidate both confirmations");
        check(
                game.score().black() == 25 && game.score().white() == 7.5,
                "area scoring after removing agreed dead stones");
        game.resume(1);
        check(
                game.phase() == Game.Phase.PLAYING
                        && game.passes() == 0
                        && new GameView(game.snapshot()).dead.isEmpty(),
                "resume resets scoring metadata");
        game.pass(1);
        game.pass(2);
        game.confirm(1);
        game.confirm(2);
        check(
                game.phase() == Game.Phase.FINISHED && game.winner() == 2,
                "both confirmations required and komi applied");
        fails(() -> game.pass(game.turn()));
        Game tie = new Game(5, 0);
        tie.pass(1);
        tie.pass(2);
        tie.confirm(1);
        tie.confirm(2);
        check(tie.winner() == 0 && tie.result().contains("和棋"), "empty-board tie");
    }

    private static void protocol() throws Exception {
        Frame original = new Frame("MOVE", 42, Map.of("x", "3", "nickname", "围棋玩家"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Protocol.write(bytes, original);
        Protocol.write(bytes, new Frame("PING", Map.of()));
        InputStream fragmented =
                new ByteArrayInputStream(bytes.toByteArray()) {
                    @Override
                    public synchronized int read(byte[] target, int offset, int length) {
                        return super.read(target, offset, Math.min(length, 1));
                    }
                };
        check(Protocol.read(fragmented).equals(original), "one-byte fragmented reads");
        check(
                Protocol.read(fragmented).type().equals("PING") && fragmented.read() == -1,
                "coalesced messages preserve boundaries");
        ByteArrayOutputStream malformed = new ByteArrayOutputStream();
        new DataOutputStream(malformed).writeInt(Protocol.MAX_FRAME + 1);
        fails(() -> Protocol.read(new ByteArrayInputStream(malformed.toByteArray())));
        byte[] truncated = Arrays.copyOf(bytes.toByteArray(), 8);
        fails(() -> Protocol.read(new ByteArrayInputStream(truncated)));
        byte[] wrongVersion = bytes.toByteArray();
        wrongVersion[5] = 9;
        fails(() -> Protocol.read(new ByteArrayInputStream(wrongVersion)));
        ByteArrayOutputStream zero = new ByteArrayOutputStream();
        new DataOutputStream(zero).writeInt(-1);
        fails(() -> Protocol.read(new ByteArrayInputStream(zero.toByteArray())));
    }

    private static void records() throws Exception {
        Game game = koPosition();
        game.play(1, 1, 2);
        game.pass(2);
        game.pass(1);
        game.resume(2);
        game.play(2, 4, 4);
        game.resign(1, "认输");
        Path path = OUTPUT.resolve("capture-replay.gogame");
        RecordStore.save(path, game.snapshot());
        GameView restored = RecordStore.load(path);
        List<int[]> frames = restored.replayBoards();
        check(
                Arrays.equals(restored.board, game.board())
                        && restored.moves.size() == game.moves().size(),
                "record round trip");
        check(
                frames.get(8)[6] == 2 && frames.get(9)[6] == 0,
                "replay reflects captures at the right step");
        check(
                restored.phase.equals("FINISHED") && !restored.result.isEmpty(),
                "record preserves result");
        Map<String, String> corrupt = new LinkedHashMap<>(game.snapshot());
        corrupt.put("board", Game.key(new int[25]));
        Path bad = OUTPUT.resolve("invalid.gogame");
        RecordStore.save(bad, corrupt);
        fails(() -> RecordStore.load(bad));
        Files.write(path, new byte[] {0, 0, 0, 1});
        fails(() -> RecordStore.load(path));
    }

    private static void ai() throws Exception {
        check(go.ai.AiDifficulty.values().length == 5, "exactly five difficulty presets");
        for (go.ai.AiDifficulty difficulty : go.ai.AiDifficulty.values()) {
            check(
                    difficulty.overrides().contains("maxTime=" + difficulty.budget().seconds),
                    "preset has its own time limit");
            check(
                    difficulty.humanLike() == (difficulty != go.ai.AiDifficulty.SUPERHUMAN),
                    "human models are used only for ranked presets");
        }
        check(
                go.ai.AiDifficulty.AMATEUR_LOW.humanProfile().equals("rank_2d"),
                "low amateur target");
        check(
                go.ai.AiDifficulty.AMATEUR_MID.humanProfile().equals("rank_5d"),
                "middle amateur target");
        check(
                go.ai.AiDifficulty.AMATEUR_HIGH.humanProfile().equals("rank_8d"),
                "high amateur target");
        check(
                go.ai.AiDifficulty.PROFESSIONAL.humanProfile().equals("proyear_2023"),
                "professional style target");
    }

    private static void network() throws Exception {
        Path archive = OUTPUT.resolve("server-records");
        Files.createDirectories(archive);
        try (GameServer server = new GameServer("127.0.0.1", 0, archive)) {
            server.start();
            try (Peer black = new Peer(server.port());
                    Peer white = new Peer(server.port());
                    Peer watcher = new Peer(server.port())) {
                black.hello("黑棋测试", "");
                Frame welcome = white.hello("白棋测试", "");
                watcher.hello("观战测试", "");
                black.send("CREATE_ROOM", Map.of("title", "集成测试", "size", "9", "komi", "7.5"));
                Frame initial = black.state(f -> f.get("phase").equals("WAITING"));
                String room = initial.get("roomId");
                white.send("JOIN_ROOM", Map.of("roomId", room));
                white.state(f -> f.get("role").equals("2"));
                black.state(f -> f.get("white").equals("白棋测试"));
                black.send("READY", Map.of());
                black.state(f -> f.get("blackReady").equals("true"));
                white.send("READY", Map.of());
                black.state(f -> f.get("phase").equals("PLAYING"));
                white.state(f -> f.get("phase").equals("PLAYING"));
                black.action("MOVE", 0, Map.of("x", "2", "y", "2"));
                black.revision(1);
                white.revision(1);
                white.action("MOVE", 1, Map.of("x", "6", "y", "6"));
                Frame second = black.revision(2);
                white.revision(2);
                watcher.send("WATCH_ROOM", Map.of("roomId", room));
                Frame watching = watcher.revision(2);
                check(
                        watching.get("board").equals(second.get("board"))
                                && watching.get("role").equals("0"),
                        "mid-game spectator receives complete snapshot");
                watcher.action("MOVE", 2, Map.of("x", "3", "y", "3"));
                watcher.await(f -> f.type().equals("ERROR"));
                watcher.revision(2);
                black.send("SYNC", Map.of());
                check(
                        black.revision(2).get("board").equals(second.get("board")),
                        "spectator move rejected without mutation");
                white.action("MOVE", 2, Map.of("x", "3", "y", "3"));
                white.await(f -> f.type().equals("ERROR"));
                white.revision(2);
                black.action("MOVE", 1, Map.of("x", "3", "y", "2"));
                black.await(f -> f.type().equals("ERROR"));
                black.revision(2);
                long id = black.action("MOVE", 2, Map.of("x", "3", "y", "2"));
                Frame third = black.revision(3);
                white.revision(3);
                watcher.revision(3);
                black.write(new Frame("MOVE", id, Map.of("revision", "3", "x", "4", "y", "2")));
                black.await(f -> f.type().equals("ERROR"));
                black.revision(3);
                white.close();
                Frame paused = black.state(f -> f.get("paused").equals("true"));
                check(
                        paused.get("board").equals(third.get("board")),
                        "disconnect pauses without modifying the board");
                black.action("MOVE", 3, Map.of("x", "4", "y", "2"));
                black.await(f -> f.type().equals("ERROR"));
                try (Peer reconnected = new Peer(server.port())) {
                    reconnected.hello("白棋测试", welcome.get("token"));
                    Frame recovered =
                            reconnected.state(
                                    f ->
                                            f.get("role").equals("2")
                                                    && f.get("paused").equals("false"));
                    check(
                            recovered.get("board").equals(third.get("board")),
                            "reconnect restores seat and full state");
                    black.state(f -> f.get("paused").equals("false") && f.number("revision") == 3);
                    reconnected.action("MOVE", 3, Map.of("x", "6", "y", "5"));
                    black.revision(4);
                    reconnected.revision(4);
                    black.action("PASS", 4, Map.of());
                    black.revision(5);
                    reconnected.revision(5);
                    reconnected.action("PASS", 5, Map.of());
                    black.revision(6);
                    reconnected.revision(6);
                    black.action("MARK_DEAD", 6, Map.of("x", "2", "y", "2"));
                    Frame marked = black.revision(7);
                    reconnected.revision(7);
                    check(
                            BitSet.valueOf(Base64.getDecoder().decode(marked.get("dead")))
                                            .cardinality()
                                    == 2,
                            "whole connected group marked dead");
                    black.action("CONFIRM_SCORE", 7, Map.of());
                    black.revision(8);
                    reconnected.revision(8);
                    reconnected.action("MARK_DEAD", 8, Map.of("x", "6", "y", "6"));
                    check(
                            black.revision(9).get("confirmed").equals("0"),
                            "network marking invalidates prior confirmation");
                    reconnected.revision(9);
                    black.action("RESUME_PLAY", 9, Map.of());
                    black.revision(10);
                    reconnected.revision(10);
                    black.action("PASS", 10, Map.of());
                    black.revision(11);
                    reconnected.revision(11);
                    reconnected.action("PASS", 11, Map.of());
                    black.revision(12);
                    reconnected.revision(12);
                    black.action("CONFIRM_SCORE", 12, Map.of());
                    black.revision(13);
                    reconnected.revision(13);
                    reconnected.action("CONFIRM_SCORE", 13, Map.of());
                    Frame ended = black.revision(14);
                    reconnected.revision(14);
                    watcher.revision(14);
                    check(
                            ended.get("phase").equals("FINISHED") && !ended.get("result").isEmpty(),
                            "authoritative game result broadcast");
                    new GameView(ended.fields()).replayBoards();
                    reconnected.send("LEAVE_ROOM", Map.of());
                    reconnected.await(f -> f.type().equals("LEFT"));
                    reconnected.send(
                            "CREATE_ROOM", Map.of("title", "第二房间", "size", "19", "komi", "6.5"));
                    Frame otherRoom = reconnected.state(f -> f.get("phase").equals("WAITING"));
                    check(!otherRoom.get("roomId").equals(room), "independent rooms");
                    black.send("SYNC", Map.of());
                    Frame old = black.revision(14);
                    check(
                            old.get("white").equals("白棋测试")
                                    && old.get("result").equals(ended.get("result")),
                            "leaving a finished room preserves players and result");
                    watcher.send("JOIN_ROOM", Map.of("roomId", otherRoom.get("roomId")));
                    watcher.await(f -> f.type().equals("ERROR"));
                    check(
                            watcher.state(f -> f.get("roomId").equals(room))
                                    .get("phase")
                                    .equals("FINISHED"),
                            "cannot enter two rooms at once");
                }
                try (Socket bad = new Socket("127.0.0.1", server.port())) {
                    bad.setSoTimeout(3000);
                    new DataOutputStream(bad.getOutputStream()).writeInt(-5);
                    check(bad.getInputStream().read() == -1, "malformed connection is closed");
                }
                black.send("LIST_ROOMS", Map.of());
                black.await(f -> f.type().equals("ROOM_LIST"));
            }
        }
        try (var files = Files.list(archive)) {
            Path saved =
                    files.filter(p -> p.toString().endsWith(".gogame")).findFirst().orElseThrow();
            check(
                    RecordStore.load(saved).phase.equals("FINISHED"),
                    "server auto-archives a readable completed game");
        }
    }

    private static void expiry() throws Exception {
        try (GameServer server =
                new GameServer(
                        "127.0.0.1", 0, OUTPUT.resolve("expiry-records"), Duration.ofSeconds(1))) {
            server.start();
            try (Peer a = new Peer(server.port());
                    Peer b = new Peer(server.port())) {
                a.hello("expiry-a", "");
                Frame bWelcome = b.hello("expiry-b", "");
                a.send("CREATE_ROOM", Map.of("title", "expiry", "size", "9", "komi", "7.5"));
                String id = a.state(f -> f.get("phase").equals("WAITING")).get("roomId");
                b.send("JOIN_ROOM", Map.of("roomId", id));
                b.state(f -> f.get("role").equals("2"));
                a.send("READY", Map.of());
                a.state(f -> f.get("blackReady").equals("true"));
                b.send("READY", Map.of());
                a.state(f -> f.get("phase").equals("PLAYING"));
                b.close();
                Frame ended = a.state(f -> f.get("phase").equals("FINISHED"));
                check(
                        ended.get("winner").equals("1") && ended.get("result").contains("重连超时"),
                        "disconnect grace expiry forfeits the missing player");
                try (Peer stale = new Peer(server.port())) {
                    stale.send(
                            "HELLO",
                            Map.of("nickname", "expiry-b", "token", bWelcome.get("token")));
                    check(
                            stale.await(f -> f.type().equals("ERROR"))
                                    .get("message")
                                    .contains("失效"),
                            "expired token cannot reclaim a finished seat");
                }
            }
        }
    }

    private static void boardRendering() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        BoardPanel panel = new BoardPanel();
                        int[] stones = new int[81];
                        stones[20] = 1;
                        stones[60] = 2;
                        BitSet dead = new BitSet();
                        dead.set(60);
                        panel.position(9, stones, dead, new Game.Move(1, 2, 2), 2, false);
                        panel.setSize(720, 720);
                        BufferedImage image =
                                new BufferedImage(720, 720, BufferedImage.TYPE_INT_ARGB);
                        var graphics = image.createGraphics();
                        panel.paint(graphics);
                        graphics.dispose();
                        ImageIO.write(image, "png", OUTPUT.resolve("board-preview.png").toFile());
                        check(
                                (image.getRGB(360, 360) >>> 24) != 0,
                                "board paints its grid and wood surface");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static final class Peer implements AutoCloseable {
        final Socket socket;
        final BlockingQueue<Frame> incoming = new LinkedBlockingQueue<>();
        final OutputStream out;
        long sequence;

        Peer(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10000);
            socket.setTcpNoDelay(true);
            out = socket.getOutputStream();
            Thread reader =
                    new Thread(
                            () -> {
                                try {
                                    while (!socket.isClosed())
                                        incoming.add(Protocol.read(socket.getInputStream()));
                                } catch (IOException ignored) {
                                }
                            },
                            "test-peer");
            reader.setDaemon(true);
            reader.start();
        }

        Frame hello(String nickname, String token) throws Exception {
            send("HELLO", Map.of("nickname", nickname, "token", token));
            return await(f -> f.type().equals("WELCOME"));
        }

        long send(String type, Map<String, String> fields) throws Exception {
            long id = ++sequence;
            write(new Frame(type, id, fields));
            return id;
        }

        long action(String type, long revision, Map<String, String> extra) throws Exception {
            Map<String, String> fields = new HashMap<>(extra);
            fields.put("revision", "" + revision);
            return send(type, fields);
        }

        void write(Frame frame) throws Exception {
            Protocol.write(out, frame);
        }

        Frame revision(long revision) throws Exception {
            return state(f -> f.number("revision") == revision);
        }

        Frame state(Predicate<Frame> match) throws Exception {
            return await(f -> f.type().equals("STATE") && match.test(f));
        }

        Frame await(Predicate<Frame> predicate) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            Frame last = null;
            while (System.nanoTime() < deadline) {
                Frame frame = incoming.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    last = frame;
                    if (predicate.test(frame)) return frame;
                }
            }
            throw new AssertionError(
                    "Timed out waiting for server frame. Last: "
                            + (last == null ? "none" : last.type() + " " + last.fields()));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private static void run(String label, Checked test) throws Exception {
        test.run();
        System.out.println("OK  " + label);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void fails(Checked action) throws Exception {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException | IOException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }
}
