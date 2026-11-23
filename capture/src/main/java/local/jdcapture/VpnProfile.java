package local.jdcapture;

import com.wireguard.config.Config;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;
import org.json.JSONObject;
import java.util.*;

/** Builds a local-only, app-scoped profile. Never store or log the resulting config. */
final class VpnProfile {
    static Config build(String material, Collection<String> applications, String ownPackage) throws Exception {
        if (applications == null || applications.isEmpty()) throw new IllegalArgumentException("VPN_SELECT_APPLICATIONS");
        if (applications.size() > 64) throw new IllegalArgumentException("VPN_TOO_MANY_APPLICATIONS");
        Set<String> selected = new LinkedHashSet<>();
        for (String pkg : applications) {
            if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") ||
                pkg.equals(ownPackage) || pkg.equals("com.android.shell") || pkg.equals("moe.shizuku.privileged.api"))
                throw new IllegalArgumentException("VPN_APPLICATION_NOT_ALLOWED");
            selected.add(pkg);
        }
        JSONObject data = new JSONObject(material);
        int port = data.getInt("port");
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("VPN_INVALID_ENDPOINT");
        Interface device = new Interface.Builder().parsePrivateKey(data.getString("clientPrivateKey"))
            // QUIC clients may start with a 1280-byte UDP payload (+28 IPv4/UDP).
            // The outer WireGuard endpoint is loopback, not the mobile uplink.
            .parseAddresses("10.0.0.1/32").parseDnsServers("10.0.0.53").setMtu(1420)
            .includeApplications(selected).build();
        Peer peer = new Peer.Builder().parsePublicKey(data.getString("serverPublicKey"))
            .parseEndpoint("127.0.0.1:" + port).parseAllowedIPs("0.0.0.0/0").setPersistentKeepalive(15).build();
        // A single default-route peer makes GoBackend block other address families.
        // IPv6 is intentionally blocked for selected apps until upstream forwarding is verified.
        return new Config.Builder().setInterface(device).addPeer(peer).build();
    }
}
