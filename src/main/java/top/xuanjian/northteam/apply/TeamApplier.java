package top.xuanjian.northteam.apply;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import top.xuanjian.northteam.config.PluginConfig;
import top.xuanjian.northteam.model.TeamConfig;
import top.xuanjian.northteam.model.TeamNames;
import top.xuanjian.northteam.model.TeamUnit;
import top.xuanjian.northteam.state.PluginState;
import top.xuanjian.northteam.util.MiniMessages;
import top.xuanjian.northteam.util.TeamConfigValidator;
import top.xuanjian.northteam.util.TeamProperties;
import top.xuanjian.northteam.util.ValidationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 队伍应用器：实现契约 4 的 apply 语义。
 *
 * <p>已确认的产品语义（全量重建）：
 * <ol>
 *   <li>删除配置外的受管队伍（受 {@code apply.wipe_unmanaged_teams} 控制，
 *       {@code apply.protected_teams} 名单内的永不删除）；</li>
 *   <li>按 {@code key} 建/更新队伍（正式队伍名 = {@code nt_ + key}）；</li>
 *   <li>清空并重写成员：在线玩家立即入队，离线玩家记录在案由
 *       {@code PlayerJoinEvent} 在下次登录时补入；</li>
 *   <li>同步记分板（委托 {@link ScoreboardSyncer}）。</li>
 * </ol>
 *
 * <p>本类只操作「本插件命名空间」（{@code nt_} / {@code sb_}）内的队伍，
 * 并在删除前再次核对保护名单，绝不触碰其它插件的记分板对象。
 *
 * <p><b>所有方法必须在主线程调用</b>（Bukkit 记分板非线程安全）。
 */
public final class TeamApplier {

    private final PluginState state;
    private final Supplier<PluginConfig> configSupplier;
    private final Consumer<String> infoLog;
    private final Consumer<String> warnLog;
    private final ScoreboardSyncer scoreboardSyncer = new ScoreboardSyncer();

    public TeamApplier(PluginState state, Supplier<PluginConfig> configSupplier,
                       Consumer<String> infoLog, Consumer<String> warnLog) {
        this.state = state;
        this.configSupplier = configSupplier;
        this.infoLog = infoLog == null ? message -> { } : infoLog;
        this.warnLog = warnLog == null ? message -> { } : warnLog;
    }

    /** 按契约 {@code sort_order} 排序；相同则按 key 稳定排序。 */
    public static List<TeamUnit> sortedUnits(TeamConfig config) {
        List<TeamUnit> units = new ArrayList<>(config.unitsOrEmpty());
        units.sort(Comparator.comparingInt(TeamUnit::sortOrder)
                .thenComparing(unit -> unit.key() == null ? "" : unit.key()));
        return units;
    }

    // ------------------------------------------------------------------
    // 预演 / 执行
    // ------------------------------------------------------------------

    /**
     * 只读推导本次将发生的变更（供 {@code --dry-run} 与执行前对照）。
     */
    public ApplyPlan plan(TeamConfig config) {
        PluginConfig pluginConfig = configSupplier.get();
        Scoreboard scoreboard = mainScoreboard();
        List<TeamUnit> units = sortedUnits(config);

        Set<String> desired = new LinkedHashSet<>();
        for (TeamUnit unit : units) {
            if (unit.key() != null && !unit.key().isBlank()) {
                desired.add(unit.teamName());
            }
        }

        List<String> create = new ArrayList<>();
        List<String> update = new ArrayList<>();
        List<String> delete = new ArrayList<>();

        for (String name : desired) {
            if (scoreboard != null && scoreboard.getTeam(name) != null) {
                update.add(name);
            } else {
                create.add(name);
            }
        }

        if (pluginConfig.wipeUnmanagedTeams()) {
            Set<String> candidate = new LinkedHashSet<>();
            if (scoreboard != null) {
                for (Team team : scoreboard.getTeams()) {
                    if (TeamNames.isFormal(team.getName())) {
                        candidate.add(team.getName());
                    }
                }
            }
            // state.json 记录过的也纳入候选：即使队伍已被别的插件提前删掉，
            // 多算一次删除候选也不会造成副作用（执行时会跳过不存在的队伍）。
            candidate.addAll(state.snapshot().formalTeamsOrEmpty());
            for (String name : candidate) {
                if (desired.contains(name) || pluginConfig.isProtected(name)) {
                    continue;
                }
                delete.add(name);
            }
        }

        List<String> assign = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (TeamUnit unit : units) {
            for (String member : unit.normalizedMembers()) {
                Player online = Bukkit.getPlayerExact(member);
                if (online != null && online.isOnline()) {
                    assign.add(online.getName());
                } else {
                    pending.add(member);
                }
            }
        }

        List<String> leave = new ArrayList<>();
        if (scoreboard != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (config.unitOfMember(player.getName()).isPresent()) {
                    continue;
                }
                Team current = scoreboard.getEntryTeam(player.getName());
                if (current != null && TeamNames.isFormal(current.getName())) {
                    leave.add(player.getName());
                }
            }
        }

        return new ApplyPlan(List.copyOf(create), List.copyOf(update), List.copyOf(delete),
                List.copyOf(assign), List.copyOf(pending), List.copyOf(leave),
                sidebarRowsFor(config, pluginConfig));
    }

    /**
     * 执行一次 apply（契约 4.2 / 4.3 / 4.4）。
     *
     * @param source 配置来源描述（"官网" / "本地缓存"），写入 state.json 与日志
     * @param dryRun true 时只打印计划
     * @param force  true 时忽略 version 去重
     */
    public ApplyOutcome apply(TeamConfig config, String source, boolean dryRun, boolean force) {
        List<String> messages = new ArrayList<>();

        ValidationResult validation = TeamConfigValidator.validate(config);
        if (validation.hasErrors()) {
            messages.add("<red>配置校验未通过，已中止 apply（未修改服务器任何状态）：");
            validation.errors().forEach(error -> messages.add("<red> • " + error));
            return ApplyOutcome.failure(messages);
        }
        validation.warnings().forEach(warning -> messages.add("<yellow> • " + warning));

        PluginConfig pluginConfig = configSupplier.get();
        var previous = state.snapshot();

        // 契约 2.1 / 4.2：version 相同即跳过（除非 --force）
        if (!dryRun && !force && config.id() != null
                && config.id().equals(previous.appliedConfigId())
                && config.versionOrEmpty().equals(previous.appliedVersion())
                && !config.versionOrEmpty().isEmpty()) {
            messages.add("<gray>配置 <white>" + config.id() + "</white> 的 version（"
                    + config.versionOrEmpty() + "）与当前已应用版本相同，已跳过。");
            messages.add("<gray>如需强制重放，请使用 <white>/nt apply " + config.id() + " --force</white>。");
            return ApplyOutcome.skipped(messages);
        }

        ApplyPlan plan = plan(config);

        if (dryRun) {
            messages.add("<gold>[预演] 以下变更不会真正执行：</gold>");
            messages.addAll(plan.describe());
            return new ApplyOutcome(true, true, false, plan, messages);
        }

        Scoreboard scoreboard = mainScoreboard();
        if (scoreboard == null) {
            messages.add("<red>主记分板不可用（服务器尚未就绪），已中止 apply。");
            return ApplyOutcome.failure(messages);
        }

        try {
            int deleted = deleteUnmanaged(scoreboard, plan, pluginConfig, messages);
            int written = writeUnits(scoreboard, config, messages);
            int removed = evictUnlistedOnlinePlayers(scoreboard, config);

            ScoreboardSyncer.SyncResult sync = scoreboardSyncer.sync(
                    config, pluginConfig, scoreboard, previous, false);
            messages.addAll(sync.warnings());

            List<String> formalTeams = new ArrayList<>();
            List<String> sidebarTeams = new ArrayList<>(sync.sidebarTeams());
            for (TeamUnit unit : sortedUnits(config)) {
                if (unit.key() != null && !unit.key().isBlank()) {
                    formalTeams.add(unit.teamName());
                }
            }

            state.recordApplySuccess(config, source, sync.objective(), sync.enabled(),
                    formalTeams, sidebarTeams);

            infoLog.accept("apply 完成：配置 " + config.id() + "（" + config.nameOrFallback()
                    + "）来源=" + source + "，删除 " + deleted + " 队 / 写入 " + written
                    + " 队 / 移出 " + removed + " 名在线玩家 / 侧边栏 " + sync.rows() + " 行");

            messages.add("<green>已应用配置 <white>" + config.id() + "</white>（"
                    + config.nameOrFallback() + "，来源：" + source + "）。");
            messages.add("<gray>队伍 <white>" + written + "</white> 个，删除 <white>" + deleted
                    + "</white> 个，侧边栏 <white>" + sync.rows() + "</white> 行，"
                    + "待登录补入 <white>" + plan.pendingCount() + "</white> 人。");
            return new ApplyOutcome(true, false, false, plan, messages);
        } catch (RuntimeException e) {
            // 契约要求：绝不把栈抛给玩家
            warnLog.accept("apply 过程中出现异常：" + e);
            messages.add("<red>apply 过程中出现异常，已中止：" + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            return ApplyOutcome.failure(messages);
        }
    }

    // ------------------------------------------------------------------
    // 单个玩家登录补入（契约 4.2）
    // ------------------------------------------------------------------

    /**
     * 玩家登录时按已应用配置补入其队伍。
     *
     * @return true 表示确实做了入队操作
     */
    public boolean assignOnJoin(Player player) {
        PluginConfig pluginConfig = configSupplier.get();
        if (!pluginConfig.assignOnJoin()) {
            return false;
        }
        TeamConfig applied = state.appliedConfig().orElse(null);
        if (applied == null) {
            return false;
        }
        Scoreboard scoreboard = mainScoreboard();
        if (scoreboard == null) {
            return false;
        }
        TeamUnit unit = applied.unitOfMember(player.getName()).orElse(null);
        if (unit == null) {
            return false;
        }
        try {
            String teamName = unit.teamName();
            Team team = scoreboard.getTeam(teamName);
            if (team == null) {
                // 队伍在重启后丢失（记分板不持久化）且 auto_reapply_on_start=false 时会走到这里
                warnLog.accept("玩家 " + player.getName() + " 应入队 " + teamName
                        + "，但该队伍不存在；请执行 /nt apply " + applied.id() + " 重建。");
                return false;
            }
            if (!unit.normalizedMembers().stream().anyMatch(name -> name.equalsIgnoreCase(player.getName()))) {
                return false;
            }
            team.addEntry(player.getName());
            return true;
        } catch (RuntimeException e) {
            warnLog.accept("玩家 " + player.getName() + " 登录补入队伍失败：" + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private int deleteUnmanaged(Scoreboard scoreboard, ApplyPlan plan, PluginConfig pluginConfig,
                                List<String> messages) {
        int deleted = 0;
        for (String name : plan.teamsToDelete()) {
            if (pluginConfig.isProtected(name)) {
                continue;
            }
            Team team = scoreboard.getTeam(name);
            if (team == null) {
                continue;
            }
            try {
                for (String entry : new ArrayList<>(team.getEntries())) {
                    team.removeEntry(entry);
                }
                team.unregister();
                deleted++;
            } catch (RuntimeException e) {
                messages.add("<yellow>删除队伍 " + name + " 失败：" + e.getMessage());
            }
        }
        return deleted;
    }

    private int writeUnits(Scoreboard scoreboard, TeamConfig config, List<String> messages) {
        int written = 0;
        for (TeamUnit unit : sortedUnits(config)) {
            if (unit.key() == null || unit.key().isBlank()) {
                continue;
            }
            String teamName = unit.teamName();
            try {
                Team team = scoreboard.getTeam(teamName);
                if (team == null) {
                    team = scoreboard.registerNewTeam(teamName);
                }
                applyUnit(team, unit, messages);

                // 「清空并重写成员」：先移除全部 entry，再按名单写入在线玩家
                for (String entry : new ArrayList<>(team.getEntries())) {
                    team.removeEntry(entry);
                }
                for (String member : unit.normalizedMembers()) {
                    Player online = Bukkit.getPlayerExact(member);
                    if (online != null && online.isOnline()) {
                        team.addEntry(online.getName());
                    }
                }
                written++;
            } catch (RuntimeException e) {
                messages.add("<red>写入队伍 " + teamName + " 失败：" + e.getMessage());
            }
        }
        return written;
    }

    /** 把不在新名单内的在线玩家移出本插件的正式队伍。 */
    private int evictUnlistedOnlinePlayers(Scoreboard scoreboard, TeamConfig config) {
        int removed = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            String name = player.getName();
            if (config.unitOfMember(name).isPresent()) {
                continue;
            }
            Team current = scoreboard.getEntryTeam(name);
            if (current != null && TeamNames.isFormal(current.getName())) {
                try {
                    current.removeEntry(name);
                    removed++;
                } catch (RuntimeException e) {
                    warnLog.accept("移出玩家 " + name + " 失败：" + e.getMessage());
                }
            }
        }
        return removed;
    }

    private void applyUnit(Team team, TeamUnit unit, List<String> messages) {
        team.displayName(MiniMessages.parse(unit.displayName()));
        team.prefix(MiniMessages.parse(unit.prefix() == null ? "" : unit.prefix()));
        team.suffix(MiniMessages.parse(unit.suffix() == null ? "" : unit.suffix()));
        team.color(TeamProperties.color(unit.color()));
        team.setAllowFriendlyFire(unit.friendlyFire());
        team.setCanSeeFriendlyInvisibles(unit.seeFriendlyInvisibles());

        setOption(team, "NAME_TAG_VISIBILITY",
                TeamProperties.optionStatusNameForVisibility(unit.nametagVisibility()), messages);
        setOption(team, "DEATH_MESSAGE_VISIBILITY",
                TeamProperties.optionStatusNameForVisibility(unit.deathMessageVisibility()), messages);
        setOption(team, "COLLISION_RULE",
                TeamProperties.optionStatusNameForCollision(unit.collisionRule()), messages);
    }

    private void setOption(Team team, String optionName, String statusName, List<String> messages) {
        Team.Option option = TeamProperties.optionOrNull(optionName);
        if (option == null) {
            messages.add("<yellow>当前服务端不支持队伍选项 " + optionName + "，已跳过（不影响其它字段）。");
            return;
        }
        Team.OptionStatus status = TeamProperties.optionStatusOrNull(statusName);
        if (status == null) {
            messages.add("<yellow>当前服务端不支持 " + optionName + " 的取值 " + statusName + "，已跳过。");
            return;
        }
        try {
            team.setOption(option, status);
        } catch (RuntimeException e) {
            messages.add("<yellow>设置 " + optionName + "=" + statusName + " 失败：" + e.getMessage());
        }
    }

    private int sidebarRowsFor(TeamConfig config, PluginConfig pluginConfig) {
        var settings = config.scoreboardOrDisabled();
        if (!settings.enabled() || !pluginConfig.scoreboardMasterEnabled()) {
            return 0;
        }
        if (!top.xuanjian.northteam.model.ScoreboardSettings.POSITION_SIDEBAR
                .equals(settings.positionOrDefault())) {
            return 0;
        }
        return Math.min(config.unitsOrEmpty().size(), pluginConfig.scoreboardMaxRows());
    }

    private static Scoreboard mainScoreboard() {
        try {
            var manager = Bukkit.getScoreboardManager();
            return manager == null ? null : manager.getMainScoreboard();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 供命令层展示：在线玩家名（小写）集合，避免重复查询。 */
    static Set<String> onlineNamesLower() {
        Set<String> names = new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
