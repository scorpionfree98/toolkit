package local.jdcapture;

import local.jdcapture.core.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Per-session authenticated loopback endpoint; never exposed as an Android component. */
final class EmbeddedBridge implements AutoCloseable {
    final String token;
    private final CaptureStore store;
    private final boolean isolated;
    private final ServerSocket server;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final InFlightBudget budget = new InFlightBudget();
    private final ExecutorService workers = new ThreadPoolExecutor(0, 8, 20, TimeUnit.SECONDS,
        new SynchronousQueue<>(), r -> new Thread(r, "engine-bridge"));
    EmbeddedBridge(CaptureStore store, boolean isolated) throws Exception {
        this.store = store; this.isolated = isolated;
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        StringBuilder hex = new StringBuilder(); for (byte b : secret) hex.append(String.format("%02x", b)); token = hex.toString();
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) try {
                Socket socket = server.accept(); clients.add(socket);
                try { workers.execute(() -> serve(socket)); }
                catch (RejectedExecutionException e) { clients.remove(socket); socket.close(); }
            } catch (IOException e) { if (!server.isClosed()) store.event("BRIDGE_ACCEPT_FAILED", "", "IO error"); }
        }, "engine-bridge-accept"); accept.setDaemon(true); accept.start();
    }
    int port() { return server.getLocalPort(); }
    private void serve(Socket socket) {
        int reserved = -1;
        try (Socket s = socket) {
            s.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(s.getInputStream()); HttpWire.Head head = HttpWire.head(in);
            String auth = head.value("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                reply(s, 403, new JSONObject()); return;
            }
            String[] request = head.firstLine().split(" ");
            if (request.length != 3 || !request[0].equals("POST") ||
                !Arrays.asList("/response", "/final", "/event").contains(request[1])) { reply(s, 404, new JSONObject()); return; }
            String length = head.value("Content-Length");
            if (head.value("Transfer-Encoding") != null || length == null || !length.matches("[0-9]{1,8}")) throw new IOException("INVALID_FRAMING");
            int n = Integer.parseInt(length);
            if(n > InFlightBudget.MAX_REQUEST) { reply(s,413,new JSONObject()); return; }
            if(!budget.acquire(n)) { reply(s,503,new JSONObject()); return; }
            reserved = n;
            byte[] bytes = new byte[n]; new DataInputStream(in).readFully(bytes);
            JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8)), out = new JSONObject();
            if (request[1].equals("/event")) store.event(data.getString("type"), data.optString("host"), data.optString("detail"));
            else {
                ProxyEngine.Exchange x = exchange(data);
                if (request[1].equals("/final")) { x.localOnly = true; x.status = 409; store.finalCaptured(x); }
                else {
                    byte[] delivered = isolated ? store.selfTestResponse(x) : store.response(x);
                    if(delivered.length>2*1024*1024)throw new IOException("REPLACEMENT_TOO_LARGE");
                    boolean changed = delivered != x.responseBody; out.put("changed", changed);
                    if (changed) out.put("bodyBase64", Base64.getEncoder().encodeToString(delivered));
                }
            }
            reply(s, 200, out);
        } catch (Exception e) { store.event("BRIDGE_REQUEST_FAILED", "", e.getClass().getSimpleName()); }
        finally { if(reserved >= 0) budget.release(reserved); clients.remove(socket); }
    }
    private static List<String[]> headers(JSONArray array) throws Exception {
        if (array.length() > 160) throw new IOException("TOO_MANY_HEADERS");
        List<String[]> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) { JSONArray h = array.getJSONArray(i); list.add(new String[]{h.getString(0), h.getString(1)}); }
        return list;
    }
    private static ProxyEngine.Exchange exchange(JSONObject data) throws Exception {
        ProxyEngine.Exchange x = new ProxyEngine.Exchange(); x.url = data.getString("url"); x.method = data.getString("method");
        x.requestHttpVersion = data.optString("requestHttpVersion"); x.responseHttpVersion = data.optString("responseHttpVersion");
        x.transportProtocol = data.optString("transportProtocol");
        x.requestHeaders = headers(data.getJSONArray("requestHeaders")); x.responseHeaders = headers(data.getJSONArray("responseHeaders"));
        x.requestBody = body(data.getString("requestBodyBase64"));
        x.responseBody = body(data.getString("responseBodyBase64")); x.status = data.getInt("status");
        if (data.has("decodedBodyBase64")) x.decodedResponse = body(data.getString("decodedBodyBase64"));
        return x;
    }
    private static byte[] body(String value) throws IOException {
        if(value.length() > ((2*1024*1024+2)/3)*4) throw new IOException("BODY_TOO_LARGE");
        byte[] decoded=Base64.getDecoder().decode(value);
        if(decoded.length>2*1024*1024)throw new IOException("BODY_TOO_LARGE");
        return decoded;
    }
    private static void reply(Socket s, int code, JSONObject data) throws IOException {
        HttpWire.reply(s.getOutputStream(), code, Collections.singletonList(new String[]{"Content-Type", "application/json"}), data.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Override public void close() {
        try { server.close(); } catch (IOException ignored) {}
        for (Socket s : clients) try { s.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }
}
