package local.jdcapture;

import android.content.Context;

import local.jdcapture.core.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Private append-only flow evidence. Record persists before a modified response is returned. */
final class CaptureStore implements ProxyEngine.Listener {
    private static final Object EXPORT_LOCK = new Object();
    final File directory;
    volatile String latestEntry, latestCheckout;
    volatile String status = "尚未启动";
    volatile String lastConnectionEvent = "";
    volatile String selfTestResult = "";
    volatile int count;
    private final Map<String, int[]> rules = new HashMap<>();
    private volatile boolean rulesEnabled;
    CaptureStore(Context context) { this(new File(context.getFilesDir(), "captures")); }
    CaptureStore(File directory) { this.directory = directory; directory.mkdirs(); }
    synchronized void arm(String stage, int before, int after) throws Exception {
        String source = stage.equals("entry") ? latestEntry : latestCheckout;
        if (source == null) throw new IllegalStateException("NO_CURRENT_RESPONSE");
        ResponseRules.preview(stage, source, before, after); rules.put(stage, new int[]{before, after}); rulesEnabled = true;
    }
    synchronized void beginStage() { rules.clear(); rulesEnabled = false; latestEntry = null; latestCheckout = null; selfTestResult = ""; }
    boolean rulesActive() { return rulesEnabled; }
    synchronized void disableRules() { rules.clear(); rulesEnabled = false; }
    @Override public synchronized void event(String type, String host, String detail) {
        if (type.equals("SELF_TEST_PASS")) selfTestResult = "自检通过：HTTPS 捕获与返回修改成功";
        if (type.equals("SELF_TEST_FAILED")) selfTestResult = "自检失败：" + detail;
        String message = type + (host.isEmpty() ? "" : " · " + host) + " · " + detail;
        if (type.startsWith("TLS_") || type.startsWith("CONNECTION_") || type.equals("TUNNEL_IDLE") || type.equals("CLIENT_CLOSED"))
            lastConnectionEvent = message;
        else status = message;
        try {
            JSONObject o = new JSONObject(); o.put("atMs", System.currentTimeMillis()); o.put("type", type); o.put("host", host); o.put("detail", detail);
            File events = new File(directory, "events.jsonl");
            byte[] bytes = (o + "\n").getBytes(StandardCharsets.UTF_8);
            if (events.length() + bytes.length > CaptureLimits.EVENT_BYTES) {
                status = "事件日志已达容量上限，请先导出并清理；未删除现有记录"; return;
            }
            try (FileOutputStream out = new FileOutputStream(events, true)) {
                out.write(bytes);
            }
        } catch (Exception ignored) { status = "LOG_WRITE_FAILED"; }
    }
    private JSONArray headers(List<String[]> headers) {
        JSONArray values = new JSONArray(); for (String[] h : headers) values.put(new JSONArray(Arrays.asList(h))); return values;
    }
    private JSONObject record(ProxyEngine.Exchange x) throws Exception {
        JSONObject o = new JSONObject(); o.put("id", UUID.randomUUID().toString()); o.put("observedAtMs", x.atMs);
        o.put("url", x.url); o.put("method", x.method); o.put("requestHeaders", headers(x.requestHeaders));
        o.put("requestHttpVersion", x.requestHttpVersion); o.put("responseHttpVersion", x.responseHttpVersion);
        o.put("transportProtocol", x.transportProtocol);
        o.put("requestBodyBase64", Base64.getEncoder().encodeToString(x.requestBody));
        o.put("status", x.status); o.put("responseHeaders", headers(x.responseHeaders));
        o.put("responseBodyBase64", Base64.getEncoder().encodeToString(x.responseBody));
        o.put("source", "PHONE_TLS_HTTP"); o.put("finalRequestForwarded", !x.localOnly); return o;
    }
    private void save(JSONObject record) throws Exception {
        if (directory.getUsableSpace() < 32L * 1024 * 1024) throw new IOException("LOW_STORAGE");
        byte[] bytes = record.toString().getBytes(StandardCharsets.UTF_8);
        CaptureLimits.requireSpace(directory, bytes.length);
        File target = new File(directory, record.getString("id") + ".json"), tmp = new File(directory, record.getString("id") + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); out.getFD().sync(); }
        if (!tmp.renameTo(target)) throw new IOException("FLOW_COMMIT_FAILED");
        count++;
    }
    @Override public synchronized void finalCaptured(ProxyEngine.Exchange x) throws Exception {
        JSONObject o = record(x); o.put("kind", "FINAL_CAPTURE"); save(o);
        status = "已保存第 " + count + " 条 · 最终请求已在本机阻断";
    }
    @Override public synchronized byte[] response(ProxyEngine.Exchange x) throws Exception {
        JSONObject record = record(x); byte[] delivered = x.responseBody;
        String stage = ResponseRules.stage(x.url, new String(x.requestBody, StandardCharsets.UTF_8));
        record.put("stage", stage); record.put("modified", false);
        if (x.status == 200 && !stage.equals("other")) {
            try {
                byte[] decoded = x.decodedResponse != null ? x.decodedResponse : ResponseBody.decode(x.responseBody, x.responseHeaders);
                String text = new String(decoded, StandardCharsets.UTF_8);
                int value = ResponseRules.value(stage, text);
                if (stage.equals("entry")) latestEntry = text; else latestCheckout = text;
                record.put("originalText", text); record.put("observedValue", value);
                int[] rule = rules.get(stage);
                if (rule != null) {
                    ResponseRules.Preview preview = ResponseRules.preview(stage, text, rule[0], rule[1]);
                    delivered = preview.text().getBytes(StandardCharsets.UTF_8);
                    record.put("modified", true); record.put("modifiedText", preview.text());
                }
            } catch (Exception e) { record.put("matchError", e.getMessage() == null ? "FORMAT_ERROR" : e.getMessage()); }
        }
        try { save(record); }
        catch (Exception e) {
            event("SAVE_FAILED", "", "Response forwarded unchanged");
            status = "记录保存失败（空间不足或达到限额），本次返回未改写；请停止捕获、导出并清理日志";
            return x.responseBody;
        }
        status = "已保存第 " + count + " 条 · " + stage + " · " + (record.has("matchError") ? record.optString("matchError") : delivered != x.responseBody ? "已改写" : "原始返回");
        return delivered;
    }
    synchronized byte[] selfTestResponse(ProxyEngine.Exchange x) throws Exception {
        java.net.URI uri = java.net.URI.create(x.url);
        if (!Arrays.asList("localhost", "127.0.0.1", "::1", "capture-test.invalid", "192.0.2.2").contains(uri.getHost()) ||
            !"/capture-selftest".equals(uri.getPath())) throw new IOException("NOT_SELF_TEST");
        String text = new String(x.decodedResponse != null ? x.decodedResponse : x.responseBody, StandardCharsets.UTF_8);
        if (!text.equals("{\"message\":\"original\"}")) throw new IOException("SELF_TEST_BODY_MISMATCH");
        byte[] changed = "{\"message\":\"modified-in-apk\"}".getBytes(StandardCharsets.UTF_8);
        JSONObject o = record(x); o.put("source", "LOCAL_SYNTHETIC_SELF_TEST"); o.put("modified", true);
        o.put("originalText", text); o.put("modifiedText", new String(changed, StandardCharsets.UTF_8)); save(o);
        return changed;
    }
    void export(OutputStream destination) throws Exception {
      synchronized (EXPORT_LOCK) {
        // No CA private keys, proxy ownership files, or application preferences in exports.
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            File[] files = directory.listFiles(); if (files == null) return;
            for (File f : files) {
                if (!(f.getName().endsWith(".json") || f.getName().equals("events.jsonl"))) continue;
                zip.putNextEntry(new ZipEntry(f.getName()));
                long remaining = f.length();
                try (InputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[8192]; int n;
                    while (remaining > 0 && (n = in.read(b, 0, (int)Math.min(b.length, remaining))) != -1) {
                        zip.write(b, 0, n); remaining -= n;
                    }
                }
                zip.closeEntry();
            }
        }
      }
    }
    void clearLogs() throws IOException {
        synchronized (EXPORT_LOCK) {
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("CAPTURE_DIRECTORY_UNAVAILABLE");
            for (File file : files) {
                if (file.isFile() && (file.getName().endsWith(".json") || file.getName().endsWith(".tmp") || file.getName().equals("events.jsonl"))) {
                    if (!file.delete()) throw new IOException("CAPTURE_DELETE_FAILED");
                }
            }
            count = 0; beginStage(); status = "抓包日志已清理";
        }
    }
}
