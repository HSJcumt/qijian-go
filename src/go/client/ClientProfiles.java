package go.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Local player names and server choices, independent of a network session. */
public final class ClientProfiles {
    public record Stats(int games, int wins, int draws) {
        public String winRate() {
            return String.format(Locale.ROOT, "%.1f%%", games == 0 ? 0 : 100.0 * wins / games);
        }
    }

    public record Result(boolean machine, int outcome) {
        public Result {
            if (outcome < 0 || outcome > 2) throw new IllegalArgumentException("胜负结果无效。");
        }
    }

    private record Player(
            String id, int avatar, List<String> aliases, Map<String, Result> results) {}

    public record Server(String name, String host, int port) {
        public Server {
            name = name.strip();
            host = host.strip();
            if (name.isEmpty()
                    || name.length() > 40
                    || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("请输入 1～40 字的服务器名称。");
            if (host.isEmpty()
                    || host.length() > 253
                    || host.chars()
                            .anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                    || host.contains("/")
                    || port < 1
                    || port > 65535)
                throw new IllegalArgumentException("请输入有效的服务器地址和 1～65535 的端口。");
        }

        public String endpoint() {
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }

        @Override
        public String toString() {
            return name + "  ·  " + endpoint();
        }
    }

    private final Path file;
    private Server publishedServer;
    private List<String> players = List.of();
    private List<Server> servers = List.of(new Server("本机服务器", "127.0.0.1", 9528));
    private String lastPlayer = "";
    private Map<String, String> sessions = Map.of();
    private Map<String, Player> details = Map.of();
    private int selected;

    public ClientProfiles(Path file) {
        this.file = file;
    }

    public void load() throws IOException {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("玩家资料格式无效。", e);
        }
        try {
            List<String> names = new ArrayList<>();
            Map<String, Player> loadedDetails = new LinkedHashMap<>();
            int playerCount = count(p, "players");
            for (int i = 0; i < playerCount; i++) {
                String name = validPlayer(p.getProperty("player." + i, ""));
                if (!names.contains(name)) names.add(name);
                String prefix = "player." + i + ".";
                String id = p.getProperty(prefix + "id", UUID.randomUUID().toString());
                UUID.fromString(id);
                int avatar = Integer.parseInt(p.getProperty(prefix + "avatar", "0"));
                validAvatar(avatar);
                Set<String> aliases = new LinkedHashSet<>();
                aliases.add(name);
                for (int j = 0, n = count(p, prefix + "aliases"); j < n; j++)
                    aliases.add(validPlayer(p.getProperty(prefix + "alias." + j, "")));
                Map<String, Result> results = new LinkedHashMap<>();
                for (int j = 0, n = count(p, prefix + "results"); j < n; j++) {
                    String entry = prefix + "result." + j + ".";
                    String key = p.getProperty(entry + "key", "");
                    if (key.isEmpty()) throw new IllegalArgumentException("对局编号无效。");
                    results.put(
                            key,
                            new Result(
                                    Boolean.parseBoolean(p.getProperty(entry + "machine")),
                                    Integer.parseInt(p.getProperty(entry + "outcome", "-1"))));
                }
                loadedDetails.put(
                        name, new Player(id, avatar, List.copyOf(aliases), Map.copyOf(results)));
            }
            List<Server> endpoints = new ArrayList<>();
            int serverCount = count(p, "servers");
            for (int i = 0; i < serverCount; i++) {
                String prefix = "server." + i + ".";
                endpoints.add(
                        new Server(
                                p.getProperty(prefix + "name", ""),
                                p.getProperty(prefix + "host", ""),
                                Integer.parseInt(p.getProperty(prefix + "port", "0"))));
            }
            String last = p.getProperty("lastPlayer", "");
            int choice = Integer.parseInt(p.getProperty("selectedServer", "0"));
            Map<String, String> savedSessions = new HashMap<>();
            for (String key : p.stringPropertyNames())
                if (key.startsWith("session.")) {
                    String value = p.getProperty(key);
                    if (value.length() > 128 || value.chars().anyMatch(Character::isISOControl))
                        throw new IllegalArgumentException("会话资料无效。");
                    savedSessions.put(key, value);
                }
            players = List.copyOf(names);
            lastPlayer = names.contains(last) ? last : "";
            if (!endpoints.isEmpty()) servers = List.copyOf(endpoints);
            selected = choice >= 0 && choice < servers.size() ? choice : 0;
            sessions = Map.copyOf(savedSessions);
            details = Map.copyOf(loadedDetails);
        } catch (IllegalArgumentException e) {
            throw new IOException("玩家资料格式无效。", e);
        }
    }

    private static int count(Properties p, String key) {
        int value = Integer.parseInt(p.getProperty(key, "0"));
        if (value < 0 || value > 10000) throw new IllegalArgumentException("资料数量无效。");
        return value;
    }

    public static String validPlayer(String name) {
        String value = name.strip();
        if (value.isEmpty()
                || value.length() > 16
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请输入 1～16 字的玩家名称，不能包含换行或控制字符。");
        return value;
    }

    public List<String> players() {
        return players;
    }

    public List<Server> servers() {
        return servers;
    }

    public String lastPlayer() {
        return lastPlayer;
    }

    public Server server() {
        return publishedServer == null ? servers.get(selected) : publishedServer;
    }

    public void configurePublishedServer(Path config) throws IOException {
        if (!Files.exists(config)) return;
        Properties settings = new Properties();
        try (Reader input = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            settings.load(input);
        }
        try {
            publishedServer =
                    new Server(
                            "游戏服务器",
                            settings.getProperty("server.host", ""),
                            Integer.parseInt(settings.getProperty("server.port", "9528")));
        } catch (IllegalArgumentException e) {
            throw new IOException("客户端服务器配置无效", e);
        }
    }

    public String login(String input) throws IOException {
        String name = validPlayer(input);
        List<String> names = new ArrayList<>(players);
        if (!names.contains(name)) names.add(name);
        Map<String, Player> updated = new LinkedHashMap<>(details);
        updated.putIfAbsent(
                name, new Player(UUID.randomUUID().toString(), 0, List.of(name), Map.of()));
        save(names, name, servers, selected, sessions, updated);
        players = List.copyOf(names);
        lastPlayer = name;
        details = Map.copyOf(updated);
        return name;
    }

    public void selectServer(Server server) throws IOException {
        List<Server> endpoints = new ArrayList<>(servers);
        int choice = -1;
        for (int i = 0; i < endpoints.size(); i++) {
            Server existing = endpoints.get(i);
            if (existing.host().equalsIgnoreCase(server.host())
                    && existing.port() == server.port()) {
                choice = i;
                break;
            }
        }
        if (choice < 0) {
            choice = endpoints.size();
            endpoints.add(server);
        } else endpoints.set(choice, server);
        save(players, lastPlayer, endpoints, choice, sessions, details);
        servers = List.copyOf(endpoints);
        selected = choice;
    }

    public void savePublishedServer(Path config, Server server) throws IOException {
        Path target = config.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "server-", ".tmp");
        try {
            Properties settings = new Properties();
            settings.setProperty("server.host", server.host());
            settings.setProperty("server.port", Integer.toString(server.port()));
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                settings.store(writer, "Qijian game server");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            publishedServer = server;
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String sessionKey(Server server, String player) {
        String key =
                server.host().toLowerCase(Locale.ROOT)
                        + ":"
                        + server.port()
                        + "/"
                        + validPlayer(player);
        return "session."
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    public String sessionToken(Server server, String player) {
        return sessions.getOrDefault(sessionKey(server, player), "");
    }

    public void rememberSession(Server server, String player, String token) throws IOException {
        Map<String, String> updated = new HashMap<>(sessions);
        String key = sessionKey(server, player);
        if (token.isEmpty()) updated.remove(key);
        else updated.put(key, token);
        save(players, lastPlayer, servers, selected, updated, details);
        sessions = Map.copyOf(updated);
    }

    private Player player(String name) {
        Player player = details.get(name);
        if (player == null) throw new IllegalArgumentException("玩家不存在。");
        return player;
    }

    private static void validAvatar(int avatar) {
        if (avatar < 0 || avatar >= 5) throw new IllegalArgumentException("请选择五个头像之一。");
    }

    public String playerId(String name) {
        return player(name).id();
    }

    public int avatar(String name) {
        return player(name).avatar();
    }

    public List<String> aliases(String name) {
        return player(name).aliases();
    }

    public Stats stats(String name, boolean machine) {
        int games = 0, wins = 0, draws = 0;
        for (Result result : player(name).results().values())
            if (result.machine() == machine) {
                games++;
                if (result.outcome() == 1) wins++;
                if (result.outcome() == 0) draws++;
            }
        return new Stats(games, wins, draws);
    }

    public String edit(String oldName, String input, int avatar) throws IOException {
        String name = validPlayer(input);
        validAvatar(avatar);
        Player old = player(oldName);
        if (!name.equals(oldName) && players.contains(name))
            throw new IllegalArgumentException("该名称属于另一个已有玩家，请使用其他昵称。");
        List<String> names = new ArrayList<>(players);
        names.set(names.indexOf(oldName), name);
        Set<String> aliases = new LinkedHashSet<>(old.aliases());
        aliases.add(name);
        Map<String, Player> updated = new LinkedHashMap<>(details);
        updated.remove(oldName);
        updated.put(name, new Player(old.id(), avatar, List.copyOf(aliases), old.results()));
        Map<String, String> tokens = new HashMap<>(sessions);
        List<Server> sessionServers = new ArrayList<>(servers);
        if (publishedServer != null && !sessionServers.contains(publishedServer))
            sessionServers.add(publishedServer);
        if (!oldName.equals(name))
            for (Server server : sessionServers) {
                String token = tokens.remove(sessionKey(server, oldName));
                if (token != null) tokens.put(sessionKey(server, name), token);
            }
        String last = lastPlayer.equals(oldName) ? name : lastPlayer;
        save(names, last, servers, selected, tokens, updated);
        players = List.copyOf(names);
        lastPlayer = last;
        details = Map.copyOf(updated);
        sessions = Map.copyOf(tokens);
        return name;
    }

    public boolean recordResults(String name, Map<String, Result> incoming) throws IOException {
        Player old = player(name);
        Map<String, Result> results = new LinkedHashMap<>(old.results());
        incoming.forEach(
                (key, result) -> {
                    if (key.isBlank() || key.length() > 200)
                        throw new IllegalArgumentException("对局编号无效。");
                    results.putIfAbsent(key, result);
                });
        if (results.size() == old.results().size()) return false;
        Map<String, Player> updated = new LinkedHashMap<>(details);
        updated.put(name, new Player(old.id(), old.avatar(), old.aliases(), Map.copyOf(results)));
        save(players, lastPlayer, servers, selected, sessions, updated);
        details = Map.copyOf(updated);
        return true;
    }

    private void save(
            List<String> names,
            String last,
            List<Server> endpoints,
            int choice,
            Map<String, String> savedSessions,
            Map<String, Player> savedDetails)
            throws IOException {
        Properties p = new Properties();
        p.putAll(savedSessions);
        p.setProperty("players", "" + names.size());
        p.setProperty("lastPlayer", last);
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            p.setProperty("player." + i, name);
            Player player = savedDetails.get(name);
            String prefix = "player." + i + ".";
            p.setProperty(prefix + "id", player.id());
            p.setProperty(prefix + "avatar", "" + player.avatar());
            p.setProperty(prefix + "aliases", "" + player.aliases().size());
            for (int j = 0; j < player.aliases().size(); j++)
                p.setProperty(prefix + "alias." + j, player.aliases().get(j));
            p.setProperty(prefix + "results", "" + player.results().size());
            int j = 0;
            for (var result : new TreeMap<>(player.results()).entrySet()) {
                String entry = prefix + "result." + j++ + ".";
                p.setProperty(entry + "key", result.getKey());
                p.setProperty(entry + "machine", "" + result.getValue().machine());
                p.setProperty(entry + "outcome", "" + result.getValue().outcome());
            }
        }
        p.setProperty("servers", "" + endpoints.size());
        p.setProperty("selectedServer", "" + choice);
        for (int i = 0; i < endpoints.size(); i++) {
            Server server = endpoints.get(i);
            String prefix = "server." + i + ".";
            p.setProperty(prefix + "name", server.name());
            p.setProperty(prefix + "host", server.host());
            p.setProperty(prefix + "port", "" + server.port());
        }
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "players-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                p.store(writer, "Qijian local players and servers");
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
}
