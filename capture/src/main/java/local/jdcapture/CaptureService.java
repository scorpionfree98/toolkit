package local.jdcapture;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import local.jdcapture.core.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import org.json.JSONObject;
import java.util.concurrent.*;

public final class CaptureService extends Service {
    public static final int NOTIFICATION_ID = 2101;
    public static volatile CaptureService current;
    private static final ExecutorService OPERATIONS = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private volatile EmbeddedBridge bridge;
    private volatile VpnController vpn;
    private volatile String mode = "HTTP";
    private volatile boolean active, isolated, destroyed;
    private volatile long failureGeneration=-1;
    private volatile Scope scope = Scope.TUNNEL;
    private volatile IProxyControl control;
    CaptureStore store;
    CertificateAuthority authority;
    private SharedPreferences ownership;
    private SharedPreferences recovery;
    private final CaptureLifecycle lifecycle = new CaptureLifecycle();
    private boolean backendPending;
    private int operationStartId;
    private void state(CaptureLifecycle.State value) {
        lifecycle.set(value);
        if(!recovery.edit().putString("state",value.name()).commit())
            store.event("STATE_WRITE_FAILED","","Recovery state persistence failed");
    }
    @Override public void onCreate() {
        super.onCreate(); current = this; store = new CaptureStore(this); ownership = getSharedPreferences("proxy_ownership", MODE_PRIVATE);
        recovery=getSharedPreferences("capture_recovery",MODE_PRIVATE);
        if(!recovery.getString("state","STOPPED").equals("STOPPED")) { lifecycle.set(CaptureLifecycle.State.RECOVERY_REQUIRED); backendPending=true; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("proxy", "抓包运行状态", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "proxy").setContentTitle("入口抓包台")
            .setContentText("本机代理服务 · 点击查看状态").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "停止并恢复网络", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); else startForeground(NOTIFICATION_ID, n);
        executor.scheduleAtFixedRate(() -> {
            long generation=lifecycle.generation();
            try {
                if (active && lifecycle.state()==CaptureLifecycle.State.RUNNING && control != null) {
                    if (vpn != null && !vpn.running()) throw new IllegalStateException("VPN_REVOKED_OR_STOPPED");
                    control.heartbeat();
                    if (!new JSONObject(control.runtimeStatus()).getBoolean("alive")) throw new IllegalStateException("ENGINE_EXITED");
                }
            } catch (Exception e) {
                if (!destroyed && failureGeneration!=generation) { failureGeneration = generation; OPERATIONS.execute(() -> {
                    if(lifecycle.matches(generation)&&lifecycle.state()==CaptureLifecycle.State.RUNNING)failRuntime(e);
                }); }
            }
        }, 2, 3, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("STOP".equals(intent.getAction())) OPERATIONS.execute(()->{operationStartId=startId;stopOwned();});
        else if ("CLEAR_LOGS".equals(intent.getAction())) OPERATIONS.execute(() -> clearLogs(startId));
        else OPERATIONS.execute(() -> {operationStartId=startId;startProxy(intent.getStringExtra("scope"), "SELFTEST".equals(intent.getAction()), intent.getStringExtra("mode"), intent.getStringArrayListExtra("apps"));});
        return START_NOT_STICKY;
    }
    private void clearLogs(int startId) {
        if (active || bridge != null || vpn != null || ownership.getBoolean("owned", false)) {
            store.status = "请先停止抓包并完成网络恢复，再清理日志"; return;
        }
        String result;
        try { store.clearLogs(); result = "抓包日志已清理，证书和工具配置保留"; }
        catch (Exception error) { result = "抓包日志清理未完成，请重试"; }
        final String message = result;
        new Handler(Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(getApplicationContext(), message, android.widget.Toast.LENGTH_LONG).show());
        if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE);
    }
    private void startProxy(String scopeName, boolean test, String requestedMode, java.util.List<String> apps) {
        if (destroyed) return;
        boolean pending=lifecycle.state()==CaptureLifecycle.State.RECOVERY_REQUIRED;
        lifecycle.begin(CaptureLifecycle.State.STARTING); state(CaptureLifecycle.State.STARTING);
        try {
            if (!CaptureSupport.supported(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS))
                throw new IllegalStateException("CAPTURE_REQUIRES_ANDROID_10_ARM64");
            if (!test && requestedMode != null && !requestedMode.equals("HTTP") && !requestedMode.equals("VPN")) throw new IllegalArgumentException("INVALID_MODE");
            boolean wantsVpn = "VPN".equals(requestedMode);
            if (wantsVpn) {
                if (test && (apps == null || apps.size() != 1 || !apps.get(0).equals("local.jdcapture.probe") ||
                    getPackageManager().checkSignatures(getPackageName(), "local.jdcapture.probe") != android.content.pm.PackageManager.SIGNATURE_MATCH))
                    throw new IllegalArgumentException("INSTALL_SIGNED_VPN_PROBE_FIRST");
                VpnController.validateApps(this, apps);
                if (android.net.VpnService.prepare(this) != null) throw new IllegalStateException("VPN_PERMISSION_REQUIRED");
            }
            control = PrivilegeClient.await(this);
            if (active || bridge != null || pending) shutdownBackend();
            if (ownership.getBoolean("owned", false)) restore();
            mode = wantsVpn ? "VPN" : "HTTP";
            if ((!test || wantsVpn) && VpnController.hasVpn(this)) throw new IllegalStateException("EXISTING_VPN_STOP_IT_FIRST");
            scope = Scope.valueOf(scopeName == null ? "TUNNEL" : scopeName); isolated = test;
            String previous = control.readProxy(), pac = control.readPac();
            if (!test || wantsVpn) {
                if (!(pac.isEmpty() || pac.equals("null"))) throw new IllegalStateException("EXISTING_PAC_PROXY");
                if (!(previous.isEmpty() || previous.equals("null") || previous.equals(":0"))) throw new IllegalStateException("EXISTING_PROXY_RESTORE_FIRST");
            }
            store.beginStage(); store.status = "正在启动内置 mitmproxy，首次解包可能需要一些时间…";
            File caDir = new File(getFilesDir(), "ca"); authority = new CertificateAuthority(caDir);
            String pem = pem("PRIVATE KEY", authority.runtimePrivateKey()) + pem("CERTIFICATE", authority.publicCertificate());
            bridge = new EmbeddedBridge(store, test);
            backendPending=true;
            if (wantsVpn) {
                if (test) control.startVpnTestRuntime(bridge.port(), bridge.token, scope.name(), pem);
                else control.startVpnRuntime(bridge.port(), bridge.token, scope.name(), pem);
                vpn = new VpnController(this); vpn.start(control.vpnConfig(), apps);
            } else control.startRuntime(bridge.port(), bridge.token, scope.name(), pem, test);
            if (!test && !wantsVpn) {
                if (!ownership.edit().putBoolean("owned", true).putString("previous", previous).commit()) throw new IllegalStateException("OWNERSHIP_WRITE_FAILED");
                control.setLocalProxy(previous);
                if (!control.readProxy().equals("127.0.0.1:18886")) throw new IllegalStateException("PROXY_READBACK_FAILED");
            }
            active = true;
            state(CaptureLifecycle.State.RUNNING);
            store.event("ENGINE_STARTED", "", test ? mode + " 隔离自检运行中，系统代理未更改" : mode + " · mitmproxy · " + scope);
        } catch (Exception e) { failRuntime(e); }
    }
    private static String pem(String label, byte[] value) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(value) + "\n-----END " + label + "-----\n";
    }
    private void shutdownBackend() throws Exception {
        active = false;
        ResourceCleanup.run(() -> store.disableRules(), () -> {
          if (vpn != null) {
            vpn.stop(); vpn = null;
            // Android removes the VPN network asynchronously. Do not mistake our closing
            // network for an unrelated VPN during a serialized mode switch.
            long until = android.os.SystemClock.elapsedRealtime() + 2000;
            while (VpnController.hasVpn(this) && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100);
          }
        }, this::restore, () -> {
            if (backendPending && (control==null||!control.asBinder().isBinderAlive()))control=PrivilegeClient.await(this);
            if (control != null) { if(!control.asBinder().isBinderAlive())control=PrivilegeClient.await(this); control.stopRuntime(); backendPending=false; }
        }, () -> {
            if (bridge != null) { bridge.close(); bridge = null; }
        });
    }
    private void failRuntime(Exception error) {
        if (destroyed) return;
        active = false; store.event("ENGINE_FAILED", "", reason(error));
        try { shutdownBackend(); state(CaptureLifecycle.State.STOPPED); store.status = "内核失败，已停止并恢复网络：" + reason(error); }
        catch (Exception recovery) { recoveryPending(recovery, lifecycle.generation()); }
    }
    private static String reason(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private void restore() throws Exception {
        if (!ownership.getBoolean("owned", false)) return;
        if (control == null || !control.asBinder().isBinderAlive()) control = PrivilegeClient.await(this);
        String currentProxy = control.readProxy();
        if (currentProxy.equals("127.0.0.1:18886")) control.restoreProxy(ownership.getString("previous", ":0"));
        else if (!(currentProxy.isEmpty() || currentProxy.equals("null") || currentProxy.equals(":0")))
            store.event("EXTERNAL_PROXY_PRESERVED", "", "Did not overwrite external settings");
        if (!ownership.edit().clear().commit()) throw new IllegalStateException("OWNERSHIP_CLEAR_FAILED");
    }
    private void stopOwned() {
        if(destroyed)return;
        lifecycle.begin(CaptureLifecycle.State.STOPPING);state(CaptureLifecycle.State.STOPPING);
        try {
            if(control==null)control=PrivilegeClient.await(this);
            shutdownBackend(); store.status = "代理已停止，网络设置已恢复";
            state(CaptureLifecycle.State.STOPPED);
            if(stopSelfResult(operationStartId))stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception e) { recoveryPending(e,lifecycle.generation()); }
    }
    private void recoveryPending(Exception error,long generation) {
        state(CaptureLifecycle.State.RECOVERY_REQUIRED);
        int delay=lifecycle.nextRecoveryDelay(generation);
        store.status="网络恢复待完成："+reason(error)+(delay<0?"；自动重试已停止，请恢复 Shizuku 后点击停止重试":"；"+delay+" 秒后重试清理");
        if(delay<0||destroyed)return;
        executor.schedule(()->OPERATIONS.execute(()->{
            if(destroyed||!lifecycle.matches(generation)||lifecycle.state()!=CaptureLifecycle.State.RECOVERY_REQUIRED)return;
            try {
                if(control==null||!control.asBinder().isBinderAlive())control=PrivilegeClient.await(this);
                shutdownBackend();state(CaptureLifecycle.State.STOPPED);
                store.status="清理完成，网络设置已恢复；任务不会自动重启";
                if(stopSelfResult(operationStartId))stopForeground(STOP_FOREGROUND_REMOVE);
            }catch(Exception e){recoveryPending(e,generation);}
        }),delay,TimeUnit.SECONDS);
    }
    boolean running() { return active; }
    String mode() { return mode; }
    String stage() {
        if(!active)return switch(lifecycle.state()) {
            case STARTING -> "正在启动"; case STOPPING -> "正在停止与恢复网络";
            case RECOVERY_REQUIRED -> "网络恢复待完成"; default -> "已停止";
        };
        return isolated ? "隔离自检 · " + mode + " · mitmproxy" : mode + " · " + scope.name() + " · mitmproxy";
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true; active = false;
        if (current == this) current = null;
        executor.shutdownNow();
        // Binder / settings calls can block; never perform recovery on the main thread.
        OPERATIONS.execute(() -> {
            try {
                // Lifecycle callbacks enqueue cleanup before a replacement service can enqueue start.
                shutdownBackend();
                state(CaptureLifecycle.State.STOPPED);
            }
            catch (Exception e) { state(CaptureLifecycle.State.RECOVERY_REQUIRED); if (store != null) store.event("RESTORE_PENDING", "", "Shizuku lease will attempt restoration; check system proxy"); }
            finally { if (bridge != null) { bridge.close(); bridge = null; } }
        });
        super.onDestroy();
    }
}
