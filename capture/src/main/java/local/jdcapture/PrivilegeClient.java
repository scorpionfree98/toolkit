package local.jdcapture;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import rikka.shizuku.Shizuku;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class PrivilegeClient {
    private static volatile IProxyControl remote;
    private static CompletableFuture<IProxyControl> pending;
    private static long bindingAt;
    static boolean granted() {
        try { return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException e) { return false; }
    }
    static void connect(Context context) { connection(context); }
    private static synchronized CompletableFuture<IProxyControl> connection(Context context) {
        if (!granted()) throw new IllegalStateException("SHIZUKU_PERMISSION_REQUIRED");
        if (remote != null && remote.asBinder().isBinderAlive()) return CompletableFuture.completedFuture(remote);
        remote = null;
        if (pending != null && !pending.isDone()) {
            if (SystemClock.elapsedRealtime() - bindingAt < 8000) return pending;
            pending.completeExceptionally(new IllegalStateException("SHIZUKU_SERVICE_TIMEOUT"));
        }
        final CompletableFuture<IProxyControl> attempt = new CompletableFuture<>();
        pending = attempt; bindingAt = SystemClock.elapsedRealtime();
        // Same-version APK replacement must not reuse a daemon whose Context
        // and AssetManager still point at the previous APK (not just old Java).
        final int serviceVersion;
        try {
            long installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            serviceVersion = (int)(installed ^ (installed >>> 32)) & 0x7fffffff;
        } catch (PackageManager.NameNotFoundException e) {
            attempt.completeExceptionally(e); return attempt;
        }
        ServiceConnection callback = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                synchronized (PrivilegeClient.class) {
                    if (pending != attempt || attempt.isDone()) return;
                    if (!binder.isBinderAlive()) { attempt.completeExceptionally(new IllegalStateException("SHIZUKU_DISCONNECTED")); return; }
                    remote = IProxyControl.Stub.asInterface(binder); attempt.complete(remote);
                }
            }
            public void onServiceDisconnected(ComponentName name) {
                synchronized (PrivilegeClient.class) {
                    if (pending != attempt) return;
                    remote = null;
                    attempt.completeExceptionally(new IllegalStateException("SHIZUKU_DISCONNECTED"));
                    pending = null;
                }
            }
        };
        try {
            Shizuku.bindUserService(new Shizuku.UserServiceArgs(new ComponentName(context, ProxyUserService.class))
                .daemon(true).processNameSuffix("proxy_control").debuggable(BuildConfig.DEBUG).version(serviceVersion), callback);
        } catch (RuntimeException error) { attempt.completeExceptionally(error); }
        return attempt;
    }
    static IProxyControl await(Context context) throws Exception {
        if (!granted()) throw new IllegalStateException("SHIZUKU_PERMISSION_REQUIRED");
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("BIND_WAIT_ON_MAIN_THREAD");
        CompletableFuture<IProxyControl> result = new CompletableFuture<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try { connection(context).whenComplete((value, error) -> {
                if (error != null) result.completeExceptionally(error); else result.complete(value);
            }); }
            catch (RuntimeException error) { result.completeExceptionally(error); }
        });
        return result.get(8, TimeUnit.SECONDS);
    }
}
