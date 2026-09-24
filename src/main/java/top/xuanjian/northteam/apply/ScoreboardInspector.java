package top.xuanjian.northteam.apply;

import org.bukkit.Bukkit;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import net.kyori.adventure.text.format.NamedTextColor;
import top.xuanjian.northteam.model.TeamConfig;
import top.xuanjian.northteam.model.TeamNames;
import top.xuanjian.northteam.model.TeamUnit;
import top.xuanjian.northteam.util.ContractValues;
import top.xuanjian.northteam.util.MiniMessages;
import top.xuanjian.northteam.util.SidebarEntries;
import top.xuanjian.northteam.util.TeamProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 读取服务端记分板的**当前实际状态**，用于：
 * <ul>
 *   <li>契约 4「{@code /nt info <id>} 标出与当前服务器状态的差异」；</li>
 *   <li>{@code /nt status} 展示当前受管队伍与侧边栏行数（同时是 CI 真机验收的断言依据）。</li>
 * </ul>
 *
 * <p>只读取本插件命名空间（{@code nt_} / {@code sb_}）内的队伍，
 * 不对任何对象做修改。必须在主线程调用。
 */
public final class ScoreboardInspector {

    public record TeamState(
            String name,
            String key,
            String colorName,
            String prefix,
            String suffix,
            String displayName,
            boolean friendlyFire,
            boolean canSeeFriendlyInvisibles,
            String nametagVisibility,
            String deathMessageVisibility,
            String collisionRule,
            List<String> playerEntries,
            List<String> otherEntries
    ) {
        /** 契约 2.1 语义下的成员数（只统计像玩家名的 entry）。 */
        public int memberCount() {
            return playerEntries.size();
        }
    }

    public record ObjectiveState(
            String name,
            String displayName,
            String displaySlot,
            int rows
    ) {
    }

    public record Snapshot(
            boolean available,
            List<TeamState> formalTeams,
            List<TeamState> sidebarTeams,
            ObjectiveState objective
    ) {
        public static Snapshot unavailable() {
            return new Snapshot(false, List.of(), List.of(), null);
        }
    }

    /** 采集当前状态。 */
    public Snapshot capture(String objectiveName) {
        Scoreboard scoreboard = mainScoreboard();
        if (scoreboard == null) {
            return Snapshot.unavailable();
        }

        List<TeamState> formal = new ArrayList<>();
        List<TeamState> sidebar = new ArrayList<>();
        for (Team team : scoreboard.getTeams()) {
            TeamState state = read(team);
            if (state == null) {
                continue;
            }
            if (TeamNames.isFormal(state.name())) {
                formal.add(state);
            } else if (TeamNames.isSidebar(state.name())) {
                sidebar.add(state);
            }
        }
        formal.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        sidebar.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

        ObjectiveState objectiveState = null;
        if (objectiveName != null && !objectiveName.isBlank()) {
            try {
                Objective objective = scoreboard.getObjective(objectiveName);
                if (objective != null) {
                    DisplaySlot slot = objective.getDisplaySlot();
                    objectiveState = new ObjectiveState(
                            objective.getName(),
                            MiniMessages.toPlainText(objective.displayName()),
                            slot == null ? "未显示" : slot.name(),
                            countRows(scoreboard, objective));
                }
            } catch (RuntimeException ignored) {
                // objective 刚被其它插件注销等竞态，视为不存在
            }
        }

        return new Snapshot(true, List.copyOf(formal), List.copyOf(sidebar), objectiveState);
    }

    /**
     * 对比官网配置与当前服务端状态，返回中文差异描述（契约 4：{@code /nt info} 需标出差异）。
     */
    public List<String> diff(TeamConfig config, Snapshot current) {
        List<String> lines = new ArrayList<>();
        if (!current.available()) {
            lines.add("<yellow>主记分板不可用，无法对比当前服务器状态。");
            return lines;
        }

        List<TeamUnit> units = TeamApplier.sortedUnits(config);
        Set<String> desired = new java.util.LinkedHashSet<>();
        for (TeamUnit unit : units) {
            if (unit.key() == null || unit.key().isBlank()) {
                continue;
            }
            desired.add(unit.teamName());
            TeamState actual = find(current.formalTeams(), unit.teamName());
            if (actual == null) {
                lines.add("<yellow>缺失：队伍 <white>" + unit.teamName()
                        + "</white> 尚未在服务端创建（需要 apply）。");
                continue;
            }
            compareUnit(lines, unit, actual);
        }

        for (TeamState state : current.formalTeams()) {
            if (!desired.contains(state.name())) {
                lines.add("<yellow>多余：服务端存在受管队伍 <white>" + state.name()
                        + "</white>，但不在本配置内（apply 会删除）。");
            }
        }

        var settings = config.scoreboardOrDisabled();
        String objectiveName = settings.objectiveOrDefault();
        if (settings.enabled()) {
            if (current.objective() == null) {
                lines.add("<yellow>记分板：objective <white>" + objectiveName
                        + "</white> 尚未创建（需要 apply）。");
            } else if (current.objective().rows() != units.size()) {
                // 行数可能被本地 scoreboard.max_rows 截断，故这里只提示「不一致」，
                // 由管理员结合 config.yml 的 max_rows 判断是否符合预期。
                lines.add("<yellow>记分板：objective <white>" + objectiveName + "</white> 当前 "
                        + current.objective().rows() + " 行，配置队伍数 " + units.size()
                        + "（若设置了 scoreboard.max_rows 则行数会被上限截断）。");
            }
        }

        if (lines.isEmpty()) {
            lines.add("<green>服务端状态与配置一致，无需变更。");
        }
        return lines;
    }

    /** 供 {@code /nt status} 展示当前实际状态（也是 CI 断言的输出来源）。 */
    public List<String> describe(Snapshot current) {
        List<String> lines = new ArrayList<>();
        if (!current.available()) {
            lines.add("<yellow>主记分板不可用。");
            return lines;
        }

        lines.add("<gray>服务端正式队伍（nt_*）：<white>" + current.formalTeams().size() + "</white> 个");
        for (TeamState state : current.formalTeams()) {
            lines.add("<dark_gray> • <white>" + state.name() + "</white> 颜色=" + state.colorName()
                    + " 前缀=" + quote(state.prefix()) + " 后缀=" + quote(state.suffix())
                    + " 成员=" + state.memberCount()
                    + " 友伤=" + state.friendlyFire()
                    + " 看见隐身队友=" + state.canSeeFriendlyInvisibles()
                    + " 名牌=" + state.nametagVisibility()
                    + " 死亡消息=" + state.deathMessageVisibility()
                    + " 碰撞=" + state.collisionRule());
        }

        ObjectiveState objective = current.objective();
        if (objective == null) {
            lines.add("<gray>记分板 objective：<yellow>不存在</yellow>");
        } else {
            lines.add("<gray>记分板 objective <white>" + objective.name() + "</white>：标题="
                    + quote(objective.displayName()) + " 位置=" + objective.displaySlot()
                    + " 行数=" + objective.rows());
        }
        lines.add("<gray>侧边栏行队伍（sb_*）：<white>" + current.sidebarTeams().size() + "</white> 个");
        return lines;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static void compareUnit(List<String> lines, TeamUnit unit, TeamState actual) {
        String expectedColor = unit.color() == null ? "white" : unit.color().toLowerCase(Locale.ROOT);
        if (!expectedColor.equals(actual.colorName())) {
            lines.add("<yellow>差异：<white>" + unit.teamName() + "</white> 颜色 服务端="
                    + actual.colorName() + "，配置=" + expectedColor);
        }
        String expectedPrefix = MiniMessages.toPlainText(MiniMessages.parse(
                unit.prefix() == null ? "" : unit.prefix()));
        if (!expectedPrefix.equals(MiniMessages.stripTags(actual.prefix()))) {
            lines.add("<yellow>差异：<white>" + unit.teamName() + "</white> 前缀 服务端="
                    + quote(actual.prefix()) + "，配置=" + quote(unit.prefix()));
        }
        if (actual.friendlyFire() != unit.friendlyFire()) {
            lines.add("<yellow>差异：<white>" + unit.teamName() + "</white> 友伤 服务端="
                    + actual.friendlyFire() + "，配置=" + unit.friendlyFire());
        }
        if (actual.canSeeFriendlyInvisibles() != unit.seeFriendlyInvisibles()) {
            lines.add("<yellow>差异：<white>" + unit.teamName() + "</white> 看见隐身队友 服务端="
                    + actual.canSeeFriendlyInvisibles() + "，配置=" + unit.seeFriendlyInvisibles());
        }
        String expectedNametag = unit.nametagVisibility() == null ? "always" : unit.nametagVisibility();
        if (!expectedNametag.equals(actual.nametagVisibility())) {
            lines.add("<yellow>差异：<white>" + unit.teamName() + "</white> 名牌可见性 服务端="
                    + actual.nametagVisibility() + "，配置=" + expectedNametag);
        }
        String expectedDeath = unit.deathMessageVisibility() == null ? "always" : unit.deathMessageVisibility();
        if (!expectedDeath.equals(actual.deathMessageVisibility())) {
            lines.add("<yellow>差异：<white>" + unit.teamName() + "</white> 死亡消息可见性 服务端="
                    + actual.deathMessageVisibility() + "，配置=" + expectedDeath);
        }
        String expectedCollision = unit.collisionRule() == null ? "always" : unit.collisionRule();
        if (!expectedCollision.equals(actual.collisionRule())) {
            lines.add("<yellow>差异：<white>" + unit.teamName() + "</white> 碰撞规则 服务端="
                    + actual.collisionRule() + "，配置=" + expectedCollision);
        }
        // 成员差异：按契约只统计在线玩家对应的 entry
        List<String> expectedMembers = unit.normalizedMembers().stream()
                .filter(name -> Bukkit.getPlayerExact(name) != null)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
        List<String> actualMembers = actual.playerEntries().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
        if (!expectedMembers.equals(actualMembers)) {
            lines.add("<yellow>差异：<white>" + unit.teamName() + "</white> 在线成员 服务端="
                    + actualMembers + "，配置（在线部分）=" + expectedMembers
                    + " <dark_gray>（离线成员由登录事件补入）");
        }
    }

    private static TeamState read(Team team) {
        try {
            List<String> players = new ArrayList<>();
            List<String> others = new ArrayList<>();
            for (String entry : team.getEntries()) {
                if (ContractValues.PLAYER_NAME_PATTERN.matcher(entry).matches()) {
                    players.add(entry);
                } else {
                    others.add(entry);
                }
            }
            players.sort(String::compareToIgnoreCase);

            String colorName = "white";
            try {
                if (team.hasColor()) {
                    var color = team.color();
                    if (color instanceof NamedTextColor named) {
                        colorName = TeamProperties.colorName(named);
                    }
                }
            } catch (RuntimeException ignored) {
                // 颜色读取失败按白色处理
            }

            return new TeamState(
                    team.getName(),
                    TeamNames.isFormal(team.getName())
                            ? team.getName().substring(TeamNames.FORMAL_PREFIX.length())
                            : team.getName(),
                    colorName,
                    MiniMessages.serialize(team.prefix()),
                    MiniMessages.serialize(team.suffix()),
                    MiniMessages.toPlainText(team.displayName()),
                    team.allowFriendlyFire(),
                    team.canSeeFriendlyInvisibles(),
                    visibility(team, "NAME_TAG_VISIBILITY"),
                    visibility(team, "DEATH_MESSAGE_VISIBILITY"),
                    collision(team, "COLLISION_RULE"),
                    List.copyOf(players),
                    List.copyOf(others));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String visibility(Team team, String optionName) {
        String statusName = TeamProperties.currentOptionStatusName(team, optionName);
        if (statusName == null) {
            return "不支持";
        }
        return TeamProperties.visibilityFromOptionStatus(statusName);
    }

    private static String collision(Team team, String optionName) {
        String statusName = TeamProperties.currentOptionStatusName(team, optionName);
        if (statusName == null) {
            return "不支持";
        }
        return TeamProperties.collisionFromOptionStatus(statusName);
    }

    /** 统计 objective 上真正挂了分值、且属于本插件侧边栏方案的行数。 */
    private static int countRows(Scoreboard scoreboard, Objective objective) {
        int rows = 0;
        for (Team team : scoreboard.getTeams()) {
            if (!TeamNames.isSidebar(team.getName())) {
                continue;
            }
            for (String entry : team.getEntries()) {
                if (!SidebarEntries.isOurs(entry)) {
                    continue;
                }
                try {
                    if (objective.getScore(entry).isScoreSet()) {
                        rows++;
                    }
                } catch (RuntimeException ignored) {
                    // 忽略该行
                }
                break;
            }
        }
        return rows;
    }

    private static TeamState find(List<TeamState> states, String name) {
        for (TeamState state : states) {
            if (state.name().equals(name)) {
                return state;
            }
        }
        return null;
    }

    private static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "<dark_gray>(空)</dark_gray>";
        }
        return "<white>\"" + value.replace("<", "\\<") + "\"</white>";
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
