package com.autojump.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.autojump.app.data.*;
import com.autojump.app.automation.*;
import com.autojump.app.util.ShortcutUtil;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Local diagnostic export and explicitly reviewed recovery; no task dispatch. */
public final class DataRecoveryActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private void show(String text) {
        if (!isFinishing() && !isDestroyed()) new android.app.AlertDialog.Builder(this)
                .setMessage(text).setPositiveButton("关闭", null).show();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(20 * getResources().getDisplayMetrics().density); column.setPadding(padding,padding,padding,padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(column); setContentView(scroll);
        TextView title = new TextView(this); title.setText("数据诊断与恢复"); title.setTextSize(23); column.addView(title);
        status = new TextView(this); column.addView(status); refresh();
        Button export = new Button(this); export.setText("导出诊断与原始副本"); column.addView(export);
        export.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("副本可能包含规则链接和个人配置，请只保存到可信位置。这是诊断文件，不会执行任务。")
                .setPositiveButton("选择位置", (d,w) -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE,"toolkit-recovery-diagnostics.json"),10)).setNegativeButton("取消",null).show());
        Button restore = new Button(this); restore.setText("选择规则备份并预览恢复"); column.addView(restore);
        restore.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),11));
        TextView note = new TextView(this); note.setText("请选择原有“导出规则”生成的备份文件。诊断文件中的旧队列仅供排查，不可恢复执行。恢复后的规则全部停用；取消预览不会改变配置。"); column.addView(note);
    }
    private void refresh() {
        String message;
        try { String raw = new RuleRepository(this).rawRules(); message = raw.trim().isEmpty() ? "规则：正常空数据" : "规则：可读取，共 " + StrictRuleImport.decode(raw).size() + " 条"; }
        catch (RuntimeException error) { message = "规则：读取异常，普通保存已受保护，请先导出原始副本"; }
        android.content.SharedPreferences p = getSharedPreferences("automation_execution_sp",0);
        message += "\n历史队列异常副本：" + (p.contains("pending_sessions_corrupt_backup")?"存在（不会重放）":"无");
        message += "\n历史日志异常副本：" + (p.contains("execution_logs_corrupt_backup")?"存在":"无");
        status.setText(message);
    }
    private String diagnostics() {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("diagnosticSchema",1);
        result.put("ruleStorage",getSharedPreferences("jump_config_sp",0).getAll());
        result.put("executionEvidenceOnly",getSharedPreferences("automation_execution_sp",0).getAll());
        return new Gson().toJson(result);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (result!=RESULT_OK || data==null || data.getData()==null) return;
        android.net.Uri uri=data.getData();
        io.execute(() -> {
            try {
                if(request==10) {
                    try(OutputStream out=getContentResolver().openOutputStream(uri)) {
                        if(out==null) throw new IOException(); out.write(diagnostics().getBytes(StandardCharsets.UTF_8));
                    }
                    runOnUiThread(()->show("诊断副本已导出"));
                } else if(request==11) {
                    String document;
                    try(InputStream in=getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                        if(in==null) throw new IOException(); byte[] b=new byte[8192];int n;
                        while((n=in.read(b))!=-1) { if(out.size()+n>4_000_000)throw new IOException("备份超过 4 MB");out.write(b,0,n); }
                        document=out.toString("UTF-8");
                    }
                    RecoveryPlan plan=new RecoveryPlan(new RuleRepository(this).rawRules(),document);
                    runOnUiThread(()->preview(plan));
                }
            } catch(Exception error) { runOnUiThread(()->show("操作未完成，请确认文件格式、大小及存储权限；当前规则未确认被替换。")); }
        });
    }
    private void preview(RecoveryPlan plan) {
        if(isFinishing()||isDestroyed())return;
        new android.app.AlertDialog.Builder(this).setTitle("恢复预览").setMessage(plan.summary)
            .setNegativeButton("取消",null).setPositiveButton("备份原配置并恢复",(d,w)->{
                try {
                    if(!QueuePolicy.ordered(new PendingExecutionStore(this).getAll()).isEmpty())
                        throw new IllegalStateException("请先等待或停止正在执行、排队的任务");
                    new RuleRepository(this).restoreReviewed(plan);
                    refresh();
                    try { ShortcutUtil.refreshDynamicShortcut(this); }
                    catch(RuntimeException ignored) { show("规则已恢复并停用；快捷方式刷新失败，请回管理页重试"); return; }
                    show("规则已恢复并全部停用，请检查后逐条启用");
                } catch(RuntimeException error) { show(error.getMessage()); }
            }).show();
    }
    @Override protected void onDestroy() { io.shutdown();super.onDestroy(); }
}
