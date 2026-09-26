#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""android-mcp 的最小 stdio 客户端（纯标准库）。

用途：在 WorkBuddy 连接器之外，直接用 JSON-RPC 2.0 调用本机 android MCP 服务端，
      便于脚本化/自测。用法：
        python mcp_android.py tools                     # 列出工具
        python mcp_android.py call <tool> '<json 参数>'  # 调用工具
"""
from __future__ import annotations

import json
import subprocess
import sys
import os

SERVER = r"C:\Users\oscur\.workbuddy-ai\mcp-servers\android_mcp.py"
PYTHON = r"C:\Users\oscur\.workbuddy-ai\binaries\python\versions\3.13.12\python.exe"


def _pick_jdk() -> str:
    """挑一个 Gradle 8.x 认得的 JDK。

    坑：`D:\\Android Studio\\jbr` 会随 Android Studio 升级变成 JDK 25，而 Gradle 8.14
    只支持到 Java 24，构建会以一句莫名奇妙的 `What went wrong: 25.0.2` 失败。
    所以这里按 21 → 17 → 11 的顺序探测可用的 JDK。
    """
    env = os.environ.get("GIGI_JAVA_HOME")
    cands = [env] if env else []
    cands += [
        r"C:\Users\oscur\.jdks\ms-21.0.12.1",
        r"C:\Users\oscur\.jdks\temurin-21",
        r"C:\Program Files\Eclipse Adoptium\jdk-21",
        r"C:\Users\oscur\.jdks\jbr_dcevm-11.0.16",
    ]
    cands += [r"D:\Android Studio\jbr", r"C:\Program Files\Java\latest"]
    for c in cands:
        if c and os.path.exists(os.path.join(c, "bin", "java.exe")):
            return c
    return r"D:\Android Studio\jbr"


ENV = {
    **os.environ,
    "ANDROID_HOME": r"D:\AndroidSDK",
    "JAVA_HOME": _pick_jdk(),
    "GRADLE_USER_HOME": r"D:\Android Studio\gradle",
    "ANDROID_SERIAL": "ac9bcc9a",
}


class Mcp:
    def __init__(self) -> None:
        self.p = subprocess.Popen(
            [PYTHON, SERVER], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, env=ENV, text=True, encoding="utf-8", bufsize=1,
        )
        self._id = 0
        self.request("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "workbuddy-cli", "version": "1.0"},
        })
        self.notify("notifications/initialized", {})

    def _send(self, obj) -> None:
        self.p.stdin.write(json.dumps(obj, ensure_ascii=False) + "\n")
        self.p.stdin.flush()

    def request(self, method, params):
        self._id += 1
        rid = self._id
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError("MCP 服务端已退出")
            line = line.strip()
            if not line:
                continue
            msg = json.loads(line)
            if msg.get("id") == rid:
                if "error" in msg:
                    raise RuntimeError(json.dumps(msg["error"], ensure_ascii=False))
                return msg["result"]

    def notify(self, method, params):
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def tools(self):
        return self.request("tools/list", {})["tools"]

    def call(self, name, args):
        r = self.request("tools/call", {"name": name, "arguments": args})
        parts = []
        for c in r.get("content", []):
            parts.append(c.get("text", ""))
        return "\n".join(parts)


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        return
    m = Mcp()
    cmd = sys.argv[1]
    if cmd == "tools":
        for t in m.tools():
            print(f'{t["name"]:22s} {t["description"][:90]}')
    elif cmd == "call":
        name = sys.argv[2]
        args = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        print(m.call(name, args))
    else:
        print("未知命令", cmd)


if __name__ == "__main__":
    main()
