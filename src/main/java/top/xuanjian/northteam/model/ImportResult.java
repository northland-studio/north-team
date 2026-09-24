package top.xuanjian.northteam.model;

import com.google.gson.annotations.SerializedName;

/**
 * 契约 3.3 {@code POST /api/team/import} 的响应。
 */
public record ImportResult(
        @SerializedName("id") Integer id,
        @SerializedName("name") String name,
        @SerializedName("unit_count") Integer unitCount,
        @SerializedName("member_count") Integer memberCount,
        @SerializedName("message") String message
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

    public String messageOrEmpty() {
        return message == null ? "" : message;
    }
}
