package com.autojump.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.autojump.app.bean.Rule;
import java.util.ArrayList;
import java.util.List;

public class RuleRepository {
    private static final Object LOCK = new Object();
    private static final String TAG = "RuleRepository";
    private static final String SP_NAME = "jump_config_sp";
    private static final String KEY_RULE_LIST = "rule_list";
    private final SharedPreferences preferences;
    private final RuleJsonCodec codec;

    public RuleRepository(Context context) {
        this(context.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE));
    }

    RuleRepository(SharedPreferences preferences) {
        this.preferences = preferences;
        codec = new RuleJsonCodec();
    }

    public List<Rule> getRules() {
        String json = preferences.getString(KEY_RULE_LIST, "");
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            List<Rule> rules = codec.decode(json);
            if (!json.trim().startsWith("{")) saveRules(rules);
            return rules;
        } catch (RuntimeException error) {
            Log.e(TAG, "读取规则失败，保留原始数据", error);
            return new ArrayList<>();
        }
    }

    public void saveRules(List<Rule> rules) {
        synchronized (LOCK) {
            String original = preferences.getString(KEY_RULE_LIST, "");
            requireReadableBeforeSave(original);
            if (!preferences.edit().putString(KEY_RULE_LIST, codec.encode(rules)).commit())
                throw new IllegalStateException("规则保存失败，未确认写入，请重试");
        }
    }

    static void requireReadableBeforeSave(String original) {
        if (original == null || original.trim().isEmpty()) return;
        try { new RuleJsonCodec().decode(original); }
        catch (RuntimeException error) {
            throw new IllegalStateException("现有规则读取异常，已阻止覆盖。请先备份并恢复原配置。", error);
        }
    }

    public String exportRules(List<Rule> rules) {
        return codec.encode(rules);
    }

    public List<Rule> importRules(String json) {
        return StrictRuleImport.decode(json);
    }

    public String rawRules() { return preferences.getString(KEY_RULE_LIST, ""); }

    public void restoreReviewed(RecoveryPlan plan) {
        synchronized (LOCK) {
            plan.requireUnchanged(rawRules());
            StrictRuleImport.decode(plan.replacement);
            SharedPreferences.Editor edit = preferences.edit();
            // Preserve the first pre-recovery document and the immediate previous one.
            if (!preferences.contains("first_pre_recovery_raw")) edit.putString("first_pre_recovery_raw", plan.original);
            if (!edit.putString("previous_pre_recovery_raw", plan.original)
                    .putLong("previous_pre_recovery_at", System.currentTimeMillis())
                    .putString(KEY_RULE_LIST, plan.replacement).commit())
                throw new IllegalStateException("恢复写入失败，未确认成功，请导出诊断后重试");
        }
    }
}
