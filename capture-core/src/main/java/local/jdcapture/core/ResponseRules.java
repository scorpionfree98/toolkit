package local.jdcapture.core;

import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class ResponseRules {
    public static final String SKU = "100228358274";
    public static final String ENTRY_PATH = "/mall/active/3Rcw1NV6pjiUBpznNHooXjPNAicD/index.html";
    public static String stage(String url, String body) {
        URI u = URI.create(url);
        if ("pro.m.jd.com".equals(u.getHost()) && ENTRY_PATH.equals(u.getPath())) return "entry";
        if ("api.m.jd.com".equals(u.getHost()) && hasFunction(url, body, "SEP_ACTIVITY_WARE_DETAIL")) return "checkout";
        return "other";
    }
    public static boolean hasFunction(String url, String body, String function) {
        String query = URI.create(url).getRawQuery();
        for (String data : new String[]{query == null ? "" : query, body}) {
            for (String part : data.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && decode(kv[0]).equals("functionId") && decode(kv[1]).equals(function)) return true;
            }
        }
        return false;
    }
    private static String decode(String v) { try { return URLDecoder.decode(v, "UTF-8"); } catch (Exception e) { return ""; } }
    public record Preview(String text, String field, int before, int after) {}
    private record Document(JSONObject json, JSONObject target, String field, int start, int end) {}
    private static Document document(String stage, String text) throws Exception {
        if (stage.equals("entry")) {
            Matcher m = Pattern.compile("window\\.__react_data__\\s*=\\s*").matcher(text); int start = -1;
            while (m.find()) start = m.end();
            if (start < 0) throw new IllegalArgumentException("BOOTSTRAP_NOT_FOUND");
            int end = jsonEnd(text, start);
            JSONObject json = new JSONObject(text.substring(start, end)); JSONObject data = json.getJSONObject("activityData");
            JSONObject shield = data.optJSONObject("shieldResult");
            if (shield != null && shield.optBoolean("intercept")) throw new IllegalArgumentException("ENTRY_VERIFICATION_REQUIRED");
            if (!"02015183".equals(data.optString("activityId")) || !"5923154".equals(data.optString("pageId")))
                throw new IllegalArgumentException("ENTRY_IDENTITY_MISMATCH");
            List<JSONObject> targets = new ArrayList<>(); find(data, targets);
            if (targets.size() != 1) throw new IllegalArgumentException("UNIQUE_TARGET_REQUIRED");
            return new Document(json, targets.get(0), "skuStatus", start, end);
        }
        if (!stage.equals("checkout")) throw new IllegalArgumentException("UNSUPPORTED_STAGE");
        JSONObject json = new JSONObject(text), data = json.optJSONObject("data");
        if (!json.optBoolean("success") || json.optInt("errorCode") != 1000 || data == null ||
            !"1421003".equals(data.optString("activityId")) || !SKU.equals(data.optString("wareBusinessId")) || data.optInt("exchangeScore") != 9)
            throw new IllegalArgumentException("DETAIL_IDENTITY_MISMATCH");
        return new Document(json, data, "exchangeQty", 0, text.length());
    }
    private static void find(Object value, List<JSONObject> result) throws Exception {
        if (value instanceof JSONObject o) {
            if (SKU.equals(o.optString("skuId")) && o.optInt("exchangeScore", -1) == 9 && o.has("skuStatus")) result.add(o);
            Iterator<String> it = o.keys(); while (it.hasNext()) find(o.get(it.next()), result);
        } else if (value instanceof JSONArray a) for (int i = 0; i < a.length(); i++) find(a.get(i), result);
    }
    private static int jsonEnd(String text, int start) {
        boolean quoted = false, escaped = false; int level = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
            else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') level++;
            else if ((c == '}' || c == ']') && --level == 0) return i + 1;
        }
        throw new IllegalArgumentException("INVALID_BOOTSTRAP");
    }
    public static int value(String stage, String text) throws Exception {
        Document d = document(stage, text); Object v = d.target.get(d.field);
        if (!(v instanceof Integer)) throw new IllegalArgumentException("INTEGER_REQUIRED");
        return (Integer)v;
    }
    public static Preview preview(String stage, String text, int before, int after) throws Exception {
        if (before < 0 || before > 99 || after < 0 || after > 99 || before == after) throw new IllegalArgumentException("INVALID_VALUES");
        Document d = document(stage, text);
        if (!(d.target.get(d.field) instanceof Integer) || d.target.getInt(d.field) != before) throw new IllegalArgumentException("EXPECTED_VALUE_MISMATCH");
        d.target.put(d.field, after); String raw = text.substring(d.start, d.end); String match = null;
        Matcher m = Pattern.compile("\"" + d.field + "\"\\s*:\\s*(" + before + ")(?=\\s*[,}])").matcher(raw);
        while (m.find()) {
            String candidate = raw.substring(0, m.start(1)) + after + raw.substring(m.end(1));
            if (same(new JSONObject(candidate), d.json)) {
                if (match != null) throw new IllegalArgumentException("UNIQUE_PATCH_REQUIRED");
                match = candidate;
            }
        }
        if (match == null) throw new IllegalArgumentException("UNIQUE_PATCH_REQUIRED");
        return new Preview(text.substring(0, d.start) + match + text.substring(d.end), d.field, before, after);
    }
    private static boolean same(Object a, Object b) throws Exception {
        if (a instanceof JSONObject x && b instanceof JSONObject y) {
            if (x.length() != y.length()) return false;
            Iterator<String> it = x.keys();
            while (it.hasNext()) { String k = it.next(); if (!y.has(k) || !same(x.get(k), y.get(k))) return false; }
            return true;
        }
        if (a instanceof JSONArray x && b instanceof JSONArray y) {
            if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }
}
