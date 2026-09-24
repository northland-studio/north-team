package top.xuanjian.northteam.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import top.xuanjian.northteam.NorthTeamPlugin;
import top.xuanjian.northteam.api.ApiException;
import top.xuanjian.northteam.apply.ApplyOutcome;
import top.xuanjian.northteam.apply.ServerCollector;
import top.xuanjian.northteam.model.ConfigSummary;
import top.xuanjian.northteam.model.ImportResult;
import top.xuanjian.northteam.model.ScoreboardSettings;
import top.xuanjian.northteam.model.TeamConfig;
import top.xuanjian.northteam.model.TeamUnit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /nt} 命令全集（契约 4）。
 *
 * <pre>
 * /nt list                       列出官网配置
 * /nt info &lt;id&gt;                  查看配置详情 + 与当前服务端状态的差异
 * /nt apply &lt;id&gt; [--dry-run] [--force]  全量重建队伍与记分板
 * /nt import [名称]               采集服务器全部 scoreboard team 回传官网
 * /nt reload                     重读 config.yml
 * /nt status                     显示已应用配置、缓存、API 可达性与当前服务端状态
 * </pre>
 *
 * <p>权限：只读命令用 {@code northteam.view}，写操作用 {@code northteam.admin}
 * （两者在 plugin.yml 里都是 {@code default: op}，且 admin 隐含 view）。
 */
public final class NorthTeamCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_ADMIN = "northteam.admin";
    private static final String PERM_VIEW = "northteam.view";

    private static final List<String> SUBCOMMANDS =
            List.of("list", "info", "apply", "import", "reload", "status");
    private static final List<String> APPLY_FLAGS = List.of("--dry-run", "--force");

    private final NorthTeamPlugin plugin;

    public NorthTeamCommand(NorthTeamPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                if (requireView(sender)) {
                    handleList(sender);
                }
            }
            case "info" -> {
                if (requireView(sender)) {
                    handleInfo(sender, args);
                }
            }
            case "status" -> {
                if (requireView(sender)) {
                    handleStatus(sender);
                }
            }
            case "apply" -> {
                if (requireAdmin(sender)) {
                    handleApply(sender, args);
                }
            }
            case "import" -> {
                if (requireAdmin(sender)) {
                    handleImport(sender, args);
                }
            }
            case "reload" -> {
                if (requireAdmin(sender)) {
                    handleReload(sender);
                }
            }
            default -> sendHelp(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 子命令实现
    // ------------------------------------------------------------------

    private void handleList(CommandSender sender) {
        plugin.send(sender, "<gray>正在拉取官网配置列表…");
        plugin.async(() -> {
            try {
                var response = plugin.api().listConfigs();
                List<ConfigSummary> configs = response.configsOrEmpty();
                Integer appliedId = plugin.state().snapshot().appliedConfigId();
                List<String> lines = new ArrayList<>();
                lines.add("<gold>官网配置共 <white>" + response.countOrSize() + "</white> 条：</gold>");
                for (ConfigSummary summary : configs) {
                    boolean applied = appliedId != null && appliedId == summary.idOrZero();
                    lines.add("<gray> • <white>#" + summary.idOrZero() + "</white> "
                            + summary.nameOrFallback()
                            + " <dark_gray>| 日期 " + summary.eventDateOrDash()
                            + " | 队伍 " + summary.unitCountOrZero()
                            + " | 人数 " + summary.memberCountOrZero()
                            + " | " + (summary.isPublic() ? "<green>已公示" : "<yellow>未公示")
                            + " <dark_gray>| version " + summary.versionOrEmpty()
                            + (applied ? " <green>← 当前已应用" : ""));
                }
                if (configs.isEmpty()) {
                    lines.add("<yellow>官网目前没有任何配置。");
                }
                plugin.sync(() -> lines.forEach(line -> plugin.sendRaw(sender, line)));
            } catch (ApiException e) {
                plugin.sync(() -> reportApiFailure(sender, "拉取配置列表失败", e));
            }
        });
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.send(sender, "<red>用法：/nt info <id>");
            return;
        }
        Integer id = parseId(sender, args[1]);
        if (id == null) {
            return;
        }
        plugin.send(sender, "<gray>正在获取配置 <white>" + id + "</white>…");
        plugin.async(() -> {
            NorthTeamPlugin.LoadedConfig loaded = plugin.loadConfig(id, true);
            plugin.sync(() -> {
                if (loaded == null) {
                    reportUnavailable(sender, id);
                    return;
                }
                TeamConfig config = loaded.config();
                List<String> lines = new ArrayList<>();
                lines.add("<gold>配置 <white>#" + (config.id() == null ? id : config.id())
                        + "</white> " + config.nameOrFallback() + "</gold>");
                lines.add("<gray>来源：<white>" + loaded.source() + "</white>"
                        + " <dark_gray>| version " + config.versionOrEmpty()
                        + " | 日期 " + orDash(config.eventDate())
                        + " | " + (config.isPublic() ? "<green>已公示" : "<yellow>未公示"));
                if (config.description() != null && !config.description().isBlank()) {
                    lines.add("<gray>说明：<white>" + config.description());
                }

                ScoreboardSettings scoreboard = config.scoreboardOrDisabled();
                lines.add("<gray>记分板：<white>" + (scoreboard.enabled() ? "启用" : "关闭")
                        + "</white> <dark_gray>objective=" + scoreboard.objectiveOrDefault()
                        + " position=" + scoreboard.positionOrDefault()
                        + " mode=" + scoreboard.scoreModeOrDefault()
                        + " title=" + scoreboard.displayNameOrDefault());

                for (TeamUnit unit : top.xuanjian.northteam.apply.TeamApplier.sortedUnits(config)) {
                    lines.add("<gray> • <white>" + unit.safeKey() + "</white> " + unit.displayName()
                            + " <dark_gray>颜色=" + unit.color()
                            + " 前缀=" + quote(unit.prefix())
                            + " 后缀=" + quote(unit.suffix())
                            + " 友伤=" + unit.friendlyFire()
                            + " 见隐身=" + unit.seeFriendlyInvisibles()
                            + " 名牌=" + unit.nametagVisibility()
                            + " 死亡消息=" + unit.deathMessageVisibility()
                            + " 碰撞=" + unit.collisionRule()
                            + " 人数=" + unit.normalizedMembers().size());
                }

                String objectiveName = scoreboard.objectiveOrDefault();
                var current = plugin.inspector().capture(objectiveName);
                lines.add("<gold>与当前服务端状态的差异：</gold>");
                lines.addAll(plugin.inspector().diff(config, current));
                plugin.sync(() -> lines.forEach(line -> plugin.sendRaw(sender, line)));
            });
        });
    }

    private void handleApply(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.send(sender, "<red>用法：/nt apply <id> [--dry-run] [--force]");
            return;
        }
        Integer id = parseId(sender, args[1]);
        if (id == null) {
            return;
        }
        boolean dryRun = false;
        boolean force = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "--dry-run" -> dryRun = true;
                case "--force" -> force = true;
                default -> {
                    plugin.send(sender, "<red>未知选项：" + args[i] + "（可用：--dry-run、--force）");
                    return;
                }
            }
        }
        final boolean dryRunFinal = dryRun;
        final boolean forceFinal = force;
        final int configId = id;

        plugin.send(sender, "<gray>正在获取配置 <white>" + configId + "</white>…"
                + (dryRunFinal ? " <yellow>(预演)" : ""));
        plugin.async(() -> {
            NorthTeamPlugin.LoadedConfig loaded = plugin.loadConfig(configId, true);
            plugin.sync(() -> {
                if (loaded == null) {
                    reportUnavailable(sender, configId);
                    return;
                }
                ApplyOutcome outcome = plugin.applier().apply(
                        loaded.config(), loaded.source(), dryRunFinal, forceFinal);
                outcome.messages().forEach(line -> plugin.sendRaw(sender, line));
                if (outcome.success() && !outcome.dryRun() && !outcome.skippedByVersion()) {
                    plugin.refreshSummariesAsync();
                }
            });
        });
    }

    private void handleImport(CommandSender sender, String[] args) {
        String name = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim()
                : null;

        // 采集必须读记分板 → 主线程
        plugin.sync(() -> {
            ServerCollector.Collected collected = plugin.collector().collect(name);
            collected.warnings().forEach(line -> plugin.sendRaw(sender, line));
            if (collected.unitCount() == 0) {
                plugin.send(sender, "<red>服务器当前没有可采集的队伍，已取消回传。");
                return;
            }
            plugin.send(sender, "<gray>已采集 <white>" + collected.unitCount() + "</white> 队 / <white>"
                    + collected.memberCount() + "</white> 人，正在回传官网…");

            plugin.async(() -> {
                try {
                    TeamConfig body = collected.config();
                    ImportResult result = plugin.api().importConfig(
                            body.nameOrFallback(), body.description(), body.eventDate(),
                            body, body.unitsOrEmpty());
                    plugin.sync(() -> {
                        plugin.send(sender, "<green>已回传官网，新配置 ID <white>"
                                + result.idOrZero() + "</white>（" + result.nameOrFallback() + "）。");
                        plugin.send(sender, "<gray>官网统计：队伍 " + result.unitCountOrZero()
                                + " / 人数 " + result.memberCountOrZero() + "。" + result.messageOrEmpty());
                        plugin.send(sender, "<gray>新配置默认不公示，可在官网管理页设为公示。");
                        plugin.refreshSummariesAsync();
                    });
                } catch (ApiException e) {
                    plugin.sync(() -> reportApiFailure(sender, "回传官网失败", e));
                }
            });
        });
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadPluginConfig();
        plugin.send(sender, "<green>config.yml 已重新加载。");
        plugin.send(sender, "<gray>API 地址：<white>" + plugin.config().apiBase()
                + "</white> <dark_gray>| server_key："
                + (plugin.config().hasServerKey() ? "<green>已配置" : "<red>未配置")
                + " <dark_gray>| 保护队伍：" + String.join(", ", plugin.config().protectedTeams())
                + " <dark_gray>| 记分板总开关：" + plugin.config().scoreboardMasterEnabled());
    }

    private void handleStatus(CommandSender sender) {
        plugin.send(sender, "<gray>正在检测状态…");
        // 主线程采集当前服务端状态（CI 验收断言也读这一段）
        plugin.sync(() -> {
            var snapshot = plugin.state().snapshot();
            List<String> lines = new ArrayList<>();
            lines.add("<gold>NorthTeam 状态</gold>");
            lines.add("<gray>插件版本：<white>" + NorthTeamPlugin.PLUGIN_VERSION + "</white>");
            lines.add("<gray>API 地址：<white>" + plugin.config().apiBase() + "</white> <dark_gray>| server_key："
                    + (plugin.config().hasServerKey() ? "<green>已配置" : "<red>未配置") + "<gray> | 超时 "
                    + plugin.config().requestTimeoutSeconds() + "s");
            if (snapshot.hasAppliedConfig()) {
                lines.add("<gray>已应用配置：<white>#" + snapshot.appliedConfigId() + "</white> "
                        + orDash(snapshot.appliedConfigName()) + " <dark_gray>version "
                        + orDash(snapshot.appliedVersion()));
            } else {
                lines.add("<gray>已应用配置：<yellow>无</yellow>");
            }
            lines.add("<gray>上次应用时间：<white>" + orDash(snapshot.appliedAt()) + "</white>");
            lines.add("<gray>缓存文件：<white>"
                    + (snapshot.cacheFile() == null
                            ? "-"
                            : plugin.state().cacheFolder().resolve(snapshot.cacheFile()).toString())
                    + "</white>");
            lines.add("<gray>本地缓存清单：<white>" + describeCachedIds() + "</white>");
            lines.add("<gray>上次同步：<white>" + orDash(snapshot.lastSyncAt()) + "</white> "
                    + (snapshot.lastSyncOk() ? "<green>成功" : "<red>失败")
                    + " <dark_gray>" + orEmpty(snapshot.lastSyncMessage()));

            String objectiveName = plugin.state().appliedConfig()
                    .map(config -> config.scoreboardOrDisabled().objectiveOrDefault())
                    .orElseGet(() -> snapshot.objective() == null
                            ? ScoreboardSettings.DEFAULT_OBJECTIVE : snapshot.objective());
            lines.addAll(plugin.inspector().describe(plugin.inspector().capture(objectiveName)));
            lines.forEach(line -> plugin.sendRaw(sender, line));

            // 可达性探测放到网络线程
            plugin.async(() -> {
                boolean reachable;
                String detail = "";
                try {
                    plugin.api().listConfigs();
                    reachable = true;
                } catch (ApiException e) {
                    reachable = false;
                    detail = e.getMessage();
                }
                final boolean ok = reachable;
                final String message = detail;
                plugin.sync(() -> plugin.sendRaw(sender, "<gray>API 可达性："
                        + (ok ? "<green>正常</green>" : "<red>不可达</red>")
                        + (ok ? "" : " <gray>" + message)));
            });
        });
    }

    // ------------------------------------------------------------------
    // Tab 补全
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(visibleSubcommands(sender), args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "info", "apply" -> filter(configIds(sender), args[1]);
                // import 之后是自由文本名称，不补全
                default -> List.of();
            };
        }

        if ("apply".equals(sub)) {
            List<String> used = new ArrayList<>();
            for (int i = 2; i < args.length - 1; i++) {
                used.add(args[i].toLowerCase(Locale.ROOT));
            }
            List<String> remaining = new ArrayList<>();
            for (String flag : APPLY_FLAGS) {
                if (!used.contains(flag)) {
                    remaining.add(flag);
                }
            }
            return filter(remaining, args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> visibleSubcommands(CommandSender sender) {
        List<String> result = new ArrayList<>();
        for (String sub : SUBCOMMANDS) {
            boolean write = "apply".equals(sub) || "import".equals(sub) || "reload".equals(sub);
            if (write ? sender.hasPermission(PERM_ADMIN) : hasView(sender)) {
                result.add(sub);
            }
        }
        return result;
    }

    /** 配置 ID 补全来自异步预热的缓存，绝不在补全线程发网络请求。 */
    private List<String> configIds(CommandSender sender) {
        if (!hasView(sender)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (ConfigSummary summary : plugin.cachedSummaries()) {
            ids.add(String.valueOf(summary.idOrZero()));
        }
        return ids;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 权限与提示
    // ------------------------------------------------------------------

    private boolean hasView(CommandSender sender) {
        return sender.hasPermission(PERM_VIEW) || sender.hasPermission(PERM_ADMIN);
    }

    private boolean requireView(CommandSender sender) {
        if (hasView(sender)) {
            return true;
        }
        plugin.send(sender, "<red>你没有权限执行该命令（需要 " + PERM_VIEW + "）。");
        return false;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(PERM_ADMIN)) {
            return true;
        }
        plugin.send(sender, "<red>你没有权限执行该命令（需要 " + PERM_ADMIN + "）。");
        return false;
    }

    private void sendHelp(CommandSender sender, String label) {
        plugin.send(sender, "<gold>NorthTeam 命令（队伍信息采集公示系统）</gold>");
        plugin.sendRaw(sender, "<gray>/" + label + " list <dark_gray>—— 列出官网配置");
        plugin.sendRaw(sender, "<gray>/" + label + " info <id> <dark_gray>—— 配置详情与差异");
        plugin.sendRaw(sender, "<gray>/" + label + " apply <id> [--dry-run] [--force] <dark_gray>—— 全量重建队伍与侧边栏");
        plugin.sendRaw(sender, "<gray>/" + label + " import [名称] <dark_gray>—— 采集服务器队伍回传官网");
        plugin.sendRaw(sender, "<gray>/" + label + " reload <dark_gray>—— 重读 config.yml");
        plugin.sendRaw(sender, "<gray>/" + label + " status <dark_gray>—— 当前状态与 API 可达性");
    }

    private Integer parseId(CommandSender sender, String raw) {
        try {
            int id = Integer.parseInt(raw.trim());
            if (id <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return id;
        } catch (NumberFormatException e) {
            plugin.send(sender, "<red>配置 ID 必须是正整数，收到：<white>" + raw);
            return null;
        }
    }

    private void reportUnavailable(CommandSender sender, int id) {
        plugin.send(sender, "<red>无法获取配置 " + id + "：官网不可达且没有可用缓存。");
        plugin.send(sender, "<gray>本地缓存清单：" + describeCachedIds());
    }

    private void reportApiFailure(CommandSender sender, String action, ApiException e) {
        plugin.send(sender, "<red>" + action + "：" + e.getMessage());
        if (e.isUnreachable()) {
            plugin.send(sender, "<gray>官网当前不可达，可稍后重试；"
                    + "已应用过的配置仍可用 /nt apply <id> 从本地缓存重放。");
        }
    }

    private String describeCachedIds() {
        List<Integer> ids = plugin.state().cachedConfigIds();
        return ids.isEmpty() ? "（无）" : ids.toString();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String quote(String value) {
        return value == null || value.isEmpty() ? "(空)" : "\"" + value + "\"";
    }
}
