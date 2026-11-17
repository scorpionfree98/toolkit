package com.autojump.app.automation;

/** Persist every admission before the first external launch can suspend the process. */
public final class BatchAdmission {
    public interface Callback { void complete(boolean queued, String message); }
    private final int total;
    private final Runnable dispatch;
    private final Callback callback;
    private int completed;
    private int queued;
    private boolean cancelled;
    private String lastFailure;
    public BatchAdmission(int total, Runnable dispatch, Callback callback) {
        if (total <= 0) throw new IllegalArgumentException("empty batch");
        this.total = total; this.dispatch = dispatch; this.callback = callback;
    }
    public void complete(boolean accepted, String message) {
        if (completed >= total) return;
        if (accepted) queued++;
        else {
            lastFailure = message;
            if (message != null && message.startsWith("用户停止")) cancelled = true;
        }
        if (++completed != total) return;
        if (cancelled) { callback.complete(false, "用户停止了等待中的条件匹配任务"); return; }
        if (queued > 0) dispatch.run();
        callback.complete(queued > 0, queued == total ? "已匹配并排队 " + queued + " 条任务"
                : "已排队 " + queued + " 条，未入队 " + (total - queued) + " 条：" + lastFailure);
    }
}
