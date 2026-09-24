package top.xuanjian.northteam;

import com.google.gson.JsonParseException;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import top.xuanjian.northteam.api.ApiException;
import top.xuanjian.northteam.api.TeamApiClient;
import top.xuanjian.northteam.apply.ScoreboardInspector;
import top.xuanjian.northteam.apply.ServerCollector;
import top.xuanjian.northteam.apply.TeamApplier;
import top.xuanjian.northteam.command.NorthTeamCommand;
import top.xuanjian.northteam.config.PluginConfig;
import top.xuanjian.northteam.listener.PlayerJoinListener;
import top.xuanjian.northteam.model.ConfigSummary;
import top.xuanjian.northteam.model.TeamConfig;
import top.xuanjian.northteam.state.PluginState;
import top.xuanjian.northteam.util.Json;
import top.xuanjian.northteam.util.MiniMessages;

import java.util.List;
import java.util.Optional;

/**
 * NorthTeam —— 队伍信息采集公示系统的游戏侧插件。
 *
 * <p>职责：
 * <ul>
 *   <li>按契约 docs/API.md 与官网对接（读写都用 {@code X-Server-Key}）；</li>
 *   <li>{@code /nt apply <id>} 全量重建服务端队伍与侧边栏（契约 4）；</li>
 *   <li>离线可用：API 不可达时回退到 {@code plugins/NorthTeam/cache/config-<id>.json}；</li>
 *   <li>重启自愈：{@code auto_reapply_on_start} 时用 state.json 记录的配置重放
 *       （scoreboard team 不持久化，必须重建）。</li>
 * </ul>
 */
public final class NorthTeamPlugin extends JavaPlugin {

    /**
     * 插件版本，用于启动日志与 CI 断言。
     * <b>必须与 build.gradle 的 {@code version} 保持一致</b>（plugin.yml 由 Gradle 展开注入）。
     */
    public static final String PLUGIN_VERSION = "1.0.0";

    private static final String PREFIX = "<gray>[<gold>NorthTeam</gold>]</gray> ";

    private volatile PluginConfig pluginConfig;
    private PluginState state;
    private volatile TeamApiClient apiClient;
    private TeamApplier applier;
    private ScoreboardInspector inspector;
    private ServerCollector collector;

    /** 供 Tab 补全使用的配置 ID/摘要缓存（异步刷新，避免在补全线程做网络请求）。 */
    private volatile List<ConfigSummary> cachedSummaries = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pluginConfig = PluginConfig.from(getConfig(), getLogger());

        state = new PluginState(getDataFolder().toPath(), message -> getLogger().warning(plain(message)));
        state.load();

        rebuildApiClient();

        applier = new TeamApplier(state, this::config, this::info, this::warn);
        inspector = new ScoreboardInspector();
        collector = new ServerCollector();

        registerCommand();

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(applier), this);

        warnIfUnconfigured();

        // 异步预热配置列表，供 /nt list 与 Tab 补全使用
        refreshSummariesAsync();

        if (pluginConfig.autoReapplyOnStart()) {
            scheduleAutoReapply();
        } else {
            info("<gray>auto_reapply_on_start=false，跳过启动自动重放。");
        }

        getLogger().info("NorthTeam v" + PLUGIN_VERSION + " 已启用（API: " + pluginConfig.apiBase()
                + "，server_key: " + (pluginConfig.hasServerKey() ? "已配置" : "未配置") + "）");
    }

    @Override
    public void onDisable() {
        info("<gray>NorthTeam 正在卸载，已保存 state.json。");
        if (state != null) {
            state.save();
        }
    }

    private void registerCommand() {
        PluginCommand command = getCommand("nt");
        if (command == null) {
            getLogger().severe("plugin.yml 中未声明 nt 命令，/nt 将不可用！");
            return;
        }
        NorthTeamCommand executor = new NorthTeamCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void warnIfUnconfigured() {
        if (!pluginConfig.hasServerKey()) {
            warn("<yellow>未配置 server_key：读接口将走公开读通道（无认证，只能读取已公示的配置），"
                    + "/nt import 不可用。");
            warn("<gray>如需读取未公示配置或回传采集，请在 config.yml 填写 api.server_key"
                    + "（取自官网 mod_servers.server_key），或设置环境变量 NORTHTEAM_SERVER_KEY。");
        }
        if (pluginConfig.apiBase().isBlank()) {
            warn("<yellow>api.base 为空，所有官网接口不可用。");
        }
    }

    // ------------------------------------------------------------------
    // 启动自愈（契约 4.5）
    // ------------------------------------------------------------------

    private void scheduleAutoReapply() {
        var snapshot = state.snapshot();
        if (!snapshot.hasAppliedConfig()) {
            info("<gray>auto_reapply_on_start：state.json 中没有历史配置，跳过自动重放。");
            return;
        }
        int configId = snapshot.appliedConfigId();
        info("<gray>auto_reapply_on_start：将在启动后自动重放配置 " + configId + "。");

        // 延迟 3 秒，避开启动高峰；网络请求在异步线程，apply 本身回主线程执行
        getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
            LoadedConfig loaded = loadConfig(configId, true);
            getServer().getScheduler().runTask(this, () -> {
                if (loaded == null) {
                    warn("启动自动重放失败：无法获取配置 " + configId + "（官网不可达且无本地缓存）。");
                    return;
                }
                // 重启后记分板是空的，必须无条件重放，因此 force=true（不受 version 去重影响）
                var outcome = applier.apply(loaded.config(), loaded.source(), false, true);
                outcome.messages().forEach(this::info);
                if (!outcome.success()) {
                    warn("启动自动重放未成功，请手动执行 /nt apply " + configId);
                }
            });
        }, 60L);
    }

    // ------------------------------------------------------------------
    // 配置加载（含缓存回退，契约 4.4）
    // ------------------------------------------------------------------

    /**
     * 加载配置的来源描述。
     *
     * @param config  解析后的配置
     * @param source  中文来源描述（官网 / 本地缓存（版本时间 X，缓存时间 Y））
     * @param fromApi 是否来自官网
     */
    public record LoadedConfig(TeamConfig config, String source, boolean fromApi) {
    }

    /**
     * 拉取配置；官网不可达时回退到本地缓存。
     *
     * @param cacheFallbackEnabled 是否允许回退到缓存（{@code /nt apply} 的 --force 场景仍允许）
     * @return null 表示既拿不到官网数据也没有缓存
     */
    public LoadedConfig loadConfig(int configId, boolean cacheFallbackEnabled) {
        TeamApiClient client = apiClient;
        try {
            TeamApiClient.RawConfig raw = client.exportConfigRaw(configId);
            state.writeCache(configId, raw.rawJson());
            state.recordSyncResult(true, "配置 " + configId + " 拉取成功（官网）");
            return new LoadedConfig(raw.config(), "官网", true);
        } catch (ApiException apiFailure) {
            state.recordSyncResult(false, apiFailure.getMessage());
            if (!cacheFallbackEnabled) {
                warn("拉取配置 " + configId + " 失败：" + apiFailure.getMessage());
                return null;
            }
            Optional<String> cached = state.readCache(configId);
            if (cached.isEmpty()) {
                warn("拉取配置 " + configId + " 失败且无本地缓存：" + apiFailure.getMessage());
                return null;
            }
            try {
                TeamConfig config = Json.parse(cached.get(), TeamConfig.class);
                if (config == null) {
                    warn("本地缓存 config-" + configId + ".json 解析结果为 null。");
                    return null;
                }
                String cacheTime = state.cacheTimestamp(configId).orElse("未知");
                String source = "本地缓存（版本时间 " + config.versionOrEmpty() + "，缓存时间 " + cacheTime + "）";
                info("<yellow>官网不可达（" + apiFailure.getMessage() + "），已改用 " + source + "。");
                return new LoadedConfig(config, source, false);
            } catch (JsonParseException parseFailure) {
                warn("本地缓存 config-" + configId + ".json 不是合法 JSON：" + parseFailure.getMessage());
                return null;
            }
        }
    }

    /** 异步预热配置摘要缓存（供 Tab 补全）。 */
    public void refreshSummariesAsync() {
        async(() -> {
            try {
                var response = apiClient.listConfigs();
                cachedSummaries = List.copyOf(response.configsOrEmpty());
            } catch (ApiException e) {
                debug("预热配置列表失败：" + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // 访问器与工具
    // ------------------------------------------------------------------

    public PluginConfig config() {
        return pluginConfig;
    }

    public PluginState state() {
        return state;
    }

    public TeamApiClient api() {
        return apiClient;
    }

    public TeamApplier applier() {
        return applier;
    }

    public ScoreboardInspector inspector() {
        return inspector;
    }

    public ServerCollector collector() {
        return collector;
    }

    public List<ConfigSummary> cachedSummaries() {
        return cachedSummaries;
    }

    /** {@code /nt reload}：重读 config.yml 并重建 HTTP 客户端。 */
    public void reloadPluginConfig() {
        reloadConfig();
        pluginConfig = PluginConfig.from(getConfig(), getLogger());
        rebuildApiClient();
        refreshSummariesAsync();
    }

    private void rebuildApiClient() {
        PluginConfig current = pluginConfig;
        apiClient = new TeamApiClient(
                current.apiBase(),
                current.serverKey(),
                current.connectTimeoutSeconds(),
                current.requestTimeoutSeconds(),
                this::debug);
    }

    /** 在主线程执行（记分板操作必须回到主线程）。 */
    public void sync(Runnable task) {
        if (getServer().isPrimaryThread()) {
            task.run();
        } else {
            getServer().getScheduler().runTask(this, task);
        }
    }

    /** 在异步线程执行（网络请求）。 */
    public void async(Runnable task) {
        getServer().getScheduler().runTaskAsynchronously(this, task);
    }

    /** 给命令发送者发一条中文提示（MiniMessage + 传统颜色码混用均可）。 */
    /**
     * 给命令发送者发一条带前缀的消息，**同时写入服务端日志**。
     *
     * <p>为什么要写日志：{@code /nt list}、{@code /nt info}、{@code /nt apply} 的结果是
     * 异步（HTTP）返回的，而 RCON / 面板这类一次性连接在首帧响应后就关闭了 —— 只发给
     * sender 的话结果会被静默丢弃（控制台与游戏内正常，面板里只能看到「正在拉取…」）。
     * 落一份日志既能保证面板/RCON 场景可追溯，也让 CI 断言有稳定的证据来源。
     */
    public void send(CommandSender sender, String text) {
        getLogger().info(plain(text));
        sender.sendMessage(MiniMessages.parse(PREFIX + text));
    }

    /** 给命令发送者发一条无前缀的续行（同样写日志）。 */
    public void sendRaw(CommandSender sender, String text) {
        getLogger().info(plain(text));
        sender.sendMessage(MiniMessages.parse(text));
    }

    public void info(String text) {
        getLogger().info(plain(text));
    }

    public void warn(String text) {
        getLogger().warning(plain(text));
    }

    public void debug(String text) {
        if (pluginConfig != null && pluginConfig.debug()) {
            getLogger().info("[debug] " + plain(text));
        }
    }

    /** 去掉颜色标签，避免日志里出现 MiniMessage 标签。 */
    private static String plain(String text) {
        return text == null ? "" : MiniMessages.stripTags(text);
    }
}
