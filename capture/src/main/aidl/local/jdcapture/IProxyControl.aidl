package local.jdcapture;
interface IProxyControl {
    String readProxy() = 0;
    String readPac() = 1;
    void setLocalProxy(String previous) = 2;
    void heartbeat() = 3;
    void restoreProxy(String previous) = 4;
    String startRuntime(int bridgePort, String token, String scope, String caPem, boolean isolated) = 5;
    void stopRuntime() = 6;
    String runtimeStatus() = 7;
    String runtimeLog() = 8;
    String startVpnRuntime(int bridgePort, String token, String scope, String caPem) = 9;
    String vpnConfig() = 10;
    String startVpnTestRuntime(int bridgePort, String token, String scope, String caPem) = 11;
    String runtimeStorage() = 12;
    String clearRuntimeHistory() = 13;
    void destroy() = 16777114;
}
