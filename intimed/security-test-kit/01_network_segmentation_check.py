#!/usr/bin/env python3
"""SC-03 / IM-E06-BL-002 – network segmentation validation, run FROM a site network (hospital VLAN, clinic, VPN
client) against the IntiMed device endpoint: device ports accept only mutual TLS (TLS ≥ 1.2, client certificate
required), nothing else is reachable, the console is not reachable from device VLANs, and plaintext MLLP is refused.
Usage: 01_network_segmentation_check.py --host devices.uat.intimed.example.qa [--console-host intimed.example.qa]
       [--expect-console reachable|blocked] [--ports 22,80,443,3389,5432,9443,9444]"""
import argparse, socket, ssl, kitlib as k

S = "01_network_segmentation_check"
ap = argparse.ArgumentParser()
ap.add_argument("--host", required=True)
ap.add_argument("--console-host")
ap.add_argument("--expect-console", choices=["reachable", "blocked"], default="blocked")
ap.add_argument("--ports", default="22,80,443,3389,5432,9443,9444,8080")
ap.add_argument("--timeout", type=float, default=4.0)
a = ap.parse_args()


def tcp_open(host, port):
    try:
        with socket.create_connection((host, port), timeout=a.timeout):
            return True
    except OSError:
        return False


def tls_requires_client_cert(host, port):
    ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    try:
        with socket.create_connection((host, port), timeout=a.timeout) as s, ctx.wrap_socket(s, server_hostname=host) as t:
            proto = t.version()
            try:
                t.sendall(b"\x0bMSH|^~\\&|NETCHECK\r\x1c\r"); data = t.recv(64)
                return False, proto + " accepted data without a client certificate (%d bytes)" % len(data)
            except (ssl.SSLError, OSError) as e:
                return True, proto + " closed without client certificate: " + type(e).__name__
    except ssl.SSLError as e:
        return True, "handshake refused without client certificate: " + str(e)[:80]
    except OSError as e:
        return False, "not reachable: " + str(e)[:80]


for port in (2575, 8443):
    ok, d = tls_requires_client_cert(a.host, port)
    k.check(S, "SC-03", f"device port {port}: reachable and mutual TLS enforced", ok, d)
try:
    with socket.create_connection((a.host, 2575), timeout=a.timeout) as s:
        s.sendall(b"\x0bMSH|^~\\&|PLAINTEXT|NETCHECK\r\x1c\r"); s.settimeout(a.timeout)
        reply = s.recv(64)
        k.check(S, "SC-03", "plaintext MLLP refused on 2575", not reply.startswith(b"\x0bMSH"), reply[:20])
except OSError as e:
    k.check(S, "SC-03", "plaintext MLLP refused on 2575", True, type(e).__name__)
for port in [int(x) for x in a.ports.split(",") if x]:
    k.check(S, "SC-03", f"port {port} not reachable on the device endpoint", not tcp_open(a.host, port))
if a.console_host:
    reach = tcp_open(a.console_host, 443)
    k.check(S, "SC-03", f"console 443 {a.expect_console} from this network", reach == (a.expect_console == "reachable"))
k.finish(S)
