"""Host/process sampling and optional database-host metric agent."""
from __future__ import annotations

import hmac
import json
import math
import os
import platform
import socket
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

import psutil
import requests

MIB = 1024 ** 2


def normalize_process_cpu(percent, logical_cpus):
    if percent is None or not logical_cpus or not math.isfinite(percent):
        return None
    return min(100.0, max(0.0, percent / logical_cpus))


def counter_cpu(previous, current):
    if not previous or any(k not in previous or k not in current for k in ("BUSY_TIME", "IDLE_TIME")):
        return None
    busy = current["BUSY_TIME"] - previous["BUSY_TIME"]
    idle = current["IDLE_TIME"] - previous["IDLE_TIME"]
    return busy / (busy + idle) * 100 if busy >= 0 and idle >= 0 and busy + idle > 0 else None


def _rate(previous, current, field, seconds):
    if previous is None or current is None or seconds <= 0:
        return None
    delta = getattr(current, field) - getattr(previous, field)
    return delta / seconds / MIB if delta >= 0 else None


class HostSampler:
    def __init__(self, pids):
        self.logical = psutil.cpu_count() or 1
        self.roots = {}
        self.root_times = {}
        self.notes = set()
        for raw_pid in pids:
            pid = int(raw_pid)
            try:
                process = psutil.Process(pid)
                self.roots[pid] = process
                self.root_times[pid] = process.create_time()
            except (psutil.NoSuchProcess, psutil.AccessDenied, OSError):
                self.notes.add(f"İzlenen PID {pid} başlatılırken kapandı veya erişilemedi; süreç metrikleri eksik olabilir.")
        self.processes = {}
        self.last_time = time.monotonic()
        self.last_disk = psutil.disk_io_counters()
        self.last_net = psutil.net_io_counters()
        psutil.cpu_percent(None)
        self._refresh()

    def _refresh(self):
        found = {}
        unavailable = False
        for pid, root in self.roots.items():
            try:
                if root.create_time() != self.root_times[pid] or not root.is_running():
                    raise psutil.NoSuchProcess(pid)
                for p in [root, *root.children(recursive=True)]:
                    key = (p.pid, p.create_time())
                    if key not in self.processes:
                        p.cpu_percent(None)
                    found[key] = self.processes.get(key, p)
            except (psutil.NoSuchProcess, psutil.AccessDenied, OSError):
                unavailable = True
                self.notes.add(f"İzlenen PID {pid} kapandı, değişti veya erişilemiyor; süreç metrikleri eksik olabilir.")
        self.processes = found
        return unavailable

    def sample(self):
        missing = self._refresh()
        now = time.monotonic()
        seconds = now - self.last_time
        disk, net = psutil.disk_io_counters(), psutil.net_io_counters()
        vm = psutil.virtual_memory()
        cpu, rss, private = [], [], []
        for process in self.processes.values():
            try:
                cpu.append(process.cpu_percent(None))
                memory = process.memory_info()
                rss.append(memory.rss / MIB)
                # Windows private bytes are commit, not USS. Other platforms leave this field unmeasured.
                private.append(getattr(memory, "private", None))
            except (psutil.AccessDenied, psutil.NoSuchProcess, OSError):
                missing = True
        result = {
            "hostCpuPercent": psutil.cpu_percent(None),
            "hostMemoryUsedMiB": (vm.total - vm.available) / MIB,
            "hostMemoryAvailableMiB": vm.available / MIB,
            "processCpuPercent": normalize_process_cpu(sum(cpu), self.logical) if cpu and not missing else None,
            "processWorkingSetMiB": sum(rss) if rss and not missing else None,
            "processPrivateMiB": sum(private)/MIB if private and all(p is not None for p in private) and not missing else None,
            "diskReadMiBps": _rate(self.last_disk, disk, "read_bytes", seconds),
            "diskWriteMiBps": _rate(self.last_disk, disk, "write_bytes", seconds),
            "networkReceiveMiBps": _rate(self.last_net, net, "bytes_recv", seconds),
            "networkSendMiBps": _rate(self.last_net, net, "bytes_sent", seconds),
        }
        self.last_time, self.last_disk, self.last_net = now, disk, net
        return result

    def inventory(self):
        model = platform.processor() or ""
        if os.name == "nt":
            try:
                proc = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                                       "(Get-CimInstance Win32_Processor | Select-Object -ExpandProperty Name) -join '; '"],
                                      capture_output=True, text=True, timeout=8,
                                      creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                if proc.returncode == 0:
                    model = proc.stdout.strip()
            except (OSError, subprocess.TimeoutExpired):
                pass
        disks = []
        for partition in psutil.disk_partitions():
            try:
                usage = psutil.disk_usage(partition.mountpoint)
                disks.append(f"{partition.device}: {usage.total/1024**3:.1f} GiB, boş {usage.free/1024**3:.1f} GiB, {partition.fstype}")
            except (PermissionError, OSError):
                pass
        interfaces = [f"{name}: {info.speed} Mbps" for name,info in psutil.net_if_stats().items() if info.isup]
        return {"hostLabel":socket.gethostname(), "roleLocation":"Test istemcisi; günlük işler dahil",
                "os":platform.platform(), "cpu":model, "physicalCores":psutil.cpu_count(logical=False) or "",
                "logicalCores":self.logical, "memoryGiB":round(psutil.virtual_memory().total/1024**3,2),
                "disk":"\n".join(disks), "network":"\n".join(interfaces),
                "version":f"Python {platform.python_version()} · psutil {psutil.__version__}",
                "limits":"CPU yüzdeleri toplam mantıksal CPU kapasitesine göre %0–100; RAM kullanılan=total−available."}


def fetch_agent(url, token):
    parts = urlsplit(url)
    if parts.scheme != "https" and not (parts.scheme == "http" and parts.hostname in ("127.0.0.1", "localhost", "::1")):
        raise ValueError("Metrik ajanı HTTPS veya yerel SSH-tüneli adresi kullanmalı.")
    response = requests.get(url, headers={"Authorization":f"Bearer {token}"}, timeout=3, allow_redirects=False)
    response.raise_for_status()
    result = response.json()
    if not isinstance(result, dict) or not isinstance(result.get("sample"), dict) or not isinstance(result.get("inventory"), dict):
        raise ValueError("Metrik ajanı yanıtı geçersiz.")
    for key in ("hostCpuPercent", "hostMemoryUsedMiB", "hostMemoryAvailableMiB"):
        value = result["sample"].get(key)
        if value is not None and (not isinstance(value,(int,float)) or not math.isfinite(value) or value < 0 or key.endswith("Percent") and value > 100):
            raise ValueError("Metrik ajanı sayısal alanı geçersiz.")
    return result


def serve_agent(port=8765, token=None):
    """Loopback only: expose remotely over an authenticated SSH tunnel, never an open listener."""
    if not token or len(token) < 16:
        raise ValueError("SKY_DIT_AGENT_TOKEN en az 16 karakter olmalı.")
    import threading
    sampler = HostSampler([])
    inventory = sampler.inventory()
    cached = {"inventory":inventory, "sample":{}, "capturedAt":0}
    lock = threading.Lock()
    stop = threading.Event()
    def sample_loop():
        while not stop.wait(1):
            with lock:
                cached.update(sample=sampler.sample(), capturedAt=time.time())
    threading.Thread(target=sample_loop, daemon=True).start()
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/metrics" or not hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {token}"):
                self.send_error(403)
                return
            with lock:
                body = json.dumps(cached, allow_nan=False).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(body)
        def log_message(self, *_args):
            pass
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    try:
        print(f"SKY - DIT metrik ajanı: http://127.0.0.1:{port}/metrics (loopback)", flush=True)
        server.serve_forever()
    finally:
        stop.set(); server.server_close()
