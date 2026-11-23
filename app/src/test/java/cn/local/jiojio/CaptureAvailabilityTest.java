package cn.local.jiojio;
import org.junit.Test;
import static org.junit.Assert.*;
public class CaptureAvailabilityTest {
    @Test public void acceptsSupportedDevices(){
        assertTrue(CaptureAvailability.supported(29,new String[]{"arm64-v8a","armeabi-v7a"}));
        assertTrue(CaptureAvailability.supported(35,new String[]{"arm64-v8a"}));
    }
    @Test public void keepsUnsupportedDevicesOutOfNativeRuntime(){
        assertFalse(CaptureAvailability.supported(28,new String[]{"arm64-v8a"}));
        assertFalse(CaptureAvailability.supported(35,new String[]{"x86_64"}));
        assertFalse(CaptureAvailability.supported(35,new String[]{"armeabi-v7a"}));
        assertFalse(CaptureAvailability.supported(35,null));
        assertFalse(CaptureAvailability.supported(35,new String[0]));
    }
}
