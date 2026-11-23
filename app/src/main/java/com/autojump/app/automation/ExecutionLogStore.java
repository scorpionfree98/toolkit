package com.autojump.app.automation;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ExecutionLogStore {
    public static class Entry {
        private long timestamp;
        private String sessionId;
        private String ruleId;
        public String getRuleId() { return ruleId; }
        private String ruleName;
        private String status;
        private String message;

        Entry(long timestamp, String sessionId, String ruleName, String status, String message) {
            this.timestamp = timestamp;
            this.sessionId = sessionId;
            this.ruleName = ruleName;
            this.status = status;
            this.message = message;
        }

        public long getTimestamp() { return timestamp; }
        public String getSessionId() { return sessionId; }
        public String getRuleName() { return ruleName; }
        public String getStatus() { return status; }
        public String getMessage() { return message; }
    }

    private static final Object LOCK = new Object();
    private static final int MAX_ENTRIES = 100;
    private static final String SP_NAME = "automation_execution_sp";
    private static final String KEY_LOGS = "execution_logs";
    private final SharedPreferences preferences;
    private final Gson gson = new Gson();
    private boolean readable = true;

    public ExecutionLogStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public void append(ExecutionSession session, String status, String message) {
        synchronized (LOCK) {
            List<Entry> entries = getAll();
            if (!readable) return;
            Entry entry = new Entry(System.currentTimeMillis(), session.getId(),
                    session.getRuleName(), status, message);
            entry.ruleId = session.getRuleId();
            entries.add(0, entry);
            if (entries.size() > MAX_ENTRIES) entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
            preferences.edit().putString(KEY_LOGS, gson.toJson(entries)).apply();
        }
    }

    /** Local diagnostics contain state only, never rule actions, URLs or credentials. */
    public void appendDiagnostic(String status, String message) {
        synchronized (LOCK) {
            try {
                List<Entry> entries = getAll();
                if (!readable) return;
                entries.add(0, new Entry(System.currentTimeMillis(), "diagnostic",
                        "系统诊断", status, message));
                if (entries.size() > MAX_ENTRIES) entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
                // Persist lifecycle evidence before HyperOS can terminate the process.
                if (!preferences.edit().putString(KEY_LOGS, gson.toJson(entries)).commit()) {
                    android.util.Log.w("ToolkitDiagnostics", "Diagnostic persistence failed");
                }
            } catch (RuntimeException error) {
                // Diagnostics must never crash the launcher/service or overwrite unreadable logs.
                android.util.Log.w("ToolkitDiagnostics", "Diagnostic unavailable: "
                        + error.getClass().getSimpleName());
            }
        }
    }

    public List<Entry> getAll() {
        synchronized (LOCK) {
            readable = true;
            String json = preferences.getString(KEY_LOGS, "");
            if (json == null || json.isEmpty()) return new ArrayList<>();
            Type type = new TypeToken<List<Entry>>() {}.getType();
            try {
                List<Entry> entries = gson.fromJson(json, type);
                if (entries == null || entries.contains(null)) throw new IllegalArgumentException("INVALID_LOG");
                return entries;
            } catch (RuntimeException error) {
                readable = com.autojump.app.data.CorruptDataArchive.quarantine(preferences, KEY_LOGS, json);
                return new ArrayList<>();
            }
        }
    }

    public void clear() { preferences.edit().remove(KEY_LOGS).apply(); }
}
