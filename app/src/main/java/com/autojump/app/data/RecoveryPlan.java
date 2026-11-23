package com.autojump.app.data;

import com.autojump.app.bean.Rule;
import java.util.*;

/** A reviewed recovery imports rules only, never sessions or enabled automation. */
public final class RecoveryPlan {
    public final String original;
    public final String replacement;
    public final String summary;
    public RecoveryPlan(String original, String imported) {
        this.original = original;
        List<Rule> incoming = StrictRuleImport.decode(imported);
        if (incoming.isEmpty()) throw new IllegalArgumentException("恢复文件没有规则；此入口不用于清空配置");
        Set<String> ids = new HashSet<>();
        for (Rule rule : incoming) {
            if (!ids.add(rule.getId())) throw new IllegalArgumentException("恢复文件存在重复规则 ID");
            rule.setEnabled(false);
        }
        String previous;
        try {
            List<Rule> old = original == null || original.trim().isEmpty() ? Collections.emptyList() : StrictRuleImport.decode(original);
            Set<String> oldIds = new HashSet<>();
            for (Rule rule : old) oldIds.add(rule.getId());
            int same = 0; for (String id : ids) if (oldIds.contains(id)) same++;
            previous = "现有 " + old.size() + " 条；替换同 ID " + same + " 条，新增 "
                    + (ids.size()-same) + " 条，移除 " + (oldIds.size()-same) + " 条。";
        } catch (RuntimeException error) { previous = "现有规则无法完整读取，将保留原始数据副本。"; }
        replacement = new RuleJsonCodec().encode(incoming);
        StringBuilder names=new StringBuilder();
        for(Rule rule:incoming) {
            String name=String.valueOf(rule.getName());
            names.append("\n• ").append(name.length()>80?name.substring(0,80)+"…":name).append("（停用）");
        }
        summary = previous + "\n恢复后共 " + incoming.size() + " 条，全部保持停用，请逐条检查后启用。"
                + "\n不会恢复或执行旧队列。\n"+names;
    }
    public void requireUnchanged(String current) {
        if (!Objects.equals(original, current)) throw new IllegalStateException("配置已变化，请重新选择文件并预览");
    }
}
