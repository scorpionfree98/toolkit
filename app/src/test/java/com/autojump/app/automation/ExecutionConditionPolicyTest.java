package com.autojump.app.automation;

import com.autojump.app.bean.Rule;
import com.autojump.app.domain.RuleCondition;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExecutionConditionPolicyTest {
    @Test public void manualRunDoesNotEvenReadMatchingConditions() {
        assertNull(ExecutionConditionPolicy.failure(true, () -> { throw new AssertionError("must not evaluate"); }));
    }
    @Test public void automaticRunStillRejectsFalseOrUnreadableConditions() {
        assertEquals("WiFi 不符合", ExecutionConditionPolicy.failure(false, () -> "WiFi 不符合"));
        assertEquals("条件无法检查", ExecutionConditionPolicy.failure(false, () -> "条件无法检查"));
    }
    @Test public void oldAndNewAutomaticSessionsNeverInheritManualOverride() {
        assertFalse(new ExecutionSession().isIgnoreConditions());
        assertFalse(new Gson().fromJson("{}", ExecutionSession.class).isIgnoreConditions());
        assertFalse(ExecutionSession.fromRule(new Rule("a", "a"), 1).isIgnoreConditions());
    }
    @Test public void manualOverrideSurvivesPersistenceAndQueueStartWithoutMutatingRule() {
        Rule rule = new Rule("a", "a"); rule.getConditions().add(RuleCondition.wifi("test-only"));
        Gson gson = new Gson(); String before = gson.toJson(rule);
        ExecutionSession s = ExecutionSession.fromRule(rule, 1); s.setIgnoreConditions(true);
        s = gson.fromJson(gson.toJson(s), ExecutionSession.class);
        s.startFrom(rule, 2);
        assertTrue(s.isIgnoreConditions()); assertEquals(before, gson.toJson(rule));
        assertNull(ExecutionConditionPolicy.failure(s.isIgnoreConditions(), () -> "reject"));
    }
    @Test public void manualOverrideDoesNotChangeUnlockOrCancelSafety() {
        Rule r = new Rule("a", "a"); r.setRequestUnlockBeforeRun(true);
        ExecutionSession s = ExecutionSession.fromRule(r, 1); s.setIgnoreConditions(true);
        assertTrue(s.isRequestUnlockBeforeRun()); s.requestCancel(); assertTrue(s.isCancelRequested());
    }
}
