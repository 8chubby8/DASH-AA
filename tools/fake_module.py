#!/usr/bin/env python3
"""A WiFi ACCESSORY module with no hardware — for benching DASH-AA (or upstream DASH) on a laptop.

    python3 tools/fake_module.py                       # the Climate module, to localhost:3274
    python3 tools/fake_module.py --host 192.168.1.20   # a DASH somewhere else

It speaks the locked module SDK exactly as the DashModule/DashAccessory library does
(module-sdk.md): HELLO on DISCOVER; on INSTALL, MANIFEST then each asset as a length-prefixed,
CRC32-checked BLOCK (lowercase hex, no padding — Arduino's String(crc, HEX)) then INSTALL_END;
ROGER on every ACTIVATE but the state dump only on the real SILENT→ACTIVE transition (§6);
a REPORT for every variable on activation and every 5 s; RESEND answered; ACTION applied and
reported back. It goes SILENT if the socket drops and redials, as a wireless module must.

The assets are read from a real sketch folder — by default the Climate sketch in the upstream
repo — in that sketch's own make_assets.py ORDER, so the bytes DASH receives are the same bytes
the board would send.
"""
import argparse
import re
import socket
import threading
import time
import zlib
from pathlib import Path

UPSTREAM = Path(__file__).resolve().parents[2] / "dash" / "arduino" / "current_sketches"

PROFILES = {
    "climate": dict(
        sketch=UPSTREAM / "ClimateWifi", id="0000DA58AC04", name="Climate",
        desc="Single-zone cabin climate", version="v2.0",
        state={"fan": "2", "temp": "21", "mode": "feet", "auto": "off", "recirc": "off",
               "ac": "off", "screen": "off", "seat_left": "off", "seat_right": "off"},
    ),
    "gauge": dict(
        sketch=UPSTREAM / "GaugeWifi", id="0000DA58AC05", name="Tank Gauge",
        desc="Air tank pressure", version="v1.3",
        state={"tank_pressure": "6.5"},
    ),
}


def assets_in_order(sketch: Path):
    """(block name, bytes) in the sketch's own make_assets.py ORDER."""
    script = (sketch / "make_assets.py").read_text()
    order = re.findall(r'"([^"]+\.(?:json|svg|png))"', script.split("ORDER", 1)[1].split("]", 1)[0])
    out = []
    for filename in order:
        data = (sketch / "assets" / filename).read_bytes()
        name = filename[:-5] if filename.endswith(".json") else filename
        out.append((name, data))
    return out


class FakeModule:
    def __init__(self, profile, host, port):
        self.p = profile
        self.host, self.port = host, port
        self.state = dict(profile["state"])
        self.assets = assets_in_order(profile["sketch"])
        self.active = False
        self.sock = None
        self.lock = threading.Lock()

    def send(self, line: str):
        with self.lock:
            self.sock.sendall((line + "\r\n").encode())

    def send_block(self, name, data):
        with self.lock:
            crc = "%x" % (zlib.crc32(data) & 0xFFFFFFFF)
            self.sock.sendall(f"BLOCK|{self.p['id']}|{name}|{len(data)}|{crc}\r\n".encode())
            self.sock.sendall(data)

    def report_all(self):
        for k, v in self.state.items():
            self.send(f"REPORT|{self.p['id']}|{k}|{v}")

    def handle(self, line):
        f = line.split("|")
        if f[0] == "DISCOVER":
            self.send(f"HELLO|{self.p['id']}|ACCESSORY|{self.p['name']}|{self.p['desc']}|{self.p['version']}")
            return
        if len(f) < 2 or f[1] != self.p["id"]:
            return
        if f[0] == "INSTALL":
            total = sum(len(d) for _, d in self.assets)
            self.send(f"MANIFEST|{self.p['id']}|{len(self.assets)}|{total}")
            for name, data in self.assets:
                self.send_block(name, data)
            self.send(f"INSTALL_END|{self.p['id']}")
            print(f"installed: {len(self.assets)} blocks, {total} bytes")
        elif f[0] == "RESEND" and len(f) >= 3:
            for name, data in self.assets:
                if name == f[2]:
                    self.send_block(name, data)
        elif f[0] == "ACTIVATE":
            was = self.active
            self.active = True
            self.send(f"ROGER|{self.p['id']}|activate")
            if not was:
                print("ACTIVE")
                self.report_all()
        elif f[0] == "DEACTIVATE":
            self.active = False
            self.send(f"ROGER|{self.p['id']}|deactivate")
            print("SILENT")
        elif f[0] == "ACTION" and len(f) >= 3:
            control = f[2]
            value = f[3] if len(f) > 3 else None
            print(f"ACTION {control} = {value}")
            if value is not None and control in self.state:
                self.state[control] = value
                self.send(f"REPORT|{self.p['id']}|{control}|{value}")

    def heartbeat(self):
        while True:
            time.sleep(5)
            if self.active and self.sock:
                try:
                    self.report_all()
                except OSError:
                    pass

    def run(self):
        threading.Thread(target=self.heartbeat, daemon=True).start()
        while True:
            try:
                self.sock = socket.create_connection((self.host, self.port), timeout=5)
                self.sock.settimeout(None)
                print(f"connected to DASH at {self.host}:{self.port} as {self.p['name']} ({self.p['id']})")
                buf = b""
                while True:
                    chunk = self.sock.recv(4096)
                    if not chunk:
                        raise OSError("closed")
                    buf += chunk
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        line = line.decode(errors="replace").strip("\r")
                        if line:
                            self.handle(line)
            except OSError as e:
                if self.active:
                    print("link lost — SILENT")
                self.active = False
                time.sleep(2)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--profile", choices=PROFILES, default="climate")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3274)
    a = ap.parse_args()
    FakeModule(PROFILES[a.profile], a.host, a.port).run()
