package local.jdcapture.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded HTTP/1.x parser. No silent truncated evidence, no ambiguous framing. */
public final class HttpWire {
    public static final int MAX_BODY = 8 * 1024 * 1024;
    public record Head(String firstLine, List<String[]> headers) {
        public String value(String key) {
            String found = null;
            for (String[] h : headers) if (h[0].equalsIgnoreCase(key)) {
                if (found != null) return found + "," + h[1];
                found = h[1];
            }
            return found;
        }
    }
    public static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] bytes = b.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') throw new IOException("INVALID_LINE_ENDING");
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            if (b.size() >= 16384) throw new IOException("HEADER_LINE_TOO_LARGE");
            b.write(c);
        }
        throw new EOFException();
    }
    public static Head head(InputStream in) throws IOException {
        String first = line(in); List<String[]> headers = new ArrayList<>();
        int bytes = first.length();
        for (int i = 0; i < 160; i++) {
            String s = line(in); bytes += s.length();
            if (bytes > 65536) throw new IOException("HEADERS_TOO_LARGE");
            if (s.isEmpty()) return new Head(first, headers);
            int colon = s.indexOf(':');
            if (colon <= 0 || !s.substring(0, colon).matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) throw new IOException("INVALID_HEADER");
            headers.add(new String[]{s.substring(0, colon), s.substring(colon + 1).trim()});
        }
        throw new IOException("TOO_MANY_HEADERS");
    }
    public static byte[] body(InputStream in, Head head) throws IOException {
        String len = head.value("Content-Length"), enc = head.value("Transfer-Encoding");
        if (len != null && enc != null) throw new IOException("AMBIGUOUS_FRAMING");
        if (enc != null) {
            if (!enc.equalsIgnoreCase("chunked")) throw new IOException("UNSUPPORTED_TRANSFER_ENCODING");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (;;) {
                String s = line(in).split(";", 2)[0]; int n;
                try { n = Integer.parseInt(s, 16); } catch (NumberFormatException e) { throw new IOException("INVALID_CHUNK"); }
                if (n < 0 || (long)out.size() + n > MAX_BODY) throw new IOException("BODY_TOO_LARGE");
                if (n == 0) {
                    for (int i = 0; i < 80; i++) if (line(in).isEmpty()) return out.toByteArray();
                    throw new IOException("TRAILERS_TOO_LARGE");
                }
                out.write(exact(in, n));
                if (!line(in).isEmpty()) throw new IOException("INVALID_CHUNK_END");
            }
        }
        if (len == null) return new byte[0];
        try { int n = Integer.parseInt(len); return exact(in, n); }
        catch (NumberFormatException e) { throw new IOException("INVALID_CONTENT_LENGTH"); }
    }
    private static byte[] exact(InputStream in, int n) throws IOException {
        if (n < 0 || n > MAX_BODY) throw new IOException("BODY_TOO_LARGE");
        byte[] b = new byte[n]; int pos = 0;
        while (pos < n) { int k = in.read(b, pos, n - pos); if (k < 0) throw new EOFException(); pos += k; }
        return b;
    }
    public static byte[] bounded(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > MAX_BODY) throw new IOException("BODY_TOO_LARGE");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
    public static void reply(OutputStream out, int status, List<String[]> headers, byte[] body) throws IOException {
        out.write(("HTTP/1.1 " + status + " Response\r\n").getBytes(StandardCharsets.ISO_8859_1));
        for (String[] h : headers) {
            if (Arrays.asList("content-length", "transfer-encoding", "connection", "keep-alive", "proxy-connection").contains(h[0].toLowerCase(Locale.ROOT))) continue;
            out.write((h[0] + ": " + h[1] + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write(("Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body); out.flush();
    }
}
