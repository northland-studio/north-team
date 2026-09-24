package top.xuanjian.northteam.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * 契约 3.1 {@code GET /api/team/list} 的响应包体。
 */
public record ConfigListResponse(
        @SerializedName("count") Integer count,
        @SerializedName("configs") List<ConfigSummary> configs
) {

    public List<ConfigSummary> configsOrEmpty() {
        return configs == null ? List.of() : configs;
    }

    public int countOrSize() {
        return count == null ? configsOrEmpty().size() : count;
    }
}
