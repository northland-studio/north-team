package top.xuanjian.northteam.apply;

import java.util.List;

/**
 * 一次 {@code /nt apply} 将要发生的变更（只读推导结果）。
 *
 * <p>{@code --dry-run} 直接打印本对象，不触碰服务端状态；
 * 正式 apply 也先算出本对象，执行完再与实际结果对照，便于排错。
 */
public record ApplyPlan(
        List<String> teamsToCreate,
        List<String> teamsToUpdate,
        List<String> teamsToDelete,
        List<String> playersToAssign,
        List<String> membersPendingJoin,
        List<String> unitsToLeave,
        int sidebarRows
) {

    public int createdCount() {
        return teamsToCreate.size();
    }

    public int updatedCount() {
        return teamsToUpdate.size();
    }

    public int deletedCount() {
        return teamsToDelete.size();
    }

    /** 受影响的在线玩家数（重分队或新入队）。 */
    public int movedPlayerCount() {
        return playersToAssign.size();
    }

    /** 离线成员数：这些玩家会在下次登录时由 PlayerJoinEvent 补入。 */
    public int pendingCount() {
        return membersPendingJoin.size();
    }

    /**
     * 供聊天栏展示的变更摘要（中文）。
     */
    public List<String> describe() {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("<gray>新建队伍：<white>" + createdCount() + "</white> 个"
                + (teamsToCreate.isEmpty() ? "" : " <dark_gray>(" + String.join(", ", teamsToCreate) + ")"));
        lines.add("<gray>更新队伍：<white>" + updatedCount() + "</white> 个"
                + (teamsToUpdate.isEmpty() ? "" : " <dark_gray>(" + String.join(", ", teamsToUpdate) + ")"));
        lines.add("<gray>删除队伍：<white>" + deletedCount() + "</white> 个"
                + (teamsToDelete.isEmpty() ? "" : " <dark_gray>(" + String.join(", ", teamsToDelete) + ")"));
        lines.add("<gray>重分队玩家（在线，立即生效）：<white>" + movedPlayerCount() + "</white> 人");
        lines.add("<gray>待登录补入玩家（离线）：<white>" + pendingCount() + "</white> 人");
        if (!unitsToLeave.isEmpty()) {
            lines.add("<gray>移出受管队伍（不在新名单内）：<white>" + unitsToLeave.size() + "</white> 人");
        }
        lines.add("<gray>侧边栏行数：<white>" + sidebarRows + "</white>");
        return lines;
    }
}
