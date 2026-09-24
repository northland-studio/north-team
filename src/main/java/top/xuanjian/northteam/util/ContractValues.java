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

    /** 契约 2.1：{@code display_name} 1~32 字符。 */
    public static final int DISPLAY_NAME_MAX = 32;

    /** 契约 2.1：{@code prefix}/{@code suffix} 长度 ≤ 64（含标签）。 */
    public static final int PREFIX_SUFFIX_MAX = 64;

    /**
     * 离线模式玩家名（原版玩家名规则）：1~16 位字母/数字/下划线。
     * 用于 {@code /nt import} 过滤记分板上非玩家 entry（侧边栏占位 entry 等）。
     */
    public static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private ContractValues() {
    }
}
