package cn.local.jiojio;

/** The embedded Linux runtime is built for ARM64 and Android 10+. */
public final class CaptureAvailability {
    private CaptureAvailability() {}
    public static boolean supported(int sdk, String[] abis) {
        return local.jdcapture.CaptureSupport.supported(sdk, abis);
    }
}
