package local.jdcapture;

import android.content.Context;
import android.os.Binder;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** Fixed privileged operations, a renewable lease, and a persistent recovery record. */
public class ProxyUserService extends IProxyControl.Stub {
    private final RuntimeHost runtime;
    private final int appUid;
    private long lastHeartbeat;
    private String baseline;
    private int leaseRecoveryAttempts;
    public ProxyUserService(Context context) {
        runtime = new RuntimeHost(context); appUid = context.getApplicationInfo().uid;
        try { recover(); } catch (Exception e) { throw new IllegalStateException("RECOVERY_FAILED", e); }
        Thread watchdog = new Thread(() -> {
            for (;;) {
                try {
                    Thread.sleep(3000);
                    synchronized (this) {
                        if (leaseRecoveryAttempts<3 && (baseline != null || runtime.started()) &&
                            (android.os.SystemClock.elapsedRealtime() - lastHeartbeat > 20000 || !runtime.alive())) {
                            leaseRecoveryAttempts++;
                            ResourceCleanup.run(this::restoreOwned,runtime::stop);
                        }
                    }
                } catch (Exception ignored) { /* At most three lease cleanup attempts; explicit stop can retry. */ }
            }
        }, "proxy-lease"); watchdog.setDaemon(true); watchdog.start();
    }
    private void caller() {
        int uid = Binder.getCallingUid();
        if (uid != appUid && uid != android.os.Process.myUid()) throw new SecurityException("CALLER_REJECTED");
    }
    private static boolean direct(String p) { return p.equals(":0") || p.equals("null") || p.isEmpty(); }
    private String run(String... args) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("SETTINGS_TIMEOUT"); }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) { byte[] buf = new byte[1024]; int n; while ((n = in.read(buf)) != -1 && out.size() < 16384) out.write(buf, 0, n); }
            if (p.exitValue() != 0) throw new IOException("SETTINGS_PERMISSION_DENIED");
            return out.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (Exception e) { throw new IllegalStateException("SHIZUKU_OPERATION_FAILED", e); }
    }
    private String proxy() { return run("/system/bin/settings", "get", "global", "http_proxy"); }
    private void recover() throws Exception {
        File f = new File(runtime.root, "owner.json");
        if (f.isFile()) {
            baseline = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getString("previous");
            if (!direct(baseline)) throw new IOException("INVALID_SAVED_BASELINE");
            restoreOwned();
        }
    }
    private void restoreOwned() throws Exception {
        if (baseline == null) return;
        if (proxy().equals("127.0.0.1:18886")) {
            if (baseline.equals(":0")) run("/system/bin/settings", "put", "global", "http_proxy", ":0");
            else run("/system/bin/settings", "delete", "global", "http_proxy");
            if (proxy().equals("127.0.0.1:18886")) throw new IOException("RESTORE_READBACK_FAILED");
        }
        Files.deleteIfExists(new File(runtime.root, "owner.json").toPath()); baseline = null;
    }
    @Override public String readProxy() { caller(); return proxy(); }
    @Override public String readPac() { caller(); return run("/system/bin/settings", "get", "global", "global_proxy_pac_url"); }
    @Override public synchronized void setLocalProxy(String previous) {
        caller();
        if (!runtime.httpProxyReady() || baseline != null || !proxy().equals(previous) || !direct(previous)) throw new IllegalStateException("EXISTING_PROXY_OR_ENGINE_DOWN");
        try {
            JSONObject owner = new JSONObject(); owner.put("previous", previous); owner.put("session", runtime.sessionName());
            RuntimeHost.atomic(new File(runtime.root, "owner.json"), owner.toString());
            baseline = previous; lastHeartbeat = android.os.SystemClock.elapsedRealtime();
            run("/system/bin/settings", "put", "global", "http_proxy", "127.0.0.1:18886");
        } catch (Exception e) { throw new IllegalStateException("PROXY_SET_FAILED", e); }
    }
    @Override public synchronized void heartbeat() {
        caller(); lastHeartbeat = android.os.SystemClock.elapsedRealtime();
        leaseRecoveryAttempts=0;
        try { runtime.beat(); } catch (Exception e) { throw new IllegalStateException("HEARTBEAT_FAILED", e); }
    }
    @Override public synchronized void restoreProxy(String previous) {
        caller(); if (!direct(previous)) throw new IllegalArgumentException("INVALID_RESTORE_BASELINE");
        if (baseline == null) baseline = previous;
        try { restoreOwned(); } catch (Exception e) { throw new IllegalStateException("RESTORE_FAILED", e); }
    }
    @Override public synchronized String startRuntime(int port, String token, String scope, String pem, boolean isolated) {
        caller();
        try {
            restoreOwned(); lastHeartbeat = android.os.SystemClock.elapsedRealtime();
            String status = runtime.start(port, token, scope, pem, isolated);
            lastHeartbeat = android.os.SystemClock.elapsedRealtime(); runtime.beat(); return status;
        } catch (Exception e) { throw new IllegalStateException(e.getMessage(), e); }
    }
    @Override public synchronized String startVpnRuntime(int port, String token, String scope, String pem) {
        caller();
        try {
            restoreOwned(); lastHeartbeat = android.os.SystemClock.elapsedRealtime();
            String status = runtime.start(port, token, scope, pem, false, true);
            lastHeartbeat = android.os.SystemClock.elapsedRealtime(); runtime.beat(); return status;
        } catch (Exception e) { throw new IllegalStateException("VPN_ENGINE_START_FAILED: " + e.getClass().getSimpleName(), e); }
    }
    @Override public synchronized String startVpnTestRuntime(int port, String token, String scope, String pem) {
        caller();
        try {
            restoreOwned(); lastHeartbeat = android.os.SystemClock.elapsedRealtime();
            String status = runtime.start(port, token, scope, pem, true, true);
            lastHeartbeat = android.os.SystemClock.elapsedRealtime(); runtime.beat(); return status;
        } catch (Exception e) { throw new IllegalStateException("VPN_SELF_TEST_START_FAILED: " + e.getClass().getSimpleName(), e); }
    }
    @Override public synchronized String vpnConfig() {
        caller(); try { return runtime.vpnConfig(); } catch (Exception e) { throw new IllegalStateException("VPN_CONFIG_UNAVAILABLE", e); }
    }
    @Override public synchronized void stopRuntime() {
        caller(); try { ResourceCleanup.run(this::restoreOwned,runtime::stop); } catch (Exception e) { throw new IllegalStateException("STOP_FAILED", e); }
    }
    @Override public synchronized String runtimeStatus() {
        caller(); try { return runtime.status(); } catch (Exception e) { throw new IllegalStateException("STATUS_FAILED", e); }
    }
    @Override public synchronized String runtimeLog() { caller(); return runtime.log(); }
    @Override public synchronized String runtimeStorage() {
        caller();try{return runtime.storage();}catch(Exception e){throw new IllegalStateException("RUNTIME_STORAGE_UNAVAILABLE",e);}
    }
    @Override public synchronized String clearRuntimeHistory() {
        caller();if(baseline!=null)throw new IllegalStateException("PROXY_RECOVERY_REQUIRED");
        try{return runtime.clearHistory();}catch(Exception e){throw new IllegalStateException("RUNTIME_CLEANUP_FAILED",e);}
    }
    @Override public synchronized void destroy() {
        caller(); try { ResourceCleanup.run(this::restoreOwned,runtime::stop); } catch (Exception ignored) { /* Python lease also attempts restoration. */ }
        System.exit(0);
    }
}
