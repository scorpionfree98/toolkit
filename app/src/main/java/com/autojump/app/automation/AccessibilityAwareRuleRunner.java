package com.autojump.app.automation;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.autojump.app.bean.Rule;
import com.autojump.app.domain.ActionType;
import com.autojump.app.domain.RuleAction;
import com.autojump.app.shizuku.ShizukuAccessibilityRecovery;
import com.google.gson.Gson;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

/** Android adapter for the execution gate, holding only the application context. */
public final class AccessibilityAwareRuleRunner {
    private static AccessibilityExecutionGate gate;
    private static AccessibilityExecutionGate matchingGate;
    private static final Map<String, Boolean> conditionOverrides = new HashMap<>();
    private static final Map<String, WeakReference<Activity>> foregroundOwners = new HashMap<>();
    public interface Callback { void onResult(boolean queued, String message); }
    private AccessibilityAwareRuleRunner() {}

    public static void cancelWaiting() {
        Runnable cancel = () -> {
            if (gate != null) gate.cancelAll();
            if (matchingGate != null) matchingGate.cancelAll();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) cancel.run();
        else new Handler(Looper.getMainLooper()).post(cancel);
    }

    public static void enqueue(Context context, Rule rule, Callback callback) {
        enqueue(context, rule, false, callback);
    }

    /** Only in-app explicit Run/Save-and-run buttons use this override. */
    public static void enqueueManual(Context context, Rule rule, Callback callback) {
        enqueue(context, rule, true, callback);
    }

    private static void enqueue(Context context, Rule rule, boolean ignoreConditions, Callback callback) {
        Context app = context.getApplicationContext();
        Activity foreground = context instanceof Activity ? (Activity) context : null;
        Gson gson = new Gson();
        Rule snapshot = gson.fromJson(gson.toJson(rule), Rule.class);
        new Handler(Looper.getMainLooper()).post(() -> {
            String conditionFailure = ExecutionConditionPolicy.failure(ignoreConditions,
                    () -> QueueCoordinator.conditionFailure(app, snapshot));
            if (conditionFailure != null) {
                new ExecutionLogStore(app).append(
                        ExecutionSession.fromRule(snapshot, System.currentTimeMillis()),
                        "SKIPPED", conditionFailure);
                callback.onResult(false, conditionFailure);
                return;
            }
            if (conditionOverrides.containsKey(snapshot.getId())) {
                callback.onResult(false, "指令正在等待自动点击服务，本次触发已合并");
                return;
            }
            conditionOverrides.put(snapshot.getId(), ignoreConditions);
            if (foreground != null && !foreground.isFinishing() && !foreground.isDestroyed()) {
                foregroundOwners.put(snapshot.getId(), new WeakReference<>(foreground));
            }
            android.util.Log.i("ToolkitAccessibilityGate", "request needsAccessibility="
                    + requiresAccessibility(snapshot) + " enabled="
                    + AccessibilityServiceState.isEnabled(app) + " connected="
                    + (AutoJumpAccessibilityService.connectedContext() != null));
            if (gate == null) gate = new AccessibilityExecutionGate(
                    new AccessibilityExecutionGate.Environment() {
                        @Override public boolean connected() {
                            return AccessibilityServiceState.isEnabled(app)
                                    && AutoJumpAccessibilityService.connectedContext() != null;
                        }
                        @Override public AccessibilityExecutionGate.Availability availability() {
                            return AccessibilityExecutionGate.Availability.valueOf(
                                    ShizukuAccessibilityRecovery.availability().name());
                        }
                        @Override public void later(Runnable task, long delayMs) {
                            new Handler(Looper.getMainLooper()).postDelayed(task, delayMs);
                        }
                        @Override public void restore(AccessibilityExecutionGate.Result result) {
                            android.util.Log.i("ToolkitAccessibilityGate", "starting Shizuku recovery");
                            ShizukuAccessibilityRecovery.recover(app, (success, message) -> {
                                android.util.Log.i("ToolkitAccessibilityGate", "recovery result=" + success);
                                result.complete(success, message);
                            });
                        }
                        @Override public boolean enqueue(Rule selected) {
                            // Persist every recovered waiter before any external launch can
                            // suspend this process. Dispatch before the launcher callback
                            // closes its transparent foreground activity; HyperOS may block
                            // the same external launch immediately afterwards.
                            WeakReference<Activity> reference = foregroundOwners.remove(selected.getId());
                            Activity owner = reference == null ? null : reference.get();
                            Context dispatch = owner != null && !owner.isFinishing() && !owner.isDestroyed()
                                    ? owner : app;
                            Context launchContext = dispatch;
                            boolean bypass = Boolean.TRUE.equals(conditionOverrides.remove(selected.getId()));
                            return ForegroundQueueDispatch.run(
                                    () -> QueueCoordinator.enqueueDeferred(app, selected, bypass) != null,
                                    () -> QueueCoordinator.pump(launchContext));
                        }
                    });
            gate.run(snapshot, requiresAccessibility(snapshot), (queued, message) -> {
                conditionOverrides.remove(snapshot.getId());
                foregroundOwners.remove(snapshot.getId());
                if (!queued) new ExecutionLogStore(app).append(
                        ExecutionSession.fromRule(snapshot, System.currentTimeMillis()),
                        "BLOCKED", message);
                callback.onResult(queued, message);
            });
        });
    }

    /** Conditions were selected by the launcher; the queue rechecks before execution. */
    public static void enqueueMatching(Context context, List<Rule> matches, Callback callback) {
        Context app = context.getApplicationContext();
        WeakReference<Activity> owner = new WeakReference<>(context instanceof Activity ? (Activity) context : null);
        List<Rule> snapshots = new ArrayList<>();
        Gson gson = new Gson();
        for (Rule rule : matches) snapshots.add(gson.fromJson(gson.toJson(rule), Rule.class));
        new Handler(Looper.getMainLooper()).post(() -> {
            if (matchingGate != null) {
                callback.onResult(false, "条件匹配任务正在等待自动点击服务，本次触发已合并");
                return;
            }
            if (snapshots.isEmpty()) { callback.onResult(false, "没有符合条件的任务"); return; }
            Set<String> ready = new HashSet<>();
            int[] admitted = {0};
            AccessibilityExecutionGate localGate = new AccessibilityExecutionGate(
                    new AccessibilityExecutionGate.Environment() {
                        @Override public boolean connected() {
                            return AccessibilityServiceState.isEnabled(app)
                                    && AutoJumpAccessibilityService.connectedContext() != null;
                        }
                        @Override public AccessibilityExecutionGate.Availability availability() {
                            return AccessibilityExecutionGate.Availability.valueOf(
                                    ShizukuAccessibilityRecovery.availability().name());
                        }
                        @Override public void later(Runnable task, long delay) {
                            new Handler(Looper.getMainLooper()).postDelayed(task, delay);
                        }
                        @Override public void restore(AccessibilityExecutionGate.Result result) {
                            ShizukuAccessibilityRecovery.recover(app, result::complete);
                        }
                        @Override public boolean enqueue(Rule rule) {
                            // Permission callbacks may arrive out of order; persist only
                            // after all results, in the original priority order.
                            ready.add(rule.getId());
                            return true;
                        }
                    });
            matchingGate = localGate;
            BatchAdmission completion = new BatchAdmission(snapshots.size(), () -> {
                for (Rule rule : snapshots) if (ready.contains(rule.getId())) {
                    if (QueueCoordinator.enqueueDeferred(app, rule) != null) admitted[0]++;
                }
                Activity page = owner.get();
                if (admitted[0] > 0) QueueCoordinator.pump(
                        page != null && !page.isFinishing() && !page.isDestroyed() ? page : app);
            }, (queued, message) -> {
                matchingGate = null;
                callback.onResult(queued && admitted[0] > 0, queued
                        ? "已匹配 " + snapshots.size() + " 条，已排队 " + admitted[0] + " 条；未入队原因见运行记录"
                        : message);
            });
            for (Rule rule : snapshots) localGate.run(rule, requiresAccessibility(rule), (queued, message) -> {
                if (!queued) new ExecutionLogStore(app).append(
                        ExecutionSession.fromRule(rule, System.currentTimeMillis()), "BLOCKED", message);
                completion.complete(queued, message);
            });
        });
    }

    static boolean requiresAccessibility(Rule rule) {
        if (rule == null) return false;
        for (RuleAction action : rule.getActions()) {
            if (action != null && (action.getType() == ActionType.CLICK
                    || action.getType() == ActionType.RECORDED_TOUCH)) return true;
        }
        return false;
    }
}
