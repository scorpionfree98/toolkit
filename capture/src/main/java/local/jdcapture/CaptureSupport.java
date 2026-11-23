package local.jdcapture;

/** Pure entry guard: the embedded executable requires Android 10 and ARM64. */
public final class CaptureSupport {
    private CaptureSupport() {}
    public static boolean supported(int sdk, String[] abis) {
        if (sdk < 29 || abis == null) return false;
        for (String abi : abis) if ("arm64-v8a".equals(abi)) return true;
        return false;
    }
}
