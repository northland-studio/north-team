package top.xuanjian.northteam.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 队伍单元（契约 docs/API.md 2.1 的 {@code units[]} 元素）。
 *
 * <p>字段名与语义严格对应契约，不得改动。JSON 反序列化同时依赖
 * {@link SerializedName} 与 Gson 的 LOWER_CASE_WITH_UNDERSCORES 命名策略，
 * 两者互为冗余保险。
 */
public record TeamUnit(
        @SerializedName("key") String key,
        @SerializedName("display_name") String displayName,
        @SerializedName("color") String color,
        @SerializedName("prefix") String prefix,
        @SerializedName("suffix") String suffix,
        @SerializedName("friendly_fire") boolean friendlyFire,
        @SerializedName("see_friendly_invisibles") boolean seeFriendlyInvisibles,
        @SerializedName("nametag_visibility") String nametagVisibility,
        @SerializedName("death_message_visibility") String deathMessageVisibility,
        @SerializedName("collision_rule") String collisionRule,
        @SerializedName("sort_order") int sortOrder,
        @SerializedName("members") List<String> members
) {

    /** members 可能为 null（官网未给出该字段时），统一收敛为空列表。 */
    public List<String> membersOrEmpty() {
        return members == null ? List.of() : members;
    }

    /**
     * 按契约要求做「大小写不敏感去重」后的成员名单，保持原始书写形式与出现顺序。
     * 契约 2.1：{@code members} 为离线模式玩家名，大小写不敏感去重。
     */
    public List<String> normalizedMembers() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        LinkedHashSet<String> lower = new LinkedHashSet<>();
        for (String raw : membersOrEmpty()) {
            if (raw == null) {
                continue;
            }
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (lower.add(name.toLowerCase(Locale.ROOT))) {
                seen.add(name);
            }
        }
        return new ArrayList<>(seen);
    }

    /** 契约 2.1：插件用 key 做 scoreboard team 名（前缀 nt_）。 */
    public String teamName() {
        return TeamNames.FORMAL_PREFIX + key;
    }

    /**
     * 仅用于日志/展示的安全 key（key 为 null 时返回占位符，避免拼接出 "nt_null"）。
     */
    public String safeKey() {
        return key == null ? "<null>" : key;
    }
}
