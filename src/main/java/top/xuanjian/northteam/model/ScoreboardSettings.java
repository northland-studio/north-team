package top.xuanjian.northteam.model;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/**
 * 契约 2.1 的 {@code scoreboard} 子对象。
 *
 * @param enabled     是否启用记分板同步
 * @param objective   objective 名（默认 {@code nt_teams}）
 * @param displayName 侧边栏标题，MiniMessage（也允许 {@code &} 传统颜色码）
 * @param position    {@code sidebar} | {@code list} | {@code below_name}
 * @param scoreMode   {@code member_count} | {@code fixed}
 * @param unitScores  仅 {@code fixed} 模式使用，本期保留（契约 2.1）
 */
public record ScoreboardSettings(
        @SerializedName("enabled") boolean enabled,
        @SerializedName("objective") String objective,
        @SerializedName("display_name") String displayName,
        @SerializedName("position") String position,
        @SerializedName("score_mode") String scoreMode,
        @SerializedName("unit_scores") Map<String, Integer> unitScores
) {

    public static final String DEFAULT_OBJECTIVE = "nt_teams";
    public static final String DEFAULT_DISPLAY_NAME = "<gold>队伍</gold>";
    public static final String POSITION_SIDEBAR = "sidebar";
    public static final String POSITION_LIST = "list";
    public static final String POSITION_BELOW_NAME = "below_name";
    public static final String MODE_MEMBER_COUNT = "member_count";
    public static final String MODE_FIXED = "fixed";

    /** 官网未给出 scoreboard 段时的兜底（关闭同步，不影响其它流程）。 */
    public static ScoreboardSettings disabled() {
        return new ScoreboardSettings(false, DEFAULT_OBJECTIVE, DEFAULT_DISPLAY_NAME,
                POSITION_SIDEBAR, MODE_MEMBER_COUNT, Map.of());
    }

    public String objectiveOrDefault() {
        return objective == null || objective.isBlank() ? DEFAULT_OBJECTIVE : objective;
    }

    public String displayNameOrDefault() {
        return displayName == null || displayName.isBlank() ? DEFAULT_DISPLAY_NAME : displayName;
    }

    public String positionOrDefault() {
        return position == null || position.isBlank() ? POSITION_SIDEBAR : position;
    }

    public String scoreModeOrDefault() {
        return scoreMode == null || scoreMode.isBlank() ? MODE_MEMBER_COUNT : scoreMode;
    }

    public Map<String, Integer> unitScoresOrEmpty() {
        return unitScores == null ? Map.of() : unitScores;
    }
}
