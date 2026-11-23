package local.jdcapture;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import java.util.*;

/** Uses the official userspace backend; its VpnService owns TUN and protects the UDP sockets. */
final class VpnController {
    private final Context context;
    private final GoBackend backend;
    private volatile boolean up;
    private final Tunnel tunnel = new Tunnel() {
        public String getName() { return "CaptureLocal"; }
        public void onStateChange(State state) { up = state == State.UP; }
    };
    VpnController(Context context) { this.context = context; backend = new GoBackend(context.getApplicationContext()); }
    static boolean hasVpn(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        // Also covers per-app VPNs that are not this app's active default network.
        for (android.net.Network n : cm.getAllNetworks()) {
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }
    static void validateApps(Context context, Collection<String> apps) throws Exception {
        if (apps == null || apps.isEmpty()) throw new IllegalArgumentException("VPN_SELECT_APPLICATIONS");
        for (String pkg : apps) {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            if (info.uid < 10000 || info.uid == context.getApplicationInfo().uid || pkg.equals("moe.shizuku.privileged.api"))
                throw new IllegalArgumentException("VPN_APPLICATION_NOT_ALLOWED");
        }
    }
    void start(String material, Collection<String> apps) throws Exception {
        if (VpnService.prepare(context) != null) throw new IllegalStateException("VPN_PERMISSION_REQUIRED");
        validateApps(context, apps);
        Config config = VpnProfile.build(material, apps, context.getPackageName());
        backend.setState(tunnel, Tunnel.State.UP, config);
        if (!up) throw new IllegalStateException("VPN_START_FAILED");
    }
    boolean running() { return up; }
    void stop() throws Exception { backend.setState(tunnel, Tunnel.State.DOWN, null); up = false; }
}
