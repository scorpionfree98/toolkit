package local.jdcapture.core;

import org.junit.Test;
import static org.junit.Assert.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyTest {
    private InputStream bytes(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII)); }
    @Test public void framingRejectsAmbiguityAndTruncation() throws Exception {
        HttpWire.Head ambiguous = HttpWire.head(bytes("POST / HTTP/1.1\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n"));
        assertThrows(IOException.class, () -> HttpWire.body(bytes("a"), ambiguous));
        HttpWire.Head duplicated = HttpWire.head(bytes("POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\n"));
        assertThrows(IOException.class, () -> HttpWire.body(bytes("a"), duplicated));
        HttpWire.Head truncated = HttpWire.head(bytes("POST / HTTP/1.1\r\nContent-Length: 2\r\n\r\n"));
        assertThrows(EOFException.class, () -> HttpWire.body(bytes("a"), truncated));
        assertThrows(IOException.class, () -> HttpWire.head(bytes("GET / HTTP/1.1\n\n")));
    }
    @Test public void chunkedBodiesAreDecodedWithoutLosingBytes() throws Exception {
        HttpWire.Head head = HttpWire.head(bytes("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"));
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), HttpWire.body(bytes("2\r\nhe\r\n3;ext=value\r\nllo\r\n0\r\nX-Trailer: 1\r\n\r\n"), head));
    }
    @Test public void scopesAreExactAndDoNotOverlap() {
        assertFalse(Scope.TUNNEL.decrypts("api.m.jd.com"));
        assertTrue(Scope.ENTRY.decrypts("pro.m.jd.com"));
        assertFalse(Scope.ENTRY.decrypts("api.m.jd.com"));
        assertTrue(Scope.CHECKOUT.decrypts("api.m.jd.com"));
        assertFalse(Scope.CHECKOUT.decrypts("pro.m.jd.com"));
        assertFalse(Scope.ENTRY.decrypts("pro.m.jd.com.attacker.invalid"));
    }
    @Test public void caIsStableAndRejectsOutOfScopeHosts() throws Exception {
        File dir = Files.createTempDirectory("proxy-ca").toFile();
        CertificateAuthority a = new CertificateAuthority(dir), b = new CertificateAuthority(dir);
        assertArrayEquals(a.publicCertificate(), b.publicCertificate());
        assertEquals(a.fingerprint(), b.fingerprint());
        assertThrows(IOException.class, () -> a.serverContext("example.com"));
        Files.write(new File(dir, "ca-key.pk8").toPath(), new byte[]{1});
        assertThrows(Exception.class, () -> new CertificateAuthority(dir));
    }
    @Test public void changingStageClosesPreviouslyAcceptedConnections() throws Exception {
        CertificateAuthority ca = new CertificateAuthority(Files.createTempDirectory("proxy-stage").toFile());
        ProxyEngine proxy = new ProxyEngine(ca, new ProxyEngine.Listener() {
            public void event(String type, String host, String detail) {}
            public byte[] response(ProxyEngine.Exchange e) { return e.responseBody; }
            public void finalCaptured(ProxyEngine.Exchange e) {}
        });
        proxy.scope(Scope.ENTRY); proxy.start(0);
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("CONNECT pro.m.jd.com:443 HTTP/1.1\r\nHost: pro.m.jd.com\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertTrue(HttpWire.head(socket.getInputStream()).firstLine().contains("200"));
            proxy.scope(Scope.CHECKOUT);
            assertEquals(-1, socket.getInputStream().read());
        } finally { proxy.close(); }
    }
    private SSLContext clientContext(CertificateAuthority ca) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType()); ks.load(null, null);
        ks.setCertificateEntry("local", CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(ca.publicCertificate())));
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS"); ctx.init(null, tm.getTrustManagers(), null); return ctx;
    }
    private String finalRequest(ProxyEngine proxy, SSLContext context, String ticket) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("CONNECT api.m.jd.com:443 HTTP/1.1\r\nHost: api.m.jd.com:443\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertTrue(HttpWire.head(socket.getInputStream()).firstLine().contains("200"));
            try (SSLSocket tls = (SSLSocket)context.getSocketFactory().createSocket(socket, "api.m.jd.com", 443, false)) {
                SSLParameters p = tls.getSSLParameters(); p.setEndpointIdentificationAlgorithm("HTTPS"); tls.setSSLParameters(p); tls.startHandshake();
                String body = "functionId=SEP_EXCHANGE_SUBMIT&body=" + ticket;
                tls.getOutputStream().write(("POST /api HTTP/1.1\r\nHost: api.m.jd.com\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
                HttpWire.Head response = HttpWire.head(tls.getInputStream()); assertTrue(response.firstLine().contains("409"));
                return new String(HttpWire.body(tls.getInputStream(), response), StandardCharsets.UTF_8);
            }
        }
    }
    @Test public void finalCapturesAreContinuousAndNeverForwardedEvenIfSaveFails() throws Exception {
        CertificateAuthority ca = new CertificateAuthority(Files.createTempDirectory("proxy-tls").toFile());
        AtomicInteger attempts = new AtomicInteger(); List<String> bodies = new ArrayList<>();
        ProxyEngine.Listener listener = new ProxyEngine.Listener() {
            public void event(String type, String host, String detail) {}
            public byte[] response(ProxyEngine.Exchange e) { throw new AssertionError("Must never reach upstream response"); }
            public void finalCaptured(ProxyEngine.Exchange e) throws Exception {
                assertTrue(e.localOnly); bodies.add(new String(e.requestBody, StandardCharsets.UTF_8));
                if (attempts.incrementAndGet() == 3) throw new IOException("simulated disk failure");
            }
        };
        ProxyEngine proxy = new ProxyEngine(ca, listener); proxy.scope(Scope.CHECKOUT); proxy.start(0); int port = proxy.port();
        try {
            SSLContext context = clientContext(ca);
            assertTrue(finalRequest(proxy, context, "first").contains("CAPTURED_LOCALLY"));
            assertTrue(finalRequest(proxy, context, "newest").contains("CAPTURED_LOCALLY"));
            assertTrue(finalRequest(proxy, context, "third").contains("CAPTURE_SAVE_FAILED"));
            assertEquals(3, attempts.get()); assertTrue(bodies.get(1).endsWith("newest"));
        } finally { proxy.close(); }
        assertThrows(IOException.class, () -> { try (Socket ignored = new Socket("127.0.0.1", port)) {} });
    }
}
