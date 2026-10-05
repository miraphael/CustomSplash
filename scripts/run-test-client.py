#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
直接启动一个 PCL 风格「版本隔离」的 Minecraft 客户端（不经过 PCL 图形界面）。

为什么需要它
------------
PCL 是图形界面的启动器，没法在无人值守的脚本里点按钮。而验证模组又必须真的把
游戏跑起来（Mixin 到底注入成功没有、界面画出来是什么样，光编译是看不出来的），
所以这里按官方 version json 自己拼一条启动命令。

它做的事
--------
1. 读 `versions/<版本名>/<版本名>.json`，按 rules 筛出**当前平台（windows/x86_64）**
   真正需要的库，拼成 classpath；
2. 把 natives 相关的 -D 参数指向一个可写目录（LWJGL 3.4 会自己从 classpath 的 jar 里
   把 .dll 解压过去，不需要我们提前解压）；
3. 用 `net.fabricmc.loader.impl.launch.knot.KnotClient` 启动（version json 里的 mainClass）。

用法
----
    python run-test-client.py --version "26.3-Fabric 0.19.5测试模组" \
        --minecraft "<.minecraft 绝对路径>" [--java <java.exe>] [--extra-jvm ...] \
        [--extra-game ...] [--log <日志文件>]

`--extra-game` 用来追加游戏参数，比如直接进某个存档（验证世界加载界面时很有用）：

    --extra-game --quickPlaySingleplayer --extra-game "新的世界"

注意 version json 里的 quick play 规则必须为 false（见下面 FEATURES 的注释），
`--extra-game` 是绕过那套规则手动追加的，所以只传**一条** quick play 参数。

注意
----
- 只处理 `os.name=windows` / `os.arch=x86_64`；其它平台的规则一律跳过。
- 离线账号：用户名固定 Player，uuid / token 用占位值（客户端不联机时够用）。
"""

import argparse
import json
import os
import subprocess
import sys

PLATFORM_OS = "windows"
PLATFORM_ARCH = "x86_64"

# 三个「同一条库、不同架构」的 natives 变体，文件名在 jar 内可能重名，
# 所以按当前架构只留一个，避免把 32 位 / arm64 的 dll 也塞进 classpath。
ARCH_SUFFIXES = {
    "x86_64": ("-x86", "-arm64"),   # 这两个要排掉，剩下的 "natives-windows" 才是 x64
    "x86": ("-x86_64", "-arm64"),
    "arm64": ("-x86_64", "-x86"),
}


# 规则里除了 os 还可能带 features。这里声明「本次启动启用了哪些特性」，
# 不在这里的、或者值为 False 的，一律当作没启用。
#
# 特别注意：quick play 那几条必须全部为 False。
# 官方启动器只会挑其中一条传；要是把 --quickPlayPath / --quickPlaySingleplayer /
# --quickPlayMultiplayer / --quickPlayRealms 一股脑全传进去，Main 会直接抛
# 「Only one quick play option can be specified」然后崩在参数解析阶段。
FEATURES = {
    "is_demo_user": False,
    "has_custom_resolution": True,      # 传 --width/--height，窗口固定 1280x720
    "has_quick_plays_support": False,
    "is_quick_play_singleplayer": False,
    "is_quick_play_multiplayer": False,
    "is_quick_play_realms": False,
}


def os_rule_matches(rule_os):
    """判断一条 rule 的 os 条件是否命中当前平台。"""
    if not rule_os:
        return True
    name = rule_os.get("name")
    arch = rule_os.get("arch")
    if name is not None and name != PLATFORM_OS:
        return False
    if arch is not None:
        # Mojang 的 arch 值：x86 / x86_64 / arm64
        if arch != PLATFORM_ARCH:
            return False
    version_range = rule_os.get("versionRange")
    if version_range is not None:
        # 注意：这里**不能**用 java 的 os.version（Windows 上只有 "10.0"，
        # 拿它跟 "10.0.17134" 比会得出错误结论，导致 ZGC/G1 两组参数同时生效）。
        # 官方启动器用的是注册表里的构建号（CurrentBuildNumber），这里照做。
        current = windows_build_number()
        if "min" in version_range and compare_versions(current, version_range["min"]) < 0:
            return False
        if "max" in version_range and compare_versions(current, version_range["max"]) > 0:
            return False
    return True


def rule_matches(rule):
    """一条 rule 要 os 和 features 两个条件**同时**成立才算命中。"""
    if not os_rule_matches(rule.get("os")):
        return False
    for key, expected in (rule.get("features") or {}).items():
        if bool(FEATURES.get(key, False)) != bool(expected):
            return False
    return True


_BUILD_NUMBER = None


def windows_build_number():
    """取 Windows 构建号，形如 10.0.19045；取不到就当作很新的版本。"""
    global _BUILD_NUMBER
    if _BUILD_NUMBER is not None:
        return _BUILD_NUMBER
    _BUILD_NUMBER = "10.0.99999"
    try:
        import winreg
        key = winreg.OpenKey(
            winreg.HKEY_LOCAL_MACHINE,
            r"SOFTWARE\Microsoft\Windows NT\CurrentVersion")
        try:
            major, _ = winreg.QueryValueEx(key, "CurrentMajorVersionNumber")
        except OSError:
            major = 10
        build, _ = winreg.QueryValueEx(key, "CurrentBuildNumber")
        _BUILD_NUMBER = f"{major}.0.{build}"
    except Exception:
        pass
    return _BUILD_NUMBER


def compare_versions(a, b):
    """按点分数字段比较版本号，缺的位补 0。"""
    pa = [int(x) for x in str(a).split(".") if x.isdigit()]
    pb = [int(x) for x in str(b).split(".") if x.isdigit()]
    while len(pa) < len(pb):
        pa.append(0)
    while len(pb) < len(pa):
        pb.append(0)
    return (pa > pb) - (pa < pb)


def rules_allow(rules):
    """按官方语义求值：从后往前找第一条命中的规则，其 action 就是结论。"""
    if not rules:
        return True
    for rule in reversed(rules):
        if rule_matches(rule):
            return rule.get("action") == "allow"
    return False


def is_wrong_arch_natives(name):
    """排掉与当前架构不符的 natives 变体（见 ARCH_SUFFIXES 注释）。"""
    if ":natives-" not in name:
        return False
    for suffix in ARCH_SUFFIXES[PLATFORM_ARCH]:
        if name.endswith(suffix):
            return True
    return False


def library_path(lib, base):
    """返回库的 jar 路径；没有 artifact（纯 rules 条目）时返回 None。"""
    artifact = lib.get("downloads", {}).get("artifact")
    if artifact:
        return os.path.join(base, "libraries", artifact["path"])
    # 有些条目（比如老式 natives）只有 name，需要自己拼 maven 路径
    parts = lib["name"].split(":")
    if len(parts) < 3:
        return None
    group, artifact_id, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else None
    fname = f"{artifact_id}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
    return os.path.join(base, "libraries", *group.split("."), artifact_id, version, fname)


def flatten_args(arg_list):
    """把 arguments 数组里带 rules 的条目展开成字符串列表。"""
    out = []
    for entry in arg_list:
        if isinstance(entry, str):
            out.append(entry)
        elif rules_allow(entry.get("rules")):
            value = entry["value"]
            out.extend(value if isinstance(value, list) else [value])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help="versions/ 下的版本目录名")
    ap.add_argument("--minecraft", required=True, help=".minecraft 目录的绝对路径")
    ap.add_argument("--java", default=None, help="java.exe 路径")
    ap.add_argument("--extra-jvm", action="append", default=[], help="追加的 JVM 参数，可重复")
    ap.add_argument("--extra-game", action="append", default=[],
                    help="追加的游戏参数（拼在 mainClass 之后），可重复")
    ap.add_argument("--log", default=None, help="把 stdout/stderr 写进这个文件")
    ap.add_argument("--dry-run", action="store_true", help="只打印命令，不启动")
    args = ap.parse_args()

    base = os.path.abspath(args.minecraft)
    version_dir = os.path.join(base, "versions", args.version)
    version_json = os.path.join(version_dir, args.version + ".json")
    if not os.path.isfile(version_json):
        sys.exit(f"找不到版本 json: {version_json}")

    with open(version_json, encoding="utf-8") as f:
        data = json.load(f)

    # ---- classpath ----
    cp = []
    missing = []
    for lib in data["libraries"]:
        name = lib["name"]
        if not rules_allow(lib.get("rules")):
            continue
        if is_wrong_arch_natives(name):
            continue
        path = library_path(lib, base)
        if path is None:
            continue
        if not os.path.isfile(path):
            missing.append(name)
            continue
        cp.append(path)

    client_jar = os.path.join(version_dir, args.version + ".jar")
    if os.path.isfile(client_jar):
        cp.append(client_jar)
    else:
        sys.exit(f"找不到客户端 jar: {client_jar}")

    if missing:
        sys.stderr.write("以下库在本机缺失（可能是别的平台的）：\n")
        for m in missing:
            sys.stderr.write("  " + m + "\n")

    natives_dir = os.path.join(version_dir, args.version + "-natives")
    os.makedirs(os.path.join(natives_dir, "java"), exist_ok=True)
    os.makedirs(os.path.join(natives_dir, "jna"), exist_ok=True)
    os.makedirs(os.path.join(natives_dir, "lwjgl"), exist_ok=True)
    os.makedirs(os.path.join(natives_dir, "netty"), exist_ok=True)

    subs = {
        "natives_directory": natives_dir,
        "launcher_name": "customsplash-dev",
        "launcher_version": "1",
        "classpath": os.pathsep.join(cp),
        "classpath_separator": os.pathsep,
        "library_directory": os.path.join(base, "libraries"),
        "auth_player_name": "Player",
        "version_name": args.version,
        "game_directory": version_dir,
        "assets_root": os.path.join(base, "assets"),
        "assets_index_name": data.get("assets", "legacy"),
        "auth_uuid": "00000000000000000000000000000000",
        "auth_access_token": "0",
        "clientid": "",
        "auth_xuid": "",
        "version_type": "release",
        "user_type": "msa",
        "resolution_width": "1280",
        "resolution_height": "720",
    }

    def sub(text):
        for key, value in subs.items():
            text = text.replace("${" + key + "}", value)
        return text

    jvm = flatten_args(data["arguments"].get("default-user-jvm", []))
    jvm += flatten_args(data["arguments"].get("jvm", []))
    game = [sub(a) for a in flatten_args(data["arguments"]["game"])]

    java_exe = args.java or "java"
    cmd = [java_exe] + [sub(a) for a in jvm] + args.extra_jvm
    cmd += [data["mainClass"]] + game + args.extra_game

    if args.dry_run:
        print(" ".join(cmd))
        return

    print("启动:", args.version)
    print("游戏目录:", version_dir)
    print("classpath 条目:", len(cp))
    # 注意：这里必须**阻塞等待**游戏结束（subprocess.run 而不是 Popen）。
    # 本脚本经常是作为「后台任务」被拉起的，一旦它 return，外层会把整个进程组收掉，
    # 游戏会静默退出（窗口消失、日志 0 字节），看起来就像「模组崩了」。
    if args.log:
        with open(args.log, "w", encoding="utf-8", errors="replace") as lf:
            print("日志:", args.log)
            subprocess.run(cmd, stdout=lf, stderr=subprocess.STDOUT,
                           cwd=version_dir)
        return
    subprocess.run(cmd, cwd=version_dir)


if __name__ == "__main__":
    main()
