"""Render the existing offline report contract; replace output files atomically."""
import json
import os
import re
import tempfile
import time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "docs/testing/performance/rapor.html"
DATA_SCRIPT = re.compile(r'(<script id="report-data" type="application/json">)(.*?)(</script>)', re.S)
STAGES = (100000, 250000, 500000, 1000000, 2500000, 5000000)


def now():
    return datetime.now(ZoneInfo("Europe/Istanbul")).isoformat(timespec="seconds")


def blank_report():
    match = DATA_SCRIPT.search(TEMPLATE.read_text(encoding="utf-8"))
    if not match:
        raise ValueError("HTML veri bloğu bulunamadı.")
    data = json.loads(match.group(2))
    data["runs"] = []
    data["findings"] = []
    data["report"].update(id="SKY-DIT-UAT", title="SKY - DIT · Data Integration Tool", testedAt="", notes="")
    data["collection"] = {"state":"ready", "notes":[], "sources":{}}
    return data


def atomic_write(path: Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", dir=path.parent, suffix=".tmp", delete=False) as f:
            temporary = Path(f.name)
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        # Windows may briefly deny replacing a report that is being scanned or
        # read by a browser.  Report persistence must not abort an otherwise
        # healthy transfer measurement, so give the replace a short bounded
        # retry window before surfacing a real write failure.
        for attempt in range(8):
            try:
                os.replace(temporary, path)
                temporary = None
                break
            except PermissionError:
                if attempt == 7:
                    raise
                time.sleep(0.25 * (attempt + 1))
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


def write_report(output: Path, report):
    output = Path(output)
    raw = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
    embedded = raw.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
    template = TEMPLATE.read_text(encoding="utf-8")
    html, count = DATA_SCRIPT.subn(lambda m: m.group(1) + "\n" + embedded + "\n" + m.group(3), template, count=1)
    if count != 1:
        raise ValueError("HTML veri bloğu bulunamadı.")
    jp, hp = output / "sky-dit-results.json", output / "sky-dit-report.html"
    atomic_write(jp, raw + "\n")
    atomic_write(hp, html)
    return jp, hp
