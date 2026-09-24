package top.xuanjian.northteam.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文本解析工具：以 MiniMessage 为主，同时兼容 {@code &} 传统颜色码。
 *
 * <p>契约 2.1 明确：{@code prefix}/{@code suffix} 是 MiniMessage 字符串，
 * 「允许 {@code &} 传统颜色码，插件侧统一转换」。官网管理页的手工输入很可能混用两种写法，
 * 因此这里统一把传统码翻译成 MiniMessage 标签后再解析。
 *
 * <p>设计要点：
 * <ul>
 *   <li>传统码是「作用到下一个重置/颜色码为止」，MiniMessage 标签是「显式闭合」。
 *       转换时用栈记录本次打开的传统标签，遇到新颜色码或 {@code &r} 时逐层闭合，
 *       字符串结束时再兜底闭合，语义上贴近原版 {@code /team} 的观感。</li>
 *   <li>解析绝不抛异常：任何失败都逐级降级（MiniMessage → 传统码解析 → 纯文本），
 *       保证「字段非法也要给出可读结果，绝不把栈丢给玩家」。</li>
 * </ul>
 */
public final class MiniMessages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** 16 种原版颜色对应的传统码字符。 */
    private static final Map<Character, String> COLOR_CODES = new LinkedHashMap<>();
    /** 传统格式码字符（&k &l &m &n &o）。 */
    private static final Map<Character, String> FORMAT_CODES = new LinkedHashMap<>();

    static {
        COLOR_CODES.put('0', "black");
        COLOR_CODES.put('1', "dark_blue");
        COLOR_CODES.put('2', "dark_green");
        COLOR_CODES.put('3', "dark_aqua");
        COLOR_CODES.put('4', "dark_red");
        COLOR_CODES.put('5', "dark_purple");
        COLOR_CODES.put('6', "gold");
        COLOR_CODES.put('7', "gray");
        COLOR_CODES.put('8', "dark_gray");
        COLOR_CODES.put('9', "blue");
        COLOR_CODES.put('a', "green");
        COLOR_CODES.put('b', "aqua");
        COLOR_CODES.put('c', "red");
        COLOR_CODES.put('d', "light_purple");
        COLOR_CODES.put('e', "yellow");
        COLOR_CODES.put('f', "white");

        FORMAT_CODES.put('k', "obfuscated");
        FORMAT_CODES.put('l', "bold");
        FORMAT_CODES.put('m', "strikethrough");
        FORMAT_CODES.put('n', "underlined");
        FORMAT_CODES.put('o', "italic");
    }

    private MiniMessages() {
    }

    /**
     * 把「MiniMessage + 传统颜色码」混用的字符串解析为 {@link Component}。
     * 永不返回 null，永不抛异常。
     */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI.deserialize(toMiniMessage(raw));
        } catch (Exception miniFailure) {
            // 降级 1：按传统 & 码解析（输入本来就只有 & 码时通常走不到这里）
            try {
                return LEGACY_AMP.deserialize(raw);
            } catch (Exception legacyFailure) {
                // 降级 2：纯文本，保证一定有可展示内容
                return Component.text(raw);
            }
        }
    }

    /**
     * 解析失败时返回 null（供需要区分「输入非法」的调用方使用，例如校验器）。
     */
    public static Component parseOrNull(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI.deserialize(toMiniMessage(raw));
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 {@link Component} 序列化回 MiniMessage（{@code /nt import} 回传官网时使用）。 */
    public static String serialize(Component component) {
        if (component == null) {
            return "";
        }
        try {
            return MINI.serialize(component);
        } catch (Exception e) {
            return toPlainText(component);
        }
    }

    /** 提取组件纯文本（{@code /nt import} 的 display_name 需要 1~32 字符的纯展示名）。 */
    public static String toPlainText(Component component) {
        if (component == null) {
            return "";
        }
        try {
            return PLAIN.serialize(component);
        } catch (Exception e) {
            return "";
        }
    }

    /** 去掉 MiniMessage/传统码标签后的纯文本，便于长度校验与日志输出。 */
    public static String stripTags(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        try {
            return toPlainText(MINI.deserialize(toMiniMessage(raw)));
        } catch (Exception e) {
            return raw.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        }
    }

    /**
     * 把传统 {@code &} 颜色码转换为 MiniMessage 标签。
     *
     * <p>已是 MiniMessage 标签的部分原样保留（不做二次转义），
     * 只有 {@code &} 序列会被改写；{@code &&} 视为转义，输出一个字面 {@code &}。
     */
    public static String toMiniMessage(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.indexOf('&') < 0) {
            return raw;
        }

        StringBuilder out = new StringBuilder(raw.length() + 16);
        // 栈中记录本次由传统码打开、尚未闭合的 MiniMessage 标签名
        Deque<String> openTags = new ArrayDeque<>();

        int i = 0;
        int length = raw.length();
        while (i < length) {
            char c = raw.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }

            // "&&" → 字面量 &
            if (i + 1 < length && raw.charAt(i + 1) == '&') {
                out.append('&');
                i += 2;
                continue;
            }

            // "&#RRGGBB" → <#RRGGBB>
            if (i + 1 < length && raw.charAt(i + 1) == '#') {
                String hex = raw.length() >= i + 8 ? raw.substring(i + 2, i + 8) : "";
                if (hex.length() == 6 && isHex(hex)) {
                    closeAll(out, openTags);
                    out.append("<#").append(hex).append('>');
                    openTags.push("#" + hex);
                    i += 8;
                    continue;
                }
            }

            if (i + 1 < length) {
                char code = Character.toLowerCase(raw.charAt(i + 1));
                String colorTag = COLOR_CODES.get(code);
                if (colorTag != null) {
                    // 颜色码会重置已有格式（与原版一致）：先闭合全部已开标签
                    closeAll(out, openTags);
                    out.append('<').append(colorTag).append('>');
                    openTags.push(colorTag);
                    i += 2;
                    continue;
                }
                String formatTag = FORMAT_CODES.get(code);
                if (formatTag != null) {
                    out.append('<').append(formatTag).append('>');
                    openTags.push(formatTag);
                    i += 2;
                    continue;
                }
                if (code == 'r') {
                    closeAll(out, openTags);
                    i += 2;
                    continue;
                }
            }

            // 不是已知的传统码，原样输出 & 继续
            out.append(c);
            i++;
        }

        closeAll(out, openTags);
        return out.toString();
    }

    private static void closeAll(StringBuilder out, Deque<String> openTags) {
        while (!openTags.isEmpty()) {
            String tag = openTags.pop();
            out.append("</").append(tag).append('>');
        }
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
