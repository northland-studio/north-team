package top.xuanjian.northteam.apply;

import java.util.List;

/**
 * 一次 {@code /nt apply} 的执行结果。
 *
 * @param success       是否成功（校验失败、记分板异常会置 false）
 * @param dryRun        是否为预演（预演不修改任何服务端状态）
 * @param skippedByVersion 是否因 version 与 state.json 相同而跳过
 * @param plan          本次的变更计划
 * @param messages      需要展示给管理员的中文提示行（MiniMessage/传统码混排）
 */
public record ApplyOutcome(
        boolean success,
        boolean dryRun,
        boolean skippedByVersion,
        ApplyPlan plan,
        List<String> messages
) {

    public static ApplyOutcome failure(List<String> messages) {
        return new ApplyOutcome(false, false, false, null, List.copyOf(messages));
    }

    public static ApplyOutcome skipped(List<String> messages) {
        return new ApplyOutcome(true, false, true, null, List.copyOf(messages));
    }
}
