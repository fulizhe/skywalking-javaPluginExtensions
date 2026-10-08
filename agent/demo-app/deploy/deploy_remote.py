#!/usr/bin/env python3
"""Push the demo-app stress image + compose to a remote Linux host and start the stack.

Uses Paramiko for SSH/SFTP and the local Docker CLI for save/load. No secret is
stored here: password comes from --password or DEPLOY_SSH_PASSWORD.

Example:
    $env:DEPLOY_SSH_PASSWORD = "***"
    python deploy_remote.py --host 172.16.1.108 --user root \
        --remote-dir /root/_demo_app_sw_stress

Env fallbacks: DEPLOY_SSH_HOST, DEPLOY_SSH_USER, DEPLOY_SSH_PASSWORD, DEPLOY_SSH_PORT.

Profiles (--profile, 可重复; 默认 `stress`):
  demo-app 不带 profile; `stress` / `stress-slow` / `deps` 各自收在 profile 里。
  远端按需指定, 如 `--profile stress --profile deps`; `--profile none` 只起 demo-app。

Stress paths / threads (--stress-paths, --stress-threads):
  `--stress-paths` 默认 `compose` = 沿用 compose 里的值(`stress` 档刻意只压 ms 级 2xx 路径,
  理由见 compose 注释); 传 `full` 用本文件的全压列表(含 /error、/http500, 会触发同步 webhook
  自环), 也可直接传逗号分隔的路径列表。**只作用于 `stress` 服务**, `stress-slow` 的慢端点
  清单是它自己的设计, 不受影响。
  `--stress-threads` 覆盖 `stress` 档线程数(默认 0 = 沿用 compose 的 32)。线程越多、对
  demo-app 的内存压力越大 —— 108 上曾因 32 线程把 demo-app 顶到 `mem_limit` 上限触发
  cgroup OOM 重启, 故该机用 16。

Deps on remote (--deps-ports):
  远端 deps(redis/mysql/kafka)只给 compose 内部用, 故默认**剥掉宿主端口映射**;
  加 `--deps-ports` 才对外发布(本机调试用)。

Periodic slow round (--stress-slow-interval):
  `stress-slow` 是有限请求、打完即退(repo 里那份是给本地手动核对指标准确性用的)。
  远端传该参数(秒) > 0 时, 把命令包成 `while true; do <原命令>; sleep N; done` 并把
  `restart` 改成 `unless-stopped`, 让慢档按周期反复跑。
  注意: 一轮约 3.3min(240 请求 / 4 线程 / 端点均耗时 3.3s), 周期必须大于一轮时长;
  建议 420(≈7min), 实测约 10min 一轮、每端点 40 个样本(分位可用的下限)。

What it does:
  1. docker save <image> | gzip  -> local temp tar.gz
  2. render an image-only compose (drops the local build context, points the
     settings bind-mount at ./settings.xml)
  3. sftp upload tar.gz + docker-compose.yaml + settings.xml
  4. ssh: docker load, docker compose --profile <..> up -d, wait healthy, tail logs
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

# 全压列表(含错误/告警路径): 会触发插件**同步 webhook 回打自身**形成放大环路,
# 且 5xx 响应带 Connection: close 会泄漏 TCP 连接 —— 只适合"要连告警链路一起压"的场合,
# 不是常规档默认值。常规档默认沿用 compose 的 ms 级 2xx 路径集(见 --stress-paths)。
STRESS_PATHS_FULL = ("/hello,/fullSample,/queryDbByMybatis,/queryDbByJdbc,"
                      "/api/trace-alert-demo/error,/api/trace-alert-demo/http500,/longTimeTask")
LOADTEST_PATHS_PREFIX = "-Dloadtest.paths="
LOADTEST_THREADS_PREFIX = "-Dloadtest.threads="
SETTINGS_LOCAL_MOUNT = "../settings.xml"
SETTINGS_REMOTE_MOUNT = "./settings.xml"
# 远端 deps(redis/mysql/kafka)只给 compose 内网用, 按 profile 识别后剥掉宿主端口
DEPS_PROFILE = "deps"


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


def set_loadtest_arg(command: list, prefix: str, value: str) -> bool:
    """把 command 里的 <prefix>... 覆盖成 <prefix><value>；没有就追加。返回是否发生了覆盖。"""
    if not isinstance(command, list):
        return False
    for i, arg in enumerate(command):
        if isinstance(arg, str) and arg.startswith(prefix):
            command[i] = prefix + value
            return True
    command.append(prefix + value)
    return False


def wrap_command_loop(command: list, interval_sec: int) -> list:
    """把命令包成 `while true; do <命令>; sleep N; done`, 供远端慢档周期跑。"""
    inner = " ".join(shlex.quote(str(a)) for a in command)
    return ["sh", "-c", f"while true; do {inner}; sleep {interval_sec}; done"]


def render_remote_compose(src: str, dst: str, stress_paths: str | None = None,
                          deps_ports: bool = False, slow_interval_sec: int = 0,
                          stress_threads: int = 0) -> None:
    with open(src, "r", encoding="utf-8") as f:
        doc = yaml.safe_load(f)
    services = doc["services"]
    services["demo-app"].pop("build", None)
    # settings 挂载: 本地是 ../settings.xml(相对 agent/demo-app), 远端只有 ./settings.xml
    for name, svc in services.items():
        if not isinstance(svc, dict) or "volumes" not in svc:
            continue
        svc["volumes"] = [
            (v.replace(SETTINGS_LOCAL_MOUNT, SETTINGS_REMOTE_MOUNT) if isinstance(v, str) else v)
            for v in svc["volumes"]
        ]
    # 远端 deps 只在内网用: 默认剥掉宿主端口映射(9600/8092 是 demo-app 的, 不动)
    if not deps_ports:
        stripped = [n for n, s in services.items()
                    if isinstance(s, dict) and DEPS_PROFILE in (s.get("profiles") or []) and "ports" in s]
        for n in stripped:
            services[n].pop("ports", None)
        print(f"[local] deps ports NOT published on host: {', '.join(stripped) or '(none)'}")
    # 压测路径覆盖(只作用于 stress 服务; stress-slow 的慢端点清单是它自己的设计)
    if stress_paths and "stress" in services:
        applied = set_loadtest_arg(services["stress"].get("command", []), LOADTEST_PATHS_PREFIX, stress_paths)
        print(f"[local] stress paths -> {stress_paths}"
              f"{'' if applied else '  (WARN: compose 里没有 -Dloadtest.paths, 已追加)'}")
    # 线程数覆盖(同上, 只作用于 stress): 线程越多对 demo-app 的内存压力越大
    if stress_threads > 0 and "stress" in services:
        applied = set_loadtest_arg(services["stress"].get("command", []), LOADTEST_THREADS_PREFIX,
                                   str(stress_threads))
        print(f"[local] stress threads -> {stress_threads}"
              f"{'' if applied else '  (WARN: compose 里没有 -Dloadtest.threads, 已追加)'}")
    # 周期慢档: repo 里那份是一次性(本地手动核对用), 远端包成循环
    if slow_interval_sec > 0 and "stress-slow" in services:
        slow = services["stress-slow"]
        slow["command"] = wrap_command_loop(slow.get("command", []), slow_interval_sec)
        slow["restart"] = "unless-stopped"
        print(f"[local] stress-slow -> loop every ~{slow_interval_sec}s "
              f"(+ ~200s per round, restart=unless-stopped)")
    with open(dst, "w", encoding="utf-8") as f:
        yaml.safe_dump(doc, f, allow_unicode=True, sort_keys=False, default_flow_style=False)
    print(f"[local] rendered remote compose -> {dst}")
    return doc


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
    ap.add_argument("--profile", action="append", dest="profiles", default=None,
                    metavar="NAME", help="要启动的 compose profile(可重复); 默认 stress。"
                                         "传 none 只起 demo-app")
    ap.add_argument("--stress-paths", default="compose",
                    help="stress 档压测路径: 'compose'(默认,沿用 compose 的 ms 级 2xx 集合) / "
                         "'full'(全压,含 /error、/http500) / 逗号分隔的路径列表")
    ap.add_argument("--deps-ports", action="store_true",
                    help="远端也对外发布 deps(redis/mysql/kafka)宿主端口; 默认不发布(只内网用)")
    ap.add_argument("--stress-slow-interval", type=int, default=0, metavar="SEC",
                    help="远端 stress-slow 每隔 SEC 秒再跑一轮(0=只跑一次); 需 > 一轮时长(约 200s)")
    ap.add_argument("--stress-threads", type=int, default=0, metavar="N",
                    help="远端 stress 档线程数(0=沿用 compose)。线程越多对 demo-app 内存压力越大")
    args = ap.parse_args()

    raw_paths = str(args.stress_paths).strip()
    if raw_paths.lower() in ("compose", ""):
        stress_paths = None
    elif raw_paths.lower() == "full":
        stress_paths = STRESS_PATHS_FULL
    else:
        stress_paths = raw_paths
    profiles = args.profiles if args.profiles is not None else ["stress"]
    profiles = [] if [p for p in profiles if p.strip().lower() == "none"] else [p for p in profiles if p.strip()]
    # 周期慢档必须真的起到 stress-slow 服务, 否则 interval 无效 —— 自动补上它的 profile
    if args.stress_slow_interval > 0 and profiles and "stress-slow" not in profiles:
        profiles.append("stress-slow")
        print("[local] --stress-slow-interval>0 -> 自动加入 profile: stress-slow")

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
        doc = render_remote_compose(args.compose, compose_tmp, stress_paths,
                                    deps_ports=args.deps_ports, slow_interval_sec=args.stress_slow_interval,
                                    stress_threads=args.stress_threads)
        services = doc.get("services", {})
        # 选定 profile 覆盖的服务 + 无常驻 profile 的 demo-app
        selected = ["demo-app"] + [
            n for n, s in services.items()
            if n != "demo-app" and isinstance(s, dict) and set(s.get("profiles") or []) & set(profiles)
        ]
        print(f"[local] profiles={profiles or '(none)'} -> start {', '.join(selected)}")

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
        profile_flags = "".join(f" --profile {shlex.quote(p)}" for p in profiles)
        run(client, f"cd {d} && {compose_cmd} -f docker-compose.yaml{profile_flags} up -d")

        deadline = time.time() + 300
        healthy = False
        while time.time() < deadline:
            _, ps = run(client, f"cd {d} && {compose_cmd} {profile_flags} ps", check=False)
            if "(healthy)" in ps:
                healthy = True
                break
            time.sleep(10)
        print(f"[remote] demo-app healthy: {healthy}")
        run(client, f"cd {d} && {compose_cmd} {profile_flags} ps", check=False)

        time.sleep(30)
        for svc in selected:
            run(client, f"cd {d} && {compose_cmd} {profile_flags} logs --tail 15 {shlex.quote(svc)}",
                check=False)
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
