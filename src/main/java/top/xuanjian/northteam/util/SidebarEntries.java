package top.xuanjian.northteam.util;

/**
 * 侧边栏「每行一个队伍」方案所用的不可见 entry。
 *
 * <p>侧边栏的一行由一个 entry 承载：行文本放在该行队伍的 prefix 里，
 * entry 本身只用来挂分数。为了让 entry 不显示任何字符，使用原版分节符
 * {@code §} 组合出「不可见且唯一」的字符串。
 *
 * <p>这个格式同时也是本插件的**所有权指纹**：只有本插件生成的侧边栏队伍才会
 * 含有这种 entry，清理时据此判断，避免误删其它插件占用 {@code sb_} 前缀的队伍。
 */
public final class SidebarEntries {

    private static final String HEX = "0123456789abcdef";
    private static final char SECTION = '\u00A7';

    private SidebarEntries() {
    }

    /**
     * 第 {@code index} 行（0 基）的 entry，形如 {@code §a§0§r}。
     * 两个十六进制字符保证同一侧边栏内唯一（最多 256 行）。
     */
    public static String entry(int index) {
        char first = HEX.charAt(Math.floorMod(index, 16));
        char second = HEX.charAt(Math.floorMod(index / 16, 16));
        return "" + SECTION + first + SECTION + second + SECTION + 'r';
    }

    /** 判断 entry 是否为本插件生成的侧边栏占位串。 */
    public static boolean isOurs(String entry) {
        if (entry == null || entry.length() != 6) {
            return false;
        }
        if (entry.charAt(0) != SECTION || entry.charAt(2) != SECTION
                || entry.charAt(4) != SECTION || entry.charAt(5) != 'r') {
            return false;
        }
        return HEX.indexOf(entry.charAt(1)) >= 0 && HEX.indexOf(entry.charAt(3)) >= 0;
    }
}
