package top.xuanjian.northteam.api;

/**
 * 官网 API 调用失败。
 *
 * <p>该异常只承载「已经翻译成中文、可以直接发给玩家」的提示文本，
 * 调用方捕获后直接展示即可，绝不把栈信息抛给玩家。
 */
public class ApiException extends Exception {

    private static final long serialVersionUID = 1L;

    /** 失败分类，便于命令层决定提示措辞与是否需要回退到缓存。 */
    public enum Kind {
        /** 网络层不可达（连接失败、超时、DNS 失败等）。 */
        UNREACHABLE,
        /** 服务端返回了 4xx/5xx。 */
        HTTP_ERROR,
        /** 响应不是合法 JSON，或结构与契约不符。 */
        MALFORMED,
        /** 本地配置问题（例如未填写 server_key）。 */
        CONFIG
    }

    private final Kind kind;
    private final int statusCode;

    public ApiException(Kind kind, String message) {
        this(kind, message, -1, null);
    }

    public ApiException(Kind kind, String message, Throwable cause) {
        this(kind, message, -1, cause);
    }

    public ApiException(Kind kind, String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP 状态码，非 HTTP 错误时为 -1。 */
    public int statusCode() {
        return statusCode;
    }

    public boolean isUnreachable() {
        return kind == Kind.UNREACHABLE;
    }
}
