#!/usr/bin/env python3
"""Local readiness and backup-age check; stderr/journal is the collector integration point."""
import argparse
import json
from pathlib import Path
import time
import urllib.request


def check(marker, now=None, fetch=None):
    now = time.time() if now is None else now
    fetch = fetch or urllib.request.urlopen
    failures = []
    try:
        # Fixed loopback endpoint; no arbitrary host, password or token in argv/logs.
        with fetch("http://127.0.0.1:8080/actuator/health/readiness", timeout=10) as response:
            if response.status != 200 or json.load(response).get("status") != "UP":
                failures.append("readiness")
    except (OSError, ValueError):
        failures.append("readiness")
    try:
        if marker.is_symlink():
            raise ValueError()
        age = now - int(marker.read_text(encoding="ascii").strip())
        if age < -300 or age > 26 * 3600:
            failures.append("backup_age")
    except (OSError, ValueError):
        failures.append("backup_age")
    return failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("marker", type=Path)
    args = parser.parse_args()
    failures = check(args.marker)
    print(json.dumps({"service": "beachcleanup", "check": "staging", "status": "ALERT" if failures else "OK", "failures": failures}))
    raise SystemExit(1 if failures else 0)
