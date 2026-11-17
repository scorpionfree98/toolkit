package com.autojump.app.automation;

import org.junit.Test;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import static org.junit.Assert.*;

/** Structural guard; actual launcher warm-start behavior still needs device acceptance. */
public class LauncherTaskIsolationTest {
    @Test public void ordinaryLauncherNeverRequiresDefaultInstruction() throws Exception {
        File source = new File("src/main/java/com/autojump/app/MainActivity.java");
        if (!source.exists()) source = new File("app/src/main/java/com/autojump/app/MainActivity.java");
        String code = new String(java.nio.file.Files.readAllBytes(source.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(code.contains("defaultId()"));
        assertTrue(code.contains("LauncherRuleSelection.select"));
        assertTrue(code.contains("enqueueMatching(this, selection.matches"));
        assertTrue(code.contains("intent.getStringExtra(\"rule_id\")"));
    }

    @Test public void failureNeverAutomaticallyLaunchesPermissionOrManagementPage() throws Exception {
        File source = new File("src/main/java/com/autojump/app/MainActivity.java");
        if (!source.exists()) source = new File("app/src/main/java/com/autojump/app/MainActivity.java");
        String code = new String(java.nio.file.Files.readAllBytes(source.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        // Structural regression guard; the dialog itself still needs Android acceptance.
        assertFalse(code.contains("if (!explicit) openManagement()"));
        assertFalse(code.contains("new Intent(app, PermissionGuideActivity.class)"));
        assertTrue(code.contains("page.showLaunchFailure(message, PermissionGuideActivity.class)"));
        assertTrue(code.contains("setNegativeButton(\"关闭\""));
    }

    @Test public void launcherCannotResumeManagementTask() throws Exception {
        File manifest = new File("src/main/AndroidManifest.xml");
        if (!manifest.exists()) manifest = new File("app/src/main/AndroidManifest.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var activities = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("activity");
        String ns = "http://schemas.android.com/apk/res/android";
        boolean found = false;
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            if (".MainActivity".equals(activity.getAttributeNS(ns, "name"))) {
                found = true;
                assertEquals("${applicationId}.automation", activity.getAttributeNS(ns, "taskAffinity"));
                assertEquals("true", activity.getAttributeNS(ns, "noHistory"));
                assertEquals("true", activity.getAttributeNS(ns, "excludeFromRecents"));
            }
        }
        assertTrue(found);
    }
}
