package top.xuanjian.northteam.util;

import top.xuanjian.northteam.model.ScoreboardSettings;
import top.xuanjian.northteam.model.TeamConfig;
import top.xuanjian.northteam.model.TeamUnit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按契约 2.1 的字段约束校验官网返回的配置。
 *
 * <p>契约把数据校验的主要责任放在官网（3.6 校验失败返回 400），
 * 但插件侧仍需自保：官网数据异常时给出**中文可读**的提示并拒绝应用，
 * 而不是把非法字段硬写进记分板导致服务端异常。
 *
 * <p>本类不依赖 Bukkit，便于单独推理。
 */
public final class TeamConfigValidator {

    private TeamConfigValidator() {
    }

    public static ValidationResult validate(TeamConfig config) {
        ValidationResult result = ValidationResult.ok();

        if (config == null) {
            result.error("配置为空，无法解析。");
            return result;
        }
        if (config.units() == null) {
            result.error("配置缺少 units 字段（契约 2.1 要求为数组）。");
            return result;
        }
        if (config.schema() != 0 && config.schema() != 1) {
            result.warning("配置 schema=" + config.schema() + "，本插件按 schema=1 解析，字段兼容性由官网保证。");
        }

        List<TeamUnit> units = config.unitsOrEmpty();
        if (units.isEmpty()) {
            result.warning("配置内没有任何队伍（units 为空）；apply 将只执行清理动作。");
        }

        Map<String, Integer> keySeenAt = new LinkedHashMap<>();
        // 玩家名（小写）→ 首次出现的队伍 key，用于检测「一人多队」
        Map<String, String> memberOwner = new LinkedHashMap<>();

        for (int i = 0; i < units.size(); i++) {
            TeamUnit unit = units.get(i);
            String where = "第 " + (i + 1) + " 个队伍";

            if (unit == null) {
                result.error(where + "为 null，无法解析。");
                continue;
            }
            if (unit.key() == null || unit.key().isBlank()) {
                result.error(where + "缺少 key。");
            } else {
                String key = unit.key();
                if (!ContractValues.KEY_PATTERN.matcher(key).matches()) {
                    result.error(where + "（" + key + "）的 key 不合法：必须匹配 [a-z0-9_]{1,16}。");
                }
                Integer previous = keySeenAt.putIfAbsent(key, i);
                if (previous != null) {
                    result.error("队伍 key 重复: " + key + "（第 " + (previous + 1) + " 与第 " + (i + 1) + " 个队伍）。");
                }
                // key 与 nt_ 前缀拼接后的长度：原版记分板队伍名历史上限 16 字符，
                // 这里只提示不阻断，具体说明见 README「已知限制」。
                if (key.length() + "nt_".length() > 16) {
                    result.warning("队伍 " + key + " 的记分板名 nt_" + key + " 长度 "
                            + (key.length() + 3) + " 超过原版 16 字符惯例，部分客户端可能截断。");
                }
            }

            String displayName = unit.displayName();
            if (displayName == null || displayName.isBlank()) {
                result.error(where + "（" + unit.safeKey() + "）缺少 display_name。");
            } else {
                int visibleLength = MiniMessages.stripTags(displayName).length();
                if (visibleLength == 0) {
                    result.error(where + "（" + unit.safeKey() + "）的 display_name 去掉颜色标签后为空。");
                } else if (visibleLength > ContractValues.DISPLAY_NAME_MAX) {
                    result.error(where + "（" + unit.safeKey() + "）的 display_name 可见长度 "
                            + visibleLength + " 超过 " + ContractValues.DISPLAY_NAME_MAX + " 字符。");
                }
                if (MiniMessages.parseOrNull(displayName) == null) {
                    result.error(where + "（" + unit.safeKey() + "）的 display_name 不是合法的 MiniMessage/颜色码文本。");
                }
            }

            if (unit.color() != null && !ContractValues.COLORS.contains(unit.color().toLowerCase(Locale.ROOT))) {
                result.error(where + "（" + unit.safeKey() + "）的 color=" + unit.color()
                        + " 不是 16 种原版颜色名之一。");
            }
            checkTextLength(result, unit, "prefix", unit.prefix());
            checkTextLength(result, unit, "suffix", unit.suffix());
            checkTags(result, where + "（" + unit.safeKey() + "）", "prefix", unit.prefix());
            checkTags(result, where + "（" + unit.safeKey() + "）", "suffix", unit.suffix());
            checkTags(result, where + "（" + unit.safeKey() + "）", "display_name", unit.displayName());
            checkEnum(result, unit, "nametag_visibility", unit.nametagVisibility(), ContractValues.VISIBILITY, "always");
            checkEnum(result, unit, "death_message_visibility", unit.deathMessageVisibility(),
                    ContractValues.VISIBILITY, "always");
            checkEnum(result, unit, "collision_rule", unit.collisionRule(), ContractValues.COLLISION, "always");

            for (String member : unit.normalizedMembers()) {
                if (!ContractValues.PLAYER_NAME_PATTERN.matcher(member).matches()) {
                    result.error(where + "（" + unit.safeKey() + "）的成员名 " + member
                            + " 不符合离线模式玩家名规则 [A-Za-z0-9_]{1,16}。");
                    continue;
                }
                String lower = member.toLowerCase(Locale.ROOT);
                String owner = memberOwner.putIfAbsent(lower, unit.safeKey());
                if (owner != null) {
                    result.error("玩家 " + member + " 同时出现在 " + owner + " 和 " + unit.safeKey() + "两队。");
                }
            }
        }

        ScoreboardSettings scoreboard = config.scoreboardOrDisabled();
        Set<String> unitKeys = new LinkedHashSet<>();
        for (TeamUnit unit : config.unitsOrEmpty()) {
            if (unit.key() != null) {
                unitKeys.add(unit.key());
            }
        }
        checkScoreboard(result, scoreboard, unitKeys);
        return result;
    }

    /**
     * 1.1.0：MiniMessage 对不认识的标签不报错，而是当普通文字原样显示 ——
     * 于是 {@code <dark>}（本意 {@code <dark_gray>}）会直接出现在侧边栏/聊天里。
     * 这里在 apply 阶段挑出来告警，并给出近似建议。
     */
    private static void checkTags(ValidationResult result, String where, String field, String value) {
        for (String literal : MiniMessages.invalidTags(value)) {
            String suggestion = MiniMessages.suggestTag(literal);
            // 日志输出会经过 stripTags（把 <...> 当标签剥掉），所以这里用全角括号包住标签原文，
            // 否则告警会显示成「prefix 里的  不是标准标签」这种看不懂的空白。
            String shown = literal.replace('<', '「').replace('>', '」');
            result.warning(where + " 的 " + field + " 里的 " + shown
                    + " 不是标准的 MiniMessage 标签，会按普通文字原样显示"
                    + (suggestion == null ? "。" : "（是不是想写「" + suggestion + "」？）"));
        }
    }

    private static void checkScoreboard(ValidationResult result, ScoreboardSettings scoreboard,
                                       Set<String> unitKeys) {
        checkTags(result, "scoreboard", "display_name", scoreboard.displayName());
        if (!ContractValues.POSITIONS.contains(scoreboard.positionOrDefault())) {
            result.error("scoreboard.position=" + scoreboard.position() + " 非法，只能是 "
                    + ContractValues.POSITIONS + "。");
        }
        if (!ContractValues.SCORE_MODES.contains(scoreboard.scoreModeOrDefault())) {
            result.error("scoreboard.score_mode=" + scoreboard.scoreMode() + " 非法，只能是 "
                    + ContractValues.SCORE_MODES + "。");
        }
        if (scoreboard.objective() != null && !scoreboard.objective().isBlank()) {
            String objective = scoreboard.objective();
            if (objective.length() > 16) {
                result.warning("scoreboard.objective=" + objective
                        + " 长度超过原版 objective 名 16 字符惯例，可能被客户端截断。");
            }
        }
        if (MiniMessages.parseOrNull(scoreboard.displayNameOrDefault()) == null) {
            result.error("scoreboard.display_name 不是合法的 MiniMessage/颜色码文本。");
        }
        if (ScoreboardSettings.MODE_FIXED.equals(scoreboard.scoreModeOrDefault())) {
            // 1.1.0：fixed 模式真正生效 —— 数字显示在队头行「队伍名 · N」里，
            // N 取 unit_scores[key]；缺 key 的队伍回退为名单人数。
            Map<String, Integer> scores = scoreboard.unitScoresOrEmpty();
            if (scores.isEmpty()) {
                result.warning("score_mode=fixed 但 scoreboard.unit_scores 为空，"
                        + "各队会回退为「按名单人数」显示数字。");
            }
            for (Map.Entry<String, Integer> entry : scores.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    result.error("scoreboard.unit_scores 里存在空 key。");
                    continue;
                }
                if (!unitKeys.contains(key)) {
                    result.error("scoreboard.unit_scores 的 key " + key
                            + " 不对应任何队伍（可用 key：" + unitKeys + "）。");
                    continue;
                }
                if (entry.getValue() == null) {
                    result.error("scoreboard.unit_scores." + key + " 缺少数值。");
                } else if (Math.abs(entry.getValue()) > 1000000) {
                    result.warning("scoreboard.unit_scores." + key + "=" + entry.getValue()
                            + " 数值偏大，侧边栏显示可能不美观。");
                }
            }
        } else if (!scoreboard.unitScoresOrEmpty().isEmpty()) {
            result.warning("scoreboard.unit_scores 只对 score_mode=fixed 生效，"
                    + "当前为 " + scoreboard.scoreModeOrDefault() + "，这些数值会被忽略。");
        }
    }

    private static void checkTextLength(ValidationResult result, TeamUnit unit, String field, String value) {
        if (value == null) {
            return;
        }
        if (value.length() > ContractValues.PREFIX_SUFFIX_MAX) {
            result.error("队伍 " + unit.safeKey() + " 的 " + field + " 长度 " + value.length()
                    + " 超过契约上限 " + ContractValues.PREFIX_SUFFIX_MAX + " 字符（含标签）。");
        }
        if (MiniMessages.parseOrNull(value) == null) {
            result.error("队伍 " + unit.safeKey() + " 的 " + field + " 不是合法的 MiniMessage/颜色码文本。");
        }
    }

    private static void checkEnum(ValidationResult result, TeamUnit unit, String field, String value,
                                 java.util.Set<String> allowed, String fallback) {
        if (value == null || value.isBlank()) {
            result.warning("队伍 " + unit.safeKey() + " 未提供 " + field + "，按契约默认 " + fallback + " 处理。");
            return;
        }
        if (!allowed.contains(value)) {
            result.error("队伍 " + unit.safeKey() + " 的 " + field + "=" + value + " 非法，只能是 " + allowed + "。");
        }
    }
}
