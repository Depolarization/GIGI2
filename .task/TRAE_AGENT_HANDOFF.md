# 给 TRAE Agent 的交接说明：你不是这套协作网络的唯一使用者

> 写于 2026-09-25 01:50。**请先读完本文再动手派发或提交任何东西。**
> 本文件是 GIGI 工程的**常设约定**，长期有效，不是一次性通知。

## 一、先说清一件容易搞错的事

你现在能读到 `D:\AndroidStudioProjects\GIGI\skills\subagent-cli-ops\SKILL.md`，这份 skill 是**为你（TRAE 的 agent）专门准备的自包含版**——
因为你是 TRAE 的 agent，**读不到** WorkBuddy 的用户级目录 `C:/Users/oscur/.workbuddy-ai/skills/`。

这个"读不到"造成了两套东西同时存在，**你必须认清它们的区别**：

| | WorkBuddy 的 agent（如我这个会话） | **你（TRAE 的 agent）** |
| :--- | :--- | :--- |
| 全局 skill | `C:/Users/oscur/.workbuddy-ai/skills/subagent-cli-ops/SKILL.md`（通用版，643 行） | ❌ **读不到** |
| 工程 skill | `D:/AndroidStudioProjects/Shield/.workbuddy-ai/skills/...`（Shield 用薄覆盖层） | ✅ `D:/AndroidStudioProjects/GIGI/skills/subagent-cli-ops/SKILL.md`（**自包含版**，通用规则已内联） |
| 该怎么用 | 覆盖层 + 全局版**两份都要读** | **只读工程这一份就够**，它已包含全部通用规则 |

**结论：不要去找"全局版 skill"。** 你手头这份 GIGI 工程 skill 是完整的、自足的。
如果你看到别的 agent 引用 `~/.workbuddy-ai/...`，那是它的路径，**不是你的**，忽略即可。

## 二、工具在 GIGI 本地，不要在别处找

2026-09-25 之前，GIGI 没有任何工具，agent 被迫去调 **Shield 工程**下的
`D:/AndroidStudioProjects/Shield/tools/agent_monitor.py` —— **那是不合理的**（跨工程耦合、可能误停别人的进程）。
现在已经补好了，**两个工具都在 GIGI 本地**：

```bash
# Python 解释器（不在 PATH，必须写绝对路径）
PY="C:/Users/oscur/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"

# ① 子代理监视器（只读观测）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --json        # 非阻塞看状态
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --text        # 人看的表格
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --ensure --serve 8787   # 幂等自拉起
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --wait-done "T6,T7"     # 登记完工哨兵

# ② 调度代理（有副作用：会自动派下一棒）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --status [--chain GIGI-MAIN]
```

🔴 **铁律：一律用 GIGI 本地路径。** 不要再去调 `D:/AndroidStudioProjects/Shield/tools/` 下的副本。
两份代码同源、功能等价（都扫全盘所有工程），所以 Shield 那份"看得见"你在跑什么——**但你要用自己这份**。

## 三、已经有子代理在跑了：先认领现状，别重复派发

**当前正在运行的三个 CLI 子代理**（截至 2026-09-25 01:50）：

| 任务 ID | PID | 状态 | 独占文件 |
| :--- | :--- | :--- | :--- |
| `T5b` | 15656 | 进行中 | `ui/dialogs/playerdetail/` 下三个文件 |
| `T5c` | 36116 | 进行中 | `ui/dialogs/cardcover/` 下三个文件 |
| `T5d` | 560 | 进行中 | `ui/dialogs/about/AboutDialog.kt` |

**它们是谁派的？为什么会自动往下跑？** —— 有一条**接力链 `GIGI-MAIN`** 正由 `task_scheduler.py` 常驻守护：

```
T5b(只等) ─┐
T5c(只等) ─┼─► T6 ∥ T7 ∥ T8 ∥ T9 ─► T10 ─► T11(收尾，自动拉起)
T5d(只等) ─┘
```

清单在 `.task/chains/GIGI-MAIN.json`（**已 gitignore，是运行时数据**）。
三条 `wait_only` 棒次正在等 T5b/c/d 完工，然后**调度器会自动派 T6–T9（并行四页面）、T10（集成接线）、T11（真机取证）**——
**全程不需要任何人插手**。

### 所以你现在**不要**做的事

1. ❌ **不要重新派 T5b / T5c / T5d**。它们活着、在干活、有自己的探针（`.task/progress/T5b.md` 等）。
   重复派发 = 两份进程写同一批文件 = 静默覆盖 + 烧额度。
   判据：`"$PY" tools/agent_monitor.py --json` 看到 `state=running` 且 `pid` 非空 ⇒ **别碰**。
2. ❌ **不要"帮忙提交"T5b / T5c / T5d 的产物**。它们的探针还停在 `status: doing`，产物写到一半。
   当前工作树里这几个文件就是它们的在途产物，**属于它们自己提交**：
   ```
   ?? .task/progress/T5c.md
   ?? .task/progress/T5d.md
   ?? app/src/main/java/com/gigi/tcg/ui/dialogs/about/AboutDialog.kt
   ?? app/src/main/java/com/gigi/tcg/ui/dialogs/cardcover/CardCoverViewModel.kt
   ?? app/src/main/java/com/gigi/tcg/ui/dialogs/cardcover/CardImageSaver.kt
   ```
   **这 5 个文件不要 `git add`。** 等它们收工（探针转 `done`）后由它们自己提交。
3. ❌ **不要碰调度器**：`--stop` / `--stop-all` 会掐断自动交接链，T6–T11 就再也不会被派出去。
   除非你确认链条需要中止（例如用户明确要求）。
4. ❌ **不要碰监视器**：`agent_monitor.py --stop` 会杀掉正在替所有人守候的 watchdog。
   `--stop` 前必须先读 `tools/.agent_monitor/waiting.json`，**非空（有人在等任务）就绝对禁止**。

## 四、你要派新任务时，必须遵守的约定

### 4.1 派发前先"认领"——避免和接力链撞车

接力链已经把 **T6–T10** 排好了日程。你要派新任务，先跑一次：

```bash
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --json    # 看有没有同 ID 在跑
"$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --status # 看接力链排到哪了
```

- **ID 冲突** ⇒ 换一个 ID（如 `T5e`、`FIX-1`），**绝不要复用正在跑或已排期的 ID**。
- **文件冲突** ⇒ 对照 `.task/任务清单-main.md` 的「文件占用锁」表；🔒 的文件/目录不许碰。
- **任务归属**：如果你要做的事其实属于 T6–T10 的范畴（四页面 / 集成接线 / 真机取证），
  **不要另起炉灶** —— 那几棒已经在链上，等它们跑完或直接读它们的结果。

### 4.2 派发必须照 skill 的模板（§2 / §5）

```bash
# 1) 派单先落盘（不是可选）
#    写 .task/dispatch/<ID>.txt，要素：角色 / 必读手册 / 独占文件 / 改前闸门 /
#    要做什么 / 硬性禁止 / 验收门槛 / 提交署名 / 探针路径 / 收工动作
# 2) 后台派发
cd D:/AndroidStudioProjects/GIGI
"C:/Users/oscur/.qoder-cn/bin/qoderclicn/qoderclicn.exe" -p --permission-mode bypass_permissions \
  -m Qwen3.8-Flash "$(cat .task/dispatch/<ID>.txt)" > .agent_work/<ID>.log 2>&1
# 3) 派完后立刻自拉起监视器 + 登记哨兵（幂等、非阻塞）
"$PY" tools/agent_monitor.py --ensure --serve 8787
"$PY" tools/agent_monitor.py --wait-done "<ID>"
```

**要点（细节以 skill 为准）**：
- `-m` 模型名**派发前自己先跑一次 `--list-models` 确认**，别照抄记忆。
- **必须后台派发**（`run_in_background`），前台会被超时杀掉。
- **体量切到单文件/单改动**；派单里"要做什么"超过 5 条编号 ⇒ 回去拆。
- **派单必带改前闸门**：`git status --porcelain -- <你独占的文件>`，非空 ⇒ 停手报 `blocked`。
- **绝不 `git add -A`**；提交署名 `git -c user.name="GIGI-Bot" -c user.email="gigi-bot@local"`，
  type ∈ `scaffold / domain / data / auth / ui / build / test`。
- **绝不阻塞等待**子代理（禁止 `TaskOutput` / `sleep` 轮询）——会卡死整轮对话。
  长链路就交给调度器：写 `.task/chains/<ID>.json` 然后 `--chain <ID> --daemon`。

### 4.3 你自己要保持"跨轮可续"，因为你的 turn 会结束

**关键机制（务必理解）**：主代理（也就是你）**不是常驻进程**——turn 一结束进程就退出，
连你派出的后台子进程也可能被宿主连带杀掉。
所以"子代理完工唤醒你"这件事**在架构上不可能由推送完成**。

- **短任务（本 turn 内能跑完）**：派完 → `--ensure` → `--wait-done` → **正常结束本轮**。
  下一轮开头读一次 `tools/.agent_monitor/notify.json` 的 `fired` 字段即知谁完工。
- **长链路（≥2 棒且跨轮）**：**必须**用调度代理（`.task/chains/` + `--daemon`），
  由它在后台自动交接。**不要**靠"下轮开头读 notify.json 再手动派下一棒"——那不叫自动。

## 五、一句话总结

> **GIGI 的协作网络是共享基础设施，不是你的私有工具。**
> 先 `--json` 看谁在跑、先 `--status` 看链条排到哪，再决定自己该做什么；
> 不乱派、不乱提交、不随手停服务；
> 派发只走 `tools/` 里的 GIGI 本地工具，用 `skills/subagent-cli-ops/SKILL.md` 这一份（它自包含，别再找全局版）。

## 附：常用命令速查

```bash
PY="C:/Users/oscur/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"
MON="D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py"
SCHED="D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py"

"$PY" "$MON" --json                      # 谁在跑（非阻塞，第一个该跑的命令）
"$PY" "$MON" --text                      # 人看的表格
"$PY" "$MON" --ensure --serve 8787       # 幂等拉起监视器
"$PY" "$MON" --wait-done "T6,T7"         # 登记完工哨兵
"$PY" "$MON" --open                      # 用浏览器看界面（自动绕过本机代理）
"$PY" "$SCHED" --status                  # 接力链排到哪了
"$PY" "$SCHED" --chain X --daemon        # 启动长链路自动交接

git -C D:/AndroidStudioProjects/GIGI status --porcelain --untracked-files=all   # 提交前必看
```

**提交纪律**：只 `git add` 自己产出的文件，逐个点名，**永不 `-A`**；
看到别人的在途产物（探针 `doing`）就绕开。
