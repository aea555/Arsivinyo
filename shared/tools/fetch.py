#!/usr/bin/env python3
"""Fetches a pinned file over parallel ranged requests and checks its SHA-256.

Release servers (GitHub's, Boost's) can hold one connection to ~100 KB/s; sixteen at once
are about twenty times faster. A server that does not take ranges is read in one go.

    fetch.py URL SHA256 DESTINATION
"""
import concurrent.futures
import hashlib
import os
import sys
import urllib.request

PART = 2 * 1024 * 1024


def fetch(url: str, sha256: str, path: str) -> None:
    with urllib.request.urlopen(urllib.request.Request(url, method="HEAD")) as r:
        final = r.url
        size = int(r.headers.get("Content-Length") or 0)
        ranges = r.headers.get("Accept-Ranges", "").lower() == "bytes" and size > 0
    temp = path + ".part"
    if not ranges:
        with urllib.request.urlopen(final, timeout=120) as r, open(temp, "wb") as out:
            while chunk := r.read(1 << 20):
                out.write(chunk)
    else:
        def part(start: int):
            end = min(size, start + PART) - 1
            for _ in range(5):
                try:
                    req = urllib.request.Request(final, headers={"Range": f"bytes={start}-{end}"})
                    with urllib.request.urlopen(req, timeout=60) as r:
                        data = r.read()
                    if len(data) == end - start + 1:
                        return start, data
                except OSError:
                    pass
            raise RuntimeError(f"could not fetch bytes {start}-{end} of {url}")

        with open(temp, "wb") as out, concurrent.futures.ThreadPoolExecutor(16) as pool:
            out.truncate(size)
            for start, data in pool.map(part, range(0, size, PART)):
                out.seek(start)
                out.write(data)
    digest = hashlib.sha256()
    with open(temp, "rb") as f:
        while chunk := f.read(1 << 20):
            digest.update(chunk)
    if digest.hexdigest() != sha256:
        os.remove(temp)
        sys.exit(f"{url}: checksum {digest.hexdigest()} is not the pinned {sha256}")
    os.replace(temp, path)


if __name__ == "__main__":
    fetch(*sys.argv[1:4])
