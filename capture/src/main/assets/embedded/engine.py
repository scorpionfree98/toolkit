"""Private APK engine. Fixed loopback bridge, original response evidence, lease cleanup."""
import asyncio
import base64
import http.client
import http.server
import ipaddress
import json
import logging
import socket
import os
from pathlib import Path
import signal
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse
import zlib

from mitmproxy import http as mitm_http, options, ctx, dns
import mitmproxy_rs
from mitmproxy.tools.dump import DumpMaster

CONFIG = {}
ROOT = Path()
DEVICE_FIXTURES = []
DEVICE_QUIC_SERVERS = []
BRIDGE_SLOTS = None
MAX_BODY = 2 * 1024 * 1024
LAST_DROP_REPORT = 0.0


def report_drop(error):
    global LAST_DROP_REPORT
    now = time.monotonic()
    if now-LAST_DROP_REPORT >= 5:
        LAST_DROP_REPORT=now
        print("CAPTURE_NOT_SAVED_OR_MODIFIED", type(error).__name__, flush=True)


def bounded_content(response):
    raw = response.raw_content or b""
    if len(raw) > MAX_BODY:
        raise ValueError("BODY_TOO_LARGE")
    coding = response.headers.get("content-encoding", "identity").lower().strip()
    if coding in ("", "identity"):
        return raw
    if coding not in ("gzip", "deflate"):
        raise ValueError("UNSUPPORTED_BOUNDED_ENCODING")
    decoder = zlib.decompressobj(31 if coding == "gzip" else 15)
    result = decoder.decompress(raw, MAX_BODY + 1)
    if len(result) > MAX_BODY or not decoder.eof or decoder.unused_data:
        raise ValueError("DECODE_LIMIT_OR_INVALID_STREAM")
    return result


def flow_record(flow, decode=False):
    if len(flow.request.pretty_url)>65536:
        raise ValueError("URL_TOO_LARGE")
    for message in (flow.request, flow.response):
        if message is not None:
            if len(message.raw_content or b"") > MAX_BODY:
                raise ValueError("BODY_TOO_LARGE")
            if sum(len(k)+len(v) for k,v in message.headers.items(multi=True)) > 65536:
                raise ValueError("HEADERS_TOO_LARGE")
    data = record(flow)
    if decode:
        data["decodedBodyBase64"] = encode(bounded_content(flow.response))
    return data


def bridge(path, data):
    conn = http.client.HTTPConnection("127.0.0.1", CONFIG["bridgePort"], timeout=10)
    try:
        conn.request("POST", path, json.dumps(data).encode(),
                     {"Authorization": "Bearer " + CONFIG["token"], "Content-Type": "application/json"})
        response = conn.getresponse()
        data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError("BRIDGE_REPLY_TOO_LARGE")
        if response.status != 200:
            raise RuntimeError("BRIDGE_REJECTED")
        return json.loads(data)
    finally:
        conn.close()


async def bridge_async(path, data):
    # Hold a bounded slot until the socket worker actually completes. Cancellation
    # cannot release it early and leave an unbounded number of worker threads.
    global BRIDGE_SLOTS
    if BRIDGE_SLOTS is None:
        BRIDGE_SLOTS = asyncio.Semaphore(2)
    # A flow factory holds no Base64/decompressed copies while waiting. Reject
    # saturation immediately; do not retain an unbounded waiting flow queue.
    if BRIDGE_SLOTS.locked():
        raise RuntimeError("BRIDGE_BUSY")
    # With an available semaphore this coroutine completes without yielding.
    # wait_for would schedule acquisition later, allowing an unbounded race of waiters.
    await BRIDGE_SLOTS.acquire()
    slots = BRIDGE_SLOTS
    task = asyncio.create_task(asyncio.to_thread(lambda: bridge(path, data() if callable(data) else data)))
    def completed(worker):
        slots.release()
        if not worker.cancelled():
            worker.exception()  # Retrieve errors even if the awaiting flow was cancelled.
    task.add_done_callback(completed)
    return await asyncio.shield(task)


def encode(value):
    return base64.b64encode(value or b"").decode("ascii")


def record(flow):
    return {"url": flow.request.pretty_url, "method": flow.request.method,
            "requestHttpVersion": flow.request.http_version,
            "responseHttpVersion": flow.response.http_version if flow.response else "",
            "transportProtocol": flow.client_conn.transport_protocol,
            "requestHeaders": list(flow.request.headers.items(multi=True)),
            "requestBodyBase64": encode(flow.request.raw_content),
            "status": flow.response.status_code if flow.response else 409,
            "responseHeaders": list(flow.response.headers.items(multi=True)) if flow.response else [],
            "responseBodyBase64": encode(flow.response.raw_content) if flow.response else ""}


def final_request(flow):
    if CONFIG["scope"] != "CHECKOUT" or flow.request.host != "api.m.jd.com":
        return False
    sources = [urllib.parse.urlsplit(flow.request.url).query]
    try:
        if len(flow.request.raw_content or b"") > MAX_BODY or flow.request.headers.get("content-encoding"):
            return True  # Cannot safely inspect: fail closed in the final-stage endpoint.
        sources.append((flow.request.raw_content or b"").decode("utf-8"))
    except ValueError:
        pass
    return any("SEP_EXCHANGE_SUBMIT" in urllib.parse.parse_qs(s).get("functionId", []) for s in sources)


class Capture:
    def next_layer(self, nextlayer):
        # mitmproxy 12.2.3 can defer forever on an empty post-handshake QUIC
        # buffer with allow_hosts: HTTP/3 sends stream events, not more raw
        # ClientHello bytes. Once h3 is negotiated, select its HTTP layer.
        client = nextlayer.context.client
        if (nextlayer.layer is None and client.transport_protocol == "udp"
                and client.tls_version == "QUICv1" and client.alpn == b"h3"):
            from mitmproxy.proxy.layers.http import HttpLayer, HTTPMode
            nextlayer.layer = HttpLayer(nextlayer.context, HTTPMode.transparent)

    def server_connect(self, data):
        if not CONFIG.get("deviceFixture"):
            return
        host, port = data.server.address
        if host in {"192.0.2.2", "capture-test.invalid"} and port in {18080,18443,18444,18081}:
            data.server.address = ("127.0.0.1",port)
        else:
            data.server.error = "DEVICE_SELF_TEST_LOCAL_ONLY"

    def http_connect(self, flow):
        if CONFIG["isolated"] and flow.request.host not in {"localhost", "127.0.0.1", "::1", "capture-test.invalid", "192.0.2.2"}:
            flow.response = mitm_http.Response.make(403, b"SELF_TEST_LOCAL_ONLY")

    async def request(self, flow):
        if CONFIG["isolated"] and flow.request.host not in {"localhost", "127.0.0.1", "::1", "capture-test.invalid", "192.0.2.2"}:
            flow.response = mitm_http.Response.make(403, b"SELF_TEST_LOCAL_ONLY")
            return
        if final_request(flow):
            result = "CAPTURED_LOCALLY"
            # Install the block before any await; cancellation cannot permit forwarding.
            flow.metadata["final_blocked"] = True
            flow.response = mitm_http.Response.make(409, b"CAPTURE_PENDING")
            try:
                await bridge_async("/final", lambda: flow_record(flow))
            except Exception:
                result = "CAPTURE_SAVE_FAILED"
            flow.metadata["final_blocked"] = True
            flow.response = mitm_http.Response.make(409, json.dumps({"code": result, "finalRequestForwarded": False}),
                                               {"Content-Type": "application/json"})

    async def response(self, flow):
        if flow.metadata.get("final_blocked"):
            return
        try:
            result = await bridge_async("/response", lambda: flow_record(flow, decode=True))
            if result.get("changed"):
                replacement = base64.b64decode(result["bodyBase64"], validate=True)
                if len(replacement) > MAX_BODY:
                    raise ValueError("REPLACEMENT_TOO_LARGE")
                for name in ["content-encoding", "etag", "content-md5"]:
                    flow.response.headers.pop(name, None)
                flow.response.content = replacement
        except Exception as error:
            # Failed bridge never changes a normal response. Final requests are blocked above.
            report_drop(error)
            try:
                await bridge_async("/event", lambda: {"type": "BRIDGE_FAILED", "detail": type(error).__name__})
            except Exception:
                pass

    async def error(self, flow):
        try:
            await bridge_async("/event", {"type": "CONNECTION_ERROR", "host": flow.request.host if flow.request else "",
                              "detail": "mitmproxy upstream/client failure"})
        except Exception:
            pass

    async def running(self):
        # running() is dispatched after listeners are ready.
        if CONFIG.get("deviceFixture"):
            start_device_fixtures()
            await start_device_quic_fixtures()
        if CONFIG.get("mode") == "VPN":
            servers = ctx.master.addons.get("proxyserver").servers
            if not any(server.listen_addrs for server in servers):
                raise RuntimeError("VPN_LISTENER_NOT_READY")
            keys = json.loads((ROOT / "wireguard.json").read_text())
            (ROOT / "vpn-client.json").write_text(json.dumps({
                "clientPrivateKey": keys["client_key"], "serverPublicKey": mitmproxy_rs.wireguard.pubkey(keys["server_key"]),
                "port": CONFIG["port"]}))
        (ROOT / "ready").write_text(str(os.getpid()))
        if CONFIG["isolated"] and CONFIG.get("mode") != "VPN":
            self.selftest_task = asyncio.create_task(asyncio.to_thread(self_test))


class AndroidVpnDns:
    """Resolve the virtual VPN DNS endpoint through Android's existing DNS bridge.

    No external resolver IP is hardcoded. IPv6 and HTTPS/SVCB hints get empty
    answers in the IPv4-only first version, preventing unsupported IPv6 routes.
    """
    async def dns_request(self, flow):
        if flow.server_conn.address != ("10.0.0.53", 53):
            return
        q = flow.request.question
        if not q or q.class_ != dns.classes.IN:
            flow.response = flow.request.fail(dns.response_codes.NOTIMP)
            return
        if q.type != dns.types.A:
            flow.response = flow.request.succeed([])
            return
        if CONFIG.get("deviceFixture"):
            flow.response = flow.request.succeed([dns.ResourceRecord.A(q.name,ipaddress.IPv4Address("192.0.2.2"),ttl=1)]) if q.name == "capture-test.invalid" else flow.request.fail(dns.response_codes.NXDOMAIN)
            return
        try:
            rows = await asyncio.wait_for(asyncio.to_thread(socket.getaddrinfo, q.name, None, socket.AF_INET, socket.SOCK_STREAM), 12)
            addresses = sorted({r[4][0] for r in rows})
            flow.response = flow.request.succeed([dns.ResourceRecord.A(q.name, ipaddress.IPv4Address(ip), ttl=30) for ip in addresses])
        except socket.gaierror as error:
            code = dns.response_codes.NXDOMAIN if error.errno == socket.EAI_NONAME else dns.response_codes.SERVFAIL
            flow.response = flow.request.fail(code)
        except (OSError, TimeoutError):
            flow.response = flow.request.fail(dns.response_codes.SERVFAIL)


def start_device_fixtures():
    """Only the separately signed probe app is routed here. No business endpoints."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    from datetime import datetime, timedelta, timezone
    import socketserver
    pem=(ROOT / "ca/mitmproxy-ca.pem").read_bytes()
    ca=x509.load_pem_x509_certificate(pem); ca_key=serialization.load_pem_private_key(pem,None)
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048); now=datetime.now(timezone.utc)
    cert=(x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,"capture-test.invalid")]))
          .issuer_name(ca.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
          .not_valid_before(now-timedelta(minutes=1)).not_valid_after(now+timedelta(days=1))
          .add_extension(x509.SubjectAlternativeName([x509.DNSName("capture-test.invalid"),x509.IPAddress(ipaddress.IPv4Address("192.0.2.2"))]),False)
          .sign(ca_key,hashes.SHA256()))
    (ROOT / "vpn-fixture.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (ROOT / "vpn-fixture.key").write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    (ROOT / "ca-public.der").write_bytes(ca.public_bytes(serialization.Encoding.DER))
    class Fixture(http.server.BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_GET(self):
            data=b'{"message":"original"}'
            self.send_response(200);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data)
    class Echo(socketserver.BaseRequestHandler):
        def handle(self):
            body,sock=self.request
            if body==b"synthetic-udp":sock.sendto(body,self.client_address)
    for port in (18080,18443):
        server=http.server.ThreadingHTTPServer(("127.0.0.1",port),Fixture);server.daemon_threads=True
        if port==18443:
            tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(ROOT / "vpn-fixture.pem",ROOT / "vpn-fixture.key");server.socket=tls.wrap_socket(server.socket,server_side=True)
        DEVICE_FIXTURES.append(server);threading.Thread(target=server.serve_forever,daemon=True).start()
    udp=socketserver.ThreadingUDPServer(("127.0.0.1",18081),Echo);udp.daemon_threads=True
    DEVICE_FIXTURES.append(udp);threading.Thread(target=udp.serve_forever,daemon=True).start()


async def start_device_quic_fixtures():
    """HTTP/3-only synthetic origins; TCP fallback cannot satisfy this test."""
    from aioquic.asyncio import QuicConnectionProtocol, serve
    from aioquic.h3.connection import H3Connection, H3_ALPN
    from aioquic.h3.events import HeadersReceived
    from aioquic.quic.configuration import QuicConfiguration
    from aioquic.quic.packet import QuicProtocolVersion

    class Http3Fixture(QuicConnectionProtocol):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self.http = H3Connection(self._quic)

        def quic_event_received(self, event):
            for item in self.http.handle_event(event):
                if isinstance(item, HeadersReceived):
                    headers = dict(item.headers)
                    ok = headers.get(b":method") == b"GET" and headers.get(b":path") == b"/capture-selftest"
                    body = b'{"message":"original"}' if ok else b"NOT_FOUND"
                    self.http.send_headers(item.stream_id, [(b":status", b"200" if ok else b"404"),
                        (b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())])
                    self.http.send_data(item.stream_id, body, end_stream=True)
            self.transmit()

    config = QuicConfiguration(is_client=False, alpn_protocols=H3_ALPN,
                               supported_versions=[QuicProtocolVersion.VERSION_1])
    config.load_cert_chain(ROOT / "vpn-fixture.pem", ROOT / "vpn-fixture.key")
    for port in (18443, 18444):
        server = await serve("127.0.0.1", port, configuration=config, create_protocol=Http3Fixture)
        DEVICE_QUIC_SERVERS.append(server)


def self_test():
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    from datetime import datetime, timedelta, timezone
    server = None
    try:
        pem = (ROOT / "ca/mitmproxy-ca.pem").read_bytes()
        ca = x509.load_pem_x509_certificate(pem)
        ca_key = serialization.load_pem_private_key(pem, None)
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")]))
                .issuer_name(ca.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(minutes=1)).not_valid_after(now + timedelta(days=1))
                .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"),
                    x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False).sign(ca_key, hashes.SHA256()))
        (ROOT / "fixture.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        (ROOT / "fixture.key").write_bytes(key.private_bytes(serialization.Encoding.PEM,
                         serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))

        class Fixture(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                payload = b'{"message":"original"}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("X-Capture-Selftest", "1")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        server.daemon_threads = True
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(ROOT / "fixture.pem", ROOT / "fixture.key")
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        client_tls = ssl.create_default_context(cafile=str(ROOT / "ca/mitmproxy-ca.pem"))
        # Legacy app CAs lack SKI. Keep chain and hostname verification; relax RFC-profile
        # strictness only in this loopback fixture client, never for proxy upstream TLS.
        client_tls.verify_flags &= ~ssl.VERIFY_X509_STRICT
        conn = http.client.HTTPSConnection("127.0.0.1", CONFIG["port"], timeout=15, context=client_tls)
        try:
            conn.set_tunnel("localhost", server.server_port)
            conn.request("GET", "/capture-selftest")
            response = conn.getresponse()
            body = json.loads(response.read())
            assert response.status == 200 and body["message"] == "modified-in-apk"
        finally:
            conn.close()
        bridge("/event", {"type": "SELF_TEST_PASS", "detail": "TLS verified; response captured and modified by APK"})
        (ROOT / "selftest.json").write_text(json.dumps({"passed": True, "status": response.status,
            "body": body, "caSha256": ca.fingerprint(hashes.SHA256()).hex(), "at": time.time()}))
    except Exception as error:
        (ROOT / "selftest.json").write_text(json.dumps({"passed": False, "error": type(error).__name__, "at": time.time()}))
        try:
            bridge("/event", {"type": "SELF_TEST_FAILED", "detail": type(error).__name__})
        except Exception as bridge_error:
            print("SELF_TEST_BRIDGE_FAILED", type(bridge_error).__name__, flush=True)
    finally:
        if server:
            server.shutdown()
            server.server_close()


def restore_lease():
    path = ROOT.parent / "owner.json"
    try:
        owner = json.loads(path.read_text())
        if owner.get("session") != ROOT.name:
            return
        settings = "/system/bin/settings"
        current = subprocess.check_output([settings, "get", "global", "http_proxy"], text=True, timeout=5).strip()
        if current == "127.0.0.1:18886":
            previous = owner["previous"]
            if previous in ["", "null"]:
                subprocess.run([settings, "delete", "global", "http_proxy"], timeout=5, check=True)
            elif previous == ":0":
                subprocess.run([settings, "put", "global", "http_proxy", ":0"], timeout=5, check=True)
        # Java owns deletion. Avoid unlinking another session's replacement owner file.
    except (OSError, ValueError, subprocess.SubprocessError):
        pass


def allowed_hosts(scope, isolated):
    if isolated:
        return [r"^(localhost|127\.0\.0\.1):[0-9]+$"]
    return {"TUNNEL": ["(?!)"], "ENTRY": [r"^pro\.m\.jd\.com:443$"],
            "CHECKOUT": [r"^(m-sep\.jd\.com|api\.m\.jd\.com):443$"]}[scope]


async def main():
    if CONFIG.get("deviceFixture"):
        # Synthetic traffic only; keep upstream WireGuard key logging disabled.
        logging.basicConfig(level=logging.INFO, format="%(name)s: %(message)s")
    allow = allowed_hosts(CONFIG["scope"], CONFIG["isolated"])
    if CONFIG.get("deviceFixture"):
        allow = [r"^(capture-test\.invalid|192\.0\.2\.2):(18080|18443|18081)$"]
    vpn = CONFIG.get("mode") == "VPN"
    # Upstream INFO logging includes the client private key. Never emit it to our log UI.
    logging.getLogger("mitmproxy.proxy.mode_servers").setLevel(logging.WARNING)
    modes = ["wireguard:" + str(ROOT / "wireguard.json")] if vpn else ["regular"]
    opts = options.Options(mode=modes, listen_host="127.0.0.1", listen_port=CONFIG["port"],
                           confdir=str(ROOT / "ca"), allow_hosts=allow, ssl_insecure=False)
    master = DumpMaster(opts, with_termlog=False, with_dumper=False)
    if CONFIG["isolated"]:
        opts.update(ssl_verify_upstream_trusted_ca=str(ROOT / "ca/mitmproxy-ca.pem"))
    opts.update(body_size_limit="8m")
    if vpn:
        resolver = master.addons.get("dns_resolver")
        if resolver is not None:
            master.addons.remove(resolver)
        master.addons.add(AndroidVpnDns())
        # The same allow_hosts controls TCP TLS and QUIC SNI. Keep HTTP/3 ALPN
        # handling enabled instead of overriding decrypted UDP with a raw layer.
        opts.update(http3=True, udp_hosts=[])
    master.addons.add(Capture())
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, master.shutdown)

    async def watchdog():
        while True:
            await asyncio.sleep(2)
            try:
                os.kill(CONFIG["parentPid"], 0)
                if time.time() - (ROOT / "heartbeat").stat().st_mtime > 30:
                    raise RuntimeError("LEASE_EXPIRED")
            except (OSError, RuntimeError):
                restore_lease()
                master.shutdown()
                return

    task = asyncio.create_task(watchdog())
    try:
        await master.run()
    finally:
        task.cancel()
        restore_lease()
        for server in DEVICE_QUIC_SERVERS:
            server.close()
        DEVICE_QUIC_SERVERS.clear()
        for fixture in DEVICE_FIXTURES:
            fixture.shutdown(); fixture.server_close()
        DEVICE_FIXTURES.clear()
        (ROOT / "stopped").write_text("stopped")
        for path in list((ROOT / "ca").glob("*")) + [ROOT / "config.json", ROOT / "fixture.key", ROOT / "wireguard.json", ROOT / "vpn-client.json", ROOT / "vpn-fixture.key"]:
            if path.is_file():
                path.unlink(missing_ok=True)


if __name__ == "__main__":
    CONFIG = json.loads(Path(sys.argv[1]).read_text())
    ROOT = Path(sys.argv[1]).parent
    os.umask(0o077)
    asyncio.run(main())
