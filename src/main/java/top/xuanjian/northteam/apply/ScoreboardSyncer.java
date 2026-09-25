package top.xuanjian.northteam.apply;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import top.xuanjian.northteam.config.PluginConfig;
import top.xuanjian.northteam.model.ScoreboardSettings;
import top.xuanjian.northteam.model.StateSnapshot;
import top.xuanjian.northteam.model.TeamConfig;
import top.xuanjian.northteam.model.TeamNames;
import top.xuanjian.northteam.model.TeamUnit;
import top.xuanjian.northteam.util.ContractValues;
import top.xuanjian.northteam.util.MiniMessages;
import top.xuanjian.northteam.util.SidebarEntries;
import top.xuanjian.northteam.util.TeamProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 记分板同步（契约 4.3）。
 *
 * <p>侧边栏（1.1.0 起）采用**两级结构**：
 * <ul>
 *   <li><b>队头行</b>：该队的 {@code display_name}（按 {@code color} 上色），尾部带 {@code " · N"}，
 *       N 取 {@code score_mode=member_count} 的名单人数或 {@code score_mode=fixed} 的
 *       {@code unit_scores[key]}；</li>
 *   <li><b>成员行</b>：紧随其后的该队**在线**成员，每人一行。</li>
 * </ul>
 * 每行仍然是「一个 {@code sb_<i>} 记分板队伍 + 一个不可见 entry」的经典做法：行文本放进
 * 该行队伍的 prefix，entry 只用来挂分值，而分值在这里**只决定行序**（侧边栏按分数从高到低
 * 显示），因此从总行数递减分配。
 *
 * <p>行数同时受 {@code scoreboard.max_rows} 与客户端上限 15 行约束（超出的行客户端不画），
 * 超限时按队伍顺序截断：前面的队伍保持完整，第一支队就装不下时退化为「队头 + 尽量多成员」。
 *
 * <p>安全性：本类只操作两类对象 ——
 * 名字属于本插件命名空间（{@code sb_}）的队伍，以及本插件此前在 {@code state.json}
 * 记录过的侧边栏队伍；并且额外要求队伍带本插件的「指纹」（含本插件生成的不可见 entry）。
 * 其它插件即使占用 {@code sb_} 前缀也不会被误删。
 */
public final class ScoreboardSyncer {

    /** objective 标题长度上限（{@code Objective.displayName(Component)} 的契约限制）。 */
    private static final int OBJECTIVE_NAME_MAX = 128;

    /**
     * 同步结果。
     *
     * @param ok           是否执行成功（创建 objective 失败时为 false）
     * @param rows         侧边栏渲染行数（非 sidebar 位置时为队伍数）
     * @param sidebarTeams 本次使用的侧边栏行队伍名
     * @param rowTexts     侧边栏各行的纯文本（按渲染顺序），供日志与真机验收断言
     * @param objective    objective 名
     * @param enabled      记分板同步是否实际生效
     * @param warnings     需要提示管理员的中文说明行
     */
    public record SyncResult(boolean ok, int rows, List<String> sidebarTeams, List<String> rowTexts,
                             String objective, boolean enabled, List<String> warnings) {
    }

    /** 客户端侧边栏硬上限：一屏最多显示 15 行，多出来的行客户端直接不画。 */
    private static final int SIDEBAR_CLIENT_MAX = 15;

    /**
     * 侧边栏的一行。
     *
     * @param text   行文本（放进该行队伍的 prefix）
     * @param unitKey 归属队伍 key（日志用）
     * @param header 是否是队头行（true=队伍行，false=成员行）
     */
    private record SidebarRow(Component text, String unitKey, boolean header) {
    }

    /**
     * 同步记分板。必须在主线程调用（Bukkit 记分板不是线程安全的）。
     *
     * @param dryRun true 时只推导不修改
     */
    public SyncResult sync(TeamConfig config, PluginConfig pluginConfig, Scoreboard scoreboard,
                           StateSnapshot previous, boolean dryRun) {
        List<String> warnings = new ArrayList<>();
        if (scoreboard == null) {
            warnings.add("<red>主记分板不可用（服务器尚未就绪），已跳过记分板同步。");
            return new SyncResult(false, 0, List.of(), List.of(), "-", false, warnings);
        }

        ScoreboardSettings settings = config.scoreboardOrDisabled();
        String objectiveName = settings.objectiveOrDefault();

        List<TeamUnit> units = new ArrayList<>(config.unitsOrEmpty());
        units.sort(Comparator.comparingInt(TeamUnit::sortOrder)
                .thenComparing(unit -> unit.key() == null ? "" : unit.key()));

        boolean enabled = settings.enabled() && pluginConfig.scoreboardMasterEnabled();
        if (!enabled) {
            if (settings.enabled()) {
                warnings.add("<yellow>官网配置要求启用记分板，但本地 scoreboard.master_enabled=false，已跳过记分板同步并清理本插件的侧边栏。");
            }
            Set<String> owned = ownedSidebarTeams(scoreboard, previous);
            cleanupSidebarRows(scoreboard, owned, Set.of(), dryRun, warnings);
            if (!dryRun) {
                removeObjective(scoreboard, objectiveName, warnings);
            }
            return new SyncResult(true, 0, List.of(), List.of(), objectiveName, false, warnings);
        }

        Set<String> ownedSidebar = ownedSidebarTeams(scoreboard, previous);
        String position = settings.positionOrDefault();
        DisplaySlot slot = slotFor(position);

        if (slot == DisplaySlot.SIDEBAR) {
            Objective objective = dryRun ? null
                    : obtainObjective(scoreboard, objectiveName, settings, warnings);
            if (!dryRun && objective == null) {
                return new SyncResult(false, 0, List.of(), List.of(), objectiveName, false, warnings);
            }

            // 1.1.0：两级结构（队头行 + 在线成员行），并在超限前先算好行计划
            List<SidebarRow> rows = planSidebarRows(units, settings, pluginConfig, warnings);
            int total = rows.size();

            Set<String> needed = new LinkedHashSet<>();
            List<String> sidebarTeams = new ArrayList<>();
            List<String> rowTexts = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                String teamName = TeamNames.sidebarTeamName(i + 1);
                needed.add(teamName);
                sidebarTeams.add(teamName);
                rowTexts.add(MiniMessages.toPlainText(rows.get(i).text()));
                if (dryRun || objective == null) {
                    continue;
                }
                try {
                    Team row = scoreboard.getTeam(teamName);
                    if (row == null) {
                        row = scoreboard.registerNewTeam(teamName);
                    }
                    row.prefix(rows.get(i).text());
                    String entry = sidebarEntry(i);
                    row.addEntry(entry);
                    // 分值只用于决定行序（分数高的在上面），所以按总行数递减
                    objective.getScore(entry).setScore(total - i);
                } catch (RuntimeException e) {
                    warnings.add("<red>侧边栏第 " + (i + 1) + " 行（" + teamName + "）渲染失败：" + e.getMessage());
                }
            }

            cleanupSidebarRows(scoreboard, ownedSidebar, needed, dryRun, warnings);
            if (!dryRun) {
                objective.setDisplaySlot(slot);
            }
            return new SyncResult(true, total, List.copyOf(sidebarTeams), List.copyOf(rowTexts),
                    objectiveName, true, warnings);
        }

        // list / below_name：这两个位置是「按玩家显示」，无法用行队伍方案，
        // 因此给每个成员 entry 上分为其所属队伍的人数。
        Objective objective = dryRun ? null : obtainObjective(scoreboard, objectiveName, settings, warnings);
        if (!dryRun && objective == null) {
            return new SyncResult(false, 0, List.of(), List.of(), objectiveName, false, warnings);
        }
        cleanupSidebarRows(scoreboard, ownedSidebar, Set.of(), dryRun, warnings);

        Set<String> desiredMembers = new LinkedHashSet<>();
        for (TeamUnit unit : units) {
            for (String member : unit.normalizedMembers()) {
                desiredMembers.add(member.toLowerCase(Locale.ROOT));
            }
        }

        if (!dryRun && objective != null) {
            objective.setDisplaySlot(slot);
            resetStalePlayerScores(scoreboard, objective, desiredMembers, warnings);
            for (TeamUnit unit : units) {
                int score = scoreOf(unit, settings);
                for (String member : unit.normalizedMembers()) {
                    try {
                        objective.getScore(member).setScore(score);
                    } catch (RuntimeException e) {
                        warnings.add("<red>为 " + member + " 上分失败：" + e.getMessage());
                    }
                }
            }
        }
        return new SyncResult(true, units.size(), List.of(), List.of(), objectiveName, true, warnings);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 推导侧边栏行计划：每队一行队头，紧随其后是该队**在线**成员各一行。
     *
     * <p>行数受 {@code scoreboard.max_rows} 与客户端上限 15 双重约束。超限时按队伍顺序
     * 截断：前面的队伍保持完整，后面的队伍整体不渲染；如果第一支队伍自己就装不下，
     * 退化成「队头 + 尽量多的成员」，避免整个侧边栏空白。
     */
    private List<SidebarRow> planSidebarRows(List<TeamUnit> units, ScoreboardSettings settings,
                                             PluginConfig pluginConfig, List<String> warnings) {
        int configured = pluginConfig.scoreboardMaxRows();
        int cap = Math.max(1, Math.min(configured, SIDEBAR_CLIENT_MAX));
        if (configured > SIDEBAR_CLIENT_MAX) {
            warnings.add("<yellow>scoreboard.max_rows=" + configured + " 超过客户端侧边栏上限 "
                    + SIDEBAR_CLIENT_MAX + " 行，已按 " + SIDEBAR_CLIENT_MAX + " 处理。");
        }

        List<SidebarRow> rows = new ArrayList<>();
        boolean truncated = false;
        for (TeamUnit unit : units) {
            List<String> online = onlineMembers(unit);
            int need = 1 + online.size();
            if (rows.size() + need > cap) {
                if (rows.isEmpty()) {
                    rows.add(headerRow(unit, settings));
                    int room = Math.max(0, cap - 1);
                    int shown = Math.min(room, online.size());
                    for (int i = 0; i < shown; i++) {
                        rows.add(memberRow(unit, online.get(i)));
                    }
                    warnings.add("<yellow>队伍 " + unit.safeKey() + " 的在线成员超过侧边栏上限（"
                            + cap + " 行），只列出前 " + shown + " 名。");
                } else {
                    truncated = true;
                }
                break;
            }
            rows.add(headerRow(unit, settings));
            for (String name : online) {
                rows.add(memberRow(unit, name));
            }
        }
        if (truncated) {
            warnings.add("<yellow>侧边栏上限 " + cap + " 行，已按队伍顺序省略后面的队伍（已渲染 "
                    + rows.size() + " 行）。");
        }
        return rows;
    }

    /**
     * 队头行文本：该队 {@code display_name} + {@code " · N"}。
     *
     * <p>刻意**不再拼接 prefix**：按契约 prefix/suffix 是记分板队伍的前后缀（作用于头顶名牌
     * 与聊天），1.0.x 把它们也拼进侧边栏行，于是配置里 prefix 与 display_name 都写队名时，
     * 侧边栏就会出现「队名队名」的重复。
     */
    private static SidebarRow headerRow(TeamUnit unit, ScoreboardSettings settings) {
        NamedTextColor color = TeamProperties.color(unit.color());
        Component text = MiniMessages.parse(unit.displayName())
                .append(Component.text(" · " + scoreOf(unit, settings)))
                .colorIfAbsent(color);
        return new SidebarRow(text, unit.safeKey(), true);
    }

    /** 成员行文本：在线成员名，按该队颜色上色。 */
    private static SidebarRow memberRow(TeamUnit unit, String memberName) {
        NamedTextColor color = TeamProperties.color(unit.color());
        return new SidebarRow(Component.text(memberName).colorIfAbsent(color), unit.safeKey(), false);
    }

    /** 该队当前在线的成员（保持配置名单里的顺序）。 */
    private static List<String> onlineMembers(TeamUnit unit) {
        List<String> online = new ArrayList<>();
        for (String member : unit.normalizedMembers()) {
            Player player = Bukkit.getPlayerExact(member);
            if (player != null && player.isOnline()) {
                online.add(member);
            }
        }
        return online;
    }

    /**
     * 分数：{@code member_count} 取该队名单人数；
     * {@code fixed} 取 {@code scoreboard.unit_scores[key]}（契约保留字段）。
     */
    private static int scoreOf(TeamUnit unit, ScoreboardSettings settings) {
        if (ScoreboardSettings.MODE_FIXED.equals(settings.scoreModeOrDefault())) {
            Integer fixed = settings.unitScoresOrEmpty().get(unit.key());
            if (fixed != null) {
                return fixed;
            }
        }
        return unit.normalizedMembers().size();
    }

    private static DisplaySlot slotFor(String position) {
        return switch (position) {
            case ScoreboardSettings.POSITION_LIST -> DisplaySlot.PLAYER_LIST;
            case ScoreboardSettings.POSITION_BELOW_NAME -> DisplaySlot.BELOW_NAME;
            default -> DisplaySlot.SIDEBAR;
        };
    }

    private static Objective obtainObjective(Scoreboard scoreboard, String name,
                                             ScoreboardSettings settings, List<String> warnings) {
        Component displayName = MiniMessages.parse(settings.displayNameOrDefault());
        String plain = MiniMessages.toPlainText(displayName);
        if (plain.length() > OBJECTIVE_NAME_MAX) {
            displayName = Component.text(plain.substring(0, OBJECTIVE_NAME_MAX));
            warnings.add("<yellow>scoreboard.display_name 超过 " + OBJECTIVE_NAME_MAX
                    + " 字符，已截断显示。");
        }
        try {
            Objective existing = scoreboard.getObjective(name);
            if (existing != null) {
                existing.displayName(displayName);
                return existing;
            }
            return scoreboard.registerNewObjective(name, Criteria.DUMMY, displayName);
        } catch (RuntimeException e) {
            warnings.add("<red>创建/更新 objective " + name + " 失败：" + e.getMessage()
                    + "（可能是同名 objective 已被其它插件占用）");
            return null;
        }
    }

    private static void removeObjective(Scoreboard scoreboard, String name, List<String> warnings) {
        try {
            Objective objective = scoreboard.getObjective(name);
            if (objective != null) {
                objective.unregister();
            }
        } catch (RuntimeException e) {
            warnings.add("<yellow>清理 objective " + name + " 时出现问题：" + e.getMessage());
        }
    }

    /**
     * 收集「属于本插件」的侧边栏队伍：state.json 记录过的，或名字在 {@code sb_} 命名空间
     * 且带本插件指纹（含本插件生成的不可见 entry）。
     */
    private static Set<String> ownedSidebarTeams(Scoreboard scoreboard, StateSnapshot previous) {
        Set<String> owned = new LinkedHashSet<>(previous.sidebarTeamsOrEmpty());
        for (Team team : scoreboard.getTeams()) {
            if (TeamNames.isSidebar(team.getName()) && hasOurSignature(team)) {
                owned.add(team.getName());
            }
        }
        return owned;
    }

    private static void cleanupSidebarRows(Scoreboard scoreboard, Set<String> owned,
                                           Set<String> needed, boolean dryRun, List<String> warnings) {
        for (String name : owned) {
            if (needed.contains(name)) {
                continue;
            }
            Team team = scoreboard.getTeam(name);
            if (team == null) {
                continue;
            }
            if (dryRun) {
                continue;
            }
            try {
                for (String entry : new ArrayList<>(team.getEntries())) {
                    if (isOurSidebarEntry(entry)) {
                        // resetScores 会同时清掉 objective 上的分值，避免留下孤儿行
                        scoreboard.resetScores(entry);
                    }
                }
                team.unregister();
            } catch (RuntimeException e) {
                warnings.add("<yellow>清理侧边栏行队伍 " + name + " 失败：" + e.getMessage());
            }
        }
    }

    /** 非 sidebar 位置：把不再属于任何队伍的玩家分值从本 objective 上清掉。 */
    private static void resetStalePlayerScores(Scoreboard scoreboard, Objective objective,
                                               Set<String> desiredMembers, List<String> warnings) {
        try {
            for (String entry : new ArrayList<>(scoreboard.getEntries())) {
                if (!ContractValues.PLAYER_NAME_PATTERN.matcher(entry).matches()) {
                    continue;
                }
                if (desiredMembers.contains(entry.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                objective.getScore(entry).resetScore();
            }
        } catch (RuntimeException e) {
            warnings.add("<yellow>清理旧分值失败：" + e.getMessage());
        }
    }

    /**
     * 第 index 行（0 基）的不可见 entry。
     * 形如 {@code §a§0§r}：两个十六进制字符保证同一侧边栏内唯一，且渲染宽度为 0。
     */
    private static String sidebarEntry(int index) {
        return SidebarEntries.entry(index);
    }

    /** 判断 entry 是否为本插件生成的侧边栏占位串。 */
    private static boolean isOurSidebarEntry(String entry) {
        return SidebarEntries.isOurs(entry);
    }

    private static boolean hasOurSignature(Team team) {
        for (String entry : team.getEntries()) {
            if (isOurSidebarEntry(entry)) {
                return true;
            }
        }
        return false;
    }
}
