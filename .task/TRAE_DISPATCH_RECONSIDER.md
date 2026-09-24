# 给 TRAE agent 的提示词：重新考虑派发方式

> 用法：把下面「=== 提示词开始 ===」到「=== 提示词结束 ===」之间的内容**整段复制**发给 TRAE 的 agent。
> 本文档本身只是容器，不要把它当任务派单。

=== 提示词开始 ===

先停一下手里的事，读三段东西再动手：

1. `D:/AndroidStudioProjects/GIGI/.task/TRAE_AGENT_HANDOFF.md`（跨 agent 共享协作网络约定）
2. `D:/AndroidStudioProjects/GIGI/skills/subagent-cli-ops/SKILL.md`（**本工程**派发规则，刚更新过，重点看 §7.3）
3. 然后跑这两条命令看现场：
   ```bash
   "$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --status
   "$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --text
   ```

## 一、先纠正一个误判：没有任何子代理"死掉"

你（或你上一轮）可能观察到"监听进程一起来，别人的子代理就没了"。**这个观察不成立**，核实结果如下：

**全盘只有若干个 `qoderclicn.exe`，全部活着：**

| pid | 任务 | 父进程 | 归属 |
| :-- | :-- | :-- | :-- |
| 976 | T9 | python 38128（GIGI 调度器） | 本工程 |
| 21248 | T6 | python 38128（GIGI 调度器） | 本工程 |
| 30636 | T7 | python 38128（GIGI 调度器） | 本工程 |
| 38564 | T8 | python 38128（GIGI 调度器） | 本工程 |
| 16156 | R7-DLGLAYOUT-ADJUDICAT | python 28916（**Shield** 调度器） | 另一个工程 |
| 19696 | （R7 重试副本） | python 28916（**Shield** 调度器） | 另一个工程 |

> 注：pid 会变动、也会被复用（比如 T9 曾同时有 976 / 22388 两个进程，旧的已退出）。
> **不要拿 pid 当任务的唯一标识**，要用「进程名 + 父进程 + 会话内容」综合判断。

- 监视器统计：**疑似卡死 = 0**，GIGI 侧**死亡 = 0**。
- 已完工：`T5b`（commit `8167533`）、`T5d`（commit `9047de6`）、`T5c`（构建成功）。
- 正在跑：`T6 / T7 / T8 / T9`，**四个并行**，最后活动时间都在 1–2 分钟内。
- `R7` 是 **Shield 工程**的任务，跟你无关，**不要碰它、不要停它**。

**两条调度器都已用正确的方式脱离宿主**（见下条），父进程链都追到了 `svchost.exe`：
- GIGI 调度器 pid=38128：`python ← svchost ← services ← wininit` ✅
- Shield 调度器 pid=28916：`python ← cmd.exe ← svchost ← services ← wininit` ✅

所以：**两个工程的 agent 完全可以同时跑，互不干扰。** 你看到的"死亡"极可能是把
**Shield 的 R7** 或**别工程的旧任务**当成了自己的，或者是**探针文件停止更新**造成的错觉（见第三节）。

## 二、关键认知：探针不动 ≠ 卡死

这是最容易误判的一点，务必记住：

- **探针（`.task/progress/*.md`）不更新，不代表子代理卡死。**
  实测 `T5c` 的探针 mtime 停在 `01:47:25`，但它的**会话文件** `02:04:24` 还在更新——
  因为它在跑 `assembleDebug + testDebugUnitTest + lintDebug`，Gradle 是子进程，
  所以 `qoderclicn.exe` 自身 CPU 增量极小（6 秒仅 +0.1s，看着像空转，其实在编译）。

- **判断子代理是否真在干活，按这个优先级看：**
  1. **会话文件 mtime（最灵敏）**：`C:/Users/oscur/.qoder-cn/projects/D--AndroidStudioProjects-GIGI/*.jsonl`
     —— mtime 在增长 = 活着。
  2. 证据/产物目录是否有新文件。
  3. CPU 增量（6 秒 +0.1s ≈ 空转，但**跑 Gradle 时也这样**，不可单独下结论）。

  只有「进程没了」或「会话 mtime 长时间冻住且无产物」才叫卡死。

- **不要靠 PID 认任务**：PID 会被复用（`T5a` 用过的 36116 后来又是别人的）。
  认任务要靠**会话文件内容的首句（派单文本） + mtime**。

## 三、你派发的四个子代理（T6–T9）为什么能活得好好的

因为它们由 **GIGI 调度器**（pid 38128）派出，而该调度器已通过 `--daemon-task` **彻底脱离宿主 Job**，
父链直达 `svchost`。它的 Job Object 不属于宿主，所以宿主 turn 结束**带不走**它派出的子代理。

**这已经是最强的方式，GIGI 侧不需要再改派发方式了。** 本工程 `tools/task_scheduler.py` 刚做了两处修复（commit `f7c0a57`）：
1. `_install_task()`：改为先写 `_launch.cmd` 包装脚本（**CRLF + GBK**），`schtasks /TR` **只指向脚本路径**
   —— 杜绝嵌套引号被解析坏；创建与启动**分步检查 returncode**，不再静默失败。
2. `cmd_stop()`：补上 `schtasks /Delete /TN GIGIChain_<ID> /F`
   —— 使 `--stop` 在 `--daemon-task` 模式下**完整收尾**，不残留可被再次触发的计划任务。

## 四、唯一的红线：不要用 `--stop` 去"清理"

⚠️ **`--stop` 会连带杀掉该链正在跑的子代理**（子代理在调度器的 Job 里）。

所以：
- 想"清干净重来"而跑 `--stop`，会把在跑的 T6–T9 **一起端掉** —— **千万别这么干**。
- `--stop` 只在该链**确认没有在跑的子代理**、或收工时使用。
- 判断"有没有在跑"**不能只看 `--status`**（那是链级状态），必须：
  ```bash
  "$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --json
  # 再对每个 pid 确认进程真的在：
  # PowerShell: Get-Process -Id <pid>
  ```
- **不要停** `AgentMonitor` 计划任务、**不要停** `GIGIChain_GIGI-MAIN` 计划任务、
  **不要**去动 Shield 的调度器（pid 28916）和它的 `R7`。

## 五、接下来该怎么派（如果还要派新任务）

1. **先认领再派**：跑 `--json` + 读 `.task/progress/` 现状，确认任务 ID 没被占用、没在跑。
2. **走调度器，不要手工 Popen 直派**：把新步骤追加进 `.task/chains/GIGI-MAIN.json` 的 `steps`，
   或新建一条链，然后用 **`--daemon-task`** 拉起：
   ```bash
   "$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --chain <CHAIN_ID> --daemon-task
   ```
   🔴 **必须用 `--daemon-task`，不要用 `--daemon`** —— 本机宿主 Job 拒绝 `CREATE_BREAKAWAY_FROM_JOB`，
   `--daemon` 会静默降级成不带 breakaway 的 detached，**turn 一结束调度器连同它派的子代理一起被杀**。
3. **派完必须验活**（`--status` 说"存活"不算数），追溯父链到 `svchost.exe`：
   ```powershell
   $id=<调度器pid>; while ($id) { $p=Get-CimInstance Win32_Process -Filter "ProcessId = $id"; "$($p.ProcessId) $($p.Name)"; $id=$p.ParentProcessId }
   ```
   看到 `svchost.exe`（其父 `services.exe`）才算真脱离；只到 `cmd.exe`/`python.exe` = 还在宿主 Job 里。
   （`--daemon-task` 时父链里有 `cmd.exe` 是正常的，它是 `_launch.cmd` 的解释器。）
4. **工具只能用本工程的**：`D:/AndroidStudioProjects/GIGI/tools/` 下的
   `agent_monitor.py` / `task_scheduler.py`。**不要再调 `D:/AndroidStudioProjects/Shield/tools/`**，
   跨工程共用会造成任务名冲突与状态互相污染。
5. **不要替别人提交**：正在跑的任务，它的产物（探针、证据）由它自己提交；别人**不要** `git add`。
6. **不要阻塞**：派完就结束 turn，靠文件哨兵（`--wait-done` / `notify.json`）或
   `--status` 在下一轮看进度，**禁止**用前台长等待卡住对话。

## 六、一句话总结

**没有任何子代理死掉**（T5b/T5d 已提交、T5c 已成功、T6–T9 正在并行推进，R7 是另一个工程的且活着）；
两个工程的 agent **可以同时跑**；GIGI 派发方式**已经是最强的 `--daemon-task`，无需再改**；
你只需要：**别用 `--stop` 清理**、**派新任务先 `--json` 认领**、**只用本工程 tools**、
**判断卡死看会话 mtime 而不是探针**。

=== 提示词结束 ===
