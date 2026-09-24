package top.xuanjian.northteam.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * {@code plugins/NorthTeam/state.json} 的落盘结构。
 *
 * <p>用途：
 * <ul>
 *   <li>记录「当前已应用配置」的 ID/版本，供 {@code /nt apply} 做 version 去重；</li>
 *   <li>记录本插件在记分板上创建过的队伍名，重启后（记分板不持久化）可自愈重建，
 *       也能在清理时精确回收自己创建的对象；</li>
 *   <li>记录上次同步时间与结果，供 {@code /nt status} 展示。</li>
 * </ul>
 *
 * <p>全部字段可空/有默认值，任何解析失败都退化为「空状态」而不是崩溃。
 */
public record StateSnapshot(
        @SerializedName("schema") int schema,
        @SerializedName("applied_config_id") Integer appliedConfigId,
        @SerializedName("applied_config_name") String appliedConfigName,
        @SerializedName("applied_version") String appliedVersion,
        @SerializedName("applied_at") String appliedAt,
        @SerializedName("cache_file") String cacheFile,
        @SerializedName("objective") String objective,
        @SerializedName("scoreboard_enabled") boolean scoreboardEnabled,
        @SerializedName("formal_teams") List<String> formalTeams,
        @SerializedName("sidebar_teams") List<String> sidebarTeams,
        @SerializedName("last_sync_at") String lastSyncAt,
        @SerializedName("last_sync_ok") boolean lastSyncOk,
        @SerializedName("last_sync_message") String lastSyncMessage
) {

    public static final int CURRENT_SCHEMA = 1;

    /** 全新安装时的空状态。 */
    public static StateSnapshot empty() {
        return new StateSnapshot(CURRENT_SCHEMA, null, null, null, null, null,
                null, false, List.of(), List.of(), null, false, null);
    }

    public List<String> formalTeamsOrEmpty() {
        return formalTeams == null ? List.of() : formalTeams;
    }

    public List<String> sidebarTeamsOrEmpty() {
        return sidebarTeams == null ? List.of() : sidebarTeams;
    }

    public boolean hasAppliedConfig() {
        return appliedConfigId != null;
    }
}
