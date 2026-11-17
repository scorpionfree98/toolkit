package com.autojump.app.engine;

import com.autojump.app.bean.Rule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Desktop launch scans enabled rules; a legacy default never restricts selection. */
public final class LauncherRuleSelection {
    public interface Check { String failure(Rule rule); }
    public static final class Rejection {
        public final Rule rule;
        public final String reason;
        Rejection(Rule rule, String reason) { this.rule = rule; this.reason = reason; }
    }
    public static final class Result {
        public final List<Rule> matches = new ArrayList<>();
        public final List<Rejection> rejections = new ArrayList<>();
        public String noMatchMessage() {
            if (rejections.isEmpty()) return "没有可执行的已启用任务，请从长按菜单进入自动任务管理。";
            StringBuilder text = new StringBuilder("当前没有符合条件的任务：");
            for (int i = 0; i < rejections.size() && i < 10; i++) {
                Rejection r = rejections.get(i);
                text.append("\n\n「").append(r.rule.getName()).append("」\n").append(r.reason);
            }
            if (rejections.size() > 10) text.append("\n其余原因见运行记录。");
            return text.toString();
        }
    }
    private LauncherRuleSelection() {}
    public static Result select(List<Rule> rules, Check check) {
        Result result = new Result();
        List<Rule> candidates = new ArrayList<>();
        if (rules != null) for (Rule rule : rules) {
            if (rule != null && rule.isEnabled() && !rule.getActions().isEmpty()) candidates.add(rule);
        }
        candidates.sort(Comparator.comparingInt(Rule::getPriority).reversed());
        for (Rule rule : candidates) {
            String reason;
            try { reason = check.failure(rule); }
            catch (RuntimeException error) { reason = "条件无法检查：" + error.getClass().getSimpleName(); }
            if (reason == null) result.matches.add(rule);
            else result.rejections.add(new Rejection(rule, reason));
        }
        return result;
    }
}
