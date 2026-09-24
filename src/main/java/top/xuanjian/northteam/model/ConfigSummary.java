package top.xuanjian.northteam.model;

import com.google.gson.annotations.SerializedName;

/**
 * 契约 3.1 {@code GET /api/team/list} 的 {@code configs[]} 元素。
 * 也可用于 3.6 管理接口的全量列表（字段为超集，多余字段被忽略）。
 */
public record ConfigSummary(
        @SerializedName("id") Integer id,
        @SerializedName("name") String name,
        @SerializedName("event_date") String eventDate,
        @SerializedName("is_public") boolean isPublic,
        @SerializedName("version") String version,
        @SerializedName("unit_count") Integer unitCount,
        @SerializedName("member_count") Integer memberCount
) {

    public int idOrZero() {
        return id == null ? 0 : id;
    }

    public String nameOrFallback() {
        return name == null || name.isBlank() ? "未命名配置" : name;
    }

    public int unitCountOrZero() {
        return unitCount == null ? 0 : unitCount;
    }

    public int memberCountOrZero() {
        return memberCount == null ? 0 : memberCount;
    }

    public String versionOrEmpty() {
        return version == null ? "" : version;
    }

    public String eventDateOrDash() {
        return eventDate == null || eventDate.isBlank() ? "-" : eventDate;
    }
}
