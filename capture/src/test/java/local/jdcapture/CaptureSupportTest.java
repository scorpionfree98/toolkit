package local.jdcapture;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureSupportTest {
    @Test public void requiresBothSupportedOsAndExecutableAbi() {
        assertTrue(CaptureSupport.supported(29, new String[]{"arm64-v8a"}));
        assertTrue(CaptureSupport.supported(35, new String[]{"arm64-v8a", "armeabi-v7a"}));
        assertFalse(CaptureSupport.supported(28, new String[]{"arm64-v8a"}));
        assertFalse(CaptureSupport.supported(26, new String[]{"arm64-v8a"}));
        assertFalse(CaptureSupport.supported(35, new String[]{"x86_64"}));
        assertFalse(CaptureSupport.supported(35, new String[]{"armeabi-v7a"}));
    }
    @Test public void malformedOrMissingAbisFailClosed() {
        assertFalse(CaptureSupport.supported(35, null));
        assertFalse(CaptureSupport.supported(35, new String[0]));
        assertFalse(CaptureSupport.supported(35, new String[]{null, "", "ARM64"}));
    }
}
