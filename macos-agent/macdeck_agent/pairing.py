"""What a phone needs to pair: a token, the TLS certificate's fingerprint, and an address."""
import base64
import hashlib
import json
import os
import secrets
import socket
import ssl
import subprocess

from . import config


def load_or_create_token():
    if config.TOKEN_PATH.exists():
        return config.TOKEN_PATH.read_text().strip()
    token = secrets.token_urlsafe(16)
    config.TOKEN_PATH.write_text(token)
    os.chmod(config.TOKEN_PATH, 0o600)
    return token


def load_or_create_cert():
    """Self-signed TLS certificate. The phone pins its fingerprint from the QR code,
    so no CA is involved and the LAN IP never needs to match the certificate."""
    if not (config.CERT_PATH.exists() and config.KEY_PATH.exists()):
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
             "-subj", "/CN=MacDeck", "-keyout", str(config.KEY_PATH), "-out", str(config.CERT_PATH)],
            check=True, capture_output=True,
        )
        os.chmod(config.KEY_PATH, 0o600)
    der = ssl.PEM_cert_to_DER_cert(config.CERT_PATH.read_text())
    return base64.urlsafe_b64encode(hashlib.sha256(der).digest()).decode().rstrip("=")


def tls_context():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(config.CERT_PATH, config.KEY_PATH)
    return ctx


def payload(host, token, cert_fp):
    """The pairing JSON: in the QR code, or handed over USB."""
    return json.dumps({"h": host, "p": config.PORT, "t": token, "f": cert_fp}, separators=(",", ":"))


def interface_ip(iface):
    result = subprocess.run(["ipconfig", "getifaddr", iface], capture_output=True, text=True)
    return result.stdout.strip() or None


def lan_ip():
    """The Mac's Wi-Fi/Ethernet address, which is what a phone on the same network can reach.

    A VPN (utun*) can own the default route or the 10.x routes; its address is useless to
    the phone, so prefer the interface of the default route only if it's en*, then any en*.
    """
    if os.environ.get("MACDECK_HOST"):
        return os.environ["MACDECK_HOST"]
    route = subprocess.run(["route", "-n", "get", "default"], capture_output=True, text=True).stdout
    default_iface = next((l.split(":")[1].strip() for l in route.splitlines() if "interface:" in l), "")
    candidates = ([default_iface] if default_iface.startswith("en") else []) + [f"en{i}" for i in range(8)]
    for iface in candidates:
        ip = interface_ip(iface)
        if ip:
            return ip
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def show_pairing(ip, token, cert_fp):
    print("\nScan this with the MacDeck app to pair:\n")
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(payload(ip, token, cert_fp))
        qr.print_ascii(invert=True)
    except ImportError:
        pass
    print(f"\nOr enter manually  →  host: {ip}   port: {config.PORT}   token: {token}")
    print(f"                       certificate: {cert_fp}\n")
