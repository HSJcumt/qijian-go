package go.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure rules model. A room serializes access; local AI receives a private copy. */
public final class Game {
    public enum Phase {
        PLAYING,
        SCORING,
        FINISHED
    }

    public record Move(int color, int x, int y) {
        public boolean pass() {
            return x < 0;
        }
    }

    public record Group(Set<Integer> stones, Set<Integer> liberties) {}

    public record Trial(int[] board, int captured) {}

    public record Score(double black, double white) {}

    private final int size;
    private final double komi;
    private int[] board;
    private int turn = 1, passes, blackCaptures, whiteCaptures, confirmations, winner;
    private long revision;
    private Phase phase = Phase.PLAYING;
    private String result = "";
    private Double adjudicatedLead;
    private final BitSet dead = new BitSet();
    private final Set<String> positions = new HashSet<>();
    private final List<Move> moves = new ArrayList<>();

    /** New games use equal area scoring. The legacy overload reads older records. */
    public Game(int size) {
        this(size, 0);
    }

    public Game(int size, double komi) {
        if (size < 5 || size > 19 || !Double.isFinite(komi) || Math.abs(komi) > 100)
            throw new IllegalArgumentException("棋盘或贴目设置不合法");
        this.size = size;
        this.komi = komi;
        board = new int[size * size];
        positions.add(key(board));
    }

    private Game(Game source) {
        size = source.size;
        komi = source.komi;
        board = source.board.clone();
        turn = source.turn;
        passes = source.passes;
        blackCaptures = source.blackCaptures;
        whiteCaptures = source.whiteCaptures;
        confirmations = source.confirmations;
        winner = source.winner;
        revision = source.revision;
        phase = source.phase;
        result = source.result;
        adjudicatedLead = source.adjudicatedLead;
        dead.or(source.dead);
        positions.addAll(source.positions);
        moves.addAll(source.moves);
    }

    public Game copy() {
        return new Game(this);
    }

    public boolean canUndo(int color) {
        return phase != Phase.FINISHED
                && (color == 1 || color == 2)
                && moves.stream().anyMatch(move -> move.color == color);
    }

    /** Rebuild the retained branch, including captures and superko; versions never go backwards. */
    public void undo(int color) {
        requirePlayer(color);
        int keep = moves.size() - 1;
        while (keep >= 0 && moves.get(keep).color != color) keep--;
        if (keep < 0) throw new IllegalArgumentException("还没有可撤回的己方落子");
        Game restored = new Game(size, komi);
        for (int i = 0; i < keep; i++) {
            Move move = moves.get(i);
            if (restored.phase == Phase.SCORING) restored.resume(move.color);
            if (move.pass()) restored.pass(move.color);
            else restored.play(move.color, move.x, move.y);
        }
        if (restored.phase == Phase.SCORING) restored.resume(restored.turn);
        board = restored.board;
        turn = restored.turn;
        passes = restored.passes;
        blackCaptures = restored.blackCaptures;
        whiteCaptures = restored.whiteCaptures;
        phase = Phase.PLAYING;
        confirmations = 0;
        dead.clear();
        winner = 0;
        result = "";
        positions.clear();
        positions.addAll(restored.positions);
        moves.clear();
        moves.addAll(restored.moves);
        revision++;
    }

    public int size() {
        return size;
    }

    public double komi() {
        return komi;
    }

    public int turn() {
        return turn;
    }

    public int passes() {
        return passes;
    }

    public int winner() {
        return winner;
    }

    public long revision() {
        return revision;
    }

    public Phase phase() {
        return phase;
    }

    public int[] board() {
        return board.clone();
    }

    public List<Move> moves() {
        return List.copyOf(moves);
    }

    public String result() {
        return result;
    }

    public static String colorName(int color) {
        return color == 1 ? "黑方" : "白方";
    }

    private void requirePlayer(int color) {
        if (color != 1 && color != 2) throw new IllegalArgumentException("观战者不能操作棋局");
        if (phase == Phase.FINISHED) throw new IllegalArgumentException("对局已经结束");
    }

    public Trial preview(int x, int y) {
        if (phase != Phase.PLAYING) throw new IllegalArgumentException("当前不是行棋阶段");
        Trial trial = simulate(board, size, turn, x, y);
        if (positions.contains(key(trial.board)))
            throw new IllegalArgumentException("违反全局同形禁止规则（劫）");
        return trial;
    }

    public void play(int color, int x, int y) {
        requirePlayer(color);
        if (color != turn) throw new IllegalArgumentException("请等待对方落子");
        if (moves.size() >= 20000) throw new IllegalArgumentException("达到对局步数上限，请停手结算");
        Trial trial = preview(x, y);
        board = trial.board;
        if (color == 1) blackCaptures += trial.captured;
        else whiteCaptures += trial.captured;
        positions.add(key(board));
        moves.add(new Move(color, x, y));
        turn = 3 - turn;
        passes = 0;
        revision++;
    }

    public void pass(int color) {
        requirePlayer(color);
        if (phase != Phase.PLAYING || color != turn) throw new IllegalArgumentException("现在不能停一手");
        moves.add(new Move(color, -1, -1));
        turn = 3 - turn;
        passes++;
        revision++;
        if (passes == 2) {
            phase = Phase.SCORING;
            confirmations = 0;
        }
    }

    /** Enter settlement without manufacturing pass moves. */
    public void beginScoring(int color) {
        requirePlayer(color);
        if (phase != Phase.PLAYING) throw new IllegalArgumentException("当前不能发起结算");
        phase = Phase.SCORING;
        confirmations = 0;
        revision++;
    }

    public void markDead(int color, int x, int y) {
        requirePlayer(color);
        if (phase != Phase.SCORING) throw new IllegalArgumentException("请在结算阶段标记死子");
        int index = index(x, y, size);
        if (board[index] == 0) throw new IllegalArgumentException("请选择有棋子的交叉点");
        Group group = group(board, size, index);
        boolean value = !dead.get(index);
        for (int stone : group.stones) dead.set(stone, value);
        confirmations = 0;
        revision++;
    }

    public void confirm(int color) {
        requirePlayer(color);
        if (phase != Phase.SCORING) throw new IllegalArgumentException("当前不能确认结算");
        confirmations |= color;
        revision++;
        if (confirmations == 3) {
            Score score = score();
            winner = score.black > score.white ? 1 : score.black < score.white ? 2 : 0;
            result =
                    String.format(
                            Locale.ROOT,
                            "黑 %.1f / 白 %.1f · %s",
                            score.black,
                            score.white,
                            winner == 0
                                    ? "和棋"
                                    : colorName(winner)
                                            + "胜 "
                                            + String.format(
                                                    Locale.ROOT,
                                                    "%.1f 点",
                                                    Math.abs(score.black - score.white)));
            phase = Phase.FINISHED;
        }
    }

    public void resume(int color) {
        requirePlayer(color);
        if (phase != Phase.SCORING) throw new IllegalArgumentException("当前无需恢复行棋");
        if (moves.size() >= 20000) throw new IllegalArgumentException("达到对局步数上限，请确认结算或认输");
        phase = Phase.PLAYING;
        passes = 0;
        dead.clear();
        confirmations = 0;
        revision++;
    }

    public void resign(int color, String reason) {
        requirePlayer(color);
        winner = 3 - color;
        phase = Phase.FINISHED;
        result = colorName(winner) + "胜 · " + colorName(color) + reason;
        revision++;
    }

    /** Apply a server-validated model adjudication only after the player accepts its preview. */
    public void adjudicate(double blackLead, BitSet marked) {
        if (phase == Phase.FINISHED) throw new IllegalArgumentException("对局已经结束");
        if (!Double.isFinite(blackLead) || Math.abs(blackLead) > 1000 || marked.length() > board.length)
            throw new IllegalArgumentException("模型计分无效");
        for (int i = marked.nextSetBit(0); i >= 0; i = marked.nextSetBit(i + 1))
            if (board[i] == 0) throw new IllegalArgumentException("死子标记不能为空点");
        dead.clear(); dead.or(marked);
        adjudicatedLead = blackLead;
        winner = blackLead > 0 ? 1 : blackLead < 0 ? 2 : 0;
        result = winner == 0 ? "KataGo 裁定：和棋" : String.format(Locale.ROOT,
                "KataGo 裁定：%s胜 %.1f 点", colorName(winner), Math.abs(blackLead));
        confirmations = 3;
        phase = Phase.FINISHED;
        revision++;
    }

    public Score score() {
        int[] scored = board.clone();
        for (int i = dead.nextSetBit(0); i >= 0; i = dead.nextSetBit(i + 1)) scored[i] = 0;
        double black = 0, white = komi;
        boolean[] visited = new boolean[scored.length];
        for (int i = 0; i < scored.length; i++) {
            if (scored[i] == 1) {
                black++;
                continue;
            }
            if (scored[i] == 2) {
                white++;
                continue;
            }
            if (visited[i]) continue;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            visited[i] = true;
            int count = 0, boundary = 0;
            while (!queue.isEmpty()) {
                int p = queue.removeFirst();
                count++;
                for (int n : neighbors(p, size)) {
                    if (scored[n] != 0) boundary |= scored[n];
                    else if (!visited[n]) {
                        visited[n] = true;
                        queue.addLast(n);
                    }
                }
            }
            if (boundary == 1) black += count;
            else if (boundary == 2) white += count;
        }
        return new Score(black, white);
    }

    public Map<String, String> snapshot() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("size", "" + size);
        data.put("komi", "" + komi);
        data.put("board", key(board));
        data.put("turn", "" + turn);
        data.put("phase", phase.name());
        data.put("revision", "" + revision);
        data.put("passes", "" + passes);
        data.put("dead", Base64.getEncoder().encodeToString(dead.toByteArray()));
        data.put("confirmed", "" + confirmations);
        data.put("blackCaptures", "" + blackCaptures);
        data.put("whiteCaptures", "" + whiteCaptures);
        data.put("winner", "" + winner);
        if (adjudicatedLead != null) {
            data.put("scoreMethod", "katago");
            data.put("scoreLead", Double.toString(adjudicatedLead));
        }
        data.put("result", result);
        Score score = score();
        data.put("blackScore", "" + score.black);
        data.put("whiteScore", "" + score.white);
        StringBuilder history = new StringBuilder();
        for (Move move : moves)
            history.append(move.color)
                    .append(',')
                    .append(move.x)
                    .append(',')
                    .append(move.y)
                    .append(';');
        data.put("moves", history.toString());
        return data;
    }

    public static String key(int[] cells) {
        byte[] bytes = new byte[cells.length];
        for (int i = 0; i < cells.length; i++) bytes[i] = (byte) cells[i];
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static int[] decode(String key, int size) {
        byte[] bytes = Base64.getDecoder().decode(key);
        if (bytes.length != size * size) throw new IllegalArgumentException("棋盘数据长度错误");
        int[] cells = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] < 0 || bytes[i] > 2) throw new IllegalArgumentException("棋盘数据错误");
            cells[i] = bytes[i];
        }
        return cells;
    }

    public static Trial simulate(int[] source, int size, int color, int x, int y) {
        int p = index(x, y, size);
        if (source[p] != 0) throw new IllegalArgumentException("该位置已有棋子");
        int[] next = source.clone();
        next[p] = color;
        int captured = 0;
        for (int n : neighbors(p, size)) {
            if (next[n] == 3 - color) {
                Group group = group(next, size, n);
                if (group.liberties.isEmpty()) {
                    for (int stone : group.stones) next[stone] = 0;
                    captured += group.stones.size();
                }
            }
        }
        if (group(next, size, p).liberties.isEmpty())
            throw new IllegalArgumentException("禁入点：落子后己方无气");
        return new Trial(next, captured);
    }

    public static Group group(int[] cells, int size, int start) {
        Set<Integer> stones = new HashSet<>(), liberties = new HashSet<>();
        int color = cells[start];
        if (color == 0) return new Group(stones, liberties);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        stones.add(start);
        while (!queue.isEmpty()) {
            for (int n : neighbors(queue.removeFirst(), size)) {
                if (cells[n] == 0) liberties.add(n);
                else if (cells[n] == color && stones.add(n)) queue.addLast(n);
            }
        }
        return new Group(stones, liberties);
    }

    public static int[] neighbors(int p, int size) {
        int[] values = new int[4];
        int count = 0, x = p % size, y = p / size;
        if (x > 0) values[count++] = p - 1;
        if (x < size - 1) values[count++] = p + 1;
        if (y > 0) values[count++] = p - size;
        if (y < size - 1) values[count++] = p + size;
        return Arrays.copyOf(values, count);
    }

    private static int index(int x, int y, int size) {
        if (x < 0 || y < 0 || x >= size || y >= size) throw new IllegalArgumentException("落点超出棋盘");
        return y * size + x;
    }
}
