package go.ai;

import java.nio.file.Path;

/** Strength labels are broad estimates, never calibrated human ranks. */
public enum AiProfile {
    SMALL("小型 · B18 · 约 2633 万参数", "kata1-b18c384nbt-s9996604416-d4316597426"),
    MEDIUM("中型 · B28 · 约 7283 万参数", "kata1-b28c512nbt-s13255194368-d5935380940"),
    LARGE("大型 · B40 · 约 2.33 亿参数", "kata1-zhizi-b40c768nbt-s11472M-d5982M");
    private final String title, file;

    AiProfile(String title, String file) {
        this.title = title;
        this.file = file;
    }

    public Path model(Path home) {
        return home.resolve("models").resolve(file + ".bin.gz");
    }

    public String shortName() {
        return switch (this) {
            case SMALL -> "B18";
            case MEDIUM -> "B28";
            case LARGE -> "B40";
        };
    }

    @Override
    public String toString() {
        return title;
    }

    public enum Budget {
        QUICK("快速 · 最多 1 秒 / 128 次搜索", 128, 1),
        STANDARD("标准 · 最多 3 秒 / 512 次搜索", 512, 3),
        DEEP("深入 · 最多 8 秒 / 1600 次搜索", 1600, 8);
        public final int visits, seconds;
        private final String title;

        Budget(String title, int visits, int seconds) {
            this.title = title;
            this.visits = visits;
            this.seconds = seconds;
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
