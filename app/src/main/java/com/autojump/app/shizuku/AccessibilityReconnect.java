package com.autojump.app.shizuku;

import java.util.Set;

/** Rebind only our service; reread the list so unrelated service changes survive. */
public final class AccessibilityReconnect {
    public interface Settings {
        String services() throws Exception;
        void services(String value) throws Exception;
        void enableFramework() throws Exception;
        void awaitUnbind() throws Exception;
    }

    private AccessibilityReconnect() {}

    public static String restore(Settings settings, String component) throws Exception {
        Set<String> before = AccessibilityServiceListPolicy.parse(settings.services());
        boolean enabled = before.remove(component);
        if (enabled) {
            try {
                settings.services(AccessibilityServiceListPolicy.join(before));
                settings.awaitUnbind();
            } finally {
                // Even a failed wait must not leave the user's service disabled.
                enable(settings, component);
            }
        } else {
            enable(settings, component);
        }
        if (!AccessibilityServiceListPolicy.contains(settings.services(), component)) {
            throw new IllegalStateException("系统设置回读未确认恢复结果");
        }
        return enabled ? "RECONNECTED" : "RESTORED";
    }

    private static void enable(Settings settings, String component) throws Exception {
        Set<String> latest = AccessibilityServiceListPolicy.parse(settings.services());
        latest.add(component);
        settings.services(AccessibilityServiceListPolicy.join(latest));
        settings.enableFramework();
    }
}
