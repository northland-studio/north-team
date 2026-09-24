package top.xuanjian.northteam.util;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.scoreboard.Team;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 契约枚举值 ↔ Bukkit 记分板枚举的映射。
 *
 * <p>刻意做到「对 API 变更免疫」：
 * <ul>
 *   <li>颜色使用 {@link NamedTextColor} 的 16 个具名常量；反向查询走本类的自有反查表，
 *       不依赖 Adventure 的 {@code NameMap} 具体方法签名。</li>
 *   <li>{@link Team.Option} / {@link Team.OptionStatus} 一律通过 {@code Enum.valueOf} 按名取常量。
 *       这样即使某个常量在 26.2 被 Mojang 移除（例如 {@code DEATH_MESSAGE_VISIBILITY}），
 *       本插件也只是「取不到、跳过并记录一次说明」，而不是编译不过或运行时抛异常。
 *       </li>
 * </ul>
 */
public final class TeamProperties {

    private static final Map<String, NamedTextColor> COLOR_BY_NAME = new LinkedHashMap<>();
    private static final Map<NamedTextColor, String> NAME_BY_COLOR = new LinkedHashMap<>();

    static {
        register("black", NamedTextColor.BLACK);
        register("dark_blue", NamedTextColor.DARK_BLUE);
        register("dark_green", NamedTextColor.DARK_GREEN);
        register("dark_aqua", NamedTextColor.DARK_AQUA);
        register("dark_red", NamedTextColor.DARK_RED);
        register("dark_purple", NamedTextColor.DARK_PURPLE);
        register("gold", NamedTextColor.GOLD);
        register("gray", NamedTextColor.GRAY);
        register("dark_gray", NamedTextColor.DARK_GRAY);
        register("blue", NamedTextColor.BLUE);
        register("green", NamedTextColor.GREEN);
        register("aqua", NamedTextColor.AQUA);
        register("red", NamedTextColor.RED);
        register("light_purple", NamedTextColor.LIGHT_PURPLE);
        register("yellow", NamedTextColor.YELLOW);
        register("white", NamedTextColor.WHITE);
    }

    private TeamProperties() {
    }

    private static void register(String name, NamedTextColor color) {
        COLOR_BY_NAME.put(name, color);
        NAME_BY_COLOR.put(color, name);
    }

    /** 契约颜色名 → 原版颜色；非法/为空时回退白色（校验器已负责报错）。 */
    public static NamedTextColor color(String contractColorName) {
        if (contractColorName == null) {
            return NamedTextColor.WHITE;
        }
        NamedTextColor color = COLOR_BY_NAME.get(contractColorName.trim().toLowerCase(Locale.ROOT));
        return color == null ? NamedTextColor.WHITE : color;
    }

    /** 原版颜色 → 契约颜色名（{@code /nt import} 回传官网时使用）；无对应时返回 white。 */
    public static String colorName(NamedTextColor color) {
        if (color == null) {
            return "white";
        }
        String name = NAME_BY_COLOR.get(color);
        return name == null ? "white" : name;
    }

    /**
     * 契约 {@code nametag_visibility} / {@code death_message_visibility} →
     * {@link Team.OptionStatus} 常量名。两个字段的取值集合同为
     * {@code always | hideForOtherTeams | hideForOwnTeam | never}。
     */
    public static String optionStatusNameForVisibility(String contractValue) {
        if (contractValue == null) {
            return "ALWAYS";
        }
        return switch (contractValue) {
            case "hideForOtherTeams" -> "FOR_OTHER_TEAMS";
            case "hideForOwnTeam" -> "FOR_OWN_TEAM";
            case "never" -> "NEVER";
            default -> "ALWAYS";
        };
    }

    /**
     * 契约 {@code collision_rule} → {@link Team.OptionStatus} 常量名。
     * 取值集合为 {@code always | pushOtherTeams | pushOwnTeam | never}。
     */
    public static String optionStatusNameForCollision(String contractValue) {
        if (contractValue == null) {
            return "ALWAYS";
        }
        return switch (contractValue) {
            case "pushOtherTeams" -> "FOR_OTHER_TEAMS";
            case "pushOwnTeam" -> "FOR_OWN_TEAM";
            case "never" -> "NEVER";
            default -> "ALWAYS";
        };
    }

    /** {@link Team.OptionStatus} 常量名 → 契约 {@code *_visibility} 取值（import 方向）。 */
    public static String visibilityFromOptionStatus(String statusName) {
        if (statusName == null) {
            return "always";
        }
        return switch (statusName) {
            case "FOR_OTHER_TEAMS" -> "hideForOtherTeams";
            case "FOR_OWN_TEAM" -> "hideForOwnTeam";
            case "NEVER" -> "never";
            default -> "always";
        };
    }

    /** {@link Team.OptionStatus} 常量名 → 契约 {@code collision_rule} 取值（import 方向）。 */
    public static String collisionFromOptionStatus(String statusName) {
        if (statusName == null) {
            return "always";
        }
        return switch (statusName) {
            case "FOR_OTHER_TEAMS" -> "pushOtherTeams";
            case "FOR_OWN_TEAM" -> "pushOwnTeam";
            case "NEVER" -> "never";
            default -> "always";
        };
    }

    /**
     * 按名取 {@link Team.Option} 常量；该常量在本版本不存在时返回 null。
     *
     * <p>用 {@code valueOf} 而非直接引用常量，是为了对「原版移除某个队伍选项」
     * 这类跨版本变更免疫 —— 例如死亡消息可见性曾在原版被整体移除。
     */
    public static Team.Option optionOrNull(String optionName) {
        if (optionName == null) {
            return null;
        }
        try {
            return Team.Option.valueOf(optionName);
        } catch (IllegalArgumentException notPresentInThisVersion) {
            return null;
        }
    }

    /** 按名取 {@link Team.OptionStatus} 常量；不存在时返回 null。 */
    public static Team.OptionStatus optionStatusOrNull(String statusName) {
        if (statusName == null) {
            return null;
        }
        try {
            return Team.OptionStatus.valueOf(statusName);
        } catch (IllegalArgumentException notPresentInThisVersion) {
            return null;
        }
    }

    /**
     * 读取队伍某个选项当前值的常量名；选项或取值在本版本不存在时返回 null。
     */
    public static String currentOptionStatusName(Team team, String optionName) {
        Team.Option option = optionOrNull(optionName);
        if (option == null) {
            return null;
        }
        try {
            Team.OptionStatus status = team.getOption(option);
            return status == null ? null : status.name();
        } catch (RuntimeException e) {
            // 个别实现可能对未知选项抛异常，视为「不支持」
            return null;
        }
    }
}
