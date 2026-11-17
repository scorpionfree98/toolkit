package com.autojump.app.domain;

import java.util.List;
import java.util.function.Supplier;

/** Validate before changing the draft; replacement keeps list order and condition type. */
public final class RuleConditionEdits {
    private RuleConditionEdits() {}
    public static void apply(List<RuleCondition> conditions, int index, Supplier<RuleCondition> create) {
        if (index < -1 || index >= conditions.size()) throw new IllegalArgumentException("条件已变化，请重新打开编辑");
        RuleCondition replacement = create.get();
        if (replacement == null || replacement.getType() == null) throw new IllegalArgumentException("条件无效");
        if (index == -1) conditions.add(replacement);
        else {
            if (conditions.get(index).getType() != replacement.getType()) {
                throw new IllegalArgumentException("修改条件不能改变条件类型");
            }
            conditions.set(index, replacement);
        }
    }
}
