#!/usr/bin/env python3
"""Fetch selected third_party/externals from skia/DEPS.

googlesource.com is often unreachable from this network; rewrite known
chromium/skia mirrors to the corresponding GitHub repos before fetch.
"""
import os
import re
import subprocess
import sys
import time

DEPS = open(os.path.join(os.environ.get("SKIA_MINGW_WORK", "."), "skia/DEPS")).read()
pairs = re.findall(
    r'"(third_party/externals/[a-zA-Z0-9_.\-]+)"\s*:\s*"([^"]+@[0-9a-f]{40})"',
    DEPS,
)
want = set(sys.argv[1:])
sel = [(p, u.rsplit("@", 1)) for p, u in pairs if p.split("/")[-1] in want]
base = os.path.join(os.environ.get("SKIA_MINGW_WORK", "."), "skia")
env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
# Prefer direct GitHub; a dead ALL_PROXY (e.g. 127.0.0.1:7897) breaks fetch.
for k in ("ALL_PROXY", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    env.pop(k, None)


def github_mirror(url: str) -> str:
    """Map chromium/skia googlesource mirrors → github.com when possible."""
    m = re.match(
        r"https://(?:chromium|skia)\.googlesource\.com/external/github\.com/(.+?)(?:\.git)?$",
        url,
    )
    if m:
        return f"https://github.com/{m.group(1)}.git"
    return url


def fetch(item):
    path, (url, rev) = item
    url = github_mirror(url)
    full = os.path.join(base, path)
    os.makedirs(full, exist_ok=True)

    def run(cmd):
        return subprocess.run(cmd, cwd=full, env=env, capture_output=True, text=True)

    if (
        os.path.exists(os.path.join(full, ".git"))
        and subprocess.run(
            ["git", "rev-parse", "--verify", "-q", "HEAD"],
            cwd=full,
            capture_output=True,
        ).returncode
        == 0
    ):
        return f"SKIP {path} (已存在)"
    if not os.path.exists(os.path.join(full, ".git")):
        run(["git", "init", "-q"])
        run(["git", "remote", "add", "origin", url])
    last = ""
    for attempt in range(1, 7):
        run(["git", "remote", "set-url", "origin", url])
        r = run(["git", "fetch", "--depth", "1", "-q", "origin", rev])
        if r.returncode == 0:
            c = run(["git", "checkout", "-q", "FETCH_HEAD"])
            if c.returncode == 0:
                return f"OK   {path} <- {url} (第{attempt}次)"
            last = c.stderr.strip()[:120]
        else:
            err = r.stderr.strip().splitlines()
            last = err[-1][:120] if err else "unknown"
        time.sleep(3)
    return f"FAIL {path}: {last}"


for item in sel:
    print(fetch(item), flush=True)
