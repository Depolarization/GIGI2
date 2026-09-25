#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""task_scheduler.py —— 主从全自动交接的「调度代理」（方案 B）

## 解决的根因
主代理（WorkBuddy/Trae 里的 agent）**不是常驻进程**，只是一次 API 调用：turn 一结束进程即退出。
因此"子代理完工 → 唤醒主代理"这一跳**在架构上不可能由推送完成**（没有可推送的目标）。

## 解法
把「守候 + 交接」的职责从主代理**下放到一个独立常驻进程**（本脚本）。
它是普通 Python 进程，用 Windows 任务计划程序拉起（父进程 svchost），
**完全不受宿主 turn 结束影响**，可以活几小时。

链条：
    主代理派发任务 + 写下"接力清单" → 主代理结束 turn（用户可随时追加）
      本调度器在后台：守候 → 检测第 N 棒完工 → 自动拉起第 N+1 棒 → …
      全部棒次完工 → 写 finished.json + 可选拉起"收尾代理"
    用户/主代理下次运行时读 scheduler 状态即知全局

## 与 agent_monitor.py 的分工
- `agent_monitor.py`：**只读观测**（看板 + 完工哨兵 notify.json）。
- `task_scheduler.py`（本脚本）：**有副作用**（真的拉起下一棒 CLI 子代理）。
  两者独立，互不依赖。

## 用法
    # 定义一个接力链（JSON 落盘）
    python task_scheduler.py --chain .task/chains/<CHAIN_ID>.json --daemon

    # 查看状态
    python task_scheduler.py --status [--chain <CHAIN_ID>]

    # 停止
    python task_scheduler.py --stop [--chain <CHAIN_ID>]
    python task_scheduler.py --stop-all

## 接力清单格式（.task/chains/<CHAIN_ID>.json）
{
  "id": "DLG-FIX",
  "cwd": "D:/AndroidStudioProjects/GIGI",
  "final_agent": {              # 可选：全部棒次完成后自动拉起的收尾代理
    "id": "VERIFY-FINAL",
    "dispatch": ".task/dispatch/VERIFY-FINAL.txt",
    "model": "Qwen3.8-Max"
  },
  "steps": [
    { "id": "T6-DLGFIX",        # 已有任务：只等它完工，不重新派发
      "wait_only": true },
    { "id": "R7-DLGFIX-APPLY",  # 待派任务：等上一棒完工后自动派发
      "dispatch": ".task/dispatch/R7-DLGFIX-APPLY.txt",
      "model": "Qwen3.8-Max",
      "depends_on": ["T6-DLGFIX"] },
    { "id": "V7-APPLY",         # 验收棒
      "dispatch": ".task/dispatch/V7-APPLY.txt",
      "model": "Qwen3.8-Max",
      "depends_on": ["R7-DLGFIX-APPLY"] }
  ]
}

## 安全边界（硬性）
- 只读工程之外什么都不改；本脚本只写 `.task/chains/` 下的状态文件与 `.agent_work/*.log`。
- **绝不**代替主代理做验收判定；它只负责"把下一棒拉起来"。
- 每棒最多重试 1 次（`max_retry`，默认 1）；重试后仍失败 ⇒ 整链置 `blocked` 并停止，
  **不无脑往下派**（避免把坏产物喂给下一棒）。
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJ = HERE.parent
CHAINS_DIR = PROJ / ".task" / "chains"
AGENT_WORK = PROJ / ".agent_work"

CLI = Path("C:/Users/oscur/.qoder-cn/bin/qoderclicn/qoderclicn.exe")
DEFAULT_MODEL = "Qwen3.8-Max"
SCAN_INTERVAL = 20          # 秒：守候扫描间隔
STALE_LIMIT = 45 * 60       # 秒：某棒超过这个时长无任何活动 ⇒ 判卡死（不再等）
RETRY_LIMIT = 1


def now_ts() -> float:
    return time.time()


def iso(ts: float | None) -> str:
    if not ts:
        return "-"
    return datetime.datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S")


def log(chain_dir: Path, msg: str) -> None:
    line = "[%s] %s" % (datetime.datetime.now().strftime("%H:%M:%S"), msg)
    try:
        with open(chain_dir / "scheduler.log", "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except OSError:
        pass


# --------------------------------------------------------------------------------------
# 完工判定（与 agent_monitor.py 同一口径，但**独立实现**，不 import 以免耦合）
# --------------------------------------------------------------------------------------
def _resolve_probe_file(proj: Path, tid: str) -> Path | None:
    """把任务 ID 解析到真实探针文件。

    真实探针名常带后缀（`T4b` → `T4b-GUARD.md`），故支持三级回退：
      ① 精确同名  ② 以 `<tid>-` 开头  ③ 以 `<tid>` 结尾
    多候选时取 mtime 最新者。
    """
    pd = proj / ".task" / "progress"
    if not pd.is_dir():
        return None
    exact = pd / ("%s.md" % tid)
    if exact.exists():
        return exact
    cands = [p for p in pd.glob("*.md")
             if p.stem == tid or p.stem.startswith(tid + "-") or p.stem.endswith("-" + tid)]
    if not cands:
        return None
    return max(cands, key=lambda p: p.stat().st_mtime)


def _read_probe(proj: Path, tid: str) -> tuple[str, float | None]:
    """返回 (status, probe_mtime)。"""
    p = _resolve_probe_file(proj, tid)
    if p is None:
        return "", None
    try:
        m = p.stat().st_mtime
        text = p.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return "", None
    # 兼容两种探针写法：`status: done` 与 markdown 列表 `- status: done`
    mst = re.search(r"(?m)^[\s>*-]*status\s*[:：]\s*[`*]*([A-Za-z_]+)", text)
    return (mst.group(1).lower() if mst else ""), m


def _pid_alive_for(proj: Path, tid: str) -> int | None:
    """在进程表里找**命令行含该任务派单/日志路径**的 qoderclicn 进程。"""
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-CimInstance Win32_Process | Where-Object {$_.Name -like '*qoderclicn*'} | "
             "Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"],
            capture_output=True, timeout=25,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        ).stdout.decode("utf-8", "replace") or "[]"
        rows = json.loads(out)
    except Exception:
        return None
    if isinstance(rows, dict):
        rows = [rows]
    e = re.escape(tid)
    # 派单/日志文件名可能是 tid 本身，也可能带后缀（如 T4b-GUARD）
    pats = [re.compile(r"dispatch[\\/]%s(?:-[\w.]+)?\.txt" % e, re.I),
            re.compile(r"\.agent_work[\\/]%s(?:-[\w.]+)?\.log" % e, re.I)]
    for r in rows or []:
        cmd = r.get("CommandLine") or ""
        if any(p.search(cmd) for p in pats):
            return int(r.get("ProcessId") or 0) or None
    return None


def _newest_session_for(proj: Path, tid: str) -> float | None:
    """该任务在 qoder 会话 transcript 里的最后活动时刻（弱佐证，仅用于判卡死）。"""
    root = Path.home() / ".qoder-cn" / "logs" / "sessions"
    if not root.is_dir():
        return None
    best = None
    pat = re.compile(r"(?<![A-Za-z0-9_-])%s(?![A-Za-z0-9_-])" % re.escape(tid))
    for seg in root.glob("*/*/segments/*.jsonl"):
        try:
            m = seg.stat().st_mtime
        except OSError:
            continue
        if best is not None and m <= best:
            continue
        if now_ts() - m > 6 * 3600:
            continue
        try:
            with open(seg, "rb") as fh:
                fh.seek(0, 2)
                size = fh.tell()
                fh.seek(max(0, size - 65536))
                tail = fh.read().decode("utf-8", "replace")
        except OSError:
            continue
        if len(pat.findall(tail)) >= 2:
            best = m
    return best


def probe_state(proj: Path, tid: str) -> dict:
    """返回该棒的状态：pending / running / done / blocked / dead。"""
    st, pm = _read_probe(proj, tid)
    pid = _pid_alive_for(proj, tid)
    sess = _newest_session_for(proj, tid)
    # last/age 只认探针 mtime；sess 是弱佐证，仅作 stale/dead 的单向救援，不得抬升活动时间
    acts = [pm] if pm else []
    last = max(acts) if acts else None
    return {
        "id": tid, "probe_status": st, "probe_ts": pm, "pid": pid,
        "sess_ts": sess, "last_activity": last,
        "age": (now_ts() - last) if last else None,
    }


def classify(s: dict) -> str:
    st = s["probe_status"]
    if st == "done":
        return "done"
    if st == "blocked":
        return "blocked"
    if s["pid"]:
        if s["age"] is not None and s["age"] > STALE_LIMIT:
            # 单向救援：pid 存活且 sess 仍新鲜 ⇒ 降级为 running；
            # sess 永不把无 pid 的条目抬成 running（🔴 见派单红线）。
            sess = s.get("sess_ts")
            if sess is not None and (now_ts() - sess) < STALE_LIMIT:
                return "running"
            return "stale"
        return "running"
    if st == "doing":
        # 探针说在干、进程没了 ⇒ 刚结束还在落盘 or 真死了
        if s["age"] is not None and s["age"] < 120:
            return "running"
        return "dead"
    # 从未开工（无探针） 或 已结束但状态非 done
    return "pending" if not st else "idle"


# --------------------------------------------------------------------------------------
# 派发 / 守候
# --------------------------------------------------------------------------------------
def dispatch(chain: dict, step: dict, chain_dir: Path) -> int | None:
    """派发一棒：等价于主代理的 CLI 派发命令。返回 pid（拿不到返回 None）。"""
    proj = Path(chain["cwd"])
    tid = step["id"]
    df = proj / step["dispatch"]
    if not df.exists():
        log(chain_dir, "❌ 派单不存在，无法派发 %s：%s" % (tid, df))
        return None
    model = step.get("model") or DEFAULT_MODEL
    AGENT_WORK.mkdir(parents=True, exist_ok=True)
    logf = AGENT_WORK / ("%s.log" % tid)
    try:
        prompt = df.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        log(chain_dir, "❌ 读派单失败 %s：%s" % (df, e))
        return None
    cmd = [str(CLI), "-p", "--permission-mode", "bypass_permissions", "-m", model, prompt]
    try:
        with open(logf, "wb") as lf:
            p = subprocess.Popen(
                cmd, cwd=str(proj), stdout=lf, stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        log(chain_dir, "▶ 已派发 %s（pid=%s, model=%s）" % (tid, p.pid, model))
        return p.pid
    except Exception as e:
        log(chain_dir, "❌ 派发 %s 失败：%s" % (tid, e))
        return None


def save_state(chain: dict, chain_dir: Path) -> None:
    p = chain_dir / "state.json"
    tmp = p.with_suffix(".tmp")
    try:
        tmp.write_text(json.dumps(chain, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(p)
    except OSError:
        pass


def run_chain(chain_path: Path) -> int:
    chain = json.loads(chain_path.read_text(encoding="utf-8"))
    # 状态落盘到「每条链一个子目录」，避免多条链互相覆盖
    chain_dir = chain_path.parent / chain_path.stem
    chain_dir.mkdir(parents=True, exist_ok=True)
    proj = Path(chain["cwd"])
    (chain_dir / "scheduler.pid").write_text(str(os.getpid()), encoding="utf-8")
    log(chain_dir, "=== 调度器启动 chain=%s pid=%d ===" % (chain.get("id"), os.getpid()))

    steps = chain.get("steps") or []
    done_ids: set[str] = set()
    retries: dict[str, int] = {}
    chain["status"] = "running"
    chain["started_ts"] = now_ts()

    while True:
        all_done = True
        for step in steps:
            tid = step["id"]
            if tid in done_ids:
                continue

            # 依赖未满足 ⇒ 本轮跳过
            deps = step.get("depends_on") or []
            if any(d not in done_ids for d in deps):
                all_done = False
                continue

            s = probe_state(proj, tid)
            st = classify(s)

            # ① 只等待型：已存在任务，不派发
            if step.get("wait_only"):
                if st == "done":
                    log(chain_dir, "✅ [wait_only] %s 已完成" % tid)
                    done_ids.add(tid)
                    continue
                if st == "blocked":
                    log(chain_dir, "⛔ [wait_only] %s 阻塞 ⇒ 整链停止" % tid)
                    chain["status"] = "blocked"
                    chain["blocked_at"] = tid
                    chain["note"] = "wait_only 任务阻塞"
                    save_state(chain, chain_dir)
                    return 2
                all_done = False
                continue

            # ② 待派任务
            if tid not in chain.setdefault("dispatched", {}):
                pid = dispatch(chain, step, chain_dir)
                if pid is None:
                    chain["status"] = "blocked"
                    chain["blocked_at"] = tid
                    chain["note"] = "派发失败"
                    save_state(chain, chain_dir)
                    return 2
                chain["dispatched"][tid] = {"pid": pid, "at": now_ts(), "round": 0}
                save_state(chain, chain_dir)
                all_done = False
                continue

            if st == "done":
                log(chain_dir, "✅ %s 已完成" % tid)
                done_ids.add(tid)
                chain["dispatched"][tid]["done_at"] = now_ts()
                save_state(chain, chain_dir)
                continue

            if st == "blocked":
                log(chain_dir, "⛔ %s 探针报 blocked ⇒ 整链停止（避免坏产物下传）" % tid)
                chain["status"] = "blocked"
                chain["blocked_at"] = tid
                chain["note"] = "任务自报 blocked"
                save_state(chain, chain_dir)
                return 2

            # ③ 卡死/死亡 ⇒ 有限重试
            if st in ("stale", "dead"):
                n = retries.get(tid, 0)
                if n < RETRY_LIMIT:
                    retries[tid] = n + 1
                    log(chain_dir, "♻ %s 判定 %s（age=%s）⇒ 重试第 %d 次"
                        % (tid, st, s["age"] and int(s["age"]), n + 1))
                    time.sleep(5)
                    pid = dispatch(chain, step, chain_dir)
                    if pid:
                        chain["dispatched"][tid] = {"pid": pid, "at": now_ts(), "round": n + 1}
                        save_state(chain, chain_dir)
                        all_done = False
                        continue
                log(chain_dir, "💀 %s 判定 %s 且重试已用尽 ⇒ 整链停止" % (tid, st))
                chain["status"] = "dead"
                chain["blocked_at"] = tid
                chain["note"] = "重试用尽"
                save_state(chain, chain_dir)
                return 3

            all_done = False

        if all_done:
            log(chain_dir, "🎉 全部棒次完成")
            chain["status"] = "finished"
            chain["finished_ts"] = now_ts()

            fa = chain.get("final_agent")
            if fa and not chain.get("final_dispatched"):
                pid = dispatch(chain, fa, chain_dir)
                if pid:
                    chain["final_dispatched"] = {"pid": pid, "at": now_ts()}
                    # 登记进 dispatched：下一循环 fa 命中"已派发"分支，只守候不重发
                    chain.setdefault("dispatched", {})[fa["id"]] = {"pid": pid, "at": now_ts()}
                    log(chain_dir, "🏁 已拉起收尾代理 %s（pid=%s）" % (fa["id"], pid))
                    save_state(chain, chain_dir)
                    # 继续守候收尾代理
                    steps = list(steps) + [dict(fa, depends_on=[])]
                    continue
            save_state(chain, chain_dir)
            _write_finished(chain_dir, chain)
            return 0

        save_state(chain, chain_dir)
        time.sleep(SCAN_INTERVAL)


def _write_finished(chain_dir: Path, chain: dict) -> None:
    """给主代理的完工信号（拉取式；主代理下次运行读它）。"""
    payload = {
        "chain": chain.get("id"),
        "status": chain.get("status"),
        "finished_at": iso(chain.get("finished_ts")),
        "steps": [s["id"] for s in (chain.get("steps") or [])],
        "note": "主代理请读本文件 + .task/progress/*.md 做独立验收；"
                "调度器不代替主代理判定质量。",
    }
    try:
        (chain_dir / "finished.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError:
        pass


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------
def cmd_status(chain_id: str | None) -> int:
    if not CHAINS_DIR.is_dir():
        print("（无 chains 目录）")
        return 0
    files = sorted(CHAINS_DIR.glob("*.json"))
    if chain_id:
        files = [f for f in files if f.stem == chain_id]
    if not files:
        print("（无接力链）")
        return 0
    for f in files:
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except Exception as e:
            print("%s  读取失败：%s" % (f.name, e))
            continue
        # 运行态优先取 state.json（接力清单本身是"输入"，state.json 才是"运行时状态"）
        st_file = f.parent / f.stem / "state.json"
        if st_file.exists():
            try:
                d = {**d, **json.loads(st_file.read_text(encoding="utf-8"))}
            except Exception:
                pass
        alive = "-"
        pidf = f.parent / f.stem / "scheduler.pid"
        if pidf.exists():
            try:
                alive = "存活 pid=%s" % _pid_alive(int(pidf.read_text().strip()))
            except Exception:
                pass
        print("链 %-18s status=%-9s 调度器=%s" % (d.get("id"), d.get("status") or "未启动", alive))
        for s in (d.get("steps") or []):
            mark = ""
            if s.get("wait_only"):
                mark = " [只等]"
            print("   · %s%s" % (s["id"], mark))
        fin = f.parent / f.stem / "finished.json"
        if fin.exists():
            try:
                fd = json.loads(fin.read_text(encoding="utf-8"))
                print("   🏁 finished.json: %s @ %s" % (fd.get("status"), fd.get("finished_at")))
            except Exception:
                pass
    return 0


def _pid_alive(pid: int) -> str:
    try:
        out = subprocess.run(["tasklist", "/FI", "PID eq %d" % pid, "/NH"],
                             capture_output=True, timeout=10,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        txt = out.stdout.decode("gbk", "replace")
        return "是" if str(pid) in txt else "否（已退出）"
    except Exception:
        return "未知"


def cmd_stop(chain_id: str | None, all_: bool) -> int:
    killed = 0
    # ① 杀调度器进程（两种落盘位置都要覆盖）
    pid_files = []
    if CHAINS_DIR.is_dir():
        pid_files = list(CHAINS_DIR.glob("*/scheduler.pid")) + list(CHAINS_DIR.glob("scheduler.pid"))
    for pidf in pid_files:
        cname = pidf.parent.name if pidf.parent != CHAINS_DIR else "(根)"
        if chain_id and cname != chain_id:
            continue
        try:
            pid = int(pidf.read_text(encoding="utf-8").strip())
        except Exception:
            continue
        try:
            subprocess.run(["taskkill", "/PID", str(pid), "/F"],
                           capture_output=True, timeout=10,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            print("已停止调度器 pid=%d（链 %s）" % (pid, cname))
            killed += 1
        except Exception as e:
            print("停止 pid=%d 失败：%s" % (pid, e))

    # ② 注销计划任务（--daemon-task 启动时留下来；否则"正确停止"就不完整：
    #    计划任务还在 ⇒ 可能被再次触发，或残留占用任务名）
    task_names = []
    if CHAINS_DIR.is_dir():
        for d in CHAINS_DIR.iterdir():
            if d.is_dir() and (chain_id is None or d.name == chain_id):
                if (d / "_launch.cmd").exists():
                    task_names.append("GIGIChain_%s" % d.name)
    for tn in task_names:
        r = subprocess.run(["schtasks", "/Delete", "/TN", tn, "/F"],
                           capture_output=True, timeout=20,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if r.returncode == 0:
            print("已注销计划任务 %s" % tn)
            killed += 1

    if not killed:
        print("（没有在跑的调度器 / 计划任务）")
    return 0


def _spawn_detached(chain_path: Path) -> int | None:
    """把调度器自身以**完全脱离宿主进程树**的方式拉起（本轮结束也不受影响）。

    🔴 关键：优先加 CREATE_BREAKAWAY_FROM_JOB（脱离 Job Object，宿主收尾不会连带杀掉；
    agent_monitor.py 曾踩过同一坑：DETACHED_PROCESS 不脱离 Job 就活不过会话收尾）。
    但**若宿主 Job 不允许 breakaway**，Popen 会抛 `[WinError 5] 拒绝访问`
    —— 此时自动降级：去掉 breakaway 再试，并用计划任务兜底（见 `--daemon-task`）。
    """
    base = (getattr(subprocess, "DETACHED_PROCESS", 0x00000008)
            | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)
            | getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000))
    breakaway = getattr(subprocess, "CREATE_BREAKAWAY_FROM_JOB", 0x01000000)

    chain_dir = chain_path.parent / chain_path.stem
    chain_dir.mkdir(parents=True, exist_ok=True)
    logf = chain_dir / "scheduler.out.log"
    for flags, tag in ((base | breakaway, "breakaway"), (base, "detached")):
        try:
            with open(logf, "ab") as lf:
                p = subprocess.Popen(
                    [sys.executable, str(Path(__file__).resolve()), "--run", str(chain_path)],
                    cwd=str(PROJ), stdout=lf, stderr=subprocess.STDOUT,
                    stdin=subprocess.DEVNULL, creationflags=flags, close_fds=True,
                )
            print("（启动方式：%s）" % tag)
            return p.pid
        except OSError as e:
            if tag == "breakaway":
                print("breakaway 被拒（%s），降级为 detached…" % e)
                continue
            print("脱离启动失败：%s" % e)
            return None
    return None


def _install_task(chain_path: Path) -> int:
    """兜底：用 Windows 计划任务拉起（父进程 svchost，彻底脱离宿主）。

    计划任务名 = `GIGIChain_<CHAIN_ID>`；只创建 + 立即运行一次。
    卸载用 `schtasks /Delete /TN <名> /F`。

    🔴 关键（对齐 Shield 版 2026-09-25 实测修复，勿改回）：
    1. `/TR` **不能塞完整命令行**（嵌套引号会被 schtasks 解析坏，任务建了但跑不起来）。
       正确做法：写一个 `.cmd` 包装脚本，`/TR` 只指向它。
    2. `/SC ONCE /ST 00:00` 是"过去的时刻" ⇒ 建完必须立刻 `/Run` 才真的跑；
       且 `/ST` 不能省略（schtasks 要求 ONCE 必带）。
    3. 包装脚本必须 **CRLF + GBK**（Windows 批处理），否则 `goto`/路径解析异常。
    4. 创建与启动要**分步检查 returncode**，否则失败被静默吞掉。
    """
    cid = chain_path.stem
    name = "GIGIChain_%s" % cid
    py = sys.executable
    script = str(Path(__file__).resolve())
    chain_dir = chain_path.parent / cid
    chain_dir.mkdir(parents=True, exist_ok=True)
    launcher = chain_dir / "_launch.cmd"

    body = (
        "@echo off\r\n"
        'cd /d "{}"\r\n'
        '"{}" "{}" --run "{}" >> "{}" 2>&1\r\n'
    ).format(PROJ, py, script, chain_path, chain_dir / "scheduler.out.log")
    try:
        launcher.write_text(body, encoding="gbk", newline="")
    except OSError as e:
        print("写启动脚本失败：%s" % e)
        return 1
    try:
        cr = subprocess.run(["schtasks", "/Create", "/TN", name, "/F",
                             "/SC", "ONCE", "/ST", "00:00", "/TR", str(launcher)],
                            capture_output=True, timeout=30,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if cr.returncode != 0:
            print("计划任务创建失败：%s" % (cr.stdout or b"").decode("gbk", "replace"))
            return 1
        rr = subprocess.run(["schtasks", "/Run", "/TN", name],
                            capture_output=True, timeout=30,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        out = (rr.stdout or b"").decode("gbk", "replace").strip()
        if rr.returncode != 0:
            print("计划任务启动失败：%s" % out)
            return 1
        print("计划任务 %s 已启动（父进程 svchost，抗宿主清理）：%s" % (name, out or "OK"))
        print("  停止：task_scheduler.py --stop --chain %s" % cid)
        return 0
    except Exception as e:
        print("计划任务兜底失败：%s" % e)
        return 1


def main() -> int:
    ap = argparse.ArgumentParser(description="主从全自动交接调度器（方案 B）")
    ap.add_argument("--chain", metavar="FILE_OR_ID", help="接力清单 json 路径或链 ID")
    ap.add_argument("--run", metavar="FILE", help="（内部）前台运行指定接力清单")
    ap.add_argument("--status", action="store_true", help="查看接力链状态")
    ap.add_argument("--stop", action="store_true", help="停止调度器")
    ap.add_argument("--stop-all", action="store_true", help="停止全部调度器")
    ap.add_argument("--daemon", action="store_true",
                    help="以完全脱离宿主的方式后台常驻（推荐）")
    ap.add_argument("--daemon-task", action="store_true",
                    help="兜底：用 Windows 计划任务拉起（breakaway 被 Job 拒绝时用）")
    args = ap.parse_args()

    if args.run:
        return run_chain(Path(args.run))
    if args.status:
        return cmd_status(args.chain)
    if args.stop or args.stop_all:
        return cmd_stop(args.chain, args.stop_all)

    if not args.chain:
        ap.error("需要 --chain <文件或ID>，或用 --status / --stop")
    p = Path(args.chain)
    if not p.exists():
        p = CHAINS_DIR / ((args.chain + ".json") if not args.chain.endswith(".json") else args.chain)
    if not p.exists():
        print("找不到接力清单：%s" % args.chain)
        return 1

    if args.daemon or args.daemon_task:
        if args.daemon_task:
            return _install_task(p)
        pid = _spawn_detached(p)
        if pid:
            print("调度器已后台常驻（pid=%d，链 %s）" % (pid, p.stem))
            print("提示：--status 看进度；--stop 停止；若本进程被宿主连带杀掉，改用 --daemon-task。")
            return 0
        print("改用计划任务兜底…")
        return _install_task(p)
    return run_chain(p)


if __name__ == "__main__":
    sys.exit(main())
