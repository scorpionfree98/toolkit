package local.jdcapture;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Shell-side process owner. No caller-supplied command or filesystem path. */
final class RuntimeHost {
    private static final String SHA = "19c0c8ff7fd8e59cdcd29d9f338f504dc67275809496619c2915205894dd49b5";
    private final Context context;
    final File root;
    private java.lang.Process process;
    private File session;
    private int port;
    private Thread logPump;
    private volatile boolean logFailed;
    private boolean isolated, vpn;
    private boolean cleanupPending;
    RuntimeHost(Context context) {
        this.context = context;
        root = new File("/data/local/tmp/jdcapture-runtime-" + context.getApplicationInfo().uid);
    }
    private static void directory(File f) throws Exception {
        if (Files.isSymbolicLink(f.toPath())) throw new IOException("SYMLINK_REJECTED");
        if (!f.isDirectory() && !f.mkdirs()) throw new IOException("DIRECTORY_FAILED");
        if (Os.stat(f.getPath()).st_uid != Process.myUid()) throw new IOException("DIRECTORY_OWNER_MISMATCH");
        Os.chmod(f.getPath(), 0700);
    }
    static void atomic(File file, String value) throws Exception {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            Os.chmod(tmp.getPath(), 0600); out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private void asset(String name, File target) throws Exception {
        try (InputStream in = context.getAssets().open("embedded/" + name); OutputStream out = new FileOutputStream(target)) {
            Os.chmod(target.getPath(), 0600); byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
    }
    private void install() throws Exception {
        directory(root);
        File runtime = new File(root, "shell-runtime-dns"), marker = new File(root, "installed.sha256");
        if (marker.isFile() && new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).equals(SHA) && new File(runtime, "bin/python").canExecute()) return;
        File archive = new File(root, "runtime.tar.gz"); asset("runtime.bin", archive);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(archive)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
        StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equals(SHA)) throw new IOException("RUNTIME_HASH_MISMATCH");
        // Archive is a fixed, hash-verified build artifact, never a caller-provided archive.
        java.lang.Process unpack = new ProcessBuilder("/system/bin/tar", "-xzf", archive.getPath(), "-C", root.getPath())
            .redirectErrorStream(true).redirectOutput(new File(root, "install.log")).start();
        if (!unpack.waitFor(40, TimeUnit.SECONDS)) { unpack.destroyForcibly(); throw new IOException("RUNTIME_UNPACK_TIMEOUT"); }
        if (unpack.exitValue() != 0) throw new IOException("RUNTIME_UNPACK_FAILED");
        atomic(marker, SHA); archive.delete();
    }
    synchronized String start(int bridgePort, String token, String scope, String pem, boolean test) throws Exception {
        return start(bridgePort, token, scope, pem, test, false);
    }
    synchronized String start(int bridgePort, String token, String scope, String pem, boolean test, boolean vpnMode) throws Exception {
        if (bridgePort < 1024 || bridgePort > 65535 || !token.matches("[a-f0-9]{64}") ||
            !Arrays.asList("TUNNEL", "ENTRY", "CHECKOUT").contains(scope) || pem.length() > 16384 ||
            !pem.contains("BEGIN PRIVATE KEY") || !pem.contains("BEGIN CERTIFICATE")) throw new IOException("INVALID_RUNTIME_CONFIG");
        stop(); install(); new RuntimeSessions(root).requireCapacity();
        session = new File(root, "session-" + UUID.randomUUID().toString()); directory(session);
        cleanupPending=true;
        File ca = new File(session, "ca"); directory(ca); atomic(new File(ca, "mitmproxy-ca.pem"), pem);
        asset("engine.py", new File(session, "engine.py"));
        isolated = test; vpn = vpnMode; port = 18886;
        if (vpn) try (DatagramSocket free = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) { port = free.getLocalPort(); }
        if (test && !vpn) try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = free.getLocalPort(); }
        JSONObject config = new JSONObject(); config.put("bridgePort", bridgePort); config.put("token", token);
        config.put("deviceFixture", vpn && test); config.put("mode", vpn ? "VPN" : "HTTP"); config.put("scope", scope); config.put("isolated", test); config.put("port", port); config.put("parentPid", Process.myPid());
        logFailed=false;
        atomic(new File(session, "config.json"), config.toString()); beat();
        ProcessBuilder builder = new ProcessBuilder(new File(root, "shell-runtime-dns/bin/python").getPath(),
            new File(session, "engine.py").getPath(), new File(session, "config.json").getPath());
        builder.redirectInput(new File("/dev/null")); builder.redirectErrorStream(true);
        process = builder.start();
        final java.lang.Process started=process;
        final File logFile=new File(session,"engine.private.log");logFailed=false;
        logPump=new Thread(()->{
            try(InputStream in=started.getInputStream();OutputStream out=new FileOutputStream(logFile)) {
                Os.chmod(logFile.getPath(),0600);RuntimeSessions.copyLog(in,out,RuntimeSessions.MAX_LOG_BYTES);
            }catch(Exception error){logFailed=true;started.destroy();}
        },"capture-bounded-log");logPump.setDaemon(true);logPump.start();
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!process.isAlive()) throw new IOException("ENGINE_EXIT: " + log());
            if (new File(session, "ready").isFile()) {
                if (vpn) { vpnConfig(); return status(); }
                try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 500); return status(); }
            }
            Thread.sleep(100);
        }
        stop(); throw new IOException("ENGINE_START_TIMEOUT");
    }
    synchronized String vpnConfig() throws Exception {
        if (!vpn || !alive() || session == null) throw new IOException("VPN_ENGINE_NOT_READY");
        return new String(Files.readAllBytes(new File(session, "vpn-client.json").toPath()), StandardCharsets.UTF_8);
    }
    synchronized boolean httpProxyReady() { return alive() && !vpn && !isolated && port == 18886; }
    synchronized void beat() throws Exception {
        if(logFailed)throw new IOException("RUNTIME_LOG_WRITE_FAILED");
        if (session != null) atomic(new File(session, "heartbeat"), Long.toString(System.currentTimeMillis()));
    }
    synchronized String storage() throws Exception { directory(root); return new RuntimeSessions(root).inventory(alive()?sessionName():"").toString(); }
    synchronized String clearHistory() throws Exception {
        directory(root);
        if(alive())throw new IOException("STOP_RUNTIME_BEFORE_CLEANING");
        int removed=new RuntimeSessions(root).clearFinished("");
        if(session!=null&&!session.exists())session=null;
        return new JSONObject(storage()).put("removed",removed).toString();
    }
    synchronized boolean alive() { return process != null && process.isAlive(); }
    synchronized boolean started() { return process != null || cleanupPending; }
    synchronized String sessionName() { return session == null ? "" : session.getName(); }
    synchronized String status() throws Exception {
        JSONObject o = new JSONObject(); o.put("alive", alive()); o.put("port", port); o.put("isolated", isolated);
        o.put("mode", vpn ? "VPN" : "HTTP"); o.put("engine", "mitmproxy 12.2.3"); o.put("uid", Process.myUid()); return o.toString();
    }
    synchronized String log() {
        try {
            if (session == null) return "尚未启动";
            File f = new File(session, "engine.private.log");
            try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
                long start = Math.max(0, in.length() - 4096); in.seek(start); byte[] b = new byte[(int)(in.length() - start)]; in.readFully(b);
                return new String(b, StandardCharsets.UTF_8).replaceAll("(?m)^.*(?:PrivateKey|server_key|client_key|clientPrivateKey).*$", "[private VPN key redacted]");
            }
        } catch (Exception e) { return "日志不可用"; }
    }
    synchronized void stop() throws Exception {
        if (process != null) {
        process.destroy();
        if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
        if (process.isAlive()) throw new IOException("ENGINE_STOP_TIMEOUT");
        process = null;
        }
        if(logPump!=null){logPump.join(2000);if(logPump.isAlive())throw new IOException("RUNTIME_LOG_CLOSE_PENDING");logPump=null;}
        // Private CA remains in the app. Remove the transient shell-side copy and auth token.
        if (session != null && session.exists()) {
            new RuntimeSessions(root).cleanSecrets(session);
            atomic(new File(session,"stopped"),"stopped");
        }
        cleanupPending=false;
    }
}
