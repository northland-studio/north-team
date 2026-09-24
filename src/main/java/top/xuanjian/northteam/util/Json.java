package top.xuanjian.northteam.util;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * 全插件共用的 JSON 配置。
 *
 * <p>使用 {@link FieldNamingPolicy#LOWER_CASE_WITH_UNDERSCORES}，与契约 docs/API.md 的
 * snake_case 字段名对齐（{@code display_name} / {@code is_public} / {@code unit_count} …），
 * 模型上的 {@code @SerializedName} 作为冗余保险，两者同时生效。
 */
public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .disableHtmlEscaping()
            .create();

    private Json() {
    }

    /**
     * 解析 JSON。失败时抛出 {@link JsonParseException}，由调用方翻译成中文提示。
     */
    public static <T> T parse(String raw, Class<T> type) {
        return GSON.fromJson(raw, type);
    }
}
