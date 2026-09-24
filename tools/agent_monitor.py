#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""agent_monitor.py -- 全局 CLI 子代理任务监视器（跨工具、跨工程）。

用途：**不依赖任何宿主工具（WorkBuddy / TRAE / Cursor / …）的后台任务面板**，
把所有 CLI 子代理（qoderclicn / opencode+OpenRouter / 百炼 bl）的派发与进度
汇总成一份结构化状态，供人查看，也供主代理"不阻塞等待"地读取。

两种用法：
  1) 人看：`python tools/agent_monitor.py --watch --serve 8787`  → 浏览器打开 http://127.0.0.1:8787
  2) 主代理读：`python tools/agent_monitor.py --json`（或读 tools/.agent_monitor/state.json）
     ⇒ 派发后立刻返回，改为周期性读这份状态，不必阻塞等待子代理。

关键纪律（血泪教训，别改）：
  - **不用日志大小判存活**：CLI 输出经管道缓冲，进程结束才落盘，0 字节是正常的。
  - **判活先跑 `date`**：绝不用会话上下文注入的时间戳。
  - 判活三指标：进程存活 × 会话/日志新鲜度 × 证据目录新增；三者同时停滞才判卡死。
  - 🔴 **只认产物，不认状态**：CLI 常以非 0 码退出却产物完好（"假失败"）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# --------------------------------------------------------------------------------------
# 常量
# --------------------------------------------------------------------------------------
STALE_SEC = 30 * 60          # 无任何活动超过这个时长 ⇒ 疑似卡死
DEAD_GRACE_SEC = 60          # 进程没了、但 60s 内还有活动 ⇒ 仍算刚结束，不急着标死
RECENT_SEC = 24 * 3600       # 界面只显示 24h 内有活动的任务（历史任务太多会刷屏）
POLL_SEC = 3                 # 扫描周期

TOOL_KINDS = {
    "qoder": ("qoderclicn", ".exe"),
    "opencode": ("opencode", ""),
    "bailian": ("bl", ".cmd"),
}

HERE = Path(__file__).resolve().parent
OUT_DIR = HERE / ".agent_monitor"
STATE_JSON = OUT_DIR / "state.json"
EVENTS_JSONL = OUT_DIR / "events.jsonl"
WAITING_JSON = OUT_DIR / "waiting.json"   # 主代理登记"我在等这些任务"
NOTIFY_JSON = OUT_DIR / "notify.json"     # 完工通知（非阻塞哨兵的产物）

# 🔴 主代理在**任意工程**里派发后调一次这个绝对路径即可（幂等：已开则不重复起）
SELF_WIN = r"C:/Users/oscur/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"
ENTRY = Path(__file__).resolve()
# 本副本位于 GIGI 工程；本工程/本机其它工程的 agent 一律调这个路径（幂等）
SELF_TOOL = str(ENTRY).replace(chr(92), "/")
ENSURE_CMD = ('"C:/Users/oscur/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" '
              '"D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --ensure --serve 8787')

# 全盘搜索时跳过的目录名（噪音/巨树）
SKIP_DIRS = {
    "node_modules", ".git", "build", "out", ".gradle", "venv", ".venv",
    "__pycache__", "AppData", ".cache", "$Recycle.Bin", "Windows",
    "Program Files", "Program Files (x86)", "ProgramData",
    ".studio", ".vs", "obj", "bin", "dist", "target",
}


def now_ts() -> float:
    return time.time()


def fmt_age(sec: float | None) -> str:
    if sec is None:
        return "-"
    sec = int(max(0, sec))
    if sec < 60:
        return "%ds" % sec
    if sec < 3600:
        return "%dm%02ds" % (sec // 60, sec % 60)
    return "%dh%02dm" % (sec // 3600, (sec % 3600) // 60)


def iso(ts: float | None) -> str:
    if not ts:
        return ""
    return datetime.fromtimestamp(ts).astimezone().strftime("%m-%d %H:%M:%S")


# --------------------------------------------------------------------------------------
# 进程枚举
# --------------------------------------------------------------------------------------
# --------------------------------------------------------------------------------------
# 进程枚举 —— 🔴 绝不为它起子进程
# --------------------------------------------------------------------------------------
# 血泪教训：早期版本用 `subprocess.run(["powershell", ...])` 每 3 秒枚举一次进程。
# Windows 上 subprocess 不设 CREATE_NO_WINDOW 时**每次调用都会弹一个控制台窗口**，
# 于是用户看到"大量 PowerShell 界面被反复拉起然后被系统关闭"——正是本工具在刷屏。
# 现在改为**纯标准库 + ctypes 直调 Win32**：CreateToolhelp32Snapshot 取 pid/父 pid/名字，
# 完全不创建任何子进程 ⇒ 不可能弹窗，还顺带快了一个数量级。
if os.name == "nt":
    import ctypes
    from ctypes import wintypes

    _k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _ntdll = ctypes.WinDLL("ntdll", use_last_error=True)

    TH32CS_SNAPPROCESS = 0x00000002
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    MAX_PATH = 260

    class _PROCESSENTRY32W(ctypes.Structure):
        _fields_ = [
            ("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
            ("th32ProcessID", wintypes.DWORD),
            ("th32DefaultHeapID", ctypes.POINTER(ctypes.c_ulong)),
            ("th32ModuleID", wintypes.DWORD), ("cntThreads", wintypes.DWORD),
            ("th32ParentProcessID", wintypes.DWORD),
            ("pcPriClassBase", ctypes.c_long), ("dwFlags", wintypes.DWORD),
            ("szExeFile", ctypes.c_wchar * MAX_PATH),
        ]

    _k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    _k32.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(_PROCESSENTRY32W)]
    _k32.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(_PROCESSENTRY32W)]
    _k32.OpenProcess.restype = wintypes.HANDLE
    _k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    _k32.CloseHandle.argtypes = [wintypes.HANDLE]
    _k32.QueryFullProcessImageNameW.argtypes = [
        wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]

    # NtQueryInformationProcess(ProcessCommandLineInformation=60) 取命令行。
    # 比 WMI 快、比读 PEB 稳，且**不需要管理员权限**（同用户进程即可）。
    _ntdll.NtQueryInformationProcess.argtypes = [
        wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p,
        wintypes.ULONG, ctypes.POINTER(wintypes.ULONG)]


def _iter_nt_processes() -> list[dict]:
    """Win32 快照枚举进程：取 pid / ppid / 可执行名。零子进程、零窗口。"""
    out: list[dict] = []
    snap = _k32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
    if snap == wintypes.HANDLE(-1).value or not snap:
        return out
    try:
        e = _PROCESSENTRY32W()
        e.dwSize = ctypes.sizeof(_PROCESSENTRY32W)
        ok = _k32.Process32FirstW(snap, ctypes.byref(e))
        while ok:
            out.append({"pid": int(e.th32ProcessID),
                        "ppid": int(e.th32ParentProcessID),
                        "name": e.szExeFile or "",
                        "cmdline": "", "create_ts": None})
            ok = _k32.Process32NextW(snap, ctypes.byref(e))
    except Exception:
        pass
    finally:
        _k32.CloseHandle(snap)
    return out


def _proc_cmdline(pid: int) -> str:
    """取进程命令行（NtQueryInformationProcess / ProcessCommandLineInformation）。失败返回 ""。"""
    h = _k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        return ""
    try:
        need = wintypes.ULONG(0)
        # 先探长度（STATUS_INFO_LENGTH_MISMATCH / STATUS_BUFFER_TOO_SMALL 都会填 need）
        _ntdll.NtQueryInformationProcess(h, 60, None, 0, ctypes.byref(need))
        if not need.value:
            return ""
        buf = ctypes.create_string_buffer(need.value + 64)
        st = _ntdll.NtQueryInformationProcess(h, 60, buf, need.value + 64, ctypes.byref(need))
        if st != 0:
            return ""
        # UNICODE_STRING { USHORT Length; USHORT MaxLen; PWSTR Buffer; }
        length = ctypes.cast(buf, ctypes.POINTER(ctypes.c_ushort))[0]
        if not length:
            return ""
        # Buffer 指针在结构体偏移 8（x64）；用偏移取更稳
        off = 8 if ctypes.sizeof(ctypes.c_void_p) == 8 else 4
        ptr = ctypes.cast(ctypes.byref(buf, off), ctypes.POINTER(ctypes.c_void_p))[0]
        if not ptr:
            return ""
        return ctypes.wstring_at(ptr, length // 2)
    except Exception:
        return ""
    finally:
        _k32.CloseHandle(h)


def list_processes() -> list[dict]:
    """返回 [{pid, ppid, name, cmdline, create_ts}]；失败时返回空表（降级不报错）。

    🔴 **已不再调用 PowerShell**（见上方注释）：走 ctypes 直调 Win32，
    只对**候选进程**（名字像 CLI 通道的）才去取命令行，控制开销到最小。
    """
    procs: list[dict] = []
    if os.name == "nt":
        try:
            cands = _iter_nt_processes()
            # 只给"可能就是 CLI 通道"的进程取命令行（有窗口/长命令行的才看），别对全表都取
            want = ("qoderclicn", "opencode", "node", "bun", "bl", "python", "cmd")
            for p in cands:
                nm = (p["name"] or "").lower()
                if any(w in nm for w in want):
                    p["cmdline"] = _proc_cmdline(p["pid"])
            return cands
        except Exception:
            return procs
    # 非 Windows 兜底
    try:
        r = subprocess.run(["ps", "-eo", "pid=,ppid=,comm=,args="],
                           capture_output=True, text=True, timeout=30)
        for line in (r.stdout or "").splitlines():
            parts = line.strip().split(None, 3)
            if len(parts) >= 3:
                procs.append({"pid": int(parts[0]), "ppid": int(parts[1]),
                              "name": parts[2], "cmdline": parts[3] if len(parts) > 3 else "",
                              "create_ts": None})
    except Exception:
        pass
    return procs


def _parse_ps_date(v) -> float | None:
    """PowerShell JSON 里的 CreationDate 形如 '/Date(1790123456789)/' 或 ISO 串。"""
    if not v:
        return None
    if isinstance(v, dict) and "value" in v:
        v = v["value"]
    m = re.search(r"/Date\((\d+)", str(v))
    if m:
        return int(m.group(1)) / 1000.0
    s = str(v)
    for fmt in ("%Y-%m-%dT%H:%M:%S.%f%z", "%Y-%m-%dT%H:%M:%S%z",
                "%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
        try:
            dt = datetime.strptime(s.replace("Z", "+0000"), fmt)
            return dt.timestamp()
        except ValueError:
            continue
    return None


def match_tool(proc: dict) -> str | None:
    """判断该进程是否属于某条 CLI 通道。"""
    name = (proc.get("name") or "").lower()
    cmd = proc.get("cmdline") or ""
    if "node" in name and cmd:
        # opencode 由 bun/node 承载；只在命令行里出现 opencode 时才认
        if re.search(r"opencode", cmd, re.I) and "agent_monitor" not in cmd:
            return "opencode"
        return None
    for kind, (exe, ext) in TOOL_KINDS.items():
        if name == (exe + ext).lower() or name == exe.lower():
            return kind
    if name == "bl.exe" or name == "bl.cmd" or name == "bl":
        return "bailian"
    return None


# --------------------------------------------------------------------------------------
# 工程 / 派单 / 探针 / 日志
# --------------------------------------------------------------------------------------
def find_projects(roots: list[Path], max_depth: int = 4) -> list[Path]:
    """找出所有含 .task/dispatch 或 .agent_work 的工程根。"""
    found: list[Path] = []
    seen: set[str] = set()

    def probe(p: Path):
        try:
            if (p / ".task" / "dispatch").is_dir() or (p / ".agent_work").is_dir():
                key = str(p).lower()
                if key not in seen:
                    seen.add(key)
                    found.append(p)
        except OSError:
            pass

    for root in roots:
        probe(root)
        root = Path(root)
        if not root.is_dir():
            continue
        for dirpath, dirnames, _ in os.walk(root):
            d = Path(dirpath)
            depth = len(d.relative_to(root).parts)
            if depth >= max_depth:
                dirnames[:] = []
            dirnames[:] = [
                x for x in dirnames
                if x not in SKIP_DIRS and not x.startswith("$")
            ]
            probe(d)
    return found


def field(text: str, key: str) -> str:
    """从探针/README 的 YAML-ish 头部取标量字段。"""
    m = re.search(r"^%s:\s*(.*)$" % re.escape(key), text, re.M)
    if not m:
        return ""
    v = m.group(1).strip().strip('"').strip("'")
    # 行内列表 [a, b]
    if v.startswith("[") and v.endswith("]"):
        return v[1:-1].strip()
    return v


def list_field(text: str, key: str) -> list[str]:
    """取块状列表（key: 后面若干 '  - item'）。"""
    m = re.search(r"^%s:\s*$" % re.escape(key), text, re.M)
    if not m:
        return []
    out = []
    for line in text[m.end():].splitlines():
        if re.match(r"^\s*-\s+", line):
            out.append(re.sub(r"^\s*-\s+", "", line).strip())
        elif line.strip() and not line.startswith(" "):
            break
    return out


def scan_engine() -> dict:
    """扫描全部工程的派发/探针/日志/进程，产出结构化状态。"""
    procs = list_processes()
    tools: list[dict] = []
    for p in procs:
        kind = match_tool(p)
        if kind:
            p = dict(p)
            p["kind"] = kind
            tools.append(p)

    # 启动时的脚本名/工程线索
    for p in tools:
        cmd = p.get("cmdline") or ""
        p["hint"] = _cmd_hint(cmd)

    roots = _default_roots()
    projects = find_projects(roots)

    tasks: dict[str, dict] = {}
    for proj in projects:
        _scan_project(proj, tasks, tools)

    # 会话 transcript mtime —— 判活三指标之一（进程 × 会话 × 证据）
    _scan_sessions(tasks)

    # 把没有探针/派单记录、但确实在跑的进程也列出来（不丢）
    # 注意：若该进程已被某个任务精确绑定，就不再建"pid-xxx"伪条目
    bound_pids = {t["tool_pid"] for t in tasks.values() if t.get("proc_bound")}
    for t in tools:
        if t["pid"] in bound_pids:
            continue
        key = t["hint"] or ("pid-%s" % t["pid"])
        if key not in tasks:
            tasks[key] = {
                "id": key, "kind": t["kind"], "project": "", "tool_pid": t["pid"],
                "dispatch_ts": None, "started_ts": t.get("create_ts"),
                "probe_ts": None, "log_ts": None, "evidence_ts": None,
                "sess_ts": None,
                "probe_status": "", "probe_owner": "", "summary": "",
                "files": [], "evidence": [], "blockers": [],
                "log_path": "", "dispatch_path": "", "probe_path": "",
                "log_bytes": 0, "log_tail": [], "exit_hint": "",
            }

    # 判定
    for t in tasks.values():
        _classify(t)

    all_rows = sorted(tasks.values(), key=lambda x: -(x.get("last_activity") or 0))
    # 默认只留"有信号"的任务：在跑 / 卡死 / 死亡 / 阻塞 / 24h 内有活动。
    # 否则几百条历史任务会把界面刷爆（实测单个大工程可累积 435 条）。
    cutoff = now_ts() - RECENT_SEC
    rows = [r for r in all_rows
            if r["state"] in ("running", "stale", "dead", "blocked")
            or (r.get("last_activity") or 0) >= cutoff]
    hidden = len(all_rows) - len(rows)
    live = sum(1 for r in all_rows if r["state"] == "running")
    stale = sum(1 for r in all_rows if r["state"] == "stale")

    state = {
        "generated_ts": now_ts(),
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "counts": {"total": len(all_rows), "shown": len(rows), "hidden": hidden,
                   "running": live, "stale": stale,
                   "dead": sum(1 for r in all_rows if r["state"] == "dead"),
                   "done": sum(1 for r in all_rows if r["state"] == "done"),
                   "blocked": sum(1 for r in all_rows if r["state"] == "blocked"),
                   "idle": sum(1 for r in all_rows if r["state"] == "idle"),
                   "unknown": sum(1 for r in all_rows if r["state"] == "unknown")},
        "recent_sec": RECENT_SEC,
        "processes": [{"pid": p["pid"], "kind": p["kind"], "age": fmt_age(
            now_ts() - p["create_ts"] if p.get("create_ts") else None),
            "hint": p.get("hint", ""), "cmd": (p.get("cmdline") or "")[:300]}
            for p in sorted(tools, key=lambda x: x["pid"])],
        "projects": [str(p) for p in projects],
        "rows": rows,
    }
    return state


def _cmd_hint(cmd: str) -> str:
    """从命令行里取任务 ID（只认派单/日志文件路径；取不到就留空）。

    🔴 不要用 `-m <模型>` 兜底：那会造出 `model:Qwen3.8-Max` 这种伪任务
    （实测它会被挂到进程上、在界面里显示成一个"任务"）。
    """
    if not cmd:
        return ""
    m = re.search(r"dispatch[\\/]([^\\/\s\"']+)\.txt", cmd)
    if m:
        return m.group(1)
    m = re.search(r"\.agent_work[\\/]([^\\/\s\"']+)\.log", cmd)
    if m:
        return m.group(1)
    return ""


def _default_roots() -> list[Path]:
    """默认扫描范围：本工具所在工程 + 常见工程父目录 + 用户目录浅层。"""
    roots: list[Path] = [HERE.parent]
    for cand in (Path("D:/AndroidStudioProjects"), Path("D:/EmulatorShared"),
                 Path.home()):
        if cand.is_dir():
            roots.append(cand)
    return roots


def _newest_mtime(paths: list[Path]) -> float | None:
    best = None
    for p in paths:
        try:
            if p.exists():
                m = p.stat().st_mtime
                if best is None or m > best:
                    best = m
        except OSError:
            continue
    return best


# --------------------------------------------------------------------------------------
# 会话 transcript mtime —— 判活三指标之一（skill subagent-cli-ops §7）
# --------------------------------------------------------------------------------------
# 背景：CLI 子代理（qoderclicn）的**活动**最可靠的体现是它的会话 transcript 在增长；
# 而探针/证据只在收工时落盘，长任务中途可能几十分钟不写。若 last_activity 只取
# probe/log/evidence，就会把「探针不落盘但会话一直在干活」的代理误判 stale。
# 实测（2026-09-25）：T6-DLGFIX 探针停更 43min，但 transcript 每 ~40s 更新一次。
SESSION_ROOTS = [
    Path.home() / ".qoder-cn" / "logs" / "sessions",
    Path.home() / ".qoder-cn" / "projects",
]


def _scan_sessions(tasks: dict) -> None:
    """把每个任务 ID 对应的会话 transcript mtime 记入 t['sess_ts']。

    匹配方式：会话目录名形如 `D--AndroidStudioProjects-GIGI`，其下按 session 分目录，
    内含 `segments/*.jsonl`。任务 ID 只出现在 transcript **内容**里（不在文件名/目录名），
    故读尾部 64KB 做确认。

    🔴🔴 严格匹配（2026-09-25 修 bug）：**必须用带边界的精确匹配**。
    曾用 `tid not in tail` 做子串判断 ⇒ `G-PACK` / `arch` / `clean` / `e2c` / `s2` 这类
    **短 ID 恰好是常见英文单词**，会随机命中任意会话的文本 ⇒ 幽灵任务被绑上别的活跃会话
    mtime ⇒ `age` 恒为 3s ⇒ 一律显示「进行中」（实测 12 个"进行中"里 8 个是幽灵）。
    现在要求：① 该任务**必须有派单文件**（真实存在过）；
    ② ID 在 transcript 中以**词边界**出现（前后非 [A-Za-z0-9_-]）；
    ③ 命中次数 ≥2（只出现 1 次多半是偶然提及）。
    """
    for root in SESSION_ROOTS:
        if not root.is_dir():
            continue
        # 会话目录：<project-slug>/<session_id>/segments/*.jsonl
        for slug_dir in root.iterdir():
            try:
                if not slug_dir.is_dir():
                    continue
            except OSError:
                continue
            newest = None
            newest_file = None
            for seg in slug_dir.glob("*/segments/*.jsonl"):
                try:
                    m = seg.stat().st_mtime
                except OSError:
                    continue
                if newest is None or m > newest:
                    newest, newest_file = m, seg
            if newest is None or newest_file is None:
                continue
            # 只认近 24h 内有活动的会话，避免把历史会话算成"活着"
            if now_ts() - newest > 24 * 3600:
                continue
            # 轻量确认：只读尾部 64KB 找任务 ID
            tail = ""
            try:
                with open(newest_file, "rb") as fh:
                    fh.seek(0, 2)
                    size = fh.tell()
                    fh.seek(max(0, size - 65536))
                    tail = fh.read().decode("utf-8", errors="replace")
            except OSError:
                continue
            for tid, t in tasks.items():
                # 🔴 只对「**可能真的在跑**」的任务采集 sess_ts：
                #    ① 必须有进程绑定（proc_bound），或 ② 探针明说 doing。
                #    否则一律不采 —— 已 done/blocked/idle 的任务即便 ID 命中会话文本，
                #    也是主代理复述/上下文提及，不是"这个任务在跑"（见 _classify 注释）。
                if not t.get("proc_bound") and (t.get("probe_status") or "").lower() != "doing":
                    continue
                if not _id_in_text(tid, tail):
                    continue
                if newest and (t.get("sess_ts") is None or newest > t["sess_ts"]):
                    t["sess_ts"] = newest


def _id_in_text(tid: str, text: str, min_hits: int = 2) -> bool:
    """任务 ID 是否以**词边界**形式在文本中出现至少 min_hits 次。

    🔴 不能用 `tid in text`：短 ID（`arch`/`clean`/`e2c`/`s2`）会命中任意英文文本。
    """
    if not tid or not text:
        return False
    pat = re.compile(r"(?<![A-Za-z0-9_-])%s(?![A-Za-z0-9_-])" % re.escape(tid))
    return len(pat.findall(text)) >= min_hits


def _scan_project(proj: Path, tasks: dict, tools: list[dict]) -> None:
    dispatch_dir = proj / ".task" / "dispatch"
    progress_dir = proj / ".task" / "progress"
    work_dirs = [proj / ".agent_work", proj / ".task" / "events", work_dirs_extra(proj)]

    # ---- 派单文件 ----
    if dispatch_dir.is_dir():
        for f in sorted(dispatch_dir.glob("*.txt")):
            tid = f.stem
            t = tasks.setdefault(tid, _blank(tid))
            t["dispatch_path"] = str(f)
            t["dispatch_ts"] = _newest_mtime([f])
            t["project"] = t["project"] or str(proj)

    # ---- 探针 ----
    if progress_dir.is_dir():
        for f in sorted(progress_dir.glob("*.md")):
            tid = f.stem
            t = tasks.setdefault(tid, _blank(tid))
            t["probe_path"] = str(f)
            t["probe_ts"] = _newest_mtime([f])
            t["project"] = t["project"] or str(proj)
            try:
                text = f.read_text(encoding="utf-8", errors="replace")
            except OSError:
                text = ""
            t["probe_status"] = field(text, "status")
            t["probe_owner"] = field(text, "owner")
            t["summary"] = field(text, "summary")
            t["files"] = (list_field(text, "files") or
                          ([field(text, "files")] if field(text, "files") else []))
            t["evidence"] = (list_field(text, "evidence") or
                             ([field(text, "evidence")] if field(text, "evidence") else []))
            bl = field(text, "blockers")
            t["blockers"] = [] if bl in ("", "[]") else [bl]
            t["probe_text"] = text[:4000]

    # ---- 日志 ----
    logs: list[Path] = []
    for wd in work_dirs:
        if wd and wd.is_dir():
            # 排除临时/中间文件，避免造出 "live" 之类的伪任务
            logs.extend(sorted(p for p in wd.glob("*.log")
                               if not re.search(r"\.tmp$|\.part$|~$", p.name)))
    for f in logs:
        tid = f.name[:-4]
        t = tasks.setdefault(tid, _blank(tid))
        if not t["log_path"]:
            t["log_path"] = str(f)
            t["project"] = t["project"] or str(proj)
        t["log_ts"] = max(t["log_ts"] or 0, _mtime(f) or 0) or t["log_ts"]
        try:
            t["log_bytes"] = f.stat().st_size
            t["log_tail"] = _tail(f, 25)
        except OSError:
            pass

    # ---- 证据：按任务名匹配文件，绝不把"目录整体的最新 mtime"糊给所有任务 ----
    # （否则历史任务的"最后活动"会被别人刚写的文件刷新成几秒前 ⇒ 假"进行中"）
    for wd in work_dirs:
        if not (wd and wd.is_dir()):
            continue
        for f in wd.rglob("*"):
            if not f.is_file():
                continue
            name = f.name
            hit = None
            for t in tasks.values():
                if t["project"] != str(proj):
                    continue
                tid = t["id"]
                # 同一任务可能同时存在 .log / -or.log / .jsonl / 截图等变体
                if name == tid or name.startswith(tid + ".") or name.startswith(tid + "-") \
                        or name.startswith(tid + "_"):
                    hit = t
                    break
            if hit is None:
                continue
            m = _mtime(f)
            if m:
                hit["evidence_ts"] = max(hit["evidence_ts"] or 0, m) or hit["evidence_ts"]
                hit.setdefault("evidence_files", [])
                if len(hit["evidence_files"]) < 8:
                    hit["evidence_files"].append(f.name)

    # ---- 关联进程 ----
    for t in tasks.values():
        if t["project"] != str(proj):
            continue
        if not t.get("tool_pid"):
            hit = _find_proc_for(t, tools)
            if hit:
                t["tool_pid"] = hit["pid"]
                t["kind"] = hit["kind"]
                t["started_ts"] = hit.get("create_ts")
                t["proc_bound"] = True
        if not t.get("kind") and t.get("log_path"):
            t["kind"] = _kind_from_cmdline(t, tools)


def work_dirs_extra(proj: Path) -> Path | None:
    return None


def _blank(tid: str) -> dict:
    return {
        "id": tid, "kind": "", "project": "", "tool_pid": None,
        "dispatch_ts": None, "started_ts": None, "probe_ts": None,
        "log_ts": None, "evidence_ts": None, "sess_ts": None,
        "probe_status": "", "probe_owner": "", "summary": "",
        "files": [], "evidence": [], "blockers": [],
        "log_path": "", "dispatch_path": "", "probe_path": "",
        "log_bytes": 0, "log_tail": [], "exit_hint": "", "probe_text": "",
        "proc_bound": False, "evidence_files": [],
    }


def _mtime(p: Path) -> float | None:
    try:
        return p.stat().st_mtime
    except OSError:
        return None


def _tail(p: Path, n: int) -> list[str]:
    try:
        with p.open("rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - 8192))
            data = fh.read().decode("utf-8", "replace")
        return [l for l in data.splitlines() if l.strip()][-n:]
    except OSError:
        return []


def _find_proc_for(t: dict, tools: list[dict]) -> dict | None:
    """**精确绑定**：只在进程命令行里找该任务的**派单文件名 / 日志文件名**。

    为什么不用宽泛的"ID 在命令行里出现"：CLI 命令形如
      `qoderclicn ... "$(cat .task/dispatch/R5-NEWCODE.txt)"`
    整段派单文本都在命令行里，于是**别的任务 ID 会因为被派单提到而误命中**
    （实测 R4-DLGBUG 被挂到 R5-NEWCODE 的进程上）。只用文件路径才能唯一确定身份。
    绝不按"同一工作目录"匹配 —— 那会让同目录全部历史任务共用一个 PID。
    宁可显示 `-`，也不要错的 PID。
    """
    tid = t["id"]
    if not tid:
        return None
    e = re.escape(tid)
    pats = [
        re.compile(r"dispatch[\\/]%s\.txt" % e, re.I),
        re.compile(r"\.agent_work[\\/]%s(?:-[\w.]+)?\.log" % e, re.I),
        re.compile(r"progress[\\/]%s\.md" % e, re.I),
    ]
    for p in tools:
        cmd = p.get("cmdline") or ""
        if any(pat.search(cmd) for pat in pats):
            return p
    return None


def _kind_from_cmdline(t: dict, tools: list[dict]) -> str:
    for p in tools:
        cmd = p.get("cmdline") or ""
        if t["id"] and t["id"] in cmd:
            return p["kind"]
    return ""


# --------------------------------------------------------------------------------------
# 状态判定
# --------------------------------------------------------------------------------------
def _classify(t: dict) -> None:
    # 🔴 判活口径（skill subagent-cli-ops §7）：进程存活 × 会话/日志新鲜度 × 证据新增。
    #
    # 🔴🔴 sess_ts 的**角色限定**（2026-09-25 踩坑后引入，勿改回）：
    #    sess_ts 是**弱佐证**，只能用来把"要判 stale 的任务"救回 running，
    #    **绝不能**用来把"早已结束的任务"抬成 running。
    #    原因：任务 ID（如 G-PACK / arch / clean）会出现在**任何**会话文本里
    #    （派生它的派单、主代理复述、别任务的上下文），子串/词边界匹配都无法区分
    #    "这个会话在跑这个任务" 与 "这个会话提到了这个任务"。
    #    历史事故：把 sess_ts 并入 acts 后，8 个已 done 的幽灵任务被绑上活跃会话 mtime ⇒
    #    age 恒为几秒 ⇒ 全部显示「进行中」（用户实测 11 个「进行中」里 8 个是假的）。
    acts = [x for x in (t.get("probe_ts"), t.get("log_ts"),
                        t.get("evidence_ts")) if x]
    own_activity = max(acts) if acts else (t.get("started_ts") or 0)
    age = now_ts() - own_activity if own_activity else None

    st = (t.get("probe_status") or "").lower()
    alive = bool(t.get("tool_pid"))

    if st == "done":
        state = "done"
    elif st == "blocked":
        state = "blocked"
    elif alive:
        # 有进程 ≠ 是"这个"任务的进程。只有命令行里确实出现该任务 ID 才算绑定。
        if t.get("proc_bound"):
            state = "stale" if (age is not None and age > STALE_SEC) else "running"
        else:
            state = "idle"
    else:
        if age is None:
            state = "unknown"
        elif age < DEAD_GRACE_SEC:
            state = "running"          # 刚结束，还在落盘（由自身产物 mtime 判定）
        elif st == "doing":
            state = "dead"             # 探针说在干，进程却没了 ⇒ 中途死亡，需补派
        else:
            state = "idle" if (t.get("log_path") or t.get("probe_path")) else "unknown"

    # sess_ts 只做**单向救援**：本来要判 stale，但会话仍在更新 ⇒ 其实在干活
    # （长任务不落探针的典型情形，T6-DLGFIX 实测）。
    if state == "stale" and t.get("sess_ts"):
        sess_age = now_ts() - t["sess_ts"]
        if sess_age < STALE_SEC:
            state = "running"

    # 展示用的 last_activity：running 的任务允许用 sess_ts 显示更真实的活动时刻
    if state == "running" and t.get("sess_ts"):
        t["last_activity"] = max(own_activity or 0, t["sess_ts"])
        age = now_ts() - t["last_activity"]
    else:
        t["last_activity"] = own_activity
    t["age_sec"] = age
    t["age"] = fmt_age(age)
    t["last_activity_at"] = iso(t["last_activity"])
    t["state"] = state
    t["state_label"] = {
        "running": "进行中", "stale": "疑似卡死", "dead": "中途死亡(需补派)",
        "done": "已完成", "blocked": "阻塞", "idle": "已结束", "unknown": "无记录",
    }.get(state, state)

    # 只认产物：日志里的关键报错提示
    tail = "\n".join(t.get("log_tail") or [])
    hints = []
    for pat, msg in (
        (r"429|frequency limit", "疑似限流 429"),
        (r"Sorry, something went wrong", "模型瞬时错误"),
        (r"Unable to connect to the service|connection was interrupted",
         "通道/网络中断（成果通常还在）"),
        (r"ProviderModelNotFoundError", "模型 id 不存在（需注册到 opencode 配置）"),
        (r"User not found", "OpenRouter key 无效/无余额"),
        (r"registry\.bin\.lock", "AS 占用 gradle 锁（构建静默中断）"),
        (r"EPERM.*git\.staging", "CLI git.staging 残留"),
        (r"BUILD SUCCESSFUL", "构建成功"),
        (r"BUILD FAILED", "构建失败"),
    ):
        if re.search(pat, tail, re.I):
            hints.append(msg)
    t["exit_hint"] = "；".join(hints)


# --------------------------------------------------------------------------------------
# 输出
# --------------------------------------------------------------------------------------
def write_state(state: dict) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    tmp = STATE_JSON.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, STATE_JSON)
    # 事件流：状态变化才追加一行
    try:
        prev = {}
        if EVENTS_JSONL.exists():
            for line in EVENTS_JSONL.read_text(encoding="utf-8", errors="replace").splitlines()[-2000:]:
                try:
                    d = json.loads(line)
                    prev[d["id"]] = d["state"]
                except Exception:
                    pass
        with EVENTS_JSONL.open("a", encoding="utf-8") as fh:
            for r in state["rows"]:
                if prev.get(r["id"]) != r["state"]:
                    fh.write(json.dumps({
                        "ts": state["generated_ts"], "at": state["generated_at"],
                        "id": r["id"], "state": r["state"], "label": r["state_label"],
                        "hint": r.get("exit_hint", ""), "age": r.get("age", ""),
                    }, ensure_ascii=False) + "\n")
    except OSError:
        pass


def render_text(state: dict, show_all: bool = False) -> str:
    c = state["counts"]
    out = []
    out.append("CLI 子代理监视  %s" % state["generated_at"])
    out.append("总计 %d ｜ 显示 %d（近 24h 或有状态）｜ 隐藏历史 %d ｜ 进行中 %d ｜ 疑似卡死 %d ｜ 死亡 %d ｜ 阻塞 %d"
               % (c["total"], c["shown"], c["hidden"], c["running"], c["stale"],
                  c.get("dead", 0), c["blocked"]))
    out.append("-" * 108)
    out.append("%-22s %-9s %-14s %-12s %-6s %s" %
               ("任务", "通道", "状态", "最后活动", "PID", "提示"))
    out.append("-" * 108)
    for r in state["rows"]:
        out.append("%-22s %-9s %-14s %-12s %-6s %s" % (
            r["id"][:22], r["kind"] or "-", r["state_label"][:14],
            r["age"], str(r["tool_pid"] or "-"), r.get("exit_hint", "")))
    out.append("-" * 108)
    out.append("进程：")
    for p in state["processes"]:
        out.append("  pid=%-7s %-9s age=%-8s %s" % (p["pid"], p["kind"], p["age"], p["hint"]))
    if not state["processes"]:
        out.append("  （无在跑的 CLI 子代理进程）")
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# 看的界面（单文件 HTML + 轮询 /api/state）
# --------------------------------------------------------------------------------------
HTML = r"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>CLI 子代理监视器</title>
<style>
:root{--bg:#0d1117;--card:#161b22;--card2:#1c2128;--line:#30363d;--fg:#e6edf3;--dim:#8b949e;
--ok:#3fb950;--run:#58a6ff;--stale:#d29922;--dead:#f85149;--idle:#6e7681;--blk:#a371f7}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
font:13px/1.5 "Segoe UI","Microsoft YaHei",system-ui,sans-serif}
header{position:sticky;top:0;background:#0d1117f2;backdrop-filter:blur(8px);
border-bottom:1px solid var(--line);padding:10px 18px;z-index:9}
.hrow{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
h1{font-size:14px;margin:0;font-weight:600;letter-spacing:.3px;white-space:nowrap}
.stats{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
/* 🔴 过滤器按钮：整颗 pill 可点，带 hover / active / 选中态 */
.f{padding:3px 10px;border-radius:999px;border:1px solid var(--line);
background:var(--card);font-size:12px;color:var(--dim);cursor:pointer;user-select:none;
transition:all .12s;display:inline-flex;align-items:center;gap:5px}
.f:hover{background:var(--card2);border-color:#484f58;color:var(--fg)}
.f.on{background:#1f6feb22;border-color:#1f6feb;color:var(--fg);box-shadow:0 0 0 1px #1f6feb55 inset}
.f.dimmed{opacity:.45}
.f b{color:var(--fg);font-weight:600}
.f.zero{opacity:.3;cursor:default}
.f.zero:hover{background:var(--card);border-color:var(--line)}
.tools{display:flex;gap:7px;align-items:center;margin-left:auto;flex-wrap:wrap}
input.q{background:#0b0f14;border:1px solid var(--line);border-radius:7px;color:var(--fg);
padding:4px 9px;font-size:12px;width:170px;outline:none}
input.q:focus{border-color:#1f6feb}
button.b{background:var(--card);border:1px solid var(--line);border-radius:7px;color:var(--dim);
padding:4px 9px;font-size:12px;cursor:pointer;transition:all .12s}
button.b:hover{background:var(--card2);color:var(--fg);border-color:#484f58}
.dot{width:8px;height:8px;border-radius:50%;display:inline-block;vertical-align:1px}
.wrap{padding:14px 18px 40px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(360px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;overflow:hidden;
display:flex;flex-direction:column}
.card.running{border-color:#1f6feb}.card.stale{border-color:#9e6a03}
.card.dead,.card.blocked{border-color:#da3633}.card.done{border-color:#238636}
.card h2{font-size:13px;margin:0;padding:9px 12px;border-bottom:1px solid var(--line);
display:flex;justify-content:space-between;align-items:center;gap:8px;
background:#1c212866}
.card h2 .id{display:flex;align-items:center;gap:7px;min-width:0}
.card h2 .id span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.tag{font-size:11px;padding:2px 7px;border-radius:5px;background:#21262d;color:var(--dim);
border:1px solid var(--line);white-space:nowrap;flex:none}
.body{padding:10px 12px;font-size:12px}
.kv{display:grid;grid-template-columns:74px 1fr;gap:3px 8px;color:var(--dim)}
.kv span:nth-child(2){color:var(--fg);word-break:break-all}
.hint{margin-top:7px;padding:5px 8px;border-radius:6px;background:#2d2305;color:#e3b341;
border:1px solid #9e6a03;font-size:11.5px}
/* 日志：默认折叠成一行摘要，点开才展开 */
.logbar{margin-top:9px;display:flex;align-items:center;gap:7px;flex-wrap:wrap}
.logbar .lbl{color:var(--dim);font-size:11.5px}
details.log{margin-top:6px}
details.log>summary{cursor:pointer;color:var(--dim);font-size:11.5px;padding:4px 7px;
border:1px solid var(--line);border-radius:6px;background:#0b0f14;list-style:none;
display:flex;align-items:center;gap:6px}
details.log>summary::-webkit-details-marker{display:none}
details.log>summary:hover{color:var(--fg);border-color:#484f58}
details.log>summary::before{content:"▶";font-size:9px;transition:transform .12s}
details.log[open]>summary::before{transform:rotate(90deg)}
pre{background:#0b0f14;border:1px solid var(--line);border-radius:7px;padding:8px;
max-height:300px;overflow:auto;font-size:11.5px;color:#adbac7;margin:6px 0 0;
white-space:pre-wrap;word-break:break-all;font-family:"Cascadia Mono",Consolas,monospace}
.notify{margin-bottom:12px;padding:10px 12px;border-radius:8px;background:#0d2818;
border:1px solid #238636;font-size:12px}
.notify b{color:#3fb950}
.muted{color:var(--dim)}a{color:#58a6ff;text-decoration:none}
code{background:#0b0f14;border:1px solid var(--line);border-radius:4px;padding:0 4px;font-size:11.5px}
footer{padding:14px 18px;color:var(--dim);font-size:11.5px}
.empty{color:var(--dim);padding:30px 0;text-align:center}
/* 日志大窗口 */
.modal{position:fixed;inset:0;background:#000a;backdrop-filter:blur(3px);
display:none;z-index:50;padding:5vh 6vw}
.modal.on{display:flex}
.modal .box{background:var(--card);border:1px solid var(--line);border-radius:12px;
width:100%;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 18px 60px #000b}
.modal .mh{display:flex;align-items:center;gap:10px;padding:10px 14px;
border-bottom:1px solid var(--line);background:#1c212866}
.modal .mh .t{font-size:13px;font-weight:600;flex:1;min-width:0;overflow:hidden;
text-overflow:ellipsis;white-space:nowrap}
.modal pre{max-height:none;flex:1;border:0;border-radius:0;margin:0;font-size:12px;
padding:12px 14px}
</style></head><body>
<header>
  <div class="hrow">
    <h1>CLI 子代理监视器</h1>
    <div class="stats" id="stats"></div>
    <div class="tools">
      <input class="q" id="q" placeholder="搜索 ID / 摘要 / 提示…">
      <button class="b" id="btn-all">全部</button>
      <button class="b" id="btn-fold">折叠日志</button>
      <button class="b" id="btn-open">在新窗口打开</button>
      <span class="muted" id="gen" style="font-size:11.5px"></span>
    </div>
  </div>
</header>
<div class="wrap">
  <div id="procs" class="muted" style="margin-bottom:10px"></div>
  <div id="notify"></div>
  <div id="err"></div>
  <div class="grid" id="grid"></div>
</div>
<footer id="foot"></footer>

<div class="modal" id="modal">
  <div class="box">
    <div class="mh">
      <span class="t" id="m-title">日志</span>
      <span class="muted" id="m-meta" style="font-size:11.5px"></span>
      <button class="b" id="m-copy">复制</button>
      <button class="b" id="m-down">下载</button>
      <button class="b" id="m-close">关闭 (Esc)</button>
    </div>
    <pre id="m-body"></pre>
  </div>
</div>

<script>
const CLS={running:'run',stale:'stale',dead:'dead',blocked:'blocked',done:'ok',idle:'idle',unknown:'idle'};
const COL={running:'var(--run)',stale:'var(--stale)',dead:'var(--dead)',
blocked:'var(--blk)',done:'var(--ok)',idle:'var(--idle)',unknown:'var(--idle)'};
const NM={running:'进行中',stale:'疑似卡死',dead:'中途死亡',blocked:'阻塞',
done:'已完成',idle:'已结束',unknown:'无记录'};
const ORDER=['running','stale','dead','blocked','done','idle','unknown'];
function esc(s){return (s==null?'':String(s)).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}

// ---- 视图状态（localStorage 持久化，刷新不丢） ----
const LS='agentmon.v1';
let V = {states:{running:true,stale:true,dead:true,blocked:true,done:true,idle:true,unknown:true},
         q:'', fold:true, all:false};
try{const o=JSON.parse(localStorage.getItem(LS)||'{}');
    V=Object.assign(V,o); V.states=Object.assign({running:true,stale:true,dead:true,blocked:true,done:true,idle:true,unknown:true},o.states||{});}catch(e){}
const save=()=>{try{localStorage.setItem(LS,JSON.stringify(V));}catch(e){}};

let LAST=null;                       // 最近一次 state
const $=id=>document.getElementById(id);

function pass(r){
  if(!V.all && !V.states[r.state]) return false;
  const q=V.q.trim().toLowerCase();
  if(!q) return true;
  return (r.id+' '+(r.summary||'')+' '+(r.exit_hint||'')+' '+(r.kind||'')+' '+
          (r.probe_status||'')+' '+((r.log_tail||[]).join(' '))).toLowerCase().includes(q);
}

function renderStats(){
  const c=(LAST&&LAST.counts)||{};
  let h='<span class="f'+(V.all?' on':'')+'" data-all="1"><b>'+(c.total||0)+'</b> 全部任务</span>';
  h+=ORDER.filter(k=>c[k]!=null&&!V.all).map(k=>{
    const n=c[k]||0, on=!!V.states[k];
    return '<span class="f'+(on?' on':'')+(n?'':' zero')+'" data-st="'+k+'">'+
      '<i class="dot" style="background:'+COL[k]+'"></i>'+NM[k]+' <b>'+n+'</b></span>';
  }).join('');
  $('stats').innerHTML=h;
}

function renderRows(){
  const rows=(LAST&&LAST.rows)||[];
  const vis=rows.filter(pass);
  if(!rows.length){
    $('grid').innerHTML='<div class="empty">还没有扫描到派发/探针记录。<br>派发一个 CLI 子代理后这里会自动出现。</div>';
    $('foot').textContent='扫描范围：'+((LAST&&LAST.projects)||[]).join(' ｜ ');return;
  }
  if(!vis.length){
    $('grid').innerHTML='<div class="empty">当前过滤条件下没有任务。<br>'+
      '<span class="muted">（共 '+rows.length+' 条，被过滤掉 '+(rows.length-vis.length)+' 条 —— 点顶部「全部」可看全）</span></div>';
  }else{
    $('grid').innerHTML=vis.map(r=>{
      const tail=(r.log_tail||[]);
      const hasLog=tail.length>0;
      const summaryLine = hasLog ? tail[tail.length-1] : '';
      const openAttr = (!V.fold && hasLog) ? ' open' : '';
      return `
  <div class="card ${esc(CLS[r.state]||'unknown')}">
    <h2><span class="id"><i class="dot" style="background:${COL[r.state]}"></i><span>${esc(r.id)}</span></span>
        <span class="tag">${esc(r.state_label)}</span></h2>
    <div class="body">
      <div class="kv">
        <span>通道</span><span>${esc(r.kind||'-')}${r.tool_pid?' · pid '+r.tool_pid:''}</span>
        <span>最后活动</span><span>${esc(r.age)} 前${r.last_activity_at?' ('+esc(r.last_activity_at)+')':''}</span>
        <span>探针</span><span>${esc(r.probe_status||'-')} ${esc(r.probe_owner?'· '+r.probe_owner:'')}</span>
        <span>摘要</span><span>${esc(r.summary||'-')}</span>
        <span>日志</span><span>${esc((r.log_bytes||0)+' B')}${r.log_path?' · '+esc(r.log_path.split(/[\\/]/).pop()):''}</span>
      </div>
      ${r.exit_hint?'<div class="hint">⚠ '+esc(r.exit_hint)+'</div>':''}
      ${hasLog?
        '<div class="logbar"><span class="lbl">末行：</span><span class="muted" style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+
          esc(summaryLine)+'</span><button class="b" data-log="'+esc(r.id)+'">放大 ↗</button></div>'+
        '<details class="log"'+openAttr+'><summary>展开日志（'+tail.length+' 行）</summary><pre>'+esc(tail.join('\n'))+'</pre></details>'
        :'<div class="muted" style="margin-top:8px">（日志为空 —— 管道缓冲，进程结束才落盘，0 字节是正常的）</div>'}
    </div>
  </div>`;}).join('');
  }
  const p=(LAST&&LAST.processes)||[];
  $('foot').textContent='显示 '+vis.length+' / '+rows.length+' 条　·　扫描范围：'+((LAST&&LAST.projects)||[]).join(' ｜ ')+
    '　·　判活口径：进程存活 × 会话/日志新鲜度 × 证据新增，三者同时停滞 > 30min 才判卡死　·　只认产物，不认任务状态';
}

function renderAll(){renderStats();renderRows();}

// ---- 过滤交互 ----
$('stats').addEventListener('click',ev=>{
  const el=ev.target.closest('.f'); if(!el) return;
  if(el.dataset.all){ V.all=!V.all; }
  else if(el.dataset.st){ const k=el.dataset.st;
    const anyOn=ORDER.some(x=>V.states[x]);
    if(V.states[k]) V.states[k]=false; else V.states[k]=true;
    // 全关掉 = 视为全选，避免出现"空视图"
    if(!ORDER.some(x=>V.states[x])) ORDER.forEach(x=>V.states[x]=true);
    V.all=false;
  }
  save();renderAll();
});
$('q').addEventListener('input',ev=>{V.q=ev.target.value;save();renderRows();});
$('q').value=V.q;
$('btn-all').addEventListener('click',()=>{V.all=true;ORDER.forEach(k=>V.states[k]=true);save();renderAll();});
$('btn-fold').addEventListener('click',()=>{V.fold=!V.fold;save();
  $('btn-fold').textContent=V.fold?'折叠日志':'展开日志';renderRows();});
$('btn-open').addEventListener('click',()=>window.open(location.href,'_blank'));
$('btn-fold').textContent=V.fold?'折叠日志':'展开日志';

// ---- 日志大窗口 ----
let MODAL_R=null;
function openLog(r){
  MODAL_R=r;
  $('m-title').textContent=r.id+'　·　'+r.state_label;
  $('m-meta').textContent=(r.log_path||'')+'　'+(r.log_bytes||0)+' B　最后活动 '+r.age+' 前';
  $('m-body').textContent=(r.log_tail||[]).join('\n');
  $('modal').classList.add('on');
}
function closeLog(){$('modal').classList.remove('on');MODAL_R=null;}
$('grid').addEventListener('click',ev=>{
  const b=ev.target.closest('button[data-log]'); if(!b) return;
  const r=((LAST&&LAST.rows)||[]).find(x=>x.id===b.dataset.log); if(r) openLog(r);
});
$('m-close').addEventListener('click',closeLog);
$('modal').addEventListener('click',ev=>{if(ev.target.id==='modal') closeLog();});
document.addEventListener('keydown',ev=>{if(ev.key==='Escape') closeLog();});
$('m-copy').addEventListener('click',async()=>{
  try{await navigator.clipboard.writeText(((MODAL_R&&MODAL_R.log_tail)||[]).join('\n'));
      $('m-copy').textContent='已复制';setTimeout(()=>$('m-copy').textContent='复制',1200);}
  catch(e){$('m-copy').textContent='复制失败';setTimeout(()=>$('m-copy').textContent='复制',1500);}
});
$('m-down').addEventListener('click',()=>{
  if(!MODAL_R) return;
  const blob=new Blob([((MODAL_R.log_tail)||[]).join('\n')],{type:'text/plain;charset=utf-8'});
  const a=document.createElement('a');a.href=URL.createObjectURL(blob);
  a.download=MODAL_R.id+'.tail.log';a.click();URL.revokeObjectURL(a.href);
});

async function tick(){
  let s;
  try{s=await (await fetch('/api/state',{cache:'no-store'})).json();}
  catch(e){$('err').innerHTML='<div class="hint" style="margin-bottom:12px">⚠ 拿不到 /api/state：'+esc(e.message)+'　（监视器是否还在跑？）</div>';return;}
  $('err').innerHTML='';
  LAST=s;
  $('gen').textContent=s.generated_at;
  $('procs').textContent='在跑的 CLI 进程 '+s.processes.length+' 个'+
    (s.processes.length?'：'+s.processes.map(p=>p.kind+'#'+p.pid+'('+p.age+(p.hint?' '+p.hint:'')+')').join('  '):'');
  // 完工通知（哨兵产物）
  try{
    const n=await (await fetch('/api/notify',{cache:'no-store'})).json();
    const f=n.fired||{}, ks=Object.keys(f).reverse(), pend=n.pending||[];
    $('notify').innerHTML=(ks.length||pend.length)?
      '<div class="notify"><b>完工通知</b>　<span class="muted">（主代理读 notify.json / /api/notify，无需阻塞等待）</span><br>'+
      ks.slice(0,12).map(k=>{const v=f[k];return '✅ <b>'+esc(k)+'</b> → '+esc(v.state_label)+
        '　<span class="muted">'+(v.at||'')+'　'+(v.hint||'')+'</span>';}).join('<br>')+
      (ks.length>12?'<br><span class="muted">…另有 '+(ks.length-12)+' 条</span>':'')+
      (pend.length?'<br><span class="muted">仍在等：'+pend.map(esc).join(', ')+'</span>':'')+'</div>':'';
  }catch(e){}
  renderAll();
}
tick();setInterval(tick,3000);
</script></body></html>
"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # 静音
        pass

    def _send(self, code: int, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionAbortedError):
            pass

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            self._send(200, HTML.encode("utf-8"), "text/html; charset=utf-8")
        elif path == "/api/state":
            try:
                body = STATE_JSON.read_bytes()
            except OSError:
                body = b'{"rows":[],"counts":{},"processes":[],"projects":[],"generated_at":""}'
            self._send(200, body, "application/json; charset=utf-8")
        elif path == "/api/notify":
            # 完工通知（非阻塞哨兵产物）；主代理也可直接读这个文件
            try:
                body = NOTIFY_JSON.read_bytes()
            except OSError:
                body = b'{"fired":{},"pending":[]}'
            self._send(200, body, "application/json; charset=utf-8")
        else:
            self._send(404, b"not found", "text/plain")


def is_port_free(port: int, host: str = "127.0.0.1") -> bool:
    """判断端口是否可绑定。

    🔴 **不要**先 setsockopt(SO_REUSEADDR) 再 bind 来判断占用：
    Windows 上 SO_REUSEADDR 语义宽松，即使已有进程在 LISTENING 也能 bind 成功，
    于是"已监听"被误判成"空闲" ⇒ `--ensure` 跳过探测一路再起新实例（实测起过 3 个）。
    正确姿势：先真连一次，连得上就是被占用；连不上再尝试 bind。
    """
    # 1) 能连上 ⇒ 一定有人监听
    try:
        with socket.create_connection((host, port), timeout=0.6):
            return False
    except OSError:
        pass
    # 2) 连不上 ⇒ 试 bind（不加 SO_REUSEADDR，避免宽松语义）
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind((host, port))
            return True
        except OSError:
            return False


def _probe_our_service(port: int, host: str = "127.0.0.1") -> bool:
    """裸 HTTP 请求判断该端口上是不是我们的界面（拿 /api/state 探一下）。

    直接用 socket，不引入任何代理/证书逻辑 —— 幂等检测必须可靠，
    否则每次 --ensure 都会再起一个实例（实测踩过：起了 3 个）。
    """
    try:
        with socket.create_connection((host, port), timeout=3) as s:
            s.sendall(b"GET /api/state HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n")
            buf = b""
            # 🔴 别只读 4096B 就下结论：HTTP 响应头本身就要 ~200B，state.json 一大
            # 正文就会被截在 4096 边界外，`"rows"` 恰好落空 ⇒ 幂等失效、每次
            # --ensure 都再起一个实例（实测起过 3 个）。这里读到 EOF 或 64KB 为止。
            while len(buf) < 65536:
                chunk = s.recv(8192)
                if not chunk:
                    break
                buf += chunk
        # 只认我们自己界面的特征串（有 header 里的 /api/state 语义 + JSON 骨架）
        return (b'"rows"' in buf) or (b'"counts"' in buf) or (b'"projects"' in buf)
    except OSError:
        return False


def pick_port(preferred: int) -> int:
    if is_port_free(preferred):
        return preferred
    for p in range(preferred + 1, preferred + 40):
        if is_port_free(p):
            return p
    return 0


def serve(port: int) -> None:
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    sys.stderr.write("[agent_monitor] 界面已启动： http://127.0.0.1:%d\n" % port)
    sys.stderr.flush()
    httpd.serve_forever()


# --------------------------------------------------------------------------------------
# 完工通知（非阻塞哨兵）
# --------------------------------------------------------------------------------------
# 🔴 设计要点：主代理**绝不能在命令行里前台阻塞等子代理**（那样它会卡住整轮对话，
# 用户就没法及时追加/派发新任务 —— 用户 2026-09-24 明确抱怨过这一点）。
# 所以流程改成"登记 + 轮询"：
#   ① 主代理派发后跑一次 `--wait-done <ID1,<ID2>`（立即返回，只写 waiting.json）
#   ② 主代理正常结束本轮回复（用户可继续追加任务）
#   ③ 本扫描循环在后台持续比对；某 ID 达到终态（done/blocked/dead/idle）就写 notify.json
#   ④ 主代理下轮开始时读一次 notify.json 即知谁完工了（零阻塞）
# 宿主（WorkBuddy/TRAE）的 `<task-notification>` 只对它们自己管的进程有效，
# 对 CLI 子代理**根本不触发** ⇒ 这套文件哨兵是两个宿主里都生效的通用解。
TERMINAL_STATES = ("done", "blocked", "dead", "idle")


def check_waiting(state: dict) -> None:
    """比对 waiting.json，把已达终态的任务写入 notify.json（幂等：已通知的不重复）。"""
    wf = OUT_DIR / "waiting.json"
    if not wf.exists():
        return
    try:
        waiting = json.loads(wf.read_text(encoding="utf-8") or "{}")
    except Exception:
        return
    if not waiting:
        return

    nf = OUT_DIR / "notify.json"
    try:
        fired = json.loads(nf.read_text(encoding="utf-8") or "{}")
    except Exception:
        fired = {}
    if not isinstance(fired, dict):
        fired = {}
    fired.setdefault("fired", {})   # {id: {...}} 已通知过的

    by_id = {r["id"]: r for r in state.get("rows", [])}
    # 任务可能因为太旧被 rows 过滤掉，所以也查全量（写 state 时保留了 total）
    changed = False
    for tid, meta in list(waiting.items()):
        if tid in fired["fired"]:
            continue
        r = by_id.get(tid)
        if not r:
            # 只在等待表里、但已被 24h 过滤 ⇒ 说明久无活动，判 idle
            if meta.get("since") and (now_ts() - meta["since"]) > 1800:
                fired["fired"][tid] = {
                    "id": tid, "state": "idle", "state_label": "已结束(无近期活动)",
                    "at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                    "hint": "超过 30min 无任何活动且不在显示窗口内", "age": ">30m",
                }
                changed = True
            continue
        if r["state"] in TERMINAL_STATES:
            fired["fired"][tid] = {
                "id": tid, "state": r["state"], "state_label": r["state_label"],
                "at": state["generated_at"], "age": r.get("age", ""),
                "hint": r.get("exit_hint", ""), "summary": r.get("summary", ""),
                "pid": r.get("tool_pid"), "log": (r.get("log_path") or "").split("\\")[-1],
            }
            changed = True

    if changed:
        fired["updated_at"] = state["generated_at"]
        fired["updated_ts"] = state["generated_ts"]
        fired["pending"] = [i for i in waiting if i not in fired["fired"]]
        tmp = nf.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(fired, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, nf)
        # 清掉已通知的等待项，避免 waiting 无限增长
        rest = {k: v for k, v in waiting.items() if k not in fired["fired"]}
        wf.write_text(json.dumps(rest, ensure_ascii=False, indent=2), encoding="utf-8")


def loop_scan(interval: int, once: bool = False) -> None:
    while True:
        try:
            st = scan_engine()
            write_state(st)
            check_waiting(st)
            if once:
                return
        except Exception as exc:  # 采集失败不能拖垮监视器
            sys.stderr.write("[agent_monitor] scan error: %r\n" % (exc,))
        time.sleep(interval)


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------
def _browser_env() -> dict:
    """给"打开浏览器"用的环境变量：**显式清掉代理**。

    🔴 本机装了代理软件（`127.0.0.1:7897`，Clash 类）。系统 `ProxyEnable=0` 时 registry 不拦，
    但代理客户端常自己设 `HTTP_PROXY/HTTPS_PROXY` 或走 TUN ⇒ 浏览器把 `127.0.0.1` 也塞进代理链路，
    于是本地页面**打不开**（用户实测即是此症）。所以打开浏览器时显式把代理变量清空，
    并设 `NO_PROXY=127.0.0.1,localhost` 兜底。
    """
    env = dict(os.environ)
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
              "ALL_PROXY", "all_proxy", "GLOBAL_PROXY"):
        env.pop(k, None)
    env["NO_PROXY"] = "127.0.0.1,localhost,::1"
    env["no_proxy"] = env["NO_PROXY"]
    return env


def open_browser(port: int) -> bool:
    """用**系统默认浏览器**打开界面。返回是否成功发起。"""
    url = "http://127.0.0.1:%d/" % port
    try:
        if os.name == "nt":
            # 走 cmd 的 start 才能用系统默认程序；不用 os.startfile（它不接受参数且在某些场景静默失败）
            subprocess.Popen(["cmd", "/c", "start", "", url],
                             env=_browser_env(), shell=False,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                             close_fds=True)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", url], env=_browser_env())
        else:
            subprocess.Popen(["xdg-open", url], env=_browser_env())
        return True
    except Exception as exc:
        sys.stderr.write("[agent_monitor] 打开浏览器失败：%r（请手动访问 %s）\n" % (exc, url))
        return False


def _install_autostart(port: int = 8787) -> tuple[bool, str]:
    """注册 Windows 计划任务，让界面**脱离任何宿主进程树**独立运行。

    🔴 血泪教训：`--ensure` 用 `subprocess.Popen(DETACHED_PROCESS)` 拉起的实例，
    在宿主（WorkBuddy / TRAE）**会话结束时会连带被杀掉** —— 实测 TRAE 里 agent 跑完
    `--wait-done` 后会话收尾，界面进程就静默消失（server.log 无异常，state.json 停止更新）。
    `DETACHED_PROCESS` 只脱离控制台，**并没有脱离 Job Object**；宿主若用了 Job，
    子进程仍会被一起收割。
    解法：交给 **任务计划程序**（Task Scheduler）拉起 —— 它的父进程是 svchost，
    与任何终端会话无关，宿主怎么开关都影响不到它。

    ⚠️ `schtasks /TR` 里嵌套引号极易被解析坏（任务建了却跑不起来，实测踩过）。
    所以**先落一个 .cmd 包装脚本**（路径无空格），/TR 只指向它。
    """
    task = "AgentMonitor"
    launcher = OUT_DIR / "_launch.cmd"
    try:
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        launcher.write_text(
            "@echo off\r\n"
            'cd /d "%s"\r\n'
            '"%s" "%s" --watch --serve %d --interval %d >> "%s" 2>&1\r\n'
            % (HERE, SELF_WIN, ENTRY, port, POLL_SEC, OUT_DIR / "server.log"),
            encoding="ascii", errors="replace")
    except Exception as exc:
        return False, "写包装脚本失败：%r" % (exc,)

    cmd = ["schtasks", "/Create", "/TN", task, "/TR", '"%s"' % launcher,
           "/SC", "ONCE", "/ST", "00:00", "/F"]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=30,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        out = (r.stdout or b"").decode("utf-8", "replace").strip()
        err = (r.stderr or b"").decode("utf-8", "replace").strip()
        if r.returncode == 0:
            return True, out or "计划任务已注册"
        return False, (err or out or "schtasks 返回 %d" % r.returncode)
    except Exception as exc:
        return False, repr(exc)


def _run_autostart_task() -> bool:
    """立即触发那个计划任务（真正独立地拉起界面）。"""
    cmd = ["schtasks", "/Run", "/TN", "AgentMonitor"]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=30,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return r.returncode == 0
    except Exception:
        return False


def _spawn_detached(port: int, interval: int = POLL_SEC) -> int:
    """把一个**完全脱离当前进程树**的界面进程拉起来。返回新进程 pid（失败 0）。

    🔴 为什么不用 `subprocess.Popen(DETACHED_PROCESS)` 单独一个标志：
    它只脱离控制台，**不脱离 Job Object**。宿主（WorkBuddy / TRAE）在会话收尾时
    若 kill 自己创建的 job，子进程会被一起收割 —— 实测界面就是这么静默消失的
    （`server.log` 无异常、`state.json` 停在某一刻）。

    ✅ 正确组合：`DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB`。
    其中 **`CREATE_BREAKAWAY_FROM_JOB` 才是"跳出宿主 Job"的关键**。
    注意：若父进程的 job 未设 `JOB_OBJECT_LIMIT_BREAKAWAY_OK`，这个标志会失败（OSError），
    此时退回不带它的组合（至少仍脱离控制台）。

    ❌ 也别绕 `cmd /c start`：start 的引号/标题占位/`/D` 组合极易被解析坏，
    实测要么起不来、要么留下一个**常驻 cmd 窗口**（`cmd /K _launch.cmd`）把 python 挂成子进程。
    直接用 CreateProcess 语义最干净。
    """
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    log = OUT_DIR / "server.log"
    argv = [SELF_WIN, str(ENTRY), "--watch", "--serve", str(port), "--interval", str(interval)]
    base = getattr(subprocess, "DETACHED_PROCESS", 0x8) | \
        getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x200) | \
        getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
    breakout = getattr(subprocess, "CREATE_BREAKAWAY_FROM_JOB", 0x01000000)
    si = subprocess.STARTUPINFO()
    si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    si.wShowWindow = 0  # SW_HIDE，绝不闪窗
    for flags in (base | breakout, base):
        try:
            with log.open("ab") as fh:
                p = subprocess.Popen(argv, stdout=fh, stderr=fh,
                                     stdin=subprocess.DEVNULL,
                                     creationflags=flags, startupinfo=si,
                                     close_fds=True)
            return p.pid
        except OSError:
            continue
        except Exception:
            break
    return 0


def ensure_via_task(port: int = 8787) -> str:
    """用计划任务确保界面在跑（最可靠的路径）。返回 'task' / 'popen' / 'fail'。

    ⚠️ 实测 `schtasks /TR` 里嵌套引号极易被解析坏（任务建了但跑不起来）。
    所以这里**先写一个 .cmd 包装脚本**，让 /TR 只指向这个不带空格的脚本路径。
    """
    ok, msg = _install_autostart()
    if not ok:
        return "popen"
    if _run_autostart_task():
        return "task"
    return "popen"


def main() -> int:
    ap = argparse.ArgumentParser(description="全局 CLI 子代理任务监视器")
    ap.add_argument("--serve", type=int, nargs="?", const=8787, default=None,
                    metavar="PORT", help="启动可视化界面（默认端口 8787，占用则自动+1）")
    ap.add_argument("--watch", action="store_true", help="持续扫描并写 state.json")
    ap.add_argument("--interval", type=int, default=POLL_SEC, help="扫描周期秒（默认 3）")
    ap.add_argument("--json", action="store_true", help="打印一次状态 JSON 后退出")
    ap.add_argument("--text", action="store_true", help="打印一次表格后退出（默认行为）")
    ap.add_argument("--ensure", action="store_true",
                    help="确保界面在跑（幂等）：已监听则直接返回，否则自动拉起后台；配合 --serve 指定端口")
    ap.add_argument("--open", dest="do_open", action="store_true",
                    help="用系统浏览器打开界面（自动绕过本机代理）；可单独用，也可与 --ensure 合用")
    ap.add_argument("--daemon", action="store_true",
                    help="注册并启动「计划任务」版界面：**脱离任何宿主进程树**，"
                         "关掉 WorkBuddy/TRAE 也不会被连带杀掉（推荐日常用这个）")
    ap.add_argument("--stop", action="store_true", help="停止界面（结束进程 + 注销计划任务）")
    ap.add_argument("--wait-done", metavar="IDS", default=None,
                    help="**非阻塞哨兵**：把逗号分隔的任务 ID 记入 .agent_monitor/waiting.json，"
                         "界面/CLI 一旦发现全部完工即写 .agent_monitor/notify.json 供主代理读取。"
                         "主代理派发后调一次即返回，**绝不要前台阻塞等**")
    args = ap.parse_args()

    if args.wait_done is not None:
        # 登记"等这些任务完工"，写完立刻返回。真正的判定在扫描循环里做。
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        ids = [x.strip() for x in args.wait_done.split(",") if x.strip()]
        wf = OUT_DIR / "waiting.json"
        try:
            old = json.loads(wf.read_text(encoding="utf-8")) if wf.exists() else {}
        except Exception:
            old = {}
        old.update({i: {"since": now_ts()} for i in ids})
        wf.write_text(json.dumps(old, ensure_ascii=False, indent=2), encoding="utf-8")
        print("已登记待观察任务 %d 个：%s" % (len(ids), ", ".join(ids)))
        print("（非阻塞。完工通知会写到 %s ；主代理读它即可，不要在命令行里 sleep 等。）" % (OUT_DIR / "notify.json"))
        return 0

    if args.stop:
        # 结束所有界面进程 + 注销计划任务
        killed = 0
        if os.name == "nt":
            ps = ("Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*agent_monitor*' "
                  "-and $_.CommandLine -notlike '*--ensure*' -and $_.CommandLine -notlike '*--stop*' } "
                  "| Select-Object -ExpandProperty ProcessId")
            try:
                r = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                                   capture_output=True, timeout=30,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                for tok in (r.stdout or b"").decode("utf-8", "replace").split():
                    try:
                        os.kill(int(tok), 9)
                        killed += 1
                    except Exception:
                        pass
            except Exception:
                pass
            try:
                subprocess.run(["schtasks", "/Delete", "/TN", "AgentMonitor", "/F"],
                               capture_output=True, timeout=30,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            except Exception:
                pass
        print("已停止 %d 个界面进程，并注销了计划任务 AgentMonitor。" % killed)
        return 0

    if args.daemon:
        port = args.serve or 8787
        # 已在本跑就直接返回
        for p in range(port, port + 40):
            if not is_port_free(p) and _probe_our_service(p):
                print("界面已在运行： http://127.0.0.1:%d" % p)
                if args.do_open:
                    open_browser(p)
                return 0
        ok, msg = _install_autostart(port)
        started = False
        if ok:
            print("计划任务已注册（%s）" % msg)
            started = _run_autostart_task()
            if not started:
                print("触发计划任务失败，改用独立进程方式。", file=sys.stderr)
        else:
            print("注册计划任务失败：%s" % msg, file=sys.stderr)
            print("改用独立进程方式（cmd start，同样脱离当前进程树）。", file=sys.stderr)
        if not started:
            _spawn_detached(port)
        shown = None
        for _ in range(40):
            time.sleep(0.5)
            for p in range(port, port + 40):
                if not is_port_free(p) and _probe_our_service(p):
                    shown = p
                    break
            if shown:
                break
        if shown:
            print("界面已启动（独立于宿主）： http://127.0.0.1:%d" % shown)
            if args.do_open:
                open_browser(shown)
            return 0
        print("已尝试启动，但暂未探到端口。请稍等几秒，或看 %s" % (OUT_DIR / "server.log"), file=sys.stderr)
        return 1

    if args.ensure:
        port = args.serve or 8787
        already = None
        # 先用纯 socket 探测（比 urllib 稳，不会被代理/证书层吞掉异常）
        for p in range(port, port + 40):
            if is_port_free(p):
                continue
            if _probe_our_service(p):
                already = p
                break
            # 端口被占但不是我们的界面 ⇒ 继续往后找
        if already:
            print("界面已在运行： http://127.0.0.1:%d" % already)
            if args.do_open:
                open_browser(already)
            return 0

        # 🔴 用 `cmd /c start` 起一个**与当前进程树无关**的独立进程（Windows）；
        # 其他平台退回普通后台。
        # 别用 subprocess.Popen(DETACHED_PROCESS)：那只脱离控制台、**不脱离 Job Object**，
        # 宿主（WorkBuddy/TRAE）会话收尾时会连带杀掉它（实测界面就是这么静默消失的）。
        # 这里**不碰计划任务**（schtasks 可能阻塞）—— 需要开机/长期驻留就用 `--daemon`。
        if os.name == "nt":
            _spawn_detached(port, args.interval)
        else:
            log = OUT_DIR / "server.log"
            OUT_DIR.mkdir(parents=True, exist_ok=True)
            with log.open("ab") as fh:
                subprocess.Popen(
                    [sys.executable, str(Path(__file__).resolve()),
                     "--watch", "--serve", str(port), "--interval", str(args.interval)],
                    stdout=fh, stderr=fh, stdin=subprocess.DEVNULL)
        for _ in range(30):
            time.sleep(0.4)
            for p in range(port, port + 40):
                if not is_port_free(p) and _probe_our_service(p):
                    print("界面已拉起（独立进程）： http://127.0.0.1:%d" % p)
                    if args.do_open:
                        open_browser(p)
                    return 0
        print("已尝试拉起界面（端口 %d），请查看 %s" % (port, OUT_DIR / "server.log"))
        return 0

    if args.do_open:
        # 单独用 --open：先找我们已起的实例，找不到就拉起再开
        for p in range(args.serve or 8787, (args.serve or 8787) + 40):
            if not is_port_free(p) and _probe_our_service(p):
                print("界面已在运行： http://127.0.0.1:%d" % p)
                open_browser(p)
                return 0
        print("界面还没起。请先跑： --ensure --serve 8787", file=sys.stderr)
        return 1

    if args.json or args.text:
        st = scan_engine()
        write_state(st)
        print(json.dumps(st, ensure_ascii=False, indent=2) if args.json else render_text(st))
        return 0

    # 默认：扫描循环（可同时开界面）
    if args.serve is not None:
        port = pick_port(args.serve)
        threading.Thread(target=serve, args=(port,), daemon=True).start()
    loop_scan(args.interval, once=not args.watch and args.serve is None)
    if args.watch or args.serve is not None:
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
