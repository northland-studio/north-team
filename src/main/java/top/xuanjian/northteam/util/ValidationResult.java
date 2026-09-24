package top.xuanjian.northteam.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 校验结果：收集全部问题，而不是遇到第一个就返回，
 * 这样 {@code /nt apply} 能一次性告诉管理员配置里所有不合规的地方。
 */
public final class ValidationResult {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void error(String message) {
        errors.add(message);
    }

    public void warning(String message) {
        warnings.add(message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public static ValidationResult ok() {
        return new ValidationResult();
    }
}
