package local.jdcapture;

import org.junit.Test;
import org.json.JSONObject;
import com.wireguard.config.Config;
import com.wireguard.config.Peer;
import java.util.*;
import static org.junit.Assert.*;

public class VpnProfileTest {
    private String material(int port) throws Exception {
        JSONObject o=new JSONObject(); String key=Base64.getEncoder().encodeToString(new byte[32]);
        o.put("port",port);o.put("clientPrivateKey",key);o.put("serverPublicKey",key);
        // A caller-supplied remote endpoint must never be used.
        o.put("endpoint","203.0.113.1:1234");return o.toString();
    }
    @Test public void profileRoutesOnlyExplicitApplicationsToLocalEndpoint() throws Exception {
        Config config=VpnProfile.build(material(51820),Arrays.asList("example.testapp","example.testapp"),"local.jdcapture");
        assertEquals(Collections.singleton("example.testapp"),config.getInterface().getIncludedApplications());
        assertTrue(config.getInterface().getExcludedApplications().isEmpty());
        assertEquals(1,config.getPeers().size());Peer peer=config.getPeers().get(0);
        assertEquals("127.0.0.1:51820",peer.getEndpoint().get().toString());
        assertEquals("0.0.0.0/0",peer.getAllowedIps().iterator().next().toString());
        assertEquals(1,peer.getAllowedIps().size());assertTrue(config.getInterface().getMtu().get() >= 1280 + 28);
        assertEquals("10.0.0.53",config.getInterface().getDnsServers().iterator().next().getHostAddress());
    }
    @Test public void rejectsEmptyScopeAndLoopingApplications() throws Exception {
        for(List<String> list:Arrays.asList(Collections.<String>emptyList(),Collections.singletonList("local.jdcapture"),Collections.singletonList("com.android.shell"),Collections.singletonList("moe.shizuku.privileged.api"),Collections.singletonList("bad\napp"))){
            try{VpnProfile.build(material(51820),list,"local.jdcapture");fail("Invalid scope accepted");}
            catch(IllegalArgumentException expected){}
        }
    }
    @Test public void rejectsInvalidPortAndKeys() throws Exception {
        for(int port:new int[]{0,1023,65536}){
            try{VpnProfile.build(material(port),Collections.singletonList("example.app"),"local.jdcapture");fail("Invalid port accepted");}
            catch(IllegalArgumentException expected){}
        }
        JSONObject o=new JSONObject(material(51820));o.put("clientPrivateKey","invalid");
        try{VpnProfile.build(o.toString(),Collections.singletonList("example.app"),"local.jdcapture");fail("Invalid key accepted");}
        catch(com.wireguard.config.BadConfigException expected){}
    }
}
