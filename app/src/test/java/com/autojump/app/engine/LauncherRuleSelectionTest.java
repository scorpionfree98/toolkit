package com.autojump.app.engine;

import com.autojump.app.bean.Rule;
import com.autojump.app.domain.RuleAction;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherRuleSelectionTest {
    private Rule rule(String id, int priority) {
        Rule r = new Rule(id, id); r.setPriority(priority);
        r.getActions().add(RuleAction.deeplink("demo://test")); return r;
    }
    @Test public void allMatchesAreSelectedInDescendingPriorityWithoutDefault() {
        Rule low = rule("low", 1), high = rule("high", 10);
        LauncherRuleSelection.Result r = LauncherRuleSelection.select(Arrays.asList(low, high), x -> null);
        assertEquals(Arrays.asList(high, low), r.matches);
    }
    @Test public void failedFirstRuleDoesNotPreventLaterMatch() {
        Rule high = rule("high", 10), low = rule("low", 1);
        LauncherRuleSelection.Result r = LauncherRuleSelection.select(Arrays.asList(high, low),
                x -> x == high ? "条件不满足：当前时间不在区间内" : null);
        assertEquals(Arrays.asList(low), r.matches);
        assertEquals(high, r.rejections.get(0).rule);
    }
    @Test public void disabledAndEmptyRulesAreNotCheckedOrExecuted() {
        Rule disabled = rule("disabled", 10); disabled.setEnabled(false);
        Rule empty = new Rule("empty", "empty");
        List<Rule> checked = new ArrayList<>();
        LauncherRuleSelection.Result r = LauncherRuleSelection.select(Arrays.asList(disabled, empty),
                x -> { checked.add(x); return null; });
        assertTrue(r.matches.isEmpty()); assertTrue(checked.isEmpty());
    }
    @Test public void unreadableConditionFailsClosedButOtherRulesContinue() {
        Rule a = rule("a", 10), b = rule("b", 1);
        LauncherRuleSelection.Result r = LauncherRuleSelection.select(Arrays.asList(a, b),
                x -> { if (x == a) throw new SecurityException(); return null; });
        assertEquals(Arrays.asList(b), r.matches);
        assertTrue(r.rejections.get(0).reason.contains("条件无法检查"));
    }
    @Test public void rejectionExplainsEachTasksConditionWithoutDefaultPrompt() {
        LauncherRuleSelection.Result r = LauncherRuleSelection.select(
                Arrays.asList(rule("morning", 2), rule("wifi", 1)),
                x -> x.getId().equals("morning") ? "不在时间区间" : "WiFi 不匹配");
        assertTrue(r.noMatchMessage().contains("不在时间区间"));
        assertTrue(r.noMatchMessage().contains("WiFi 不匹配"));
        assertFalse(r.noMatchMessage().contains("默认"));
    }
    @Test public void equalPriorityPreservesSavedOrderAndInputListIsNotMutated() {
        Rule a = rule("a", 1), b = rule("b", 1);
        List<Rule> source = Arrays.asList(a, b);
        assertEquals(source, LauncherRuleSelection.select(source, x -> null).matches);
        assertEquals(a, source.get(0));
    }
    @Test public void emptyLibraryExplainsHowToManageWithoutRequiringDefault() {
        String message = LauncherRuleSelection.select(null, x -> null).noMatchMessage();
        assertTrue(message.contains("没有可执行")); assertFalse(message.contains("默认"));
    }
}
