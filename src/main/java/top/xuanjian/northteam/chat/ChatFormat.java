package top.xuanjian.northteam.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.TextComponent;
import top.xuanjian.northteam.model.TeamUnit;
import top.xuanjian.northteam.util.MiniMessages;
import top.xuanjian.northteam.util.TeamProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 聊天行模板渲染（插件 1.0.1 新增）。
 *
 * <p>为什么需要它：Bukkit/Paper 的默认聊天渲染用的是 {@code player.getName()}，
 * 而不是带记分板队伍装饰的 displayName，因此**记分板队伍前缀不会出现在聊天栏**
 * （老问题 SPIGOT-564：{@code Scoreboard team prefixes are not applied to the default
 * server chat}）。进退服消息走的是另一条路径（用 displayName），所以那边能看到前缀，
 * 聊天栏看不到 —— 这个不一致就是本类要抹平的。
 *
 * <p>渲染方式：把 {@code chat.format} 模板按占位符切分，字面量片段用
 * {@link MiniMessages#parse(String)} 解析（因此模板里可以直接写 MiniMessage 或 {@code &}
 * 颜色码），占位符替换成对应的 {@link Component}。**未知占位符按字面量保留**，不做报错，
 * 避免管理员写错一个花括号就整条聊天消失。
 *
 * <p>本类只依赖 Adventure，不触碰 Bukkit API，因此可以安全地在
 * {@link io.papermc.paper.event.player.AsyncChatEvent} 的异步线程里调用。
 */
public final class ChatFormat {

    public static final String PREFIX = "{prefix}";
    public static final String SUFFIX = "{suffix}";
    public static final String PLAYER = "{player}";
    public static final String DISPLAY_NAME = "{displayname}";
    public static final String TEAM = "{team}";
    public static final String TEAM_KEY = "{team_key}";
    public static final String MESSAGE = "{message}";

    /** 全部受支持的占位符（用于配置自检与文档）。 */
    public static final List<String> KNOWN_PLACEHOLDERS = List.of(
            PREFIX, SUFFIX, PLAYER, DISPLAY_NAME, TEAM, TEAM_KEY, MESSAGE);

    private ChatFormat() {
    }

    /**
     * 找出模板里出现但不被支持的 {@code {...}} 占位符。
     *
     * <p>只用于 {@code /nt reload} 时的告警提示，不影响渲染（未知占位符会原样显示）。
     */
    public static List<String> unknownPlaceholders(String format) {
        List<String> unknown = new ArrayList<>();
        if (format == null) {
            return unknown;
        }
        int i = 0;
        while (i < format.length()) {
            int open = format.indexOf('{', i);
            if (open < 0) {
                break;
            }
            int close = format.indexOf('}', open + 1);
            if (close < 0) {
                break;
            }
            String token = format.substring(open, close + 1);
            if (!KNOWN_PLACEHOLDERS.contains(token) && !unknown.contains(token)) {
                unknown.add(token);
            }
            i = close + 1;
        }
        return unknown;
    }

    /**
     * 渲染一行聊天。
     *
     * @param format        模板（来自 {@code chat.format} / {@code chat.format_no_team}）
     * @param playerName    玩家名（{@code {player}}，会用所属队伍 color 上色）
     * @param unit          玩家所属队伍；不在任何队伍时为 {@code null}
     * @param displayName   玩家显示名（{@code {displayname}}，由 Paper 解析，可能已含原版装饰）
     * @param message       玩家消息原文（{@code {message}}，保留事件/点击等原样属性）
     */
    public static Component render(String format, String playerName, TeamUnit unit,
                                   Component displayName, Component message) {
        TextComponent.Builder out = Component.text();
        if (format == null || format.isEmpty()) {
            return out.build();
        }

        NamedTextColor nameColor = unit == null
                ? NamedTextColor.WHITE
                : TeamProperties.color(unit.color());
        Component playerComponent = Component.text(playerName == null ? "" : playerName)
                .colorIfAbsent(nameColor);

        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            int open = format.indexOf('{', i);
            if (open < 0) {
                literal.append(format, i, format.length());
                break;
            }
            int close = format.indexOf('}', open + 1);
            if (close < 0) {
                literal.append(format, i, format.length());
                break;
            }
            String token = format.substring(open, close + 1);
            Component replacement = resolve(token, unit, playerComponent, displayName, message);
            if (replacement == null) {
                // 未知占位符：当字面量，连同前面未输出的片段一起留到下一轮
                literal.append(format, i, close + 1);
            } else {
                literal.append(format, i, open);
                flush(out, literal);
                out.append(replacement);
            }
            i = close + 1;
        }
        flush(out, literal);
        return out.build();
    }

    private static Component resolve(String token, TeamUnit unit, Component playerComponent,
                                     Component displayName, Component message) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case PREFIX -> parseOrEmpty(unit == null ? null : unit.prefix());
            case SUFFIX -> parseOrEmpty(unit == null ? null : unit.suffix());
            case TEAM -> parseOrEmpty(unit == null ? null : unit.displayName());
            case TEAM_KEY -> unit == null ? Component.empty() : Component.text(unit.safeKey());
            case PLAYER -> playerComponent;
            case DISPLAY_NAME -> displayName == null ? Component.empty() : displayName;
            case MESSAGE -> message == null ? Component.empty() : message;
            default -> null;
        };
    }

    private static Component parseOrEmpty(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return MiniMessages.parse(raw);
    }

    /** 把累积的字面量片段按 MiniMessage 解析后追加，并清空缓冲。 */
    private static void flush(TextComponent.Builder out, StringBuilder literal) {
        if (literal.length() == 0) {
            return;
        }
        out.append(MiniMessages.parse(literal.toString()));
        literal.setLength(0);
    }
}
