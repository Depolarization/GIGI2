# V7E-OVERLAY-RESET — 叠加层退出组合重置 LoginViewModel（消除残留 LoggedIn 重入闪退）

status: done

## 缺陷复核（本棒自行验证，对照源码）

- ✅ `viewModel(key = "login-add-account")`（AppGate.kt:325）的 `LocalViewModelStoreOwner` 默认
  ComponentActivity ⇒ VM 挂 Activity 级 ViewModelStore，**跨组合进出持久**，固定 key ⇒ 复用同一实例。
- ✅ `_uiState` 初值 `Checking()`（LoginViewModel.kt:72）。
- ⚠️ **修正派单表述**："只有一处产出 LoggedIn"不准确——实际有**两处**：
  - LoginViewModel.kt:89（init 分支：非 addAccount 且已有 sessionUid 时直接置 LoggedIn）
  - LoginViewModel.kt:207（finalize Success：扫码确认→凭据交换成功置 LoggedIn）
  5 步复现链依赖的是 :207 这条（叠加层登录成功后状态停在 LoggedIn），结论不受影响。
- ✅ `LaunchedEffect(loginState)`（AppGate.kt:346）首次组合必执行、lambda 捕获本次组合值不重读
  StateFlow ⇒ 第二次进入叠加层时立即消费残留 `LoggedIn` → `onLoggedIn` → `Main(addAccount=false)`
  → 叠加层同帧被摘除 ⇒ "添加账号页闪一下回主页"。
- ✅ `LaunchedEffect(loginViewModel) { begin() }` 救不了：begin() 把状态改 Checking() 只触发**下一帧**
  重组，同一帧内第二个 effect 已持有捕获值。
- ✅ `LoginViewModel.cancel()`（LoginViewModel.kt:118）：`generation++` + 取消 qrJob/finalizeJob +
  `_uiState.value = Checking()`——同步、幂等、无外部副作用，`onDispose` 可安全调用。

## Login 分支复核（要求 3）

- `addAccount()` 无 cookie 时只设 `Login()`（AppGate.kt:236，addAccount 默认 false）；
  `cancelAddAccount()` else 兜底同样设 `Login()`（AppGate.kt:252）。
  ⇒ **当前状态机确实无法产出 `GateUiState.Login(addAccount = true)`**。
- 但 `Login` 类的 `addAccount` 字段与 key 切换逻辑（AppGate.kt:355）仍在，且 Login 分支的
  key="login" VM 同样跨组合持久（登录成功→Main→登出再回 Login 时 init:89 也可能残留 LoggedIn）。
  ⇒ 仍按派单在 Login 分支加对称 DisposableEffect（防御性），理由如上。

## 步骤记录

- [x] 改前闸门：`git diff --quiet -- AppGate.kt` → 0（干净放行）
- [x] Main 叠加层加 DisposableEffect（begin() 之后，带 V7E 成因注释）
- [x] Login 分支加对称 DisposableEffect（防御性，理由见上）
- [x] 新增 import `androidx.compose.runtime.DisposableEffect`（复核：原文件确无，已加）
- [x] 新建 AppGateOverlayResetTest.kt（源码文本断言，3 用例）
- [x] 构建 + 全量单测：BUILD SUCCESSFUL；变异验证前后各跑一次全量均绿
- [x] 变异验证（见下）
- [x] 提交：生产码 + 测试分两次

## 5 步复现链（缺陷本体，探针留档）

1. 主页点"添加账号" → `Main(addAccount=true)` → 进入叠加层 → `viewModel(key="login-add-account")`
   取 Activity 级 ViewModelStore 中的 VM。
2. 扫码登录成功 → `finalize` Success（LoginViewModel.kt:207）→ VM 状态 = `LoggedIn(uid)`。
3. `LaunchedEffect(loginState)` → `onLoggedIn(uid)` → Gate 切 `Main(verified=true, addAccount=false)`
   ⇒ 叠加层退出组合，**VM 状态仍停在 LoggedIn，无人重置**（VM 实例跨组合持久）。
4. 第二次点"添加账号" → 同 key 返回**同一实例** ⇒ 本次组合 `loginState` 初始值即残留 `LoggedIn`。
5. `LaunchedEffect(loginState)` 首次组合必执行、捕获本次组合值不重读 StateFlow ⇒ 同帧立即
   `onLoggedIn` ⇒ 叠加层瞬时被摘除 ⇒ "添加账号页闪一下回主页"。
   （`LaunchedEffect(loginViewModel){begin()}` 虽声明在前、把状态改 `Checking()`，但 StateFlow 更新
   只触发下一帧重组，救不了同一帧已持捕获值的第二个 effect。）

## 为什么 DisposableEffect 能同时覆盖两条退出路径

`DisposableEffect(loginViewModel) { onDispose { loginViewModel.cancel() } }` 挂在叠加层组合块内部，
`onDispose` 在**该 effect 离开组合的任何路径**上执行——不依赖退出原因：

- **取消返回**：`onCancelAddAccount` 里已有 `loginViewModel.cancel()` + `cancelAddAccount()`
  置 `addAccount=false` ⇒ `if` 块整体离开组合 ⇒ onDispose 再兜一次 `cancel()`（幂等，双保险；
  即便将来某条取消路径漏调 cancel()，onDispose 也兜住）。
- **登录成功摘除**：`onLoggedIn` 切 `Main(addAccount=false)` ⇒ if 块离开组合 ⇒ onDispose 把残留
  `LoggedIn` 重置为 `Checking()` ⇒ 下次进入不再被 `LaunchedEffect(loginState)` 同帧消费。
- **将来新增路径**（切服 key 重建、登出连带等）：只要退出组合就重置，无需逐路径补刀。

`cancel()` 同步且幂等：`generation++`（弃迟到回调）+ 取消 qrJob/finalizeJob + `_uiState=Checking()`，
onDispose 时 VM 未 `onCleared`（Activity 级 store），调用安全。

## 变异验证（同 V6I-D 做法）

- 变异：两处 `onDispose { loginViewModel.cancel() }` → `onDispose { }`（Allow multiple，2 处替换）。
- 跑 `AppGateOverlayResetTest`：**BUILD FAILED，2/3 红** —
  `disposableEffectResetsViewModelOnDispose` 红（cancel 计数 0 ≠ effect 计数 2）、
  `mainBranchEffectIsInsideMainSection` 红（Main 区间 regex 匹配不到 cancel）；
  `bothBranchesResetTheViewModel` 绿（预期：该用例只防"整块删除 DisposableEffect"）。
- 还原：`git checkout -- AppGate.kt`（diff --quiet=0）→ 复跑该类 **BUILD SUCCESSFUL 全绿**；
  再全量 `assembleDebug + testDebugUnitTest --rerun-tasks` → **178 用例 / failures=0 / errors=0**
  （基线 171 + 本棒 3；并发棒另 +4，与本棒无关）。

## 结论

残留 `LoggedIn` 重入闪退已消除：Main 叠加层 + Login 分支各加一处退出组合重置 VM 的
`DisposableEffect`，回归锁测试防止后人删除。`LaunchedEffect(loginState)`、`addAccount()` /
`cancelAddAccount()` 状态机原样未动；未触碰 LoginViewModel.kt / LoginScreen.kt / GigiNavHost.kt。

status: done


## 提交交叉事故记录（共享 index 竞态）

- 生产码 commit `792c627` 干净（仅 AppGate.kt）。
- 我提交测试/探针时，并发棒 V7F 正持有 staged 文件；我的 test commit 先误裹了
  `V7F-RANK-ALIGN.md`，soft-reset 修正过程中 V7F 抢先 commit `f3ad97b`，把我的
  `AppGateOverlayResetTest.kt`（62 行）与 `V7E-OVERLAY-RESET.md`（88 行）扫进其 probe commit。
- 内容完整、署名 GIGI-Bot，工作树对 HEAD 干净、全量 178 用例全绿 ⇒ 不重写他人历史（并发下风险大于收益）。
- 教训：**共享工作树里 `git add` 后必须立刻 commit，且 commit 前 `git status --short` 复核 index**；
  多棒并发时 index 是公共的，别人 staged 的内容会被你的 commit 带走（反之亦然）。
