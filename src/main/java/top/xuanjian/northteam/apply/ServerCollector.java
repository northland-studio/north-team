package top.xuanjian.northteam.apply;

import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import top.xuanjian.northteam.model.ScoreboardSettings;
import top.xuanjian.northteam.model.TeamConfig;
import top.xuanjian.northteam.model.TeamNames;
import top.xuanjian.northteam.model.TeamUnit;
import top.xuanjian.northteam.util.ContractValues;
import top.xuanjian.northteam.util.MiniMessages;
import top.xuanjian.northteam.util.TeamProperties;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 采集服务端现有记分板队伍，转成契约 2.1 的 {@link TeamConfig}
 * （契约 3.3 {@code POST /api/team/import} 的请求体来源）。
 *
 * <p>采集范围：
 * <ul>
 *   <li>**包含**所有正式/第三方 scoreboard team（契约与任务要求「服务器全部 scoreboard team」）；</li>
 *   <li>**排除**本插件内部的侧边栏行队伍（{@code sb_*}，含本插件指纹），它们是渲染产物而非真实队伍。</li>
 * </ul>
 *
 * <p>必须在主线程调用（读取 Bukkit 记分板）。
 */
public final class ServerCollector {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * @param config   可直接作为 {@code POST /api/team/import} 的请求体
     * @param warnings 需要提示管理员的中文说明
     */
    public record Collected(TeamConfig config, List<String> warnings) {

        public int unitCount() {
            return config.unitsOrEmpty().size();
        }

        public int memberCount() {
            return config.unitsOrEmpty().stream()
                    .mapToInt(unit -> unit.normalizedMembers().size())
                    .sum();
        }
    }

    /**
     * @param name 采集配置名；为空时使用「服务器采集 &lt;时间&gt;」
     */
    public Collected collect(String name) {
        List<String> warnings = new ArrayList<>();
        Scoreboard scoreboard = mainScoreboard();
        if (scoreboard == null) {
            warnings.add("<red>主记分板不可用，无法采集。");
            return new Collected(emptyConfig(name), warnings);
        }

        List<Team> teams = new ArrayList<>(scoreboard.getTeams());
        teams.sort(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER));

        List<TeamUnit> units = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        int index = 0;
        int skippedSidebar = 0;

        for (Team team : teams) {
            if (TeamNames.isSidebar(team.getName()) && isPluginSidebarRow(team)) {
                skippedSidebar++;
                continue;
            }
            index++;
            String key = uniqueKey(deriveKey(team.getName()), usedKeys);
            units.add(toUnit(team, key, index, warnings));
        }

        if (index == 0) {
            warnings.add("<yellow>服务端当前没有任何可采集的 scoreboard team。");
        }
        if (skippedSidebar > 0) {
            warnings.add("<gray>已跳过 " + skippedSidebar + " 个本插件内部侧边栏行队伍（sb_*）。");
        }

        ScoreboardSettings settings = readScoreboardSettings(scoreboard);
        String configName = (name == null || name.isBlank())
                ? "服务器采集 " + LocalDateTime.now().format(STAMP)
                : name.trim();

        TeamConfig config = new TeamConfig(
                1,
                null,
                configName,
                "由 NorthTeam 插件在服务器执行 /nt import 采集（" + LocalDateTime.now().format(STAMP) + "）",
                null,
                false,
                null,
                settings,
                List.copyOf(units));
        return new Collected(config, warnings);
    }

    private static TeamConfig emptyConfig(String name) {
        return new TeamConfig(1, null,
                name == null || name.isBlank() ? "服务器采集" : name.trim(),
                "由 NorthTeam 插件采集", null, false, null,
                ScoreboardSettings.disabled(), List.of());
    }

    private TeamUnit toUnit(Team team, String key, int sortOrder, List<String> warnings) {
        List<String> members = new ArrayList<>();
        for (String entry : team.getEntries()) {
            if (ContractValues.PLAYER_NAME_PATTERN.matcher(entry).matches()) {
                members.add(entry);
            }
        }

        String displayName = MiniMessages.toPlainText(team.displayName());
        if (displayName.isBlank()) {
            displayName = team.getName();
        }
        if (displayName.length() > ContractValues.DISPLAY_NAME_MAX) {
            warnings.add("<yellow>队伍 " + team.getName() + " 的 display_name 超过 "
                    + ContractValues.DISPLAY_NAME_MAX + " 字符，已截断回传。");
            displayName = displayName.substring(0, ContractValues.DISPLAY_NAME_MAX);
        }

        String prefix = cap(MiniMessages.serialize(team.prefix()), team.getName(), "prefix", warnings);
        String suffix = cap(MiniMessages.serialize(team.suffix()), team.getName(), "suffix", warnings);

        String colorName = "white";
        try {
            if (team.hasColor() && team.color() instanceof net.kyori.adventure.text.format.NamedTextColor named) {
                colorName = TeamProperties.colorName(named);
            }
        } catch (RuntimeException ignored) {
            // 颜色读取失败按白色回传
        }

        return new TeamUnit(
                key,
                displayName,
                colorName,
                prefix,
                suffix,
                team.allowFriendlyFire(),
                team.canSeeFriendlyInvisibles(),
                visibility(team, "NAME_TAG_VISIBILITY"),
                visibility(team, "DEATH_MESSAGE_VISIBILITY"),
                collision(team, "COLLISION_RULE"),
                sortOrder,
                List.copyOf(members));
    }

    /** 契约 2.1：prefix/suffix 长度 ≤ 64（含标签），超长需截断，否则官网会 400。 */
    private static String cap(String value, String teamName, String field, List<String> warnings) {
        if (value == null) {
            return "";
        }
        if (value.length() > ContractValues.PREFIX_SUFFIX_MAX) {
            warnings.add("<yellow>队伍 " + teamName + " 的 " + field + " 超过 "
                    + ContractValues.PREFIX_SUFFIX_MAX + " 字符，已截断回传。");
            return value.substring(0, ContractValues.PREFIX_SUFFIX_MAX);
        }
        return value;
    }

    private static String visibility(Team team, String optionName) {
        String statusName = TeamProperties.currentOptionStatusName(team, optionName);
        return statusName == null ? "always" : TeamProperties.visibilityFromOptionStatus(statusName);
    }

    private static String collision(Team team, String optionName) {
        String statusName = TeamProperties.currentOptionStatusName(team, optionName);
        return statusName == null ? "always" : TeamProperties.collisionFromOptionStatus(statusName);
    }

    /** 反映服务端真实记分板状态，而不是写死 enabled=false。 */
    private static ScoreboardSettings readScoreboardSettings(Scoreboard scoreboard) {
        String objectiveName = ScoreboardSettings.DEFAULT_OBJECTIVE;
        boolean enabled = false;
        String displayName = ScoreboardSettings.DEFAULT_DISPLAY_NAME;
        String position = ScoreboardSettings.POSITION_SIDEBAR;
        try {
            for (Objective objective : scoreboard.getObjectives()) {
                if (objective.getDisplaySlot() != null) {
                    objectiveName = objective.getName();
                    displayName = MiniMessages.serialize(objective.displayName());
                    position = switch (objective.getDisplaySlot()) {
                        case PLAYER_LIST -> ScoreboardSettings.POSITION_LIST;
                        case BELOW_NAME -> ScoreboardSettings.POSITION_BELOW_NAME;
                        default -> ScoreboardSettings.POSITION_SIDEBAR;
                    };
                    enabled = true;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // 读取失败按「未启用」回传
        }
        return new ScoreboardSettings(enabled, objectiveName, displayName, position,
                ScoreboardSettings.MODE_MEMBER_COUNT, java.util.Map.of());
    }

    /**
     * 由记分板队伍名派生契约 {@code key}：剥掉 {@code nt_} 前缀，再规整到
     * {@code [a-z0-9_]{1,16}}。
     */
    static String deriveKey(String teamName) {
        String base = teamName == null ? "" : teamName;
        if (TeamNames.isFormal(base)) {
            base = base.substring(TeamNames.FORMAL_PREFIX.length());
        }
        StringBuilder out = new StringBuilder();
        for (char c : base.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        String key = out.toString();
        if (key.isEmpty()) {
            key = "team";
        }
        return key.length() > 16 ? key.substring(0, 16) : key;
    }

    /** 保证 key 在本次采集内唯一（契约 2.1：配置内唯一）。 */
    static String uniqueKey(String key, Set<String> used) {
        if (used.add(key)) {
            return key;
        }
        for (int i = 2; i < 1000; i++) {
            String suffix = "_" + i;
            String candidate = key.length() + suffix.length() > 16
                    ? key.substring(0, 16 - suffix.length()) + suffix
                    : key + suffix;
            if (used.add(candidate)) {
                return candidate;
            }
        }
        // 理论上到不了这里
        String fallback = "team" + used.size();
        used.add(fallback);
        return fallback;
    }

    /** 是否为「本插件生成的」侧边栏行队伍（带不可见 entry 指纹）。 */
    private static boolean isPluginSidebarRow(Team team) {
        for (String entry : team.getEntries()) {
            if (top.xuanjian.northteam.util.SidebarEntries.isOurs(entry)) {
                return true;
            }
        }
        return false;
    }

    private static Scoreboard mainScoreboard() {
        try {
            var manager = Bukkit.getScoreboardManager();
            return manager == null ? null : manager.getMainScoreboard();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
