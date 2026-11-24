package com.autojump.app.update;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ReleaseInfoTest {
    private String valid(){return "{\"draft\":false,\"prerelease\":false,\"tag_name\":\"v0.3.17\",\"assets\":[{\"name\":\"toolkit-0.3.17.apk\",\"browser_download_url\":\"https://github.com/scorpionfree98/toolkit/releases/download/v0.3.17/toolkit-0.3.17.apk\",\"digest\":\"sha256:"+"a".repeat(64)+"\",\"size\":87234567,\"state\":\"uploaded\"}]}";}
    @Test public void stableReleaseUsesNumericBuildCode()throws Exception{ReleaseInfo r=ReleaseInfo.parse(valid());assertEquals(1017,r.code);assertEquals("0.3.17",r.version);assertEquals(87234567,r.size);assertEquals(r.url,r.downloadUrl(0));assertTrue(r.downloadUrl(1).startsWith("https://ghfast.top/https://github.com/"));}
    @Test public void rejectsDraftAndPrerelease(){for(String key:new String[]{"draft","prerelease"})assertThrows(IOException.class,()->ReleaseInfo.parse(valid().replace("\""+key+"\":false","\""+key+"\":true")));}
    @Test public void rejectsForeignRepositoryAndPlainHttp(){assertThrows(IOException.class,()->ReleaseInfo.parse(valid().replace("scorpionfree98/toolkit","attacker/toolkit")));assertThrows(IOException.class,()->ReleaseInfo.parse(valid().replace("https://","http://")));}
    @Test public void rejectsMissingOrInvalidDigest(){assertThrows(IOException.class,()->ReleaseInfo.parse(valid().replace("sha256:","sha512:")));assertThrows(IOException.class,()->ReleaseInfo.parse(valid().replace("\"digest\"","\"unknown\"")));}
    @Test public void rejectsMissingApkOrWrongAssetName(){assertThrows(IOException.class,()->ReleaseInfo.parse(valid().replace("toolkit-0.3.17.apk","debug.apk")));}
    @Test public void rejectsSizeAndUnuploadedAsset(){for(String size:new String[]{"0","-1","268435457"})assertThrows(IOException.class,()->ReleaseInfo.parse(valid().replace("87234567",size)));assertThrows(IOException.class,()->ReleaseInfo.parse(valid().replace("uploaded","new")));}
    @Test public void rejectsUnknownVersionScheme(){assertThrows(IOException.class,()->ReleaseInfo.parse(valid().replace("0.3.17","0.4.1")));}
    @Test public void rejectsMalformedJson(){assertThrows(IOException.class,()->ReleaseInfo.parse("{}"));assertThrows(IOException.class,()->ReleaseInfo.parse("not json"));}
}
