package local.jdcapture.core;

import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;

/** Offline regression against private exports; prints only status and counts, never bodies or credentials. */
public final class CaptureReplay {
    public static void main(String[] paths) throws Exception {
        int entries=0;
        for(String path:paths) try(ZipFile zip=new ZipFile(path)) {
            Enumeration<? extends ZipEntry> files=zip.entries();
            while(files.hasMoreElements()) {
                ZipEntry f=files.nextElement();if(!f.getName().endsWith(".json"))continue;
                JSONObject flow=new JSONObject(new String(zip.getInputStream(f).readAllBytes(),StandardCharsets.UTF_8));
                if(!"entry".equals(flow.optString("stage")))continue;
                JSONArray original=flow.getJSONArray("responseHeaders");List<String[]> headers=new ArrayList<>();
                for(int i=0;i<original.length();i++){JSONArray h=original.getJSONArray(i);headers.add(new String[]{h.getString(0),h.getString(1)});}
                byte[] raw=Base64.getDecoder().decode(flow.getString("responseBodyBase64"));
                byte[] decoded=ResponseBody.decode(raw,headers);
                int observed=ResponseRules.value("entry",new String(decoded,StandardCharsets.UTF_8));
                entries++;
                System.out.println("ENTRY_DECODED at="+flow.getLong("observedAtMs")+" wireBytes="+raw.length+" decodedBytes="+decoded.length+" observedStatus="+observed);
            }
        }
        if(entries==0)throw new IllegalStateException("NO_ENTRY_FIXTURES");
        System.out.println("REPLAY_PASS entries="+entries);
    }
}
