package top.xuanjian.northteam.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 契约 docs/API.md 里出现的全部枚举取值与字段约束的集中定义。
 *
 * <p>这里刻意不依赖任何 Bukkit 类型，方便校验逻辑独立、可读、可复用；
 * Bukkit 侧的映射（{@code Team.Option} / {@code NamedTextColor}）在
 * {@link TeamProperties} 中完成。
 */
public final class ContractValues {

    /** 契约 2.1：16 种原版颜色名。 */
    public static final Set<String> COLORS = new LinkedHashSet<>(List.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white"));

    /** 契约 2.1：nametag_visibility / death_message_visibility 取值。 */
    public static final Set<String> VISIBILITY = new LinkedHashSet<>(List.of(
            "always", "hideForOtherTeams", "hideForOwnTeam", "never"));

    /** 契约 2.1：collision_rule 取值。 */
    public static final Set<String> COLLISION = new LinkedHashSet<>(List.of(
            "always", "pushOtherTeams", "pushOwnTeam", "never"));

    /** 契约 2.1：scoreboard.position 取值。 */
    public static final Set<String> POSITIONS = new LinkedHashSet<>(List.of(
            "sidebar", "list", "below_name"));

    /** 契约 2.1：scoreboard.score_mode 取值。 */
    public static final Set<String> SCORE_MODES = new LinkedHashSet<>(List.of(
            "member_count", "fixed"));

    /** 契约 2.1：{@code key} 约束 {@code [a-z0-9_]{1,16}}。 */
    public static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_]{1,16}");

    /**
     * 契约 2.1：{@code display_name} 1~32 字符。
     *
     * <p>注意口径：这里限制的是**去掉颜色标签后的可见长度**（校验器用 stripTags 判定），
     * MiniMessage 标签本身不占额度 —— 这样「多段变色」不会因为标签变长而被拒。
     */
    public static final int DISPLAY_NAME_MAX = 32;

    /**
     * 契约 2.1：{@code prefix}/{@code suffix} 长度 ≤ 256（含标签）。
     *
     * <p>1.1.0 起从 64 放宽到 256：MiniMessage 标签很占长度（{@code <dark_gray>…</dark_gray>}
     * 一段就 21 字符），64 在多段变色 + 图标前缀时很容易撞上限；现代版本的前后缀是
     * Component，原版没有硬性长度限制，只有客户端显示上的截断，所以按"够用且不离谱"取 256。
     */
    public static final int PREFIX_SUFFIX_MAX = 256;

    /** 文本字段的原始长度上限（防止极端长的标签串把存储/日志撑坏）。 */
    public static final int TEXT_RAW_MAX = 512;

    /**
     * 离线模式玩家名（原版玩家名规则）：1~16 位字母/数字/下划线。
     * 用于 {@code /nt import} 过滤记分板上非玩家 entry（侧边栏占位 entry 等）。
     */
    public static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private ContractValues() {
    }
}
