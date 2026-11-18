package go.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable state transferred to the UI and saved in game records. */
public final class GameView {
    public final Map<String, String> fields;
    public final int size, turn, role, confirmed, blackCaptures, whiteCaptures;
    public final double komi, blackScore, whiteScore;
    public final long revision;
    public final String phase, result, roomId, black, white;
    public final int[] board;
    public final BitSet dead;
    public final List<Game.Move> moves;

    public GameView(Map<String, String> fields) {
        this.fields = Map.copyOf(fields);
        size = integer("size");
        if (size < 5 || size > 19) throw new IllegalArgumentException("棋盘尺寸无效");
        board = Game.decode(text("board"), size);
        komi = Double.parseDouble(text("komi"));
        if (!Double.isFinite(komi) || Math.abs(komi) > 100)
            throw new IllegalArgumentException("贴目无效");
        turn = integer("turn");
        role = Integer.parseInt(fields.getOrDefault("role", "0"));
        if (turn < 1 || turn > 2 || role < 0 || role > 2)
            throw new IllegalArgumentException("执棋方无效");
        confirmed = integer("confirmed");
        blackCaptures = integer("blackCaptures");
        whiteCaptures = integer("whiteCaptures");
        blackScore = Double.parseDouble(text("blackScore"));
        whiteScore = Double.parseDouble(text("whiteScore"));
        revision = Long.parseLong(text("revision"));
        phase = text("phase");
        if (!Set.of("WAITING", "PLAYING", "SCORING", "FINISHED").contains(phase))
            throw new IllegalArgumentException("对局阶段无效");
        result = text("result");
        roomId = text("roomId");
        black = text("black");
        white = text("white");
        dead = BitSet.valueOf(Base64.getDecoder().decode(text("dead")));
        if (dead.length() > board.length) throw new IllegalArgumentException("死子标记越界");
        List<Game.Move> list = new ArrayList<>();
        String history = text("moves");
        if (!history.isEmpty())
            for (String entry : history.split(";")) {
                String[] parts = entry.split(",", -1);
                if (parts.length != 3) throw new IllegalArgumentException("落子记录无效");
                int c = Integer.parseInt(parts[0]),
                        x = Integer.parseInt(parts[1]),
                        y = Integer.parseInt(parts[2]);
                if (c != 1 && c != 2) throw new IllegalArgumentException("落子颜色无效");
                if (!((x == -1 && y == -1) || (x >= 0 && y >= 0 && x < size && y < size)))
                    throw new IllegalArgumentException("落子坐标无效");
                list.add(new Game.Move(c, x, y));
                if (list.size() > 20002) throw new IllegalArgumentException("棋谱步数过多");
            }
        moves = List.copyOf(list);
    }

    public String text(String key) {
        return fields.getOrDefault(key, "");
    }

    public int integer(String key) {
        return Integer.parseInt(text(key));
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(text(key));
    }

    /** Reconstruct captures rather than painting historical stones on top of one another. */
    public List<int[]> replayBoards() {
        Game replay = new Game(size, komi);
        List<int[]> frames = new ArrayList<>();
        frames.add(replay.board());
        for (Game.Move move : moves) {
            if (replay.phase() == Game.Phase.SCORING) replay.resume(move.color());
            if (move.pass()) replay.pass(move.color());
            else replay.play(move.color(), move.x(), move.y());
            frames.add(replay.board());
        }
        if (!Arrays.equals(replay.board(), board)) throw new IllegalArgumentException("棋谱与最终棋盘不一致");
        return frames;
    }
}
