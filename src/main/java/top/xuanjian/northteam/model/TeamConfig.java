package top.xuanjian.northteam.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Optional;

/**
 * 契约 2.1 的完整配置（export / public 详情 / 管理读写共用同一形状）。
 */
public record TeamConfig(
        @SerializedName("schema") int schema,
        @SerializedName("id") Integer id,
        @SerializedName("name") String name,
        @SerializedName("description") String description,
        @SerializedName("event_date") String eventDate,
        @SerializedName("is_public") boolean isPublic,
        @SerializedName("version") String version,
        @SerializedName("scoreboard") ScoreboardSettings scoreboard,
        @SerializedName("units") List<TeamUnit> units
) {

    public List<TeamUnit> unitsOrEmpty() {
        return units == null ? List.of() : units;
    }

    public ScoreboardSettings scoreboardOrDisabled() {
        return scoreboard == null ? ScoreboardSettings.disabled() : scoreboard;
    }

    public String nameOrFallback() {
        return name == null || name.isBlank() ? "未命名配置" : name;
    }

    public String versionOrEmpty() {
        return version == null ? "" : version;
    }

    /**
     * 在配置内按玩家名（大小写不敏感）查所属队伍。
     * 契约要求「单配置内一个玩家只能在一个队伍」，若数据非法仍返回首个匹配，
     * 由校验器负责报错。
     */
    public Optional<TeamUnit> unitOfMember(String playerName) {
        if (playerName == null) {
            return Optional.empty();
        }
        for (TeamUnit unit : unitsOrEmpty()) {
            for (String member : unit.normalizedMembers()) {
                if (member.equalsIgnoreCase(playerName)) {
                    return Optional.of(unit);
                }
            }
        }
        return Optional.empty();
    }

    /** 配置内全部成员的原始名单（已按每队去重）。 */
    public List<String> allMembers() {
        return unitsOrEmpty().stream()
                .flatMap(u -> u.normalizedMembers().stream())
                .toList();
    }
}
