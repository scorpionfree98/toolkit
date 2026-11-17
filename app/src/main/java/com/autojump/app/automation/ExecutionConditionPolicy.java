package com.autojump.app.automation;

import java.util.function.Supplier;

/** Manual overrides skip only matching predicates, never permission/unlock checks. */
public final class ExecutionConditionPolicy {
    private ExecutionConditionPolicy() {}
    public static String failure(boolean ignoreConditions, Supplier<String> check) {
        return ignoreConditions ? null : check.get();
    }
}
