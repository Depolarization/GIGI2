# V6A-P0 探针 — 冷启动全页卡"加载中"根因修复 + 添加账户返回键

status: doing

## 开工前取证
- 改前闸门：`git diff --quiet -- AppContainer.kt AppGate.kt LoginScreen.kt` → exit=0 ✅
- 语义核实（CredentialStore.kt）：
  - `adoptActiveCookie(uid,…)` L124 以 loginInfo.gameUid 作 `StoredAccount(uid = uid, …)` 落盘；
  - `activateAccount` 用的就是 `account.uid`（AppContainer L100）；
  - `activeUid()`（store L69）已经 `isSafeUid` 且"存在于索引"双重过滤 ⇒ refreshAccounts 产出的
    `_activeAccountUid` 要么是 store 验证过的账户 uid，要么是 null。
  ⇒ **account.uid == gameUid 成立**，启动播种 `updateSession(activeAccountUid)` 语义正确。
- 环境：JAVA_HOME 必须显式指 `C:/Users/oscur/.jdks/ms-21.0.12.1`（PATH java=1.8）。

## A（P0-1）启动播种
GateViewModel.init：refreshAccounts 后，hasCookie 且 activeAccountUid!=null → updateSession(uid)。
- 登出红线手推：播种仅发生在 init（VM 构造一次）。`logout()` 走 `removeAccount`→`refreshAccounts`→
  `updateSession(null)`，期间无任何路径再调播种；`sessionUid` 只有 updateSession/activateAccount 写，
  二者在 logout 后都以 null/next 账户为准 ⇒ 登出后不会被自动填回。✅
- 登录页（无凭据）不播种；旧版单槽（无账户索引）activeAccountUid=null，不播种，行为同现状。

## B（P0-2）observeMain 瞬时失败退避重试
- 抽出纯函数 `shouldRetryVerify(t, attempt, maxAttempts)`（GateViewModel companion，JVM 可测）：
  CancellationException/auth 失败 → false；IOException → true；其余非鉴权 → true；attempt>max → false。
- catch else 分支：1s/2s/3s 退避重试同一请求；每次失败 Log.w("GigiGate","verify retry n/3: 类:msg")
  （不打印 cookie/凭据/URL）。重试用尽 → verifiedForCurrentSession=false 复位，_uiState 保持
  Main(verified=false)，不回登录页。auth 分支行为不变。

## C（F1）BackHandler
LoginScreen：`BackHandler(enabled = addAccount) { onCancelAddAccount?.invoke() }`；
首登页（addAccount=false）不接管，系统默认退出保持。

## D 自审
- 返回键 navIcon 用 TextButton（M3 默认 minHeight 48dp，触摸达标）；间距 24/16/8dp 节奏一致；
  错误文案均走 colorScheme.error / onSurfaceVariant，无硬编码对比度问题（占位白底为扫码刻意设计，不动）。
- 未发现须修的语义不变类问题，故 D 无实际改动（不扩大）。

## 实现结果
- A ✅ GateViewModel.init L99-107：`seededSessionUid(hasCookie, activeAccountUid)` 非空即 updateSession。
  登出不回填：写入点穷举确认 `_sessionUid` 仅 updateSession/activateAccount 写；logout →
  updateSession(null) 后无自动路径再触发 init/播种。
- B ✅ observeMain 重构为 handleVerifyAttempt：runCatching 捕获 → Cancellation 重抛 →
  auth 失败走 handleAuthFailure（原分支逐行保留）→ 其余按 shouldRetryVerify 退避 1s/2s/3s，
  每次 Log.w("GigiGate","verify retry n/3: 类: msg")（无 cookie/URL）；用尽 → 复位标记、保持 Main。
- C ✅ LoginScreen：`BackHandler(enabled = addAccount && onCancelAddAccount != null)`，
  首登页不接管。AppContainer.kt 未改（根因在 Gate 侧修即可，容器保持单写者语义）。

status: done — BUILD SUCCESSFUL 150/0，三次提交 b13335a / 2d1d634 / efe6e68

## 测试
- 新建 app/src/test/java/com/gigi/tcg/ui/login/GateVerifyRetryTest.kt：
  B 的重试判定真值表；A 的播种判定纯函数 `seededSessionUid(hasCookie, activeUid)` 真值表
  （AppContainer/GateViewModel 在纯 JVM 构造不出：CredentialStore 需 Context，见 U3A2-WIRE.md §2；
  登出"不回填"以源码写入点穷举 + 真机步骤验证，见下）。
- 构建：`JAVA_HOME=… ./gradlew :app:testDebugUnitTest --rerun-tasks`

## 真机验证步骤（A 兜底证据）
1. 已登录态杀进程冷启动 → 主页应立即显示"账号内容区+加载中"，不再永久停在加载中转圈；
   logcat 过滤 GigiGate 无凭据类输出。
2. 断网冷启动 → 主页仍渲染（B 的重试 3 次后停在 Main(verified=false)），
   logcat 出现 3 行 `verify retry n/3`；恢复网络后切页/切账户可再次触发校验。
3. 主页菜单"退出登录"（最后一个账户）→ 回登录页，冷重启前 sessionUid 不应被自动填回
   （表现为退出后立即回到登录页且各页不再拉数据）。
