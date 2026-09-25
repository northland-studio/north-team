package top.xuanjian.northteam.config;

import org.bukkit.configuration.file.FileConfiguration;
import top.xuanjian.northteam.chat.ChatFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * {@code config.yml} 的强类型视图。
 *
 * <p>每次 {@code /nt reload} 都会重新构造一份，运行期不再直接读 FileConfiguration，
 * 避免「配置读一半被重载」导致的语义漂移。
 *
 * <p>支持两个环境变量覆盖，便于 CI 与容器化部署不改文件：
 * <ul>
 *   <li>{@code NORTHTEAM_API_BASE} —— 覆盖 {@code api.base}</li>
 *   <li>{@code NORTHTEAM_SERVER_KEY} —— 覆盖 {@code api.server_key}（避免把密钥写进仓库）</li>
 * </ul>
 */
public final class PluginConfig {

    private final String apiBase;
    private final String serverKey;
    private final int connectTimeoutSeconds;
    private final int requestTimeoutSeconds;
    private final boolean autoReapplyOnStart;
    private final boolean wipeUnmanagedTeams;
    private final List<String> protectedTeams;
    private final boolean scoreboardMasterEnabled;
    private final int scoreboardMaxRows;
    private final boolean assignOnJoin;
    private final boolean chatEnabled;
    private final String chatFormat;
    private final String chatNoTeamFormat;
    private final boolean debug;

    /** 有队伍成员默认聊天格式：队伍前缀 + 玩家名（按队伍 color 上色）+ 消息。 */
    public static final String DEFAULT_CHAT_FORMAT = "{prefix}{player}<gray>: </gray>{message}";
    /** 无队伍玩家默认聊天格式：与原生 {@code <名字> 消息} 观感一致。 */
    public static final String DEFAULT_CHAT_FORMAT_NO_TEAM = "{player}<gray>: </gray>{message}";

    private PluginConfig(String apiBase, String serverKey, int connectTimeoutSeconds,
                         int requestTimeoutSeconds, boolean autoReapplyOnStart,
                         boolean wipeUnmanagedTeams, List<String> protectedTeams,
                         boolean scoreboardMasterEnabled, int scoreboardMaxRows,
                         boolean assignOnJoin, boolean chatEnabled, String chatFormat,
                         String chatNoTeamFormat, boolean debug) {
        this.apiBase = apiBase;
        this.serverKey = serverKey;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.autoReapplyOnStart = autoReapplyOnStart;
        this.wipeUnmanagedTeams = wipeUnmanagedTeams;
        this.protectedTeams = protectedTeams;
        this.scoreboardMasterEnabled = scoreboardMasterEnabled;
        this.scoreboardMaxRows = scoreboardMaxRows;
        this.assignOnJoin = assignOnJoin;
        this.chatEnabled = chatEnabled;
        this.chatFormat = chatFormat;
        this.chatNoTeamFormat = chatNoTeamFormat;
        this.debug = debug;
    }

    public static PluginConfig from(FileConfiguration config, Logger logger) {
        String rawBase = config.getString("api.base", "https://xuanjian.top");
        String rawKey = config.getString("api.server_key", "");

        String envBase = System.getenv("NORTHTEAM_API_BASE");
        if (envBase != null && !envBase.isBlank()) {
            rawBase = envBase;
            logger.info("api_base 已被环境变量 NORTHTEAM_API_BASE 覆盖。");
        }
        String envKey = System.getenv("NORTHTEAM_SERVER_KEY");
        if (envKey != null && !envKey.isBlank()) {
            rawKey = envKey;
            logger.info("server_key 已被环境变量 NORTHTEAM_SERVER_KEY 覆盖。");
        }

        List<String> protectedPatterns = new ArrayList<>();
        for (String pattern : config.getStringList("apply.protected_teams")) {
            if (pattern != null && !pattern.isBlank()) {
                protectedPatterns.add(pattern.trim());
            }
        }
        if (protectedPatterns.isEmpty()) {
            protectedPatterns.addAll(DEFAULT_PROTECTED);
        }

        int maxRows = config.getInt("scoreboard.max_rows", 15);
        if (maxRows < 1) {
            logger.warning("scoreboard.max_rows=" + maxRows + " 不合法（需 ≥1），已回退为 15。");
            maxRows = 15;
        }

        // 聊天渲染（1.0.1）：默认开启，因为 Paper 默认渲染不带记分板队伍前缀
        boolean chatEnabled = config.getBoolean("chat.enabled", true);
        String chatFormat = config.getString("chat.format", DEFAULT_CHAT_FORMAT);
        String chatNoTeamFormat = config.getString("chat.format_no_team", DEFAULT_CHAT_FORMAT_NO_TEAM);
        if (chatFormat == null) {
            chatFormat = DEFAULT_CHAT_FORMAT;
        }
        if (chatNoTeamFormat == null) {
            chatNoTeamFormat = DEFAULT_CHAT_FORMAT_NO_TEAM;
        }
        if (chatEnabled) {
            if (chatFormat.isBlank()) {
                logger.warning("chat.format 为空：有队伍的玩家聊天将保持服务端默认渲染"
                        + "（不会有队伍前缀）。如需前缀请填写 {prefix}{player}<gray>: </gray>{message}");
            }
            for (String token : ChatFormat.unknownPlaceholders(chatFormat)) {
                logger.warning("chat.format 里的占位符 " + token + " 不被支持，将按字面量显示。"
                        + "可用占位符：" + String.join(" ", ChatFormat.KNOWN_PLACEHOLDERS));
            }
            for (String token : ChatFormat.unknownPlaceholders(chatNoTeamFormat)) {
                logger.warning("chat.format_no_team 里的占位符 " + token + " 不被支持，将按字面量显示。"
                        + "可用占位符：" + String.join(" ", ChatFormat.KNOWN_PLACEHOLDERS));
            }
        }

        return new PluginConfig(
                rawBase == null ? "" : rawBase.trim(),
                rawKey == null ? "" : rawKey.trim(),
                Math.max(1, config.getInt("api.connect_timeout_seconds", 5)),
                Math.max(1, config.getInt("api.request_timeout_seconds", 15)),
                config.getBoolean("auto_reapply_on_start", true),
                config.getBoolean("apply.wipe_unmanaged_teams", true),
                List.copyOf(protectedPatterns),
                config.getBoolean("scoreboard.master_enabled", true),
                maxRows,
                config.getBoolean("member.assign_on_join", true),
                chatEnabled,
                chatFormat.trim(),
                chatNoTeamFormat.trim(),
                config.getBoolean("debug", false));
    }

    /**
     * 默认保护名单。
     *
     * <p>契约 4.1 明确默认保护 {@code sidebar_*}；本插件自己的侧边栏行队伍使用
     * {@code sb_} 前缀，同样列入默认保护，避免误删。
     */
    public static final List<String> DEFAULT_PROTECTED = List.of("sidebar_*", "sb_*");

    public String apiBase() {
        return apiBase;
    }

    public String serverKey() {
        return serverKey;
    }

    public boolean hasServerKey() {
        return !serverKey.isEmpty() && !"CHANGE_ME".equals(serverKey);
    }

    public int connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public int requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public boolean autoReapplyOnStart() {
        return autoReapplyOnStart;
    }

    public boolean wipeUnmanagedTeams() {
        return wipeUnmanagedTeams;
    }

    public List<String> protectedTeams() {
        return protectedTeams;
    }

    public boolean scoreboardMasterEnabled() {
        return scoreboardMasterEnabled;
    }

    public int scoreboardMaxRows() {
        return scoreboardMaxRows;
    }

    public boolean assignOnJoin() {
        return assignOnJoin;
    }

    /** 是否由本插件渲染聊天行（1.0.1 新增，默认 true）。 */
    public boolean chatEnabled() {
        return chatEnabled;
    }

    /** 有队伍成员的聊天模板；空字符串表示不改动，交回默认渲染。 */
    public String chatFormat() {
        return chatFormat;
    }

    /** 不在任何队伍里的玩家的聊天模板；空字符串表示不改动。 */
    public String chatNoTeamFormat() {
        return chatNoTeamFormat;
    }

    public boolean debug() {
        return debug;
    }

    /**
     * 队伍名是否命中保护名单（支持 {@code *} 通配）。
     */
    public boolean isProtected(String teamName) {
        if (teamName == null) {
            return false;
        }
        String name = teamName.toLowerCase(Locale.ROOT);
        for (String pattern : protectedTeams) {
            if (matchesGlob(pattern.toLowerCase(Locale.ROOT), name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 极简 glob：只支持 {@code *}（任意长度）。不引入正则，避免管理员写错正则导致意外匹配。
     */
    static boolean matchesGlob(String pattern, String value) {
        if (!pattern.contains("*")) {
            return pattern.equals(value);
        }
        String[] parts = pattern.split("\\*", -1);
        int cursor = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            if (i == 0) {
                if (!value.startsWith(part)) {
                    return false;
                }
                cursor = part.length();
            } else if (i == parts.length - 1) {
                if (!value.endsWith(part) || value.length() - part.length() < cursor) {
                    return false;
                }
                cursor = value.length();
            } else {
                int found = value.indexOf(part, cursor);
                if (found < 0) {
                    return false;
                }
                cursor = found + part.length();
            }
        }
        return true;
    }
}
