package go.client;

import static go.client.AppTheme.BG;
import static go.client.AppTheme.FONT;
import static go.client.AppTheme.GREEN;
import static go.client.AppTheme.INK;
import static go.client.AppTheme.LINE;
import static go.client.AppTheme.MUTED;
import static go.client.AppTheme.label;

import go.core.Game;
import go.core.GameView;
import go.core.RecordStore;
import go.net.Protocol.Frame;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;

/** Native Swing desktop application. UI state is owned exclusively by the EDT. */
@SuppressWarnings("serial")
public final class GoApp extends JFrame {
    private enum Mode {
        LOGIN,
        ENTRANCE,
        HOME,
        ONLINE,
        REPLAY
    }

    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final CardLayout screenCards = new CardLayout();
    private final JPanel screens = new JPanel(screenCards);
    private final AppPages operations = new AppPages(this::showCurrentScreen);
    private final ClientProfiles profiles =
            new ClientProfiles(
                    Path.of(System.getProperty("go.client.profiles", "data/client.properties")));
    private final JLabel loginFeedback = label(" ", 12, MUTED);
    private final ClientEntry.Entrance entrance = new ClientEntry.Entrance(this::enterGame, this::openServerSettings);
    private final ClientEntry.Home landing =
            new ClientEntry.Home(
                    this::enterGame,
                    this::openProfile,
                    this::localSetup,
                    this::openRecord,
                    this::closeApp,
                    this::invitePlayer,
                    p -> {
                        if (requireConnection()) send("WATCH_ROOM", Map.of("roomId", p.roomId()));
                    });
    private final BoardPanel board = new BoardPanel();
    private final JLabel gameTitle = label("对局", 25, INK),
            blackLabel = label("黑方", 17, INK),
            whiteLabel = label("白方", 17, INK),
            turnLabel = label("等待开始", 21, GREEN),
            roleLabel = label("", 13, MUTED),
            scoreLabel = label("", 13, MUTED),
            captureLabel = label("", 13, MUTED);
    private final JTextArea statusArea = textArea(), moveArea = textArea();
    private final JButton readyButton = button("准备开始", true),
            passButton = button("停一手", true),
            resignButton = button("认输", false),
            confirmButton = button("确认结算", true),
            resumeButton = button("继续行棋", false),
            retryAiButton = button("重试 AI", true),
            undoButton = button("悔棋", false),
            acceptUndoButton = button("同意悔棋", true),
            rejectUndoButton = button("拒绝悔棋", false),
            requestScoreButton = button("发起结算", true),
            acceptScoreButton = button("同意结算", true),
            rejectScoreButton = button("不同意", false),
            countButton = button("点目", true),
            leaveButton = button("返回主页", false);
    private final JSlider replaySlider = new JSlider(0, 0, 0);
    private final JLabel replayLabel = label("第 0 手", 13, MUTED);
    private final JPanel replayBar = new JPanel(new BorderLayout(12, 0));
    private final JPanel gameActions = whitePanel();
    private final ThinkingCountdown aiCountdown = new ThinkingCountdown();
    private final ModelLoadingPanel modelLoading =
            new ModelLoadingPanel(() -> onlineAction("AI_RETRY", Map.of()), this::openHome);
    private final ExecutorService background =
            Executors.newFixedThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "go-background");
                        t.setDaemon(true);
                        return t;
                    });
    private final ClientRecordArchive recordArchive =
            new ClientRecordArchive(
                    Path.of(System.getProperty("go.client.records", "records/client")),
                    failure -> {
                        System.err.println(failure.getMessage());
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!this.disposed) error(failure.getMessage());
                                });
                    });
    private Mode mode = Mode.LOGIN;
    private GameView view;
    private boolean connected, connecting, pending, leavingRoom, disposed;
    private long connectionGeneration;
    private String token = "", tokenEndpoint = "";
    private String playerName = "";
    private boolean enterRequested, attemptedResume;
    private boolean profileEditing, playerHistory;
    private AppPages.Page profilePage;
    private ProfilePanel profilePanel;
    private ProfilePanel.Edit pendingProfile;
    private long profileRequest;
    private AppPages.Page serverSettingsPage;
    private NetworkClient network;
    private AiSetupPanel.Selection aiSelection;
    private AiSetupPanel aiSetup;
    private AppPages.Page aiPage, closeConfirmation, countPage, resultPage;
    private String shownResult = "";
    private JLabel countPoints;
    private JButton countEnd;
    private Map<String, String> modelCatalog = Map.of();
    private List<int[]> replayFrames = List.of();

    public GoApp() {
        super("棋间 · 在线围棋");
        try {
            profiles.load();
            profiles.configurePublishedServer(
                    Path.of(System.getProperty("go.client.config", "config/client.properties")));
        } catch (java.io.IOException e) {
            loginFeedback.setText("无法读取本机玩家资料，请检查 data/client.properties。");
        }
        entrance.server(profiles.server());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 760));
        setSize(1320, 880);
        setLocationRelativeTo(null);
        JPanel root = panel(new BorderLayout(0, 0));
        root.setOpaque(true);
        root.setBorder(new EmptyBorder(0, 28, 22, 28));
        root.add(header(), BorderLayout.NORTH);
        pages.setOpaque(false);
        pages.add(gamePage(), "GAME");
        pages.add(modelLoading, "MODEL_LOADING");
        root.add(pages, BorderLayout.CENTER);
        screens.add(
                ClientEntry.login(
                        profiles.players(), profiles.lastPlayer(), this::login, loginFeedback),
                "LOGIN");
        screens.add(landing, "HOME");
        screens.add(root, "PLAY");
        setContentPane(screens);
        screens.add(operations, "OPERATIONS");
        screens.add(entrance, "ENTRANCE");
        ToolTipManager.sharedInstance().setEnabled(false);
        getRootPane()
                .registerKeyboardAction(
                        e -> operations.back(),
                        KeyStroke.getKeyStroke("ESCAPE"),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        board.onClick(this::boardClicked);
        requestScoreButton.setName("requestScoreButton");
        acceptScoreButton.setName("acceptScoreButton");
        rejectScoreButton.setName("rejectScoreButton");
        countButton.setName("countButton");
        requestScoreButton.addActionListener(
                e -> {
                    if (view != null)
                        onlineAction(
                                view.text("scoreRequestId").isEmpty()
                                        ? "REQUEST_SCORE"
                                        : "CANCEL_SCORE",
                                view.text("scoreRequestId").isEmpty()
                                        ? Map.of()
                                        : Map.of("scoreRequestId", view.text("scoreRequestId")));
                });
        acceptScoreButton.addActionListener(e -> respondScore(true));
        rejectScoreButton.addActionListener(e -> respondScore(false));
        countButton.addActionListener(e -> openCount());
        readyButton.addActionListener(e -> onlineAction("READY", Map.of()));
        passButton.addActionListener(e -> gameAction("PASS", null));
        resignButton.addActionListener(
                e -> confirm("认输后本局将结束，确定认输吗？", () -> gameAction("RESIGN", null)));
        confirmButton.addActionListener(e -> gameAction("CONFIRM_SCORE", null));
        resumeButton.addActionListener(e -> gameAction("RESUME_PLAY", null));
        retryAiButton.addActionListener(e -> onlineAction("AI_RETRY", Map.of()));
        undoButton.setName("undoButton");
        acceptUndoButton.setName("acceptUndoButton");
        rejectUndoButton.setName("rejectUndoButton");
        undoButton.addActionListener(
                e -> {
                    if (view == null) return;
                    boolean ownRequest = view.text("undoRequestedBy").equals("" + view.role);
                    onlineAction(
                            ownRequest ? "CANCEL_UNDO" : "REQUEST_UNDO",
                            ownRequest
                                    ? Map.of("undoRequestId", view.text("undoRequestId"))
                                    : Map.of());
                });
        acceptUndoButton.addActionListener(e -> respondUndo(true));
        rejectUndoButton.addActionListener(e -> respondUndo(false));
        leaveButton.addActionListener(e -> openHome());
        replaySlider.addChangeListener(e -> renderReplay());
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        closeApp();
                    }
                });
        screenCards.show(screens, "LOGIN");
    }

    private JPanel header() {
        JPanel header = panel(new BorderLayout(20, 0));
        header.setBorder(new EmptyBorder(22, 0, 23, 0));
        JPanel brand = panel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        brand.add(new Logo());
        brand.add(label("棋间", 28, INK));
        header.add(brand, BorderLayout.WEST);
        return header;
    }

    private void login(String input) {
        if (mode != Mode.LOGIN) return;
        try {
            playerName = profiles.login(input);
            refreshPlayer();
            setTitle("棋间 · " + playerName);
            mode = Mode.ENTRANCE;
            entrance.status("", false);
            showScreen("ENTRANCE");
        } catch (IllegalArgumentException | java.io.IOException e) {
            loginFeedback.setText(
                    e instanceof java.io.IOException ? "玩家资料保存失败，请检查目录写入权限。" : e.getMessage());
        }
    }

    private void refreshPlayer() {
        if (playerName.isEmpty()) return;
        landing.player(playerName, profiles.avatar(playerName));
        setTitle("棋间 · " + playerName);
        if (profilePanel != null)
            profilePanel.stats(profiles.stats(playerName, false), profiles.stats(playerName, true));
    }

    private void openProfile() {
        if (playerName.isEmpty()) return;
        if (profilePage != null && operations.contains(profilePage)) return;
        profilePanel =
                new ProfilePanel(
                        playerName,
                        profiles.avatar(playerName),
                        profiles.stats(playerName, false),
                        profiles.stats(playerName, true),
                        this::saveProfile,
                        this::closeProfile);
        JPanel centered = panel(new GridBagLayout());
        centered.add(profilePanel);
        profilePage = showOperation("profilePage", "玩家资料", centered, this::closeProfile);
        if (connected && playerHistory) {
            profilePanel.message("正在同步历史战绩…");
            send("PLAYER_HISTORY", Map.of());
        }
    }

    private void closeProfile() {
        if (pendingProfile != null) return;
        operations.remove(profilePage);
        profilePage = null;
        profilePanel = null;
    }

    private void saveProfile(ProfilePanel.Edit edit) {
        if (pendingProfile != null) return;
        String name = ClientProfiles.validPlayer(edit.nickname());
        if (!name.equals(playerName) && profiles.players().contains(name)) {
            profilePanel.message("该昵称属于另一位已有玩家。");
            return;
        }
        if (!name.equals(playerName) && connecting) {
            profilePanel.message("正在进入服务器，请稍后再保存昵称；头像仍可单独修改。");
            return;
        }
        if (!name.equals(playerName) && connected) {
            if (!profileEditing) {
                profilePanel.message("此服务器需要更新后才能在线改名，头像可照常修改。");
                return;
            }
            requestProfile(edit);
            return;
        }
        applyProfile(edit);
    }

    private void requestProfile(ProfilePanel.Edit edit) {
        pendingProfile = edit;
        if (profilePanel != null) profilePanel.saving(true);
        if (profilePage != null) profilePage.busy(true);
        try {
            profileRequest = network.send("UPDATE_PROFILE", Map.of("nickname", edit.nickname()));
        } catch (IllegalStateException e) {
            profileSaveFailed(e.getMessage());
        }
    }

    private void applyProfile(ProfilePanel.Edit edit) {
        try {
            playerName = profiles.edit(playerName, edit.nickname(), edit.avatar());
            if (connected)
                tokenEndpoint =
                        profiles.server().host()
                                + ":"
                                + profiles.server().port()
                                + "/"
                                + playerName;
            refreshPlayer();
            pendingProfile = null;
            closeProfile();
        } catch (java.io.IOException | IllegalArgumentException e) {
            profileSaveFailed("保存失败：" + e.getMessage());
        }
    }

    private void profileSaveFailed(String message) {
        pendingProfile = null;
        if (profilePanel != null) {
            profilePanel.saving(false);
            profilePanel.message(message);
        }
        if (profilePage != null) profilePage.busy(false);
    }

    private void recordResults(Map<String, ClientProfiles.Result> results) {
        try {
            if (profiles.recordResults(playerName, results)) refreshPlayer();
        } catch (java.io.IOException e) {
            if (profilePanel != null) profilePanel.message("战绩保存失败，请检查玩家资料目录是否可写。");
        }
    }

    private void invitePlayer(ClientEntry.OnlinePlayer player) {
        if (requireConnection()) send("INVITE_PLAYER", Map.of("nickname", player.name()));
    }

    private void enterGame() {
        if (playerName.isEmpty() || connecting) return;
        if (connected) {
            if (mode == Mode.ONLINE && view != null) renderGame();
            else {
                mode = Mode.HOME;
                showScreen("HOME");
                send("LIST_ROOMS", Map.of());
            }
            return;
        }
        enterRequested = true;
        connect();
    }

    private void openServerSettings() {
        if (connecting || mode != Mode.ENTRANCE) return;
        if (serverSettingsPage != null && operations.contains(serverSettingsPage)) return;
        ServerSettingsPanel[] editor = new ServerSettingsPanel[1];
        editor[0] = new ServerSettingsPanel(profiles.server(), server -> {
            try {
                profiles.savePublishedServer(
                        Path.of(System.getProperty("go.client.config", "config/client.properties")), server);
            } catch (java.io.IOException e) {
                editor[0].message("服务器地址保存失败，请检查目录写入权限。");
                return;
            }
            ++connectionGeneration;
            if (network != null) network.close();
            network = null;
            connected = false;
            token = "";
            tokenEndpoint = "";
            entrance.server(server);
            operations.remove(serverSettingsPage);
            serverSettingsPage = null;
            entrance.status("未连接服务器", false);
            enterGame();
        });
        serverSettingsPage = showOperation("serverSettingsPage", "配置服务器地址", editor[0], null);
    }

    private JPanel gamePage() {
        JPanel game = panel(new BorderLayout(22, 12));
        JPanel title = panel(new BorderLayout());
        title.add(gameTitle, BorderLayout.WEST);
        game.add(title, BorderLayout.NORTH);
        JPanel boardArea = card(new BorderLayout());
        boardArea.add(board, BorderLayout.CENTER);
        replayBar.setOpaque(false);
        replayBar.setBorder(new EmptyBorder(8, 22, 16, 22));
        replaySlider.setOpaque(false);
        replaySlider.setBackground(BG);
        replayBar.add(replaySlider, BorderLayout.CENTER);
        JButton previous = button("‹", false), next = button("›", false);
        previous.addActionListener(e -> replaySlider.setValue(replaySlider.getValue() - 1));
        next.addActionListener(e -> replaySlider.setValue(replaySlider.getValue() + 1));
        JPanel replayButtons = panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        replayButtons.add(previous);
        replayButtons.add(next);
        replayButtons.add(replayLabel);
        replayBar.add(replayButtons, BorderLayout.WEST);
        boardArea.add(replayBar, BorderLayout.SOUTH);
        game.add(boardArea, BorderLayout.CENTER);
        JPanel side = card(new BorderLayout(0, 12));
        side.setPreferredSize(new Dimension(300, 680));
        side.setBorder(new EmptyBorder(22, 22, 20, 22));
        JPanel info = whitePanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        addLine(info, label("本 局 对 弈", 12, MUTED), 18);
        addLine(info, blackLabel, 14);
        addLine(info, whiteLabel, 20);
        info.add(separator());
        info.add(Box.createVerticalStrut(18));
        addLine(info, turnLabel, 8);
        addLine(info, roleLabel, 12);
        addLine(info, aiCountdown, 10);
        statusArea.setRows(4);
        statusArea.setFont(FONT.deriveFont(13f));
        statusArea.setForeground(MUTED);
        addLine(info, statusArea, 12);
        addLine(info, captureLabel, 8);
        addLine(info, scoreLabel, 14);
        gameActions.setLayout(new GridLayout(0, 2, 10, 10));
        gameActions.setMaximumSize(new Dimension(500, 150));
        addLine(info, gameActions, 18);
        side.add(info, BorderLayout.NORTH);
        moveArea.setFont(FONT.deriveFont(12f));
        moveArea.setForeground(MUTED);
        JScrollPane moves = new JScrollPane(moveArea);
        moves.setBorder(
                new CompoundBorder(
                        new MatteBorder(1, 0, 0, 0, LINE), new EmptyBorder(12, 0, 0, 0)));
        moves.setPreferredSize(new Dimension(250, 150));
        side.add(moves, BorderLayout.CENTER);
        side.add(leaveButton, BorderLayout.SOUTH);
        game.add(side, BorderLayout.EAST);
        return game;
    }

    private void localSetup() {
        if (!requireConnection()) return;
        if (mode == Mode.ONLINE && view != null && !view.roomId.isEmpty()) {
            error("请先退出当前房间，再创建人机对局。");
            return;
        }
        if (aiPage != null && operations.contains(aiPage)) return;
        send("AI_CATALOG", Map.of());
        aiSetup =
                new AiSetupPanel(
                        aiSelection,
                        modelCatalog,
                        () -> connected,
                        selection -> {
                            if (!connected) {
                                error("连接已断开，请重新进入游戏。");
                                return;
                            }
                            aiSelection = selection;
                            mode = Mode.HOME;
                            leavingRoom = false;
                            operations.remove(aiPage);
                            aiPage = null;
                            aiSetup = null;
                            // Legacy servers require this wire field; it is fixed at zero and has
                            // no UI setting.
                            send(
                                    "CREATE_AI_ROOM",
                                    Map.of(
                                            "title",
                                            playerName + "的人机棋室",
                                            "aiMode",
                                            "katago",
                                            "aiDifficulty",
                                            selection.difficulty().name(),
                                            "aiModel",
                                            selection.profile().name(),
                                            "aiBudget",
                                            selection.budget().name(),
                                            "size",
                                            "" + selection.size(),
                                            "color",
                                            "" + selection.color(),
                                            "komi",
                                            "0"));
                        });
        aiPage = showOperation("aiSetupPage", "人机对战", aiSetup, this::closeAiSetup);
    }

    private void closeAiSetup() {
        operations.remove(aiPage);
        aiPage = null;
        aiSetup = null;
    }

    private boolean requireConnection() {
        if (connected) return true;
        if (mode != Mode.ONLINE) mode = Mode.ENTRANCE;
        entrance.status("未连接服务器，点击屏幕重试", false);
        showScreen("ENTRANCE");
        return false;
    }

    private void prepareSwitch(Runnable continued) {
        if (mode == Mode.ONLINE && view != null && !view.roomId.isEmpty()) {
            String warning =
                    view.role > 0 && !view.phase.equals("WAITING") && !view.phase.equals("FINISHED")
                            ? "退出当前对局将判负，确定继续吗？"
                            : "确定退出当前房间吗？";
            String roomId = view.roomId;
            Runnable leave =
                    () -> {
                        if (!connected) {
                            error("当前连接已断开，请先重连并退出房间，或等待 60 秒后席位释放。");
                            return;
                        }
                        if (view == null || !view.roomId.equals(roomId)) {
                            error("当前房间已发生变化，请返回后重试。");
                            return;
                        }
                        leavingRoom = true;
                        send("LEAVE_ROOM", Map.of());
                        continued.run();
                    };
            if (view.flag("aiPreparing")) leave.run();
            else confirm(warning, leave);
        } else {
            continued.run();
        }
    }

    private void openHome() {
        // A disconnected online game stays visible until reconnection; only the page changes.
        if (!connected) {
            requireConnection();
            return;
        }
        prepareSwitch(
                () -> {
                    mode = Mode.HOME;
                    view = null;
                    enterRequested = false;
                    landing.status("", false);
                    showScreen("HOME");
                    if (connected) send("LIST_ROOMS", Map.of());
                });
    }

    private void connect() {
        if (connecting || connected || playerName.isEmpty()) return;
        ClientProfiles.Server selected = profiles.server();
        String host = selected.host(), nickname = playerName;
        int port = selected.port();
        if (network != null) network.close();
        long generation = ++connectionGeneration;
        String endpoint = host + ":" + port + "/" + nickname;
        String reconnect =
                endpoint.equals(tokenEndpoint) ? token : profiles.sessionToken(selected, nickname);
        attemptedResume = !reconnect.isEmpty();
        if (reconnect.isEmpty() && mode == Mode.ONLINE) {
            mode = Mode.HOME;
            view = null;
        }
        connecting = true;
        entrance.status("正在连接服务器…", true);
        background.execute(
                () -> {
                    try {
                        NetworkClient client =
                                new NetworkClient(
                                        host,
                                        port,
                                        frame ->
                                                SwingUtilities.invokeLater(
                                                        () -> {
                                                            if (!disposed
                                                                    && generation
                                                                            == connectionGeneration)
                                                                received(frame);
                                                        }),
                                        message ->
                                                SwingUtilities.invokeLater(
                                                        () -> {
                                                            if (!disposed
                                                                    && generation
                                                                            == connectionGeneration)
                                                                connectionLost(message);
                                                        }));
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (disposed || generation != connectionGeneration) {
                                        client.close();
                                        return;
                                    }
                                    network = client;
                                    tokenEndpoint = endpoint;
                                    client.start(
                                            nickname,
                                            reconnect,
                                            profiles.playerId(playerName),
                                            profiles.aliases(playerName));
                                });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!disposed && generation == connectionGeneration)
                                        connectionLost("连接失败：" + e.getMessage());
                                });
                    }
                });
    }

    private void received(Frame frame) {
        try {
            switch (frame.type()) {
                case "WELCOME" -> {
                    token = frame.get("token");
                    connected = true;
                    connecting = false;
                    leavingRoom = false;
                    attemptedResume = false;
                    profileEditing = frame.get("profileEditing").equals("true");
                    playerHistory = frame.get("playerHistory").equals("true");
                    try {
                        profiles.rememberSession(profiles.server(), playerName, token);
                    } catch (java.io.IOException e) {
                        System.err.println("本机重连资料保存失败：" + e.getMessage());
                    }
                    landing.status("", false);
                    if (enterRequested) {
                        enterRequested = false;
                        if (mode != Mode.ONLINE) {
                            mode = Mode.HOME;
                            view = null;
                        }
                        showScreen("HOME");
                    }
                    if (playerHistory) send("PLAYER_HISTORY", Map.of());
                    if (profileEditing && !frame.get("nickname").equals(playerName))
                        requestProfile(
                                new ProfilePanel.Edit(playerName, profiles.avatar(playerName)));
                }
                case "PROFILE_UPDATED" -> {
                    if (pendingProfile != null && frame.requestId() == profileRequest)
                        applyProfile(pendingProfile);
                }
                case "GAME_RESULT" -> {
                    recordResults(
                            Map.of(
                                    frame.get("gameId"),
                                    new ClientProfiles.Result(
                                            Boolean.parseBoolean(frame.get("machine")),
                                            frame.integer("outcome"))));
                    if (!frame.get("board").isEmpty())
                        recordArchive.save(new GameView(frame.fields()));
                }
                case "PLAYER_HISTORY" -> {
                    Map<String, ClientProfiles.Result> results = new LinkedHashMap<>();
                    for (String row : frame.get("results").split("\n"))
                        if (!row.isEmpty()) {
                            String[] cells = row.split("\t", -1);
                            if (cells.length != 3) throw new IllegalArgumentException("战绩数据无效");
                            results.put(
                                    cells[0],
                                    new ClientProfiles.Result(
                                            Boolean.parseBoolean(cells[1]),
                                            Integer.parseInt(cells[2])));
                        }
                    recordResults(results);
                    if (frame.get("complete").equals("true") && profilePanel != null)
                        profilePanel.message(" ");
                }
                case "HISTORY_ERROR" -> {
                    if (profilePanel != null) profilePanel.message(frame.get("message"));
                }
                case "AI_CATALOG" -> {
                    modelCatalog = frame.fields();
                    if (aiSetup != null) aiSetup.catalog(modelCatalog);
                }
                case "INVITATION" -> {
                    String roomId = frame.get("roomId");
                    confirm(
                            frame.get("from") + " 邀请你对弈，是否接受？",
                            () -> {
                                if (requireConnection())
                                    send("JOIN_ROOM", Map.of("roomId", roomId));
                            });
                }
                case "ROOM_LIST" -> {
                    List<ClientEntry.OnlinePlayer> players = new ArrayList<>();
                    for (String row : frame.get("players").split("\n")) {
                        String[] cells = row.split("\t", -1);
                        if (cells.length == 3)
                            players.add(
                                    new ClientEntry.OnlinePlayer(
                                            cells[0], cells[1], Boolean.parseBoolean(cells[2])));
                    }
                    landing.players(players);
                }
                case "STATE" -> {
                    // Count before ignoring a final state during a leave; replay and spectators
                    // never count.
                    GameView next = new GameView(frame.fields());
                    if (mode != Mode.REPLAY) recordArchive.save(next);
                    if (mode != Mode.REPLAY
                            && next.role > 0
                            && next.phase.equals("FINISHED")
                            && !next.text("gameId").isEmpty()) {
                        int winner = next.integer("winner");
                        recordResults(
                                Map.of(
                                        next.text("gameId"),
                                        new ClientProfiles.Result(
                                                !next.text("aiMode").isEmpty(),
                                                winner == 0 ? 0 : winner == next.role ? 1 : 2)));
                    }
                    if (leavingRoom || mode == Mode.REPLAY) return;
                    if (mode == Mode.ONLINE
                            && view != null
                            && view.roomId.equals(next.roomId)
                            && next.revision < view.revision) return;
                    mode = Mode.ONLINE;
                    view = next;
                    pending = false;
                    updateCount();
                    renderGame();
                }
                case "LEFT" -> {
                    leavingRoom = false;
                    pending = false;
                    if (mode == Mode.ONLINE) {
                        mode = Mode.HOME;
                        view = null;
                        showScreen("HOME");
                    }
                }
                case "ERROR" -> {
                    if (pendingProfile != null && frame.requestId() == profileRequest) {
                        profileSaveFailed(frame.get("message"));
                        return;
                    }
                    pending = false;
                    if (!connected) {
                        try {
                            profiles.rememberSession(profiles.server(), playerName, "");
                        } catch (java.io.IOException ignored) {
                        }
                        token = "";
                        if (network != null) network.close();
                        if (attemptedResume) {
                            attemptedResume = false;
                            connecting = false;
                            enterRequested = true;
                            connect();
                            return;
                        }
                        connectionLost(frame.get("message") + "；点击此处可重试");
                    }
                    error(frame.get("message"));
                    if (view != null && mode == Mode.ONLINE) renderGame();
                }
                default -> {}
            }
        } catch (RuntimeException e) {
            connectionLost("收到无效的棋局数据：" + e.getMessage());
            if (network != null) network.close();
        }
    }

    private void connectionLost(String message) {
        if (pendingProfile != null) profileSaveFailed("连接已断开，请重新进入服务器后保存。");
        profileEditing = false;
        playerHistory = false;
        connected = false;
        connecting = false;
        pending = false;
        enterRequested = false;
        leavingRoom = false;
        ++connectionGeneration;
        if (network != null) network.close();
        network = null;

        modelCatalog = Map.of();
        if (aiSetup != null) aiSetup.catalog(modelCatalog);
        if (mode != Mode.ONLINE) {
            mode = Mode.ENTRANCE;
            view = null;
        }
        AppPages.Page oldCount = countPage;
        countPage = null;
        operations.remove(oldCount);
        AppPages.Page oldResult = resultPage;
        resultPage = null;
        if (oldResult != null) shownResult = "";
        operations.remove(oldResult);
        landing.players(List.of());
        entrance.status("未连接服务器 · " + message + " · 点击屏幕重试", false);
        showScreen("ENTRANCE");
    }

    private void send(String type, Map<String, String> fields) {
        if (network == null || !connected) {
            requireConnection();
            return;
        }
        try {
            network.send(type, fields);
        } catch (IllegalStateException e) {
            connectionLost(e.getMessage());
        }
    }

    private void onlineAction(String type, Map<String, String> extra) {
        if (pending || view == null || !connected) return;
        Map<String, String> data = new LinkedHashMap<>(extra);
        data.put("revision", "" + view.revision);
        pending = true;
        send(type, data);
        renderGame();
    }

    private void boardClicked(Point point) {
        if (view == null || mode == Mode.REPLAY) return;
        if (view.phase.equals("SCORING")) {
            if (mode == Mode.ONLINE && view.text("aiMode").isEmpty())
                onlineAction("MARK_DEAD", Map.of("x", "" + point.x, "y", "" + point.y));
        } else gameAction("MOVE", point);
    }

    private void gameAction(String type, Point point) {
        if (mode != Mode.ONLINE || !requireConnection()) return;
        onlineAction(type, point == null ? Map.of() : Map.of("x", "" + point.x, "y", "" + point.y));
    }

    private void respondUndo(boolean accept) {
        if (view != null)
            onlineAction(
                    "RESPOND_UNDO",
                    Map.of("undoRequestId", view.text("undoRequestId"), "accept", "" + accept));
    }

    private void renderGame() {
        if (view == null || !connected) {
            showScreen("HOME");
            return;
        }
        if (mode == Mode.ONLINE && view.flag("aiPreparing")) {
            aiCountdown.state(false, "", "", -1);
            modelLoading.state(
                    view.text("gameId"), view.text("aiError"), view.flag("paused"), view.role > 0);
            showScreen("PLAY");
            cards.show(pages, "MODEL_LOADING");
            return;
        }
        showScreen("PLAY");
        cards.show(pages, "GAME");
        boolean replay = mode == Mode.REPLAY,
                online = mode == Mode.ONLINE,
                ai = !view.text("aiMode").isEmpty();
        boolean thinking = view.flag("aiThinking");
        boolean playing = view.phase.equals("PLAYING"),
                scoring = view.phase.equals("SCORING"),
                finished = view.phase.equals("FINISHED");
        boolean waiting = view.phase.equals("WAITING"), player = view.role > 0;
        boolean undoPending = !view.text("undoRequestId").isEmpty();
        boolean controlsAvailable = connected && !pending && !view.flag("paused") && !view.flag("scoreAnalyzing");
        boolean scorePending = !view.text("scoreRequestId").isEmpty();
        boolean ownScore = scorePending && view.text("scoreRequestedBy").equals("" + view.role);
        boolean available = controlsAvailable && !undoPending && !scorePending;
        aiCountdown.state(
                !replay && thinking && !view.flag("paused"),
                view.text("aiStage"),
                view.text("aiJobId"),
                view.text("aiRemainingMillis").isEmpty()
                        ? -1
                        : Long.parseLong(view.text("aiRemainingMillis")));
        boolean myTurn = player && view.turn == view.role;
        gameTitle.setText(replay ? "棋谱回放" : view.text("title"));
        blackLabel.setText(
                "●  "
                        + displayName(view.black)
                        + (waiting && view.flag("blackReady") ? " · 已准备" : ""));
        whiteLabel.setText(
                "○  "
                        + displayName(view.white)
                        + (waiting && view.flag("whiteReady") ? " · 已准备" : ""));
        turnLabel.setText(turnText(replay));
        roleLabel.setText(
                replay
                        ? "只读回放"
                        : player
                                ? "你执" + (view.role == 1 ? "黑 · 先手" : "白 · 后手")
                                : "观战中 · " + view.text("watchers") + " 人");
        String status = gameStatusText(replay);
        statusArea.setText(status);
        statusArea.setCaretPosition(0);
        captureLabel.setText("提子  黑 " + view.blackCaptures + "  /  白 " + view.whiteCaptures);
        scoreLabel.setText(
                scoring || finished
                        ? String.format(
                                Locale.ROOT,
                                "面积  黑 %.1f  /  白 %.1f",
                                view.blackScore,
                                view.whiteScore)
                        : "第 " + view.moves.size() + " 手  ·  面积计分");
        if (view.text("scoreMethod").equals("katago")) scoreLabel.setText(view.result);
        readyButton.setVisible(online && waiting);
        readyButton.setEnabled(player && available && waiting);
        boolean ready = view.role == 1 ? view.flag("blackReady") : view.flag("whiteReady");
        readyButton.setText(ready ? "取消准备" : "准备开始");
        passButton.setVisible(!replay && playing);
        passButton.setEnabled(available && playing && myTurn && !thinking);
        resignButton.setVisible(!replay && !waiting && !finished);
        resignButton.setEnabled(player && controlsAvailable);
        boolean ownUndoRequest = undoPending && view.text("undoRequestedBy").equals("" + view.role);
        undoButton.setVisible(
                online && player && (playing || scoring) && (!undoPending || ownUndoRequest));
        undoButton.setText(ownUndoRequest ? "取消悔棋" : "悔棋");
        undoButton.setEnabled(
                controlsAvailable && !scorePending && (ownUndoRequest || view.flag("canUndo")));
        boolean answerUndo = online && player && undoPending && !ownUndoRequest;
        acceptUndoButton.setVisible(answerUndo);
        rejectUndoButton.setVisible(answerUndo);
        acceptUndoButton.setEnabled(controlsAvailable);
        rejectUndoButton.setEnabled(controlsAvailable);
        requestScoreButton.setVisible(
                online && player && !ai && playing && (!scorePending || ownScore));
        requestScoreButton.setText(ownScore ? "取消结算" : "发起结算");
        requestScoreButton.setEnabled(controlsAvailable && !undoPending);
        acceptScoreButton.setVisible(online && player && scorePending && !ownScore);
        rejectScoreButton.setVisible(acceptScoreButton.isVisible());
        acceptScoreButton.setEnabled(controlsAvailable);
        rejectScoreButton.setEnabled(controlsAvailable);
        countButton.setVisible(online && player && ai && (playing || scoring));
        countButton.setEnabled(available && !thinking && (scoring || myTurn));
        confirmButton.setVisible(!replay && !ai && scoring);
        confirmButton.setEnabled(
                available && player && scoring && (view.confirmed & view.role) == 0);
        confirmButton.setText(
                view.role > 0 && (view.confirmed & view.role) != 0 ? "已确认，等对方" : "确认结算");
        resumeButton.setVisible(!replay && !ai && scoring);
        resumeButton.setEnabled(available && player && scoring);
        retryAiButton.setVisible(
                online
                        && ai
                        && playing
                        && player
                        && !myTurn
                        && !thinking
                        && !view.text("aiError").isEmpty());
        retryAiButton.setEnabled(available);
        leaveButton.setText(replay || finished ? "返回主页" : "退出房间");
        gameActions.removeAll();
        for (JButton action :
                new JButton[] {
                    readyButton,
                    passButton,
                    requestScoreButton,
                    acceptScoreButton,
                    rejectScoreButton,
                    countButton,
                    undoButton,
                    acceptUndoButton,
                    rejectUndoButton,
                    resignButton,
                    confirmButton,
                    resumeButton,
                    retryAiButton
                }) if (action.isVisible()) gameActions.add(action);
        gameActions.revalidate();
        replayBar.setVisible(replay);
        renderMoveHistory();
        if (replay) renderReplay();
        else {
            Game.Move last = view.moves.isEmpty() ? null : view.moves.get(view.moves.size() - 1);
            board.position(
                    view.size,
                    view.board,
                    view.dead,
                    last,
                    view.turn,
                    available && player && ((playing && myTurn && !thinking) || (scoring && !ai)));
        }
        revalidate();
        repaint();
        if (online && finished && !operations.active()) showResult();
    }

    private String turnText(boolean replay) {
        if (replay) return "回看每一步";
        return switch (view.phase) {
            case "WAITING" -> "等待双方准备";
            case "FINISHED" -> "对局结束";
            case "SCORING" -> "确认终局";
            default -> {
                if (view.flag("aiThinking")) yield "AI 正在计算…";
                if (!view.text("aiMode").isEmpty() && !view.text("aiError").isEmpty())
                    yield "AI 暂不可用";
                yield Game.colorName(view.turn) + "行棋";
            }
        };
    }

    private String gameStatusText(boolean replay) {
        boolean ai = !view.text("aiMode").isEmpty();
        boolean thinking = view.flag("aiThinking");
        boolean finished = view.phase.equals("FINISHED");
        boolean waiting = view.phase.equals("WAITING");
        boolean scoring = view.phase.equals("SCORING");
        boolean player = view.role > 0;
        boolean scorePending = !view.text("scoreRequestId").isEmpty();
        boolean undoPending = !view.text("undoRequestId").isEmpty();
        boolean ownScore = scorePending && view.text("scoreRequestedBy").equals("" + view.role);
        String status;
        if (replay) status = view.result.isEmpty() ? "使用下方滑块或箭头逐步回看，回放已包含提子变化。" : view.result;
        else if (view.flag("paused"))
            status = "棋手掉线，对局暂停。重连剩余约 " + view.text("reconnectSeconds") + " 秒。";
        else if (pending) status = "等待服务器确认……";
        else if (finished) status = view.result;
        else if (view.flag("scoreAnalyzing")) status = "KataGo 正在分析死活和形势，请等待计分预览。";
        else if (scorePending) status = ownScore ? "已发起结算，等待对方同意；可以取消申请。" : "对方申请结算当前盘面。不同意则继续对局。";
        else if (undoPending)
            status =
                    view.text("undoRequestedBy").equals("" + view.role)
                            ? "已申请悔棋，等待对方同意。你也可以取消申请。"
                            : "对方申请撤回到其上次落子前，请选择同意或拒绝。";
        else if (waiting) status = player ? "双方进入房间并点击准备后，黑方先行。" : "正在等待棋手准备。";
        else if (scoring)
            status = ai ? "点击点目，由 KataGo 分析死活与分差；确认采用结果后才结束对局。有争议可继续行棋。" : "点击整块棋子标记或取消死子。双方确认后结算；有争议可继续行棋。";
        else if (ai)
            status =
                    !view.text("aiError").isEmpty()
                            ? view.text("aiError")
                            : (thinking ? "正在比较后续变化，完成后自动落子。\n" : "")
                                    + view.text("aiRank")
                                    + (view.size == 19 ? "（未定级）。" : "（19 路参考）。")
                                    + "\nAI 计算及落子校验均由服务端处理。";
        else status = "双方连续停一手进入结算。禁止自杀落子和重复棋盘局面。";
        if (!view.text("undoMessage").isEmpty() && !undoPending && !finished)
            status = view.text("undoMessage") + "\n" + status;
        if (!view.text("scoreMessage").isEmpty() && !scorePending && !finished)
            status = view.text("scoreMessage") + "\n" + status;
        return status;
    }

    private void renderMoveHistory() {
        StringBuilder history = new StringBuilder("落子记录\n");
        int start = Math.max(0, view.moves.size() - 80);
        for (int i = start; i < view.moves.size(); i++) {
            Game.Move move = view.moves.get(i);
            history.append(
                    String.format(
                            Locale.ROOT,
                            "%3d  %s  %s%n",
                            i + 1,
                            move.color() == 1 ? "黑" : "白",
                            move.pass() ? "停一手" : coordinate(move, view.size)));
        }
        moveArea.setText(history.toString());
        moveArea.setCaretPosition(moveArea.getDocument().getLength());
    }

    private void respondScore(boolean accept) {
        if (view != null)
            onlineAction(
                    "RESPOND_SCORE",
                    Map.of("scoreRequestId", view.text("scoreRequestId"), "accept", "" + accept));
    }

    private void openCount() {
        if (view == null
                || pending
                || view.role == 0
                || view.text("aiMode").isEmpty()
                || view.flag("aiThinking")) return;
        if (countPage != null && operations.contains(countPage)) return;
        GameView counted = view;
        JPanel content = panel(new BorderLayout(0, 24));
        JLabel points = label("正在分析死活和形势…", 25, GREEN);
        countPoints = points;
        points.setName("countScores");
        points.setHorizontalAlignment(SwingConstants.CENTER);
        content.add(points, BorderLayout.CENTER);
        JLabel note = label("KataGo 判断死活；未收官时为形势估算。确认采用结果才会结束对局。", 14, MUTED);
        content.add(note, BorderLayout.NORTH);
        JButton end = button("确认采用结果并结束", true);
        countEnd = end;
        end.setEnabled(false);
        end.setName("endAiGameButton");
        content.add(end, BorderLayout.SOUTH);
        countPage =
                showOperation(
                        "countPage",
                        "点目",
                        content,
                        () -> {
                            AppPages.Page old = countPage;
                            countPage = null;
                            operations.remove(old);
                        });
        end.addActionListener(
                e -> {
                    if (view == null
                            || !view.roomId.equals(counted.roomId)
                            || view.revision != counted.revision) {
                        error("棋局已更新，请返回后重新点目。");
                        return;
                    }
                    if (!requireConnection()) return;
                    AppPages.Page old = countPage;
                    countPage = null;
                    operations.remove(old);
                    onlineAction("END_AI_GAME", Map.of());
                });
        onlineAction("ANALYZE_SCORE", Map.of());
    }

    private void updateCount() {
        if (countPage == null || !operations.contains(countPage) || view == null) return;
        String lead = view.text("assessedLead");
        countEnd.setEnabled(!view.flag("scoreAnalyzing") && !lead.isEmpty() && connected);
        if (view.flag("scoreAnalyzing")) countPoints.setText("正在分析死活和形势…");
        else if (!view.text("scoreError").isEmpty()) countPoints.setText(view.text("scoreError"));
        else if (lead.isEmpty()) countPoints.setText("棋盘已变化，请返回后重新点目");
        else {
            double margin = Double.parseDouble(lead);
            int dead = BitSet.valueOf(java.util.Base64.getDecoder().decode(view.text("assessedDead"))).cardinality();
            countPoints.setText(String.format(Locale.ROOT, "KataGo：%s · 判定死子 %d 颗",
                    margin == 0 ? "双方持平" : (margin > 0 ? "黑方" : "白方") + "领先 "
                            + String.format(Locale.ROOT, "%.1f", Math.abs(margin)) + " 点", dead));
        }
    }

    private void showResult() {
        String id = view.text("gameId");
        if (id.isEmpty()) id = view.roomId;
        if (id.equals(shownResult)) return;
        shownResult = id;
        int winner = view.integer("winner");
        JPanel content = panel(new BorderLayout(0, 24)),
                summary = panel(new GridLayout(0, 1, 0, 18));
        JLabel title = label(winner == 0 ? "和棋" : Game.colorName(winner) + "胜", 38, GREEN);
        title.setName("settlementWinner");
        title.setHorizontalAlignment(SwingConstants.CENTER);
        summary.add(title);
        JLabel points =
                label(
                        String.format(
                                Locale.ROOT,
                                "黑方 %.1f  /  白方 %.1f",
                                view.blackScore,
                                view.whiteScore),
                        22,
                        INK);
        points.setHorizontalAlignment(SwingConstants.CENTER);
        if (view.text("scoreMethod").equals("katago")) points.setText("已采用 KataGo 死活与形势分析结果");
        summary.add(points);
        JLabel reason = label(view.result, 15, MUTED);
        reason.setHorizontalAlignment(SwingConstants.CENTER);
        summary.add(reason);
        JPanel centered = panel(new GridBagLayout());
        centered.add(summary);
        content.add(centered, BorderLayout.CENTER);
        JButton home = button("返回主页", true);
        home.setName("settlementHomeButton");
        content.add(home, BorderLayout.SOUTH);
        resultPage = showOperation("settlementPage", "对局结算", content, this::leaveResult);
        home.addActionListener(e -> leaveResult());
    }

    private void leaveResult() {
        if (!requireConnection()) return;
        leavingRoom = true;
        send("LEAVE_ROOM", Map.of());
        mode = Mode.HOME;
        view = null;
        AppPages.Page old = resultPage;
        resultPage = null;
        operations.remove(old);
        showScreen("HOME");
    }

    private void openRecord() {
        if (!requireConnection()) return;
        RecordFilePanel[] browser = new RecordFilePanel[1];
        AppPages.Page[] page = new AppPages.Page[1];
        browser[0] =
                new RecordFilePanel(
                        recordDirectory(),
                        source -> {
                            browser[0].busy(true);
                            // Validate first; cancelling or opening an invalid record never leaves
                            // a live game.
                            background.execute(
                                    () -> {
                                        try {
                                            GameView loaded = RecordStore.load(source);
                                            List<int[]> frames = loaded.replayBoards();
                                            SwingUtilities.invokeLater(
                                                    () -> {
                                                        if (disposed
                                                                || !operations.contains(page[0]))
                                                            return;
                                                        browser[0].busy(false);
                                                        if (!connected) {
                                                            browser[0].message(
                                                                    "连接已断开，请返回首页重新进入游戏。");
                                                            return;
                                                        }
                                                        prepareSwitch(
                                                                () -> {
                                                                    mode = Mode.REPLAY;
                                                                    view = loaded;
                                                                    replayFrames = frames;
                                                                    replaySlider.setMaximum(
                                                                            frames.size() - 1);
                                                                    replaySlider.setValue(0);
                                                                    operations.remove(page[0]);
                                                                    renderGame();
                                                                });
                                                    });
                                        } catch (Exception failure) {
                                            SwingUtilities.invokeLater(
                                                    () -> {
                                                        if (!disposed
                                                                && operations.contains(page[0])) {
                                                            browser[0].busy(false);
                                                            browser[0].message(
                                                                    "无法打开棋谱："
                                                                            + failure.getMessage());
                                                        }
                                                    });
                                        }
                                    });
                        });
        page[0] = showOperation("recordOpenPage", "打开棋谱", browser[0], null);
    }

    private void renderReplay() {
        if (mode != Mode.REPLAY || view == null || replayFrames.isEmpty()) return;
        int index = Math.min(replaySlider.getValue(), replayFrames.size() - 1);
        Game.Move last = index == 0 ? null : view.moves.get(index - 1);
        // End-of-game dead marks are shown only at the final frame, not in earlier positions.
        BitSet marked = index == view.moves.size() ? view.dead : new BitSet();
        board.position(view.size, replayFrames.get(index), marked, last, 1, false);
        replayLabel.setText("第 " + index + " / " + view.moves.size() + " 手");
    }

    private Path recordDirectory() {
        Path records = recordArchive.directory();
        try {
            Files.createDirectories(records);
        } catch (java.io.IOException ignored) {
        }
        return records;
    }

    private void closeApp() {
        if (closeConfirmation != null && operations.contains(closeConfirmation)) return;
        if (mode == Mode.ONLINE
                && view != null
                && !view.phase.equals("FINISHED")
                && !view.flag("aiPreparing")) {
            closeConfirmation = confirm("确定关闭棋间吗？联机对局掉线超过 60 秒将判负。", this::disposeApp);
            return;
        }
        disposeApp();
    }

    private void disposeApp() {
        disposed = true;
        if (network != null) network.close();
        background.shutdownNow();
        recordArchive.close();
        dispose();
    }

    private void error(String message) {
        notice("操作提示", message);
    }

    private AppPages.Page showOperation(
            String name, String title, JComponent body, Runnable previous) {
        AppPages.Page[] page = new AppPages.Page[1];
        page[0] =
                new AppPages.Page(
                        name,
                        title,
                        body,
                        previous == null ? () -> operations.remove(page[0]) : previous);
        operations.push(page[0]);
        showScreen("OPERATIONS");
        return page[0];
    }

    private AppPages.Page confirm(String message, Runnable confirmed) {
        JPanel content = panel(new BorderLayout(0, 24));
        content.add(messageBody(message), BorderLayout.CENTER);
        JPanel buttons = panel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        JButton no = button("取消", false), yes = button("确认", true);
        no.setName("confirmNo");
        yes.setName("confirmYes");
        buttons.add(no);
        buttons.add(yes);
        content.add(buttons, BorderLayout.SOUTH);
        AppPages.Page page = showOperation("confirmPage", "确认操作", content, null);
        no.addActionListener(e -> operations.remove(page));
        yes.addActionListener(
                e -> {
                    operations.remove(page);
                    confirmed.run();
                });
        return page;
    }

    private void notice(String title, String message) {
        JPanel content = panel(new BorderLayout(0, 24));
        content.add(messageBody(message), BorderLayout.CENTER);
        JButton back = button("返回", true);
        back.setName("noticeReturn");
        content.add(back, BorderLayout.SOUTH);
        AppPages.Page page = showOperation("noticePage", title, content, null);
        back.addActionListener(e -> operations.remove(page));
    }

    private JComponent messageBody(String message) {
        JTextArea text = textArea();
        text.setText(message);
        text.setFont(FONT.deriveFont(18f));
        text.setBorder(new EmptyBorder(32, 28, 32, 28));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        return scroll;
    }

    private void showScreen(String name) {
        screenCards.show(screens, operations.active() ? "OPERATIONS" : name);
    }

    private void showCurrentScreen() {
        if (disposed) return;
        if (mode == Mode.LOGIN) {
            showScreen("LOGIN");
            return;
        }
        if (mode == Mode.ENTRANCE || !connected) {
            showScreen("ENTRANCE");
            return;
        }
        if (mode == Mode.HOME) {
            showScreen("HOME");
            return;
        }
        if (mode == Mode.ONLINE || mode == Mode.REPLAY) {
            renderGame();
            return;
        }
        showScreen("HOME");
    }

    private static String displayName(String name) {
        return name.isEmpty() ? "等待棋友…" : name;
    }

    private static String coordinate(Game.Move move, int size) {
        return "" + "ABCDEFGHJKLMNOPQRST".charAt(move.x()) + (size - move.y());
    }

    private static JPanel panel(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setBackground(BG);
        p.setOpaque(false);
        return p;
    }

    private static JPanel whitePanel() {
        JPanel p = new JPanel();
        p.setBackground(Color.WHITE);
        return p;
    }

    private static JPanel card(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setBackground(Color.WHITE);
        p.setBorder(new LineBorder(LINE));
        return p;
    }

    private static JTextArea textArea() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        return a;
    }

    private static JSeparator separator() {
        JSeparator s = new JSeparator();
        s.setForeground(LINE);
        s.setMaximumSize(new Dimension(1000, 1));
        return s;
    }

    private static void addLine(JPanel p, JComponent c, int bottom) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(c);
        p.add(Box.createVerticalStrut(bottom));
    }

    private static JButton button(String title, boolean primary) {
        return new AppButton(title, primary);
    }

    private static final class Logo extends JComponent {
        Logo() {
            setPreferredSize(new Dimension(38, 38));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(GREEN);
            g.fillRoundRect(0, 0, 38, 38, 10, 10);
            g.setColor(new Color(213, 226, 209, 120));
            for (int i = 10; i <= 28; i += 9) {
                g.drawLine(9, i, 29, i);
                g.drawLine(i, 9, i, 29);
            }
            g.setColor(INK);
            g.fillOval(12, 12, 14, 14);
            g.setColor(Color.WHITE);
            g.fillOval(21, 21, 10, 10);
            g.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(
                () -> {
                    UIManager.put("defaultFont", FONT);
                    for (Object key : Collections.list(UIManager.getDefaults().keys()))
                        if (UIManager.get(key) instanceof Font) UIManager.put(key, FONT);
                    UIManager.put("OptionPane.background", BG);
                    UIManager.put("Panel.background", BG);
                    new GoApp().setVisible(true);
                });
    }
}
