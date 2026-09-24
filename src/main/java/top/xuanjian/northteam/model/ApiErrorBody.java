package top.xuanjian.northteam.model;

import com.google.gson.annotations.SerializedName;

/**
 * 契约 1：错误统一为 {@code { "error": "人类可读的原因" }}。
 */
public record ApiErrorBody(
        @SerializedName("error") String error
) {

    public String errorOrFallback() {
        return error == null || error.isBlank() ? "服务端未提供错误原因" : error;
    }
}
