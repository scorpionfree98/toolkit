package cn.local.jiojio;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.junit.Test;
import static org.junit.Assert.*;

/** Structural guards, not proof of a device VPN/TLS session. */
public class CaptureIntegrationContractTest {
    private final File root = new File("src/main/AndroidManifest.xml").exists() ? new File("..") : new File(".");
    private String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(root, path).toPath()), StandardCharsets.UTF_8);
    }
    private org.w3c.dom.Document manifest(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new File(root, path));
    }
    @Test public void hostIdentitySdkAndCleartextPolicyRemainUnchanged() throws Exception {
        String build = source("app/build.gradle");
        assertTrue(build.contains("'cn.local.toolkit'")); assertTrue(build.contains("minSdk 26"));
        Element app = (Element) manifest("app/src/main/AndroidManifest.xml").getElementsByTagName("application").item(0);
        assertEquals("false", app.getAttributeNS("http://schemas.android.com/apk/res/android", "usesCleartextTraffic"));
        assertEquals("false", app.getAttributeNS("http://schemas.android.com/apk/res/android", "allowBackup"));
    }
    @Test public void captureHasNoSecondLauncherAndItsActivityIsPrivate() throws Exception {
        var doc = manifest("capture/src/main/AndroidManifest.xml");
        NodeList activities = doc.getElementsByTagName("activity");
        assertEquals(1, activities.getLength());
        Element activity = (Element) activities.item(0);
        assertEquals("local.jdcapture.MainActivity", activity.getAttributeNS("http://schemas.android.com/apk/res/android", "name"));
        assertEquals("false", activity.getAttributeNS("http://schemas.android.com/apk/res/android", "exported"));
        assertEquals(0, activity.getElementsByTagName("intent-filter").getLength());
    }
    @Test public void notificationCannotReplaceExistingMockLocationNotification() {
        assertEquals(2101, local.jdcapture.CaptureService.NOTIFICATION_ID);
        assertNotEquals(1, local.jdcapture.CaptureService.NOTIFICATION_ID);
    }
    @Test public void entryUsesOneCompatibilityGateAndPreservesLauncherMatching() throws Exception {
        assertTrue(source("app/src/main/java/cn/local/jiojio/ToolboxActivity.java").contains("CaptureAvailability.supported"));
        assertTrue(source("app/src/main/java/cn/local/jiojio/CaptureAvailability.java").contains("CaptureSupport.supported"));
        assertTrue(source("capture/src/main/java/local/jdcapture/MainActivity.java").contains("CaptureSupport.supported"));
        assertTrue(source("capture/src/main/java/local/jdcapture/CaptureService.java").contains("CaptureSupport.supported"));
        assertTrue(source("app/src/main/java/com/autojump/app/MainActivity.java").contains("enqueueMatching"));
    }
}
