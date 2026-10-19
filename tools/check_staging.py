#!/usr/bin/env python3
"""Read-only HTTPS acceptance checks, run separately from allowed and denied networks."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get(url):
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    try:
        with opener.open(urllib.request.Request(url, headers={"User-Agent": "BeachCleanup-Staging-Check"}), timeout=15) as response:
            return response.status, response.read(2 * 1024 * 1024).decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, ""


def certificate_days(host):
    context = ssl.create_default_context()
    with socket.create_connection((host, 443), timeout=15) as connection:
        with context.wrap_socket(connection, server_hostname=host) as secure:
            return (ssl.cert_time_to_seconds(secure.getpeercert()["notAfter"]) - dt.datetime.now(dt.timezone.utc).timestamp()) / 86400


def check(url, access, fetch=get, cert=certificate_days):
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ""
    if parsed.scheme != "https" or parsed.username or parsed.password or parsed.query or parsed.fragment \
            or parsed.path not in ("", "/") or parsed.port not in (None, 443) or "." not in host \
            or host == "example.com" or host.endswith((".example", ".example.com")):
        raise ValueError("Provide the actual canonical HTTPS domain, without credentials, path or query")
    origin = f"https://{host}"
    checks = []
    try:
        days = cert(host)
        checks.append({"name": "tls", "passed": days >= 14, "certificateDaysRemaining": round(days, 1)})
    except (OSError, ValueError, KeyError):
        checks.append({"name": "tls", "passed": False})
    for route, expected in [("/", 200 if access == "allowed" else 403),
                            ("/users", 401 if access == "allowed" else 403),
                            ("/actuator/health/readiness", 403)]:
        try:
            status, body = fetch(origin + route)
            passed = status == expected
            if route == "/" and access == "allowed":
                passed = passed and 'id="workspace"' in body
            checks.append({"name": route, "status": status, "expected": expected, "passed": passed})
        except (OSError, ValueError):
            checks.append({"name": route, "expected": expected, "passed": False})
    return {"timestamp": dt.datetime.now(dt.timezone.utc).isoformat(), "origin": origin,
            "vantage": access, "passed": all(item["passed"] for item in checks), "checks": checks}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url")
    parser.add_argument("--access", choices=["allowed", "blocked"], required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = check(args.url, args.access)
    data = json.dumps(report, indent=2) + "\n"
    if args.output:
        with args.output.open("x", encoding="utf-8") as output:
            output.write(data)
    print(data)
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError):
        raise SystemExit("Staging check failed: check the URL, connectivity and a new output path. No TLS bypass is supported.")
