package local.jdcapture.core;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only CONNECT proxy. TLS inspection is scoped; all other hosts tunnel. */
public final class ProxyEngine implements AutoCloseable {
    public interface Listener {
        void event(String type, String host, String detail);
        byte[] response(Exchange exchange) throws Exception;
        void finalCaptured(Exchange exchange) throws Exception;
    }
    public static final class Exchange {
        public final long atMs = System.currentTimeMillis();
        public String url, method;
        public String requestHttpVersion = "", responseHttpVersion = "", transportProtocol = "";
        public List<String[]> requestHeaders, responseHeaders;
        public byte[] requestBody, responseBody, decodedResponse;
        public int status;
        public boolean localOnly;
    }
    private final CertificateAuthority authority;
    private final Listener listener;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = new ThreadPoolExecutor(0, 96, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "capture-io"); t.setDaemon(true); return t; });
    private final Semaphore clients = new Semaphore(32);
    private volatile Scope scope = Scope.TUNNEL;
    private volatile long generation;
    private volatile boolean running;
    private ServerSocket server;
    private Future<?> acceptTask;
    public ProxyEngine(CertificateAuthority authority, Listener listener) { this.authority = authority; this.listener = listener; }
    public synchronized void scope(Scope value) {
        if (scope == value) return;
        scope = value; generation++;
        // A pre-existing CONNECT tunnel cannot turn into an inspected TLS connection.
        // Force reconnect when changing stages so persistent tunnels do not hide captures.
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
    }
    public Scope scope() { return scope; }
    public synchronized void start(int port) throws IOException {
        if (running) return;
        server = new ServerSocket(); server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)); running = true;
        acceptTask = workers.submit(() -> {
            while (running) {
                try {
                    Socket socket = server.accept();
                    if (!clients.tryAcquire()) { socket.close(); continue; }
                    sockets.add(socket);
                    try { workers.execute(() -> handle(socket)); }
                    catch (RejectedExecutionException e) { sockets.remove(socket); clients.release(); socket.close(); }
                } catch (IOException e) { if (running) listener.event("LISTENER_ERROR", "", e.getClass().getSimpleName()); }
            }
        });
    }
    public int port() { return server.getLocalPort(); }
    private void handle(Socket client) {
        String host = ""; String[] phase = {"CONNECT_HEADERS"}; long acceptedGeneration = generation;
        try (client) {
            client.setSoTimeout(30000);
            HttpWire.Head head = HttpWire.head(client.getInputStream()); String[] first = head.firstLine().split(" ");
            if (first.length != 3 || !first[0].equals("CONNECT")) {
                HttpWire.reply(client.getOutputStream(), 405, Collections.emptyList(), new byte[0]); return;
            }
            phase[0] = "CONNECT_TARGET";
            String[] address = first[1].split(":");
            if (address.length != 2 || !address[1].equals("443") || !address[0].matches("[A-Za-z0-9.-]+")) throw new IOException("INVALID_CONNECT_TARGET");
            host = address[0].toLowerCase(Locale.ROOT); Scope selected = scope;
            if (!selected.decrypts(host)) { tunnel(client, host, phase); return; }
            phase[0] = "TLS_SETUP";
            SSLContext context = authority.serverContext(host);
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            try (SSLSocket tls = (SSLSocket)context.getSocketFactory().createSocket(client, host, 443, false)) {
                tls.setUseClientMode(false); tls.setSoTimeout(30000);
                SSLParameters parameters = tls.getSSLParameters(); parameters.setApplicationProtocols(new String[]{"http/1.1"}); tls.setSSLParameters(parameters);
                phase[0] = "TLS_HANDSHAKE"; tls.startHandshake(); listener.event("TLS_READY", host, "HTTP/1.1");
                // One request per TLS connection; explicit close prevents stale scope reuse.
                exchange(tls, host, selected, phase);
            }
        } catch (Exception e) {
            if (!running || generation != acceptedGeneration) listener.event("CONNECTION_CLOSED", host, "Stopped or stage changed");
            else if (e instanceof SocketTimeoutException && phase[0].equals("TUNNEL_TRANSFER")) listener.event("TUNNEL_IDLE", host, "Idle tunnel closed");
            else if (e instanceof EOFException || e instanceof SocketException && phase[0].equals("TUNNEL_TRANSFER")) listener.event("CLIENT_CLOSED", host, phase[0]);
            else listener.event(e instanceof SSLException ? "TLS_FAILED" : "CONNECTION_ERROR", host, errorDetail(phase[0],e));
        }
        finally { sockets.remove(client); clients.release(); }
    }
    private static String errorDetail(String phase, Exception e) {
        String message = e.getMessage();
        // Log fixed parser codes and exception types, never URLs, headers or tokens.
        String code = message != null && message.matches("[A-Z][A-Z0-9_]{1,80}") ? " · " + message : "";
        return phase + " · " + e.getClass().getSimpleName() + code;
    }
    private void tunnel(Socket client, String host, String[] phase) throws Exception {
        try (Socket remote = new Socket()) {
            sockets.add(remote);
            try {
                phase[0] = "TUNNEL_CONNECT";
                remote.connect(new InetSocketAddress(host, 443), 10000); remote.setSoTimeout(60000); client.setSoTimeout(60000);
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                phase[0] = "TUNNEL_TRANSFER";
                Future<?> upstream = workers.submit(() -> { try { copy(client.getInputStream(), remote.getOutputStream()); remote.shutdownOutput(); } catch (IOException ignored) {} });
                try { copy(remote.getInputStream(), client.getOutputStream()); }
                finally { client.close(); remote.close(); upstream.cancel(true); }
            } finally { sockets.remove(remote); }
        }
    }
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[16384]; int n; while ((n = in.read(b)) != -1) { out.write(b, 0, n); out.flush(); }
    }
    private void exchange(SSLSocket tls, String host, Scope selected, String[] phase) throws Exception {
        phase[0] = "HTTP_REQUEST";
        InputStream in = tls.getInputStream(); OutputStream out = tls.getOutputStream(); HttpWire.Head head = HttpWire.head(in);
        String[] first = head.firstLine().split(" ");
        if (first.length != 3 || !first[1].startsWith("/") || first[1].startsWith("//") || !first[2].equals("HTTP/1.1")) throw new IOException("INVALID_REQUEST_LINE");
        String declaredHost = head.value("Host");
        if (declaredHost == null || !(declaredHost.equalsIgnoreCase(host) || declaredHost.equalsIgnoreCase(host + ":443"))) throw new IOException("HOST_MISMATCH");
        if (scope != selected) { HttpWire.reply(out, 409, Collections.emptyList(), "STAGE_CHANGED_RELOAD".getBytes(StandardCharsets.UTF_8)); return; }
        if (head.value("Expect") != null) throw new IOException("EXPECT_NOT_SUPPORTED");
        Exchange exchange = new Exchange(); exchange.url = "https://" + host + first[1]; exchange.method = first[0];
        exchange.requestHeaders = head.headers(); exchange.requestBody = HttpWire.body(in, head);
        boolean finalRequest = host.equals("api.m.jd.com") && ResponseRules.hasFunction(exchange.url,
            new String(exchange.requestBody, StandardCharsets.UTF_8), "SEP_EXCHANGE_SUBMIT");
        if (finalRequest) {
            // Never submit a purchase in capture mode, including a failed disk write.
            exchange.localOnly = true; exchange.status = 409; exchange.responseHeaders = Collections.emptyList(); exchange.responseBody = new byte[0];
            String result;
            try { listener.finalCaptured(exchange); result = "{\"code\":\"CAPTURED_LOCALLY\",\"finalRequestForwarded\":false}"; }
            catch (Exception e) { result = "{\"code\":\"CAPTURE_SAVE_FAILED\",\"finalRequestForwarded\":false}"; }
            HttpWire.reply(out, 409, Collections.singletonList(new String[]{"Content-Type", "application/json"}), result.getBytes(StandardCharsets.UTF_8)); return;
        }
        phase[0] = "UPSTREAM_CONNECT";
        HttpURLConnection upstream = (HttpURLConnection)new URL(exchange.url).openConnection(Proxy.NO_PROXY);
        try {
            upstream.setConnectTimeout(10000); upstream.setReadTimeout(15000); upstream.setInstanceFollowRedirects(false);
            upstream.setUseCaches(false); upstream.setRequestMethod(exchange.method);
            Set<String> remove = new HashSet<>(Arrays.asList("host", "content-length", "transfer-encoding", "connection", "proxy-connection", "proxy-authorization", "keep-alive"));
            if (head.value("Connection") != null) for (String v : head.value("Connection").split(",")) remove.add(v.trim().toLowerCase(Locale.ROOT));
            for (String[] h : head.headers()) if (!remove.contains(h[0].toLowerCase(Locale.ROOT))) upstream.addRequestProperty(h[0], h[1]);
            if (exchange.requestBody.length > 0) {
                upstream.setDoOutput(true); upstream.setFixedLengthStreamingMode(exchange.requestBody.length);
                try (OutputStream upload = upstream.getOutputStream()) { upload.write(exchange.requestBody); }
            }
            phase[0] = "UPSTREAM_HEADERS";
            exchange.status = upstream.getResponseCode(); exchange.responseHeaders = new ArrayList<>();
            for (int i = 1; upstream.getHeaderFieldKey(i) != null; i++) exchange.responseHeaders.add(new String[]{upstream.getHeaderFieldKey(i), upstream.getHeaderField(i)});
            phase[0] = "UPSTREAM_BODY";
            try (InputStream body = exchange.status >= 400 ? upstream.getErrorStream() : upstream.getInputStream()) { exchange.responseBody = HttpWire.bounded(body); }
            if (scope != selected) return;
            phase[0] = "CAPTURE_SAVE";
            byte[] response = listener.response(exchange);
            List<String[]> headers = exchange.responseHeaders;
            if (response != exchange.responseBody) {
                headers = new ArrayList<>();
                for (String[] h : exchange.responseHeaders) if (!Arrays.asList("content-encoding", "etag", "content-md5").contains(h[0].toLowerCase(Locale.ROOT))) headers.add(h);
            }
            phase[0] = "CLIENT_RESPONSE";
            HttpWire.reply(out, exchange.status, headers, response);
        } finally { upstream.disconnect(); }
    }
    @Override public synchronized void close() {
        running = false; generation++;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        try {
            // On Linux a blocked accept may retain the native listening descriptor
            // briefly after ServerSocket.close(). Join the accept loop before
            // reporting closure or taking the final client-socket snapshot.
            if (acceptTask != null) acceptTask.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("LISTENER_STOP_INTERRUPTED",e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("LISTENER_STOP_FAILED",e);
        } finally {
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
            workers.shutdownNow();
        }
    }
}
