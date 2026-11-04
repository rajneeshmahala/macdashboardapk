#!/usr/bin/env python3
"""Small, local-network-only telemetry companion for Mac Dashboard APK."""
from __future__ import annotations

import json
import os
import platform
import secrets
import shutil
import socket
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(os.environ.get("MACDASH_PORT", "8765"))
TOKEN_PATH = Path.home() / ".macdashboard-token"
STARTED = time.time()


def run(*args: str) -> str:
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.DEVNULL, timeout=3).strip()
    except Exception:
        return ""


def memory_stats() -> tuple[int, int]:
    try:
        total = int(run("sysctl", "-n", "hw.memsize"))
        page_size = int(run("sysctl", "-n", "hw.pagesize"))
        stats = run("vm_stat")
        pages = {}
        for line in stats.splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                try:
                    pages[key.strip()] = int(value.strip().rstrip("."))
                except ValueError:
                    pass
        free = (pages.get("Pages free", 0) + pages.get("Pages speculative", 0)) * page_size
        inactive = pages.get("Pages inactive", 0) * page_size
        purgeable = pages.get("Pages purgeable", 0) * page_size
        used = max(0, total - free - inactive - purgeable)
        return used, total
    except Exception:
        return 0, 0


def processes() -> list[dict]:
    output = run("ps", "-axo", "pid=,user=,%cpu=,rss=,comm=")
    rows = []
    for line in output.splitlines():
        parts = line.strip().split(None, 4)
        if len(parts) < 5:
            continue
        try:
            pid, user = int(parts[0]), parts[1]
            cpu, rss_kb = float(parts[2]), int(parts[3])
        except ValueError:
            continue
        rows.append({"pid": pid, "user": user, "cpu_percent": cpu, "memory_bytes": rss_kb * 1024, "name": parts[4].split("/")[-1]})
    return sorted(rows, key=lambda row: row["cpu_percent"], reverse=True)[:30]


def network_totals() -> dict:
    received = sent = 0
    output = run("netstat", "-ibn")
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 10 and parts[0] != "Name" and parts[0] != "lo0":
            try:
                received += int(parts[6])
                sent += int(parts[9])
            except ValueError:
                pass
    return {"received_bytes": received, "sent_bytes": sent}


def collect() -> dict:
    # top prints user/system/idle percentages; use 100 - idle for total load.
    cpu = 0.0
    try:
        cpu_line = next(line for line in run("top", "-l", "1", "-n", "0").splitlines() if line.startswith("CPU usage:"))
        idle = float(cpu_line.split("idle", 1)[0].rsplit(",", 1)[-1].replace("%", "").strip())
        cpu = max(0.0, min(100.0, 100.0 - idle))
    except Exception:
        pass
    used, total = memory_stats()
    disk = shutil.disk_usage("/")
    return {
        "hostname": socket.gethostname().split(".")[0],
        "os_version": f"macOS {platform.mac_ver()[0]}",
        "uptime_seconds": int(time.time() - STARTED),
        "cpu_percent": round(cpu, 1),
        "cpu_count": os.cpu_count() or 0,
        "memory_used_bytes": used,
        "memory_total_bytes": total,
        "memory_percent": round((used / total * 100) if total else 0, 1),
        "disks": [{"mount": "/", "used_bytes": disk.used, "total_bytes": disk.total, "percent": round(disk.used / disk.total * 100, 1) if disk.total else 0}],
        "network": network_totals(),
        "processes": processes(),
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/api/v1/metrics":
            return self.send_error(404)
        if not secrets.compare_digest(self.headers.get("Authorization", ""), f"Bearer {TOKEN}"):
            return self.send_error(401, "pairing token required")
        body = json.dumps(collect()).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} - {fmt % args}")


if TOKEN_PATH.exists():
    TOKEN = TOKEN_PATH.read_text().strip()
else:
    TOKEN = secrets.token_urlsafe(32)
    TOKEN_PATH.write_text(TOKEN + "\n")
    TOKEN_PATH.chmod(0o600)


if __name__ == "__main__":
    lan_ip = "127.0.0.1"
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("192.0.2.1", 80))
        lan_ip = sock.getsockname()[0]
        sock.close()
    except Exception:
        pass
    print("Mac Dashboard companion is running")
    print(f"Phone address: {lan_ip}:{PORT}")
    print(f"Pairing token: {TOKEN}")
    print("Keep the token private. Press Ctrl+C to stop.")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
