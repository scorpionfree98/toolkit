package com.autojump.app.data;

import android.content.SharedPreferences;

/** Local-only recovery evidence. Never include the stored value in logs. */
public final class CorruptDataArchive {
    private CorruptDataArchive() {}
    public static boolean quarantine(SharedPreferences prefs, String key, String raw) {
        // A single atomic preferences transaction retains the exact bytes before resetting.
        // Keep the first unreadable value; never replace earlier recovery evidence.
        String backup = key + "_corrupt_backup";
        if (prefs.contains(backup)) return false;
        return prefs.edit().putString(backup, raw).putLong(backup + "_at", System.currentTimeMillis())
                .remove(key).commit();
    }
}
