#!/usr/bin/env python3
"""Push the demo-app stress image + compose to a remote Linux host and start the stack.

Uses Paramiko for SSH/SFTP and the local Docker CLI for save/load. No secret is
stored here: password comes from --password or DEPLOY_SSH_PASSWORD.

Example:
    $env:DEPLOY_SSH_PASSWORD = "***"
    python deploy_remote.py --host 172.16.1.108 --user root \
        --remote-dir /root/_demo_app_sw_stress

Env fallbacks: DEPLOY_SSH_HOST, DEPLOY_SSH_USER, DEPLOY_SSH_PASSWORD, DEPLOY_SSH_PORT.

What it does:
  1. docker save <image> | gzip  -> local temp tar.gz
  2. render an image-only compose (drops the local build context, points the
     settings bind-mount at ./settings.xml)
  3. sftp upload tar.gz + docker-compose.yaml + settings.xml
  4. ssh: docker load, docker compose up -d, wait healthy, tail stress logs
"""
from __future__ import annotations

import argparse
import gzip
import os
import posixpath
import shlex
import subprocess
import sys
import tempfile
import time

import paramiko
import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_COMPOSE = os.path.normpath(os.path.join(HERE, "..", "docker-compose.yaml"))
DEFAULT_SETTINGS = os.path.normpath(os.path.join(HERE, "..", "..", "settings.xml"))


def local_image_exists(image: str) -> bool:
    rc = subprocess.call(["docker", "image", "inspect", image],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return rc == 0


def build_tarball(image: str, out_path: str) -> None:
    print(f"[local] docker save {image} | gzip -> {out_path}")
    with gzip.open(out_path, "wb", compresslevel=6) as gz:
        proc = subprocess.Popen(["docker", "save", image],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        assert proc.stdout is not None
        while True:
            chunk = proc.stdout.read(4 * 1024 * 1024)
            if not chunk:
                break
            gz.write(chunk)
        _, err = proc.communicate()
        if proc.returncode != 0:
            raise RuntimeError(f"docker save failed: {err.decode(errors='replace')}")
    size_mb = os.path.getsize(out_path) / 1024 / 1024
    print(f"[local] tarball ready: {size_mb:.0f} MB")


def render_remote_compose(src: str, dst: str) -> None:
    with open(src, "r", encoding="utf-8") as f:
        doc = yaml.safe_load(f)
    doc["services"]["demo-app"].pop("build", None)
    stress = doc["services"]["stress"]
    stress["volumes"] = [
        (v.replace("../settings.xml", "./settings.xml") if isinstance(v, str) else v)
        for v in stress.get("volumes", [])
    ]
    with open(dst, "w", encoding="utf-8") as f:
        yaml.safe_dump(doc, f, allow_unicode=True, sort_keys=False, default_flow_style=False)
    print(f"[local] rendered remote compose -> {dst}")


def ssh_connect(host: str, port: int, user: str, password: str) -> paramiko.SSHClient:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(host, port=port, username=user, password=password,
                   timeout=20, banner_timeout=30, auth_timeout=30)
    return client


def run(client: paramiko.SSHClient, cmd: str, check: bool = True) -> tuple[int, str]:
    print(f"[remote] $ {cmd}")
    chan = client.get_transport().open_session()
    chan.settimeout(3600)
    chan.exec_command(cmd)
    out = bytearray()
    while True:
        got = False
        if chan.recv_ready():
            data = chan.recv(65536)
            out += data
            sys.stdout.write(data.decode(errors="replace"))
            got = True
        if chan.recv_stderr_ready():
            data = chan.recv_stderr(65536)
            out += data
            sys.stdout.write(data.decode(errors="replace"))
            got = True
        if chan.exit_status_ready() and not chan.recv_ready() and not chan.recv_stderr_ready():
            break
        if not got:
            time.sleep(0.05)
    status = chan.recv_exit_status()
    if check and status != 0:
        raise RuntimeError(f"remote command failed ({status}): {cmd}")
    return status, out.decode(errors="replace")


def upload(sftp: paramiko.SFTPClient, local: str, remote: str) -> None:
    total = os.path.getsize(local)
    t0 = time.time()
    state = {"last": 0.0}

    def cb(done: int, _total: int) -> None:
        now = time.time()
        if done == total or now - state["last"] >= 2:
            state["last"] = now
            mb = done / 1024 / 1024
            speed = mb / max(now - t0, 1e-9)
            pct = done * 100 // total if total else 100
            print(f"\r  -> {posixpath.basename(remote)}: {pct}% "
                  f"({mb:.0f}/{total / 1024 / 1024:.0f} MB, {speed:.1f} MB/s)",
                  end="", flush=True)

    print(f"[sftp] upload {local} -> {remote}")
    sftp.put(local, remote, callback=cb)
    print()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default=os.environ.get("DEPLOY_SSH_HOST"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("DEPLOY_SSH_PORT", "22")))
    ap.add_argument("--user", default=os.environ.get("DEPLOY_SSH_USER", "root"))
    ap.add_argument("--password", default=os.environ.get("DEPLOY_SSH_PASSWORD"))
    ap.add_argument("--image", default="demo-app-agent:9.4.0")
    ap.add_argument("--remote-dir", default="/root/_demo_app_sw_stress")
    ap.add_argument("--compose", default=DEFAULT_COMPOSE)
    ap.add_argument("--settings", default=DEFAULT_SETTINGS)
    ap.add_argument("--skip-load", action="store_true", help="skip docker save/upload/load")
    ap.add_argument("--keep-tarball", action="store_true", help="keep local temp tar.gz")
    args = ap.parse_args()

    if not args.host or not args.password:
        ap.error("--host and --password (or DEPLOY_SSH_HOST/DEPLOY_SSH_PASSWORD) are required")
    for path in (args.compose, args.settings):
        if not os.path.isfile(path):
            ap.error(f"file not found: {path}")

    tarball = os.path.join(tempfile.gettempdir(), args.image.replace(":", "-").replace("/", "_") + ".tar.gz")
    remote_tar = posixpath.join(args.remote_dir, os.path.basename(tarball))
    client = None
    try:
        if not args.skip_load:
            if not local_image_exists(args.image):
                print(f"[FAIL] local image not found: {args.image}", file=sys.stderr)
                return 1
            build_tarball(args.image, tarball)

        compose_tmp = os.path.join(tempfile.gettempdir(), "docker-compose.remote.yaml")
        render_remote_compose(args.compose, compose_tmp)

        print(f"[ssh] connecting {args.user}@{args.host}:{args.port}")
        client = ssh_connect(args.host, args.port, args.user, args.password)
        run(client, f"mkdir -p {shlex.quote(args.remote_dir)}")
        run(client, "uname -m && docker --version", check=False)

        compose_cmd = "docker compose"
        status, _ = run(client, "docker compose version >/dev/null 2>&1", check=False)
        if status != 0:
            status, _ = run(client, "docker-compose version >/dev/null 2>&1", check=False)
            if status != 0:
                print("[FAIL] neither 'docker compose' nor 'docker-compose' available", file=sys.stderr)
                return 1
            compose_cmd = "docker-compose"
        print(f"[remote] compose command: {compose_cmd}")

        sftp = client.open_sftp()
        try:
            if not args.skip_load:
                upload(sftp, tarball, remote_tar)
            upload(sftp, compose_tmp, posixpath.join(args.remote_dir, "docker-compose.yaml"))
            upload(sftp, args.settings, posixpath.join(args.remote_dir, "settings.xml"))
        finally:
            sftp.close()

        if not args.skip_load:
            run(client, f"docker load -i {shlex.quote(remote_tar)}")

        d = shlex.quote(args.remote_dir)
        run(client, f"cd {d} && {compose_cmd} -f docker-compose.yaml up -d")

        deadline = time.time() + 240
        healthy = False
        while time.time() < deadline:
            _, ps = run(client, f"cd {d} && {compose_cmd} ps", check=False)
            if "(healthy)" in ps:
                healthy = True
                break
            time.sleep(10)
        print(f"[remote] demo-app healthy: {healthy}")

        time.sleep(30)
        run(client, f"cd {d} && {compose_cmd} logs --tail 20 stress", check=False)
        print(f"[OK] deployed to {args.user}@{args.host}:{args.remote_dir} (image {args.image})")
        return 0
    finally:
        if client is not None:
            client.close()
        if not args.keep_tarball and os.path.exists(tarball):
            os.remove(tarball)
            print(f"[local] removed temp {tarball}")


if __name__ == "__main__":
    sys.exit(main())
