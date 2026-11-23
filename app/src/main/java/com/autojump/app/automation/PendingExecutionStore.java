package com.autojump.app.automation;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class PendingExecutionStore {
    private static final Object LOCK = new Object();
    private static final String SP_NAME = "automation_execution_sp";
    private static final String KEY_SESSIONS = "pending_sessions";
    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public PendingExecutionStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public List<ExecutionSession> getAll() {
        synchronized (LOCK) {
            String json = preferences.getString(KEY_SESSIONS, "");
            if (json == null || json.isEmpty()) return new ArrayList<>();
            try { return decode(json); }
            catch (RuntimeException error) {
                com.autojump.app.data.CorruptDataArchive.quarantine(preferences, KEY_SESSIONS, json);
                android.util.Log.w("ToolkitQueue", "Unreadable queue isolated; no actions replayed");
                return new ArrayList<>();
            }
        }
    }

    static List<ExecutionSession> decode(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        if (!json.trim().startsWith("[")) throw new IllegalArgumentException("INVALID_QUEUE");
        Type type = new TypeToken<List<ExecutionSession>>() {}.getType();
        List<ExecutionSession> result = new Gson().fromJson(json, type);
        if (result == null || result.size() > QueuePolicy.CAPACITY) throw new IllegalArgumentException("INVALID_QUEUE");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ExecutionSession s : result) {
            if (s == null || s.getId() == null || s.getRuleId() == null || s.getStatus() == null
                    || s.getActions() == null || s.getActions().contains(null) || !ids.add(s.getId()))
                throw new IllegalArgumentException("INVALID_QUEUE_ENTRY");
        }
        return result;
    }

    private void requireReadable() {
        decode(preferences.getString(KEY_SESSIONS, ""));
    }

    public void upsert(ExecutionSession session) {
        synchronized (LOCK) {
            List<ExecutionSession> sessions = getAll();
            for (int i = 0; i < sessions.size(); i++) {
                if (sessions.get(i).getId().equals(session.getId())) {
                    sessions.set(i, session);
                    save(sessions);
                    return;
                }
            }
            sessions.add(session);
            save(sessions);
        }
    }

    public ExecutionSession findById(String sessionId) {
        if (sessionId == null) return null;
        for (ExecutionSession session : getAll()) {
            if (sessionId.equals(session.getId())) return session;
        }
        return null;
    }

    public void remove(String sessionId) {
        synchronized (LOCK) {
            List<ExecutionSession> sessions = getAll();
            for (int i = sessions.size() - 1; i >= 0; i--) {
                if (sessions.get(i).getId().equals(sessionId)) sessions.remove(i);
            }
            save(sessions);
        }
    }

    public void clear() {
        synchronized (LOCK) { requireReadable(); preferences.edit().remove(KEY_SESSIONS).apply(); }
    }

    private void save(List<ExecutionSession> sessions) {
        requireReadable();
        if (!preferences.edit().putString(KEY_SESSIONS, gson.toJson(sessions)).commit())
            throw new IllegalStateException("任务队列保存失败，本次不执行");
    }
    public long nextSequence() {
        synchronized (LOCK) {
            long next = preferences.getLong("queue_sequence", 0L) + 1;
            requireReadable();
            if (!preferences.edit().putLong("queue_sequence", next).commit())
                throw new IllegalStateException("任务序号保存失败，本次不执行");
            return next;
        }
    }
}
