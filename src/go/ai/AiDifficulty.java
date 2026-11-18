package go.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Target difficulty presets, not certified human ratings. */
public enum AiDifficulty {
    AMATEUR_LOW(
            "业余1–3段",
            "稳扎稳打",
            AiProfile.SMALL,
            AiProfile.Budget.QUICK,
            "rank_2d",
            "100000000",
            "1.0",
            "0.0"),
    AMATEUR_MID(
            "业余4–6段",
            "攻守兼备",
            AiProfile.SMALL,
            AiProfile.Budget.STANDARD,
            "rank_5d",
            "0.6",
            "1.0",
            "0.5"),
    AMATEUR_HIGH(
            "业余7–9段",
            "全局博弈",
            AiProfile.SMALL,
            AiProfile.Budget.DEEP,
            "rank_8d",
            "0.15",
            "1.0",
            "0.5"),
    PROFESSIONAL(
            "职业级",
            "精研变化",
            AiProfile.MEDIUM,
            AiProfile.Budget.DEEP,
            "proyear_2023",
            "0.03",
            "0.7",
            "0.5"),
    SUPERHUMAN("超人类棋力", "挑战极限", AiProfile.LARGE, AiProfile.Budget.DEEP, "", "", "", "");
    private final String title, caption, human, lambda, proportion, exploration;
    private final AiProfile model;
    private final AiProfile.Budget budget;

    AiDifficulty(
            String t,
            String c,
            AiProfile m,
            AiProfile.Budget b,
            String h,
            String l,
            String p,
            String e) {
        title = t;
        caption = c;
        model = m;
        budget = b;
        human = h;
        lambda = l;
        proportion = p;
        exploration = e;
    }

    public AiProfile model() {
        return model;
    }

    public AiProfile.Budget budget() {
        return budget;
    }

    public boolean humanLike() {
        return !human.isEmpty();
    }

    public String humanProfile() {
        return human;
    }

    public String caption() {
        return caption;
    }

    public String modelLabel() {
        return humanLike() ? "HumanSL + " + model.shortName() : "智子 B40";
    }

    public Path humanModel(Path home) {
        return home.resolve("models/b18c384nbt-humanv0.bin.gz");
    }

    public boolean installed(Path home) {
        return Files.isRegularFile(KataGoEngine.executable(home))
                && Files.isRegularFile(model.model(home))
                && Files.isRegularFile(home.resolve("gtp.cfg"))
                && (!humanLike() || Files.isRegularFile(humanModel(home)));
    }

    public String overrides() {
        String limits = "maxVisits=" + budget.visits + ",maxTime=" + budget.seconds;
        if (!humanLike()) return limits;
        return limits
                + ",humanSLProfile="
                + human
                + ",humanSLChosenMoveProp="
                + proportion
                + ",humanSLChosenMoveIgnorePass=true,humanSLChosenMovePiklLambda="
                + lambda
                + ",humanSLRootExploreProbWeightless="
                + exploration
                + ",humanSLPlaExploreProbWeightless="
                + exploration
                + ",humanSLOppExploreProbWeightless="
                + exploration
                + ",humanSLCpuctExploration=0.5,humanSLCpuctPermanent=0.2,ignorePreRootHistory=false,analysisIgnorePreRootHistory=false,rootNumSymmetriesToSample=2,useLcbForSelection=false"
                + ",chosenMoveTemperatureEarly=0.85,chosenMoveTemperature=0.70,chosenMoveTemperatureHalflife=80,chosenMoveTemperatureOnlyBelowProb=0.01,chosenMoveSubtract=0,chosenMovePrune=0,delayMoveScale=0,delayMoveMax=0";
    }

    public static AiDifficulty from(Map<String, String> fields) {
        String value = fields.getOrDefault("aiDifficulty", "");
        if (!value.isEmpty()) return valueOf(value);
        return switch (AiProfile.valueOf(fields.getOrDefault("aiModel", "SMALL"))) {
            case SMALL -> AMATEUR_LOW;
            case MEDIUM -> PROFESSIONAL;
            case LARGE -> SUPERHUMAN;
        };
    }

    @Override
    public String toString() {
        return title;
    }
}
