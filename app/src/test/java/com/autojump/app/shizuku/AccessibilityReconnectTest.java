package com.autojump.app.shizuku;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccessibilityReconnectTest {
    private static final String OWN = "toolkit/Service";
    private static class Fake implements AccessibilityReconnect.Settings {
        String value;
        String duringWait;
        boolean failWait;
        boolean failDisable;
        boolean ignoreRestore;
        int frameworkWrites;
        final List<String> writes = new ArrayList<>();
        Fake(String value) { this.value = value; }
        public String services() { return value; }
        public void services(String value) throws Exception {
            writes.add(value);
            if (failDisable && !value.contains(OWN)) throw new Exception("disable failed");
            if (!ignoreRestore || !value.contains(OWN)) this.value = value;
        }
        public void enableFramework() { frameworkWrites++; }
        public void awaitUnbind() throws Exception {
            if (duringWait != null) value = duringWait;
            if (failWait) throw new Exception("wait failed");
        }
    }
    @Test public void enabledButDisconnectedServiceIsActuallyToggled() throws Exception {
        Fake fake = new Fake("other/Service:" + OWN);
        assertEquals("RECONNECTED", AccessibilityReconnect.restore(fake, OWN));
        assertEquals("other/Service", fake.writes.get(0));
        assertEquals("other/Service:" + OWN, fake.writes.get(1));
        assertEquals(1, fake.frameworkWrites);
    }
    @Test public void disabledServiceIsAddedWithoutTogglingOthers() throws Exception {
        Fake fake = new Fake("other/Service");
        assertEquals("RESTORED", AccessibilityReconnect.restore(fake, OWN));
        assertEquals(1, fake.writes.size());
        assertEquals("other/Service:" + OWN, fake.value);
    }
    @Test public void preservesChangesMadeByUserDuringReconnect() throws Exception {
        Fake fake = new Fake("other/Service:" + OWN);
        fake.duringWait = "new/Service";
        AccessibilityReconnect.restore(fake, OWN);
        assertEquals("new/Service:" + OWN, fake.value);
    }
    @Test public void failedWaitStillRestoresOwnEnabledSetting() throws Exception {
        Fake fake = new Fake(OWN);
        fake.failWait = true;
        try {
            AccessibilityReconnect.restore(fake, OWN);
            fail("must report failed wait");
        } catch (Exception expected) { assertEquals("wait failed", expected.getMessage()); }
        assertEquals(OWN, fake.value);
    }
    @Test public void noEnabledServicesDoesNotPublishLiteralNull() throws Exception {
        Fake fake = new Fake("null");
        AccessibilityReconnect.restore(fake, OWN);
        assertEquals(OWN, fake.value);
    }
    @Test public void failedDisableStillAttemptsToRestoreOwnSetting() throws Exception {
        Fake fake = new Fake("other/Service:" + OWN);
        fake.failDisable = true;
        try {
            AccessibilityReconnect.restore(fake, OWN);
            fail("must report failed disable");
        } catch (Exception expected) { assertEquals("disable failed", expected.getMessage()); }
        assertEquals("other/Service:" + OWN, fake.value);
        assertEquals(2, fake.writes.size());
    }
    @Test public void ignoredSettingsWriteCannotReportRestored() throws Exception {
        Fake fake = new Fake("other/Service");
        fake.ignoreRestore = true;
        try {
            AccessibilityReconnect.restore(fake, OWN);
            fail("must verify settings readback");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("回读未确认"));
        }
    }
}
