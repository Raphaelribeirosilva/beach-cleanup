#!/usr/bin/env python3
"""Publish verified dumps to a mounted destination; prune only recognized complete pairs."""
import argparse
import datetime as dt
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify(path):
    checksum = path.with_name(path.name + ".sha256")
    if path.is_symlink() or checksum.is_symlink() or not path.is_file() or not checksum.is_file():
        raise ValueError("Backup pair must contain regular files, without symbolic links")
    expected = checksum.read_text(encoding="ascii").split()
    if len(expected) != 2 or expected[1] != path.name or expected[0] != digest(path):
        raise ValueError("Backup checksum mismatch")
    if not path.stat().st_size:
        raise ValueError("Empty backup")
    return expected[0]


def managed(directory, database):
    pattern = re.compile(re.escape(database) + r"-(\d{8}T\d{6}Z)-[A-Za-z0-9]{6}\.sql")
    pairs = []
    for path in directory.iterdir():
        match = pattern.fullmatch(path.name)
        if match:
            timestamp = dt.datetime.strptime(match[1], "%Y%m%dT%H%M%SZ").replace(tzinfo=dt.timezone.utc)
            verify(path)  # Corrupt or incomplete evidence stops pruning, never silently deletes it.
            pairs.append((timestamp, path))
    return sorted(pairs, key=lambda item: (item[0], item[1].name), reverse=True)


def prune(directory, database, days, keep=2, now=None):
    cutoff = (now or dt.datetime.now(dt.timezone.utc)) - dt.timedelta(days=days)
    removed = 0
    for timestamp, path in managed(directory, database)[keep:]:
        if timestamp < cutoff:
            # Both targets are exact, previously verified regular files inside this directory.
            path.unlink()
            path.with_name(path.name + ".sha256").unlink()
            removed += 1
    return removed


def publish(source, destination):
    expected = verify(source)
    target = destination / source.name
    if target.exists() or target.is_symlink():
        if verify(target) != expected:
            raise ValueError("Existing destination differs; refusing overwrite")
        return
    descriptor, partial = tempfile.mkstemp(prefix=".partial-", dir=destination)
    partial = Path(partial)
    marker = destination / (source.name + ".sha256")
    marker_partial = destination / (source.name + ".sha256.partial")
    installed_target = False
    installed_marker = False
    created_partial_marker = False
    try:
        with os.fdopen(descriptor, "wb") as output, source.open("rb") as stream:
            shutil.copyfileobj(stream, output)
            output.flush()
            os.fsync(output.fileno())
        if digest(partial) != expected:
            raise ValueError("Destination verification failed")
        if marker.exists() or marker.is_symlink() or marker_partial.exists() or marker_partial.is_symlink():
            raise ValueError("Unexpected destination checksum marker")
        os.replace(partial, target)
        installed_target = True
        with marker_partial.open("x", encoding="ascii") as output:
            created_partial_marker = True
            output.write(f"{expected}  {target.name}\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(marker_partial, marker)
        installed_marker = True
        verify(target)
    finally:
        partial.unlink(missing_ok=True)
        if installed_target and not installed_marker:
            target.unlink(missing_ok=True)
        if created_partial_marker:
            marker_partial.unlink(missing_ok=True)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("local", type=Path)
    parser.add_argument("offsite", type=Path)
    parser.add_argument("database")
    parser.add_argument("--days", type=int, default=14)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_]+", args.database) or not 2 <= args.days <= 3650:
        parser.error("Invalid database or retention; days must be 2..3650")
    if any(not path.is_absolute() or path.is_symlink() for path in [args.local, args.offsite]):
        parser.error("Use absolute, non-symlink directories")
    local, offsite = args.local.resolve(strict=True), args.offsite.resolve(strict=True)
    if local == offsite or local in offsite.parents or offsite in local.parents:
        parser.error("Local and offsite directories must be separate")
    subprocess.run(["mountpoint", "--quiet", str(offsite)], check=True)
    pairs = managed(local, args.database)
    if not pairs:
        raise ValueError("No complete backups")
    # Publish every retained local pair, including missed transfers after an offsite outage.
    for _, source in pairs:
        publish(source, offsite)
    managed(offsite, args.database)  # Validate every remote pair before any retention deletion.
    removed = prune(local, args.database, args.days) + prune(offsite, args.database, args.days)
    print(f"Backup publication verified; pruned_pairs={removed}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError):
        raise SystemExit("Backup maintenance failed; local recovery data preserved. Inspect protected operational logs.")
