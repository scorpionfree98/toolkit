package local.jdcapture;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

/** JVM fixture using the same bridge and CaptureStore as the APK, no Android UI. */
public final class VpnBridgeFixture {
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]);CaptureStore store=new CaptureStore(new File(root,"flows"));
        try(EmbeddedBridge bridge=new EmbeddedBridge(store,true)){
            JSONObject o=new JSONObject();o.put("port",bridge.port());o.put("token",bridge.token);
            Files.write(new File(root,"bridge-ready.json").toPath(),o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while(!new File(root,"stop-bridge").isFile())Thread.sleep(100);
        }
    }
}
