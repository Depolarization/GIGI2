---
name: subagent-cli-ops
description: GIGI 工程自包含版——CLI 子代理派发与协调铁律 + GIGI（Kotlin/Compose，com.gigi.tcg）专属技术栈、构建命令、验收门槛、工具绝对路径。本工程由 TRAE 的 agent 驱动，读不到 WorkBuddy 用户级全局 skill，故通用规则已内联在本文件。当要派发 CLI 子代理、通道或模型报错（429、额度、Sorry something went wrong）要回退、判断子代理是否卡死、代理中途死亡要接手、并行切分冲突、或要写派单提示词时使用。触发词：qoderclicn、CLI 子代理、派单、派发、并发、额度耗尽、429、模型回退、探针、卡死判活、补派、文件独占、协调网络、gradlew、assembleDebug、Compose、GIGI。
agent_created: true
---

# GIGI 工程：CLI 子代理派发与协调（自包含版）

> 🔴 **本文件是自包含的**：通用规则 + GIGI 工程常量都写在这一份里。
> 本工程由 **TRAE 的其它 agent** 驱动，**读不到** WorkBuddy 用户级 skill（`~/.workbuddy-ai/skills/`），
> 因此**不要**依赖"另外去读一个全局版"——照本文件即可完整作业。
> 若同机存在 WorkBuddy 全局版 `C:/Users/oscur/.workbuddy-ai/skills/subagent-cli-ops/SKILL.md`，
> 可以**额外**读它以获取最新通用经验，但**本文件已能独立成立**；两者冲突时以本文件为准（它是 GIGI 事实）。
>
> 🔴🔴 **接手本工程时，先读 `.task/TRAE_AGENT_HANDOFF.md`**（跨 agent 共享协作网络的约定）：
> 谁在跑、链条排到哪、哪些**不能**重复派 / 不能替它提交 / 不能停服务。**别跳过这一步。**
>
> 职责边界：本 skill 只管**怎么派、怎么守、怎么救**；派不派 / 怎么分档 / 怎么验收 → 见同目录 `task-execution`。

## 0. 环境常量（GIGI 专属，换机器只改这一块）

| 变量           | 本机值                                                                                                          | 说明                                                            |
| :----------- | :------------------------------------------------------------------------------------------------------------- | :------------------------------------------------------------ |
| `$PY`        | `C:/Users/oscur/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe`                                      | Python（**不在 PATH**，一律写绝对路径）                                   |
| `$CLI`       | `C:/Users/oscur/.qoder-cn/bin/qoderclicn/qoderclicn.exe`                                                        | **唯一能改文件**的通道                                                 |
| `$CLI_ARGS`  | `-p --permission-mode bypass_permissions`                                                                       | 不可省，否则写操作被逐个拦截                                                |
| `$MON`       | `"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py"`                                                  | **子代理监视器**（只读观测），派发后必调 `--ensure`                              |
| `$SCHED`     | `"$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py"`                                                 | **调度代理**（§7.3，有副作用：自动派下一棒）                                    |
| `$TASK`      | `.task/`                                                                                                        | `dispatch/` 派单 · `progress/` 探针 · `events/` 事件 · `chains/` 接力清单 |
| `$HANDBOOK`  | `.task/SUBAGENT_HANDBOOK.md`                                                                                    | **本工程必读手册**（技术栈/构建/设计红线/探针格式）                                  |
| `$PROJ`      | `D:/AndroidStudioProjects/GIGI`                                                                                 | 工作目录                                                          |
| `$SESS`      | `~/.qoder-cn/projects/D--AndroidStudioProjects-GIGI/<session_id>.jsonl`                                          | mtime = 会话最后活动时刻                                               |
| `$CONC`      | 3–4                                                                                                             | CLI 并行上限（超限失败率陡增）                                             |
| `$CONC_TEXT` | 6–10                                                                                                            | 文本通道并行上限（纯 API，瓶颈只有 QPM/TPM）                                  |

> 🔴 **工具在 GIGI 本工程内**（`D:/AndroidStudioProjects/GIGI/tools/`），**不要**去调 `D:/AndroidStudioProjects/Shield/tools/` 下的副本。
> 两份代码同源；监视器逻辑等价（它扫全盘所有工程），但**本工程的 agent 一律用本工程路径**，避免跨工程耦合与误停别人的进程。

**模型链（以 `"$CLI" -p --list-models` 实际输出为准；原则：免费/快 → 均衡 → 强 → 兜底）**
`Qwen3.8-Flash`（免费、多模态，首选）→ `DeepSeek-Flash`（均衡）→ `Qwen3.8-Max`（强）→ `GLM-5.3`（兜底）。
高复杂度调度可用 `kimi k2.6`。文本通道：`kimi k3`（调度+coding）、`qwen3.8-max`（视觉核验）。
🔴 **每一条通道/模型名在写进派单前，主代理必须自己先跑一次确认**（见 §5.1-5）。

## 1. 硬规则（违反即出事故）

| #  | 规则                                                                                                                        | 判据 / 后果                                                                                                                                      |
| :- | :------------------------------------------------------------------------------------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------- |
| 1  | **必须 `--permission-mode bypass_permissions`**                                                                             | 否则"只能改码、跑不了 shell"                                                                                                                            |
| 2  | **必须后台派发**                                                                                                                | 前台会被超时 SIGTERM 杀掉                                                                                                                            |
| 3  | **体量切到单文件/单改动**                                                                                                           | 派前先问"还能不能再拆小"                                                                                                                                |
| 4  | **派单先落盘 `$TASK/dispatch/<ID>.txt`，再 `$(cat …)` 传入**                                                                       | 换通道零成本接手，不重写简报                                                                                                                               |
| 5  | **强制增量落盘**（探针 + 产物 + 分步 commit）                                                                                           | 开工 `doing` → 每完成一小步即写 → 收工 `done`                                                                                                            |
| 6  | **不用日志大小判存活**                                                                                                             | 管道缓冲，进程结束才落盘，0 字节是正常的                                                                                                                        |
| 7  | **判活先跑 `date`**                                                                                                           | 绝不采信会话注入的时间戳                                                                                                                                 |
| 8  | **文件归属独占**                                                                                                                | 不因"改动区域不同"放宽                                                                                                                                 |
| 9  | **不边验边改**                                                                                                                 | 验收期冻结文件                                                                                                                                      |
| 10 | **额度报错只换 `-m`**                                                                                                          | 不重做任务设计                                                                                                                                      |
| 11 | **派单必带改前闸门**                                                                                                              | `git status --porcelain -- <独占文件>` 非空 → 停手报 `blocked`，绝不覆盖                                                                                    |
| 12 | **验收只认产物，不认任务状态**                                                                                                         | `failed` 常是假失败（见 §7）                                                                                                                          |
| 13 | **不阻塞监听子代理**（`TaskOutput`/`sleep` 卡住会让用户无法追加任务）——二选一：① `$MON --wait-done` 文件哨兵（§7.1）② `$SCHED` 调度代理自动往下推（§7.3） | ⚠️ **前提是守候进程真常驻**。派发后须确认常驻；**未确认前不得宣称哨兵可用**，只能回退「同 turn 内前台长循环阻塞等待」，见 §7.1「前提条件」与 §7.2 |
| 14 | **长链路（≥2 棒且跨轮）必须用调度代理（§7.3）**                                                                                             | 主代理只派第 1 棒 + 写接力清单 → 结束 turn；交接由 `task_scheduler.py` 后台完成。**不要**靠"下轮开头读 notify.json"续跑——那不叫自动                                        |

## 2. 派发命令

```bash
# 1) 写派单（一次写好，可复用）：$TASK/dispatch/<ID>.txt
# 2) 后台派发（工具层 run_in_background: true）
cd D:/AndroidStudioProjects/GIGI
"C:/Users/oscur/.qoder-cn/bin/qoderclicn/qoderclicn.exe" -p --permission-mode bypass_permissions \
  -m Qwen3.8-Flash "$(cat .task/dispatch/<ID>.txt)" > .agent_work/<ID>.log 2>&1
```

### 2.1 通道 ②：OpenRouter / opencode CLI（**能改文件**，备用实现类通道）

OpenRouter 无官方 CLI，用 **opencode** 作 harness（原生支持 OpenRouter provider）。本机已配置并端到端验证。

```bash
# key 已持久化在 Windows【用户级环境变量】，新进程自动继承，无需 export。
# ⚠️ 但写入之后才启动的进程才有，已在跑的 shell / 调度器读不到，须重启。
"C:/Users/oscur/.workbuddy-ai/binaries/node/workspace/node_modules/.bin/opencode" \
  run -m openrouter/stealth/space-bunny-alpha "$(cat .task/dispatch/<ID>.txt)" \
  > .agent_work/<ID>-or.log 2>&1                 # 同样必须后台派发
```

**🔴 API key 存放位置（2026-09-25 事故后确认）**：Windows 用户级环境变量
（`HKCU\Environment` → `OPENROUTER_API_KEY`），**持久化**的。
❌ 不要写进工程文件 / `opencode.jsonc` / 派单 txt / git。
⚠️ **生效范围**：写入之后才启动的进程。已在跑的调度器、已开的 shell **读不到**，要重启。
（事故：曾误记"已配置"，实际只在临时 shell 设过 ⇒ 别的 agent 调用报
`OpenRouter API key is missing`。**"跑通过一次" ≠ "已持久化"**。）

✅ **已实测：经 `schtasks` 启动的进程能读到该变量**（`HAS_KEY=YES LEN=73`）。
即**调度器（`--daemon-task`）及其派出的 opencode 子代理都能继承到 key**，无需在派单里 export。
⇒ 派 opencode 任务时**不要**把 key 明文写进派单/命令行，直接调即可。

**🔴 `small_model` 必须一起锁**（2026-09-25 实测）：
`~/.config/opencode/opencode.jsonc` 里 `"model"` 和 `"small_model"` **都要**写成
`openrouter/stealth/space-bunny-alpha`。不设 small_model 的话，opencode 生成会话标题时
会自作主张用 `google/gemini-3.8-flash`，既违反"只用一个模型"又持续报 `stream error`。
验证：`opencode debug config` 应能看到两项都被解析。

- 🔴 **模型名必须写成三段式 `openrouter/<vendor>/<model>`**；本机只允许用 `openrouter/stealth/space-bunny-alpha`。
- 新模型必须先注册进 `~/.config/opencode/opencode.jsonc` 的 `provider.openrouter.models`，否则报
  `ProviderModelNotFoundError`（表现为 `UnknownError`，要加 `--log-level DEBUG` 才看得到真因）。
- 前置检查：`opencode models openrouter` 能列出模型 ⇒ key 生效；报 `Provider not found: openrouter` ⇒ 没读到 key。
- 权限：`~/.config/opencode/opencode.jsonc` 已设 `permission: {edit|bash|webfetch|doom_loop: "allow"}`，
  等价于 `$CLI` 的 `--permission-mode bypass_permissions`。
- 其余规则与 `$CLI` 完全一致（后台派发、单文件粒度、派单落盘、探针、文件独占、闸门、≤200 字报告）。
- OpenRouter 按量计费，与 Qoder **额度池独立**；派发前确认余额。

### 2.2 通道 ③：百炼（`bl`）—— 模型池与**额度耗尽接力**

**百炼是第三条额度池**（与 Qoder Credits、OpenRouter **互相独立**）。
模型池由 `C:/Users/oscur/.workbuddy-ai/tools/sync_bailian_models.py` 从百炼官方目录同步
（口径 = 2026 年上架 + 工具调用 + 推理 + 文本/多模态 + 排除老/小型号）。

**可当主 agent 的精选 17 个**（已写入 WorkBuddy `~/.workbuddy-ai/models.json`）：

| 家族 | 模型 |
| :-- | :-- |
| DeepSeek | `deepseek-v4.1-flash`（越级智能）· `deepseek-v4-pro-0813` · `deepseek-v4-pro` · `deepseek-v4-flash-0731` · `deepseek-v4-flash` |
| GLM | `glm-5.3-prime` · `glm-5.3` · `glm-5.2` |
| Kimi | `kimi-k3` · `kimi/kimi-k2.8-preview` · `kimi-k2.7-code` · `kimi-k2.6` |
| Qwen | `qwen3.8-max-0902` · `qwen3.8-max` · `qwen3.8-flash` |
| 其它 | `MiniMax/MiniMax-M3` · `stepfun/step-5-preview` |

完整候选池（52 个）随时可重新生成：
```bash
"$PY" C:/Users/oscur/.workbuddy-ai/tools/sync_bailian_models.py --emit-skill <输出.md>
```

**🔴🔴 额度耗尽接力（用户 2026-09-25 指定，最高优先级）**

这批模型**全部开了「免费额度用完即停」**（本月抵扣额度已用尽）⇒ **任何一次调用都可能突然失败**。

1. **判据**：报错含 `quota` / `Arrearage` / `free tier` / `exceeded` / `429`
   ⇒ 这是**额度耗尽**，**不是任务本身有问题**，不要去改任务设计。
2. **动作**：换 `-m <同档或更强的模型>`，**用同一份派单文件**重新派发；
   探针追加一行 `[接力] <时间> <旧模型> → <新模型>（额度耗尽）`。
3. **不丢进度**：派单在 `.task/dispatch/`、进度在 `.task/progress/`、产物在磁盘
   ⇒ 新模型起来**先读探针**即可接续（见 §6 强制增量落盘）。
4. ⚠️ **不要拿 `bl usage freetier` 当查询命令**（不带 `--on/--off` 时默认批量开启，会改状态）。

## 3. 报错与回退链（严格按序，不跳步）

1. **换 `-m`**（只改模型名，任务设计不动）；
2. **等 2–5 分钟**重试（禁止密集重试）；
3. **转 opencode / OpenRouter**（§2.1，实现类任务也能接）；
4. 仍不行 → 转**百炼池**（§2.2，换 `-m` 到池里同档模型；额度耗尽的处理见该节）；
5. 仍不行 → 转纯文本 API 做**分析类**兜底（它没有工具执行循环，不能动手）；
6. 仍不行 → 探针写 `blocked` + 原因并上报，**不要空转**。

- `429 / quota / usage exceeds frequency limit`：宿主内置 subagent 与 CLI 额度**相互独立** ⇒ 内置 429 时**立刻切 CLI**，提示的重置时刻不必等。
- `Sorry, something went wrong`：模型侧瞬时错误 ⇒ 换 `-m`，**同时把任务拆小**（与体量叠加才是主因）。

## 4. 体量：单文件 / 单改动

**成败主因是上下文与体量，不是模型也不是环境。** 同一环境下：大任务连续 5 次失败（跨 3 个模型），切小后连续 7 次成功。

| 失败成因                  | 可控 | 对策                                              |
| :-------------------- | :- | :---------------------------------------------- |
| 体量过大 / 上下文耗尽（**最主要**） | ✅  | 切单文件/单改动；多合一必拆（数十文件级任务两次死亡，"三合一"死亡、拆成两个单文件后均成功） |
| 模型瞬时错误                | ❌  | 换 `-m`                                          |
| 并发/额度                 | ⚠️ | 并发 ≤ `$CONC`                                    |
| 前台超时被杀                | ✅  | 一律后台                                            |
| 输出过长被截断               | ✅  | 报告 ≤200 字                                       |

**GIGI 实证**：T1 脚手架（12 文件）是边缘，但作为"一次性建骨架"可接受；**T2–T9 全部按"一个模块 / 一个页面 / 一类文件"切开**，
每棒 2–6 个文件、单一提交前缀，全部一次通过。**反面判据**：派单里的「要做什么」超过 5 条编号 ⇒ 回去拆。

## 5. 派单模板（GIGI 版，照抄改）

```
[角色] 你是 <ID> 子代理；[标识] 本派单文件 .task/dispatch/<ID>.txt；
       工作目录 D:\AndroidStudioProjects\GIGI；技术栈 Kotlin + Jetpack Compose（Material 3）+ Gradle Kotlin DSL，JDK 21。
[必读] D:\AndroidStudioProjects\GIGI\.task\SUBAGENT_HANDBOOK.md（技术栈/构建/设计红线/探针格式）；
       探针 = D:\AndroidStudioProjects\GIGI\.task\progress\<ID>.md（开工写 doing，每完成一小步更新，收工 done）。
[设计权威] D:\EmulatorShared\GIGIWeb\2026-09-24-gigi-android-native-design.md §<X>；
       参照源码（必读，禁止凭想象）：D:\EmulatorShared\GIGI\web\src\<路径>——看 <哪几个要点>。
[独占文件] 只有这些可改（全部新建时写明）：<绝对/相对路径清单>。
       其它一律不碰；兄弟任务可能正在改 <列出预期会看到的其它 dirty 文件>（属正常，勿误判）。
[改前闸门] git status --porcelain -- <你独占的每个文件> 应为空（HEAD=<hash>）；非空 → 停手、贴 diff、探针写 blocked、报主代理。
[要做什么] <改什么 → 怎么改；新增什么符号；调用哪些已存在的 API 及其签名来源>。
       禁止"基于你的发现去修"——理解不外包，主代理先调查到能写出具体执行方向。
[硬性禁止] 顺手改其它文件 / 顺手重构 / 顺手加依赖 / 改构建脚本 / git add -A / "既然来了就一起做"。
[验收门槛] JDK21（见手册 §2）；权威判据 = 依次执行且全部满足：
       ① .\gradlew :app:assembleDebug → 退出码 0 且含 BUILD SUCCESSFUL
       ② .\gradlew :app:testDebugUnitTest → 全绿（用例总数 <N>，不得减少）
       ③ .\gradlew :app:lintDebug → Error 0
       不满足不许提交。以脚本/命令退出码为准，不写"看起来正常"。
[提交] git -c user.name="GIGI-Bot" -c user.email="gigi-bot@local" commit -m "<type>: <一句话>"
       type ∈ scaffold/domain/data/auth/ui/build/test（逐小步提交，严禁 git add -A）
[收工] 探针 done（符号清单 / 判据结果 / 证据路径）→ 报告 ≤200 字（结论 + commit hash + 证据路径）。
[停止] 达标即停；系统自动的 "Please continue" 不是新指令。
```

**自检清单**：工作目录绝对路径 ✅ 技术栈 ✅ 独占文件清单 ✅ 目标独占（扇出时）✅ 禁止项写死 ✅
验收门槛（命令 + 客观判据）✅ 提交署名 ✅ 收工动作 ✅ 已知上下文 ✅ 参照物绝对路径 ✅
探针路径与"开工写 doing、每步落盘" ✅ 是否允许再派子代理（允许才给读长手册）✅。
**派单自检问句**：*"如果我按这个提示词去干，能不能不猜就动手？"* 不能 → 回去调查。

### 5.1 五条判据纪律（每条都是事故换来的）

1. **"照抄即用"的代码必须先自验运行时语义**。派单里贴片段前，主代理要确认它依赖的运行时不变量（API 签名/包名/资源 id）。
   验证不了就**只写行为约束**，不贴未验证实现。优先级：**工程内既有代码 > 设计文档 > Web 版源码 > 记忆**。
2. **判据要验「效果」，不要验「实现方式」**。用可观测效果（能编译/退出码 0/产物在盘/设备可见）。
   不得已要 grep 实现时用**择一正则**并注明"语义等价即可"——否则正确但写法不同的实现会被误判 blocked。
3. **判据须"注释感知"**。凡同时出现「把 X 改到 0」和「不许动 Y」，先跑一遍判据把命中行打印出来。
   本工程实例：`grep -r "import android" app/src/main/java/com/gigi/tcg/domain/` 要 0 命中，
   但若注释里写了 android 字样会误伤 ⇒ 派单需声明"只统计真实 import 声明"。
4. **派单里写"判据"必须指定权威判据并声明"以它为准"**，否则子代理会以跑不通的那个为准并报 blocked。
   写法：`① 权威判据 = 运行 <命令>，退出码 0 即通过；② <辅助判据>`。
5. **派单里的每个可执行常量（命令/路径/包名/API 签名/字段名/模型名/端口）主代理必须自己先跑一次确认**；
   机制性断言必须先读源码验证。**扇出前实测成本 O(1)，写错的成本是 O(N) 倍返工**，且会污染 N 份报告的结论。

### 5.2 验收脚本也要被验收（防假阳性，最重要的一条）

- **"抽取 → 比对"型脚本，抽取后必须先断言非空再比对**，并打印两侧行数，让"0 行 vs 0 行"一眼可见。否则两侧都抽空 ⇒ `diff` 判"完全一致" ⇒ 假阳性。
- **当脚本给出"正合我意"的结论时，优先怀疑脚本**（最危险的假阳性恰好是你想要的结论）。
- **批量机械改写**的权威验收 = **与 `HEAD` 旧 blob 做等式比对**：`旧内容 + 预期变换 == 新内容` 严格相等。
- **集合类断言**（"有没有丢 X"）用**集合差**，不要用 grep（grep 只能查"是否出现"，查不出"是否消失"）。
  本工程实例：T2 移植验收就是**逐文件 it() 计数对照表**（TS 侧 vs Kotlin 侧 `@Test`），而非"看起来都移过来了"。

## 6. 强制增量落盘（防额度耗尽/死亡丢进度）

**探针** `$TASK/progress/<ID>.md`（格式与 `$HANDBOOK` §7 一致）：

```markdown
status: doing            # todo | doing | done | blocked
owner: <ID>
files: [<path>]
evidence: [<path / "cmd => 0">]
blockers: []
summary: <一句话>
updated: <取自 `date` 的真实时间>
```

- 开工写 `doing`，**每完成一小步即更新**，收工 `done`；主代理只靠它判进度。
- **验收类代理同样写探针**——否则结论随一次性输出丢失（历史上曾连丢 3 份动态验收报告）。
- 代理输出可能被系统清理 ⇒ **关键结论必须落盘**。
- **产物分批写盘**：先骨架 → 每 2–3 节写一次 → 最后自读确认。（一次性大文件写入最脆。）
- **分步 commit**：攒到最后一次提交最危险（曾有代理改完 87 文件未提交即死亡）。

**收工五步**：① 跑工程静态检查（`assembleDebug`/`testDebugUnitTest`/`lintDebug`），**全过才可提交**；
新引入的常量/函数名逐个 `grep -rn`，全库只出现 1 次且是使用处 = 未定义 ② `git commit`（署名见 §5 模板）
③ 探针 `done` + 填 `files/evidence/summary` ④ 协调文件 `.task/任务清单-main.md` 事件总表**向下追加**一行 `| 时间 | ID | 事件 | hash |` ⑤ 上报 ≤200 字。

## 7. 存活判定

❌ **不可靠**：日志/输出文件大小（管道缓冲，进程结束才落盘）；探针 mtime 旧（曾有代理 100+ 分钟未动探针却持续产出）；**后台任务的 `failed`/`completed` 状态**。

🔴 **只认产物，不认状态**：CLI 干完活可能以非 0 码退出被标 `failed`，但产物完好（实测 3 次派发里 2 次假失败）。收到通知后**先独立复核产物**，不要重派：
① 日志有完整收工总结？② 探针已落盘？③ 目标文件 mtime 落在本次时间窗？④ 关键断言自己跑一遍。
只有「无收工总结 **且** 探针缺失 **且** 目标文件未变」才判真失败。代价不对称：误判成功只是少验一步，**误判失败并重派 = 重复劳动 + 可能覆盖已完成产物**。

✅ **三指标（三者同时停滞才判卡死）**：① CLI 进程存活（`tasklist`）② `$SESS` 会话记录 **mtime 仍在增长** ③ 证据/产物目录**仍在新增文件**。
辅证：进程 CPU 时间增量（**CPU 6 秒仅 +0.1s ≈ 空转**，多是在等主代理裁决 ⇒ 判卡死）。

### 🔴 时间基准铁律

**任何"静止了多久/是否卡死"的判断必须先跑 `date` 取真实时间；绝不采信会话注入的时间戳。**
（实例：采信注入时间戳，比真实时间快 2 天 3 小时 ⇒ 误判两个健康代理"卡死 2.5 小时"并 taskkill，而它们在被杀前 40 秒仍有产出。）
**终止前必须确认**：`date` + 三指标 + CPU 增量；终止后登记「终止原因 + 已完成部分 + 待接续事项」。

### 7.1 子代理监视器（`$MON`）—— 主代理**绝不阻塞等待**

**为什么需要**：宿主工具（TRAE / WorkBuddy / Cursor…）的后台任务面板要么信息太少、要么根本不显示；
主代理想知道进度只能自己去阻塞等待 —— 🔴 **阻塞会卡死整轮对话，用户就没法及时派发或追加新任务**。
`$MON` 把**所有工具、所有工程**的 CLI 子代理汇总成结构化状态，主代理**读一次就返回**，人也能用浏览器实时看。

```bash
# ① 派发完成后立刻调一次（幂等：已开则不重复起；未开则后台拉起并等端口就绪）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --ensure --serve 8787
# ② 看进度（非阻塞，一次调用即返回）—— 主代理**每轮开头**读一次即可
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --json
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --text      # 人看的表格
# ③ 人看界面（--open 会自动绕过本机代理用系统浏览器打开）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --open
# ④ 开机常驻（注册 Windows 计划任务，父进程是 svchost，关掉 IDE 也带不走它）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --daemon --serve 8787
# ⑤ 停掉（杀进程 + 注销自启计划任务）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --stop
```

**🔴 抗宿主机制（用户实测"界面静默消失"的根因）**

| 坑                                              | 根因                                                                          | 正确做法                                                                                                                                             |
| :--------------------------------------------- | :-------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------- |
| 界面进程**静默消失**（`server.log` 无异常、`state.json` 停止更新） | `DETACHED_PROCESS` **只脱离控制台、不脱离 Job Object**；宿主（TRAE/WorkBuddy）会话收尾时把它连带杀掉 | 创建标志必须含 **`CREATE_BREAKAWAY_FROM_JOB`（0x01000000）**：`DETACHED_PROCESS \| CREATE_NEW_PROCESS_GROUP \| CREATE_NO_WINDOW \| CREATE_BREAKAWAY_FROM_JOB`。`_spawn_detached` 里先试带 breakaway 的，`OSError` 再退回不带 |
| 想要**彻底抗宿主清理**（IDE 重启/关掉也不停）                    | 只要父进程还在同一 Job 里就有风险                                                          | 用 `--daemon`：落一个 `_launch.cmd` 包装脚本，再 `schtasks /Create /TN AgentMonitor /TR "<launcher>" /SC ONCE /ST 00:00 /F` ⇒ 父进程变成 svchost |
| 绕 `cmd /c start "" /B <script.cmd>` 启动         | 会留下一个**常驻 `cmd.exe /K` 窗口**，监视器反而成了它的子进程                                     | **不要**用 `cmd /c start` 启动。直接用带 `CREATE_BREAKAWAY_FROM_JOB` 的 `subprocess.Popen`                                                                   |
| `schtasks /TR` 里嵌套引号被解析坏                       | 计划任务建了但跑不起来                                                                   | `/TR` **只指向一个包装脚本路径**，命令细节写进脚本里                                                                                                                   |

**🔴 `--ensure` 的幂等判定（曾起过 3 个实例）**

- `is_port_free()` **不能只靠 `bind`**：Windows 上 `SO_REUSEADDR` 会让"已 LISTENING"也能 bind 成功 ⇒ 必须先 `socket.create_connection` 试连。
- `_probe_our_service()` 读响应**不能只读 4096B**：`"rows"` 字段会被截断 ⇒ 判成"不是我们的服务" ⇒ 重复起实例。读 65536B。

#### 🔴🔴 主代理最容易犯的错：亲手 `--stop` 掉自己的 watchboard

**事故经过**：用户问"两个跑了很久的子代理是不是该杀"，主代理把 `agent_monitor` 常驻进程当成"僵尸任务"，
执行了 `agent_monitor.py --stop` 并注销计划任务 —— 等于**把正在替自己守候的 watchdog 掐死**，
此后所有完工通知再也不写 `notify.json`，只能退回被明令禁止的轮询保活。

**判据（下次别再犯）**：

1. `$MON` 是**协作基础设施**，不是子任务。用户抱怨"跑很久的 subagent"时，
   **先按 ID 判断**：是 `T5b`/`VERIFY-T2` 这类**任务 ID**（才可清理），还是 `agent_monitor`/`python.exe` 这类**常驻服务**（绝不可随手 `--stop`）。
2. **`--stop` 前必须先证明"没有人在等它"**：读 `tools/.agent_monitor/waiting.json`，
   若非空（有人在等任务）⇒ **禁止 `--stop`**。
3. 需要重新登记时用 `--wait-done`（幂等，只加不减）；要移除个别 ID 才动 `waiting.json`。
4. 只有用户明确说"把监视器关掉"时才 `--stop`。

**恢复动作**：`--ensure --serve <空闲端口>` → `--wait-done <IDs>` → 验证
`GET http://127.0.0.1:<port>/api/notify` 返回 200（**不能只看 `--ensure` 的输出**）。

#### ⚠️ `--ensure` 的端口探测会误认陌生服务（实测）

本机若另有看板（如 token 消耗看板）占用端口后，`agent_monitor.py --ensure --serve 8787`
可能输出 **"界面已在运行：http://127.0.0.1:<别的端口>"** —— 它把**另一个程序**当成了自己。
判据：`--ensure` 的输出**不可作为就绪证据**。必须补一步：
① `netstat -ano | grep <port>` 拿 pid；② 用 `Get-CimInstance Win32_Process -Filter "ProcessId = <pid>"` 看 `CommandLine` 里是不是 `agent_monitor.py`；③ 再 `GET /api/notify`。
**三者都对才算就绪**；否则换一个空闲端口显式拉起。

#### 🔴 完工通知：用"文件哨兵"，不要用阻塞等待

宿主（TRAE/WorkBuddy）的 `<task-notification>` 只对它们**自己管的进程**有效，对 CLI 子代理**根本不触发**。

```bash
# 派发后登记"我在等这几个任务"（立即返回，只写 waiting.json）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --wait-done "<ID1>,<ID2>,<ID3>"
# …然后正常结束本轮回复，用户可以继续派发/追加任务…
# 下一轮开头读一次通知（或读 tools/.agent_monitor/notify.json / GET /api/notify）：
"$PY" -c "import json,pathlib;n=json.loads((pathlib.Path(r'D:/AndroidStudioProjects/GIGI/tools/.agent_monitor/notify.json')).read_text('utf-8'));print(n['fired'])"
```

- 后台扫描循环每 3 秒比对一次；某 ID 达到**终态**（`done`/`blocked`/`dead`/`idle`）即写入 `notify.json` 的 `fired`，并从 `waiting.json` 移除。
- `notify.json` 结构：`{ "fired": { "<ID>": {state, state_label, at, age, hint, summary, pid, log} }, "updated_at", "pending": [...] }`。
- 任务久无活动且已被 24h 窗口过滤 ⇒ 超 30min 判 `idle` 并通知，**不会永远等下去**。

**🔴 主代理纪律（必须遵守）**

1. **禁止**用任何方式阻塞监听子代理（`TaskOutput` / `sleep` / 前台 `subprocess`）。
2. 派发 → `--ensure --serve 8787` → `--wait-done <IDs>` → **正常结束本轮**。
   - 若是**长链路**（后面还有第 2、3 棒）⇒ 改用 **§7.3 调度代理**：写接力清单 + `--daemon`，
     主代理同样立即结束本轮，**真正的交接由调度器后台完成**。
3. 收到用户新指令时，先读一次 `notify.json` + `--json`（都立即返回），再决定是否派新任务。
4. 用户催问进度 ⇒ 读 `--json` 回答，**不要**说"等我看看"然后卡住。

#### ⚠️ 本节的前提条件（务必先确认 `$MON` 真常驻）

文件哨兵方案**依赖 `$MON` 进程常驻**。历史教训：`$MON` 也是主代理派生的后台子进程，
**曾因 `DETACHED_PROCESS` 不脱离 Job Object，在启动它的那个 turn/会话结束时被连带终止**
（实测：端口不再监听、`state.json` 停更、`notify.json` 再没被写过 ⇒ 主代理读到**陈旧快照**，把已 `done` 的任务误判为 `stale`）。

**现已修复**（`CREATE_BREAKAWAY_FROM_JOB` + `--daemon`）。**判定与处置（每次都做，成本 1 次调用）**：

- 派发后跑 `--ensure --serve 8787`；返回里若已含 URL 即视为就绪。
- 需强确认时跑「端口可连 + 只有一个监视器进程」双查。
- 若发现 `$MON` 并未常驻 ⇒ 先 `--ensure` 拉起（或 `--daemon` 常驻）**再**启用文件哨兵；
  **未确认常驻前不要宣称文件哨兵可用**，否则只能回退到「同一 turn 内前台长循环阻塞等待」，
  且此时**不得**「正常结束本轮」——结束即杀子代理（见 §7.2）。

**回退方案**：`run_in_background=true` 派发后，用一条**前台长循环命令**
（轮询探针 `status` + `tasklist` 进程数，`sleep 20`，命中 `done`/`blocked`/进程归零即 break）把 turn 撑住直到收工。
该循环超过前台超时会被自动转后台，**不影响子代理存活**。

#### 7.2 「turn 结束连带杀死后台子进程」的实测证据

- 2026-09-24 18:37：4 个已派子代理**同时全灭**；其中 T1-AGE 首见 18:36:49、最后活动 18:37:14，**存活仅 25 秒**。
- 系统约束原文：*"exit the process as soon as the main agent finishes its turn, even if the process spawned background child processes"*。
- `Start-Process` / bash 调 powershell / `nohup`+`disown` 三条 detached 路线**在本环境全部被沙箱拦**，不要再试。
- 推论：**一轮只派 1–2 个能在本 turn 内跑完的任务**；长任务必须切成能在单次阻塞窗口内完成的小块。

#### 7.3 主从全自动交接：调度代理（§7.3）—— 一次解决三端 CLI + 双端宿主

> **用户选定方案 B**，理由：*"这理论可以一次解决 qoder/bailian/opencode 三端 cli + workbuddy/trae 双端 agent 工具的问题"*。本节是**通道无关、宿主无关**的通用描述。

**🔴 根因（先认清，否则会一直修错东西）**

主代理**不是常驻进程**，只是一次 API 调用；turn 一结束进程即退出。
⇒「子代理完工 → 唤醒主代理」这一跳**在架构上不可能由推送完成**（没有可推送的目标）。
`notify.json` 是**拉取式**设计，只有主代理**下次运行时**才会读它。
⇒ 唯一自动衔接的办法：把「守候 + 交接」职责**下放到独立常驻进程**。

```
主代理：派第 1 棒 + 写接力清单 ──► 结束 turn（用户可追加）
  调度器（常驻）：守候第 N 棒完工 ──► 自动派第 N+1 棒 ──► …
                          全部完工 ──► 写 finished.json（+ 可选拉起收尾代理）
主代理下次运行：读 finished.json / --status 做独立验收
```

```bash
# ① 写接力清单 .task/chains/<CHAIN_ID>.json（见下方格式）
# ② 🔴 首选：用计划任务拉起（父进程链 = svchost ← services ← wininit，真脱离宿主）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --chain <CHAIN_ID> --daemon-task
# ②' 次选：进程级脱离（⚠️ 本机实测**活不过 turn**，见下方警告）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --chain <CHAIN_ID> --daemon
# ③ 看进度（非阻塞，一次调用即返回）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --status [--chain <CHAIN_ID>]
# ④ 停止（杀调度器 pid + 注销计划任务）
"$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --stop [--chain <CHAIN_ID>]
"$PY" "D:/AndroidStudioProjects/GIGI/tools/task_scheduler.py" --stop-all
```

**🔴🔴 警告：`--daemon`（进程级脱离）在本机不足以跨 turn 存活 —— 必须用 `--daemon-task`（2026-09-25 实测）**

| 方式 | 创建标志 | 实测结果 |
| :-- | :-- | :-- |
| `--daemon` | `DETACHED_PROCESS \| NEW_PROCESS_GROUP \| NO_WINDOW \| CREATE_BREAKAWAY_FROM_JOB` | 本机宿主 Job **拒绝 breakaway**（`[WinError 5] 拒绝访问`）⇒ 自动降级为**去掉 breakaway** 的 `detached`。<br>而 `DETACHED_PROCESS` **只脱离控制台、不脱离 Job Object** ⇒ **turn 结束时调度器连同它派出的子代理一起被杀死** |
| `--daemon-task` ✅ | 写 `_launch.cmd` 包装脚本 + `schtasks /Create /SC ONCE /ST 00:00 /TR <launcher>` + 立即 `/Run` | 进程树 = `cmd.exe ← svchost.exe ← services.exe ← wininit.exe`，**真脱离**，可跨 turn 存活 |

**🔴 验活判据（`--status` 说"存活"**不够**）**：
```powershell
# 必须补一步：追溯父进程链到 svchost
$id=<调度器pid>; while ($id) { $p=Get-CimInstance Win32_Process -Filter "ProcessId = $id"; "$($p.ProcessId) $($p.Name)"; $id=$p.ParentProcessId }
```
只到 `cmd.exe` / `python.exe` 就说明**还在宿主 Job 里** ⇒ 迟早被带走；必须看到 `svchost.exe`（其父 `services.exe`）。
⚠️ 用 `--daemon-task` 时父链里**有 `cmd.exe` 是正常的**（它是 `_launch.cmd` 的解释器），
**关键看 `cmd.exe` 的上一层是不是 `svchost.exe`**。

**`_install_task` 的四个必踩坑（已在脚本内修，勿改回）**：
1. `/TR` **不能塞完整命令行**（嵌套引号被 `schtasks` 解析坏，任务建了但跑不起来）⇒
   写一个 `_launch.cmd` 包装脚本，`/TR` **只指向脚本路径**。
2. 包装脚本必须 **CRLF + GBK**（`encoding="gbk", newline=""`），否则 Windows 批处理解析异常。
3. `/SC ONCE /ST 00:00` 是"过去的时刻" ⇒ 建完必须**立刻 `/Run`** 才真的跑；`/ST` 不能省略。
4. 创建与启动要**分步检查 returncode**，否则失败被静默吞掉。
5. `--stop` 必须**同时**杀 pid **和** `schtasks /Delete /TN GIGIChain_<ID> /F`，
   否则 `--daemon-task` 模式下"停止"不完整（计划任务还在，会被再次触发）。

**子代理与调度器的 Job 关系（2026-09-25 实测修正，比旧描述窄很多）**：
`dispatch()` 里派子代理用的是 `creationflags=CREATE_NO_WINDOW`，**没有 breakaway** ⇒ 子代理留在调度器的 Job Object 内。

**但实测 T6–T9（四个并发子代理）全部存活并正常推进**，父链为：
```
qoderclicn.exe(T6/T7/T8/T9) ← python.exe(38128 调度器) ← svchost.exe ← services.exe ← wininit.exe
```
原因：调度器自身已通过 `--daemon-task` 脱离宿主 Job ⇒ **它的 Job Object 不属于宿主** ⇒
子代理跟着这个"外来的" Job 走，不会被宿主 turn 收尾带走。

⇒ 真实边界只有两条：
1. ✅ **宿主 turn 结束不会波及**（实测通过）；
2. ⚠️ **杀掉调度器会连带杀掉它正在派的子代理**（因为共享该调度器的 Job）。
   这就是**为什么禁止用 `--stop` 去"清理"**（见下方红线）——它会连在跑的子代理一起端掉。

（第 1 棒由主代理直派时又是另一种情况：子代理父进程随 turn 消失，但子代理自身独立存活——
实测 `T5c` 父进程 34308 早已退出，它仍继续跑了 20+ 分钟。）
若要让子代理彻底独立于调度器，可在 `dispatch()` 里也加 breakaway + 降级重试——
**改前先实测，别盲目套用**（本机宿主 Job 拒绝 breakaway，降级后与现状无实质差别）。

**🔴🔴 红线：不要用 `--stop` 去"清理"任何链** —— 它会**连带端掉该链正在跑的子代理**。
`--stop` 只在两种情况下用：① 该链已确认**没有**在跑的子代理，② 收工时按顺序停。
判断"有没有在跑"**不能只看 `--status`（status=running 是链级）、也不能只看探针**，
必须 `--json` 看每个 step 的 pid，再 `Get-Process -Id <pid>` 确认进程真在。

**接力清单格式（`.task/chains/<CHAIN_ID>.json`）**

```json
{
  "id": "T6-CHAIN",
  "cwd": "D:/AndroidStudioProjects/GIGI",
  "final_agent": { "id": "VERIFY-FINAL",
                   "dispatch": ".task/dispatch/VERIFY-FINAL.txt",
                   "model": "Qwen3.8-Max" },
  "steps": [
    { "id": "T5b",         "wait_only": true },
    { "id": "T6",          "dispatch": ".task/dispatch/T6.txt",
      "model": "Qwen3.8-Flash", "depends_on": ["T5b"] },
    { "id": "V6",          "dispatch": ".task/dispatch/V6.txt",
      "model": "Qwen3.8-Max", "depends_on": ["T6"] }
  ]
}
```

- `wait_only: true` = **已有任务，只等它完工，不重新派发**（用于把"正在跑的"接上链条）。
- `depends_on` = 依赖的棒次 ID；未满足则本轮跳过。
- `final_agent` = 全部棒次完成后自动拉起的"收尾代理"（可选）。

**安全边界（硬性，写脚本时就定死）**

| 边界              | 说明                                                                                                        |
| :-------------- | :-------------------------------------------------------------------------------------------------------- |
| **只交接、不验收**     | 调度器**绝不**代替主代理做质量/验收判定。它只判"完工/未完工"，质量归主代理。                                                                |
| **命令无关、通道无关**   | 调度器只做「探测状态 → 执行一条 dispatch 命令」。换 CLI、换模型、换宿主都**只改常量**。                                                     |
| **有副作用，隔离放**    | 与只读观测的 `agent_monitor.py` 分工不同：monitor 只读看板 + 完工哨兵；scheduler **真的拉起下一棒**。两者独立、互不依赖。                        |
| **有限重试，坏产物不下传** | 每棒最多重试 1 次；重试仍失败（`stale`/`dead`）⇒ **整链置 `blocked` 并停止**，绝不无脑往下派。任务自报 `blocked` 亦同。                          |
| **只写自己的状态**     | 只写 `.task/chains/` 下的状态文件与 `.agent_work/*.log`；不改工程源码。                                                     |

**落地坑（实测，改脚本前必读）**

1. **探针 `status` 有多种写法**：`status: done` 与 markdown 列表 `- status: done` 都常见 ⇒
   解析正则必须容错：`^[\s>*-]*status\s*[:：]\s*[\`*]*([A-Za-z\_]+)`。
2. **任务 ID 常带后缀**：真实探针名可能是 `T4b-GUARD.md` 而派单里写 `T4b` ⇒
   需要**三级回退解析**（精确同名 → 以 `<tid>-` 开头 → 以 `<tid>` 结尾），多候选取 mtime 最新。
3. **`CREATE_BREAKAWAY_FROM_JOB` 会 `[WinError 5] 拒绝访问`**：宿主 Job 不一定允许 breakaway。
   ⇒ 脚本已**自动降级**：先试 `base|breakaway`，`OSError` 再去掉 breakaway 重试；仍不行走 `--daemon-task`。
4. **状态落盘要"每条链一个子目录"**（`.task/chains/<ID>/state.json`），否则多条链互相覆盖；
   `--status` 要优先读 `state.json`（运行时状态），而非接力清单本体（输入）。
5. **`schtasks /TR` 嵌套引号会被解析坏** ⇒ `/TR` 里路径务必用双引号包住并整体作为一个参数传入。
6. **🔴🔴 `wait_only` 会对"假未收工"死等（重要）**：
   常见事故 —— 子代理**已把报告完整写盘，却在"更新探针"前终止**，探针永久停在 `status: doing`。
   此时 `wait_only` 棒次**永远不会满足**，整链静默卡死（`--status` 显示 `running` 但无进展）。
   **处置（启动链条前必做）**：对每个 `wait_only` 棒次，**核对产物而非探针** ——
   报告/证据文件存在且章节完整 ⇒ 由主代理把探针修正为 `done`（并注明"按交接点落在文件上判定"）**再**启动链条。
   **教训**：探针是"代理的自述"，产物才是"事实"；**判完工一律只认产物**。
7. **菱形依赖（并行分支）已支持**：`depends_on` 用"依赖 ID ∈ done 集合"判定 ⇒
   `A → (B ∥ C) → D` 天然可用；同一扫描轮内会**依次派发所有就绪棒次**（真并行）。
   ⚠️ 前提：**并行棒次的"独占文件集必须互斥"**。本工程实例：T5b/T5c/T5d 三弹窗各占 `ui/dialogs/<各自>/`，零交集。

### 其他要点

- **🔴 派发后必须执行一次 `--ensure`**：幂等，已运行则只打印"界面已在运行"直接返回。端口默认 8787（被占自动 +1）。
- `state.json` 每条任务含 `state` / `state_label` / `age` / `tool_pid` / `log_tail` / `exit_hint` / `probe_*` / `evidence_files`。
- **判活口径与 §7 一致**（进程存活 × 会话/日志新鲜度 × 证据新增，三者同时停滞 > 30min 才判 `stale`），**只认产物**。
  - 🔴 **`sess_ts` 只能做「单向救援」，不能做「抬升依据」**：主判据只用 `probe_ts`/`log_ts`/`evidence_ts`（自身产物）；
    会话 mtime 仅在**本已判成 stale** 时用它把任务"救回" running。
    若把 `sess_ts` 直接并进判定依据，**已结束的任务会集体诈尸**（任务 ID 会出现在任何会话文本里，
    子串匹配无法区分"这个会话在跑这个任务"与"这个会话提到了这个任务"）。
    **自查口诀**：`running` 的条目**必须全部 `proc_bound=True`**；凡出现 `pid=None` 却在「进行中」，就是 `sess_ts` 又被当成了判定依据。
  - **看到"疑似卡死"时的核查顺序**：① `tasklist` 看 pid 是否真在；② 看 `$SESS` transcript mtime（**最灵敏**）；
    ③ 才看探针；④ **绝不用 `.log` 大小判活**。若 ②在动 ⇒ **不是卡死**，是子代理没遵守增量落盘，**不要当成死亡补派**。
- **🔴 打不开界面**：本机装了代理软件（`127.0.0.1:7897`）。虽然系统 `ProxyEnable=0`，
  但代理客户端设的 `HTTP_PROXY/HTTPS_PROXY`（或 TUN 模式）会把 `127.0.0.1` 也塞进代理链路 ⇒ 页面打不开。
  用 **`--open`** 打开（它显式清掉代理变量并设 `NO_PROXY=127.0.0.1,localhost`）；或在浏览器里把 `127.0.0.1`/`localhost` 加入**代理绕过列表**。
- 输出目录 `tools/.agent_monitor/`（`state.json` / `waiting.json` / `notify.json` / `events.jsonl` / `server.log` / `_launch.cmd`），**已 gitignore，勿提交**。
- **🔴 进程枚举绝不起子进程**：早期版本每 3 秒 `subprocess.run(["powershell", …])` 枚举进程，
  Windows 上未设 `CREATE_NO_WINDOW` ⇒ **用户看到"大量 PowerShell 窗口被反复拉起又关闭"**。
  现已改为 **ctypes 直调 Win32**（`CreateToolhelp32Snapshot` + `NtQueryInformationProcess` 取命令行），
  **零子进程、零窗口**，还快了近两个数量级。**改这段前先读源码注释，别再退回 PowerShell 方案。**
- 改动 `is_port_free` / `_probe_our_service` 前先读注释（两处各踩过一次坑，都导致过重复起实例）。
- 监视器自身**不派发、不改任何工程文件**，只写它自己的输出目录。

## 8. 中途死亡 → 立刻补派接手

- 发现手段只有两个：**探针状态 + 进程存活**。
- **先分型再处置**：日志结尾是网络/通道类报错（`Unable to connect… Check your network`）⇒ **通道故障，成果都在**，补派成本低；"想不通/改不动" ⇒ 要换方案。
- 补派单必须写"**现状 + 只做收尾 + 别扩大范围**"，附已完成部分的证据路径与 commit hash；
  **并显式点名"严禁重做 A/B/C、不要覆盖既有证据"**——否则接手代理会从头再来，既烧额度又可能把已验证据覆盖成未核验数据。
- ⚠️ **前任探针可能"声称了并不存在的东西"**（死亡时探针常停在半句，写着"PASS（详见 §X）"而该章节/引用的文件根本没写）。
  接手时**必须逐个核对探针提到的每个证据文件是否真实存在**，存在性不符的结论一律降级为"无书面支撑"。
- 交接点必须落在**文件**上（探针 + commit），不靠对话记忆。

### 给"正在运行"的子代理下裁决：走它的探针文件

CLI 子代理是 `-p` 一次性会话，**没有消息通道**；但探针是它必然读写的东西。做法：
① 裁决写成独立文件 `$TASK/progress/<ID>-ADJUDICATION.md`（起因 / 判据口径 / 逐行命中原文 / 更正建议 / "已完成的改动一律保留"）；
② 在**探针首部置顶**一行指引（不要只追加到末尾）：`> 🔴 主代理裁决 ADJ-1 已下达 —— 动手前必读 <路径>：要点 <一句话>`；
③ 同时就地更正派单 `.txt`（供 `-v2` 重派时口径一致）并登记进协调文件。
**残余风险**：它若 `Write` 整文件覆盖探针，指引会丢 ⇒ 裁决必须独立成文件，探针只是指路牌。
**何时改"杀掉 + `-v2` 重派"**：已产出实质改动或长时间无产出；若尚未动过任何目标文件（逐个 `stat -c '%y'` 确认），重派代价同样很低，可优先重派。

## 9. 并行与冲突

- **按文件归属独占切分**；共享文件（公共 util、被多处复用的资产）**串行**或指定单一代理。
- 派发前对照 `.task/任务清单-main.md` 的**文件占用锁 + 在跑探针**；冲突则串行。
- 一旦并发写同一文件：逐项核验两处改动是否都还在（`grep` 关键符号 + 看提交内容），不能假定"没报错就没事"。
- **不边验边改**：实现 → 冻结 → 静态验收 → 动态验收 → 汇总 → 统一修复 → 复验；静/动双轨由**非实现者**执行。

### 🔴 改前闸门（每个改文件的派单都要写）

```bash
git status --porcelain -- <你独占的每个文件>
# 非空 → 立刻停手：贴完整 diff、探针写 blocked、报主代理裁决
# 空   → 正常推进
```

目标文件上可能压着**用户手改**或**另一个在飞代理的在途改动**，盲目覆盖 = 静默数据丢失且事后极难归因。闸门把"静默覆盖"变成"可检测的 blocked"。

**🔴 闸门口径：开在「本任务实际要动的文件集」，不要开在父目录**
并行任务共处同一工作树是常态，父目录级闸门会被兄弟任务的在途改动误伤
（本工程实例：T5b/T5c/T5d 三弹窗并行，闸门只开在自己的 `ui/dialogs/<各自>/` 目录，不要开在 `ui/dialogs/`）。
正确写法：`git status --porcelain -- $(find <目标目录> -name '<目标 glob>')`。

### 多目标扇出：用模板生成派单，不要手写 N 份

同一份清单要在 N 个目标上各跑一遍时，手写必然漂移（第 3 份开始漏条目）：
① 公共清单写成模板 `$TASK/dispatch/<NAME>-COMMON.tmpl`，占位符用 `@ID@` / `@TARGET@`；
② 用**工程内** Python 脚本读模板 → 替换 → 生成 N 份；
③ 生成后**必须校验无占位符残留**（`grep -l '@ID@\|@TARGET@' …`）；
④ 公共清单保证**可比性**，每路再加**一项专属深挖**保证不是重复劳动；
⑤ 每份派单都写**目标独占**。
三个坑：**替换顺序**、**前缀重复**、**脚本落工程内绝对路径**（bash 的 `/tmp` 与原生 Windows Python 的 `/tmp` 不是同一处）。

**🔴 扇出的隐藏杀手：共享可变状态**
「文件独占」只覆盖源码，N 路还会共同读写**运行时数据**（会话存储 / 共享数据文件 / 日志 / 缓存）。
本工程实例：模拟器/真机取证类任务若共用同一台设备，截图目录会互相覆盖 ⇒ 派单必须写"目标独占：只用 `<serial>`，截图落 `.task/evidence/<ID>/`"。

### ⚠️ dirty 文件归属核实

不要把"同一时间窗内的多个 dirty 文件"默认成同一组改动。
**逐个 `git diff` + 逐个 `git log -1 -- <file>` 确认归属**，拿不准就问用户。

## 10. 环境坑（GIGI 专属，派发前处理）

| 坑                                                       | 表现                                                                                                       | 处置                                                                                                                                       |
| :------------------------------------------------------ | :------------------------------------------------------------------------------------------------------- | :--------------------------------------------------------------------------------------------------------------------------------------- |
| 🔴 **JDK 选错**                                           | 手册 §2 明确：**必须 JDK 21** = `C:\Users\oscur\.jdks\ms-21.0.12.1`；**不可用** `"D:\Android Studio\jbr"`（JBR 25.0.2，Gradle 8.14.5 不认识 Java 25，KTS 配置阶段即抛 `IllegalArgumentException: 25.0.2`）；也不可用 `D:\Java`（1.8） | 派单里写死 `$env:JAVA_HOME="C:\Users\oscur\.jdks\ms-21.0.12.1"`                                                                                 |
| 🔴 **IDE 与 CLI 共用 `GRADLE_USER_HOME`** = `D:/Android Studio/gradle` | CLI 构建抛 `…registry.bin.lock (拒绝访问)` 并**静默中断**（日志**无** `BUILD FAILED`，只是停止增长，极易误判"还在跑"）；或构建卡住日志静默 | **派发构建类任务前先确认 AS 已关闭**（`tasklist \| grep studio64`）。判活：`stat -c '%Y' build.log` 两次不变 **且** IDE 进程在 ⇒ 就是它。**不要杀进程**，停手报 blocked |
| 🔴 **MSYS/Git-Bash 改写"远端路径"**                            | `adb push x /sdcard/…` 报 `remote secure_mkdirs failed`，实际被改写成 `C:/…/git/sdcard/…`；`uiautomator dump /sdcard/x.xml` 同样**静默写错位置** | 派单里要求子代理 `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`（副作用：本地路径参数需写成 `C:/…` 风格）                                                  |
| 🔴 **用 `sleep` 等长任务**                                   | `sleep 150; <cmd>` 整条返回空输出 + exit 1，后续命令根本没跑                                                              | **不要 `sleep` 轮询**。用 `run_in_background=true` + 等通知；`TaskOutput` 只在有通知时用一次                                                                   |
| CLI 内置 Git Bash staging 残留（`~/.qoder-cn/bin/git.staging`） | `EPERM: rm …staging` ⇒ python/git/adb **全不可用**（改了代码却无法验证/提交）                                              | **派发前主动清理**（删除无害，会重建）：`"$PY" -c "import shutil; shutil.rmtree(r'C:/Users/oscur/.qoder-cn/bin/git.staging', ignore_errors=True)"`         |
| 时间戳判活                                                   | `ls --time-style=+%H:%M:%S` 不显示日期，会把几天前的目录看成"刚刚"                                                             | 一律用 **epoch**：`stat -c '%Y' <path>` vs `date +%s`                                                                                        |
| `grep -c` 命中 0                                          | exit code = 1 ⇒ 用 `&&` 串联时**后续命令全被吃掉**（表现为"输出截断"）                                                         | 用 `;` 分隔或 `grep -c … \|\| true`                                                                                                          |
| 兄弟任务污染 `git status` 判据                                  | 派单写"只列出你的 N 个文件"，但同树有兄弟在途改动 ⇒ 判据客观不成立                                                                     | 改为"**过滤后**只剩你的 N 个文件"，并**预先告知**会看到哪些兄弟改动、属预期                                                                                              |
| git 对象库异常                                               | 仓库有 `.git/shallow` 或 `info/grafts`                                                                       | **绝不** `git gc` / `gc --prune=now` / `repack -a -d`。恢复：清失效 refs/index/reflog → `git add -A` → `git commit`                                          |
| 非 git 工程                                                | 无 `git status` 可比                                                                                        | 改用**改动前哈希快照**比对，等价实现闸门                                                                                                                    |

## 11. 协调网络（本工程启用）

| 文件                              | 作用                                                                        |
| :------------------------------ | :------------------------------------------------------------------------ |
| `.task/任务清单-main.md`           | **唯一权威**：事件总表（**只追加**）/ 文件占用锁（🔒🔓，行不删）/ 任务区块含完成标准 / Git 历史 |
| `.task/SUBAGENT_HANDBOOK.md`    | 子代理必读手册（比塞进提示词省 token、更一致）——**每个子代理开工必读**                                |
| `.task/progress/<ID>.md`        | 每任务探针                                                                     |
| `.task/dispatch/<ID>.txt`       | 派单（落盘后 `$(cat …)` 传入，换通道零成本接手）                                            |

- 主代理直接管理的子代理 ≤5，按文件独占切分；派单写清"你可改哪些 / 他人可能改哪些 / 哪些只读参照"。
- 教子代理再派子代理：手册里写明 CLI 调用姿势、必须后台、必须给独占文件与精确行号、子代理写自己的子探针由上层汇总。
- 接近上下文上限 → **必须**把总进度 + 在跑代理清单写入探针作为续接点。
- 分层是手段：若它让管理更复杂，**优先选对完成任务更有利的策略**。

## 12. 派发前自检 & 排障速查

**派发前**：目标文件未被占用 ✅ 已清理 staging ✅ 派单已落盘 ✅ 体量单文件/单改动 ✅ 后台派发 ✅
派单要素齐全（工作目录/技术栈/独占文件/目标独占/禁止项/验收门槛/署名/探针/限字）✅ 常量已实测 ✅
判据已自查（注释感知 + 与约束一致 + 权威脚本已声明）✅ 并发 ≤ `$CONC` ✅

| 症状                                                              | 先查                                                   | 处置                                                                            |
| :-------------------------------------------------------------- | :--------------------------------------------------- | :---------------------------------------------------------------------------- |
| 改了代码却无法验证/提交，EPERM                                              | CLI staging 残留                                       | 删目录后重派                                                                        |
| `Sorry, something went wrong`                                   | 体量是否过大                                               | 换 `-m` + **拆小任务**                                                             |
| `429`/quota/限流                                                  | 内置还是 CLI                                             | 内置 → 立刻切 CLI；CLI → 换 `-m` → 等 2–5 min → 文本通道分析兜底                              |
| 构建卡住、日志静默停止                                                     | AS 是否在跑（共用 `GRADLE_USER_HOME`）                        | 确认 IDE 已关；**不要**杀进程，报 blocked                                                  |
| KTS 配置阶段 `IllegalArgumentException: 25.0.2`                     | JAVA_HOME 是否写成了 JBR                                  | 改回 `C:\Users\oscur\.jdks\ms-21.0.12.1`                                          |
| 探针久未更新，疑似卡死                                                     | `date` 真实时间 + 三指标 + CPU 增量                           | **三者同时停滞**才终止；终止后立即按 §8 补派                                                    |
| 日志 0 字节                                                         | 管道缓冲                                                 | **不是死亡证据**，看三指标                                                               |
| 后台任务报 `failed`                                                  | 先复核产物（日志收工总结 / 探针 / 文件 mtime）                        | **假失败极常见**，只认产物                                                               |
| 产出与预期不符                                                         | `git show` 逐行 + 复算                                   | 不采信自述，独立复算                                                                    |
| 两代理产出互相覆盖                                                       | 文件占用锁                                                | 逐项核验两处改动是否都还在                                                                 |
| 脚本报"全量失败"或"完全一致"                                                | 先怀疑脚本                                                | 抽取后断言非空 + 打印行数；与 `HEAD` 旧 blob 等式比对                                           |
| 子代理因闸门 blocked                                                  | 闸门口径是否开在父目录                                          | 收窄到实际要动的文件集，只改该段重派 `-v2`                                                      |
| Hook 日志安静                                                       | Hook 边界                                              | `Stop`/`SubagentStop` 卡死时永不触发，不能据此判死                                          |
| `Provider not found: openrouter`                                | 是否设了 `OPENROUTER_API_KEY`                            | 未设/未导出 ⇒ opencode 不认该 provider；设了再列模型                                         |
| opencode 报 `UnknownError`、DEBUG 里是 `ProviderModelNotFoundError` | 该模型是否已注册                                             | 写进 `~/.config/opencode/opencode.jsonc` 的 `provider.openrouter.models`（见 §2.1） |
| 想知道子代理"到底干成什么样了"                                                | 先 `"$PY" "D:/AndroidStudioProjects/GIGI/tools/agent_monitor.py" --json` | 界面没起就先 `--ensure --serve 8787`；**不要**为看进度去阻塞等                                     |

## 13. 本文件的写入纪律

**只写本工程特有且长期成立的东西**：技术栈常量、路径映射、命令约定、验收门槛、取证常量、本机环境坑。
**不写**：某次任务的 ID、某一批改动的细节、临时结论、只在一个任务里成立的事实（那些留在 `.task/dispatch/` 与 `.task/progress/` 里）。

**与其它文件的分工**：
- `.task/SUBAGENT_HANDBOOK.md` = 子代理的**技术栈/构建/设计红线**（每个子代理开工必读，本文件不复制其内容）。
- `skills/task-execution/SKILL.md` = **派不派 / 怎么分档 / 怎么验收**（本文件不复制）。
- 设计权威 = `D:\EmulatorShared\GIGIWeb\2026-09-24-gigi-android-native-design.md`；参照实现 = `D:\EmulatorShared\GIGI\web`（**均为只读参照物，禁止写入**）。
