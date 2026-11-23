package local.jdcapture;

import org.junit.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class EmbeddedBridgeTest {
    private CaptureStore store;
    private EmbeddedBridge bridge;
    private Path directory;
    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("capture-bridge-test-"); store = new CaptureStore(directory.toFile());
        bridge = new EmbeddedBridge(store, true);
    }
    @After public void cleanup() throws Exception {
        bridge.close(); try (var paths = Files.walk(directory)) { paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
    }
    private HttpURLConnection post(String path, String token, JSONObject data) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + bridge.port() + path).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(3000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + token);
        byte[] body = data.toString().getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) { out.write(body); } return c;
    }
    private JSONObject flow() throws Exception {
        JSONObject o = new JSONObject(); o.put("url", "https://localhost:9443/capture-selftest"); o.put("method", "GET");
        o.put("requestHeaders", new JSONArray()); o.put("responseHeaders", new JSONArray()); o.put("requestBodyBase64", "");
        o.put("responseBodyBase64", Base64.getEncoder().encodeToString("{\"message\":\"original\"}".getBytes(StandardCharsets.UTF_8))); o.put("status", 200); return o;
    }
    @Test public void oversizedHeaderRejectedWithoutWaitingForBody() throws Exception {
        try(Socket s=new Socket("127.0.0.1",bridge.port())) {
            s.setSoTimeout(2000);
            String head="POST /response HTTP/1.1\r\nAuthorization: Bearer "+bridge.token+"\r\nContent-Length: "+(InFlightBudget.MAX_REQUEST+1)+"\r\n\r\n";
            s.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();
            assertTrue(new BufferedReader(new InputStreamReader(s.getInputStream())).readLine().contains("413"));assertEquals(0,store.count);
        }
    }
    @Test public void malformedPayloadReleasesAdmissionForNextRequest() throws Exception {
        for(int i=0;i<3;i++) {
            HttpURLConnection c=post("/response",bridge.token,new JSONObject());
            try{try{c.getResponseCode();}catch(IOException expected){}}finally{c.disconnect();}
        }
        HttpURLConnection c=post("/response",bridge.token,flow());
        try{assertEquals(200,c.getResponseCode());assertEquals(1,store.count);}finally{c.disconnect();}
    }
    @Test public void rejectsWrongTokenWithoutSaving() throws Exception {
        HttpURLConnection c = post("/response", "incorrect", flow());
        try { assertEquals(403, c.getResponseCode()); assertEquals(0, store.count); } finally { c.disconnect(); }
    }
    @Test public void selfTestPersistsOriginalBeforeReturningChange() throws Exception {
        JSONObject input=flow();input.put("requestHttpVersion","HTTP/3");input.put("responseHttpVersion","HTTP/3");input.put("transportProtocol","udp");
        HttpURLConnection c = post("/response", bridge.token, input);
        try {
            assertEquals(200, c.getResponseCode());
            JSONObject out = new JSONObject(new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(out.getBoolean("changed")); assertEquals("{\"message\":\"modified-in-apk\"}", new String(Base64.getDecoder().decode(out.getString("bodyBase64")), StandardCharsets.UTF_8));
            assertEquals(1, store.count);
            try (var files = Files.list(directory)) {
                JSONObject record = new JSONObject(new String(Files.readAllBytes(files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()), StandardCharsets.UTF_8));
                assertEquals("LOCAL_SYNTHETIC_SELF_TEST", record.getString("source"));
                assertEquals("HTTP/3",record.getString("requestHttpVersion"));
                assertEquals("HTTP/3",record.getString("responseHttpVersion"));
                assertEquals("udp",record.getString("transportProtocol"));
                assertEquals("{\"message\":\"original\"}", record.getString("originalText"));
            }
        } finally { c.disconnect(); }
    }
    @Test public void isolatedModificationRejectsRemoteOrigin() throws Exception {
        JSONObject f = flow(); f.put("url", "https://example.com/capture-selftest");
        HttpURLConnection c = post("/response", bridge.token, f);
        try { try { assertNotEquals(200, c.getResponseCode()); } catch (IOException expected) {} assertEquals(0, store.count); }
        finally { c.disconnect(); }
    }
    @Test public void finalEvidenceNeverMarkedAsForwarded() throws Exception {
        HttpURLConnection c = post("/final", bridge.token, flow());
        try {
            assertEquals(200, c.getResponseCode()); assertEquals(1, store.count);
            try (var files = Files.list(directory)) {
                JSONObject record = new JSONObject(new String(Files.readAllBytes(files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()), StandardCharsets.UTF_8));
                assertFalse(record.getBoolean("finalRequestForwarded")); assertEquals(409, record.getInt("status"));
            }
        } finally { c.disconnect(); }
    }
}
