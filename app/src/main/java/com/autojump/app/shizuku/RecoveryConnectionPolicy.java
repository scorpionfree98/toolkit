package com.autojump.app.shizuku;

/** A settings write or stale service reference is not evidence of reconnection. */
public final class RecoveryConnectionPolicy {
    private RecoveryConnectionPolicy() {}
    public static boolean confirmed(boolean enabled, boolean connected,
                                    long previousGeneration, long currentGeneration) {
        return enabled && connected && currentGeneration > previousGeneration;
    }
}
