package local.jdcapture.core;

public enum Scope {
    TUNNEL, ENTRY, CHECKOUT;
    public boolean decrypts(String host) {
        return this == ENTRY ? host.equals("pro.m.jd.com") :
            this == CHECKOUT && (host.equals("m-sep.jd.com") || host.equals("api.m.jd.com"));
    }
}
