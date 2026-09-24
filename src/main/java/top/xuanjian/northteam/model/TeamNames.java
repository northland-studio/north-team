package top.xuanjian.northteam.model;

/**
 * 本插件在服务端记分板上占用的两个命名空间。
 *
 * <p>NorthTeam 只在自己的命名空间内创建/删除队伍，绝不触碰其它插件的队伍：
 * <ul>
 *   <li>{@link #FORMAL_PREFIX} —— 由配置 {@code key} 派生的「正式队伍」，即
 *       {@code /team} 与 {@code /nt} 使用的那一套（契约 2.1：{@code nt_} 前缀）。</li>
 *   <li>{@link #SIDEBAR_PREFIX} —— 侧边栏计分板的「每队一行」所用的行队伍，
 *       与正式队伍分开命名，避免与 {@code /team} 冲突。</li>
 * </ul>
 */
public final class TeamNames {

    /** 正式队伍前缀（契约规定）。 */
    public static final String FORMAL_PREFIX = "nt_";

    /**
     * 侧边栏行队伍前缀。侧边栏「每行一个 team」的做法需要为每一行创建一个
     * scoreboard team，用它的 prefix 承载行文本；这些队伍与正式队伍必须区分命名。
     */
    public static final String SIDEBAR_PREFIX = "sb_";

    private TeamNames() {
    }

    public static boolean isFormal(String teamName) {
        return teamName != null && teamName.startsWith(FORMAL_PREFIX);
    }

    public static boolean isSidebar(String teamName) {
        return teamName != null && teamName.startsWith(SIDEBAR_PREFIX);
    }

    /** 是否为 NorthTeam 自己创建的队伍（任一命名空间）。 */
    public static boolean isOwned(String teamName) {
        return isFormal(teamName) || isSidebar(teamName);
    }

    /** 侧边栏第 index 行（从 1 开始）对应的队伍名。 */
    public static String sidebarTeamName(int index) {
        return SIDEBAR_PREFIX + index;
    }
}
