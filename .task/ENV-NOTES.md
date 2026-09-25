# 构建环境说明（主代理追加，所有在跑子代理必读）

## 🔴 JDK：PATH 上的 java 不可用，必须显式指定
- `java -version` → **1.8.0_503**（Java 8）⇒ Gradle 8.14.5 直接拒绝，无法构建。
- 系统还带一个 JBR 25（`jbr_dcevm-11.0.16` 那个才是 11，另有 Android Studio 自带的 25）⇒ Gradle 8.14.5 同样拒绝 25。
- **可用的 JDK**（已由 U4B-UIPOLISH 实测跑通）：
  - `C:/Users/oscur/.jdks/ms-21.0.12.1`  ← **推荐用这个**
  - `C:/Users/oscur/.jdks/jbr_dcevm-11.0.16`

推荐命令（可直接复制）：
```bash
cd D:/AndroidStudioProjects/GIGI && JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew :app:testDebugUnitTest --rerun-tasks
```
或
```bash
cd D:/AndroidStudioProjects/GIGI && JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew :app:assembleDebug --rerun-tasks
```
`gradle.properties` 里**没有** `org.gradle.java.home`，所以不设 JAVA_HOME 就会踩上面的坑。

## ⚠️ 并发构建假象（本工程已多次踩到）
- 三个子代理并发跑 Gradle 会互相触发缓存：`UP-TO-DATE` 可能是另一个代理刚编出来的，**不等于你的改动被编过**。
- **一律加 `--rerun-tasks`** 强制重跑。
- 若报错全部落在你**未独占**的文件上（AuthManager / AppContainer / LoginViewModel / LoginScreen 等），
  先确认自己独占的文件无错，再重跑一次；仍失败就在探针写"疑似并发干扰"并在报告里说明。
- `git status` 里看到别人的文件被改是正常的（并发所致），**不要裹挟进自己的 commit**，
  只 `git add` 你独占的文件路径。

## 当前在跑的三棒与文件占用
| 任务 | 独占文件 |
| --- | --- |
| U4A-QRRETRY | data/auth/AuthManager.kt、ui/login/LoginViewModel.kt、test/.../QrCreateRetryTest.kt |
| U3A2-WIRE-V2 | di/AppContainer.kt、data/repo/GigiRepository.kt、test/.../di/SessionRefreshWiringTest.kt |
| U4B-UIPOLISH | ✅ 已完成（commit 0b7b82a，只改了 ui/login/LoginScreen.kt） |

---

# V6 轮（2026-09-25 晚）文件占用表 —— 并发棒必读

🔴 **并发上限：同屏最多 4 个 Gradle 构建**。本机 24 逻辑核 / 23.6GB 内存，实测 4 棒并发时
可用内存已降到 ~5GB；再叠加会 OOM 或让 Gradle daemon 互锁。**看不到自己的文件就别开工。**

## 第一波（在跑）
| 任务 | 独占文件 |
| --- | --- |
| V6A-P0 | ui/login/AppGate.kt、ui/login/LoginScreen.kt、test/.../ui/login/GateVerifyRetryTest.kt |
| V6B-HOME | ui/screens/home/HomeRoute.kt、ui/screens/home/HomeViewModel.kt |
| V6C-RANK | ui/screens/rank/RankRoute.kt、ui/screens/rank/RankViewModel.kt |
| V6D-STATS | ui/screens/cardstats/CardStatsRoute.kt、ui/screens/cardstats/CardStatsViewModel.kt |

## 第二波（待发）
| 任务 | 独占文件 |
| --- | --- |
| V6E-WIKI | ui/screens/cardwiki/CardWikiRoute.kt、CardWikiViewModel.kt |
| V6F-COVER | ui/dialogs/cardcover/CardCoverSheet.kt、CardCoverViewModel.kt |
| V6G-PLAYER | ui/dialogs/playerdetail/PlayerDetailDialog.kt、ui/theme/Theme.kt、ui/theme/Color.kt |
| V6H-NAV | ui/navigation/GigiNavHost.kt、ui/components/Avatar.kt、ui/components/GigiToast.kt |

## 公共只读（谁都不许改，改了必冲突）
- `ui/components/StateViews.kt`（LoadingView / ErrorState / shimmer，U5 刚定稿）
- `ui/components/AppImage.kt`
- `di/AppContainer.kt`、`data/repo/GigiRepository.kt`、`data/auth/*`、`data/api/*`
- `build.gradle.kts` / `gradle/libs.versions.toml`（禁新增依赖）

## 跨棒已知交叉点（改之前先 grep，别假设）
- `gold`（主题色）被 HomeRoute / RankRoute / PlayerDetailDialog 共用 → 只能由 V6G-PLAYER 在
  主题层改，其余棒**不得**在自己的文件里硬编码金色。
- `LocalToast` 由 GigiNavHost 提供 → 卡面下载提示的层级问题：V6F 用"保存成功即 onDismiss"规避，
  V6H 若能在导航层把 toast 提到更高 z 序则一并加固；两者不冲突，**不要互相改对方的文件**。
- `PullToRefreshBox` 只加在 V6B/V6C/V6D 三处；**V6E 图鉴明确不加**（用户要求）。

---

# V6I 波（2026-09-25 深夜）文件占用表 —— 只修"明确有害"，风格偏好一律不动

来源：`.task/progress/V6H-AUDIT.md`（28 条）。**主代理逐条筛过**，只把"可用性/无障碍缺陷"转派；
风格替换类（换 SearchBar / PrimaryTabRow / AssistChip / 全局字号层级）**全部不派**。

| 任务 | 独占文件 |
| --- | --- |
| V6I-A-CONTRAST | ui/theme/Color.kt、ui/theme/Theme.kt、test/.../ui/theme/ContrastTest.kt（新建） |
| V6I-B-OVERFLOW | ui/screens/home/HomeRoute.kt、ui/screens/rank/RankRoute.kt、ui/dialogs/playerdetail/PlayerDetailDialog.kt |
| V6I-C-A11Y | ui/screens/cardstats/CardStatsRoute.kt、ui/screens/cardwiki/CardWikiRoute.kt、ui/login/LoginScreen.kt、ui/dialogs/about/AboutDialog.kt |

## 🔴 V6H-AUDIT 里被主代理**否决**的条目（不得照做）
- `[P2] LoginScreen.kt:186 二维码占位背景 Color.White → surface` —— **误报**。二维码底色的明度必须
  由载荷（浅色模块）决定，不由主题决定；深色模式换成 surface 会把对比度压到解码阈值以下 ⇒ **二维码不可扫**，
  而扫码是登录唯一入口。**保留 Color.White**。
- `[P2] LoginScreen.kt:166 Color.Black.copy(0.7f) → colorScheme.scrim` —— M3 `scrim` 在明/暗两档**都是纯黑**，
  改与不改等价，零收益。不改。
- `[P3] LoginScreen.kt:112 FilterChip 触摸目标 < 48dp` —— 误报。M3 Chip 内部已应用
  `minimumInteractiveComponentSize()`，触摸目标本就是 48dp；加 `heightIn(min=48.dp)` 只会破坏视觉。
- `[P2] Theme.kt:18 裸 lightColorScheme()/darkColorScheme() 紫色系` —— 真问题但**不派**：
  `GigiTheme` 的 `dynamicColor` 默认 **true**（Android 12+ 走动态取色，根本不吃这套）；
  且改 primary/tertiary 会全局改变观感，**用户未要求改配色**。留待用户决策。

## 对比度复算（主代理独立算过，供 V6I-A 校验）
| 色值 | 对白底 WCAG 对比度 | 判定 |
| --- | --- | --- |
| `WinColor 0xFF3F8F5F` | **3.96:1** | ❌ 低于 AA 4.5 |
| `LoseColor 0xFFC25656` | **4.41:1** | ❌ 低于 AA 4.5 |
| `GoldColorLight 0xFF8A6A16`（V6G 已加） | **5.06:1** | ✅ |
| 拟新增 `WinColorLight 0xFF2E7D4F` | **5.05:1** | ✅ |
| 拟新增 `LoseColorLight 0xFFB84A4A` | **5.10:1** | ✅ |

---

# V7 波（2026-09-25 23:40）文件占用表 —— 用户真机复测反馈

| 任务 | 独占文件 | 核心 |
| --- | --- | --- |
| V7A-RANK-TAB | `ui/screens/rank/RankRoute.kt` | tab indicator 改 `currentPage` + `currentPageOffsetFraction` 插值 |
| V7B-ADDACCOUNT | `ui/login/AppGate.kt`（+ 必要时 `test/.../GateSeedAndRetryTest.kt`） | `Main` 加 `addAccount` 字段，`GigiNavHost` 常驻、登录页作叠加层 |
| V7C-STATS-TITLE | `ui/screens/cardstats/CardStatsRoute.kt` | `DetailGroup` 标题加 Bold，行 label 降 `bodySmall`+`onSurfaceVariant` |
| V7D-TOAST-POS | `ui/navigation/GigiNavHost.kt` | ✅ 已完成 `8581e0d` —— 回退 Popup，恢复 `snackbarHost` |

## 🔴 V7 轮的两条硬约束（后续棒必读）

1. **V7A 不要顺手改 `settledPage → selectTab` 那段**。
   `LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }.collect { viewModel.selectTab(...) } }`
   是**数据加载**语义：切 Tab 触发拉数据应在**落定后**（滑动过程中反复切页不该反复请求）。
   indicator 跟随滑动 ≠ 数据跟随滑动，两件事必须分开。
2. **V7B 不要采用"把 NavHostController 提升到 AppGate"的方案**。
   本棒用的是**叠加层**方案（`GigiNavHost` 始终渲染 + `LoginScreen` 覆盖其上），
   这样 `key(server to sessionUid)` 的"切服重置导航树"语义不被破坏。
   提升 controller 会让那个 `key` 失效，属于反向改动。

## 🔴 V7D 回退的教训（写下来避免下次再犯）

V6H 为解决"toast 被 ModalBottomSheet 遮挡"把 ToastHost 挪进 `Popup(BottomCenter)`，
结果引入**新回归**：Popup 的 BottomCenter 对齐**屏幕**底，丢掉 M3 `Scaffold` 对 `bottomBar` 的避让
⇒ snackbar 贴屏幕底、被手势条压住。
而遮挡问题**已由 V6F 的「保存成功即 onDismiss」规避**（sheet 先关、toast 后出）。
⇒ **修 A 问题时引入的方案，必须回头检查它在 B 场景下是否造成回归**；
能靠"时序规避"（先关 sheet 再提示）解决的，不要动渲染层级。

## ⚠️ 悬而未决：排行榜前三名数字左边距（等用户确认）

真机 dump（`.task/evidence/V6/ui3.xml`）：
```
'1' left=13px  '2' left=13  '3' left=13     ← 前三名
'4' left=40px  '5' left=43  '6' left=44 … '10' left=44   ← 4 名以后
（宽度全部 77px = 28dp）
```
**前三名比 4 名以后左移 27~31px（约 10~11dp）**，但 `RankRoute.kt` 里两者走**同一个 `RankRow`**
（`itemsIndexed` → `RankRow(rank = index + 1)`），源码无分支差异 ⇒ 无法从代码推导。
可能是 `uiautomator` 对 Compose `Text(textAlign = Center)` 语义 bounds 的测量偏差，也可能是真实布局差异。
⇒ **已回问用户三种可能理解，拿到答复再派棒。禁止猜着改。**

---

# V7 第一波收工 + V7E 波（2026-09-25 23:50）

## V7 第一波四棒验收结论（主代理独立审查，逐行 diff）

| 棒 | commit | 结论 |
| --- | --- | --- |
| V7A-RANK-TAB | `5a5caeb` / `60ca680` | ✅ `selectedTabIndex` 由 `settledPage` 改 `currentPage`；indicator 用 `TabPosition.left/right`（**Dp**）按 `currentPageOffsetFraction` 线性插值；`settledPage → selectTab` 数据加载语义原样保留（仅补注释）；子代理自行纠正了派单里"Dp 当 px"的错误（javap 实测 material3 1.3.2） |
| V7B-ADDACCOUNT | `0d9f958` / `228ec24` / `060aba6` | ✅ 见下（含一个遗留缺陷 → V7E） |
| V7C-STATS-TITLE | `227dedd` / `6dc6854` | ✅ `DetailGroup` title 加 `FontWeight.Bold`；行 label 降 `bodySmall` + `onSurfaceVariant`；行数值保持 `bodyMedium`；组间距 8→12dp；与 `PlayerDetailDialog.SectionTitle` 惯例一致 |
| V7D-TOAST-POS | `8581e0d` / `3c9081c` | ✅ `snackbarHost = { ToastHost(toastController) }`；Popup 块与 import 零残留；V6H 四项改动全部保留 |

### V7B 验收细节（已核对）
- ✅ `Main(val verified: Boolean, val addAccount: Boolean = false)` 落地
- ✅ `addAccount()` 不再 `invalidateVerification()`；无 cookie 时兜底 `Login()`
- ✅ `cancelAddAccount()` 只 `copy(addAccount = false)`；**不碰** `verifiedForCurrentSession`、不调 `invalidateVerification()`
- ✅ `GigiNavHost()` 在 Main 分支 `Box` **首子**、调用点位置固定 ⇒ 组合身份稳定
- ✅ 调用签名全部核对通过：`LoginScreen(viewModel, expiredNotice, addAccount, onCancelAddAccount)`、
  `LoginViewModel.factory(app, addAccount)`、`LoginUiState.LoggedIn(uid)`
- ✅ 新增 2 条纯状态断言测试（`GateSeedAndRetryTest`）⇒ 用例数 169 → **171**（与子代理自述一致，交叉验证通过）

## 🔴 V7E 波占用表（2026-09-25 23:50）

| 任务 | 独占文件 | 核心 |
| --- | --- | --- |
| V7E-OVERLAY-RESET | `ui/login/AppGate.kt`（+ 新建 `test/.../AppGateOverlayResetTest.kt`） | 叠加层退出组合时 `DisposableEffect { onDispose { loginViewModel.cancel() } }` 重置 VM，消除残留 `LoggedIn` 重入 |

### 通道回退实录（2026-09-25 23:5x）—— 额度限制是「模型档级」不是「账号级」

- 首次派 V7E 用 `-m DeepSeek-Flash` ⇒ **13 秒内立即失败**，`.agent_work/V7E-OVERLAY-RESET.log` 只有一行：
  `You've reached your credit usage limit. Please upgrade your subscription plan to get more resources.`
- `--list-models` 实测只剩 **`Qwen3.8-Max` / `Qwen3.8-Flash`** —— DeepSeek 系已从该账号消失。
- 按 skill 回退链第 1 条「**先换 `-m` 再判死**」：极小探针
  `-p -m Qwen3.8-Flash "只回复两个字：可用"` ⇒ **正常返回「可用」** ⇒ 换档重派 V7E **成功**。
- ⇒ 结论：credit 报错**只针对该档**；**不要**试一档就宣布 `blocked(credit)`，
  也不要对**同一档**盲目连续重试。已同步修正用户级 skill `subagent-cli-ops` §2.2 第 4 点
  （原文自相矛盾，说"不要换模型硬试"，与同节第 1 点冲突，实测已证伪）。

## 🔴 V7B 遗留缺陷：残留 `LoggedIn` 导致"第二次添加账号"闪退

**这不是 V7B 引入的**（旧路径 `Login(addAccount = true)` 用同一个 VM key `"login-add-account"`，同样复用），
V7B 也没消除它。V7E 修。

关键事实：
- `LoginViewModel` 挂在 **Activity 级 `ViewModelStore`**（`LocalViewModelStoreOwner` 默认是 ComponentActivity）
  ⇒ **跨组合进出持久**，`key` 固定 ⇒ 每次进入叠加层拿到的都是**同一个实例**。
- `_uiState` 初值 = `Checking()`（LoginViewModel.kt:72），**唯一**产出 `LoggedIn` 的地方是 LoginViewModel.kt:89。
- `LaunchedEffect(loginState)` 的 lambda **捕获本次组合的 `loginState` 值**，不重读 StateFlow；
  且 `LaunchedEffect` 在**首次组合必定执行一次**。

5 步复现链：
1. 主页点"添加账号" → `Main(addAccount = true)` → 进入叠加层 → 取 VM(key="login-add-account")
2. 扫码成功 → VM 状态 = `LoggedIn(uid)`
3. `LaunchedEffect(loginState)` → `onLoggedIn(uid)` → Gate 切 `Main(verified=true, addAccount=false)`
   ⇒ 叠加层退出组合，**但 VM 状态仍停在 `LoggedIn`**
4. **第二次**点"添加账号" → 同一个 VM 实例被复用 ⇒ 本次组合的 `loginState` 初始值 = 残留 `LoggedIn`
5. `LaunchedEffect(loginState)` 首次组合即执行，闭包捕获到 `LoggedIn` ⇒ **立即 `onLoggedIn`**
   ⇒ 叠加层被瞬时摘除 ⇒ 用户看到"添加账号"页**闪一下回主页**

为什么 `LaunchedEffect(loginViewModel) { begin() }` 救不了：它虽然按声明顺序先执行、把状态改成 `Checking()`，
但 StateFlow 更新只触发**下一帧**重组，救不了**同一帧**里已经在读捕获值的第二个 effect。

**修复方向**：`DisposableEffect(loginViewModel) { onDispose { loginViewModel.cancel() } }` ——
无论哪条路径退出叠加层（取消返回 / 登录成功摘除 / 将来新增路径）都重置 VM。
`cancel()` 是同步的（`generation++` + 取消在途 job + `_uiState.value = Checking()`），幂等、无副作用。

## 🔴 通用教训（写下来，同 V7D 教训同源）

**`viewModel(key = 固定字符串)` 得到的实例跨组合进出持久**。
凡"条件组合块内创建 VM + 该 VM 有终态（如 `LoggedIn`）"的写法，都必须考虑：
退出组合后状态残留 ⇒ 下次进入时 `LaunchedEffect(state)` 首次执行就消费到旧终态。
⇒ **要么在退出时重置，要么把终态做成一次性事件（Channel/SharedFlow）而非 state。**

---

# V7 第二波（V7E + V7F）收工验收（2026-09-26 00:0x）

## 验收结论

| 棒 | commit | 结论 |
| --- | --- | --- |
| V7E-OVERLAY-RESET | `792c627`（生产）/ `dc0bc7e`（测试+探针） | ✅ Main 叠加层与 Login 分支各加 `DisposableEffect(loginViewModel) { onDispose { loginViewModel.cancel() } }`，import 增删准确；`LaunchedEffect(loginState)` 与 `addAccount()`/`cancelAddAccount()` 状态机**零改动** |
| V7F-RANK-ALIGN | `b585199`（生产）/ `af11235`（测试）/ `6197c54`（探针） | ✅ 删 `Arrangement.spacedBy(4.dp)` 与 import `Arrangement`/`widthIn`，加 `Spacer`；槽位 `widthIn(min=28.dp)` → `width(32.dp)`；`padding(start=8.dp)` → `12.dp`；**V7A 的 TabRow/indicator 段零改动**，`medalColor` 取色未动 |

**用例数交叉验证通过**：V7B 收工 171 → V7E 新增 `AppGateOverlayResetTest`（3 用例）⇒ **174** → V7F 新增 `RankRowSpacingTest`（4 用例）⇒ **178**。
两条独立路径（子代理自述 / 主代理数 `@Test`）数字吻合。

## 🔴 V7E 修正了主代理派单的一处不准确表述（记录在案）

派单写"`LoggedIn` 只有一处产出点（LoginViewModel.kt:89）"——**不准确，实际两处**：
- `:89` —— `init` 分支：`if (addAccount || sessionUid == null) startQr() else LoggedIn(...)`
  ⇒ **`addAccount = true` 时不走这条**（叠加层 VM 不受影响）；但 `key = "login"` 的 VM（`addAccount = false`）
  在已有 sessionUid 时会走 ⇒ 这正是 Login 分支也需要对称 `DisposableEffect` 的实证理由。
- `:207` —— `finalize` Success（扫码确认 → 凭据交换成功）。

⇒ 5 步复现链依赖 `:207` 这条，**结论不受影响**。教训：派单里的"事实断言"必须精确到可 grep 验证，
不要凭 `grep | head -5` 的截断结果下"唯一"结论。

## 🔴 并发提交竞态事故（V7E ↔ V7F 共享 index）

**现象**：两棒并发时共用同一个 git index（工作树是共享的），发生两次互相裹挟：
1. V7E 的 test commit 误裹了 `V7F-RANK-ALIGN.md`（V7F 的探针）
2. V7E soft-reset 修正期间，V7F 抢先 commit，把 V7E 的 `AppGateOverlayResetTest.kt` 与
   `V7E-OVERLAY-RESET.md` 扫进了 V7F 的 probe commit

**结果**：V7E 判定"不重写他人历史"（并发下重写风险 > 收益），改为写探针说明 + 补一个 addendum commit。
**主代理核查结论**：主线 `dc0bc7e → 6197c54 → af11235 → b585199 → 792c627` **每个 commit 只含本棒文件**，
**最终无污染** ✅；`676ab08` / `f3ad97b` 是修正过程留下的**游离对象**（`git merge-base --is-ancestor` 返回非 0），
不在 HEAD 祖先链上，不影响主线（仅是 object store 垃圾，如需清理可日后 `git gc`）。

**教训（写进纪律）**：
- 共享工作树里 **`git add` 后必须立刻 `commit`**，中间不要插入任何耗时操作；
- **commit 前必须 `git status --short` 复核 index**，确认只有自己的文件是 staged；
- 多棒并发时 **index 是公共资源**——别人 staged 的内容会被你的 commit 带走（反之亦然）；
- 更稳妥的做法：并发棒改用 `git commit -- <自己独占的路径>`（pathspec 限定），
  从根上避免把别人的 staged 内容裹进来。

---

# 🔴🔴 V7G 波（2026-09-26 00:1x）—— V7A 引入的 indicator 宽度回归

## 现象（用户真机反馈）
> "排行榜的 indicator 有渲染问题，在手指触碰滑动时，indicator **覆盖了整个 tab**。"

静止正常、**手指拖动 pager 过程中**指示条变成横贯整个 TabRow 的一条线。

## 根因（javap 实证，不是推测）

`RankRoute.kt:114-118`（V7A 写的）：
```kotlin
val indicatorModifier = with(LocalDensity.current) {
    Modifier
        .offset { IntOffset(leftDp.roundToPx(), 0) }
        .width(rightDp - leftDp)      // ← 被父约束夹掉
}
```

**官方 `TabRowDefaults.tabIndicatorOffset` 的真实 modifier 链**
（`javap -c` 反编译 `androidx.compose.material3.TabRowDefaults$tabIndicatorOffset$2`，逐条方法调用）：

```
Modifier
    .fillMaxWidth()                                  // SizeKt.fillMaxWidth$default
    .wrapContentSize(Alignment.BottomStart)          // SizeKt.wrapContentSize$default
                                                     //   + Alignment$Companion.getBottomStart
    .offset { IntOffset(leftState.roundToPx(), 0) }  // OffsetKt.offset（lambda 版，与 V7A 同）
    .width(widthState)                               // SizeKt."width-3ABfNKs"
```
（顺带发现：官方内部还包了 `animateDpAsState` + `tabRowIndicatorSpec`，所以官方切 tab 时 indicator 自带动画。）

**差异只有前两步**。`TabRow` 测量 indicator 时传的是**固定宽度约束**（整行宽）；
`Modifier.width(dp)` 默认 `enforceIncoming = true` ⇒ 宽度被 `constrainWidth` **夹到整行宽度**
⇒ indicator 横贯整个 TabRow。官方靠 **`wrapContentSize(Alignment.BottomStart)`** 把父约束放宽
（`wrapContentSize` 测量子内容时用宽松 `Constraints()`），后续 `.width(w)` 才生效。

## 顺带修的第二处：反向滑动不跟手
`currentPageOffsetFraction` 真实范围是 **[-0.5, +0.5]**（`currentPage` = 最近页）。
原代码 `coerceIn(0f, 1f)` 把**反向滑动**的负 fraction 夹成 0 ⇒ 走 else 分支、indicator 不动，
等 `currentPage` 翻页后才从 0.5 插值 ⇒ **跳变**。
改为"连续页码"插值：`continuous = currentPage + currentPageOffsetFraction`，
`lo = floor`、`hi = lo+1`、`t = continuous - lo` ⇒ 天然支持双向。

## 🔴 通用教训（重要，会反复遇到）

**写自定义 `Modifier` 链去替换官方实现时，必须先把官方实现 javap 出来逐条对齐**，
不要凭"看起来差不多"就少写几步。本例少写的 `wrapContentSize` 表面无关（它只是对齐方式），
实际是**解开父约束的关键开关** —— 少了它，后面所有 `width/size` 都会被父约束夹掉。

**排查口诀**：自定义 modifier 的尺寸不生效 ⇒ 先怀疑**父约束**（`enforceIncoming` / `constrainWidth`），
再看有没有 `wrapContentSize` / `requiredWidth` 之类能解开或忽略约束的节点。

## V7G 波占用表

| 任务 | 独占文件 | 核心 |
| --- | --- | --- |
| V7G-TAB-INDICATOR | `ui/screens/rank/RankRoute.kt`（+ 新建 `test/.../RankTabIndicatorTest.kt`） | indicator modifier 链补齐 `fillMaxWidth()` + `wrapContentSize(Alignment.BottomStart)`；插值改连续页码（双向跟手） |

🔴 **不许去掉自定义插值**：改回官方静态 `tabIndicatorOffset` 会退回"indicator 不跟手"（用户上一次的投诉）。
🔴 **不许动 `settledPage → selectTab`**（硬约束 1）。

## V7G 收工验收（主代理独立复跑）

| 棒 | commit | 结论 |
| --- | --- | --- |
| V7G-TAB-INDICATOR | `1f0bc6c`（生产）/ `62f834b`（测试）/ `081e3c4`（探针） | ✅ modifier 链补齐 `fillMaxWidth()` + `wrapContentSize(Alignment.BottomStart)`（在 `offset{}` 之前）；插值改连续页码（双向跟手）；import 精准增删（+`wrapContentSize`，−`tabIndicatorOffset`，−`LocalDensity`）；`settledPage → selectTab` / `selectedTabIndex` / `Tab(selected)` / `RankRow` / `medalColor` **零改动** |

- 独立复跑：`assembleDebug + testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL in 10s**
- `.task/count-tests.py` → **30 类 / 184 用例 / failures=0 / errors=0 / skipped=0** ✅（基线 178 + 6）
- 变异验证：删 `wrapContentSize(Alignment.BottomStart)` → `RankTabIndicatorTest` **3 红**（核心三条），还原后 6/6 绿
- 文件边界：`git log --name-only` 确认每个 commit 仅含本棒独占文件（**已采纳 pathspec 限定提交**）
- 已装机（Redmi Note 7 `ac9bcc9a`）

**⚠️ 仍未做端到端渲染验证**：静态断言只能证明"链写对了"，证明不了"渲染对了"。
真机受限（MIUI 拒 `input tap`/`input swipe` 的 INJECT_EVENTS，且 `screencap` 对 Compose 返回纯黑），
`SecondaryIndicator` 又是无语义节点（`uiautomator dump` 看不到）⇒ **必须用户肉眼复测**。

---

# 🔴 守望脚本的状态解析坑（2026-09-26 00:22 实测）

## 现象
早期派出的守望进程（轮询 V7A~V7D 四棒是否落定）**空转了 5 分钟**才因 55 次上限自然退出，
期间每轮日志都显示 `V7A=done V7B=done（构建+171/171 V7C=done V7D=done` —— **四棒明明都 done，却从不判 ALL_SETTLED**。

## 根因
守望脚本里的解析是临时手写的：
```bash
grep -oE '^[[:space:]>*-]*(status|状态)[[:space:]]*[:：][[:space:]]*[`*]*[^ ]+' ... \
  | tail -1 | sed -E 's/.*[:：][[:space:]]*[`*]*//'
```
`[^ ]+` 会**吞到第一个空格为止**。而 `V7B-ADDACCOUNT.md` 首行是
`status: done（构建+171/171 单测全绿；commit …）` ⇒ 提取出 `done（构建+171/171`，
**不等于 `done`** ⇒ `case "$s" in done|已完成|blocked|阻塞)` 不匹配 ⇒ `alldone` 永远为 0。

## 🔴 纪律
**守望/轮询脚本不要自己写状态解析** —— 直接复用 `tools/task_scheduler.py` 的 `_read_probe()`：
它的正则 `([A-Za-z_\u4e00-\u9fff]+)` **只取字母/汉字前缀**，`done（说明）` 能正确解析成 `done`，
且它已处理"取最后一条匹配"（探针惯例：开工 doing → 收工追加 done）与中文同义词归一。
自己手写 `[^ ]+` / `\w+` 之类一定会踩"状态行带尾随说明文字"这个坑。

（同类坑的另一面早已在 `task_scheduler.py` 的 `_read_probe()` 注释里记录：取首条会误判、
取 `[^ ]+` 会吞尾随文字。**这次是守望脚本没跟上调度器的修复**。）
