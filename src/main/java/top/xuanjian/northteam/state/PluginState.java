package top.xuanjian.northteam.state;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import top.xuanjian.northteam.model.StateSnapshot;
import top.xuanjian.northteam.model.TeamConfig;
import top.xuanjian.northteam.util.Json;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 插件运行状态与本地缓存（契约 4.4 / 4.5 的「离线可用」与「重启自愈」）。
 *
 * <p>落盘布局（与契约及任务约定一致）：
 * <pre>
 * plugins/NorthTeam/
 *   config.yml
 *   state.json              —— 当前已应用配置、上次同步结果、本插件创建过的队伍名
 *   cache/config-&lt;id&gt;.json  —— 每个配置 ID 最后一次成功拉取的完整配置原文
 * </pre>
 *
 * <p>本类不依赖 Bukkit，只做文件与内存状态管理，便于推理与复用。
 * 所有 IO 失败都被吞掉并转成回调日志，绝不影响主流程。
 */
public final class PluginState {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Gson gson = new GsonBuilder()
            // 与契约的 snake_case 对齐（StateSnapshot 上的 @SerializedName 是冗余保险）
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private final Path dataFolder;
    private final Path cacheFolder;
    private final Path stateFile;
    private final Consumer<String> warnLog;

    private StateSnapshot snapshot = StateSnapshot.empty();

    /** 当前内存中生效的配置（来源：本次 apply 的官网响应，或启动时读回缓存）。 */
    private volatile TeamConfig appliedConfig;
    /** 当前生效配置的来源描述，用于 /nt status 与日志（官网 / 本地缓存）。 */
    private volatile String appliedConfigSource = "未应用";

    public PluginState(Path dataFolder, Consumer<String> warnLog) {
        this.dataFolder = dataFolder;
        this.cacheFolder = dataFolder.resolve("cache");
        this.stateFile = dataFolder.resolve("state.json");
        this.warnLog = warnLog == null ? message -> { } : warnLog;
    }

    public Path dataFolder() {
        return dataFolder;
    }

    public Path cacheFolder() {
        return cacheFolder;
    }

    public Path stateFile() {
        return stateFile;
    }

    public StateSnapshot snapshot() {
        return snapshot;
    }

    public Optional<TeamConfig> appliedConfig() {
        return Optional.ofNullable(appliedConfig);
    }

    public void setAppliedConfig(TeamConfig config, String source) {
        this.appliedConfig = config;
        this.appliedConfigSource = source == null ? "未知来源" : source;
    }

    public String appliedConfigSource() {
        return appliedConfigSource;
    }

    /** 读取 state.json；不存在或损坏时退化为空状态。 */
    public void load() {
        try {
            ensureDirectories();
        } catch (IOException e) {
            warnLog.accept("创建插件数据目录失败：" + e.getMessage());
        }
        if (!Files.isRegularFile(stateFile)) {
            snapshot = StateSnapshot.empty();
            return;
        }
        try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            StateSnapshot loaded = gson.fromJson(reader, StateSnapshot.class);
            snapshot = loaded == null ? StateSnapshot.empty() : loaded;
            restoreAppliedConfigFromCache();
        } catch (IOException | JsonParseException e) {
            warnLog.accept("state.json 读取失败（将按空状态启动）：" + e.getMessage());
            snapshot = StateSnapshot.empty();
        }
    }

    /**
     * 启动时把 {@code state.json} 指向的缓存配置读回内存。
     *
     * <p>为什么需要：{@code appliedConfig} 是纯内存字段，重启后必然为空；而聊天栏前缀渲染
     * 需要它来反查「玩家属于哪个队伍」。开机自动重放（{@code auto_reapply_on_start}）会在启动
     * 3 秒后才写回它，中间这段窗口以及重放失败（官网不可达且无缓存）的情况下，就会退化成
     * 「所有人无队伍」。这里直接从本地缓存恢复，作为兜底。
     */
    private void restoreAppliedConfigFromCache() {
        Integer id = snapshot.appliedConfigId();
        if (id == null) {
            return;
        }
        try {
            Optional<String> cached = readCache(id);
            if (cached.isEmpty()) {
                return;
            }
            TeamConfig config = Json.parse(cached.get(), TeamConfig.class);
            if (config != null) {
                appliedConfig = config;
                if (appliedConfigSource == null || "未应用".equals(appliedConfigSource)) {
                    appliedConfigSource = "本地缓存（启动恢复）";
                }
            }
        } catch (JsonParseException e) {
            warnLog.accept("启动恢复已应用配置失败（聊天前缀等查询会退化）：" + e.getMessage());
        }
    }

    /** 覆盖式保存 state.json（先写临时文件再原子替换，避免中途崩溃损坏文件）。 */
    public void save() {
        try {
            ensureDirectories();
            Path temp = stateFile.resolveSibling("state.json.tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(snapshot, writer);
            }
            try {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            warnLog.accept("state.json 写入失败：" + e.getMessage());
        }
    }

    public void updateSnapshot(StateSnapshot next) {
        this.snapshot = next == null ? StateSnapshot.empty() : next;
    }

    /** 记录一次成功的应用结果。 */
    public void recordApplySuccess(TeamConfig config, String source, String objective,
                                   boolean scoreboardEnabled, List<String> formalTeams,
                                   List<String> sidebarTeams) {
        String now = LocalDateTime.now().format(TIMESTAMP);
        snapshot = new StateSnapshot(
                StateSnapshot.CURRENT_SCHEMA,
                config.id(),
                config.nameOrFallback(),
                config.versionOrEmpty(),
                now,
                config.id() == null ? null : cacheFileName(config.id()),
                objective,
                scoreboardEnabled,
                List.copyOf(formalTeams),
                List.copyOf(sidebarTeams),
                now,
                true,
                "应用成功（" + source + "）");
        appliedConfigSource = source;
        // 1.0.1 修：这里必须同时把配置本体留在内存里。
        // 运行期需要按玩家名反查队伍（聊天栏前缀渲染），而它读的是 appliedConfig；
        // 之前只更新 snapshot 与来源字符串，导致 appliedConfig 始终为空 —— 表现为
        // 「聊天渲染已启用但所有人都是无队伍」。
        appliedConfig = config;
        save();
    }

    /** 记录一次同步/请求结果（成功或失败），供 /nt status 展示。 */
    public void recordSyncResult(boolean ok, String message) {
        StateSnapshot current = snapshot;
        snapshot = new StateSnapshot(
                current.schema() == 0 ? StateSnapshot.CURRENT_SCHEMA : current.schema(),
                current.appliedConfigId(), current.appliedConfigName(), current.appliedVersion(),
                current.appliedAt(), current.cacheFile(), current.objective(),
                current.scoreboardEnabled(), current.formalTeamsOrEmpty(),
                current.sidebarTeamsOrEmpty(),
                LocalDateTime.now().format(TIMESTAMP), ok, message);
        save();
    }

    // ------------------------------------------------------------------
    // 配置缓存
    // ------------------------------------------------------------------

    public String cacheFileName(int configId) {
        return "config-" + configId + ".json";
    }

    public Path cacheFile(int configId) {
        return cacheFolder.resolve(cacheFileName(configId));
    }

    /** 写入缓存原文。失败只告警，不影响本次 apply。 */
    public void writeCache(int configId, String rawJson) {
        try {
            ensureDirectories();
            Files.writeString(cacheFile(configId), rawJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnLog.accept("写入缓存 config-" + configId + ".json 失败：" + e.getMessage());
        }
    }

    /** 读取缓存原文；不存在或读失败返回 empty。 */
    public Optional<String> readCache(int configId) {
        Path file = cacheFile(configId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            warnLog.accept("读取缓存 config-" + configId + ".json 失败：" + e.getMessage());
            return Optional.empty();
        }
    }

    /** 列出所有已缓存配置 ID（升序），用于启动自愈与排错提示。 */
    public List<Integer> cachedConfigIds() {
        if (!Files.isDirectory(cacheFolder)) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(cacheFolder)) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("config-") && name.endsWith(".json"))
                    .forEach(name -> {
                        String digits = name.substring("config-".length(), name.length() - ".json".length());
                        try {
                            ids.add(Integer.parseInt(digits));
                        } catch (NumberFormatException ignored) {
                            // 非本插件命名，忽略
                        }
                    });
        } catch (IOException e) {
            warnLog.accept("扫描缓存目录失败：" + e.getMessage());
            return List.of();
        }
        ids.sort(Integer::compareTo);
        return ids;
    }

    /** 上次成功缓存该配置的时间（文件修改时间，人类可读）；无缓存返回 empty。 */
    public Optional<String> cacheTimestamp(int configId) {
        Path file = cacheFile(configId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.getLastModifiedTime(file).toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime()
                    .format(TIMESTAMP));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void ensureDirectories() throws IOException {
        Files.createDirectories(dataFolder);
        Files.createDirectories(cacheFolder);
    }
}
