package top.xuanjian.northteam.apply;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
 * <p>侧边栏采用「每队一行 + 每行一个记分板队伍」的经典做法：
 * 为第 i 行创建一个 {@code sb_<i>} 队伍，把行文本放进它的 prefix，
 * 再用一个不可见字符串作为 entry，把 entry 的分数设为该队人数。
 * 这样行文本可以随意着色/加前缀，而分数仍然属于「该队」。
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
     * @param objective    objective 名
     * @param enabled      记分板同步是否实际生效
     * @param warnings     需要提示管理员的中文说明行
     */
    public record SyncResult(boolean ok, int rows, List<String> sidebarTeams, String objective,
                             boolean enabled, List<String> warnings) {
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
            return new SyncResult(false, 0, List.of(), "-", false, warnings);
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
            return new SyncResult(true, 0, List.of(), objectiveName, false, warnings);
        }

        Set<String> ownedSidebar = ownedSidebarTeams(scoreboard, previous);
        String position = settings.positionOrDefault();
        DisplaySlot slot = slotFor(position);

        if (slot == DisplaySlot.SIDEBAR) {
            int limit = Math.min(units.size(), pluginConfig.scoreboardMaxRows());
            if (units.size() > limit) {
                warnings.add("<yellow>队伍数 " + units.size() + " 超过 scoreboard.max_rows="
                        + pluginConfig.scoreboardMaxRows() + "，侧边栏只渲染前 " + limit + " 队。");
            }

            Objective objective = dryRun ? null
                    : obtainObjective(scoreboard, objectiveName, settings, warnings);
            if (!dryRun && objective == null) {
                return new SyncResult(false, 0, List.of(), objectiveName, false, warnings);
            }

            Set<String> needed = new LinkedHashSet<>();
            List<String> sidebarTeams = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                TeamUnit unit = units.get(i);
                String teamName = TeamNames.sidebarTeamName(i + 1);
                needed.add(teamName);
                sidebarTeams.add(teamName);
                if (dryRun || objective == null) {
                    continue;
                }
                try {
                    Team row = scoreboard.getTeam(teamName);
                    if (row == null) {
                        row = scoreboard.registerNewTeam(teamName);
                    }
                    row.prefix(lineFor(unit));
                    String entry = sidebarEntry(i);
                    row.addEntry(entry);
                    objective.getScore(entry).setScore(scoreOf(unit, settings));
                } catch (RuntimeException e) {
                    warnings.add("<red>侧边栏第 " + (i + 1) + " 行（" + teamName + "）渲染失败：" + e.getMessage());
                }
            }

            cleanupSidebarRows(scoreboard, ownedSidebar, needed, dryRun, warnings);
            if (!dryRun) {
                objective.setDisplaySlot(slot);
            }
            return new SyncResult(true, limit, List.copyOf(sidebarTeams), objectiveName, true, warnings);
        }

        // list / below_name：这两个位置是「按玩家显示」，无法用行队伍方案，
        // 因此给每个成员 entry 上分为其所属队伍的人数。
        Objective objective = dryRun ? null : obtainObjective(scoreboard, objectiveName, settings, warnings);
        if (!dryRun && objective == null) {
            return new SyncResult(false, 0, List.of(), objectiveName, false, warnings);
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
        return new SyncResult(true, units.size(), List.of(), objectiveName, true, warnings);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 行文本 = prefix + display_name，并按该队 color 上色（已有显式颜色则不覆盖）。 */
    private static Component lineFor(TeamUnit unit) {
        Component prefix = MiniMessages.parse(unit.prefix() == null ? "" : unit.prefix());
        Component name = MiniMessages.parse(unit.displayName());
        NamedTextColor color = TeamProperties.color(unit.color());
        return prefix.append(name).colorIfAbsent(color);
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
