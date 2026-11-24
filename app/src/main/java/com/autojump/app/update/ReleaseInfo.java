package com.autojump.app.update;

import com.google.gson.*;
import java.io.IOException;

/** Only the official stable release and its exact named asset can select an update. */
public final class ReleaseInfo {
    public static final String REPO = "https://github.com/scorpionfree98/toolkit";
    public final String version, url, sha256;
    public final long code, size;
    private ReleaseInfo(String version, long code, String url, String sha256, long size) {
        this.version=version; this.code=code; this.url=url; this.sha256=sha256; this.size=size;
    }
    public static ReleaseInfo parse(String json) throws IOException {
        try {
            JsonObject r=JsonParser.parseString(json).getAsJsonObject();
            if(r.get("draft").getAsBoolean() || r.get("prerelease").getAsBoolean()) throw new IllegalArgumentException();
            String tag=r.get("tag_name").getAsString();
            if(!tag.matches("v0\\.3\\.[1-9][0-9]{0,6}")) throw new IllegalArgumentException();
            String version=tag.substring(1), name="toolkit-"+version+".apk";
            long code=1000+Long.parseLong(version.substring(4));
            ReleaseInfo found=null;
            for(JsonElement element:r.getAsJsonArray("assets")) {
                JsonObject a=element.getAsJsonObject();
                if(!name.equals(a.get("name").getAsString())) continue;
                String url=a.get("browser_download_url").getAsString(), digest=a.get("digest").getAsString();
                long size=a.get("size").getAsLong();
                if(found!=null || !url.equals(REPO+"/releases/download/"+tag+"/"+name)
                    || !digest.matches("sha256:[a-f0-9]{64}") || size<1 || size>256L*1024*1024
                    || !"uploaded".equals(a.get("state").getAsString())) throw new IllegalArgumentException();
                found=new ReleaseInfo(version,code,url,digest.substring(7),size);
            }
            if(found==null) throw new IllegalArgumentException();
            return found;
        } catch(RuntimeException e) { throw new IOException("正式发布信息不完整或格式不受支持，请稍后重试",e); }
    }
    public String downloadUrl(int source) {
        if(source==1) return "https://ghfast.top/"+url;
        if(source==2) return "https://gh-proxy.org/"+url;
        return url;
    }
}
