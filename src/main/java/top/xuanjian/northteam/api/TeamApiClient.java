package top.xuanjian.northteam.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import top.xuanjian.northteam.model.ApiErrorBody;
import top.xuanjian.northteam.model.ConfigListResponse;
import top.xuanjian.northteam.model.ImportResult;
import top.xuanjian.northteam.model.TeamConfig;
import top.xuanjian.northteam.model.TeamUnit;
import top.xuanjian.northteam.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 官网 API 客户端（契约 3.1 / 3.2 / 3.3）。
 *
 * <p>只使用 JDK 自带 {@link HttpClient} 与 Gson；所有失败都被翻译成
 * {@link ApiException}（含中文原因），调用方不会拿到原始异常。
 *
 * <p>认证：契约 1 规定插件读写接口统一使用请求头 {@code X-Server-Key}。
 */
public final class TeamApiClient {

    private static final String HEADER_SERVER_KEY = "X-Server-Key";
    private static final String LIST_PATH = "/api/team/list";
    private static final String EXPORT_PATH = "/api/team/export/";
    private static final String IMPORT_PATH = "/api/team/import";
    /** 未配置 server_key 时的公开读通道（契约 3.4/3.5，无认证，仅 is_public=1）。 */
    private static final String PUBLIC_LIST_PATH = "/api/team/public";
    private static final String PUBLIC_EXPORT_PATH = "/api/team/public/";

    /** 读接口用：字段名 → 下划线，与契约的 snake_case 对齐。 */
    private final Gson gson = Json.GSON;

    private final String apiBase;
    private final String serverKey;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final Consumer<String> debugLog;

    public TeamApiClient(String apiBase, String serverKey, int connectTimeoutSeconds,
                         int requestTimeoutSeconds, Consumer<String> debugLog) {
        this.apiBase = normalizeBase(apiBase);
        this.serverKey = serverKey == null ? "" : serverKey.trim();
        this.requestTimeout = Duration.ofSeconds(Math.max(1, requestTimeoutSeconds));
        this.debugLog = debugLog == null ? message -> { } : debugLog;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 供 {@code /nt reload} 之后重建客户端时复用配置。 */
    public String apiBase() {
        return apiBase;
    }

    /**
     * 是否已经配置了密钥。未配置时读接口自动退化到**公开读通道**（契约 3.4/3.5，
     * 无认证、只返回 {@code is_public=1} 的配置）：公示数据本来就是公开的，
     * 这样插件在只跑公示活动时无需任何密钥；写接口（import）仍然必须有密钥。
     */
    public boolean hasServerKey() {
        return !serverKey.isEmpty();
    }

    /** 当前读接口走的是哪条通道（仅用于日志/`/nt status` 展示）。 */
    public String readChannel() {
        return hasServerKey() ? "插件通道（X-Server-Key）" : "公开读通道（无认证，仅已公示）";
    }

    /**
     * 契约 3.1 {@code GET /api/team/list}；未配置密钥时改用 3.4 {@code GET /api/team/public}。
     */
    public ConfigListResponse listConfigs() throws ApiException {
        String body = exchange(hasServerKey() ? LIST_PATH : PUBLIC_LIST_PATH, false, null);
        return parse(body, ConfigListResponse.class, "配置列表");
    }

    /**
     * 契约 3.2 {@code GET /api/team/export/:id} 的原始响应 + 解析结果。
     *
     * <p>保留原文是为了落盘到 {@code plugins/NorthTeam/cache/config-&lt;id&gt;.json}：
     * 官网不可达时可以直接用这份原文重放（契约 4.4）。
     */
    public record RawConfig(String rawJson, TeamConfig config) {
    }

    /**
     * 契约 3.2 {@code GET /api/team/export/:id}：取完整配置（含原文）。
     * 有密钥时不受 {@code is_public} 限制；无密钥时退回 3.5 {@code GET /api/team/public/:id}
     * （未公示配置会 404）。两种通道返回的 JSON 形状一致，解析模型通用。
     */
    public RawConfig exportConfigRaw(int id) throws ApiException {
        if (id <= 0) {
            throw new ApiException(ApiException.Kind.CONFIG, "配置 ID 必须是正整数，收到：" + id);
        }
        String body = exchange((hasServerKey() ? EXPORT_PATH : PUBLIC_EXPORT_PATH) + id, false, null);
        TeamConfig config = parse(body, TeamConfig.class, "配置详情");
        if (config == null) {
            throw new ApiException(ApiException.Kind.MALFORMED, "配置详情为空（契约 3.2 应返回完整配置）。");
        }
        return new RawConfig(body, config);
    }

    /**
     * 同上，只要解析结果。
     */
    public TeamConfig exportConfig(int id) throws ApiException {
        return exportConfigRaw(id).config();
    }

    /**
     * 契约 3.3 {@code POST /api/team/import}：把服务器当前队伍采集回官网，新建一条配置。
     *
     * <p>请求体严格按契约构造：2.1 去掉 {@code id}/{@code version}，
     * 且不携带 {@code is_public}（由官网默认置 0）。这里手工拼 JSON 而不是直接序列化
     * record，是为了让字段集合与契约示例逐字一致，避免多余字段影响官网校验。
     */
    public ImportResult importConfig(String name, String description, String eventDate,
                                     TeamConfig scoreboardSource, java.util.List<TeamUnit> units)
            throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("schema", 1);
        body.addProperty("name", name);
        body.addProperty("description", description == null ? "" : description);
        if (eventDate == null || eventDate.isBlank()) {
            body.add("event_date", JsonNull.INSTANCE);
        } else {
            body.addProperty("event_date", eventDate);
        }

        JsonObject scoreboard = new JsonObject();
        if (scoreboardSource != null && scoreboardSource.scoreboard() != null) {
            var settings = scoreboardSource.scoreboard();
            scoreboard.addProperty("enabled", settings.enabled());
            scoreboard.addProperty("objective", settings.objectiveOrDefault());
            scoreboard.addProperty("display_name", settings.displayNameOrDefault());
            scoreboard.addProperty("position", settings.positionOrDefault());
            scoreboard.addProperty("score_mode", settings.scoreModeOrDefault());
        } else {
            scoreboard.addProperty("enabled", false);
            scoreboard.addProperty("objective", "nt_teams");
            scoreboard.addProperty("display_name", "<gold>队伍</gold>");
            scoreboard.addProperty("position", "sidebar");
            scoreboard.addProperty("score_mode", "member_count");
        }
        body.add("scoreboard", scoreboard);

        JsonArray unitArray = new JsonArray();
        for (TeamUnit unit : units) {
            JsonObject json = new JsonObject();
            json.addProperty("key", unit.key());
            json.addProperty("display_name", unit.displayName());
            json.addProperty("color", unit.color());
            json.addProperty("prefix", unit.prefix() == null ? "" : unit.prefix());
            json.addProperty("suffix", unit.suffix() == null ? "" : unit.suffix());
            json.addProperty("friendly_fire", unit.friendlyFire());
            json.addProperty("see_friendly_invisibles", unit.seeFriendlyInvisibles());
            json.addProperty("nametag_visibility", unit.nametagVisibility());
            json.addProperty("death_message_visibility", unit.deathMessageVisibility());
            json.addProperty("collision_rule", unit.collisionRule());
            json.addProperty("sort_order", unit.sortOrder());
            JsonArray members = new JsonArray();
            unit.normalizedMembers().forEach(members::add);
            json.add("members", members);
            unitArray.add(json);
        }
        body.add("units", unitArray);

        String response = exchange(IMPORT_PATH, true, gson.toJson(body));
        return parse(response, ImportResult.class, "回传结果");
    }

    /**
     * {@code /nt status} 用的轻量可达性探测：直接打一次 3.1。
     */
    public boolean isReachable() {
        try {
            listConfigs();
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private String exchange(String path, boolean post, String jsonBody) throws ApiException {
        if (apiBase.isEmpty()) {
            throw new ApiException(ApiException.Kind.CONFIG,
                    "尚未配置官网地址（config.yml 的 api_base 为空）。");
        }
        // 读接口：无密钥时走公开读通道（契约 3.4/3.5），因此这里不再拦截。
        // 写接口（import 回传）必须要有密钥。
        if (post && !hasServerKey()) {
            throw new ApiException(ApiException.Kind.CONFIG,
                    "import 需要 server_key（config.yml 的 server_key，或环境变量 NORTHTEAM_SERVER_KEY）。"
                            + "密钥由官网 mod_servers.server_key 提供。");
        }

        URI uri;
        try {
            uri = new URI(apiBase + path);
        } catch (URISyntaxException e) {
            throw new ApiException(ApiException.Kind.CONFIG,
                    "官网地址不合法：" + apiBase + "（" + e.getReason() + "）");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "NorthTeam-Plugin");
        if (hasServerKey()) {
            builder.header(HEADER_SERVER_KEY, serverKey);
        }
        if (post) {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        debugLog.accept("请求 " + (post ? "POST " : "GET ") + uri);

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(ApiException.Kind.UNREACHABLE,
                    "API 不可达：" + apiBase + "（" + describe(e) + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ApiException.Kind.UNREACHABLE,
                    "API 请求被中断：" + apiBase, e);
        }

        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status >= 400) {
            throw httpError(status, body);
        }
        return body;
    }

    private ApiException httpError(int status, String body) {
        String reason = extractError(body);
        String hint = switch (status) {
            case 400 -> "官网认为请求数据不合法";
            case 401 -> "server_key 无效或缺失，请核对 config.yml 的 server_key 是否与官网 mod_servers.server_key 一致";
            case 403 -> "该密钥没有访问权限（需要管理员/插件通道权限）";
            case 404 -> "配置不存在，或该配置未公示（插件通道不受 is_public 限制，请核对 ID）";
            case 409 -> "官网存在冲突（例如重复导入）";
            case 500 -> "官网服务端内部错误，请稍后重试或联系官网维护者";
            default -> "官网返回异常状态码";
        };
        String message = "官网接口返回 HTTP " + status + "（" + hint + "）";
        if (!reason.isEmpty()) {
            message = message + "：" + reason;
        }
        return new ApiException(ApiException.Kind.HTTP_ERROR, message, status, null);
    }

    private String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            ApiErrorBody parsed = gson.fromJson(body, ApiErrorBody.class);
            return parsed == null ? "" : parsed.errorOrFallback();
        } catch (JsonParseException e) {
            // 网关返回 HTML 等非契约错误体时，截断展示，避免刷屏
            String trimmed = body.strip();
            return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
        }
    }

    private <T> T parse(String body, Class<T> type, String what) throws ApiException {
        if (body == null || body.isBlank()) {
            throw new ApiException(ApiException.Kind.MALFORMED, "官网返回的" + what + "为空。");
        }
        try {
            T parsed = gson.fromJson(body, type);
            if (parsed == null) {
                throw new ApiException(ApiException.Kind.MALFORMED, "官网返回的" + what + "为 null。");
            }
            return parsed;
        } catch (JsonParseException e) {
            String trimmed = body.strip();
            String preview = trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
            throw new ApiException(ApiException.Kind.MALFORMED,
                    "官网返回的" + what + "不是合法 JSON（" + e.getMessage() + "），原文开头：" + preview, e);
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        if (e instanceof java.net.ConnectException) {
            return "连接被拒绝";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "域名解析失败";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "请求超时";
        }
        return message;
    }

    /**
     * 规范化 api_base。
     *
     * <p>契约里的路径是 {@code /api/team/...}，因此 {@code api_base} 只应到站点根
     * （例如 {@code https://xuanjian.top}）。为避免管理员误填成
     * {@code https://xuanjian.top/api/team} 导致路径重复，这里主动剥掉多余的
     * {@code /api/team} 或 {@code /api} 后缀。
     */
    private static String normalizeBase(String base) {
        if (base == null) {
            return "";
        }
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/api/team")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/api/team".length());
        } else if (trimmed.endsWith("/api")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/api".length());
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
