package com.autojump.app.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.autojump.app.automation.AccessibilityServiceState;
import com.autojump.app.automation.AutoJumpAccessibilityService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.ArrayList;
import java.util.List;
import rikka.shizuku.Shizuku;

/** Bounded Shizuku recovery for this app's own accessibility service. */
public final class ShizukuAccessibilityRecovery {
    public static final int REQUEST_PERMISSION = 4107;
    private static final long BIND_TIMEOUT_MS = 25_000L;
    private static final int CONNECTION_CHECK_ATTEMPTS = 20;
    private static final long CONNECTION_CHECK_INTERVAL_MS = 300L;
    private static final List<Callback> waitingCallbacks = new ArrayList<>();
    private static boolean recovering;

    public enum Availability { NOT_RUNNING, PERMISSION_REQUIRED, READY }

    public interface Callback {
        void onResult(boolean restoredAndEnabled, String message);
    }

    private ShizukuAccessibilityRecovery() {}

    public static Availability availability() {
        try {
            if (!Shizuku.pingBinder()) return Availability.NOT_RUNNING;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? Availability.READY : Availability.PERMISSION_REQUIRED;
        } catch (RuntimeException error) {
            return Availability.NOT_RUNNING;
        }
    }

    public static boolean requestPermission() {
        if (availability() == Availability.NOT_RUNNING) return false;
        try {
            Shizuku.requestPermission(REQUEST_PERMISSION);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static void recover(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            waitingCallbacks.add(callback);
            if (recovering) return;
            recovering = true;
            recoverInternal(app, (success, message) -> {
                new com.autojump.app.automation.ExecutionLogStore(app).appendDiagnostic(
                        success ? "RECOVERY_CONNECTED" : "RECOVERY_FAILED", message);
                List<Callback> batch = new ArrayList<>(waitingCallbacks);
                waitingCallbacks.clear();
                recovering = false;
                for (Callback item : batch) item.onResult(success, message);
            });
        });
    }

    private static void recoverInternal(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        if (availability() != Availability.READY) {
            callback.onResult(false, "Shizuku 未运行或尚未授权");
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        long previousConnection = AutoJumpAccessibilityService.connectionGeneration();
        new com.autojump.app.automation.ExecutionLogStore(app).appendDiagnostic(
                "RECOVERY", "开始定向重连自动点击服务；不重启手机");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecoveryAttempt attempt = new RecoveryAttempt();
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(app, ShizukuAccessibilityBridgeService.class))
                .processNameSuffix("accessibility_recovery")
                .daemon(false)
                .version(2);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (executor.isShutdown() || !attempt.claimConnection()) return;
                try {
                    executor.execute(() -> {
                        String message;
                        boolean settingConfirmed = false;
                        try {
                            String component = new ComponentName(app,
                                    AutoJumpAccessibilityService.class).flattenToString();
                            String result = IShizukuAccessibilityBridge.Stub.asInterface(binder)
                                    .restoreAccessibility(component);
                            settingConfirmed = "RESTORED".equals(result)
                                    || "RECONNECTED".equals(result);
                            message = "RESTORED".equals(result)
                                    ? "系统设置已恢复，正在确认服务状态"
                                    : "已请求服务重连，正在确认新连接";
                        } catch (Exception error) {
                            message = error.getMessage() == null
                                    ? "Shizuku 恢复失败" : error.getMessage();
                        }
                        boolean finalSettingConfirmed = settingConfirmed;
                        String finalMessage = message;
                        main.post(() -> finish(app, args, this, executor, attempt,
                                finalSettingConfirmed, finalMessage, previousConnection, callback));
                    });
                } catch (RejectedExecutionException ignored) {
                    // Shizuku can deliver a duplicate connection callback while a
                    // non-daemon user service is being torn down after success.
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                main.post(() -> finish(app, args, this, executor, attempt,
                        false, "Shizuku 服务已断开，未确认恢复结果", previousConnection, callback));
            }
        };
        main.postDelayed(() -> {
            if (attempt.finish()) {
                safeUnbind(args, connection);
                executor.shutdownNow();
                callback.onResult(false, "Shizuku 恢复超时，未确认成功");
            }
        }, BIND_TIMEOUT_MS);
        try {
            Shizuku.bindUserService(args, connection);
        } catch (RuntimeException error) {
            if (attempt.finish()) {
                safeUnbind(args, connection);
                executor.shutdownNow();
                callback.onResult(false, error.getMessage() == null
                        ? "无法连接 Shizuku 用户服务" : error.getMessage());
            }
        }
    }

    private static void finish(Context context, Shizuku.UserServiceArgs args,
                               ServiceConnection connection, ExecutorService executor,
                               RecoveryAttempt attempt, boolean settingConfirmed, String message,
                               long previousConnection, Callback callback) {
        if (!attempt.finish()) return;
        safeUnbind(args, connection);
        executor.shutdownNow();
        if (!settingConfirmed) {
            callback.onResult(false, message);
            return;
        }
        confirmServiceConnection(context, message, callback, previousConnection, 0);
    }

    private static void confirmServiceConnection(Context context, String message,
                                                 Callback callback, long previousConnection, int attempt) {
        boolean settingEnabled = AccessibilityServiceState.isEnabled(context);
        boolean serviceConnected = AutoJumpAccessibilityService.connectedContext() != null;
        if (RecoveryConnectionPolicy.confirmed(settingEnabled, serviceConnected,
                previousConnection, AutoJumpAccessibilityService.connectionGeneration())) {
            callback.onResult(true, "自动点击服务已恢复并确认连接 ✓");
            return;
        }
        if (attempt >= CONNECTION_CHECK_ATTEMPTS - 1) {
            callback.onResult(false, message + "；系统设置已更新，但未确认服务实际连接");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> confirmServiceConnection(context, message, callback, previousConnection, attempt + 1),
                CONNECTION_CHECK_INTERVAL_MS);
    }

    private static void safeUnbind(Shizuku.UserServiceArgs args,
                                   ServiceConnection connection) {
        try {
            Shizuku.unbindUserService(args, connection, true);
        } catch (RuntimeException ignored) {
            // Shizuku may have stopped between the result and cleanup.
        }
    }
}
